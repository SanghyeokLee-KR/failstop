package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.TestPartitions;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandDraft;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_XRP;
import static kr.exchange.engine.lease.ReservationFixture.XRP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 호가·티커 REST 와 구독 스냅샷의 seq·epoch 선택(API 설계 §4-A-2·§4-A-4, S6 설계 4-6). 실제 DB 에 접수·정산하고 게시기가 낸
 * 본문을 미러에 넣어 본다. 그 마켓을 쥔 노드는 자기 카운터·기간 epoch·그 기간의 엔진 호가를 낸다. 쥐지 않은 노드는 자기 카운터와
 * 옛 엔진 호가를 내지 않고, 중계 모드에서 소유 노드 하트비트와 미러 따라잡음을 채우면 미러, 아니면 DB 집계와 seq 0 이다.
 */
class MarketViewIntegrationTest {

    private static final OrderCommand.Side BUY = OrderCommand.Side.BUY;
    private static final OrderCommand.Side SELL = OrderCommand.Side.SELL;
    // 이 시험은 미러 사용 조건 가운데 하트비트 나이를 보지 않는다(MarketMirrorTest 가 본다). 실시간 시계로 DB 조회를 사이에 두므로
    // 넉넉히 둔다.
    private static final long MAX_SILENCE_MS = 5_000;

    @Test
    void leaderFollowerDemotedAndRepromotedNodesPickTheirSource() throws Exception {
        try (PublisherRig rig = new PublisherRig("s6_view_simple", false)) {
            long alice = rig.f.user("alice", new BigDecimal("100000000"));
            long bob = rig.f.user("bob", new BigDecimal("100000000"));
            rig.submit(rig.f.limit(KRW_BTC, alice, BUY, "50000", "0.3"));
            rig.submit(rig.f.limit(KRW_BTC, alice, BUY, "50100", "0.2"));
            MarketDirectory.Market btc = rig.markets.byId(KRW_BTC);
            long e1 = rig.term.epoch();

            Map<String, Object> leader = views(rig, id -> rig.term).orderbook(btc);
            assertEquals(2L, num(leader, "seq"), "리더가 자기 카운터를 내지 않았다");
            assertEquals(e1, num(leader, "epoch"));
            assertEquals(2, units(leader), "리더가 그 기간의 엔진 호가를 내지 않았다");

            MarketViews followerViews = views(rig, id -> null);
            Map<String, Object> follower = followerViews.orderbook(btc);
            assertEquals(0L, num(follower, "seq"), "팔로워가 자기 카운터를 냈다");
            assertEquals(e1, num(follower, "epoch"), "팔로워 epoch 가 리스 epoch 가 아니다");

            // 다른 노드가 이끄는 동안 호가가 바뀌었다. 이 노드의 엔진 사본은 e1 그대로다.
            rig.submitElsewhere(rig.f.limit(KRW_BTC, bob, BUY, "49000", "0.1"));
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> units(followerViews.orderbook(btc)) == 3);
            // 대조군: 기간을 보지 않던 사본 조회(종전 depth())는 강등된 노드에 옛 호가를 준다.
            assertEquals(2, rig.data.depthForTerm(KRW_BTC, e1).bids().size(),
                    "대조군이 성립하지 않는다. 옛 기간의 사본이 옛 호가가 아니다");
            LeaderTerm repromoted = TestPartitions.term("matching-engine", e1 + 5);
            Map<String, Object> fresh = views(rig, id -> repromoted).orderbook(btc);
            assertEquals(3, units(fresh), "재승격 직후 첫 배치 전에 옛 기간의 엔진 호가를 냈다");
            assertEquals(e1 + 5, num(fresh, "epoch"));

            List<Map<String, Object>> tickers = followerViews.tickers(List.of(btc));
            assertEquals(0L, num(tickers.get(0), "seq"), "팔로워 티커가 자기 카운터를 냈다");
            assertEquals(e1, num(tickers.get(0), "epoch"));
        }
    }

    @Test
    void brokerNonOwnerUsesMirrorOnlyUnderAllThreeConditions() throws Exception {
        try (PublisherRig rig = new PublisherRig("s6_view_broker", true)) {
            long alice = rig.f.user("alice", new BigDecimal("100000000"));
            rig.submit(rig.f.limit(KRW_BTC, alice, BUY, "50000", "0.3"));
            MarketDirectory.Market btc = rig.markets.byId(KRW_BTC);
            long e = rig.term.epoch();
            JsonNode published = rig.messages("/topic/orderbook.KRW-BTC").get(0);
            byte[] body = rig.json.writeValueAsBytes(published);
            long seq = published.path("seq").asLong();

            MarketMirror mirror = new MarketMirror(() -> System.nanoTime() / 1_000_000);
            OwnerBeats beats = new OwnerBeats(() -> System.nanoTime() / 1_000_000);
            MarketViews nonOwner = new MarketViews(rig.data, new ChannelSequences(), leaseEpoch(rig), PartitionPlan.single(),
                    id -> null, new MarketViews.Relay(mirror, beats, MAX_SILENCE_MS), rig.json);
            beats.onSubscribed();
            mirror.update(MarketMirror.Channel.ORDERBOOK, "KRW-BTC", e, seq, body);

            assertEquals(0L, num(nonOwner.orderbook(btc), "seq"), "소유 노드 하트비트 없이 미러를 썼다");
            assertEquals(1, nonOwner.fallbacks(MarketMirror.Channel.ORDERBOOK, MarketViews.Fallback.OWNER_SILENT));
            for (int i = 0; i < 3; i++) {
                beats.onHeartbeat("matching-engine", e, new RelayEnvelope.Heartbeat(
                        Map.of("KRW-BTC", new long[] {e, seq}), Map.of()));
            }
            Map<String, Object> mirrored = nonOwner.orderbook(btc);
            assertEquals(seq, num(mirrored, "seq"));
            assertEquals(e, num(mirrored, "epoch"));
            assertEquals(published.path("orderbook_units").size(), units(mirrored));
            assertTrue(!mirrored.containsKey("type") && !mirrored.containsKey("stream"), "REST 모양이 아니다");

            // 구독 즉시 스냅샷도 같은 선택을 쓴다.
            List<Message<?>> out = new CopyOnWriteArrayList<>();
            ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel();
            outbound.subscribe(out::add);
            new OrderbookSnapshotOnSubscribe(rig.markets, nonOwner, rig.json, () -> outbound)
                    .onApplicationEvent(subscribeEvent("/topic/orderbook.KRW-BTC"));
            JsonNode snapshot = rig.json.readTree((byte[]) out.get(0).getPayload());
            assertEquals("snapshot", snapshot.path("stream").asText());
            assertEquals(seq, snapshot.path("seq").asLong());
            assertEquals(e, snapshot.path("epoch").asLong());

            MarketMirror oldTimeline = new MarketMirror(() -> System.nanoTime() / 1_000_000);
            oldTimeline.update(MarketMirror.Channel.ORDERBOOK, "KRW-BTC", e - 1, seq + 100, body);
            MarketViews mismatched = new MarketViews(rig.data, new ChannelSequences(), leaseEpoch(rig),
                    PartitionPlan.single(), id -> null, new MarketViews.Relay(oldTimeline, beats, MAX_SILENCE_MS), rig.json);
            Map<String, Object> db = mismatched.orderbook(btc);
            assertEquals(0L, num(db, "seq"));
            assertEquals(e, num(db, "epoch"), "DB 집계 응답의 epoch 가 리스 epoch 가 아니다");
            assertEquals(1, mismatched.fallbacks(MarketMirror.Channel.ORDERBOOK, MarketViews.Fallback.EPOCH_MISMATCH));
        }
    }

    /**
     * 비소유 노드의 티커는 seq·epoch 만 미러에서 가져오고 24시간 값은 요청 때 DB 로 계산한다(§4-A-4). 체결이 24시간 창 밖으로 밀려나면
     * 마지막 WS 티커 본문은 옛 값이 되지만 소유 노드와 비소유 노드의 REST 는 같은 새 값을 낸다.
     */
    @Test
    void nonOwnerTickerRecomputesDayStatsLikeTheOwner() throws Exception {
        try (PublisherRig rig = new PublisherRig("s6_view_ticker", true)) {
            long alice = rig.f.user("alice", new BigDecimal("100000000"));
            long bob = rig.f.user("bob", new BigDecimal("100000000"));
            rig.f.deposit(bob, XRP, new BigDecimal("1000"));
            long now = System.currentTimeMillis();
            long aging = now - Duration.ofHours(24).toMillis() + 6_000;
            // 곧 창 밖으로 밀려날 체결(가격 900)과 창 안의 체결(가격 700).
            rig.submit(limitAt(rig, KRW_XRP, alice, BUY, "900", "1", aging - 1_000));
            rig.submit(limitAt(rig, KRW_XRP, bob, SELL, "900", "1", aging));
            rig.submit(limitAt(rig, KRW_XRP, alice, BUY, "700", "2", now - 1_000));
            rig.submit(limitAt(rig, KRW_XRP, bob, SELL, "700", "2", now));
            MarketDirectory.Market xrp = rig.markets.byId(KRW_XRP);
            JsonNode lastWsTicker = last(rig.messages("/topic/ticker.KRW-XRP"));
            assertEquals("900", lastWsTicker.path("high_24h").asText(), "전제: 게시 때는 900 체결이 창 안이다");
            long e = rig.term.epoch();

            MarketMirror mirror = new MarketMirror(() -> System.nanoTime() / 1_000_000);
            OwnerBeats beats = new OwnerBeats(() -> System.nanoTime() / 1_000_000);
            mirror.update(MarketMirror.Channel.TICKER, "KRW-XRP", e, lastWsTicker.path("seq").asLong(), null);
            beats.onSubscribed();
            MarketViews owner = views(rig, id -> rig.term);
            MarketViews nonOwner = new MarketViews(rig.data, new ChannelSequences(), leaseEpoch(rig),
                    PartitionPlan.single(), id -> null, new MarketViews.Relay(mirror, beats, MAX_SILENCE_MS), rig.json);

            Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).until(() ->
                    "700".equals(owner.tickers(List.of(xrp)).get(0).get("high_24h")));
            for (int i = 0; i < 3; i++) {
                beats.onHeartbeat("matching-engine", e, new RelayEnvelope.Heartbeat(Map.of(),
                        Map.of("KRW-XRP", new long[] {e, lastWsTicker.path("seq").asLong()})));
            }
            Map<String, Object> ownerTicker = owner.tickers(List.of(xrp)).get(0);
            Map<String, Object> foreign = nonOwner.tickers(List.of(xrp)).get(0);
            assertEquals(lastWsTicker.path("seq").asLong(), num(foreign, "seq"), "비소유 노드가 미러 seq 를 쓰지 않았다");
            assertEquals(e, num(foreign, "epoch"));
            for (String field : List.of("high_24h", "low_24h", "acc_trade_volume_24h", "acc_trade_price_24h", "change",
                    "change_rate", "change_price", "trade_price")) {
                assertEquals(ownerTicker.get(field), foreign.get(field), field + " 가 소유 노드와 다르다");
            }
            // 대조군: 마지막 WS 티커 본문을 그대로 내는 판은 창 밖으로 밀려난 체결을 담은 옛 값이다.
            assertNotEquals(ownerTicker.get("high_24h"), lastWsTicker.path("high_24h").asText());
            assertNotEquals(ownerTicker.get("acc_trade_volume_24h"), lastWsTicker.path("acc_trade_volume_24h").asText());
        }
    }

    private static OrderIntake limitAt(PublisherRig rig, int market, long user, OrderCommand.Side side, String price,
                                       String qty, long engineTs) {
        long id = rig.f.nextOrderId();
        String coid = "s6-view-" + id;
        OrderIntake bare = new OrderIntake(market, CommandDraft.CommandType.NEW, user, coid, id, engineTs, id, side,
                OrderCommand.OrdType.LIMIT, null, new BigDecimal(price), new BigDecimal(qty), null,
                UUID.nameUUIDFromBytes(coid.getBytes(StandardCharsets.UTF_8)), ReservationFixture.RATE,
                ReservationFixture.RATE);
        return ReservationFixture.withCheck(bare, ReservationFixture.check(bare));
    }

    private static MarketViews views(PublisherRig rig, java.util.function.IntFunction<LeaderTerm> openTerm) {
        return new MarketViews(rig.data, rig.sequences, leaseEpoch(rig), PartitionPlan.single(), openTerm, null, rig.json);
    }

    /** 비소유 노드가 읽는 리스 epoch. 이 노드의 리스 서비스는 기간이 없으므로 leader_lease 행을 읽는다. */
    private static EpochSource leaseEpoch(PublisherRig rig) {
        return new EpochSource(TestPartitions.unstarted(PartitionPlan.single()), rig.f.app) {
            @Override
            public long epoch(int marketId) {
                return rig.f.count("SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'");
            }
        };
    }

    private static SessionSubscribeEvent subscribeEvent(String destination) {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        a.setSessionId("s1");
        a.setSubscriptionId("sub-1");
        a.setDestination(destination);
        return new SessionSubscribeEvent(new Object(), MessageBuilder.createMessage(new byte[0], a.getMessageHeaders()));
    }

    private static long num(Map<String, Object> body, String key) {
        return ((Number) body.get(key)).longValue();
    }

    private static int units(Map<String, Object> body) {
        return ((List<?>) body.get("orderbook_units")).size();
    }

    private static JsonNode last(List<JsonNode> list) {
        return list.get(list.size() - 1);
    }
}

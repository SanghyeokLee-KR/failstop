package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.TestPartitions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 노드별 호가·티커 미러와 그 사용 조건(S6 설계 4-5, 4-6, 6-2). 시계를 주입하고 DB 는 쓰지 않는다. DB 집계는 seq 0 으로만 표시되는
 * 고정 호가로 흉내 낸다.
 */
class MarketMirrorTest {

    private static final String P = "matching-engine";
    private static final String BTC = "KRW-BTC";
    private static final long E = 42;

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final ObjectMapper json = new ObjectMapper();
    private final MarketMirror mirror = new MarketMirror(clock::get);
    private final OwnerBeats beats = new OwnerBeats(clock::get);
    private final MarketDirectory.Market market = new MarketDirectory.Market(1, BTC, "ACTIVE", (short) 2, "BTC",
            "Bitcoin", 8, (short) 1, "KRW", 0, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);

    @Test
    void onlyLargerEpochSeqReplaces() {
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, 5, 10, body(5, 10));
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, 5, 9, body(5, 9));
        assertEquals(10, mirror.get(MarketMirror.Channel.ORDERBOOK, BTC).seq(), "같은 epoch 의 작은 seq 가 덮었다");
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, 5, 10, body(5, 10));
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, 6, 1, body(6, 1));
        assertEquals(6, mirror.get(MarketMirror.Channel.ORDERBOOK, BTC).epoch(), "큰 epoch 가 seq 와 무관하게 바꾼다");
        assertNull(mirror.get(MarketMirror.Channel.TICKER, BTC), "채널을 섞었다");
        mirror.update(MarketMirror.Channel.TICKER, BTC, 6, 3, null);
        assertNull(mirror.get(MarketMirror.Channel.TICKER, BTC).body(), "티커는 본문을 두지 않는다");
    }

    @Test
    void smallerEpochIsIgnoredForTenSecondsThenReplaces() {
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, 9, 100, body(9, 100));
        clock.addAndGet(9_000);
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, 8, 5, body(8, 5));
        assertEquals(9, mirror.get(MarketMirror.Channel.ORDERBOOK, BTC).epoch(), "10초 안의 작은 epoch 가 덮었다");
        clock.addAndGet(1_001);
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, 8, 5, body(8, 5));
        assertEquals(8, mirror.get(MarketMirror.Channel.ORDERBOOK, BTC).epoch(),
                "10초 넘게 갱신되지 않은 항목이 되돌아간 타임라인 값에 묶였다");
    }

    @Test
    void reachableNeedsThreeHeartbeatsInFiveSeconds() {
        beats.onSubscribed();
        heartbeat(Map.of(BTC, new long[] {E, 1}));
        clock.addAndGet(1_000);
        heartbeat(Map.of(BTC, new long[] {E, 1}));
        assertFalse(beats.reachable(P, E), "하트비트 둘로 닿는다고 봤다");
        clock.addAndGet(1_000);
        heartbeat(Map.of(BTC, new long[] {E, 1}));
        assertTrue(beats.reachable(P, E));
        assertFalse(beats.reachable(P, E + 1), "다른 epoch 의 하트비트로 닿는다고 봤다");
        clock.addAndGet(3_001);
        assertFalse(beats.reachable(P, E), "셋째 하트비트가 창을 벗어났는데 닿는다고 봤다");
        assertEquals(1_000_000 + 5_000, beats.lastReachableAt(P, E), "마지막으로 닿던 때는 셋째로 최근인 하트비트 + 5초다");
    }

    /** 메모리 경보 중에는 다시 붙을 때마다 첫 프레임 하나만 통과해 하트비트가 드문드문 온다(증거 R11a). */
    @Test
    void sparseHeartbeatsEveryFourSecondsNeverCountAsReachable() {
        beats.onSubscribed();
        for (int i = 0; i < 10; i++) {
            heartbeat(Map.of(BTC, new long[] {E, 1}));
            assertFalse(beats.reachable(P, E), "4초 간격 하트비트 " + (i + 1) + "번째에 닿는다고 봤다");
            clock.addAndGet(4_000);
        }
    }

    /** 하트비트 간격 x 3 + 발행 대기 상한이 닿음 창 5초를 넘으면 살아 있는 소유 노드도 닿지 않는 때가 생긴다. 기동에서 거부한다. */
    @Test
    void heartbeatIntervalMustKeepALiveOwnerReachable() {
        Duration maxAge = Duration.ofSeconds(2);
        OwnerBeats.requireReachableInterval(Duration.ofSeconds(1), maxAge);
        OwnerBeats.requireReachableInterval(Duration.ofMillis(500), maxAge);
        OwnerBeats.requireReachableInterval(Duration.ofMillis(1_500), Duration.ofMillis(500));
        for (Duration[] refused : new Duration[][] {{Duration.ofMillis(1_001), maxAge}, {Duration.ofSeconds(2), maxAge},
                {Duration.ofSeconds(1), Duration.ofMillis(2_001)}, {Duration.ZERO, maxAge}}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> OwnerBeats.requireReachableInterval(refused[0], refused[1]));
            assertTrue(e.getMessage().contains("owner-heartbeat"), e.getMessage());
        }

        // 거부하는 2초 간격은 제때 오는 하트비트로도 주기의 절반은 닿지 않는다. 1초 간격은 늘 닿는다.
        assertEquals(0, unreachableSteps(1_000));
        assertTrue(unreachableSteps(2_000) > 0, "2초 간격이 늘 닿았다");
    }

    /** 제때 오는 하트비트를 간격마다 받으며 셋째 하트비트 뒤 100ms 마다 닿음을 본다. 닿지 않은 표본 수를 낸다. */
    private int unreachableSteps(long intervalMs) {
        OwnerBeats b = new OwnerBeats(clock::get);
        b.onSubscribed();
        int unreachable = 0;
        for (int beat = 0; beat < 10; beat++) {
            b.onHeartbeat(P, E, new RelayEnvelope.Heartbeat(Map.of(), Map.of()));
            for (long t = 0; t < intervalMs; t += 100) {
                if (beat >= 2 && !b.reachable(P, E)) {
                    unreachable++;
                }
                clock.addAndGet(100);
            }
        }
        return unreachable;
    }

    @Test
    void viewUsesMirrorOnlyWhenSubscribedReachableAndCaughtUp() {
        MarketViews views = views();
        beats.onSubscribed();
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, E, 50, body(E, 50));
        assertEquals(0L, seq(views), "하트비트 전에 미러를 썼다");
        assertEquals(1, views.fallbacks(MarketMirror.Channel.ORDERBOOK, MarketViews.Fallback.OWNER_SILENT));

        threeBeats(Map.of(BTC, new long[] {E, 50}));
        assertEquals(50L, seq(views));

        threeBeats(Map.of(BTC, new long[] {E, 52}));
        assertEquals(0L, seq(views), "하트비트가 알린 seq 보다 뒤처진 미러를 썼다");
        assertEquals(1, views.fallbacks(MarketMirror.Channel.ORDERBOOK, MarketViews.Fallback.BEHIND));
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, E, 52, body(E, 52));
        assertEquals(52L, seq(views), "다음 게시가 따라잡았는데 쓰지 않았다");

        beats.onUnsubscribed();
        assertEquals(0L, seq(views), "구독이 끊긴 동안 미러를 썼다");
        assertEquals(1, views.fallbacks(MarketMirror.Channel.ORDERBOOK, MarketViews.Fallback.RELAY_DOWN));
    }

    @Test
    void heartbeatWithoutThatMarketOrEpochMismatchKeepsDbAggregate() {
        MarketViews views = views();
        beats.onSubscribed();
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, E, 7, body(E, 7));
        threeBeats(Map.of());
        assertEquals(0L, seq(views), "하트비트에 그 마켓 항목이 없는데 미러를 썼다");
        assertEquals(1, views.fallbacks(MarketMirror.Channel.ORDERBOOK, MarketViews.Fallback.BEHIND));

        MarketMirror stale = new MarketMirror(clock::get);
        stale.update(MarketMirror.Channel.ORDERBOOK, BTC, E - 1, 900, body(E - 1, 900));
        MarketViews old = new MarketViews(dbOnly(), new ChannelSequences(), leaseEpoch(E), PartitionPlan.single(),
                id -> null, new MarketViews.Relay(stale, beats), json);
        threeBeats(Map.of(BTC, new long[] {E, 1}));
        assertEquals(0L, seq(old), "리스 epoch 와 다른 미러를 썼다");
        assertEquals(1, old.fallbacks(MarketMirror.Channel.ORDERBOOK, MarketViews.Fallback.EPOCH_MISMATCH));
    }

    /** 다시 붙으면 새 세대의 하트비트를 셋 받기 전에는 쓰지 않는다. 같은 seq 를 알리면 다시 쓰고, 더 크면 다음 게시까지 기다린다. */
    @Test
    void newGenerationWaitsForHeartbeatsAndComparesAnnouncedSeq() {
        MarketViews views = views();
        beats.onSubscribed();
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, E, 30, body(E, 30));
        threeBeats(Map.of(BTC, new long[] {E, 30}));
        assertEquals(30L, seq(views));

        beats.onUnsubscribed();
        beats.onSubscribed();
        assertEquals(0L, seq(views), "세대가 바뀐 직후 첫 하트비트 전에 미러를 썼다");
        heartbeat(Map.of(BTC, new long[] {E, 30}));
        assertEquals(0L, seq(views), "새 세대 하트비트 하나로 미러를 썼다");
        clock.addAndGet(1_000);
        heartbeat(Map.of(BTC, new long[] {E, 30}));
        clock.addAndGet(1_000);
        heartbeat(Map.of(BTC, new long[] {E, 30}));
        assertEquals(30L, seq(views), "변화가 없던 마켓은 새 세대의 하트비트로 곧바로 다시 써야 한다");

        beats.onUnsubscribed();
        beats.onSubscribed();
        threeBeats(Map.of(BTC, new long[] {E, 33}));
        assertEquals(0L, seq(views), "끊긴 동안 게시가 있었는데 끊기기 전 미러를 썼다");
        mirror.update(MarketMirror.Channel.ORDERBOOK, BTC, E, 33, body(E, 33));
        assertEquals(33L, seq(views));
    }

    @Test
    void ownerSeriesServesOwnCounterAndTermEpochNotMirror() {
        ChannelSequences seqs = new ChannelSequences();
        seqs.next("orderbook." + BTC);
        seqs.next("orderbook." + BTC);
        kr.exchange.engine.LeaderTerm term = TestPartitions.term("matching-engine", 77);
        MarketViews owner = new MarketViews(dbOnly(), seqs, leaseEpoch(E), PartitionPlan.single(), id -> term,
                new MarketViews.Relay(mirror, beats), json);
        Map<String, Object> body = owner.orderbook(market);
        assertEquals(2L, ((Number) body.get("seq")).longValue());
        assertEquals(77L, ((Number) body.get("epoch")).longValue());
        assertEquals(0, owner.fallbacks(MarketMirror.Channel.ORDERBOOK, MarketViews.Fallback.OWNER_SILENT),
                "소유 노드가 미러 판정을 탔다");
    }

    private MarketViews views() {
        return new MarketViews(dbOnly(), new ChannelSequences(), leaseEpoch(E), PartitionPlan.single(), id -> null,
                new MarketViews.Relay(mirror, beats), json);
    }

    private long seq(MarketViews views) {
        return ((Number) views.orderbook(market).get("seq")).longValue();
    }

    private void heartbeat(Map<String, long[]> orderbook) {
        beats.onHeartbeat(P, E, new RelayEnvelope.Heartbeat(orderbook, Map.of()));
    }

    private void threeBeats(Map<String, long[]> orderbook) {
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(1_000);
            heartbeat(orderbook);
        }
    }

    private static byte[] body(long epoch, long seq) {
        return ("{\"schema_version\":\"1.0\",\"type\":\"orderbook\",\"market\":\"KRW-BTC\",\"stream\":\"realtime\","
                + "\"timestamp\":\"2026-09-25T00:00:00.000Z\",\"epoch\":" + epoch + ",\"seq\":" + seq
                + ",\"total_ask_size\":\"1\",\"total_bid_size\":\"1\",\"orderbook_units\":[{\"ask_price\":\"101\","
                + "\"ask_size\":\"1\",\"bid_price\":\"99\",\"bid_size\":\"1\"}]}").getBytes(StandardCharsets.UTF_8);
    }

    static MarketDataService dbOnly() {
        return new MarketDataService(null) {
            @Override
            public Depth dbDepth(int marketId) {
                return new Depth(List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
            }
        };
    }

    static EpochSource leaseEpoch(long epoch) {
        return new EpochSource(TestPartitions.unstarted(PartitionPlan.single()), null) {
            @Override
            public long epoch(int marketId) {
                return epoch;
            }
        };
    }
}

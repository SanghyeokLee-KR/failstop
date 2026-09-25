package kr.exchange.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.core.OrderCommand;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 중계 모드의 캔들(S6 설계 4-10). 각 노드가 체결 메시지(자기 게시와 받은 봉투)를 보고, 이 노드에 캔들 구독이 있는 단위만 DB 의
 * 최근 버킷을 읽어 자기 내장 브로커로 보낸다. 중계로 다시 보내지 않는다. 실행기가 넘치면 버리고 센다.
 */
class CandleFanoutTest {

    @Test
    void remoteTradeEmitsOnlySubscribedUnitsLocallyAndNeverRelays() throws Exception {
        try (PublisherRig rig = new PublisherRig("s6_candle_fanout", true)) {
            long alice = rig.f.user("alice", new BigDecimal("100000000"));
            long bob = rig.f.user("bob", new BigDecimal("100000000"));
            rig.f.deposit(bob, BTC, new BigDecimal("1"));
            rig.submit(rig.f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1"));
            subscribe(rig, "s1", "c1", "/topic/candle.1m.KRW-BTC");
            // 5m 구독자는 이 노드에 없다. 다른 노드의 구독은 이 노드에 보이지 않는다.
            rig.submit(rig.f.limit(KRW_BTC, bob, OrderCommand.Side.SELL, "50000", "0.1"));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                    !rig.messages("/topic/candle.1m.KRW-BTC").isEmpty());

            // 받은 체결 봉투를 흉내 낸다. 로컬 체결과 같은 경로로 1m 만 낸다.
            rig.candles.onTrade("KRW-BTC", rig.term.epoch());
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                    rig.messages("/topic/candle.1m.KRW-BTC").size() >= 2);
            JsonNode candle = rig.messages("/topic/candle.1m.KRW-BTC").get(0);
            assertEquals("candle", candle.path("type").asText());
            assertEquals("1m", candle.path("unit").asText());
            assertEquals(rig.term.epoch(), candle.path("epoch").asLong());
            assertTrue(candle.path("seq").asLong() > 0, "캔들 seq 는 버킷 최대 event_seq 다");
            assertTrue(rig.messages("/topic/candle.5m.KRW-BTC").isEmpty(), "구독 없는 단위를 냈다");

            rig.drainRelay();
            assertTrue(rig.transport.sent.stream().noneMatch(e -> e.dest() != null && e.dest().startsWith("/topic/candle.")),
                    "캔들을 중계로 보냈다");
            assertTrue(rig.transport.sent.stream().anyMatch(e -> e.trade()), "양성 축: 체결은 중계로 보냈어야 한다");
        }
    }

    @Test
    void saturatedExecutorDropsAndCounts() throws Exception {
        try (PublisherRig rig = new PublisherRig("s6_candle_overflow", false)) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            MarketDataService blocking = new MarketDataService(rig.f.app) {
                @Override
                public Map<String, Object> latestCandle(MarketDirectory.Market market, String unit) {
                    started.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }
            };
            for (String market : List.of("KRW-BTC", "KRW-ETH", "KRW-XRP")) {
                subscribe(rig, "s-" + market, "c-" + market, "/topic/candle.1m." + market);
            }
            try (CandleFanout fanout = new CandleFanout(rig.markets, blocking, rig.candleSubscriptions, rig.fanout, 1)) {
                fanout.onTrade("KRW-BTC", 1);
                assertTrue(started.await(5, TimeUnit.SECONDS));
                fanout.onTrade("KRW-ETH", 1);
                fanout.onTrade("KRW-ETH", 2);
                assertEquals(0, fanout.overflows(), "같은 마켓 대기 작업은 하나로 모은다");
                fanout.onTrade("KRW-XRP", 1);
                assertEquals(1, fanout.overflows(), "포화 때 버리고 세지 않았다");
                release.countDown();
            }
        }
    }

    private static void subscribe(PublisherRig rig, String session, String id, String destination) {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        a.setSessionId(session);
        a.setSubscriptionId(id);
        a.setDestination(destination);
        rig.candleSubscriptions.onSubscribe(new SessionSubscribeEvent(rig,
                MessageBuilder.createMessage(new byte[0], a.getMessageHeaders())));
    }
}

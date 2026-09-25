package kr.exchange.api.ws.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.ws.MarketMirror;
import kr.exchange.api.ws.WsFanout;
import kr.exchange.engine.PartitionPlan;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 중계 봉투의 인코딩·디코딩과 허용 모양(S6 설계 4-3, 6-2). */
class RelayEnvelopeTest {

    private static final byte[] BODY = "{\"type\":\"orderbook\",\"seq\":7}".getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void allowedShapesRoundTripWithHeadersAndBody() throws Exception {
        List<RelayEnvelope> allowed = List.of(
                RelayEnvelope.topic("node-a", "/topic/orderbook.KRW-BTC", "KRW-BTC", 42, 7, BODY),
                RelayEnvelope.topic("node-a", "/topic/ticker.KRW-ETH", "KRW-ETH", 42, 8, BODY),
                RelayEnvelope.topic("node-a", "/topic/trade.KRW-XRP", "KRW-XRP", 42, 90_000_000_001L, BODY),
                RelayEnvelope.topic("node-a", "/topic/candle.1m.KRW-BTC", "KRW-BTC", 42, 5, BODY),
                RelayEnvelope.user("node-a", 1234, "/queue/myOrder", "KRW-BTC", 42, 3, BODY),
                RelayEnvelope.user("node-a", 1234, "/queue/myAsset", "KRW-ETH", 42, 4, BODY),
                RelayEnvelope.heartbeat("node-a", "matching-p1", 43, 12, BODY));
        for (RelayEnvelope sent : allowed) {
            RelayEnvelope got = RelayEnvelope.decode(sent.headers(1_790_000_000_000L), sent.body());
            assertEquals(sent.kind(), got.kind());
            assertEquals(sent.origin(), got.origin());
            assertEquals(sent.dest(), got.dest());
            assertEquals(sent.userId(), got.userId());
            assertEquals(sent.market(), got.market());
            assertEquals(sent.partition(), got.partition());
            assertEquals(sent.epoch(), got.epoch());
            assertEquals(sent.seq(), got.seq());
            assertArrayEquals(sent.body(), got.body());
        }
    }

    @Test
    void rejectedShapesAreMalformed() {
        Map<String, String> base = RelayEnvelope.topic("node-a", "/topic/orderbook.KRW-BTC", "KRW-BTC", 1, 1, BODY)
                .headers(1L);
        assertMalformed(with(base, "x-dest", "/topic/../x"));
        assertMalformed(with(base, "x-dest", "/topic/orderbook.krw-btc"));
        assertMalformed(with(base, "x-market", "KRW-ETH"), "목적지와 다른 마켓");
        assertMalformed(with(base, "x-dest", "/topic/candle.7m.KRW-BTC"), "계약 밖 캔들 단위");
        assertMalformed(with(base, "x-v", "2"));
        assertMalformed(without(base, "x-v"));
        assertMalformed(with(base, "x-kind", "other"));
        assertMalformed(with(base, "x-epoch", "-1"));
        assertMalformed(with(base, "x-seq", "abc"));
        assertMalformed(without(base, "x-sent-at"));
        assertMalformed(without(base, "x-origin"));

        Map<String, String> user = RelayEnvelope.user("node-a", 77, "/queue/myOrder", "KRW-BTC", 1, 1, BODY)
                .headers(1L);
        assertMalformed(with(user, "x-dest", "/queue/other"));
        assertMalformed(with(user, "x-user", "77; DROP"));
        assertMalformed(with(user, "x-user", "abc"));
        assertMalformed(without(user, "x-user"));

        Map<String, String> hb = RelayEnvelope.heartbeat("node-a", "matching-p0", 1, 1, BODY).headers(1L);
        assertMalformed(with(hb, "x-dest", "/topic/orderbook.KRW-BTC"), "하트비트에 목적지");
        assertMalformed(without(hb, "x-partition"));
    }

    @Test
    void selfOriginOnlyWhenHolderMatches() {
        RelayEnvelope e = RelayEnvelope.topic("node-a", "/topic/trade.KRW-BTC", "KRW-BTC", 1, 1, BODY);
        assertTrue(e.fromSelf("node-a"));
        assertFalse(e.fromSelf("node-b"));
        assertFalse(e.fromSelf("node-a "));
    }

    @Test
    void heartbeatBodyRoundTripsAndRejectsBadEntries() throws Exception {
        RelayEnvelope.Heartbeat hb = new RelayEnvelope.Heartbeat(
                Map.of("KRW-BTC", new long[] {42, 1234}), Map.of("KRW-BTC", new long[] {42, 567}));
        RelayEnvelope.Heartbeat back = RelayEnvelope.Heartbeat.decode(json, hb.encode(json));
        assertArrayEquals(new long[] {42, 1234}, back.channel(MarketMirror.Channel.ORDERBOOK).get("KRW-BTC"));
        assertArrayEquals(new long[] {42, 567}, back.channel(MarketMirror.Channel.TICKER).get("KRW-BTC"));
        assertThrows(RelayEnvelope.Malformed.class, () -> RelayEnvelope.Heartbeat.decode(json,
                "{\"orderbook\":{\"KRW-BTC\":[42]}}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(RelayEnvelope.Malformed.class, () -> RelayEnvelope.Heartbeat.decode(json,
                "{\"orderbook\":{\"../x\":[42,1]}}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(RelayEnvelope.Malformed.class, () -> RelayEnvelope.Heartbeat.decode(json,
                "not json".getBytes(StandardCharsets.UTF_8)));
    }

    /** 받는 쪽은 거부 모양을 전달하지 않고 malformed 로 센다. 자기 출처 봉투도 전달하지 않는다. */
    @Test
    void subscriberDropsMalformedAndSelfEnvelopesAndCountsMalformed() {
        List<Message<?>> delivered = new CopyOnWriteArrayList<>();
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        channel.subscribe(delivered::add);
        WsFanout fanout = new WsFanout(new SimpMessagingTemplate(channel), json, null, "node-b");
        OwnerBeats beats = new OwnerBeats(() -> 0L);
        RelaySubscriber subscriber = new RelaySubscriber("node-b", PartitionPlan.single(), null, fanout,
                new MarketMirror(() -> 0L), beats, null, json, new SimpleMeterRegistry().timer("lag"));

        Map<String, String> ok = RelayEnvelope.topic("node-a", "/topic/orderbook.KRW-BTC", "KRW-BTC", 5, 9, BODY)
                .headers(System.currentTimeMillis());
        subscriber.onMessage(with(ok, "x-dest", "/topic/../x"), BODY, "a1");
        subscriber.onMessage(with(ok, "x-v", "9"), BODY, "a2");
        Map<String, String> strangerHb = RelayEnvelope.heartbeat("node-a", "matching-p9", 5, 1,
                "{\"orderbook\":{},\"ticker\":{}}".getBytes(StandardCharsets.UTF_8)).headers(System.currentTimeMillis());
        subscriber.onMessage(strangerHb, "{\"orderbook\":{},\"ticker\":{}}".getBytes(StandardCharsets.UTF_8), "a3");
        assertEquals(3, subscriber.malformed());
        assertTrue(delivered.isEmpty(), "거부 모양이 내장 브로커로 갔다");

        subscriber.onMessage(with(ok, "x-origin", "node-b"), BODY, "a4");
        assertTrue(delivered.isEmpty(), "자기 출처 봉투를 다시 전달했다");
        assertEquals(0, subscriber.received(RelayEnvelope.Kind.TOPIC));

        subscriber.onMessage(ok, BODY, "a5");
        assertEquals(1, delivered.size());
        assertEquals(1, subscriber.received(RelayEnvelope.Kind.TOPIC));
        assertNull(beats.announced("matching-engine", 5, MarketMirror.Channel.ORDERBOOK, "KRW-BTC"));
    }

    private static void assertMalformed(Map<String, String> headers) {
        assertMalformed(headers, headers.toString());
    }

    private static void assertMalformed(Map<String, String> headers, String what) {
        assertThrows(RelayEnvelope.Malformed.class, () -> RelayEnvelope.decode(headers, BODY), what);
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, String> without(Map<String, String> base, String key) {
        Map<String, String> copy = new HashMap<>(base);
        copy.remove(key);
        return copy;
    }
}

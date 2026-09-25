package kr.exchange.api.ws;

import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.config.LeaderLeaseStoreProperties;
import kr.exchange.config.WsRelayConfig;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.TestPartitions;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** WS 수용 판정의 규칙표(API 설계 §5-6, S6 설계 4-11). 시계와 리스 상태를 주입하고 DB 는 쓰지 않는다. */
class WsGateTest {

    private static final String ME = "me";
    private static final String OTHER = "other";
    private static final String ENGINE = "matching-engine";
    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";
    private static final Duration TTL = Duration.ofSeconds(6);
    private static final Duration SILENT = TTL.plusSeconds(3);

    private final AtomicLong clock = new AtomicLong(10_000_000);
    private final Map<String, LeaderTerm> owned = new HashMap<>();
    private final AtomicReference<Optional<Map<String, WsGate.LeaseRow>>> leases =
            new AtomicReference<>(Optional.of(Map.of()));
    private final OwnerBeats beats = new OwnerBeats(clock::get);

    private WsGate gate(boolean broker, List<String> partitions) {
        return new WsGate(true, ME, partitions, owned::get, leases::get, broker ? beats : null,
                Duration.ofSeconds(5), SILENT, TTL, clock::get);
    }

    private void lease(String partition, String holder, long epoch, boolean valid) {
        Map<String, WsGate.LeaseRow> rows = new HashMap<>(leases.get().orElse(Map.of()));
        rows.put(partition, new WsGate.LeaseRow(holder, epoch, valid));
        leases.set(Optional.of(rows));
    }

    @Test
    void nodeHoldingEveryPartitionAlwaysAccepts() {
        owned.put(ENGINE, TestPartitions.term(ENGINE, 7));
        lease(ENGINE, OTHER, 9, true);
        WsGate simple = gate(false, List.of(ENGINE));
        WsGate broker = gate(true, List.of(ENGINE));
        clock.addAndGet(60_000);
        assertTrue(simple.evaluate().open());
        assertTrue(broker.evaluate().open(), "모든 파티션을 쥔 노드가 구독 끊김으로 닫혔다");
        assertFalse(broker.degraded(WsGate.Cause.LINK_DOWN), "리더의 degraded 가 1 이다");
    }

    @Test
    void simpleRefusesOnlyWhenAnotherHolderHasAValidLease() {
        WsGate g = gate(false, List.of(ENGINE));
        lease(ENGINE, OTHER, 9, true);
        assertEquals(new WsGate.Decision(false, WsGate.Reason.RELAY_REQUIRED), g.evaluate());
        lease(ENGINE, OTHER, 9, false);
        assertTrue(g.evaluate().open(), "만료된 리스인데 거절했다(단일 노드 기동 직후)");
        lease(ENGINE, ME, 10, true);
        assertTrue(g.evaluate().open(), "자기 리스(승격 재생 중)인데 거절했다");
        leases.set(Optional.empty());
        assertTrue(g.evaluate().open(), "리스 상태를 모르는데 거절했다");
    }

    @Test
    void simpleP3NodeHoldingOnePartitionRefuses() {
        owned.put(P0, TestPartitions.term(P0, 20));
        lease(P0, ME, 20, true);
        lease(P1, OTHER, 21, true);
        WsGate g = gate(false, List.of(P0, P1));
        assertEquals(WsGate.Reason.RELAY_REQUIRED, g.evaluate().reason());
        owned.put(P1, TestPartitions.term(P1, 22));
        assertTrue(g.evaluate().open());
    }

    @Test
    void engineDisabledNodeNeverAccepts() {
        WsGate g = new WsGate(false, ME, List.of(ENGINE), owned::get, leases::get, null, Duration.ofSeconds(5),
                SILENT, TTL, clock::get);
        assertEquals(WsGate.Reason.RELAY_REQUIRED, g.current().reason());
        assertEquals(WsGate.Reason.RELAY_REQUIRED, g.evaluate().reason());
    }

    @Test
    void brokerLinkDownRefusesAfterFiveSeconds() {
        WsGate g = gate(true, List.of(ENGINE));
        beats.onSubscribed();
        assertTrue(g.evaluate().open());
        beats.onUnsubscribed();
        clock.addAndGet(4_900);
        assertTrue(g.evaluate().open(), "5초 유예 안에 닫았다");
        clock.addAndGet(200);
        assertEquals(WsGate.Reason.RELAY_UNAVAILABLE, g.evaluate().reason());
        assertTrue(g.degraded(WsGate.Cause.LINK_DOWN));
        beats.onSubscribed();
        assertTrue(g.evaluate().open());
        assertFalse(g.degraded(WsGate.Cause.LINK_DOWN));
    }

    @Test
    void brokerOwnerSilentPastTtlPlusThreeSecondsRefuses() {
        WsGate g = gate(true, List.of(ENGINE));
        beats.onSubscribed();
        lease(ENGINE, OTHER, 9, true);
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(1_000);
            beats.onHeartbeat(ENGINE, 9, hb());
        }
        long third = clock.get() - 2_000;
        assertTrue(g.evaluate().open());
        // 하트비트가 끊겼다. 닿음은 셋째 하트비트 + 5초에 끝나고, 거기서 리스 TTL + 3초를 더 기다린다.
        clock.set(third + 5_000 + SILENT.toMillis());
        assertTrue(g.evaluate().open(), "유예가 끝나기 전에 닫았다");
        clock.addAndGet(1);
        assertEquals(WsGate.Reason.RELAY_UNAVAILABLE, g.evaluate().reason());
        assertTrue(g.degraded(WsGate.Cause.OWNER_SILENT));
        assertFalse(g.degraded(WsGate.Cause.LINK_DOWN), "구독은 붙어 있는데 link_down 이다");
    }

    /** 리더 kill. 리스가 TTL 안에 만료되므로 하트비트가 끊겨도 중계 불능이 아니다(S6 설계 4-11 (ii)). */
    @Test
    void ownerDeathLetsLeaseExpireBeforeSilenceCounts() {
        WsGate g = gate(true, List.of(ENGINE));
        beats.onSubscribed();
        lease(ENGINE, OTHER, 9, true);
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(1_000);
            beats.onHeartbeat(ENGINE, 9, hb());
        }
        for (int ms = 0; ms <= TTL.toMillis(); ms += 200) {
            clock.addAndGet(200);
            assertTrue(g.evaluate().open(), "리스가 아직 유효한 동안 닫았다: +" + ms + "ms");
        }
        lease(ENGINE, OTHER, 9, false);
        clock.addAndGet(60_000);
        assertTrue(g.evaluate().open(), "만료된 리스의 소유자를 중계 불능으로 봤다");
        assertFalse(g.degraded(WsGate.Cause.OWNER_SILENT));
    }

    /**
     * DynamoDB 보관소에서 leader_lease 행은 승격 때 게시만 되고 lease_until 이 먼 미래다(V33). 조회기는 그 행이 아니라 보관소 항목을
     * 보관소 시계로 읽으므로, 죽은 소유 노드의 리스가 TTL 뒤 만료돼 곧 승격할 팔로워가 owner_silent 로 닫지 않는다.
     */
    @Test
    void dynamoDbLeaseStateComesFromTheStoreSoOwnerDeathIsNotSilence() {
        Instant start = Instant.parse("2026-09-25T00:00:00Z");
        AtomicReference<Instant> storeNow = new AtomicReference<>(start);
        LeaderLeaseStore store = mock(LeaderLeaseStore.class);
        when(store.current()).thenReturn(Optional.of(new LeaseSnapshot(OTHER, 9, start.plus(TTL))));
        when(store.now()).thenAnswer(inv -> storeNow.get());
        JdbcClient leaderLeaseRows = mock(JdbcClient.class);
        PartitionLeases partitionLeases = mock(PartitionLeases.class);
        when(partitionLeases.plan()).thenReturn(PartitionPlan.single());
        try (LeaseWatch watch = new WsRelayConfig().leaseWatch(leaderLeaseRows, partitionLeases,
                new LeaderLeaseStoreProperties(LeaderLeaseStoreProperties.DYNAMODB, null, null, null, null, false),
                resource -> store, Duration.ofHours(1))) {
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> watch.view().isPresent());
            verifyNoInteractions(leaderLeaseRows);
            assertEquals(new WsGate.LeaseRow(OTHER, 9, true), watch.view().orElseThrow().get(ENGINE));

            WsGate g = new WsGate(true, ME, List.of(ENGINE), owned::get, watch::view, beats, Duration.ofSeconds(5),
                    SILENT, TTL, clock::get);
            beats.onSubscribed();
            for (int i = 0; i < 3; i++) {
                clock.addAndGet(1_000);
                beats.onHeartbeat(ENGINE, 9, hb());
            }
            assertTrue(g.evaluate().open());
            // 소유 노드가 죽어 하트비트와 갱신이 멈췄다. 보관소 시계로 TTL 이 지나면 그 리스는 유효하지 않다.
            for (int s = 1; s <= 60; s++) {
                clock.addAndGet(1_000);
                storeNow.set(start.plusSeconds(s));
                watch.poll();
                assertTrue(g.evaluate().open(), "죽은 소유 노드의 리스를 유효로 봐 닫았다: +" + s + "s");
            }
            assertFalse(watch.view().orElseThrow().get(ENGINE).valid());
            assertFalse(g.degraded(WsGate.Cause.OWNER_SILENT));
        }
    }

    /** 다시 구독한 직후에는 새 세대의 하트비트 셋이 모이기 전이다. 닿지 않는 시간은 이 세대가 시작한 때부터 센다(S6 설계 4-11 (ii)). */
    @Test
    void silenceClockRestartsWithANewSubscriptionGeneration() {
        WsGate g = gate(true, List.of(ENGINE));
        beats.onSubscribed();
        lease(ENGINE, OTHER, 9, true);
        assertTrue(g.evaluate().open());
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(1_000);
            beats.onHeartbeat(ENGINE, 9, hb());
        }
        // 소유 노드의 하트비트가 오래전에 끊긴 채 판정 없이 시간이 흘렀고, 브로커가 잠깐 끊겼다가 다시 붙는다.
        clock.addAndGet(60_000);
        beats.onUnsubscribed();
        clock.addAndGet(1_000);
        beats.onSubscribed();
        assertTrue(g.evaluate().open(), "새 세대의 하트비트가 모이기도 전에 닫았다");
        clock.addAndGet(SILENT.toMillis());
        assertTrue(g.evaluate().open(), "세대 시작부터 유예가 지나기 전에 닫았다");
        clock.addAndGet(1);
        assertEquals(WsGate.Reason.RELAY_UNAVAILABLE, g.evaluate().reason());
        assertTrue(g.degraded(WsGate.Cause.OWNER_SILENT));
        assertFalse(g.degraded(WsGate.Cause.LINK_DOWN));
    }

    /** 새 epoch 를 처음 본 때부터 센다. 승격 재생이 유예보다 짧으면 거절이 생기지 않는다. */
    @Test
    void silenceClockStartsWhenANewEpochIsFirstSeen() {
        WsGate g = gate(true, List.of(ENGINE));
        beats.onSubscribed();
        clock.addAndGet(60_000);
        lease(ENGINE, OTHER, 10, true);
        assertTrue(g.evaluate().open());
        clock.addAndGet(SILENT.toMillis());
        assertTrue(g.evaluate().open(), "처음 본 때부터 유예가 지나기 전에 닫았다");
        clock.addAndGet(1);
        assertFalse(g.evaluate().open());
    }

    @Test
    void unknownLeaseStateNeverTriggersOwnerSilence() {
        WsGate g = gate(true, List.of(ENGINE));
        beats.onSubscribed();
        leases.set(Optional.empty());
        clock.addAndGet(120_000);
        assertTrue(g.evaluate().open());
        assertFalse(g.degraded(WsGate.Cause.OWNER_SILENT));
    }

    @Test
    void ownerSilentAfterShorterThanTtlPlusThreeSecondsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WsGate(true, ME, List.of(ENGINE), owned::get,
                leases::get, beats, Duration.ofSeconds(5), TTL.plusSeconds(2), TTL, clock::get));
    }

    @Test
    void leaseWatchKeepsLastViewForThreeSecondsAfterFailures() {
        AtomicReference<Map<String, WsGate.LeaseRow>> db = new AtomicReference<>(
                Map.of(ENGINE, new WsGate.LeaseRow(OTHER, 3, true)));
        LeaseWatch watch = new LeaseWatch(() -> {
            Map<String, WsGate.LeaseRow> rows = db.get();
            if (rows == null) {
                throw new IllegalStateException("DB 닿지 않음");
            }
            return rows;
        }, Duration.ofSeconds(1), clock::get);
        assertTrue(watch.view().isEmpty(), "읽은 적이 없는데 상태가 있다");
        watch.poll();
        assertEquals(3, watch.view().orElseThrow().get(ENGINE).epoch());
        db.set(null);
        clock.addAndGet(2_000);
        watch.poll();
        assertTrue(watch.view().isPresent(), "3초 안인데 마지막 결과를 버렸다");
        clock.addAndGet(1_001);
        watch.poll();
        assertTrue(watch.view().isEmpty(), "3초 넘게 실패했는데 옛 결과를 썼다");
    }

    @Test
    void sessionsCloseWith1013OnlyOnTransitionToRefusing() throws Exception {
        WsGate g = gate(false, List.of(ENGINE));
        WebSocketHandler chain = g.decorate(new TextWebSocketHandler());
        WebSocketSession s1 = session("s1");
        WebSocketSession s2 = session("s2");
        chain.afterConnectionEstablished(s1);
        chain.afterConnectionEstablished(s2);

        lease(ENGINE, OTHER, 9, true);
        g.evaluate();
        g.evaluate();
        CloseStatus expected = CloseStatus.SERVICE_OVERLOAD.withReason("relay_required");
        verify(s1, times(1)).close(expected);
        verify(s2, times(1)).close(expected);

        chain.afterConnectionClosed(s2, expected);
        lease(ENGINE, OTHER, 9, false);
        g.evaluate();
        lease(ENGINE, OTHER, 9, true);
        g.evaluate();
        verify(s1, times(2)).close(expected);
        verify(s2, times(1)).close(expected);
        assertEquals(1, g.trackedSessions());
    }

    @Test
    void sessionEstablishedWhileRefusingIsClosedAtOnce() throws Exception {
        WsGate g = gate(false, List.of(ENGINE));
        lease(ENGINE, OTHER, 9, true);
        g.evaluate();
        WebSocketSession late = session("late");
        g.decorate(new TextWebSocketHandler()).afterConnectionEstablished(late);
        verify(late).close(CloseStatus.SERVICE_OVERLOAD.withReason("relay_required"));
    }

    @Test
    void handshakeIsRefusedWith503AndRetryAfterBeforeUpgrade() throws Exception {
        WsGate g = gate(true, List.of(ENGINE));
        beats.onUnsubscribed();
        clock.addAndGet(6_000);
        g.evaluate();
        MockHttpServletResponse raw = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(raw);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ws/v1");
        req.addHeader("Upgrade", "websocket");
        req.addHeader("Connection", "Upgrade");
        boolean upgraded = g.handshakeHandler().doHandshake(new ServletServerHttpRequest(req), response,
                new TextWebSocketHandler(), new HashMap<>());
        response.flush();
        assertFalse(upgraded);
        assertEquals(503, raw.getStatus());
        assertEquals("5", raw.getHeader("Retry-After"));
        assertEquals(1, g.handshakeRefused(WsGate.Reason.RELAY_UNAVAILABLE));
        assertEquals(0, g.handshakeRefused(WsGate.Reason.RELAY_REQUIRED));
    }

    private static WebSocketSession session(String id) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        return s;
    }

    private static RelayEnvelope.Heartbeat hb() {
        return new RelayEnvelope.Heartbeat(Map.of(), Map.of());
    }
}

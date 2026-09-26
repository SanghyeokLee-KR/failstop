package kr.exchange.api.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 동시 연결 상한의 거동을 컨테이너 없이 고정한다.
 *
 * 고정하는 성질은 넷이다. 상한까지는 받는다, 넘으면 1013 으로 닫고 안쪽에 알리지 않는다,
 * 닫힌 만큼 자리가 난다, 게이트를 끄면 상한이 사라진다. 마지막 하나가 실측에서 노드를 죽인
 * 상태이고 재현이 게이트의 존재 이유다.
 */
class WsConnectionLimitHandlerTest {

    /** 안쪽 핸들러가 무엇을 보았는지 기록한다. 거절된 세션이 새어 들어가면 여기 남는다. */
    private static final class RecordingHandler extends TextWebSocketHandler {
        final List<String> established = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            established.add(session.getId());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.add(session.getId());
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            messages.add(session.getId());
        }
    }

    /** 닫힘 코드만 확인하면 되므로 세션은 최소 구현으로 둔다. */
    private static final class FakeSession implements WebSocketSession {
        private final String id;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private CloseStatus closedWith;

        FakeSession(String id) {
            this.id = id;
        }

        @Override public String getId() { return id; }
        @Override public java.net.URI getUri() { return null; }
        @Override public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return new org.springframework.http.HttpHeaders();
        }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) { }
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) { }
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return List.of();
        }
        @Override public void sendMessage(WebSocketMessage<?> message) { }
        @Override public boolean isOpen() { return closedWith == null; }
        @Override public void close() { close(CloseStatus.NORMAL); }
        @Override public void close(CloseStatus status) { this.closedWith = status; }

        CloseStatus closedWith() { return closedWith; }
    }

    private static WsConnectionLimitHandler handler(WebSocketHandler delegate, AdmissionGate gate,
                                                    AdmissionControl admission) {
        return new WsConnectionLimitHandler(delegate, gate, admission);
    }

    private static AdmissionControl bucket(int limit) {
        return new AdmissionControl("ws", limit, AdmissionControl.Shed.CAPACITY,
                new SimpleMeterRegistry());
    }

    @Test
    void admitsUpToLimitAndRejectsBeyond() throws Exception {
        RecordingHandler inner = new RecordingHandler();
        AdmissionControl admission = bucket(2);
        WsConnectionLimitHandler h = handler(inner, new AdmissionGate(true), admission);

        FakeSession a = new FakeSession("a");
        FakeSession b = new FakeSession("b");
        FakeSession c = new FakeSession("c");
        h.afterConnectionEstablished(a);
        h.afterConnectionEstablished(b);
        h.afterConnectionEstablished(c);

        assertEquals(List.of("a", "b"), inner.established,
                "상한을 넘은 연결은 안쪽 핸들러가 보면 안 된다. 보면 STOMP 세션이 만들어진다");
        assertNull(a.closedWith());
        assertNull(b.closedWith());
        assertEquals(CloseStatus.SERVICE_OVERLOAD.getCode(), c.closedWith().getCode(),
                "거절은 고장이 아니라 백프레셔다. 1013 이어야 한다");
        assertEquals(2, admission.inFlight());
    }

    /** 거절된 세션이 닫힐 때 슬롯을 반납하면 상한이 서서히 늘어난다. */
    @Test
    void rejectedSessionDoesNotReleaseSlotOnClose() throws Exception {
        RecordingHandler inner = new RecordingHandler();
        AdmissionControl admission = bucket(1);
        WsConnectionLimitHandler h = handler(inner, new AdmissionGate(true), admission);

        FakeSession a = new FakeSession("a");
        FakeSession b = new FakeSession("b");
        h.afterConnectionEstablished(a);
        h.afterConnectionEstablished(b);
        h.afterConnectionClosed(b, CloseStatus.SERVICE_OVERLOAD);

        assertEquals(1, admission.inFlight(), "거절된 세션의 종료가 자리를 만들면 안 된다");
        assertEquals(List.of(), inner.closed, "안쪽이 모르는 세션의 종료를 알리면 안 된다");
    }

    @Test
    void closingAdmittedSessionFreesSlot() throws Exception {
        RecordingHandler inner = new RecordingHandler();
        AdmissionControl admission = bucket(1);
        WsConnectionLimitHandler h = handler(inner, new AdmissionGate(true), admission);

        FakeSession a = new FakeSession("a");
        h.afterConnectionEstablished(a);
        h.afterConnectionClosed(a, CloseStatus.NORMAL);
        assertEquals(0, admission.inFlight());
        assertEquals(List.of("a"), inner.closed);

        FakeSession b = new FakeSession("b");
        h.afterConnectionEstablished(b);
        assertEquals(List.of("a", "b"), inner.established, "반납된 자리는 다시 쓰여야 한다");
    }

    /** 거절된 세션이 닫히기 전에 보낸 프레임이 안쪽에 닿으면 안쪽이 모르는 세션을 다루게 된다. */
    @Test
    void rejectedSessionFramesAreDropped() throws Exception {
        RecordingHandler inner = new RecordingHandler();
        WsConnectionLimitHandler h = handler(inner, new AdmissionGate(true), bucket(1));

        FakeSession a = new FakeSession("a");
        FakeSession b = new FakeSession("b");
        h.afterConnectionEstablished(a);
        h.afterConnectionEstablished(b);
        h.handleMessage(a, new TextMessage("x"));
        h.handleMessage(b, new TextMessage("x"));

        assertEquals(List.of("a"), inner.messages);
    }

    /**
     * 게이트를 끄면 상한이 사라진다. 2026-09-04 실측에서 노드를 죽인 상태이고 재현이 이 스위치의
     * 존재 이유다.
     */
    @Test
    void gateOffRemovesTheLimit() throws Exception {
        RecordingHandler inner = new RecordingHandler();
        AdmissionControl admission = bucket(1);
        WsConnectionLimitHandler h = handler(inner, new AdmissionGate(false), admission);

        for (int i = 0; i < 5; i++) {
            h.afterConnectionEstablished(new FakeSession("s" + i));
        }
        assertEquals(5, inner.established.size(), "게이트가 꺼지면 오는 대로 받는다");
        assertEquals(0, admission.inFlight(), "게이트가 꺼진 동안은 슬롯을 잡지 않는다");
    }

    /**
     * 게이트가 연결 수명 도중에 켜질 수 있다. 꺼진 동안 맺힌 연결은 슬롯을 쥐지 않았으므로
     * 반납하면 안 되지만, 안쪽은 그 세션을 알고 있으므로 종료는 알려야 한다. 알리지 않으면
     * Spring 안쪽에 STOMP 세션이 남는다.
     */
    @Test
    void sessionOpenedWhileGateOffStillNotifiesInnerOnClose() throws Exception {
        RecordingHandler inner = new RecordingHandler();
        AdmissionGate gate = new AdmissionGate(false);
        AdmissionControl admission = bucket(1);
        WsConnectionLimitHandler h = handler(inner, gate, admission);

        FakeSession a = new FakeSession("a");
        h.afterConnectionEstablished(a);
        gate.set(true, "test");
        h.afterConnectionClosed(a, CloseStatus.NORMAL);

        assertEquals(List.of("a"), inner.closed, "안쪽이 아는 세션의 종료는 반드시 전달돼야 한다");
        assertEquals(0, admission.inFlight(), "쥐지 않은 슬롯을 반납하면 상한이 늘어난다");
        assertEquals(0, h.trackedSessions());
    }

    /**
     * WS 게이트 데코레이터가 바깥에 들어가도(WebSocketConfig 의 사슬) 거절·반납 계수가 같다. 게이트가 닫히며 닫은 세션은 안쪽 반납
     * 경로를 그대로 탄다(S6 설계 6-4).
     */
    @Test
    void gateDecoratorOutsideKeepsAdmissionAccounting() throws Exception {
        RecordingHandler inner = new RecordingHandler();
        AdmissionControl admission = bucket(2);
        java.util.concurrent.atomic.AtomicReference<java.util.Optional<Map<String, WsGate.LeaseRow>>> leases =
                new java.util.concurrent.atomic.AtomicReference<>(java.util.Optional.of(Map.of()));
        WsGate gate = new WsGate(true, "me", List.of("matching-engine"), p -> null, leases::get, null,
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(13), java.time.Duration.ofSeconds(10),
                () -> 0L);
        WebSocketHandler chain = gate.decorate(handler(inner, new AdmissionGate(true), admission));

        FakeSession a = new FakeSession("a");
        FakeSession b = new FakeSession("b");
        FakeSession c = new FakeSession("c");
        chain.afterConnectionEstablished(a);
        chain.afterConnectionEstablished(b);
        chain.afterConnectionEstablished(c);
        assertEquals(List.of("a", "b"), inner.established);
        assertEquals(CloseStatus.SERVICE_OVERLOAD.getCode(), c.closedWith().getCode());
        chain.afterConnectionClosed(c, c.closedWith());
        assertEquals(2, admission.inFlight(), "게이트가 끼어도 거절된 세션의 종료가 자리를 만들면 안 된다");

        // 다른 노드가 유효한 리스를 쥐었다. 게이트가 닫히며 열린 세션을 1013 으로 닫고, 닫힌 세션은 자리를 돌려준다.
        leases.set(java.util.Optional.of(Map.of("matching-engine", new WsGate.LeaseRow("other", 9, true))));
        gate.evaluate();
        assertEquals("relay_required", a.closedWith().getReason());
        assertEquals("relay_required", b.closedWith().getReason());
        chain.afterConnectionClosed(a, a.closedWith());
        chain.afterConnectionClosed(b, b.closedWith());
        assertEquals(0, admission.inFlight(), "게이트가 닫은 세션의 자리가 돌아오지 않았다");
        assertEquals(List.of("a", "b"), inner.closed);
        assertEquals(0, gate.trackedSessions(), "게이트가 닫힌 세션을 계속 기록한다");
    }

    /** 안쪽이 세션을 세우다 실패하면 컨테이너가 종료를 안 부를 수 있다. 그때도 자리는 돌려준다. */
    @Test
    void slotIsReturnedWhenInnerHandlerFails() {
        WebSocketHandler failing = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                throw new IllegalStateException("안쪽 실패");
            }
        };
        AdmissionControl admission = bucket(1);
        WsConnectionLimitHandler h = handler(failing, new AdmissionGate(true), admission);

        assertThrows(IllegalStateException.class,
                () -> h.afterConnectionEstablished(new FakeSession("a")));
        assertEquals(0, admission.inFlight(), "실패한 연결이 자리를 물고 있으면 상한이 줄어든다");
        assertEquals(0, h.trackedSessions());
    }
}

package kr.exchange.api.ws;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * exchange.ws.clients 게이지 계약 검증(하네스 CICD §5-2).
 *
 * 증감은 세션 id 로 짝을 맞춘다. Spring 은 STOMP CONNECT 를 마치지 못한 세션에도 Disconnect
 * 이벤트를 내므로, 짝을 맞추지 않으면 올린 적 없는 값을 내린다. 2026-09-07 재접속 폭주 실측에서
 * 살아 있는 세션 1900 개가 계속 메시지를 받는 동안 게이지만 159 까지 떨어진 것이 그 경로다.
 */
class WsSessionMetricsTest {

    private static Message<byte[]> frame(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private SessionConnectedEvent connected(String sessionId) {
        return new SessionConnectedEvent(this, frame(sessionId));
    }

    private SessionDisconnectEvent disconnected(String sessionId) {
        return new SessionDisconnectEvent(this, frame(sessionId), sessionId, CloseStatus.NORMAL);
    }

    private double gauge(SimpleMeterRegistry registry) {
        return registry.get("exchange.ws.clients").tag("channel", "stomp").gauge().value();
    }

    @Test
    void connectAndDisconnectMoveGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WsSessionMetrics metrics = new WsSessionMetrics(registry);

        assertEquals(0.0, gauge(registry));

        metrics.onConnected(connected("a"));
        metrics.onConnected(connected("b"));
        assertEquals(2.0, gauge(registry));
        assertEquals(2, metrics.activeCount());

        metrics.onDisconnect(disconnected("a"));
        assertEquals(1.0, gauge(registry));
        assertEquals(1, metrics.activeCount());
    }

    /**
     * 폭주 회귀. STOMP CONNECT 없이 끝난 세션의 Disconnect 가 살아 있는 세션의 계수를 깎으면
     * 안 된다. 깎이면 운영자가 접속자 급감으로 읽고 없는 장애를 좇는다.
     */
    @Test
    void disconnectOfNeverConnectedSessionDoesNotDecrementLiveSessions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WsSessionMetrics metrics = new WsSessionMetrics(registry);

        metrics.onConnected(connected("live-1"));
        metrics.onConnected(connected("live-2"));
        assertEquals(2.0, gauge(registry));

        // 상한에 걸려 STOMP 까지 가지 못하고 끝난 연결들이다. 이벤트는 온다.
        for (int i = 0; i < 500; i++) {
            metrics.onDisconnect(disconnected("rejected-" + i));
        }

        assertEquals(2.0, gauge(registry), "살아 있는 세션 수가 그대로여야 한다");
        assertEquals(2, metrics.countedSessions());
    }

    @Test
    void disconnectWithoutConnectStaysAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WsSessionMetrics metrics = new WsSessionMetrics(registry);

        metrics.onDisconnect(disconnected("x"));
        metrics.onDisconnect(disconnected("y"));
        assertEquals(0.0, gauge(registry));

        metrics.onConnected(connected("z"));
        assertEquals(1.0, gauge(registry), "음수 누적 흔적이 남지 않아야 한다");
    }

    /** 같은 세션의 이벤트가 두 번 와도 계수가 어긋나면 안 된다. */
    @Test
    void duplicateEventsAreIdempotent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WsSessionMetrics metrics = new WsSessionMetrics(registry);

        metrics.onConnected(connected("a"));
        metrics.onConnected(connected("a"));
        assertEquals(1.0, gauge(registry));

        metrics.onDisconnect(disconnected("a"));
        metrics.onDisconnect(disconnected("a"));
        assertEquals(0.0, gauge(registry));
        assertEquals(0, metrics.countedSessions());
    }

    /** id 를 읽지 못하면 세지 않는다. 틀리게 세는 것보다 낫다. */
    @Test
    void missingSessionIdIsNotCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WsSessionMetrics metrics = new WsSessionMetrics(registry);

        metrics.onConnected(connected(null));
        assertEquals(0.0, gauge(registry));
    }
}

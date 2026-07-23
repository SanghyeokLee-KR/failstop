package kr.exchange.api.ws;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.CloseStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * exchange.ws.clients 게이지 계약 검증(하네스 CICD §5-2). Connected/Disconnect 이벤트로 증감하고,
 * DISCONNECT가 CONNECT보다 많아도 게이지는 0 미만으로 내려가지 않는다(음수 하한 클램프).
 */
class WsSessionMetricsTest {

    private static Message<byte[]> frame() {
        return MessageBuilder.withPayload(new byte[0]).build();
    }

    private SessionConnectedEvent connected() {
        return new SessionConnectedEvent(this, frame());
    }

    private SessionDisconnectEvent disconnected() {
        return new SessionDisconnectEvent(this, frame(), "s", CloseStatus.NORMAL);
    }

    private double gauge(SimpleMeterRegistry registry) {
        return registry.get("exchange.ws.clients").tag("channel", "stomp").gauge().value();
    }

    @Test
    void connectAndDisconnectMoveGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WsSessionMetrics metrics = new WsSessionMetrics(registry);

        assertEquals(0.0, gauge(registry));

        metrics.onConnected(connected());
        metrics.onConnected(connected());
        assertEquals(2.0, gauge(registry));
        assertEquals(2, metrics.activeCount());

        metrics.onDisconnect(disconnected());
        assertEquals(1.0, gauge(registry));
        assertEquals(1, metrics.activeCount());
    }

    @Test
    void disconnectWithoutConnectClampsAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WsSessionMetrics metrics = new WsSessionMetrics(registry);

        // 재시작 후 잔여 DISCONNECT나 이벤트 재전달로 음수가 되지 않아야 한다.
        metrics.onDisconnect(disconnected());
        metrics.onDisconnect(disconnected());
        assertEquals(0.0, gauge(registry));
        assertEquals(0, metrics.activeCount());

        // 클램프 이후 정상 연결은 다시 1부터 증가한다(음수 누적 흔적 없음).
        metrics.onConnected(connected());
        assertEquals(1.0, gauge(registry));
    }
}

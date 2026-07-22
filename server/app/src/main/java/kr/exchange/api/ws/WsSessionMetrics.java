package kr.exchange.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * STOMP 활성 세션 게이지(하네스 CICD §5-2 exchange.ws.clients, §3-3 WS 연결 예산 관찰).
 *
 * SessionConnected·SessionDisconnect 애플리케이션 이벤트로 카운트한다. DISCONNECT 프레임이
 * 없는 급단절도 전송 종료 시 SessionDisconnectEvent가 발행되므로 인터셉터 프레임 관찰보다
 * 누수에 강하다. 이벤트 콜백은 게이지 소스 AtomicInteger만 증감해 브로커 스레드를 블로킹하지 않는다.
 *
 * channel 태그는 §5-2 명세 키를 유지하되 연결 단위 계량이라 단일 값(stomp)을 쓴다. 목적지별
 * 분해는 세션-구독 회계가 필요해 후속으로 미룬다(현재 고카디널리티 위험 없음).
 */
public class WsSessionMetrics {

    private final AtomicInteger active = new AtomicInteger();

    public WsSessionMetrics(MeterRegistry registry) {
        registry.gauge("exchange.ws.clients", Tags.of("channel", "stomp"),
                active, AtomicInteger::doubleValue);
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        active.incrementAndGet();
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        active.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }

    int activeCount() {
        return active.get();
    }
}

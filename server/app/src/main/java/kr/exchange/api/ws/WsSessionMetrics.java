package kr.exchange.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STOMP 활성 세션 게이지(하네스 CICD §5-2 exchange.ws.clients, §3-3 WS 연결 예산 관찰).
 *
 * SessionConnected·SessionDisconnect 애플리케이션 이벤트로 카운트한다. DISCONNECT 프레임이
 * 없는 급단절도 전송 종료 시 SessionDisconnectEvent가 발행되므로 인터셉터 프레임 관찰보다
 * 누수에 강하다. 이벤트 콜백은 게이지 소스 AtomicInteger만 증감해 브로커 스레드를 블로킹하지 않는다.
 *
 * 세션 id로 짝을 맞추는 이유. Spring 은 STOMP CONNECT 를 마치지 못하고 끝난 세션에도
 * SessionDisconnectEvent 를 낸다. 그 세션은 SessionConnectedEvent 를 낸 적이 없으므로 올린 적
 * 없는 값을 내리게 된다. 2026-09-07 재접속 폭주 실측에서 이것이 드러났다. 살아 있는 세션 1900
 * 개가 계속 메시지를 받는 동안(끊김 창 0) 게이지만 159 까지 떨어졌다. 종전의 `n > 0` 하한은
 * 음수만 막을 뿐 이 어긋남을 막지 못한다.
 *
 * 게이지가 이렇게 무너지면 운영자가 접속자 급감으로 읽고 없는 장애를 좇는다. 이 프로젝트가
 * 다루는 오진이 정확히 그 모양이다.
 *
 * 집합 크기는 동시 연결 상한이 묶는다(WsConnectionLimit). 상한을 넘는 연결은 안쪽 핸들러에
 * 닿지 않아 두 이벤트를 모두 내지 않는다.
 *
 * channel 태그는 §5-2 명세 키를 유지하되 연결 단위 계량이라 단일 값(stomp)을 쓴다. 목적지별
 * 분해는 세션-구독 회계가 필요해 후속으로 미룬다(현재 고카디널리티 위험 없음).
 */
public class WsSessionMetrics {

    private final AtomicInteger active = new AtomicInteger();

    /** 올린 세션의 id. 내릴 때 여기 있는 것만 내려야 짝이 맞는다. */
    private final Set<String> counted = ConcurrentHashMap.newKeySet();

    public WsSessionMetrics(MeterRegistry registry) {
        registry.gauge("exchange.ws.clients", Tags.of("channel", "stomp"),
                active, AtomicInteger::doubleValue);
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (sessionId == null) {
            // id 를 못 읽으면 짝을 맞출 수 없다. 세지 않는 편이 틀리게 세는 것보다 낫다.
            return;
        }
        if (counted.add(sessionId)) {
            active.incrementAndGet();
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) {
            return;
        }
        if (counted.remove(sessionId)) {
            active.decrementAndGet();
        }
    }

    int activeCount() {
        return active.get();
    }

    /** 진단용. 짝을 기다리는 세션 수다. activeCount 와 어긋나면 회계가 깨진 것이다. */
    int countedSessions() {
        return counted.size();
    }
}

package kr.exchange.api.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionGate;

/**
 * WS 동시 연결 수용 제어(하네스 CICD·IaC §3-3, API 설계 §2-5-a).
 *
 * 상한을 넘는 연결을 즉시 닫는다. 안쪽 핸들러에는 알리지 않으므로 STOMP 세션도 구독 등록도
 * 전송 버퍼도 만들어지지 않는다. 거절된 연결이 힙에 남기는 것이 없다는 뜻이다.
 *
 * 왜 핸드셰이크가 아니라 여기인가. 핸드셰이크 인터셉터에서 슬롯을 잡으면 반납 지점이 확실하지
 * 않다. Spring 은 업그레이드가 거부된 경우에도 {@code afterHandshake} 를 예외 없이 부르므로
 * (버전 불일치·오리진 거부·서브프로토콜 협상 실패) 성공과 구분되지 않고 슬롯이 샌다.
 * {@code afterConnectionEstablished} 와 {@code afterConnectionClosed} 는 컨테이너가 세션당
 * 정확히 한 번씩 짝지어 부른다. 새지 않는 쪽을 고른다.
 *
 * 수용 게이트를 따른다. 게이트를 끄면 상한이 사라지고 종전처럼 오는 대로 받는다. 그 상태가
 * 2026-09-04 실측에서 2,702 연결에 노드를 죽인 상태다(증거 31회차). 재현이 이 스위치의
 * 존재 이유다(AdmissionGate).
 *
 * 1013(SERVICE_OVERLOAD)으로 닫는 이유. RFC 6455 가 "지금은 과부하이니 나중에 다시 오라"는
 * 뜻으로 정의한 코드다. 1011(SERVER_ERROR)로 닫으면 클라이언트가 서버 고장으로 읽고 재시도
 * 정책이 달라진다. 이 거절은 고장이 아니라 설계된 백프레셔다.
 */
public final class WsConnectionLimitHandler extends WebSocketHandlerDecorator {

    private static final Logger log = LoggerFactory.getLogger(WsConnectionLimitHandler.class);

    /**
     * 거절 로그를 세션마다 남기지 않는다. 상한에 닿은 순간은 수천 건이 몰리는 순간이고 그때
     * 로그가 디스크와 CPU 를 함께 먹는다. 이 간격으로 한 줄만 남기고 나머지는 지표로 센다.
     */
    private static final long REJECT_LOG_INTERVAL_MS = 5_000;

    private final AdmissionGate gate;
    private final AdmissionControl admission;

    /**
     * 안쪽 핸들러가 아는 세션과 그 세션이 슬롯을 쥐었는지.
     *
     * 값이 TRUE 면 슬롯을 쥐었고, FALSE 면 게이트가 꺼진 동안 맺혀 슬롯 없이 통과했고, 없으면
     * 거절해서 안쪽이 모르는 세션이다. 셋을 갈라야 하는 이유는 게이트가 연결 수명 도중에
     * 바뀔 수 있어서다. 꺼진 동안 맺힌 연결이 켜진 뒤에 닫히면, 슬롯은 반납하지 않되 종료는
     * 안쪽에 알려야 한다. 둘로만 나누면 그 경우에 안쪽의 STOMP 세션이 남는다.
     *
     * 세션 속성 맵을 쓰지 않는 이유는 구현에 따라 동시성 보장이 없어서다.
     */
    private final Map<String, Boolean> tracked = new ConcurrentHashMap<>();

    private volatile long lastRejectLogAt;

    public WsConnectionLimitHandler(WebSocketHandler delegate, AdmissionGate gate,
                                    AdmissionControl admission) {
        super(delegate);
        if (gate == null || admission == null) {
            throw new IllegalArgumentException("gate·admission 은 필수");
        }
        this.gate = gate;
        this.admission = admission;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        boolean holdsSlot = false;
        if (gate.enabled()) {
            if (!admission.tryAcquire()) {
                logRejection();
                session.close(CloseStatus.SERVICE_OVERLOAD);
                return;
            }
            holdsSlot = true;
        }
        tracked.put(session.getId(), holdsSlot);
        try {
            super.afterConnectionEstablished(session);
        } catch (Exception | Error e) {
            // 안쪽이 세션을 세우지 못했다. 컨테이너가 afterConnectionClosed 를 부르지 않을 수
            // 있으므로 여기서 반납한다. 자국을 먼저 지워 이중 반납을 막는다.
            releaseTracked(session.getId());
            throw e;
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (tracked.containsKey(session.getId())) {
            super.handleMessage(session, message);
        }
        // 거절한 세션이 닫히기 전에 보낸 프레임은 버린다. 안쪽은 이 세션을 모른다.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (tracked.containsKey(session.getId())) {
            super.handleTransportError(session, exception);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Boolean holdsSlot = tracked.remove(session.getId());
        if (holdsSlot == null) {
            // 거절한 세션이다. 안쪽은 이 세션을 모르므로 종료도 알리지 않는다.
            return;
        }
        try {
            super.afterConnectionClosed(session, status);
        } finally {
            if (holdsSlot) {
                admission.release();
            }
        }
    }

    /** 진단용. 안쪽이 아는 세션 수다. 지표 in_flight 와 크게 어긋나면 반납이 새는 것이다. */
    int trackedSessions() {
        return tracked.size();
    }

    private void releaseTracked(String sessionId) {
        Boolean holdsSlot = tracked.remove(sessionId);
        if (holdsSlot != null && holdsSlot) {
            admission.release();
        }
    }

    private void logRejection() {
        long now = System.currentTimeMillis();
        if (now - lastRejectLogAt < REJECT_LOG_INTERVAL_MS) {
            return;
        }
        lastRejectLogAt = now;
        log.warn("WS 동시 연결 상한 도달. 신규 연결을 1013 으로 거절 limit={} in_flight={}",
                admission.limit(), admission.inFlight());
    }
}

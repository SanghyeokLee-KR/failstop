package kr.exchange.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.WriteAvailability;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 쓰기 게이트(API 설계 §2-5-e). 동기 standby 가 없어 DB 가 커밋을 확인하지 않는 동안(SUSPENDED) 쓰기를 DB 에 닿기 전에
 * 423 trading_suspended 로, 이 노드가 DB 에 닿지 못하는 동안(UNREACHABLE) 503 으로 거절한다. 안전 메서드는 늘 통과한다.
 *
 * 대상 경로는 필터 등록(/api/v1/*, /admin/v1/*)만 정한다. 컨테이너는 ';' 매개변수를 떼고 퍼센트 인코딩과 '//'·'/./' 를 푼
 * 경로로 등록과 서블릿 매핑을 함께 고르므로, 여기서 원시 URI 로 다시 가리면 컨트롤러에 닿는 요청이 게이트만 비껴간다.
 *
 * 필터 순서 3. CORS(0) 뒤이고 바디 상한(4)·소유권 선검사(5)·수용 제한(6)·IP 스로틀(7)·인증(10) 앞이다. 수용 제한 뒤에
 * 두면 중단 직전에 들어와 커밋을 기다리는 요청이 쥔 허가 때문에 중단 동안의 주문이 게이트에 닿기 전에 429 를 받는다(Patroni
 * 설계 4-4). 거절은 바디, 수용 허가, 토큰, 멱등 키, 잔고 예약을 건드리지 않는다.
 *
 * 423 은 인그레스의 proxy_next_upstream 목록에 없어 재시도·퇴출이 없다. 중단 동안 쓰기마다 503 을 내면 쓰기 업스트림이 계속
 * 비어 계좌·미체결 주문 조회까지 502 가 된다.
 */
public class WriteGateFilter extends OncePerRequestFilter {

    /** 게이트를 지날 때의 세대. 게이트웨이가 쓰기 직전에 다시 대조한다(DB 설계 §5.4-f 6). */
    public static final String GENERATION_ATTRIBUTE = WriteGateFilter.class.getName() + ".generation";
    /** 게이트를 지나지 않은 호출. 받는 쪽은 자기가 불린 때의 세대를 쓴다. 세대는 0 부터라 음수와 겹치지 않는다. */
    public static final long NO_GATE = -1;
    static final String STATUS_URL = "/api/v1/status";
    // 중단은 몇 분 갈 수 있다. 재개는 상태 API 로 확인하므로 짧게 둘 이유가 없다(API 설계 §2-5-e).
    private static final int SUSPENDED_RETRY_AFTER_SECONDS = 5;

    private final WriteAvailability availability;
    private final ErrorRenderer errorRenderer;
    private final LeaderWriteProgress writeProgress;
    private final LongAdder suspendedRejections = new LongAdder();
    private final LongAdder unreachableRejections = new LongAdder();

    public WriteGateFilter(WriteAvailability availability, ErrorRenderer errorRenderer,
                           LeaderWriteProgress writeProgress) {
        this.availability = availability;
        this.errorRenderer = errorRenderer;
        this.writeProgress = writeProgress;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        WriteAvailability.View view = availability.view();
        switch (view.state()) {
            case ACTIVE -> {
                request.setAttribute(GENERATION_ATTRIBUTE, view.generation());
                chain.doFilter(request, response);
            }
            case SUSPENDED -> {
                // 좀비 판정의 수요로 세지 않는다. SUSPENDED 는 모든 노드의 쓰기가 막힌 클러스터의 사실이고 판정도 멈춰 있다.
                suspendedRejections.increment();
                errorRenderer.write(response, tradingSuspended(view));
            }
            case UNREACHABLE -> {
                // 인증 필터에 닿지 않으므로 여기서 노드 층 수요로 센다. 세지 않으면 탐지 연결만 붙지 못하고 풀로는 갱신이 성공하는
                // 리더가 수요 0 으로 끝없이 남는다(DB 설계 §5.4-f 5).
                unreachableRejections.increment();
                if (AdmissionFilter.isOrderWrite(request.getMethod(), request.getRequestURI())) {
                    writeProgress.recordAttempt();
                }
                errorRenderer.write(response, databaseUnavailable());
            }
        }
    }

    /** 게이트가 기록한 세대. 게이트를 지나지 않은 요청(필터 없는 시험 배선)은 NO_GATE 다. */
    public static long gateGeneration(HttpServletRequest request) {
        return request.getAttribute(GENERATION_ATTRIBUTE) instanceof Long generation ? generation : NO_GATE;
    }

    /**
     * 423 trading_suspended. 이 시도는 이 노드에서 아무것도 쓰지 않았다. 앞선 시도가 재개 뒤 커밋될 수 있어 클라이언트는 재개 뒤
     * 같은 키로 다시 내거나 재조회하고 새 키로 다시 내지 않는다(API 설계 §2-3). 중단을 넘은 요청은 이미 재개된 뒤일 수 있어
     * 이유가 없으면 복제 대기로 적는다.
     */
    public static ApiException tradingSuspended(WriteAvailability.View view) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", view.reason() == null ? WriteAvailability.REASON_REPLICATION : view.reason());
        detail.put("since", view.sinceMillis());
        detail.put("status_url", STATUS_URL);
        return new ApiException(423, "trading_suspended", "거래 일시 중단. 재개 후 다시 시도 필요", detail,
                SUSPENDED_RETRY_AFTER_SECONDS);
    }

    /** UNREACHABLE 의 503. §2-5-c 의 'DB 에 닿지 못함'과 같다. 아무것도 쓰지 않았으므로 LB 가 다른 노드로 보내도 된다. */
    static ApiException databaseUnavailable() {
        return new ApiException(503, "service_unavailable", "DB 연결 장애. 재조회 후 판단 필요", null, 1);
    }

    public long suspendedRejections() {
        return suspendedRejections.sum();
    }

    public long unreachableRejections() {
        return unreachableRejections.sum();
    }
}

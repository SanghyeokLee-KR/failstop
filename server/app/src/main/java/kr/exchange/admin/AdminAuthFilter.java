package kr.exchange.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ErrorRenderer;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * /admin/v1 전용 인증 필터. 고객 평면(/api) 필터와 URL·순서로 분리된다.
 *
 * 흐름(§3-6 브라우저 관리 콘솔 세션):
 *   1. mTLS 신원(인증서 CN 유래 operator_id) 도출. 없으면 401.
 *   2. POST /admin/v1/session은 세션 발급 부트스트랩이라 mTLS 신원만 요구하고 통과시킨다.
 *   3. 그 외 요청은 유효한 관리 세션을 요구하고, 세션 operator를 현재 mTLS 신원과 재대조한다.
 *   4. 인증 통과·실패 전부 admin_audit_log에 적재한다(전 액션 감사).
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String CERT_OPERATOR_ATTRIBUTE = "exchange.admin.cert-operator";
    public static final String REQUEST_ID_ATTRIBUTE = "exchange.admin.request-id";

    private final AdminMtlsResolver mtlsResolver;
    private final AdminSessionService sessions;
    private final AdminOperatorDirectory operators;
    private final AdminAuditService audit;
    private final ErrorRenderer errorRenderer;
    // §2-5 관리 버킷 weight 10. 기본 capacity 60·window 60s면 실효 6req/60s. mTLS 게이트 뒤이나
    // 승인 토큰 브루트포스·감사 로그 폭주 방어선. weight를 1로 두면 계약의 10배로 열린다. capacity·window는
    // 환경 튜닝값이라 주입받는다(운영 기본은 계약값, 통합테스트는 다건 호출을 위해 상향).
    // 노드 로컬 구현이라 다중 노드 실효 한도는 노드 수 배수다(AccountRateLimiter 제약 주석 참조).
    private static final int ADMIN_WEIGHT = 10;
    private final AccountRateLimiter rateLimiter;

    public AdminAuthFilter(AdminMtlsResolver mtlsResolver, AdminSessionService sessions,
                           AdminOperatorDirectory operators, AdminAuditService audit,
                           ErrorRenderer errorRenderer, int rateCapacity, long rateWindowMs) {
        this.mtlsResolver = mtlsResolver;
        this.sessions = sessions;
        this.operators = operators;
        this.audit = audit;
        this.errorRenderer = errorRenderer;
        this.rateLimiter = new AccountRateLimiter(rateCapacity, rateWindowMs, "admin", "admin");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        String action = request.getMethod() + " " + request.getRequestURI();
        String certOperator = null;
        try {
            certOperator = mtlsResolver.resolveOperatorId(request)
                    .orElseThrow(() -> new ApiException(401, "jwt_verification",
                            "관리 평면 접근에는 mTLS 클라이언트 인증서가 필요합니다."));
            request.setAttribute(CERT_OPERATOR_ATTRIBUTE, certOperator);

            if (isSessionBootstrap(request)) {
                // 세션 발급 전이라 세션 없이 통과. operator 존재·활성은 컨트롤러가 검증한다.
                chain.doFilter(request, response);
                return;
            }

            String operatorId = sessions.verify(request.getHeader("Authorization"), certOperator);
            if (AdminOperatorDirectory.aiOperator(operatorId)) {
                throw forbidden("AI 운영자 신원으로는 관리 API 를 부를 수 없습니다.", "ai_operator");
            }
            AdminOperatorDirectory.Operator op = operators.find(operatorId)
                    .orElseThrow(() -> forbidden("등록되지 않은 운영자입니다.", "unknown_operator"));
            if (!op.active()) {
                throw forbidden("비활성 운영자입니다.", "operator_disabled");
            }
            // 세션 부트스트랩(POST /session)은 위에서 이미 통과했다. 그 외 관리 요청만 operator 키로 차감.
            // AI 즉시 끄기와 정지는 공용 버킷을 쓰지 않는다. 화면 폴링이 버킷을 비워도 AI 를 멈출 수 있어야 한다.
            AccountRateLimiter.Decision rate = aiKillSwitch(request)
                    ? rateLimiter.acquire(operatorId + ":ai-manual", 1)
                    : rateLimiter.acquire(operatorId, ADMIN_WEIGHT);
            if (!rate.allowed()) {
                ApiException tooMany = rateLimiter.rejection(rate);
                recordDenied(operatorId, action, request.getRequestURI(), requestId, tooMany);
                errorRenderer.write(response, tooMany, requestId, rateLimiter.headers(rate));
                return;
            }
            rateLimiter.writeHeaders(response, rate);
            request.setAttribute(AdminPrincipal.REQUEST_ATTRIBUTE,
                    new AdminPrincipal(operatorId, null, op.roles()));
            chain.doFilter(request, response);
            // 컨트롤러 인가·검증 실패(403·400·5xx)는 @RestControllerAdvice가 내부에서 처리해
            // 예외로 전파되지 않는다. 응답 상태에서 result를 파생해 거부를 OK로 오라벨하지 않는다.
            int status = response.getStatus();
            try {
                audit.record(operatorId, action, request.getRequestURI(), requestId, null,
                        resultForStatus(status), Map.of("status", status));
            } catch (RuntimeException ignored) {
                // 변이는 이미 커밋됐다. 감사 적재 실패가 응답을 삼키지 않게 한다(거부 경로와 동일).
            }
        } catch (ApiException e) {
            recordDenied(certOperator, action, request.getRequestURI(), requestId, e);
            errorRenderer.write(response, e);
        }
    }

    private void recordDenied(String operatorId, String action, String target, String requestId,
                              ApiException e) {
        try {
            audit.record(operatorId, action, target, requestId,
                    null, e.status() >= 500 ? "ERROR" : "DENIED",
                    Map.of("error", e.name(), "status", e.status()));
        } catch (RuntimeException ignored) {
            // 감사 적재 실패가 원 응답을 삼키지 않게 한다.
        }
    }

    private static String resultForStatus(int status) {
        if (status >= 500) {
            return "ERROR";
        }
        if (status >= 400) {
            return "DENIED";
        }
        return "OK";
    }

    private static boolean isSessionBootstrap(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && request.getRequestURI().equals("/admin/v1/session");
    }

    /** 관제센터 즉시 끄기와 AI 정지(API §6-F 활성 조건). 신원·세션 검사와 감사는 다른 요청과 같다. */
    static boolean aiKillSwitch(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "POST".equals(request.getMethod())
                && (uri.equals("/admin/v1/msp/ai/mode/manual") || uri.equals("/admin/v1/msp/ai/stop"));
    }

    private static ApiException forbidden(String message, String reason) {
        return new ApiException(403, "admin_forbidden", message,
                java.util.Map.of("reason", reason), null);
    }

    static Optional<AdminPrincipal> principal(HttpServletRequest request) {
        Object p = request.getAttribute(AdminPrincipal.REQUEST_ATTRIBUTE);
        return p instanceof AdminPrincipal ap ? Optional.of(ap) : Optional.empty();
    }
}

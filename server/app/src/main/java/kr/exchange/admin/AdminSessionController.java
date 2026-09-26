package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 관리 세션 발급(§3-6). mTLS 통과 후 인증서 CN 유래 operator_id에 바인딩된 단기 세션을
 * 발급한다. 운영자 신원은 mTLS에서 유래하고 바디로 자가신고하지 않는다.
 */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class AdminSessionController {

    private final AdminSessionService sessions;
    private final AdminOperatorDirectory operators;
    private final AdminAuditService audit;
    private final AdminProperties props;

    public AdminSessionController(AdminSessionService sessions, AdminOperatorDirectory operators,
                                  AdminAuditService audit, AdminProperties props) {
        this.sessions = sessions;
        this.operators = operators;
        this.audit = audit;
        this.props = props;
    }

    @PostMapping("/session")
    public Map<String, Object> issue(HttpServletRequest request) {
        String certOperator = AdminRequestContext.certOperator(request);
        if (certOperator == null) {
            throw new ApiException(401, "jwt_verification",
                    "관리 세션 발급에는 mTLS 클라이언트 인증서가 필요합니다.");
        }
        // 부트스트랩 경로라 필터가 통과시킨다. 미등록·비활성 발급 시도(침해 신호)는
        // @RestControllerAdvice 흡수 전에 여기서 DENIED로 감사한다(§3-6 전 액션 감사).
        if (AdminOperatorDirectory.aiOperator(certOperator)) {
            throw denied(certOperator, request, "ai_operator", "AI 운영자 신원은 세션을 받을 수 없습니다.");
        }
        AdminOperatorDirectory.Operator op = operators.find(certOperator)
                .orElseThrow(() -> denied(certOperator, request, "unknown_operator",
                        "등록되지 않은 운영자입니다."));
        if (!op.active()) {
            throw denied(certOperator, request, "operator_disabled", "비활성 운영자입니다.");
        }
        AdminSessionService.IssuedSession issued = sessions.issue(certOperator, props.getSessionTtl());
        audit.record(certOperator, "POST /admin/v1/session", certOperator,
                AdminRequestContext.requestId(request), null, "OK", Map.of("jti", issued.jti()));
        return Map.of(
                "schema_version", "1.0",
                "token", issued.token(),
                "operator_id", certOperator,
                "display_name", op.displayName(),
                "roles", op.roles().stream().map(Enum::name).sorted().toList(),
                "expires_at", ApiFormats.time(Instant.ofEpochSecond(issued.expiresAt())));
    }

    private ApiException denied(String certOperator, HttpServletRequest request,
                                String reason, String message) {
        try {
            audit.record(certOperator, "POST /admin/v1/session", certOperator,
                    AdminRequestContext.requestId(request), null, "DENIED", Map.of("reason", reason));
        } catch (RuntimeException ignored) {
            // 감사 적재 실패가 원 거부 응답을 삼키지 않게 한다.
        }
        return new ApiException(403, "admin_forbidden", message, Map.of("reason", reason), null);
    }
}

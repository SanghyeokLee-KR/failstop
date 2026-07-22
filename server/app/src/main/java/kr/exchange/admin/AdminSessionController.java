package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
        AdminOperatorDirectory.Operator op = operators.find(certOperator)
                .orElseThrow(() -> new ApiException(403, "admin_forbidden",
                        "등록되지 않은 운영자입니다.", Map.of("reason", "unknown_operator"), null));
        if (!op.active()) {
            throw new ApiException(403, "admin_forbidden", "비활성 운영자입니다.",
                    Map.of("reason", "operator_disabled"), null);
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
                "expires_at", issued.expiresAt());
    }
}

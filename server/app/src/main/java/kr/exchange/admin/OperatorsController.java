package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 운영자·역할 관리(관리자 설계 §3-1). 조회는 VIEWER, 역할 부여·회수는 SUPER + 2인 승인.
 * SUPER 단독으로 요청·승인을 겸할 수 없다(반결탁, ApprovalService가 강제).
 */
@RestController
@RequestMapping("/admin/v1/operators")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class OperatorsController {

    public record RoleBody(String action, String role, String approval_token) {
    }

    private final AdminOperatorDirectory directory;
    private final JdbcClient jdbc;
    private final ApprovalService approvals;
    private final AdminAuditService audit;

    public OperatorsController(AdminOperatorDirectory directory, JdbcClient jdbc,
                               ApprovalService approvals, AdminAuditService audit) {
        this.directory = directory;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        List<Map<String, Object>> rows = directory.list().stream()
                .map(o -> Map.<String, Object>of(
                        "operator_id", o.operatorId(),
                        "display_name", o.displayName(),
                        "status", o.status(),
                        "roles", o.roles().stream().map(Enum::name).sorted().toList()))
                .toList();
        return Map.of("schema_version", "1.0", "data", rows);
    }

    @PostMapping("/{operatorId}/roles")
    @Transactional
    public Map<String, Object> changeRole(@PathVariable String operatorId,
                                          @RequestBody(required = false) RoleBody body,
                                          HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        if (body == null || body.action() == null || body.role() == null) {
            throw new ApiException(400, "invalid_parameter", "action과 role이 필요합니다.");
        }
        AdminRole role = parseRole(body.role());
        String grantAction = body.action().toUpperCase();
        if (!grantAction.equals("GRANT") && !grantAction.equals("REVOKE")) {
            throw new ApiException(400, "invalid_parameter", "action은 GRANT 또는 REVOKE여야 합니다.");
        }
        directory.find(operatorId).orElseThrow(() -> new ApiException(404, "not_found",
                "존재하지 않는 운영자입니다: " + operatorId));

        long approvalId = approvals.consume(principal.operatorId(),
                body.approval_token(), "operators.roles", operatorId + ":" + grantAction + ":" + role);

        if (grantAction.equals("GRANT")) {
            jdbc.sql("""
                            INSERT INTO operator_roles (operator_id, role) VALUES (?, ?)
                            ON CONFLICT (operator_id, role) DO NOTHING
                            """)
                    .params(operatorId, role.name()).update();
        } else {
            jdbc.sql("DELETE FROM operator_roles WHERE operator_id = ? AND role = ?")
                    .params(operatorId, role.name()).update();
        }
        audit.record(principal.operatorId(), "operators.roles", operatorId,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("grant_action", grantAction, "role", role.name()));
        return Map.of("schema_version", "1.0", "operator_id", operatorId,
                "action", grantAction, "role", role.name(), "approval_id", approvalId);
    }

    private static AdminRole parseRole(String raw) {
        try {
            return AdminRole.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid_parameter", "알 수 없는 역할입니다: " + raw);
        }
    }
}

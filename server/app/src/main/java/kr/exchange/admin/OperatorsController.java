package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 운영자·역할 관리(관리자 설계 §3-1). 조회는 VIEWER, 역할 부여·회수는 SUPER + 2인 승인.
 * SUPER 단독으로 요청·승인을 겸할 수 없다(반결탁, ApprovalService가 강제).
 */
@RestController
@RequestMapping("/admin/v1/operators")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class OperatorsController {

    private static final Set<String> OPERATOR_STATUSES = Set.of("ACTIVE", "DISABLED");

    public record RoleBody(String action, String role, String approval_token) {
    }

    // operator_id는 수행자 공통 필드(§3-6), 생성 대상 신원은 new_operator_id로 분리한다.
    public record CreateBody(String operator_id, String new_operator_id, String display_name,
                             String approval_token) {
    }

    public record StatusBody(String status, String approval_token) {
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

    /**
     * 운영자 생성(파괴 액션, SUPER + 2인 승인). 생성 대상 신원은 new_operator_id다. 여기서는
     * 레지스트리 등록만 하고 역할은 부여하지 않는다(부여는 roles 별도 승인 경로). 중복 신원은 409.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) CreateBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        if (body == null || body.new_operator_id() == null || body.new_operator_id().isBlank()
                || body.display_name() == null || body.display_name().isBlank()) {
            throw new ApiException(400, "invalid_parameter",
                    "new_operator_id와 display_name이 필요합니다.");
        }
        String operatorId = body.new_operator_id().trim();
        long approvalId = approvals.consume(principal.operatorId(), body.approval_token(),
                "operators.create", operatorId);
        // 승인 소각과 같은 트랜잭션에서 중복을 검사해 실패 시 토큰이 롤백·재사용 가능하게 한다.
        if (directory.find(operatorId).isPresent()) {
            throw new ApiException(409, "conflict", "이미 존재하는 운영자입니다: " + operatorId);
        }
        jdbc.sql("INSERT INTO admin_operators (operator_id, display_name) VALUES (?, ?)")
                .params(operatorId, body.display_name().trim()).update();
        audit.record(principal.operatorId(), "operators.create", operatorId,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("display_name", body.display_name().trim()));
        Map<String, Object> out = Map.of("schema_version", "1.0", "operator_id", operatorId,
                "status", "ACTIVE", "approval_id", approvalId);
        return ResponseEntity.status(201).body(out);
    }

    /**
     * 운영자 상태 전이(파괴 액션, SUPER + 2인 승인). ACTIVE|DISABLED. DISABLED 운영자는
     * 인증 필터가 세션 발급·요청을 거절한다(신원 정지). 존재하지 않으면 404.
     */
    @PatchMapping("/{operatorId}/status")
    @Transactional
    public Map<String, Object> changeStatus(@PathVariable String operatorId,
                                            @RequestBody(required = false) StatusBody body,
                                            HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        if (body == null || body.status() == null) {
            throw new ApiException(400, "invalid_parameter", "status가 필요합니다.");
        }
        String status = body.status().toUpperCase();
        if (!OPERATOR_STATUSES.contains(status)) {
            throw new ApiException(400, "invalid_parameter", "status는 ACTIVE 또는 DISABLED여야 합니다.");
        }
        long approvalId = approvals.consume(principal.operatorId(), body.approval_token(),
                "operators.status", operatorId + ":" + status);
        int affected = jdbc.sql("UPDATE admin_operators SET status = ? WHERE operator_id = ?")
                .params(status, operatorId).update();
        if (affected == 0) {
            throw new ApiException(404, "not_found", "존재하지 않는 운영자입니다: " + operatorId);
        }
        audit.record(principal.operatorId(), "operators.status", operatorId,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("status", status));
        return Map.of("schema_version", "1.0", "operator_id", operatorId,
                "status", status, "approval_id", approvalId);
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

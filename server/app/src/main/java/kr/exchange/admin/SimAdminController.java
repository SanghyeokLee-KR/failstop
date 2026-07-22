package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.sim.OpsGovernor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 봇 플릿 제어(관리자 설계 §2-1, 정본 §6-A 소비). 전역 OPS 변경은 OpsGovernor 런타임 훅에
 * 반영한다. 봇은 별도 프로파일(bots)에서 기동하므로 OpsGovernor가 없는 배포에서는 제어 의도만
 * 감사에 남긴다. 실행 중 파라미터 변경(§3-6 완결 열거)만 approval_token 필수다.
 */
@RestController
@RequestMapping("/admin/v1/sim")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class SimAdminController {

    public record RateBody(String target_ops) {
    }

    public record ParamsBody(String approval_token, Map<String, Object> params) {
    }

    private final ObjectProvider<OpsGovernor> opsGovernor;
    private final ApprovalService approvals;
    private final AdminAuditService audit;

    public SimAdminController(ObjectProvider<OpsGovernor> opsGovernor, ApprovalService approvals,
                             AdminAuditService audit) {
        this.opsGovernor = opsGovernor;
        this.approvals = approvals;
        this.audit = audit;
    }

    @org.springframework.web.bind.annotation.GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        OpsGovernor governor = opsGovernor.getIfAvailable();
        return Map.of(
                "schema_version", "1.0",
                "fleet_present", governor != null,
                "target_ops", governor == null ? "0" : String.valueOf(governor.targetOps()));
    }

    @PutMapping("/rate")
    public Map<String, Object> setRate(@RequestBody RateBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        double target = parsePositive(body == null ? null : body.target_ops());
        OpsGovernor governor = opsGovernor.getIfAvailable();
        if (governor != null) {
            governor.setTargetOps(target);
        }
        audit.record(principal.operatorId(), "sim.rate", null,
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("target_ops", String.valueOf(target), "applied", governor != null));
        return Map.of("schema_version", "1.0", "target_ops", String.valueOf(target),
                "applied", governor != null);
    }

    @PostMapping("/{command}")
    public Map<String, Object> control(@PathVariable String command, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        if (!command.equals("start") && !command.equals("stop") && !command.equals("pause")) {
            throw new ApiException(404, "not_found", "알 수 없는 제어 명령입니다: " + command);
        }
        audit.record(principal.operatorId(), "sim." + command, null,
                AdminRequestContext.requestId(request), null, "OK", Map.of());
        return Map.of("schema_version", "1.0", "command", command);
    }

    @PatchMapping("/bots/{id}/params")
    @Transactional
    public Map<String, Object> updateParams(@PathVariable String id,
                                            @RequestBody(required = false) ParamsBody body,
                                            HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        String token = body == null ? null : body.approval_token();
        long approvalId = approvals.consume(principal.operatorId(), token,
                "sim.bots.params", id);
        audit.record(principal.operatorId(), "sim.bots.params", id,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("params", body == null || body.params() == null ? Map.of() : body.params()));
        return Map.of("schema_version", "1.0", "bot_id", id, "approval_id", approvalId);
    }

    private static double parsePositive(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "invalid_parameter", "target_ops가 필요합니다.");
        }
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", "target_ops 형식이 올바르지 않습니다.");
        }
        if (v <= 0) {
            throw new ApiException(400, "invalid_parameter", "target_ops는 양수여야 합니다.");
        }
        return v;
    }
}

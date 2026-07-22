package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 카오스 실험 콘솔(관리자 설계 §2-2, 정본 §6-B). 파괴 액션은 전부 CHAOS_OPS + 2인 승인
 * 필수다. 실제 주입은 격리 스택의 카오스 주입기가 승인된 명령을 소비하는 경계다. 이 컨트롤러는
 * 승인·감사·experiment_id 발급 지점이며, 관측 데이터를 근거로 자동 주입하지 않는다(§6 안전).
 */
@RestController
@RequestMapping("/admin/v1/chaos")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class ChaosAdminController {

    public record ChaosBody(String approval_token, String target, Map<String, Object> params) {
    }

    private static final List<Map<String, Object>> CATALOG = List.of(
            catalog("E1", "PG 지연·지터", "chaos.network"),
            catalog("E2", "PG 단절·블랙홀", "chaos.network"),
            catalog("E3", "Redis 장애", "chaos.network"),
            catalog("E4", "매칭 리더 kill", "chaos.kill_node"),
            catalog("E5", "네트워크 분할", "chaos.network"),
            catalog("E6", "폭주 + 리더 kill 복합", "chaos.kill_node"),
            catalog("E7", "그레이 장애(pause)", "chaos.kill_node"),
            catalog("E8", "앱 내부 지연·예외 폭풍", "chaos.app"),
            catalog("E9", "커밋 응답 절단", "chaos.network"));

    private final ApprovalService approvals;
    private final AdminAuditService audit;

    public ChaosAdminController(ApprovalService approvals, AdminAuditService audit) {
        this.approvals = approvals;
        this.audit = audit;
    }

    @GetMapping("/experiments")
    public Map<String, Object> experiments(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        return Map.of("schema_version", "1.0", "catalog", CATALOG);
    }

    @PostMapping("/kill-node")
    @Transactional
    public Map<String, Object> killNode(@RequestBody(required = false) ChaosBody body,
                                        HttpServletRequest request) {
        return inject("chaos.kill_node", body, request);
    }

    @PostMapping("/network")
    @Transactional
    public Map<String, Object> network(@RequestBody(required = false) ChaosBody body,
                                       HttpServletRequest request) {
        return inject("chaos.network", body, request);
    }

    @PostMapping("/app")
    @Transactional
    public Map<String, Object> app(@RequestBody(required = false) ChaosBody body,
                                   HttpServletRequest request) {
        return inject("chaos.app", body, request);
    }

    @DeleteMapping("/network/{toxic}")
    @Transactional
    public Map<String, Object> clearNetwork(@PathVariable String toxic,
                                            @RequestBody(required = false) ChaosBody body,
                                            HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.CHAOS_OPS);
        String token = body == null ? null : body.approval_token();
        long approvalId = approvals.consume(principal.operatorId(), token, "chaos.network.clear", toxic);
        audit.record(principal.operatorId(), "chaos.network.clear", toxic,
                AdminRequestContext.requestId(request), approvalId, "OK", Map.of());
        return Map.of("schema_version", "1.0", "toxic", toxic, "approval_id", approvalId);
    }

    private Map<String, Object> inject(String action, ChaosBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.CHAOS_OPS);
        String target = body == null || body.target() == null ? "" : body.target();
        String token = body == null ? null : body.approval_token();
        long approvalId = approvals.consume(principal.operatorId(), token, action, target);
        String experimentId = UUID.randomUUID().toString();
        audit.record(principal.operatorId(), action, target,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("experiment_id", experimentId,
                        "params", body == null || body.params() == null ? Map.of() : body.params()));
        return Map.of("schema_version", "1.0", "action", action, "target", target,
                "experiment_id", experimentId, "approval_id", approvalId);
    }

    private static Map<String, Object> catalog(String id, String label, String action) {
        return Map.of("id", id, "label", label, "action", action, "approval_required", true);
    }
}

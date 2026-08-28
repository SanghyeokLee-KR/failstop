package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    // 바인딩 target을 엔드포인트 의미 필드에서 취한다(§3-6 확정표). kill-node=node,
    // network=name, app=assault. 균일 target 단일 필드는 발급 target과 어긋나 403을 유발했다.
    public record ChaosBody(String approval_token, String node, String method, Boolean expect_failover,
                            String name, String type, String assault) {
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
    private final JdbcClient jdbc;

    public ChaosAdminController(ApprovalService approvals, AdminAuditService audit, JdbcClient jdbc) {
        this.approvals = approvals;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @GetMapping("/experiments")
    public Map<String, Object> experiments(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        return Map.of("schema_version", "1.0", "catalog", CATALOG);
    }

    /**
     * 카오스 주입·해제 이력(승인 불요, VIEWER+). admin_audit_log의 chaos.* 액션을 투사한다.
     * experiment_id는 주입 시 detail JSON에 적재되므로 그대로 뽑아 프론트 이력에 노출한다.
     */
    @GetMapping("/runs")
    public Map<String, Object> runs(HttpServletRequest request,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "50") int limit) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        int capped = CursorPages.cap(limit, 200);
        Cursors.Cursor decoded = CursorPages.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT id, operator_id, action, target, approval_id, result,
                       detail->>'experiment_id' AS experiment_id, created_at
                FROM admin_audit_log
                WHERE action LIKE 'chaos.%'
                """);
        List<Object> params = new ArrayList<>();
        if (decoded != null) {
            long anchorId = CursorPages.numericId(decoded.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT created_at FROM admin_audit_log WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject("created_at", OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (created_at, id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        // 실험 이력은 감사 로그에 영구 누적된다. 한 건 더 읽어 다음 쪽 존재를 판단한다
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(capped + 1);
        List<CursorPages.Row<Map<String, Object>>> fetched = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new CursorPages.Row<Map<String, Object>>(Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "operator_id", rs.getString("operator_id") == null
                                ? "" : rs.getString("operator_id"),
                        "action", rs.getString("action"),
                        "target", rs.getString("target") == null ? "" : rs.getString("target"),
                        "approval_id", rs.getObject("approval_id") == null
                                ? "" : rs.getLong("approval_id"),
                        "result", rs.getString("result"),
                        "experiment_id", rs.getString("experiment_id") == null
                                ? "" : rs.getString("experiment_id"),
                        "created_at", ts(rs.getObject("created_at", OffsetDateTime.class))),
                        rs.getObject("created_at", OffsetDateTime.class),
                        String.valueOf(rs.getLong("id"))))
                .list();
        CursorPages.Page<Map<String, Object>> page = CursorPages.slice(fetched, capped);
        return Map.of("schema_version", "1.0", "data", page.data(),
                "pagination", CursorPages.pagination(page));
    }

    @PostMapping("/kill-node")
    @Transactional
    public Map<String, Object> killNode(@RequestBody(required = false) ChaosBody body,
                                        HttpServletRequest request) {
        return inject("chaos.kill_node", body == null ? null : body.node(), body, request);
    }

    @PostMapping("/network")
    @Transactional
    public Map<String, Object> network(@RequestBody(required = false) ChaosBody body,
                                       HttpServletRequest request) {
        return inject("chaos.network", body == null ? null : body.name(), body, request);
    }

    @PostMapping("/app")
    @Transactional
    public Map<String, Object> app(@RequestBody(required = false) ChaosBody body,
                                   HttpServletRequest request) {
        return inject("chaos.app", body == null ? null : body.assault(), body, request);
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

    private Map<String, Object> inject(String action, String target, ChaosBody body,
                                       HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.CHAOS_OPS);
        String t = target == null ? "" : target;
        String token = body == null ? null : body.approval_token();
        long approvalId = approvals.consume(principal.operatorId(), token, action, t);
        String experimentId = UUID.randomUUID().toString();
        audit.record(principal.operatorId(), action, t,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("experiment_id", experimentId, "params", chaosParams(body)));
        return Map.of("schema_version", "1.0", "action", action, "target", t,
                "experiment_id", experimentId, "approval_id", approvalId);
    }

    // 의미 필드 중 target 외 서술 필드만 감사 detail에 남긴다(균일 params 맵 폐기 후 관측 보존).
    private static Map<String, Object> chaosParams(ChaosBody body) {
        if (body == null) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (body.method() != null) {
            params.put("method", body.method());
        }
        if (body.expect_failover() != null) {
            params.put("expect_failover", body.expect_failover());
        }
        if (body.type() != null) {
            params.put("type", body.type());
        }
        return params;
    }

    private static Map<String, Object> catalog(String id, String label, String action) {
        return Map.of("id", id, "label", label, "action", action, "approval_required", true);
    }

    // API §2-1 UTC 밀리초 3자리 고정. OffsetDateTime.toString의 가변 소수부를 쓰지 않는다.
    private static String ts(OffsetDateTime odt) {
        return odt == null ? "" : kr.exchange.api.common.ApiFormats.time(odt);
    }
}

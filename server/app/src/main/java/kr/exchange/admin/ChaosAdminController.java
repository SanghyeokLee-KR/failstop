package kr.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 카오스 실험 콘솔(관리자 설계 §2-2, 정본 §6-B). 파괴 액션은 전부 CHAOS_OPS를 요구하고,
 * approval_token은 제시 시에만 검증·소각한다(§3-6 v2.34 카오스 승인 완화, consumeIfPresent). 실제 주입은 격리 스택의 카오스 주입기가 승인된 명령을 소비하는 경계다. 이 컨트롤러는
 * 승인·감사·experiment_id 발급 지점이며, 관측 데이터를 근거로 자동 주입하지 않는다(§6 안전).
 */
@RestController
@RequestMapping("/admin/v1/chaos")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class ChaosAdminController {

    // 바인딩 target을 엔드포인트 의미 필드에서 취한다(§3-6 확정표). kill-node=node,
    // network=name, app=assault. 균일 target 단일 필드는 발급 target과 어긋나 403이 된다.
    public record ChaosBody(String approval_token, String node, String method, Boolean expect_failover,
                            String name, String type, String assault, String experiment) {
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
    private final ObjectMapper objectMapper;

    public ChaosAdminController(ApprovalService approvals, AdminAuditService audit,
                                JdbcClient jdbc, ObjectMapper objectMapper) {
        this.approvals = approvals;
        this.audit = audit;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
                SELECT a.id, a.operator_id, a.action, a.target, a.approval_id, a.result,
                       a.detail->>'experiment_id' AS experiment_id, a.created_at,
                       c.state AS exec_state, c.result AS exec_result
                FROM admin_audit_log a
                LEFT JOIN chaos_commands c
                       ON c.experiment_id::text = a.detail->>'experiment_id'
                WHERE a.action LIKE 'chaos.%'
                """);
        List<Object> params = new ArrayList<>();
        if (decoded != null) {
            long anchorId = CursorPages.numericId(decoded.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT created_at FROM admin_audit_log WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject("created_at", OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (a.created_at, a.id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        // 실험 이력은 감사 로그에 영구 누적된다. 한 건 더 읽어 다음 쪽 존재를 판단한다
        sql.append(" ORDER BY a.created_at DESC, a.id DESC LIMIT ?");
        params.add(capped + 1);
        List<CursorPages.Row<Map<String, Object>>> fetched = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("operator_id", rs.getString("operator_id") == null
                            ? "" : rs.getString("operator_id"));
                    row.put("action", rs.getString("action"));
                    row.put("target", rs.getString("target") == null ? "" : rs.getString("target"));
                    row.put("approval_id", rs.getObject("approval_id") == null
                            ? "" : rs.getLong("approval_id"));
                    row.put("result", rs.getString("result"));
                    row.put("experiment_id", rs.getString("experiment_id") == null
                            ? "" : rs.getString("experiment_id"));
                    // 지시가 실제로 집행됐는지. 소비기 미가동 배포는 PENDING에 머문다
                    row.put("exec_state", rs.getString("exec_state") == null
                            ? "" : rs.getString("exec_state"));
                    row.put("exec_result", rs.getString("exec_result") == null
                            ? "" : rs.getString("exec_result"));
                    row.put("created_at", ts(rs.getObject("created_at", OffsetDateTime.class)));
                    return new CursorPages.Row<Map<String, Object>>(row,
                            rs.getObject("created_at", OffsetDateTime.class),
                            String.valueOf(rs.getLong("id")));
                })
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
        String safeToxic = requireSafeTarget(toxic);
        Long approvalId = consumeIfPresent(principal.operatorId(), token,
                "chaos.network.clear", safeToxic);
        String experimentId = UUID.randomUUID().toString();
        audit.record(principal.operatorId(), "chaos.network.clear", toxic,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("experiment_id", experimentId));
        enqueue(experimentId, "chaos.network.clear", safeToxic,
                body == null ? null : body.experiment(), Map.of(),
                principal.operatorId(), approvalId);
        // Map.of는 null 값에 NPE다. 승인 없이 실행하면 approval_id가 null이라 순서 보존 맵을 쓴다.
        // 키를 빼지 않고 null로 싣는다. 부재와 미승인은 다른 사실이고 뒤쪽이 참이다
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1.0");
        out.put("toxic", toxic);
        out.put("approval_id", approvalId);
        out.put("experiment_id", experimentId);
        out.put("state", "PENDING");
        return out;
    }

    private Map<String, Object> inject(String action, String target, ChaosBody body,
                                       HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.CHAOS_OPS);
        String t = requireSafeTarget(target);
        String token = body == null ? null : body.approval_token();
        Long approvalId = consumeIfPresent(principal.operatorId(), token, action, t);
        String experimentId = UUID.randomUUID().toString();
        audit.record(principal.operatorId(), action, t,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("experiment_id", experimentId, "params", chaosParams(body)));
        enqueue(experimentId, action, t, body == null ? null : body.experiment(),
                chaosParams(body), principal.operatorId(), approvalId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1.0");
        out.put("action", action);
        out.put("target", t);
        out.put("experiment_id", experimentId);
        out.put("approval_id", approvalId);
        out.put("state", "PENDING");
        return out;
    }

    /**
     * 명령을 큐에 적재한다(V21). 이 서버는 주입을 수행하지 않고 지시만 남긴다. 실제 주입은
     * 하네스 소비기가 이 행을 점유해 수행하고 결과를 되쓴다(하네스 CICD §6-2).
     *
     * 감사 로그가 아니라 별도 표인 이유는 감사가 append-only라 "처리됨"을 적을 자리가 없고,
     * 자리가 없으면 소비기 재시작이 같은 지시를 다시 읽어 리더를 두 번 죽이기 때문이다.
     *
     * 이 서버는 외부 부수효과를 내지 않으므로 승인 소각과 같은 트랜잭션에 묶는 것이 맞다.
     * 소각만 커밋되고 지시가 유실되면 승인은 탔는데 아무 일도 안 일어난다.
     */
    /**
     * 승인 토큰이 있으면 검증·소각하고, 없으면 승인 없이 진행한다(§3-6 v2.34).
     *
     * 카오스 4종을 approval_token 필수 열거에서 뺀 이유는 운영 규율이 아니라 운영 인원이다.
     * 2인 승인은 발급 주체와 사용 주체가 서로 달라야 성립하는데(반결탁, chk_approval_two_person)
     * 이 배포의 카오스 실행 주체는 1인이다. 1인 운영에서는 그 조건을 만족시킬 방법이 없어
     * 실험을 못 돌리거나 운영자 계정을 하나 더 만들어 혼자 두 역을 하게 된다. 뒤쪽은 2인 통제를
     * 형해화하면서 형식만 남기므로 규정을 사실에 맞춘다.
     *
     * 토큰을 실으면 종전 계약이 그대로 걸린다. 소각·TTL·바인딩·반결탁 전부 남아 있어
     * 2인 운영 배포는 코드 변경 없이 종전대로 쓴다. 역할 게이트(CHAOS_OPS)와 대상 검증,
     * 전 액션 감사 적재도 승인 유무와 무관하게 그대로다.
     */
    private Long consumeIfPresent(String operatorId, String token, String action, String target) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return approvals.consume(operatorId, token, action, target);
    }

    private void enqueue(String experimentId, String action, String target, String experiment,
                         Map<String, Object> params, String operatorId, Long approvalId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (Exception e) {
            json = "{}";
        }
        jdbc.sql("""
                        INSERT INTO chaos_commands
                            (experiment_id, action, target, experiment, params,
                             requested_by, approval_id)
                        VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?, ?)
                        """)
                .params(experimentId, action, target, normExperiment(experiment), json,
                        operatorId, approvalId)
                .update();
    }

    /**
     * 대상 자원명 검증. 이 값은 소비기에서 셸 문자열로 보간돼 docker 명령이 되므로,
     * 셸 메타문자가 통과하면 소비기 호스트에서 임의 명령이 실행된다. 컨테이너·toxic·assault
     * 이름은 전부 영숫자와 -_. 뿐이라 여기서 좁히는 것이 손실이 없다.
     */
    private static String requireSafeTarget(String target) {
        String t = target == null ? "" : target.trim();
        if (!t.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")) {
            throw new ApiException(400, "invalid_parameter",
                    "대상 이름은 영숫자와 -_. 만 허용");
        }
        return t;
    }

    /** 카탈로그 식별자만 받는다. 소비기가 이 값으로 주입 경로를 가르므로 임의 문자열을 막는다 */
    private static String normExperiment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return v.matches("E[1-9]") ? v : null;
    }

    // 의미 필드 중 target 외 서술 필드만 감사 detail에 남긴다.
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
        // 카탈로그 9종이 전부 카오스 4종 액션이라 v2.34 완화로 승인이 불요다
        return Map.of("id", id, "label", label, "action", action, "approval_required", false);
    }

    // API §2-1 UTC 밀리초 3자리 고정. OffsetDateTime.toString의 가변 소수부를 쓰지 않는다.
    private static String ts(OffsetDateTime odt) {
        return odt == null ? "" : kr.exchange.api.common.ApiFormats.time(odt);
    }
}

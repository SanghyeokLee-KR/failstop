package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 감사 로그 조회(관리자 설계 §3-1). append-only admin_audit_log를 운영자·action 필터로 읽는다.
 * 조회는 VIEWER 이상. 감사 자체도 로그 대상이나 조회 폭주 방지를 위해 필터가 코스 감사를 남긴다.
 */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class AuditController {

    private final JdbcClient jdbc;

    public AuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(HttpServletRequest request,
                                     @RequestParam(required = false) String operator,
                                     @RequestParam(required = false) String action,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                     OffsetDateTime from,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                     OffsetDateTime to,
                                     @RequestParam(defaultValue = "100") int limit) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        int capped = Math.max(1, Math.min(limit, 500));
        StringBuilder sql = new StringBuilder("""
                SELECT operator_id, action, target, result, approval_id, created_at
                FROM admin_audit_log WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (operator != null && !operator.isBlank()) {
            sql.append(" AND operator_id = ?");
            params.add(operator);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            params.add(to);
        }
        // created_at 동률 시 id로 안정 정렬해 LIMIT 절단이 비결정으로 흔들리지 않게 한다.
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(capped);
        List<Map<String, Object>> rows = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> Map.<String, Object>of(
                        "operator_id", rs.getString("operator_id") == null
                                ? "" : rs.getString("operator_id"),
                        "action", rs.getString("action"),
                        "target", rs.getString("target") == null ? "" : rs.getString("target"),
                        "result", rs.getString("result"),
                        "approval_id", rs.getObject("approval_id") == null
                                ? "" : rs.getLong("approval_id"),
                        "created_at", String.valueOf(rs.getObject("created_at", OffsetDateTime.class))))
                .list();
        return Map.of("schema_version", "1.0", "data", rows);
    }
}

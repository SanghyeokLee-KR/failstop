package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
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
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(defaultValue = "100") int limit) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        int capped = CursorPages.cap(limit, 500);
        Cursors.Cursor decoded = CursorPages.decode(cursor);
        // display_name은 admin_operators LEFT JOIN(인증 실패 감사는 operator_id NULL). 필터는
        // 애매성 방지를 위해 a. 접두사로 admin_audit_log 컬럼을 명시한다.
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.operator_id, o.display_name, a.action, a.target, a.request_id,
                       a.result, a.approval_id, a.created_at
                FROM admin_audit_log a
                LEFT JOIN admin_operators o ON o.operator_id = a.operator_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (operator != null && !operator.isBlank()) {
            sql.append(" AND a.operator_id = ?");
            params.add(operator);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND a.action = ?");
            params.add(action);
        }
        if (from != null) {
            sql.append(" AND a.created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND a.created_at <= ?");
            params.add(to);
        }
        if (decoded != null) {
            /* 커서의 ts를 그대로 바인딩하지 않는다. 보존 정밀도와 컬럼 정밀도가 어긋나면
               같은 시각 경계 행이 다음 쪽에서 사라진다. 실제 행 값을 다시 읽어 쓴다.
               감사 로그는 append-only라(V5 트리거) 앵커 행이 사라지는 경우가 없다 */
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
        // created_at 동률 시 id로 안정 정렬해 LIMIT 절단이 비결정으로 흔들리지 않게 한다.
        // 한 건 더 읽어 다음 쪽 존재를 판단한다. 감사 로그는 영구 누적이라 고정 상한으로
        // 자르면 그 밖의 기록에 도달할 방법이 없다. 사고 조사가 목적인 기능이 조용히
        // 잘리면 "기록이 없다"와 "기록을 못 본다"를 운영자가 구분할 수 없다
        sql.append(" ORDER BY a.created_at DESC, a.id DESC LIMIT ?");
        params.add(capped + 1);
        List<CursorPages.Row<Map<String, Object>>> fetched = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new CursorPages.Row<Map<String, Object>>(Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "operator_id", rs.getString("operator_id") == null
                                ? "" : rs.getString("operator_id"),
                        "display_name", rs.getString("display_name") == null
                                ? "" : rs.getString("display_name"),
                        "action", rs.getString("action"),
                        "target", rs.getString("target") == null ? "" : rs.getString("target"),
                        "request_id", rs.getString("request_id") == null
                                ? "" : rs.getString("request_id"),
                        "result", rs.getString("result"),
                        "approval_id", rs.getObject("approval_id") == null
                                ? "" : rs.getLong("approval_id"),
                        "created_at", ts(rs.getObject("created_at", OffsetDateTime.class))),
                        rs.getObject("created_at", OffsetDateTime.class),
                        String.valueOf(rs.getLong("id"))))
                .list();
        CursorPages.Page<Map<String, Object>> page = CursorPages.slice(fetched, capped);
        return Map.of("schema_version", "1.0", "data", page.data(),
                "pagination", CursorPages.pagination(page));
    }

    // API §2-1 UTC 밀리초 3자리 고정. OffsetDateTime.toString의 가변 소수부·오프셋 표기를 쓰지 않는다.
    private static String ts(OffsetDateTime odt) {
        return odt == null ? "" : kr.exchange.api.common.ApiFormats.time(odt);
    }
}

package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 마켓 운영 구성(관리자 설계 §2, §4-2). 마켓 halt/resume은 파괴 액션이라 2인 승인 필수
 * (사용자 확정 결정). 마켓 상태·수수료는 markets 컬럼이 정본이고 공지는 notices에 둔다.
 */
@RestController
@RequestMapping("/admin/v1/config")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class ConfigAdminController {

    public record ApprovalBody(String approval_token) {
    }

    public record NoticeBody(String title, String body, String severity) {
    }

    private final JdbcClient jdbc;
    private final ApprovalService approvals;
    private final AdminAuditService audit;

    public ConfigAdminController(JdbcClient jdbc, ApprovalService approvals, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
    }

    @GetMapping("/markets")
    public Map<String, Object> markets(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT symbol, status, maker_fee_rate::text AS maker_fee,
                               taker_fee_rate::text AS taker_fee
                        FROM markets ORDER BY id
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "market", rs.getString("symbol"),
                        "status", rs.getString("status"),
                        "maker_fee", rs.getString("maker_fee"),
                        "taker_fee", rs.getString("taker_fee")))
                .list();
        return Map.of("schema_version", "1.0", "data", rows);
    }

    @PostMapping("/markets/{symbol}/halt")
    @Transactional
    public Map<String, Object> halt(@PathVariable String symbol,
                                    @RequestBody(required = false) ApprovalBody body,
                                    HttpServletRequest request) {
        return transition(symbol, "HALTED", "config.market.halt", body, request);
    }

    @PostMapping("/markets/{symbol}/resume")
    @Transactional
    public Map<String, Object> resume(@PathVariable String symbol,
                                      @RequestBody(required = false) ApprovalBody body,
                                      HttpServletRequest request) {
        return transition(symbol, "ACTIVE", "config.market.resume", body, request);
    }

    private Map<String, Object> transition(String symbol, String targetStatus, String action,
                                           ApprovalBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        String token = body == null ? null : body.approval_token();
        long approvalId = approvals.consume(principal.operatorId(), token, action, symbol);
        int updated = jdbc.sql("UPDATE markets SET status = ?, updated_at = now() WHERE symbol = ?")
                .params(targetStatus, symbol).update();
        if (updated == 0) {
            throw new ApiException(404, "market_not_found", "존재하지 않는 마켓입니다: " + symbol);
        }
        audit.record(principal.operatorId(), action, symbol,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("status", targetStatus));
        return Map.of("schema_version", "1.0", "market", symbol, "status", targetStatus,
                "approval_id", approvalId);
    }

    @PostMapping("/notices")
    public Map<String, Object> publishNotice(@RequestBody NoticeBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new ApiException(400, "invalid_parameter", "공지 제목이 필요합니다.");
        }
        String severity = body.severity() == null ? "INFO" : body.severity();
        long id = jdbc.sql("""
                        INSERT INTO notices (title, body, severity, published_by)
                        VALUES (?, ?, ?, ?) RETURNING id
                        """)
                .params(body.title(), body.body() == null ? "" : body.body(),
                        severity, principal.operatorId())
                .query(Long.class).single();
        audit.record(principal.operatorId(), "config.notice.publish", String.valueOf(id),
                AdminRequestContext.requestId(request), null, "OK", Map.of("title", body.title()));
        return Map.of("schema_version", "1.0", "notice_id", id);
    }

    @GetMapping("/notices")
    public Map<String, Object> notices(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, title, severity, active, published_by, published_at
                        FROM notices ORDER BY published_at DESC LIMIT 100
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "title", rs.getString("title"),
                        "severity", rs.getString("severity"),
                        "active", rs.getBoolean("active"),
                        "published_by", rs.getString("published_by") == null
                                ? "" : rs.getString("published_by")))
                .list();
        return Map.of("schema_version", "1.0", "data", rows);
    }
}

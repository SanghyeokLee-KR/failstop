package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 마켓 운영 구성(관리자 설계 §2, §4-2). 마켓 halt/resume은 파괴 액션이라 2인 승인 필수
 * (사용자 확정 결정). 마켓 상태·수수료는 markets 컬럼이 정본이고 공지는 notices에 둔다.
 */
@RestController
@RequestMapping("/admin/v1/config")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class ConfigAdminController {

    private static final Set<String> NOTICE_SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");

    // NUMERIC(10,8) BETWEEN 0 AND 0.1(V1 markets). 스케일 초과·범위 밖은 앱에서 400으로 반려해
    // DB CHECK(23514)로 새지 않게 한다.
    private static final BigDecimal FEE_MAX = new BigDecimal("0.1");
    private static final int FEE_SCALE = 8;

    public record ApprovalBody(String approval_token) {
    }

    public record NoticeBody(String title, String body, String severity) {
    }

    public record FeeBody(String maker_fee, String taker_fee, String approval_token) {
    }

    public record NoticeUpdateBody(String title, String body, String severity, Boolean active) {
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
        // 현재 상태를 잠그고 검증한다. DELISTED는 종국 상태라 halt/resume으로 되살릴 수 없다.
        // 승인 토큰은 consume과 같은 트랜잭션이라 여기서 throw하면 롤백돼 재사용 가능하다.
        String current = jdbc.sql("SELECT status FROM markets WHERE symbol = ? FOR UPDATE")
                .param(symbol).query(String.class).optional().orElse(null);
        if (current == null) {
            throw new ApiException(404, "market_not_found", "존재하지 않는 마켓입니다: " + symbol);
        }
        if ("DELISTED".equals(current)) {
            throw new ApiException(409, "market_delisted",
                    "상장폐지 마켓은 상태 전이 불가: " + symbol);
        }
        jdbc.sql("UPDATE markets SET status = ?, updated_at = now() WHERE symbol = ?")
                .params(targetStatus, symbol).update();
        audit.record(principal.operatorId(), action, symbol,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("status", targetStatus));
        return Map.of("schema_version", "1.0", "market", symbol, "status", targetStatus,
                "approval_id", approvalId);
    }

    /**
     * 수수료 변경(파괴 액션, SUPER + 2인 승인). maker/taker는 부분 갱신 허용, 값은 문자열이다.
     * 수수료 권위 소스는 markets 컬럼(§4-A-1). 승인 토큰과 UPDATE는 같은 트랜잭션이라 검증 실패
     * 시 함께 롤백돼 토큰이 재사용 가능하다.
     */
    @PatchMapping("/markets/{symbol}/fees")
    @Transactional
    public Map<String, Object> updateFees(@PathVariable String symbol,
                                          @RequestBody(required = false) FeeBody body,
                                          HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        if (body == null || (body.maker_fee() == null && body.taker_fee() == null)) {
            throw new ApiException(400, "invalid_parameter",
                    "maker_fee 또는 taker_fee 중 하나가 필요합니다.");
        }
        BigDecimal maker = parseFee(body.maker_fee(), "maker_fee");
        BigDecimal taker = parseFee(body.taker_fee(), "taker_fee");
        String token = body.approval_token();
        long approvalId = approvals.consume(principal.operatorId(), token, "config.market.fees", symbol);
        // 현재 값을 잠그고 부분 갱신 대상만 교체한다. 없는 마켓은 승인과 함께 롤백된다.
        List<BigDecimal> current = jdbc.sql("""
                        SELECT maker_fee_rate, taker_fee_rate FROM markets WHERE symbol = ? FOR UPDATE
                        """)
                .param(symbol)
                .query((rs, n) -> List.of(rs.getBigDecimal("maker_fee_rate"),
                        rs.getBigDecimal("taker_fee_rate")))
                .optional()
                .orElseThrow(() -> new ApiException(404, "market_not_found",
                        "존재하지 않는 마켓입니다: " + symbol));
        BigDecimal makerFinal = maker == null ? current.get(0) : maker;
        BigDecimal takerFinal = taker == null ? current.get(1) : taker;
        jdbc.sql("""
                        UPDATE markets SET maker_fee_rate = ?, taker_fee_rate = ?, updated_at = now()
                        WHERE symbol = ?
                        """)
                .params(makerFinal, takerFinal, symbol).update();
        audit.record(principal.operatorId(), "config.market.fees", symbol,
                AdminRequestContext.requestId(request), approvalId, "OK",
                Map.of("maker_fee", makerFinal.toPlainString(), "taker_fee", takerFinal.toPlainString()));
        return Map.of("schema_version", "1.0", "market", symbol,
                "maker_fee", makerFinal.toPlainString(), "taker_fee", takerFinal.toPlainString(),
                "approval_id", approvalId);
    }

    // API §2-1 UTC 밀리초 3자리 고정. OffsetDateTime.toString의 가변 소수부를 쓰지 않는다.
    private static String ts(OffsetDateTime odt) {
        return odt == null ? "" : kr.exchange.api.common.ApiFormats.time(odt);
    }

    private static BigDecimal parseFee(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", field + " 형식이 올바르지 않습니다.");
        }
        if (value.scale() > FEE_SCALE) {
            throw new ApiException(400, "invalid_parameter",
                    field + "는 소수 8자리 이하여야 합니다.");
        }
        if (value.signum() < 0 || value.compareTo(FEE_MAX) > 0) {
            throw new ApiException(400, "invalid_parameter",
                    field + "는 0 이상 0.1 이하여야 합니다.");
        }
        return value;
    }

    @PostMapping("/notices")
    public Map<String, Object> publishNotice(@RequestBody NoticeBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new ApiException(400, "invalid_parameter", "공지 제목이 필요합니다.");
        }
        String severity = body.severity() == null ? "INFO" : body.severity();
        if (!NOTICE_SEVERITIES.contains(severity)) {
            throw new ApiException(400, "invalid_parameter",
                    "severity는 INFO|WARNING|CRITICAL 중 하나여야 합니다.");
        }
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
                        SELECT id, title, body, severity, active, published_by, published_at
                        FROM notices ORDER BY published_at DESC LIMIT 100
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "title", rs.getString("title"),
                        "body", rs.getString("body") == null ? "" : rs.getString("body"),
                        "severity", rs.getString("severity"),
                        "active", rs.getBoolean("active"),
                        "published_by", rs.getString("published_by") == null
                                ? "" : rs.getString("published_by"),
                        "published_at", ts(rs.getObject("published_at", OffsetDateTime.class))))
                .list();
        return Map.of("schema_version", "1.0", "data", rows);
    }

    /**
     * 공지 수정(SUPER, 승인 불요). 부분 갱신. 원장·매칭에 닿지 않는 운용 상태다.
     * severity가 오면 어휘를 검증하고, active 토글로 노출을 제어한다.
     */
    @PatchMapping("/notices/{id}")
    @Transactional
    public Map<String, Object> updateNotice(@PathVariable long id,
                                            @RequestBody(required = false) NoticeUpdateBody body,
                                            HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        if (body == null || (body.title() == null && body.body() == null
                && body.severity() == null && body.active() == null)) {
            throw new ApiException(400, "invalid_parameter", "변경할 필드가 필요합니다.");
        }
        if (body.severity() != null && !NOTICE_SEVERITIES.contains(body.severity())) {
            throw new ApiException(400, "invalid_parameter",
                    "severity는 INFO|WARNING|CRITICAL 중 하나여야 합니다.");
        }
        Boolean exists = jdbc.sql("SELECT true FROM notices WHERE id = ? FOR UPDATE")
                .param(id).query(Boolean.class).optional().orElse(null);
        if (exists == null) {
            throw new ApiException(404, "not_found", "존재하지 않는 공지입니다: " + id);
        }
        // NULL 파라미터를 COALESCE에 그대로 바인딩하면 PG가 타입 추론에 실패할 수 있어 명시 캐스팅.
        jdbc.sql("""
                        UPDATE notices SET
                            title = COALESCE(?::varchar, title),
                            body = COALESCE(?::text, body),
                            severity = COALESCE(?::varchar, severity),
                            active = COALESCE(?::boolean, active)
                        WHERE id = ?
                        """)
                .params(body.title(), body.body(), body.severity(), body.active(), id)
                .update();
        audit.record(principal.operatorId(), "config.notice.update", String.valueOf(id),
                AdminRequestContext.requestId(request), null, "OK", Map.of());
        return Map.of("schema_version", "1.0", "notice_id", id);
    }

    /**
     * 공지 삭제(SUPER, 승인 불요). 소프트 삭제(active=false)로 노출만 내린다.
     * app_role은 notices에 SELECT/INSERT/UPDATE만 부여되고 DELETE 권한이 없다(V5 §7).
     * 하드 DELETE는 운영 롤에서 42501로 상시 실패하므로 UPDATE 경로로 강등한다.
     * 감사 이력·게시 원본을 보존한다. 없는 id는 404, 이미 비활성이면 멱등 200.
     */
    @DeleteMapping("/notices/{id}")
    @Transactional
    public Map<String, Object> deleteNotice(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        Boolean active = jdbc.sql("SELECT active FROM notices WHERE id = ? FOR UPDATE")
                .param(id).query(Boolean.class).optional().orElse(null);
        if (active == null) {
            throw new ApiException(404, "not_found", "존재하지 않는 공지입니다: " + id);
        }
        if (Boolean.TRUE.equals(active)) {
            jdbc.sql("UPDATE notices SET active = false WHERE id = ?").param(id).update();
        }
        audit.record(principal.operatorId(), "config.notice.delete", String.valueOf(id),
                AdminRequestContext.requestId(request), null, "OK", Map.of());
        return Map.of("schema_version", "1.0", "notice_id", id, "deleted", true);
    }
}

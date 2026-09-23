package kr.exchange.admin;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 승인 토큰 수명주기(§3-6, 1인 승인 ADR 0019). 발급 주체(approver)와 사용 주체(requester)는
 * 각 요청의 인증 주체에서 도출하고 서로 같아도 된다. 토큰은 action + 대상과 requester 에
 * 바인딩되고 단회용·TTL 5분이다.
 */
public class ApprovalService {

    private final JdbcClient jdbc;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public ApprovalService(JdbcClient jdbc, Duration ttl) {
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    public record Issued(String approvalToken, long approvalId, long expiresAt) {
    }

    /**
     * 승인 발급. approver는 인증 주체 유래 operator_id, requester는 승인 대상 운영자.
     *
     * 두 신원이 같아도 된다. 1인 승인이다(ADR 0019, V34). 서로 달라야 한다는 제약은 1인 운영에서
     * 파괴 액션 자체를 봉쇄하므로 두지 않는다. 남는 방어는 별도 발급 단계와 TTL, 단회 소각,
     * 액션·대상·requester 바인딩, 역할 게이트, append-only 감사다.
     */
    public Issued issue(String approverOperatorId, String requesterOperatorId,
                        String action, String target) {
        String token = "APV" + randomToken(40);
        long exp = System.currentTimeMillis() / 1000 + ttl.toSeconds();
        long id = jdbc.sql("""
                        INSERT INTO admin_approvals
                            (approval_token, action, target, requester_operator_id,
                             approver_operator_id, expires_at)
                        VALUES (?, ?, ?, ?, ?, to_timestamp(?))
                        RETURNING id
                        """)
                .params(token, action, target == null ? "" : target,
                        requesterOperatorId, approverOperatorId, exp)
                .query(Long.class).single();
        return new Issued(token, id, exp);
    }

    /**
     * 승인 소비. 성공 시 즉시 소각(CONSUMED). action·대상 바인딩 불일치, 만료, 기소각,
     * 사용 주체 != 지정 requester는 모두 403 admin_forbidden.
     *
     * 전파는 REQUIRED다. 호출자가 트랜잭션을 열고 그 안에서 파괴 액션 DB 쓰기와 함께 호출하면
     * 소각과 액션이 한 단위로 커밋·롤백된다(액션 실패 시 토큰 보존, 재시도 가능). 격리 스택
     * 카오스 주입처럼 트랜잭션 밖의 효과와는 원자성을 보장하지 않는다. 단회 소각은 FOR UPDATE
     * 잠금으로 항상 보장한다(동시 소비·재사용 차단).
     */
    @Transactional
    public long consume(String requesterOperatorId, String approvalToken,
                        String action, String target) {
        if (approvalToken == null || approvalToken.isBlank()) {
            throw forbidden("이 액션에는 approval_token이 필요합니다.", "approval_required");
        }
        var row = jdbc.sql("""
                        SELECT id, action, target, requester_operator_id, approver_operator_id,
                               state, (expires_at <= now()) AS expired
                        FROM admin_approvals WHERE approval_token = ?
                        FOR UPDATE
                        """)
                .param(approvalToken)
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "action", rs.getString("action"),
                        "target", rs.getString("target"),
                        "requester", rs.getString("requester_operator_id"),
                        "approver", rs.getString("approver_operator_id"),
                        "state", rs.getString("state"),
                        "expired", rs.getBoolean("expired")))
                .optional()
                .orElseThrow(() -> forbidden("존재하지 않는 approval_token입니다.", "unknown_token"));

        long id = (long) row.get("id");
        if (!"ISSUED".equals(row.get("state"))) {
            throw forbidden("이미 소각·거부된 approval_token입니다.", "not_open");
        }
        if ((boolean) row.get("expired")) {
            jdbc.sql("UPDATE admin_approvals SET state = 'EXPIRED' WHERE id = ?")
                    .param(id).update();
            throw forbidden("만료된 approval_token입니다.", "expired");
        }
        if (!action.equals(row.get("action")) || !norm(target).equals(row.get("target"))) {
            throw forbidden("approval_token이 요청 액션·대상과 다릅니다.", "binding_mismatch");
        }
        if (!requesterOperatorId.equals(row.get("requester"))) {
            throw forbidden("approval_token 사용 주체가 승인 지정 요청자와 다릅니다.", "requester_mismatch");
        }
        // 발급 주체와 사용 주체가 같아도 통과시킨다(1인 승인, ADR 0019). 바인딩 검사는 위에
        // 남는다. 곧 남의 승인을 가져다 쓰는 것은 여전히 requester_mismatch 로 막힌다.
        jdbc.sql("UPDATE admin_approvals SET state = 'CONSUMED', consumed_at = now() WHERE id = ?")
                .param(id).update();
        return id;
    }

    /**
     * 승인 원장 조회 프로젝션(state 필터·상한 절단). approval_token은 발급 응답에서만 나가는
     * bearer 시크릿이므로 프로젝션에서 제외한다(§3-6). issued_at DESC, id DESC 안정 정렬로
     * LIMIT 절단을 결정화한다.
     */
    public List<CursorPages.Row<Map<String, Object>>> list(String state, Cursors.Cursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, action, target, requester_operator_id, approver_operator_id,
                       state, issued_at, expires_at, consumed_at
                FROM admin_approvals WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (state != null && !state.isBlank()) {
            sql.append(" AND state = ?");
            params.add(state);
        }
        if (cursor != null) {
            // 커서 행의 실제 issued_at을 다시 읽어 바인딩한다. 절단된 ts를 쓰면 같은 시각
            // 경계 행이 다음 쪽에서 사라진다(Cursors.encode 주석)
            long anchorId = CursorPages.numericId(cursor.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT issued_at FROM admin_approvals WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject("issued_at", OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (issued_at, id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        // 한 건 더 읽어 다음 쪽 존재를 판단한다(호출부가 limit + 1을 넘긴다)
        sql.append(" ORDER BY issued_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("approval_id", rs.getLong("id"));
                    m.put("action", rs.getString("action"));
                    m.put("target", rs.getString("target"));
                    m.put("requester_operator_id", rs.getString("requester_operator_id"));
                    m.put("approver_operator_id", rs.getString("approver_operator_id"));
                    m.put("state", rs.getString("state"));
                    m.put("issued_at", timeOrNull(rs.getObject("issued_at", OffsetDateTime.class)));
                    m.put("expires_at", timeOrNull(rs.getObject("expires_at", OffsetDateTime.class)));
                    m.put("consumed_at", timeOrNull(rs.getObject("consumed_at", OffsetDateTime.class)));
                    return new CursorPages.Row<>(m,
                            rs.getObject("issued_at", OffsetDateTime.class),
                            String.valueOf(rs.getLong("id")));
                })
                .list();
    }

    /**
     * 승인 거부(consume과 대칭). ISSUED 행을 FOR UPDATE로 잠그고 호출 주체가 그 행의 approver
     * 또는 requester일 때만 REJECTED로 전이한다(당사자만 거부, 임의 운영자 그리핑 차단).
     * consumed_at은 건드리지 않는다. de-escalation이라 승인 토큰을 소각하지 않는다.
     */
    @Transactional
    public void reject(String callerOperatorId, long id) {
        var row = jdbc.sql("""
                        SELECT state, requester_operator_id, approver_operator_id
                        FROM admin_approvals WHERE id = ?
                        FOR UPDATE
                        """)
                .param(id)
                .query((rs, n) -> Map.<String, Object>of(
                        "state", rs.getString("state"),
                        "requester", rs.getString("requester_operator_id"),
                        "approver", rs.getString("approver_operator_id")))
                .optional()
                .orElseThrow(() -> new ApiException(404, "not_found",
                        "존재하지 않는 승인입니다.", Map.of("reason", "unknown_approval"), null));
        if (!"ISSUED".equals(row.get("state"))) {
            throw forbidden("이미 소각·거부·만료된 승인입니다.", "not_open");
        }
        if (!callerOperatorId.equals(row.get("requester"))
                && !callerOperatorId.equals(row.get("approver"))) {
            throw forbidden("승인 당사자만 거부 가능", "not_party");
        }
        jdbc.sql("UPDATE admin_approvals SET state = 'REJECTED' WHERE id = ?")
                .param(id).update();
    }

    private static String timeOrNull(OffsetDateTime odt) {
        return odt == null ? null : kr.exchange.api.common.ApiFormats.time(odt);
    }

    private static String norm(String target) {
        return target == null ? "" : target;
    }

    private String randomToken(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                .replace("-", "A").replace("_", "B").substring(0, length);
    }

    private static ApiException forbidden(String message, String reason) {
        return new ApiException(403, "admin_forbidden", message, Map.of("reason", reason), null);
    }
}

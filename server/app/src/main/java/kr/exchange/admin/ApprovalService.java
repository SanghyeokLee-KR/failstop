package kr.exchange.admin;

import kr.exchange.api.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 2인 승인 토큰 수명주기(§3-6 v2.3·v2.4). 발급 주체(approver)와 사용 주체(requester)는
 * 각 요청의 mTLS 인증 주체에서 도출되며 서버가 서로 다름을 강제한다(반결탁). 토큰은
 * action + 대상에 바인딩되고 단회용·TTL 5분이다.
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
     * 두 신원이 같으면 DB CHECK(chk_approval_two_person)와 앱 가드가 함께 반려한다.
     */
    public Issued issue(String approverOperatorId, String requesterOperatorId,
                        String action, String target) {
        if (approverOperatorId.equals(requesterOperatorId)) {
            throw forbidden("approvals 발급 주체와 요청 주체가 같을 수 없습니다.", "same_operator");
        }
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
     * 사용 주체 != 지정 requester, 사용 주체 == 발급 주체는 모두 403 admin_forbidden.
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
        // 반결탁 최종 방어선: 사용 주체는 발급 주체와 달라야 한다(§3-6).
        if (requesterOperatorId.equals(row.get("approver"))) {
            throw forbidden("approval_token 사용 주체가 발급 주체와 같습니다.", "same_operator");
        }
        jdbc.sql("UPDATE admin_approvals SET state = 'CONSUMED', consumed_at = now() WHERE id = ?")
                .param(id).update();
        return id;
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

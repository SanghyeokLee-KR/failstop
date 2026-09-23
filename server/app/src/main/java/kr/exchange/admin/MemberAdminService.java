package kr.exchange.admin;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.order.OrderGateway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 회원 운영(API §6-E, 관리자_설계 §2-4). users.status 전이와 그 부수 효과를 다룬다.
 *
 * 상태 게이트는 이미 산다. 로그인·갱신은 MemberAuthService가, 주문 생성은
 * OrderGateway.requireActiveUser가 403 account_suspended로 막는다. 여기서 하는 일은
 * 상태를 바꾸는 것과, 그 상태가 뜻하는 정리를 함께 해 주는 것이다.
 *
 * 정지와 폐쇄의 부수 효과가 다르다. 정지는 되돌리는 조치라 미체결 주문을 남긴다. 주문까지
 * 취소하면 복구해도 원상태가 아니다. 폐쇄는 비가역이라 남은 주문이 체결되면 폐쇄 계정에
 * 체결이 생기므로 전량 취소한다. 취소는 예약 잔고를 되돌릴 뿐이라 자산 합 0을 건드리지 않는다.
 */
@Service
public class MemberAdminService {

    /** 목록 한 쪽 상한. 신고·감사 큐와 같은 값이다 */
    public static final int MAX_LIMIT = 200;

    private static final List<String> STATUSES = List.of("ACTIVE", "SUSPENDED", "CLOSED");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OrderGateway orders;

    public MemberAdminService(JdbcClient jdbc, TransactionTemplate tx, OrderGateway orders) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
    }

    /** 전이 결과. 운영자가 무엇이 함께 정리됐는지 보고 확인한다 */
    public record Transition(String externalId, String status, int canceledOrders,
                             int revokedSessions) {
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    /**
     * 회원 목록 한 쪽. 가입 역순이라 커서 경계 비교도 내림차순이다.
     * kind='BOT'은 봇 시더가 만든 계정이라 운영 대상이 아니다(V20 부분 인덱스 술어와 일치).
     */
    public List<CursorPages.Row<Map<String, Object>>> list(String status, Cursors.Cursor cursor,
                                                           int limit) {
        /* 활동 요약 3열은 상세와 같은 정의다(§6-E v2.35). 상관 서브쿼리로 한 왕복에 담는다.
           행마다 상세를 부르면 N+1이 되고 그 비용이 화면 폴링 주기마다 나간다.
           open_order_count·last_order_at은 idx_orders_user(user_id, status, created_at DESC)를,
           asset_kinds는 accounts UNIQUE(owner_type, owner_id, ...) 접두를 탄다.
           한 쪽 상한이 200행이라 서브쿼리 수는 유계다. */
        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.external_id::text AS external_id, u.email, u.status,
                       u.created_at, p.display_name,
                       (SELECT count(*) FROM orders o
                         WHERE o.user_id = u.id
                           AND o.status IN ('OPEN','PARTIAL')) AS open_order_count,
                       (SELECT max(o.created_at) FROM orders o
                         WHERE o.user_id = u.id) AS last_order_at,
                       (SELECT count(DISTINCT a.asset_id)
                          FROM accounts a JOIN balances b ON b.account_id = a.id
                         WHERE a.owner_type = 'USER' AND a.owner_id = u.id
                           AND b.balance <> 0) AS asset_kinds
                FROM users u
                LEFT JOIN user_profiles p ON p.user_id = u.id
                WHERE u.kind = 'HUMAN'
                """);
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND u.status = ?");
            params.add(normStatus(status));
        }
        if (cursor != null) {
            long anchorId = CursorPages.numericId(cursor.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT created_at FROM users WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject("created_at", OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (u.created_at, u.id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        sql.append(" ORDER BY u.created_at DESC, u.id DESC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    OffsetDateTime at = rs.getObject("created_at", OffsetDateTime.class);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("external_id", rs.getString("external_id"));
                    row.put("email", rs.getString("email"));
                    row.put("display_name", rs.getString("display_name"));
                    row.put("status", rs.getString("status"));
                    row.put("created_at", ApiFormats.time(at));
                    row.put("open_order_count", rs.getLong("open_order_count"));
                    // 주문이 없으면 max()가 null이다. ApiFormats.time(null)은 NPE라 값을 먼저 본다
                    OffsetDateTime lastOrder = rs.getObject("last_order_at", OffsetDateTime.class);
                    row.put("last_order_at", lastOrder == null ? null : ApiFormats.time(lastOrder));
                    row.put("asset_kinds", rs.getLong("asset_kinds"));
                    return new CursorPages.Row<>(row, at, String.valueOf(rs.getLong("id")));
                })
                .list();
    }

    /**
     * 회원 상세. 목록 필드에 활동 요약을 더한다. 폐쇄 전에 남은 주문이 있는지를
     * open_order_count로 보고 판단한다.
     */
    public Map<String, Object> detail(String externalId) {
        long userId = requireUser(externalId).id();
        Map<String, Object> body = jdbc.sql("""
                        SELECT u.external_id::text AS external_id, u.email, u.status,
                               u.created_at, u.updated_at, p.display_name
                        FROM users u
                        LEFT JOIN user_profiles p ON p.user_id = u.id
                        WHERE u.id = ?
                        """)
                .param(userId)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("external_id", rs.getString("external_id"));
                    row.put("email", rs.getString("email"));
                    row.put("display_name", rs.getString("display_name"));
                    row.put("status", rs.getString("status"));
                    row.put("created_at",
                            ApiFormats.time(rs.getObject("created_at", OffsetDateTime.class)));
                    row.put("updated_at",
                            ApiFormats.time(rs.getObject("updated_at", OffsetDateTime.class)));
                    return row;
                })
                .single();

        body.put("open_order_count", jdbc.sql(
                        "SELECT count(*) FROM orders WHERE user_id = ? AND status IN ('OPEN','PARTIAL')")
                .param(userId).query(Long.class).single());
        // 주문이 없으면 집계가 행 하나에 null을 담아 온다. optional은 0행만 걸러 여기서
        // ApiFormats.time(null)이 NPE가 된다. 값 자체를 먼저 본다.
        OffsetDateTime lastOrder = jdbc.sql(
                        "SELECT max(created_at) FROM orders WHERE user_id = ?")
                .param(userId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                .optional().orElse(null);
        body.put("last_order_at", lastOrder == null ? null : ApiFormats.time(lastOrder));
        // 계정은 owner_type·owner_id로 회원을 가리키고 잔고는 balances에 1:1로 붙는다.
        // 자산 종류 수라 asset_id를 구분한다(AVAILABLE·LOCKED가 같은 자산이면 한 종류).
        body.put("asset_kinds", jdbc.sql("""
                        SELECT count(DISTINCT a.asset_id)
                        FROM accounts a JOIN balances b ON b.account_id = a.id
                        WHERE a.owner_type = 'USER' AND a.owner_id = ? AND b.balance <> 0
                        """)
                .param(userId).query(Long.class).optional().orElse(0L));
        return body;
    }

    // ------------------------------------------------------------------
    // 전이
    // ------------------------------------------------------------------

    /** 정지. 세션만 끊고 미체결 주문은 남긴다(가역 조치라 복구가 원상태여야 한다) */
    public Transition suspend(String externalId) {
        return transition(externalId, "SUSPENDED", false);
    }

    /** 복구. 상태만 되돌리고 세션은 복구하지 않는다. 회원이 다시 로그인한다 */
    public Transition reinstate(String externalId) {
        return transition(externalId, "ACTIVE", false);
    }

    /** 폐쇄. 비가역이라 미체결 주문을 전량 취소하고 세션을 끊는다 */
    public Transition close(String externalId) {
        return transition(externalId, "CLOSED", true);
    }

    /**
     * 상태를 먼저 커밋하고 주문을 나중에 취소한다. 순서가 중요하다.
     *
     * 취소를 먼저 하면 창이 열린다. cancelAll은 진입 시점 미체결 목록의 스냅샷으로 돌고
     * 주문 1건마다 투영을 기다리므로 수 초가 걸리는데, 그동안 계정은 아직 ACTIVE라
     * requireActiveUser를 통과한다. 액세스 토큰이 스테이트리스 15분이라 세션 폐기로도
     * 막히지 않는다. 스냅샷 밖 신규 주문이 취소되지 않은 채 상태만 CLOSED로 확정된다.
     * 상태를 먼저 닫으면 그 창이 사라지고, 남은 취소는 재호출로 수렴한다(CLOSED->CLOSED 허용).
     * 정지 마켓의 미체결은 cancelAll 이 실패로 집계하고 남긴다. 그 마켓을 재개한 뒤 재호출해야 수렴한다.
     *
     * 취소를 트랜잭션 밖에 두는 이유는 그대로다. cancelAll이 주문별로 매칭 경로를 타고 각자
     * 커밋하므로 바깥 트랜잭션에 넣으면 경계가 겹친다.
     */
    private Transition transition(String externalId, String target, boolean cancelOrders) {
        UserRow user = requireUser(externalId);
        // 여기 판정은 빠른 거부일 뿐이고 권위는 아래 UPDATE의 술어다. 이 읽기는 트랜잭션
        // 밖이라 다른 운영자의 close와 겹치면 stale하다.
        if ("CLOSED".equals(user.status()) && !"CLOSED".equals(target)) {
            throw new ApiException(409, "member_closed", "폐쇄 계정은 전이 불가");
        }
        int revoked = tx.execute(s -> {
            int sessions = "ACTIVE".equals(target) ? 0 : jdbc.sql(
                            "UPDATE auth_sessions SET revoked_at = now() "
                                    + "WHERE user_id = ? AND revoked_at IS NULL")
                    .param(user.id()).update();
            // 종국 상태를 술어로 막는다. 잠금 없이 읽은 값으로 판정하면 A의 close와 B의
            // reinstate가 겹칠 때 B가 검사 없이 ACTIVE를 덮어 폐쇄 계정이 승인 없이 되살아난다.
            int moved = jdbc.sql("UPDATE users SET status = ? WHERE id = ? AND status <> 'CLOSED'")
                    .params(target, user.id()).update();
            if (moved == 0 && !"CLOSED".equals(target)) {
                throw new ApiException(409, "member_closed", "폐쇄 계정은 전이 불가");
            }
            return sessions;
        });
        int canceled = 0;
        if (cancelOrders) {
            canceled = orders.cancelAll(user.id(), null).canceledIds().size();
        }
        return new Transition(externalId, target, canceled, revoked);
    }

    private record UserRow(long id, String status) {
    }

    /** external_id는 UUID다. 형식이 아니면 캐스팅에서 터지므로 조회 전에 거른다 */
    private UserRow requireUser(String externalId) {
        if (externalId == null || !externalId.matches("[0-9a-fA-F-]{36}")) {
            throw new ApiException(404, "not_found", "존재하지 않는 회원");
        }
        return jdbc.sql("SELECT id, status FROM users WHERE external_id = ?::uuid AND kind = 'HUMAN'")
                .param(externalId)
                .query((rs, n) -> new UserRow(rs.getLong("id"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ApiException(404, "not_found", "존재하지 않는 회원"));
    }

    private static String normStatus(String raw) {
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new ApiException(400, "invalid_parameter",
                    "status는 ACTIVE·SUSPENDED·CLOSED 중 하나");
        }
        return s;
    }
}

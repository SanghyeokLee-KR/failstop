package kr.exchange.api.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 파티션 밖 취소 인계 표(V54 order_cancel_requests)의 문장(DB 설계 §3.9·§5.4-d). 요청자의 넣기·읽기·포기와 작업자의 점유·
 * 끝내기·만료다. 문장은 DB 설계 §5.4-d 의 SQL 과 같다. 점유 문장은 재점유 계수를 위해 점유 전 상태를 함께 돌려준다.
 *
 * 이 표는 작업 지시일 뿐 쓰기 권한 판정에 쓰이지 않는다. 판정은 종전처럼 저널 append 가 DB 에서 그 마켓의 리스로 한다(§5.4-b).
 * 점유와 끝내기의 리스 확인은 결과를 바르게 알리기 위한 장치다. 리스를 잃은 노드는 새 행을 점유하지 못하고 점유한 행을 끝내지
 * 못해, 그 행은 CLAIMED 로 남아 더 큰 epoch 의 작업자가 다시 점유한다.
 */
public class OrderCancelHandoffRepository {

    /** 행 상태(V54 chk_ocr_state). */
    public enum State { PENDING, CLAIMED, DONE, ABANDONED, EXPIRED }

    /** 요청자가 읽는 행. result 는 DONE 에만 있는 JSON 문자열이다. */
    public record Row(long id, String resource, State state, List<UUID> orderIds, String result) {
    }

    /** 작업자가 점유한 행. reclaimed 는 더 큰 epoch 로 끝나지 않은 점유를 다시 잡은 것이다. */
    public record Claimed(long id, String resource, long userId, List<UUID> orderIds, boolean reclaimed) {
    }

    /** 요청자가 넣을 행 하나. 한 파티션의 주문 1개 이상 500개 이하다. */
    public record NewRow(String resource, List<UUID> orderIds) {
    }

    /** 만료 문장 한 번이 닫는 행 수의 상한(DB 설계 §5.4-d 6). */
    public static final int EXPIRE_BATCH = 1000;

    private static final String CLAIM = """
            WITH picked AS (
                SELECT r.id, r.state AS prior_state FROM order_cancel_requests r
                 WHERE r.resource = :resource
                   AND (r.state = 'PENDING' OR (r.state = 'CLAIMED' AND r.claimed_epoch < :epoch))
                   AND r.created_at > now() - interval '5 minutes'
                   AND EXISTS (SELECT 1 FROM leader_lease l
                                WHERE l.resource = :resource AND l.holder = :holder AND l.epoch = :epoch
                                  AND l.lease_until > clock_timestamp())
                 ORDER BY r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED)
            UPDATE order_cancel_requests r
               SET state = 'CLAIMED', claimed_by = :holder, claimed_epoch = :epoch, claimed_at = now()
              FROM picked WHERE r.id = picked.id
            RETURNING r.id, r.resource, r.user_id, r.order_ids, picked.prior_state
            """;

    private static final String COMPLETE = """
            UPDATE order_cancel_requests o
               SET state = 'DONE', finished_at = now(), result = CAST(:result AS jsonb)
             WHERE o.id = :id AND o.state = 'CLAIMED' AND o.claimed_by = :holder AND o.claimed_epoch = :epoch
               AND EXISTS (SELECT 1 FROM leader_lease l
                            WHERE l.resource = o.resource AND l.holder = :holder AND l.epoch = :epoch
                              AND l.lease_until > clock_timestamp())
            """;

    private static final String ABANDON = """
            UPDATE order_cancel_requests r
               SET state = 'ABANDONED', finished_at = now()
             WHERE r.request_id = :request_id AND r.state = 'PENDING'
               AND NOT EXISTS (SELECT 1 FROM order_cancel_requests s
                                WHERE s.request_id = r.request_id AND s.resource = r.resource
                                  AND s.state IN ('CLAIMED', 'DONE'))
            """;

    private static final String EXPIRE = """
            WITH stale AS (
                SELECT id FROM order_cancel_requests
                 WHERE state IN ('PENDING', 'CLAIMED')
                   AND ((state = 'PENDING' AND created_at <= now() - interval '5 minutes')
                     OR (state = 'CLAIMED' AND claimed_at <= now() - interval '5 minutes'))
                 ORDER BY id LIMIT 1000 FOR UPDATE SKIP LOCKED)
            UPDATE order_cancel_requests r
               SET state = CASE r.state WHEN 'PENDING' THEN 'ABANDONED' ELSE 'EXPIRED' END, finished_at = now()
              FROM stale WHERE r.id = stale.id
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public OrderCancelHandoffRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * 한 요청의 모든 행을 한 트랜잭션으로 넣는다(DB 설계 §5.4-d 2). 넣은 행의 id 를 넣은 순서로 돌려준다. 쓰기라 실패해도 다시
     * 부르지 않는다. 연결이 끊겨 끝났으면 커밋됐을 수 있고, 그 행은 유효 시간 안에 처리되거나 만료로 닫힌다.
     */
    public List<Long> insert(UUID requestId, long userId, String origin, List<NewRow> rows) {
        List<Long> ids = tx.execute(status -> {
            List<Long> inserted = new ArrayList<>(rows.size());
            for (NewRow row : rows) {
                inserted.add(jdbc.sql("""
                                INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin)
                                VALUES (:request_id, :resource, :user_id, CAST(:order_ids AS uuid[]), :origin)
                                RETURNING id
                                """)
                        .param("request_id", requestId)
                        .param("resource", row.resource())
                        .param("user_id", userId)
                        .param("order_ids", arrayLiteral(row.orderIds()))
                        .param("origin", origin)
                        .query(Long.class)
                        .single());
            }
            return inserted;
        });
        return ids == null ? List.of() : ids;
    }

    /** 한 요청의 행을 번호 순서로 읽는다. */
    public List<Row> byRequest(UUID requestId) {
        return jdbc.sql("""
                        SELECT id, resource, state, order_ids, result::text AS result
                        FROM order_cancel_requests WHERE request_id = :request_id ORDER BY id
                        """)
                .param("request_id", requestId)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("resource"),
                        State.valueOf(rs.getString("state")), uuids(rs, "order_ids"), rs.getString("result")))
                .list();
    }

    /**
     * 가져가지 않은 행을 포기로 바꾼다. 같은 파티션에서 이 요청의 행을 소유 노드가 하나라도 가져갔으면(CLAIMED·DONE) 그 파티션의
     * 남은 PENDING 행은 포기하지 않는다. 소유 노드가 번호 순서로 이어서 처리한다(DB 설계 §5.4-d 2).
     */
    public int abandon(UUID requestId) {
        return jdbc.sql(ABANDON).param("request_id", requestId).update();
    }

    /**
     * 그 파티션의 행 하나를 점유하고 바로 커밋한다(DB 설계 §5.4-d 3). 리스 행이 이 holder·epoch 로 유효할 때만 집는다. 넣은 지
     * 5분 지난 행은 집지 않는다. 같은 epoch 가 점유한 행은 다시 집지 않고 더 작은 epoch 의 끝나지 않은 점유만 다시 집는다.
     */
    public Optional<Claimed> claim(String resource, String holder, long epoch) {
        return jdbc.sql(CLAIM)
                .param("resource", resource)
                .param("holder", holder)
                .param("epoch", epoch)
                .query((rs, n) -> new Claimed(rs.getLong("id"), rs.getString("resource"), rs.getLong("user_id"),
                        uuids(rs, "order_ids"), "CLAIMED".equals(rs.getString("prior_state"))))
                .optional();
    }

    /**
     * 점유한 행을 결과와 함께 끝낸다. 점유자·epoch 가 맞고 리스 행이 이 holder·epoch 로 유효할 때만 1행이다. 0행이면 더 큰
     * epoch 가 다시 점유했거나 이 노드의 리스가 유효하지 않은 것이라 결과를 버린다.
     */
    public int complete(long id, String holder, long epoch, String resultJson) {
        return jdbc.sql(COMPLETE)
                .param("result", resultJson)
                .param("id", id)
                .param("holder", holder)
                .param("epoch", epoch)
                .update();
    }

    /** 만료 문장 한 번. 넣은 지 5분 지난 PENDING 은 ABANDONED, 점유한 지 5분 지난 CLAIMED 는 EXPIRED 로 1000행까지 닫는다. */
    public int expireOnce() {
        return jdbc.sql(EXPIRE).update();
    }

    /** 닫은 행이 1000보다 적을 때까지 만료 문장을 되풀이한다(DB 설계 §5.4-d 6). 닫은 행 수를 돌려준다. */
    public int expire() {
        int total = 0;
        int closed;
        do {
            closed = expireOnce();
            total += closed;
        } while (closed >= EXPIRE_BATCH);
        return total;
    }

    /** uuid[] 입력 문자열. UUID 는 16진수와 하이픈뿐이라 따옴표가 필요 없다. */
    static String arrayLiteral(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private static List<UUID> uuids(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<UUID> ids = new ArrayList<>(values.length);
        for (Object value : values) {
            ids.add(value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value)));
        }
        return List.copyOf(ids);
    }
}

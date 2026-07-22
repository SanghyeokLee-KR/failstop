package kr.exchange.journal;

import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * command_journal append 단일 경로(DB 설계 v2.9 §5.1 (a'), §5.4).
 *
 * 한 트랜잭션 안에서 [assert_current_leader → NEW 멱등 검사 → next_journal_value 채번 →
 * leader_lease FOR SHARE 참조 INSERT...SELECT]를 원자 수행한다. COMMIT 성공이 곧 ACK다.
 * 롤백되면 카운터 증가도 함께 되돌아가 결번이 없다(P0-5).
 */
public class CommandJournalAppender {

    private static final String INSERT_SQL = """
            INSERT INTO command_journal
              (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
               engine_ts, tie_break, payload, fencing_token, checksum)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, l.epoch, ?
            FROM leader_lease l
            WHERE l.resource = 'matching-engine'
              AND l.holder = ? AND l.epoch = ? AND l.lease_until > clock_timestamp()
            FOR SHARE OF l
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final LeaderLeaseService lease;

    public CommandJournalAppender(JdbcClient jdbc, TransactionTemplate tx, LeaderLeaseService lease) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.lease = lease;
    }

    public AppendResult append(CommandDraft draft) {
        LeaderIdentity leader = lease.requireLeader();
        try {
            return tx.execute(status -> appendInTx(leader, draft));
        } catch (StaleLeaderException e) {
            // fencing 확정 거부는 renew 틱을 기다리지 않고 즉시 강등한다(§5.4).
            lease.demote(e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            if (FencingErrors.isFencingRejection(e)) {
                lease.demote("assert_current_leader 거부");
            }
            throw e;
        }
    }

    private AppendResult appendInTx(LeaderIdentity leader, CommandDraft draft) {
        // 커밋 전 리더 검증 계약(P0-1). INSERT...SELECT의 엄격 비교와 이중 방어다.
        jdbc.sql("SELECT assert_current_leader(?, ?)")
                .params(leader.holder(), leader.epoch())
                .query((rs, rowNum) -> 0)
                .list();

        if (draft.type() == CommandDraft.CommandType.NEW) {
            Optional<AppendResult> replay = findExistingNew(draft);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        long seq = jdbc.sql("SELECT next_journal_value('command_seq')")
                .query(Long.class)
                .single();

        int inserted = jdbc.sql(INSERT_SQL)
                .params(seq, draft.marketId(), draft.type().name(), draft.userId(),
                        draft.clientOrderId(), draft.assignedOrderId(),
                        draft.engineTs(), draft.tieBreak(), draft.payloadJson(),
                        JournalChecksum.of(seq, draft),
                        leader.holder(), leader.epoch())
                .update();
        if (inserted == 0) {
            // 내 epoch가 밀렸거나 리스 만료. 예외로 롤백해 채번도 함께 되돌린다.
            throw new StaleLeaderException(
                    "stale 리더 append 거부: holder=" + leader.holder() + ", epoch=" + leader.epoch());
        }
        return new AppendResult(seq, leader.epoch(), false, draft.assignedOrderId());
    }

    /**
     * NEW 멱등 재전송 판정. command_journal은 seq 파티션이라 (user_id, client_order_id)
     * 유니크를 물리 강제할 수 없어, 동일 키 동시 append를 트랜잭션 어드바이저리 락으로
     * 직렬화한 뒤 기존 명령을 조회한다. 완결 강제는 접수 트랜잭션의 orders
     * uq_orders_client(§5.1 (a))가 담당한다.
     */
    private Optional<AppendResult> findExistingNew(CommandDraft draft) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('cmd-new:' || ?::text || ':' || ?, 0))")
                .params(draft.userId(), draft.clientOrderId())
                .query((rs, rowNum) -> 0)
                .list();
        return jdbc.sql("""
                        SELECT seq, fencing_token, assigned_order_id
                        FROM command_journal
                        WHERE command_type = 'NEW' AND user_id = ? AND client_order_id = ?
                        ORDER BY seq
                        LIMIT 1
                        """)
                .params(draft.userId(), draft.clientOrderId())
                .query((rs, rowNum) -> new AppendResult(
                        rs.getLong("seq"), rs.getLong("fencing_token"), true,
                        rs.getLong("assigned_order_id")))
                .optional();
    }
}

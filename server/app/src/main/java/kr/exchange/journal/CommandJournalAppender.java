package kr.exchange.journal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderWriteProgress;
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
    // §5-2 계약 메트릭. requireLeader 이후 tx 커밋(=fsync=ACK) 완료까지의 지연을 잰다(RPO=0 비용).
    private final Timer appendTimer;
    // 좀비 리더 판정의 진행 축(§5.4). appendTimer는 실패한 시도도 세므로 진행성 신호로 못 쓴다.
    private final LeaderWriteProgress progress;

    // ── 임계구역 안 분해(ADR 0022 0단계 (b)) ──────────────────────────────
    //
    // 2026-09-11 계측에서 접수 락 보유 6.935밀리초 중 6.879가 이 append 였고, 그 안에서
    // 설명되는 몫이 약 2.6밀리초뿐이었다. 데이터베이스 실행 합계 약 0.5, 커밋 fsync 약 1.2,
    // 컨테이너 사이 왕복 일곱 약 0.87 이다. 약 4.3밀리초가 어디로 가는지 모른 채로는
    // 장부를 쪼갠 뒤의 천장을 예고할 수 없다. 쪼개도 마켓별로 같은 몫을 물기 때문이다.
    //
    // 그래서 트랜잭션 안을 문장별로 가른다. appendTimer 에서 inTx 를 빼면 남는 것이
    // 커넥션 획득과 트랜잭션 경계와 커밋이다. 그 셋은 여기서 더 못 가르므로 뺄셈으로 낸다.
    //
    // LongAdder 를 쓰는 이유는 Timer 셋을 더 만들면 히스토그램 버킷이 스크레이프마다
    // 붙기 때문이다. 여기서 필요한 것은 분포가 아니라 누적 합이다.
    private final java.util.concurrent.atomic.LongAdder inTxNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder leaderAssertNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder idempotencyNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder allocNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder insertNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder inTxCount =
            new java.util.concurrent.atomic.LongAdder();

    /** 트랜잭션 콜백 전체. appendTimer 에서 이것을 빼면 커넥션 획득과 경계와 커밋이 남는다. */
    public long inTxNanos() {
        return inTxNanos.sum();
    }

    /** assert_current_leader 왕복. */
    public long leaderAssertNanos() {
        return leaderAssertNanos.sum();
    }

    /** NEW 멱등 판정. 어드바이저리 락과 저널 조회 둘을 합친다. 같은 목적의 한 덩어리다. */
    public long idempotencyNanos() {
        return idempotencyNanos.sum();
    }

    /** next_journal_value 전역 채번. 이 행 잠금이 커밋까지 유지된다. */
    public long allocNanos() {
        return allocNanos.sum();
    }

    /** command_journal INSERT. */
    public long insertNanos() {
        return insertNanos.sum();
    }

    /** 위 다섯의 분모. 콜백이 돈 횟수다. */
    public long inTxCount() {
        return inTxCount.sum();
    }

    // 계측 없는 테스트용. 프로덕션은 EngineConfig가 앱 MeterRegistry로 다인자 생성자를 호출한다.
    public CommandJournalAppender(JdbcClient jdbc, TransactionTemplate tx, LeaderLeaseService lease) {
        this(jdbc, tx, lease, new SimpleMeterRegistry());
    }

    public CommandJournalAppender(JdbcClient jdbc, TransactionTemplate tx, LeaderLeaseService lease,
                                  MeterRegistry meterRegistry) {
        this(jdbc, tx, lease, meterRegistry, new LeaderWriteProgress());
    }

    public CommandJournalAppender(JdbcClient jdbc, TransactionTemplate tx, LeaderLeaseService lease,
                                  MeterRegistry meterRegistry, LeaderWriteProgress progress) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.lease = lease;
        this.progress = progress;
        this.appendTimer = Timer.builder("exchange.journal.append")
                .description("command_journal append fsync 지연(RPO=0 비용, 하네스 CICD §5-2)")
                .register(meterRegistry);
    }

    public AppendResult append(CommandDraft draft) {
        LeaderIdentity leader = lease.requireLeader();
        try {
            AppendResult result =
                    appendTimer.record(() -> tx.execute(status -> appendInTx(leader, draft)));
            // 커밋 성공만 진행으로 센다. 예외 경로는 카운터를 건드리지 않는다(§5.4 좀비 리더 판정).
            progress.recordCompletion();
            return result;
        } catch (StaleLeaderException e) {
            // fencing 거부는 리스 권위를 재확인한 뒤에만 강등한다(§5.4 강등 사유 분류).
            lease.demoteUnlessStillLeader(e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            if (FencingErrors.isFencingRejection(e)) {
                lease.demoteUnlessStillLeader(
                        "assert_current_leader 거부: " + FencingErrors.rejectionDetail(e));
            }
            throw e;
        }
    }

    private AppendResult appendInTx(LeaderIdentity leader, CommandDraft draft) {
        long tx0 = System.nanoTime();
        try {
            return appendInTxTimed(leader, draft);
        } finally {
            // 예외 경로도 센다. 거부로 끝난 append 도 커넥션을 잡고 있었다.
            inTxNanos.add(System.nanoTime() - tx0);
            inTxCount.increment();
        }
    }

    private AppendResult appendInTxTimed(LeaderIdentity leader, CommandDraft draft) {
        // 커밋 전 리더 검증 계약(P0-1). INSERT...SELECT의 엄격 비교와 이중 방어다.
        long t = System.nanoTime();
        jdbc.sql("SELECT assert_current_leader(?, ?)")
                .params(leader.holder(), leader.epoch())
                .query((rs, rowNum) -> 0)
                .list();
        leaderAssertNanos.add(System.nanoTime() - t);

        if (draft.type() == CommandDraft.CommandType.NEW) {
            t = System.nanoTime();
            Optional<AppendResult> replay = findExistingNew(draft);
            idempotencyNanos.add(System.nanoTime() - t);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        t = System.nanoTime();
        long seq = jdbc.sql("SELECT next_journal_value('command_seq')")
                .query(Long.class)
                .single();
        allocNanos.add(System.nanoTime() - t);

        long tIns = System.nanoTime();
        int inserted = jdbc.sql(INSERT_SQL)
                .params(seq, draft.marketId(), draft.type().name(), draft.userId(),
                        draft.clientOrderId(), draft.assignedOrderId(),
                        draft.engineTs(), draft.tieBreak(), draft.payloadJson(),
                        JournalChecksum.of(seq, draft),
                        leader.holder(), leader.epoch())
                .update();
        insertNanos.add(System.nanoTime() - tIns);
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

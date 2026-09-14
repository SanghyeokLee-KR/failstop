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


/**
 * command_journal append 단일 경로(DB 설계 v2.9 §5.1 (a'), §5.4).
 *
 * 한 트랜잭션 안에서 [append_command_prologue(V39: 리더 검증, NEW 멱등, 채번) →
 * leader_lease FOR KEY SHARE 참조 INSERT...SELECT]를 원자 수행한다. COMMIT 성공이 곧 ACK다.
 * 롤백되면 카운터 증가도 함께 되돌아가 결번이 없다(P0-5).
 */
public class CommandJournalAppender {

    // V39. 리더 검증과 NEW 멱등 판정과 전역 채번을 한 왕복으로 묶는다.
    private static final String PROLOGUE_SQL =
            "SELECT out_seq, out_replayed, out_fencing, out_order_id"
            + " FROM append_command_prologue(?, ?, ?, ?, ?)";

    /** 머리말 한 왕복의 결과. replayed 면 seq 가 기존 명령의 것이다. */
    private record Prologue(long seq, boolean replayed, long fencing, long orderId) {
    }

    // FOR KEY SHARE 는 holder·epoch 를 바꾸는 승격·게시만 커밋까지 막고 lease_until 갱신은 막지 않는다.
    // 그 구분은 V41 의 uq_leader_lease_fencing_key 가 있어야 성립한다(ADR 0026).
    static final String INSERT_SQL = """
            INSERT INTO command_journal
              (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
               engine_ts, tie_break, payload, fencing_token, checksum)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, l.epoch, ?
            FROM leader_lease l
            WHERE l.resource = 'matching-engine'
              AND l.holder = ? AND l.epoch = ? AND l.lease_until > clock_timestamp()
            FOR KEY SHARE OF l
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
    private final java.util.concurrent.atomic.LongAdder prologueNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder insertNanos =
            new java.util.concurrent.atomic.LongAdder();
    // 트랜잭션 경계 셋. 앞은 커넥션 획득과 자동커밋 해제, 커밋은 fsync, 뒤는 정리와 반납이다.
    private final java.util.concurrent.atomic.LongAdder preNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder commitNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder postNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder inTxCount =
            new java.util.concurrent.atomic.LongAdder();

    /** 트랜잭션 콜백 전체. appendTimer 에서 이것을 빼면 커넥션 획득과 경계와 커밋이 남는다. */
    public long inTxNanos() {
        return inTxNanos.sum();
    }

    /** 머리말 한 왕복(V39). 리더 검증과 NEW 멱등 판정과 전역 채번을 합친다. */
    public long prologueNanos() {
        return prologueNanos.sum();
    }

    /** command_journal INSERT. */
    public long insertNanos() {
        return insertNanos.sum();
    }

    /** 커넥션 획득과 자동커밋 해제와 경계 시작. 콜백이 돌기 전까지다. */
    public long preNanos() {
        return preNanos.sum();
    }

    /** 커밋. 콜백 종료부터 afterCommit 훅까지다. 이 안에 fsync 가 있다. */
    public long commitNanos() {
        return commitNanos.sum();
    }

    /** 정리와 커넥션 반납. afterCommit 뒤부터 tx.execute 반환까지다. */
    public long postNanos() {
        return postNanos.sum();
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
            // 트랜잭션 경계 분해(ADR 0022 0단계 (b) 후속).
            //
            // 2026-09-11 실측에서 append 6.099밀리초 중 콜백이 1.860 이고 나머지 4.239가
            // 커넥션과 경계와 커밋이었다(정본 5.7.12). 전용 풀을 줘도 0.49밀리초밖에 안 줄어
            // 그 비용이 풀 경합이 아니라는 것까지만 알고 정체를 모른다. 커밋 fsync 가 약
            // 1.2밀리초이므로 약 3.0이 설명되지 않는다.
            //
            // 그 4.239를 셋으로 가른다. 콜백 앞이 커넥션 획득과 자동커밋 해제와 경계 시작이고,
            // 콜백 뒤부터 커밋 동기화 훅까지가 커밋이며, 그 뒤가 정리와 반납이다.
            // marks 를 배열로 두는 이유는 람다가 지역 변수를 재할당할 수 없기 때문이다.
            // 배열은 호출마다 새로 만들고 콜백과 커밋 훅이 호출 스레드에서 돌아 스레드 사이에 공유되지 않는다.
            long t0 = System.nanoTime();
            long[] marks = new long[3];
            AppendResult result = appendTimer.record(() -> tx.execute(status -> {
                marks[0] = System.nanoTime();
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        marks[2] = System.nanoTime();
                                    }
                                });
                try {
                    return appendInTx(leader, draft);
                } finally {
                    marks[1] = System.nanoTime();
                }
            }));
            long t1 = System.nanoTime();
            if (marks[0] > 0) {
                preNanos.add(marks[0] - t0);
                if (marks[2] > 0) {
                    commitNanos.add(marks[2] - marks[1]);
                    postNanos.add(t1 - marks[2]);
                } else {
                    // 롤백 경로. 커밋 훅이 안 돌므로 뒤를 통째로 정리로 센다.
                    postNanos.add(t1 - marks[1]);
                }
            }
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
        // 머리말 한 왕복(V39). 종전에는 리더 검증과 어드바이저리 락과 멱등 조회와 채번을
        // 네 문장으로 보냈다. 넷 다 이 트랜잭션 안에서 끝나므로 함수 하나에 담긴다.
        // 판정 순서와 예외와 락 키는 그 함수가 종전 그대로 정한다.
        //
        // 삽입을 여기 안 넣는 이유는 체크섬이다. seq 를 받아야 계산할 수 있고 그 산식이
        // Jackson 정규화에 걸려 있어 plpgsql 로 옮기면 같은 계약이 두 언어에 생긴다.
        long t = System.nanoTime();
        Prologue p = jdbc.sql(PROLOGUE_SQL)
                .params(leader.holder(), leader.epoch(),
                        draft.type() == CommandDraft.CommandType.NEW,
                        draft.userId(), draft.clientOrderId())
                .query((rs, rowNum) -> new Prologue(
                        rs.getLong("out_seq"), rs.getBoolean("out_replayed"),
                        rs.getLong("out_fencing"), rs.getLong("out_order_id")))
                .single();
        prologueNanos.add(System.nanoTime() - t);

        if (p.replayed()) {
            // 원본 명령이 이미 저널에 있다. 종전 findExistingNew 가 돌려주던 것과 같은 값이다.
            return new AppendResult(p.seq(), p.fencing(), true, p.orderId());
        }
        long seq = p.seq();

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

}

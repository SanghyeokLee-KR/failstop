package kr.exchange.engine;

import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import kr.exchange.snapshot.SnapshotSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 기동 복구: 저널 재생으로 오더북을 재구성하고, 크래시 창(명령 커밋 후·이벤트 커밋 전)의
 * 재방출 이벤트를 재적재·재정산한다(DB 설계 §5.5 ②).
 *
 * 기동 경로는 체크포인트로 상한한다(2026-09-08). 상한이 없으면 다른 노드가 리더로서 계속 쓰는
 * 동안 "명령은 있는데 이벤트가 아직 없는" 구간이 항상 남고, 부팅 노드가 그것을 자기 크래시 창으로
 * 읽어 리더 자격을 요구한다. 그러면 유효한 타 리더가 있는 노드가 기동을 거부한다. 두 노드 실측에서
 * 장애 노드가 그 경로로 재합류하지 못했다(`harness/evidence/leader-failover-20260908/`).
 *
 * 상한을 걸면 pending 이 구조적으로 비어 리더 자격이 필요 없다. 상한 초과분은 둘 중 하나다.
 * 진짜 크래시 창이거나, 살아 있는 리더가 지금 쓰고 있는 것이다. 리더 자격을 얻을 수 있으면
 * 전자로 보고 이어서 재적재하고, 얻을 수 없으면 후자로 보고 팔로워로 뜬다. 팔로워의 따라잡기는
 * FollowerCatchUp 이 맡는다.
 *
 * <p>승격 재생도 같은 두 단계를 탄다(2026-09-09). 종전에는 승격 경로만 상한 없이 읽었고, 그것이
 * 종료하는 근거는 "승격 시점에는 다른 노드가 명령 저널에 쓸 수 없다" 는 fencing 하나뿐이었다.
 * 그 전제가 깨지면, 곧 승격한 노드가 리스를 잃고 다른 노드가 리더가 되면, 재생 루프가 새 리더의
 * 명령을 계속 읽어 끝나지 않는다. 2026-09-08 회차에서 실제로 CPU 200% 로 4분간 응답하지 못했다
 * (`harness/evidence/follower-rejoin-20260908/` 무효 1회차).
 *
 * 그래서 2 단계도 그 시점에 잡아 둔 저널 head 로 상한한다. 상한이 있으면 루프가 유한하게 끝나고,
 * 리스를 잃었다면 이어지는 재적재가 fencing 으로 즉시 거부된다. 무한 재생이 fail-fast 로 바뀐다.
 *
 * 두 경로의 차이는 이제 하나뿐이다. 2 단계에서 리더 자격을 얻지 못했을 때 기동은 팔로워로 뜨고
 * 승격은 실패한다. 승격은 리더 자격이 전제인 경로이므로 그 전제가 사라지면 진행할 이유가 없다.
 *
 * pending을 버리고 기동하면 이후 신규 명령의 이벤트가 계속 적재되면서 저널 스트림이
 * 재생 스트림의 prefix 성질을 잃고, 다음 재기동의 대조가 영구 실패한다(무음 유실 + 벽돌).
 * 단일 라이터의 방출 순서 그대로 sink에 넘기므로 event_seq는 W+1부터 연속 배정되어
 * prefix 성질이 유지된다.
 *
 * sink는 라이브 루프와 동일 계약(명령 단위 배치, append와 정산의 원자 커밋)이다. 명령
 * 단위로 잘라 넘기므로 도중 크래시가 나도 커밋된 prefix는 레지스트리 멱등 판정으로
 * 스킵되고 나머지만 재정산된다.
 *
 * sink가 배정한 event_seq는 재적재 결과라 여기서 버린다. 복구는 WS 게시 대상이 아니고
 * (라이브만 게시), 재기동 후 스냅샷·구독으로 상태가 전달된다. 게시 비대칭은 의도된 것이다.
 */
public class EngineRecovery {

    private static final Logger log = LoggerFactory.getLogger(EngineRecovery.class);
    // 시간 기준 renew 사이에도 이 이벤트 수마다 리스를 갱신한다. 명령 하나가 큰 이벤트 배치를
    // 내면(북 스윕) 시간 판정만으로는 renew 간격이 벌어질 수 있어, 누적 이벤트 상한으로 보강한다.
    private static final int RENEW_EVENT_INTERVAL = 10_000;
    // 부팅 승격 대기 상한·재시도 간격. 강제 종료 직후 재기동은 직전 프로세스의 리스가 TTL 내
    // 유효해 첫 승격이 실패한다. 만료 전 탈취는 try_acquire_leader가 차단하므로(DB 설계 §5.4)
    // 재시도는 잔존 리스의 만료 관측일 뿐이다. 상한은 RTO 목표 30초(API §7-4)를 넘지 않는다.
    private static final java.time.Duration BOOT_PROMOTION_WAIT_CAP = java.time.Duration.ofSeconds(30);
    private static final java.time.Duration BOOT_PROMOTION_RETRY_INTERVAL = java.time.Duration.ofMillis(250);

    private final ReplayService replayService;
    private final SingleWriterLoop.EventSink reappendSink;
    private final LeaderLeaseService lease;
    // null이면 스냅샷 비활성. 저널 seq 0 전량 재생으로만 복구한다(기존 경로).
    private final SnapshotSource snapshotSource;
    // 재생 명령 수 관측(§5-2 exchange.journal.replay.commands). 기본 no-op이라 테스트 경로는 무변경.
    private final java.util.function.LongConsumer replayObserver;
    // 재생 결과를 recovery_runs에 남긴다(C17 원천). null이면 기록하지 않는다. 테스트
    // 경로와 DB 없는 구성이 그대로 돌아야 하므로 선택적 협력자로 둔다.
    private kr.exchange.harness.RecoveryRunRecorder recoveryRecorder;

    public EngineRecovery(ReplayService replayService, SingleWriterLoop.EventSink reappendSink,
                          LeaderLeaseService lease) {
        this(replayService, reappendSink, lease, null);
    }

    public EngineRecovery(ReplayService replayService, SingleWriterLoop.EventSink reappendSink,
                          LeaderLeaseService lease, SnapshotSource snapshotSource) {
        this(replayService, reappendSink, lease, snapshotSource, n -> {
        });
    }

    public EngineRecovery(ReplayService replayService, SingleWriterLoop.EventSink reappendSink,
                          LeaderLeaseService lease, SnapshotSource snapshotSource,
                          java.util.function.LongConsumer replayObserver) {
        this.replayService = replayService;
        this.reappendSink = reappendSink;
        this.lease = lease;
        this.snapshotSource = snapshotSource;
        this.replayObserver = replayObserver;
    }

    /** C17 원천 기록자 주입. 설정에서 DB가 있을 때만 붙인다. */
    public void setRecoveryRecorder(kr.exchange.harness.RecoveryRunRecorder recorder) {
        this.recoveryRecorder = recorder;
    }

    /**
     * 기록자 배선 여부. 테스트가 이 값을 단언해 EngineConfig의 주입 한 줄을 지우는 회귀를
     * 잡는다. 그 한 줄이 사라지면 recovery_runs가 영구 0행이 되고 C17이 빈 표 대조로 다시
     * 공허 통과하는데, 표를 비우고 시작하는 테스트만으로는 그 상태를 구분하지 못한다.
     */
    public boolean hasRecoveryRecorder() {
        return recoveryRecorder != null;
    }

    /**
     * 스냅샷 로드 후 tail 재생(복구 RTO 설계 §3-b). fresh 엔진에 최신 채택 스냅샷을 적재하고
     * seq > snapshot.last_applied_seq만 재생해 tail을 상한한다. 채택 게이트를 통과하는 스냅샷이
     * 없으면 eventStart=0으로 seq 0 전량 재생(폴백)한다. 버전·설정·checksum 불일치는 게이트가
     * 폐기하므로 여기까지 오지 않는다(§3-b 단일 정본 규율). 로드는 fresh 엔진에만 허용된다
     * (MatchingEngineImpl.loadSnapshot 가드).
     */
    public ReplayResult recover(MatchingEngineImpl engine) {
        return recover(engine, java.time.Duration.ZERO);
    }

    /**
     * 부팅 전용 복구. 크래시 창 재적재에 리더 자격이 필요할 때, 직전 프로세스의 잔존 리스가
     * 만료될 때까지(TTL 1.5배, 상한 30초) 승격을 재시도한다. 무대기 경로(recover 단일 인자)는
     * 그대로 둔다: 런타임 승격 재생 중 리스 상실은 즉시 실패해야 강등이 지연되지 않는다.
     */
    public ReplayResult recoverAtBoot(MatchingEngineImpl engine) {
        java.time.Duration budget = lease.ttl().multipliedBy(3).dividedBy(2);
        if (budget.compareTo(BOOT_PROMOTION_WAIT_CAP) > 0) {
            budget = BOOT_PROMOTION_WAIT_CAP;
        }
        return recover(engine, budget, true);
    }

    private ReplayResult recover(MatchingEngineImpl engine, java.time.Duration promotionWait) {
        return recover(engine, promotionWait, false);
    }

    /**
     * @param followerAllowed 2 단계에서 리더 자격을 얻지 못했을 때 팔로워로 진행할 수 있는가.
     *                        기동은 참이고 승격은 거짓이다. 승격은 리더 자격이 전제인 경로다.
     */
    private ReplayResult recover(MatchingEngineImpl engine, java.time.Duration promotionWait,
                                 boolean followerAllowed) {
        // 이벤트 커서 시작점을 여기서 유도하지 않는다. loadSnapshot 이 engine.lastAppliedSeq()
        // 를 snapSeq 로 세우고 재생이 그 값에서 커서를 연다. 종전에는 이 자리에서 값을 만들어
        // 넘겼고 그 인자에 이벤트 축 값이 들어갈 수 있었다.
        if (snapshotSource != null) {
            Optional<SnapshotSource.Loaded> snap = snapshotSource.loadLatestAdoptable();
            if (snap.isPresent()) {
                long snapSeq = snap.get().seq();
                engine.loadSnapshot(snapSeq, snap.get().books());
                log.info("스냅샷 채택: last_applied_seq={}, markets={}",
                        snapSeq, snap.get().books().size());
            }
        }
        long replayFrom = engine.lastAppliedSeq();
        long replayStartedNs = System.nanoTime();
        ReplayResult result;
        try {
            result = replayToCheckpointThenTail(engine, promotionWait, followerAllowed);
        } catch (RuntimeException e) {
            // 불일치를 남기고 다시 던진다. 이 행이 C17이 검출하는 대상이다. 예외만 던지고
            // 기록을 안 남기면 재생이 깨진 사건이 표에 없어 C17이 통과한다.
            if (recoveryRecorder != null) {
                recoveryRecorder.recordMismatch(replayFrom, engine.lastAppliedSeq(),
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        (System.nanoTime() - replayStartedNs) / 1_000_000L);
            }
            throw e;
        }
        if (recoveryRecorder != null) {
            recoveryRecorder.recordSuccess(replayFrom, result.lastAppliedSeq(),
                    result.verifiedEvents(), (System.nanoTime() - replayStartedNs) / 1_000_000L);
        }
        replayObserver.accept(result.replayedCommands());
        List<EngineEvent> pending = result.pendingEvents();
        if (pending.isEmpty()) {
            return result;
        }
        // 재적재는 리더 자격이 필요하다. 승격 실패(유효한 타 리더 존재)는 기동 거부로 처리한다.
        // 그 리더가 같은 창을 이미 메웠다면 애초에 pending이 비고, 메우는 중이라면 여기서의
        // 병행 적재는 레지스트리 충돌·fencing으로 어차피 실패해야 맞다(무음 유실 금지).
        if (!lease.isLeader() && !acquireLeadership(promotionWait)) {
            throw new IllegalStateException(
                    "크래시 창 이벤트 " + pending.size() + "건 재적재 불가: 리더 승격 실패"
                            + (promotionWait.isZero() ? ""
                                    : "(대기 " + promotionWait.toMillis() + "ms 소진)"));
        }
        // EngineConfig 순서상 recover()가 lease.start()보다 먼저 돌아 이 구간엔 스케줄러
        // renew가 없다. 대형 크래시 창 재적재가 TTL을 넘기면 정당 단독 리더가 fencing으로
        // 자기-거부한다. 재적재 중 주기 renew로 리스를 살려둔다(§5.5). renew 실패는 실제
        // 상실이므로 이어지는 sink append가 NotLeader/fencing으로 fail-fast한다.
        // 첫 배치 직전에 한 번 당긴다. 승격 재생은 리스 획득과 첫 fencing 쓰기 사이에 스냅샷
        // 로드·tail 재생을 통째로 끼우므로, 그 구간에 하트비트가 한 틱만 밀려도 정당한 새 리더의
        // 첫 정산이 만료 리스로 거부된다(E2 라이브의 assert_current_leader 거부 창).
        lease.renew();
        long renewIntervalNanos = lease.ttl().dividedBy(3).toNanos();
        long lastRenewNanos = System.nanoTime();
        long eventsSinceRenew = 0;
        // 배치 그룹핑은 pending이 source_cmd_seq 오름차순·연속이라는 재생 방출 순서에 의존한다.
        // 명령 단위 원자성 때문에 한 배치(같은 source_cmd_seq) 내부에서는 renew할 수 없다.
        // 명령 경계마다 시간·누적 이벤트 수 둘 다로 판정해 renew 간격을 이중으로 상한한다.
        int from = 0;
        while (from < pending.size()) {
            if (System.nanoTime() - lastRenewNanos >= renewIntervalNanos
                    || eventsSinceRenew >= RENEW_EVENT_INTERVAL) {
                lease.renew();
                lastRenewNanos = System.nanoTime();
                eventsSinceRenew = 0;
            }
            int to = from + 1;
            while (to < pending.size()
                    && pending.get(to).sourceCmdSeq() == pending.get(from).sourceCmdSeq()) {
                to++;
            }
            List<EngineEvent> batch = pending.subList(from, to);
            try {
                reappendSink.accept(batch);
            } catch (Exception e) {
                throw new IllegalStateException("크래시 창 재적재·재정산 실패: source_cmd_seq="
                        + batch.get(0).sourceCmdSeq(), e);
            }
            eventsSinceRenew += (to - from);
            from = to;
        }
        log.info("크래시 창 이벤트 재적재·재정산: {}건 (source_cmd_seq {}..{})", pending.size(),
                pending.get(0).sourceCmdSeq(), pending.get(pending.size() - 1).sourceCmdSeq());
        return result;
    }

    /**
     * 두 단계 재생. 1 단계는 체크포인트까지 대조만 하고 2 단계는 그 초과분을 재적재 대상으로 낸다.
     *
     * 두 단계로 가르는 이유가 있다. 앞 구간은 이벤트가 전부 있는 안정 구간이라 대조만 하면 되고
     * 리더 자격이 필요 없다. 뒤 구간은 이벤트가 없는 구간이라 재적재가 필요하고 그것은 리더만
     * 할 수 있다. 하나로 묶으면 앞 구간까지 리더 자격에 걸린다.
     *
     * 2 단계도 상한을 건다. 상한 없이 읽으면 다른 노드가 쓰는 동안 루프가 끝나지 않는다. 상한을
     * 잡아 두면 유한하게 끝나고, 리스를 잃었다면 이어지는 재적재가 fencing 으로 거부된다.
     */
    private ReplayResult replayToCheckpointThenTail(MatchingEngineImpl engine,
                                                    java.time.Duration promotionWait,
                                                    boolean followerAllowed) {
        kr.exchange.journal.JournalCheckpoint bound =
                replayService.materializedThrough(engine.lastAppliedSeq());
        ReplayResult stable = replayService.verifyTo(engine, bound);
        log.info("재생 1단계(물질화 경계 상한): cmd<={}, 적용 {}건, 대조 {}건",
                bound.commandSeq(), stable.replayedCommands(), stable.verifiedEvents());

        long head = replayService.journalHead();
        long beyond = head - bound.commandSeq();
        if (beyond <= 0) {
            return stable;
        }
        if (!lease.isLeader() && !acquireLeadership(promotionWait)) {
            if (followerAllowed) {
                log.info("리더 자격 없이 기동한다(팔로워). 체크포인트 초과 명령 {}건은 catch-up 이 따라잡는다",
                        beyond);
                return stable;
            }
            throw new IllegalStateException(
                    "크래시 창 명령 " + beyond + "건 재적재 불가: 리더 승격 실패"
                            + (promotionWait.isZero() ? ""
                                    : "(대기 " + promotionWait.toMillis() + "ms 소진)"));
        }

        // 리더 자격을 기다리는 사이 다른 노드가 창을 메웠을 수 있다. 상한을 다시 읽어 검증
        // 가능한 구간을 먼저 소진한다. 대조 없이 재적재로 넘기면 그 구간의 불일치를 놓친다.
        kr.exchange.journal.JournalCheckpoint after =
                replayService.materializedThrough(engine.lastAppliedSeq());
        ReplayResult verified = stable;
        if (after.commandSeq() > bound.commandSeq()) {
            ReplayResult more = replayService.verifyTo(engine, after);
            verified = merge(stable, more, more.lastAppliedSeq(), more.pendingEvents());
            log.info("재생 1단계 연장(대기 중 메워진 구간): cmd<={}, 적용 {}건",
                    after.commandSeq(), more.replayedCommands());
        }

        // 2 단계. 지금 잡은 head 로 상한한다. 이벤트 커서를 열지 않으므로 방출이 전부 pending
        // 으로 나온다. 그 안에는 크래시 창과 경계 위에 이미 물질화된 명령이 섞인다. 상한이 첫
        // 구멍 직전이라 그 위의 물질화된 명령이 이 구간에 들어오기 때문이다. 후자는 재적재에서
        // event_emission_registry 멱등 스킵이 걸러낸다. 안전을 지키는 것은 그 레지스트리이지
        // 이 구간의 방출 성질이 아니다.
        long tailHead = replayService.journalHead();
        if (tailHead <= after.commandSeq()) {
            return verified;
        }
        ReplayResult tail = replayService.replayTail(engine, tailHead);
        log.info("재생 2단계(크래시 창 상한 {}): 적용 {}건, 재적재 대상 {}건",
                tailHead, tail.replayedCommands(), tail.pendingEvents().size());
        return merge(verified, tail, tail.lastAppliedSeq(), tail.pendingEvents());
    }

    /** 두 재생 결과를 하나로 접는다. 관측 수치가 단계마다 잘리지 않게 한다. */
    private static ReplayResult merge(ReplayResult first, ReplayResult second, long lastAppliedSeq,
                                      List<EngineEvent> pending) {
        return new ReplayResult(lastAppliedSeq,
                first.replayedCommands() + second.replayedCommands(),
                first.verifiedEvents() + second.verifiedEvents(),
                pending);
    }

    /**
     * 유효한 리스를 쥔 노드가 살아서 갱신하고 있는가. 참이면 만료를 기다릴 이유가 없다.
     *
     * 기동 대기의 원래 목적은 "직전 프로세스가 남긴 리스가 만료되기를 기다린다" 이다. 그 리스는
     * 갱신되지 않으므로 만료 시각이 움직이지 않는다. 살아 있는 리더의 리스는 움직인다. 그 차이로
     * 가른다. 표본 간격은 TTL 의 절반이라 갱신 주기(TTL 보다 짧다)를 반드시 한 번 이상 덮는다.
     */
    private boolean anotherLeaderRenewing() {
        java.util.Optional<kr.exchange.engine.lease.LeaseSnapshot> before = lease.leaseSnapshot();
        if (before.isEmpty() || before.get().holder().isEmpty()) {
            return false;
        }
        try {
            Thread.sleep(lease.ttl().dividedBy(2).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        java.util.Optional<kr.exchange.engine.lease.LeaseSnapshot> after = lease.leaseSnapshot();
        return after.isPresent()
                && after.get().leaseUntil().isAfter(before.get().leaseUntil());
    }

    // 승격 획득만 담당하고 획득 후 로직은 무변경이다. 대기는 부팅 스레드의 블로킹일 뿐
    // 엔진 결정론·저널·매칭 순서와 무관하다. 인터럽트는 대기 포기로 처리해 기동 실패로 흐른다.
    private boolean acquireLeadership(java.time.Duration wait) {
        if (lease.tryAcquire()) {
            return true;
        }
        if (wait.isZero() || wait.isNegative()) {
            return false;
        }
        if (anotherLeaderRenewing()) {
            // 살아 있는 리더가 갱신 중이다. 만료를 기다려도 오지 않는다.
            log.info("유효한 타 리더가 리스를 갱신 중이다. 승격 대기를 건너뛴다");
            return false;
        }
        log.warn("리더 승격 실패(잔존 리스 만료 대기 후 재시도): 상한 {}ms", wait.toMillis());
        long deadlineNanos = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(BOOT_PROMOTION_RETRY_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (lease.tryAcquire()) {
                return true;
            }
        }
        return false;
    }
}

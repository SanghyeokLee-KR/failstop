package kr.exchange.engine;

import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.JournalCheckpoints;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayResult.PendingBatch;
import kr.exchange.journal.ReplayService;
import kr.exchange.snapshot.SnapshotSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

/**
 * 복구: 저널 재생으로 오더북을 재구성하고, 크래시 창(명령 커밋 후·이벤트 커밋 전)의
 * 재방출 이벤트를 재적재·재정산한다(DB 설계 §5.5 ②).
 *
 * 경로는 셋이다. 기동과 파티션 재구성은 팔로워 복구다. 마켓별 물질화 경계까지 대조만 하고 리스를 얻지 않으며 크래시 창을
 * 재적재하지 않는다(DB 설계 §5.4-c 4·5). 크래시 창 재적재는 승격 재생만 한다. 승격 재생은 기간을 쥐고 그 파티션의 마켓
 * M(P)만 다룬다(§5.4-c 7). 기간 없는 종전 승격(recover 단일 인자)은 시험과 단일 리스 배선이 쓴다.
 *
 * 기동 경로를 체크포인트로 상한하는 이유(2026-09-08). 상한이 없으면 다른 노드가 리더로서 계속 쓰는 동안 "명령은 있는데
 * 이벤트가 아직 없는" 구간이 항상 남고, 부팅 노드가 그것을 자기 크래시 창으로 읽는다. 두 노드 실측에서 장애 노드가 그
 * 경로로 재합류하지 못했다(`harness/evidence/leader-failover-20260908/`).
 *
 * <p>승격 재생도 같은 두 단계를 탄다(2026-09-09). 2 단계도 그 시점에 잡아 둔 마켓별 head 로 상한한다. 상한이 없으면 승격한
 * 노드가 리스를 잃고 다른 노드가 리더가 됐을 때 재생 루프가 새 리더의 명령을 계속 읽어 끝나지 않는다. 2026-09-08 회차에서
 * 실제로 CPU 200% 로 4분간 응답하지 못했다(`harness/evidence/follower-rejoin-20260908/` 무효 1회차).
 *
 * 축은 마켓 벡터다. 경계, 커서, 2 단계 상한이 모두 마켓마다 따로다(ADR 0022 5-1, 5-4).
 *
 * pending을 버리고 기동하면 이후 신규 명령의 이벤트가 계속 적재되면서 저널 스트림이
 * 재생 스트림의 prefix 성질을 잃고, 다음 재기동의 대조가 영구 실패한다(무음 유실 + 벽돌).
 * 재적재 순서는 마켓별 큐를 (created_at, seq) 머리 키로 병합한 순서다(ADR 0022 5-3). 마켓 안 순서는
 * 큐가 지키므로 market_seq 가 마켓마다 이어서 배정되어 prefix 성질이 유지되고, 마켓 사이에서는
 * 사용자가 만든 자금 의존(한 마켓 체결 대금으로 다른 마켓 주문)이 시간 순서로 먼저 정산된다. 파티션 승격은 M(P) 안에서
 * 병합한다. 파티션 사이의 자금 의존은 접수 시점 예약(P2 이상)이 없앤다.
 *
 * sink는 라이브 루프와 동일 계약(명령 단위 배치, append와 정산의 원자 커밋)이다. 명령
 * 단위로 넘기므로 도중 크래시가 나도 커밋된 prefix는 레지스트리 멱등 판정으로
 * 스킵되고 나머지만 재정산된다. 기간을 쥔 승격 재생의 sink 는 그 기간으로 쓴다(§5.4-c 6).
 *
 * sink가 배정한 event_seq는 재적재 결과라 여기서 버린다. 복구는 WS 게시 대상이 아니고
 * (라이브만 게시), 재기동 후 스냅샷·구독으로 상태가 전달된다. 게시 비대칭은 의도된 것이다.
 */
public class EngineRecovery {

    private static final Logger log = LoggerFactory.getLogger(EngineRecovery.class);
    // 시간 기준 renew 사이에도 이 이벤트 수마다 리스를 갱신한다. 명령 하나가 큰 이벤트 배치를
    // 내면(북 스윕) 시간 판정만으로는 renew 간격이 벌어질 수 있어, 누적 이벤트 상한으로 보강한다.
    private static final int RENEW_EVENT_INTERVAL = 10_000;
    private static final Comparator<PendingBatch> MERGE_KEY =
            Comparator.comparing((PendingBatch batch) -> batch.createdAt().toInstant())
                    .thenComparingLong(PendingBatch::seq);
    private static final IntPredicate ALL_MARKETS = marketId -> true;

    /** 기간을 쥔 재적재 sink. 라이브 루프 sink 와 같은 계약이고 그 기간의 epoch 로 쓴다. */
    @FunctionalInterface
    public interface TermEventSink {
        void accept(LeaderTerm term, List<EngineEvent> events) throws Exception;
    }

    /** 한 번의 복구가 크래시 창을 재적재할 권한. 팔로워 복구에는 없다. */
    private interface Reinjection {
        /** 2 단계에 들어갈 권한을 확보한다. 거짓이면 승격이 실패한다. */
        boolean authorize();

        /** 권한을 여전히 쥐고 있는가. 확보를 시도하지 않는다. */
        boolean holding();

        void renew();

        /** 재적재 중 renew 간격. TTL 의 3분의 1 이다. */
        java.time.Duration renewInterval();

        void append(List<EngineEvent> events) throws Exception;
    }

    private final ReplayService replayService;
    // 종전 승격 경로(recover 단일 인자)만 쓴다.
    private final SingleWriterLoop.EventSink reappendSink;
    private final LeaderLeaseService lease;
    // 기간 승격 경로만 쓴다.
    private final TermEventSink termSink;
    // null이면 스냅샷 비활성. 저널 seq 0 전량 재생으로만 복구한다(기존 경로).
    private final SnapshotSource snapshotSource;
    // 재생 명령 수 관측(§5-2 exchange.journal.replay.commands). 기본 no-op이라 테스트 경로는 무변경.
    private final java.util.function.LongConsumer replayObserver;
    // 크래시 창 큐에서 마켓 안 created_at 이 seq 순서와 거꾸로 선 배치 수. 시계 역행의 흔적이다(ADR 0022 9-17).
    private final AtomicLong createdAtInversions = new AtomicLong();
    // 스냅샷 시작 복구를 버리고 전량 재생한 횟수. 폴백은 가용성을 지키지만 결함의 신호다(ADR 0022 7단계).
    private final AtomicLong snapshotFallbacks = new AtomicLong();
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
        this(replayService, reappendSink, lease, null, snapshotSource, replayObserver);
    }

    /** 파티션 배선. 크래시 창 재적재는 기간을 쥔 승격 재생(recover 세 인자)만 한다. */
    public EngineRecovery(ReplayService replayService, TermEventSink termSink, SnapshotSource snapshotSource,
                          java.util.function.LongConsumer replayObserver) {
        this(replayService, null, null, termSink, snapshotSource, replayObserver);
    }

    private EngineRecovery(ReplayService replayService, SingleWriterLoop.EventSink reappendSink,
                           LeaderLeaseService lease, TermEventSink termSink, SnapshotSource snapshotSource,
                           java.util.function.LongConsumer replayObserver) {
        this.replayService = replayService;
        this.reappendSink = reappendSink;
        this.lease = lease;
        this.termSink = termSink;
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

    /** 누적 마켓 안 created_at 역전 배치 수. 지표 exchange.recovery.reinject.created_at_inversions 의 원천이다. */
    public long reinjectCreatedAtInversions() {
        return createdAtInversions.get();
    }

    /** 누적 스냅샷 폴백 수. 지표 exchange.recovery.snapshot_fallback 의 원천이다. */
    public long snapshotFallbacks() {
        return snapshotFallbacks.get();
    }

    /**
     * 종전 승격 재생(모든 마켓, 기간 없음). 스냅샷 로드 후 tail 재생(복구 RTO 설계 §3-b). 마켓마다 최신 채택 스냅샷을 적재하고
     * 그 마켓에서 seq > 스냅샷 seq 만 재생해 tail 을 상한한다. 채택본이 없는 마켓은 0 에서 재생한다. 크래시 창이 있으면
     * 리스 서비스의 리더 자격을 요구하고, 없으면 한 번 얻어 본다. engine 은 fresh 여야 한다(MatchingEngineImpl.loadMarketSnapshot 가드).
     */
    public ReplayResult recover(MatchingEngineImpl engine) {
        if (reappendSink == null || lease == null) {
            throw new IllegalStateException("파티션 배선은 기간을 쥔 recover(engine, scope, term)을 쓴다");
        }
        return recover(engine, ALL_MARKETS, new Reinjection() {
            @Override
            public boolean authorize() {
                return lease.isLeader() || lease.tryAcquire();
            }

            @Override
            public boolean holding() {
                return lease.isLeader();
            }

            @Override
            public void renew() {
                lease.renew();
            }

            @Override
            public java.time.Duration renewInterval() {
                return lease.ttl().dividedBy(3);
            }

            @Override
            public void append(List<EngineEvent> events) throws Exception {
                reappendSink.accept(events);
            }
        });
    }

    /**
     * 파티션 승격 재생(DB 설계 §5.4-c 6·7). scope 의 마켓만 스냅샷에서 적재하고 대조·재적재한다. 재적재 sink 는 이 기간으로
     * 쓰므로, 재생 도중 기간이 끝나면(회복 가능 반납) 늦게 남은 쓰기가 앱과 DB 양쪽에서 거부된다. 리스는 얻지 않는다.
     */
    public ReplayResult recover(MatchingEngineImpl engine, IntPredicate scope, LeaderTerm term) {
        if (termSink == null) {
            throw new IllegalStateException("기간 재적재 sink 없는 배선이다");
        }
        LeaderLeaseService termLease = term.lease();
        return recover(engine, scope, new Reinjection() {
            @Override
            public boolean authorize() {
                return term.isCurrent();
            }

            @Override
            public boolean holding() {
                return term.isCurrent();
            }

            @Override
            public void renew() {
                if (term.isCurrent()) {
                    termLease.renew();
                }
            }

            @Override
            public java.time.Duration renewInterval() {
                return termLease.ttl().dividedBy(3);
            }

            @Override
            public void append(List<EngineEvent> events) throws Exception {
                termSink.accept(term, events);
            }
        });
    }

    /**
     * 기동 복구(DB 설계 §5.4-c 4). 모든 마켓을 물질화 경계까지 대조만 한다. 리스를 얻지 않고 크래시 창을 재적재하지 않는다.
     * 경계를 넘은 마켓은 팔로워 따라잡기가 잇고, 크래시 창은 다음 리더의 승격 재생이 메운다.
     */
    public ReplayResult recoverAtBoot(MatchingEngineImpl engine) {
        return recoverFollower(engine, ALL_MARKETS);
    }

    /** 팔로워 복구. 파티션 재구성(§5.4-c 5)이 scope 의 마켓으로 부른다. 기동 복구와 같다. */
    public ReplayResult recoverFollower(MatchingEngineImpl engine, IntPredicate scope) {
        return recover(engine, scope, (Reinjection) null);
    }

    private ReplayResult recover(MatchingEngineImpl engine, IntPredicate scope, Reinjection reinjection) {
        // 이벤트 커서 시작점을 여기서 유도하지 않는다. loadMarketSnapshot 이 마켓 커서를 그 마켓 스냅샷 seq 로
        // 세우고 재생이 engine.appliedSeq(marketId) 에서 커서를 연다. 호출자가 넘기면 다른 축 값이 들어갈 수 있다(ADR 0023 결정 2).
        ReplayResult result = snapshotSource == null ? null
                : recoverFromSnapshots(engine, scope, reinjection);
        if (result == null) {
            result = replayRecorded(engine, scope, reinjection);
        }
        replayObserver.accept(result.replayedCommands());
        List<PendingBatch> pending = result.pendingBatches();
        if (pending.isEmpty()) {
            return result;
        }
        // 재적재는 리더 자격이 필요하다. 그 리더가 같은 창을 이미 메웠다면 애초에 pending이 비고, 메우는 중이라면 여기서의
        // 병행 적재는 레지스트리 충돌·fencing으로 어차피 실패해야 맞다(무음 유실 금지).
        if (reinjection == null || !reinjection.authorize()) {
            throw new IllegalStateException(
                    "크래시 창 이벤트 " + result.pendingEvents().size() + "건 재적재 불가: 리더 자격 없음");
        }
        // 대형 크래시 창 재적재가 TTL을 넘기면 정당 단독 리더가 fencing으로 자기-거부한다. 재적재 중 주기 renew로 리스를
        // 살려둔다(§5.5). renew 실패는 실제 상실이므로 이어지는 sink append가 NotLeader/fencing으로 fail-fast한다.
        // 첫 배치 직전에 한 번 당긴다. 승격 재생은 리스 획득과 첫 fencing 쓰기 사이에 스냅샷
        // 로드·tail 재생을 통째로 끼우므로, 그 구간에 하트비트가 한 틱만 밀려도 정당한 새 리더의
        // 첫 정산이 만료 리스로 거부된다(E2 라이브의 assert_current_leader 거부 창).
        reinjection.renew();
        long renewIntervalNanos = reinjection.renewInterval().toNanos();
        long lastRenewNanos = System.nanoTime();
        long eventsSinceRenew = 0;
        // 명령 단위 원자성 때문에 한 배치 안에서는 renew할 수 없다. 배치 경계마다 시간·누적
        // 이벤트 수 둘 다로 판정해 renew 간격을 이중으로 상한한다.
        for (PendingBatch batch : pending) {
            if (System.nanoTime() - lastRenewNanos >= renewIntervalNanos
                    || eventsSinceRenew >= RENEW_EVENT_INTERVAL) {
                reinjection.renew();
                lastRenewNanos = System.nanoTime();
                eventsSinceRenew = 0;
            }
            try {
                reinjection.append(batch.events());
            } catch (Exception e) {
                throw new IllegalStateException("크래시 창 재적재·재정산 실패: source_cmd_seq="
                        + batch.seq(), e);
            }
            eventsSinceRenew += batch.events().size();
        }
        log.info("크래시 창 이벤트 재적재·재정산: {}건, 명령 {}건", result.pendingEvents().size(), pending.size());
        return result;
    }


    /**
     * 마켓마다 채택한 스냅샷을 새 엔진에 싣고 재생까지 마친 뒤 그 상태를 engine 으로 옮긴다. 채택본이 없거나 폴백하면
     * null 이다.
     *
     * 새 엔진에서 도는 이유는 폴백이다. 적재나 재생이 실패해도 engine 이 손대지 않은 채 남아 스냅샷 없는 전량 재생을
     * 그대로 탈 수 있다. 스냅샷은 저널 파생 최적화라 그 결함이 복구를 막으면 안 된다(ADR 0022 7단계).
     */
    private ReplayResult recoverFromSnapshots(MatchingEngineImpl engine, IntPredicate scope,
                                              Reinjection reinjection) {
        MatchingEngineImpl fromSnapshots = new MatchingEngineImpl();
        Map<Integer, SnapshotSource.MarketLoaded> snaps;
        try {
            snaps = snapshotSource.loadLatestAdoptable(scope);
            snaps.forEach((marketId, snap) -> fromSnapshots.loadMarketSnapshot(marketId, snap.seq(), snap.book()));
        } catch (RuntimeException e) {
            fallBackToFullReplay("스냅샷 적재 실패", e);
            return null;
        }
        if (snaps.isEmpty()) {
            return null;
        }
        log.info("스냅샷 채택(마켓별): {}", snaps.entrySet().stream()
                .map(e -> e.getKey() + "@" + e.getValue().seq()).toList());
        ReplayResult result;
        try {
            result = replayRecorded(fromSnapshots, scope, reinjection);
        } catch (RuntimeException e) {
            if (reinjection != null && !reinjection.holding()) {
                // 리더 자격을 잃은 승격은 전량 재생으로도 끝까지 가지 못한다. 다음 승격이 새 epoch 로 다시 한다.
                throw e;
            }
            fallBackToFullReplay("스냅샷 시작 재생 실패", e);
            return null;
        }
        for (int marketId : fromSnapshots.markets()) {
            engine.loadMarketSnapshot(marketId, fromSnapshots.appliedSeq(marketId), fromSnapshots.orderBook(marketId));
        }
        return result;
    }

    private void fallBackToFullReplay(String cause, RuntimeException e) {
        snapshotFallbacks.incrementAndGet();
        log.error("{}. 스냅샷 없이 전량 재생으로 폴백한다", cause, e);
    }

    /** 재생 한 벌을 돌리고 그 결과를 recovery_runs 에 남긴다(C17 원천). 예외는 기록한 뒤 그대로 던진다. */
    private ReplayResult replayRecorded(MatchingEngineImpl engine, IntPredicate scope, Reinjection reinjection) {
        long replayFrom = engine.lastAppliedSeq();
        long replayStartedNs = System.nanoTime();
        ReplayResult result;
        try {
            result = replayToCheckpointThenTail(engine, scope, reinjection);
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
            // 표시 전용 전 마켓 통합 행이다(DB 설계 §3.1 센티널 market_id 0). 마켓별 구간은 재생 적용 로그에 남는다.
            recoveryRecorder.recordSuccess(replayFrom, result.lastAppliedSeq(),
                    result.verifiedEvents(), (System.nanoTime() - replayStartedNs) / 1_000_000L);
        }
        return result;
    }

    /**
     * 마켓별 두 단계 재생(ADR 0022 5-4). 1 단계는 scope 의 마켓마다 bound(m) 까지 대조만 하고 2 단계는 그 초과분을
     * 재적재 대상으로 낸다.
     *
     * 두 단계로 가르는 이유가 있다. 앞 구간은 이벤트가 전부 있는 안정 구간이라 대조만 하면 되고
     * 리더 자격이 필요 없다. 뒤 구간은 이벤트가 없는 구간이라 재적재가 필요하고 그것은 리더만
     * 할 수 있다. 하나로 묶으면 앞 구간까지 리더 자격에 걸린다. 팔로워 복구는 1 단계에서 끝난다.
     *
     * 2 단계 상한 head(m) 은 리더 자격을 확인하고 늘어난 경계 구간의 대조까지 마친 뒤 scope 의 모든 마켓에서 한 번
     * 읽어 고정하고 도는 중에 다시 읽지 않는다.
     */
    private ReplayResult replayToCheckpointThenTail(MatchingEngineImpl engine, IntPredicate scope,
                                                    Reinjection reinjection) {
        Map<Integer, Long> startCursors = engine.appliedSeqs();
        Map<Integer, Long> appliedByMarket = new TreeMap<>();
        JournalCheckpoints bounds = scoped(replayService.materializedThroughByMarket(engine), scope);
        ReplayResult stable = verifyMarkets(engine, bounds, appliedByMarket);
        log.info("재생 1단계(마켓별 물질화 경계 상한): 마켓 {}개, 적용 {}건, 대조 {}건",
                bounds.bounds().size(), stable.replayedCommands(), stable.verifiedEvents());

        List<Integer> beyond = marketsBeyond(engine, scoped(replayService.journalHeads(), scope));
        if (beyond.isEmpty()) {
            logAppliedByMarket(engine, startCursors, appliedByMarket);
            return stable;
        }
        if (reinjection == null) {
            log.info("팔로워로 복구한다. 체크포인트를 넘은 마켓 {} 는 catch-up 이 따라잡는다", beyond);
            logAppliedByMarket(engine, startCursors, appliedByMarket);
            return stable;
        }
        if (!reinjection.authorize()) {
            throw new IllegalStateException("크래시 창 마켓 " + beyond + " 재적재 불가: 리더 자격 없음");
        }

        // 리더 자격을 확인하는 사이 다른 노드가 창을 메웠을 수 있다. 경계를 다시 읽어 검증
        // 가능한 구간을 먼저 소진한다. 대조 없이 재적재로 넘기면 그 구간의 불일치를 놓친다.
        ReplayResult more = verifyMarkets(engine, scoped(replayService.materializedThroughByMarket(engine), scope),
                appliedByMarket);
        if (more.replayedCommands() > 0) {
            log.info("재생 1단계 연장(대기 중 메워진 구간): 적용 {}건", more.replayedCommands());
        }

        // 2 단계. 이벤트 커서를 열지 않으므로 방출이 전부 pending 이다. 마켓 안 라이터는 seq 순으로
        // 정산하므로 bound(m) 위에 그 마켓의 물질화된 명령이 없어 방출은 크래시 창뿐이다.
        // event_emission_registry 멱등 스킵은 안전망으로 남는다.
        Map<Integer, Long> tailHeads = scoped(replayService.journalHeads(), scope);
        Map<Integer, List<PendingBatch>> queues = new TreeMap<>();
        long tailCommands = 0;
        for (Map.Entry<Integer, Long> head : tailHeads.entrySet()) {
            int marketId = head.getKey();
            if (head.getValue() <= engine.appliedSeq(marketId)) {
                continue;
            }
            ReplayResult tail = replayService.replayTailMarket(engine, marketId, head.getValue());
            tailCommands += tail.replayedCommands();
            appliedByMarket.merge(marketId, tail.replayedCommands(), Long::sum);
            queues.put(marketId, tail.pendingBatches());
        }
        List<PendingBatch> ordered = mergeByCreatedAt(queues);
        log.info("재생 2단계(마켓별 head 고정): 마켓 {}개, 적용 {}건, 재적재 대상 명령 {}건",
                queues.size(), tailCommands, ordered.size());
        logAppliedByMarket(engine, startCursors, appliedByMarket);
        return new ReplayResult(engine.lastAppliedSeq(),
                stable.replayedCommands() + more.replayedCommands() + tailCommands,
                stable.verifiedEvents() + more.verifiedEvents(), ordered);
    }

    private static JournalCheckpoints scoped(JournalCheckpoints bounds, IntPredicate scope) {
        if (scope == ALL_MARKETS) {
            return bounds;
        }
        Map<Integer, Long> kept = new TreeMap<>();
        bounds.bounds().forEach((marketId, bound) -> {
            if (scope.test(marketId)) {
                kept.put(marketId, bound);
            }
        });
        return new JournalCheckpoints(kept, bounds.legacyBound());
    }

    private static Map<Integer, Long> scoped(Map<Integer, Long> heads, IntPredicate scope) {
        if (scope == ALL_MARKETS) {
            return heads;
        }
        Map<Integer, Long> kept = new TreeMap<>();
        heads.forEach((marketId, head) -> {
            if (scope.test(marketId)) {
                kept.put(marketId, head);
            }
        });
        return kept;
    }

    private ReplayResult verifyMarkets(MatchingEngineImpl engine, JournalCheckpoints bounds,
                                       Map<Integer, Long> appliedByMarket) {
        long replayed = 0;
        long verified = 0;
        for (int marketId : bounds.bounds().keySet()) {
            ReplayResult result = replayService.verifyToMarket(engine, marketId, bounds);
            replayed += result.replayedCommands();
            verified += result.verifiedEvents();
            appliedByMarket.merge(marketId, result.replayedCommands(), Long::sum);
        }
        return new ReplayResult(engine.lastAppliedSeq(), replayed, verified, List.of());
    }

    /**
     * 마켓마다 이번 재생의 커서 구간과 적용 명령 수를 남긴다. 스냅샷 경계 재생을 저널의 그 구간 명령 수와 대조하는
     * 증거다. recovery_runs 는 전 마켓 통합 행이라 이 분해를 담지 않는다.
     */
    private static void logAppliedByMarket(MatchingEngineImpl engine, Map<Integer, Long> startCursors,
                                           Map<Integer, Long> appliedByMarket) {
        Map<Integer, String> byMarket = new TreeMap<>();
        appliedByMarket.forEach((marketId, applied) -> byMarket.put(marketId,
                startCursors.getOrDefault(marketId, 0L) + ".." + engine.appliedSeq(marketId) + " " + applied + "건"));
        log.info("재생 적용(마켓별): {}", byMarket);
    }

    private static List<Integer> marketsBeyond(MatchingEngineImpl engine, Map<Integer, Long> heads) {
        List<Integer> beyond = new ArrayList<>();
        heads.forEach((marketId, head) -> {
            if (head > engine.appliedSeq(marketId)) {
                beyond.add(marketId);
            }
        });
        return beyond;
    }

    /**
     * 마켓별 크래시 창 큐를 (created_at, seq) 머리 키로 병합한다(ADR 0022 5-3).
     *
     * 마켓 안 순서는 키가 아니라 큐가 정하고 병합은 그것을 바꾸지 않는다. 마켓 안에서 앞 배치보다
     * created_at 이 이른 배치는 멈추지 않고 센다. 그것에 의존한 다른 마켓 배치가 먼저 나가면 23514 로
     * 드러나 무음으로 가지 않는다.
     */
    private List<PendingBatch> mergeByCreatedAt(Map<Integer, List<PendingBatch>> queues) {
        record Head(PendingBatch batch, List<PendingBatch> queue, int index) {
        }
        PriorityQueue<Head> heads = new PriorityQueue<>((a, b) -> MERGE_KEY.compare(a.batch(), b.batch()));
        int total = 0;
        for (Map.Entry<Integer, List<PendingBatch>> entry : queues.entrySet()) {
            List<PendingBatch> queue = entry.getValue();
            for (int i = 1; i < queue.size(); i++) {
                if (queue.get(i).createdAt().isBefore(queue.get(i - 1).createdAt())) {
                    createdAtInversions.incrementAndGet();
                    log.warn("크래시 창 마켓 안 created_at 역전: market={}, seq {} 가 앞 seq {} 보다 이르다. seq 순서를 유지한다",
                            entry.getKey(), queue.get(i).seq(), queue.get(i - 1).seq());
                }
            }
            if (!queue.isEmpty()) {
                heads.add(new Head(queue.get(0), queue, 0));
            }
            total += queue.size();
        }
        List<PendingBatch> ordered = new ArrayList<>(total);
        while (!heads.isEmpty()) {
            Head head = heads.poll();
            ordered.add(head.batch());
            int next = head.index() + 1;
            if (next < head.queue().size()) {
                heads.add(new Head(head.queue().get(next), head.queue(), next));
            }
        }
        return ordered;
    }
}

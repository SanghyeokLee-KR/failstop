package kr.exchange.engine;

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
 * 그래서 2 단계도 그 시점에 잡아 둔 마켓별 head 로 상한한다. 상한이 있으면 루프가 유한하게 끝나고,
 * 리스를 잃었다면 이어지는 재적재가 fencing 으로 즉시 거부된다. 무한 재생이 fail-fast 로 바뀐다.
 *
 * 두 경로의 차이는 이제 하나뿐이다. 2 단계에서 리더 자격을 얻지 못했을 때 기동은 팔로워로 뜨고
 * 승격은 실패한다. 승격은 리더 자격이 전제인 경로이므로 그 전제가 사라지면 진행할 이유가 없다.
 *
 * 축은 마켓 벡터다. 경계, 커서, 2 단계 상한이 모두 마켓마다 따로다(ADR 0022 5-1, 5-4).
 *
 * pending을 버리고 기동하면 이후 신규 명령의 이벤트가 계속 적재되면서 저널 스트림이
 * 재생 스트림의 prefix 성질을 잃고, 다음 재기동의 대조가 영구 실패한다(무음 유실 + 벽돌).
 * 재적재 순서는 마켓별 큐를 (created_at, seq) 머리 키로 병합한 순서다(ADR 0022 5-3). 마켓 안 순서는
 * 큐가 지키므로 market_seq 가 마켓마다 이어서 배정되어 prefix 성질이 유지되고, 마켓 사이에서는
 * 사용자가 만든 자금 의존(한 마켓 체결 대금으로 다른 마켓 주문)이 시간 순서로 먼저 정산된다.
 *
 * sink는 라이브 루프와 동일 계약(명령 단위 배치, append와 정산의 원자 커밋)이다. 명령
 * 단위로 넘기므로 도중 크래시가 나도 커밋된 prefix는 레지스트리 멱등 판정으로
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
    private static final Comparator<PendingBatch> MERGE_KEY =
            Comparator.comparing((PendingBatch batch) -> batch.createdAt().toInstant())
                    .thenComparingLong(PendingBatch::seq);

    private final ReplayService replayService;
    private final SingleWriterLoop.EventSink reappendSink;
    private final LeaderLeaseService lease;
    // null이면 스냅샷 비활성. 저널 seq 0 전량 재생으로만 복구한다(기존 경로).
    private final SnapshotSource snapshotSource;
    // 재생 명령 수 관측(§5-2 exchange.journal.replay.commands). 기본 no-op이라 테스트 경로는 무변경.
    private final java.util.function.LongConsumer replayObserver;
    // 크래시 창 큐에서 마켓 안 created_at 이 seq 순서와 거꾸로 선 배치 수. 시계 역행의 흔적이다(ADR 0022 9-17).
    private final AtomicLong createdAtInversions = new AtomicLong();
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

    /** 누적 마켓 안 created_at 역전 배치 수. 지표 exchange.recovery.reinject.created_at_inversions 의 원천이다. */
    public long reinjectCreatedAtInversions() {
        return createdAtInversions.get();
    }

    /**
     * 스냅샷 로드 후 tail 재생(복구 RTO 설계 §3-b). fresh 엔진에 마켓마다 최신 채택 스냅샷을 적재하고
     * 그 마켓에서 seq > 스냅샷 seq 만 재생해 tail 을 상한한다. 채택본이 없는 마켓은 0 에서 재생한다.
     * 버전·설정·checksum 불일치는 게이트가 폐기하므로 여기까지 오지 않는다(§3-b 단일 정본 규율).
     * 로드는 fresh 마켓에만 허용된다(MatchingEngineImpl.loadMarketSnapshot 가드).
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
        // 이벤트 커서 시작점을 여기서 유도하지 않는다. loadMarketSnapshot 이 마켓 커서를 그 마켓 스냅샷 seq 로
        // 세우고 재생이 engine.appliedSeq(marketId) 에서 커서를 연다. 호출자가 넘기면 다른 축 값이 들어갈 수 있다(ADR 0023 결정 2).
        if (snapshotSource != null) {
            Map<Integer, SnapshotSource.MarketLoaded> snaps = snapshotSource.loadLatestAdoptablePerMarket();
            snaps.forEach((marketId, snap) -> engine.loadMarketSnapshot(marketId, snap.seq(), snap.book()));
            if (!snaps.isEmpty()) {
                log.info("스냅샷 채택(마켓별): {}", snaps.entrySet().stream()
                        .map(e -> e.getKey() + "@" + e.getValue().seq()).toList());
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
            // 표시 전용 값이다. 마켓별 분해는 원천에 없다(DB 설계 §3.1 센티널 market_id 0).
            recoveryRecorder.recordSuccess(replayFrom, result.lastAppliedSeq(),
                    result.verifiedEvents(), (System.nanoTime() - replayStartedNs) / 1_000_000L);
        }
        replayObserver.accept(result.replayedCommands());
        List<PendingBatch> pending = result.pendingBatches();
        if (pending.isEmpty()) {
            return result;
        }
        // 재적재는 리더 자격이 필요하다. 승격 실패(유효한 타 리더 존재)는 기동 거부로 처리한다.
        // 그 리더가 같은 창을 이미 메웠다면 애초에 pending이 비고, 메우는 중이라면 여기서의
        // 병행 적재는 레지스트리 충돌·fencing으로 어차피 실패해야 맞다(무음 유실 금지).
        if (!lease.isLeader() && !acquireLeadership(promotionWait)) {
            throw new IllegalStateException(
                    "크래시 창 이벤트 " + result.pendingEvents().size() + "건 재적재 불가: 리더 승격 실패"
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
        // 명령 단위 원자성 때문에 한 배치 안에서는 renew할 수 없다. 배치 경계마다 시간·누적
        // 이벤트 수 둘 다로 판정해 renew 간격을 이중으로 상한한다.
        for (PendingBatch batch : pending) {
            if (System.nanoTime() - lastRenewNanos >= renewIntervalNanos
                    || eventsSinceRenew >= RENEW_EVENT_INTERVAL) {
                lease.renew();
                lastRenewNanos = System.nanoTime();
                eventsSinceRenew = 0;
            }
            try {
                reappendSink.accept(batch.events());
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
     * 마켓별 두 단계 재생(ADR 0022 5-4). 1 단계는 마켓마다 bound(m) 까지 대조만 하고 2 단계는 그 초과분을
     * 재적재 대상으로 낸다.
     *
     * 두 단계로 가르는 이유가 있다. 앞 구간은 이벤트가 전부 있는 안정 구간이라 대조만 하면 되고
     * 리더 자격이 필요 없다. 뒤 구간은 이벤트가 없는 구간이라 재적재가 필요하고 그것은 리더만
     * 할 수 있다. 하나로 묶으면 앞 구간까지 리더 자격에 걸린다.
     *
     * 2 단계 상한 head(m) 은 리더 자격을 얻고 늘어난 경계 구간의 대조까지 마친 뒤 모든 마켓에서 한 번
     * 읽어 고정하고 도는 중에 다시 읽지 않는다. 리더 대기 전에 고정하지 않는 이유는 대기 중에 늘어난
     * 명령이 2 단계 상한 밖에 남지 않게 하려는 것이다.
     */
    private ReplayResult replayToCheckpointThenTail(MatchingEngineImpl engine,
                                                    java.time.Duration promotionWait,
                                                    boolean followerAllowed) {
        JournalCheckpoints bounds = replayService.materializedThroughByMarket(engine);
        ReplayResult stable = verifyMarkets(engine, bounds);
        log.info("재생 1단계(마켓별 물질화 경계 상한): 마켓 {}개, 적용 {}건, 대조 {}건",
                bounds.bounds().size(), stable.replayedCommands(), stable.verifiedEvents());

        List<Integer> beyond = marketsBeyond(engine, replayService.journalHeads());
        if (beyond.isEmpty()) {
            return stable;
        }
        if (!lease.isLeader() && !acquireLeadership(promotionWait)) {
            if (followerAllowed) {
                log.info("리더 자격 없이 기동한다(팔로워). 체크포인트를 넘은 마켓 {} 는 catch-up 이 따라잡는다",
                        beyond);
                return stable;
            }
            throw new IllegalStateException(
                    "크래시 창 마켓 " + beyond + " 재적재 불가: 리더 승격 실패"
                            + (promotionWait.isZero() ? ""
                                    : "(대기 " + promotionWait.toMillis() + "ms 소진)"));
        }

        // 리더 자격을 기다리는 사이 다른 노드가 창을 메웠을 수 있다. 경계를 다시 읽어 검증
        // 가능한 구간을 먼저 소진한다. 대조 없이 재적재로 넘기면 그 구간의 불일치를 놓친다.
        ReplayResult more = verifyMarkets(engine, replayService.materializedThroughByMarket(engine));
        if (more.replayedCommands() > 0) {
            log.info("재생 1단계 연장(대기 중 메워진 구간): 적용 {}건", more.replayedCommands());
        }

        // 2 단계. 이벤트 커서를 열지 않으므로 방출이 전부 pending 이다. 마켓 안 라이터는 seq 순으로
        // 정산하므로 bound(m) 위에 그 마켓의 물질화된 명령이 없어 방출은 크래시 창뿐이다.
        // event_emission_registry 멱등 스킵은 안전망으로 남는다.
        Map<Integer, Long> tailHeads = replayService.journalHeads();
        Map<Integer, List<PendingBatch>> queues = new TreeMap<>();
        long tailCommands = 0;
        for (Map.Entry<Integer, Long> head : tailHeads.entrySet()) {
            int marketId = head.getKey();
            if (head.getValue() <= engine.appliedSeq(marketId)) {
                continue;
            }
            ReplayResult tail = replayService.replayTailMarket(engine, marketId, head.getValue());
            tailCommands += tail.replayedCommands();
            queues.put(marketId, tail.pendingBatches());
        }
        List<PendingBatch> ordered = mergeByCreatedAt(queues);
        log.info("재생 2단계(마켓별 head 고정): 마켓 {}개, 적용 {}건, 재적재 대상 명령 {}건",
                queues.size(), tailCommands, ordered.size());
        return new ReplayResult(engine.lastAppliedSeq(),
                stable.replayedCommands() + more.replayedCommands() + tailCommands,
                stable.verifiedEvents() + more.verifiedEvents(), ordered);
    }

    private ReplayResult verifyMarkets(MatchingEngineImpl engine, JournalCheckpoints bounds) {
        long replayed = 0;
        long verified = 0;
        for (int marketId : bounds.bounds().keySet()) {
            ReplayResult result = replayService.verifyToMarket(engine, marketId, bounds);
            replayed += result.replayedCommands();
            verified += result.verifiedEvents();
        }
        return new ReplayResult(engine.lastAppliedSeq(), replayed, verified, List.of());
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

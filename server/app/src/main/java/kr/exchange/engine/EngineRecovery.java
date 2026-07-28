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
        return recover(engine, budget);
    }

    private ReplayResult recover(MatchingEngineImpl engine, java.time.Duration promotionWait) {
        long eventStart = 0;
        if (snapshotSource != null) {
            Optional<SnapshotSource.Loaded> snap = snapshotSource.loadLatestAdoptable();
            if (snap.isPresent()) {
                long snapSeq = snap.get().seq();
                engine.loadSnapshot(snapSeq, snap.get().books());
                eventStart = snapshotSource.eventWatermark(snapSeq);
                log.info("스냅샷 채택: last_applied_seq={}, event_watermark={}, markets={}",
                        snapSeq, eventStart, snap.get().books().size());
            }
        }
        ReplayResult result = replayService.rebuild(engine, eventStart);
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

    // 승격 획득만 담당하고 획득 후 로직은 무변경이다. 대기는 부팅 스레드의 블로킹일 뿐
    // 엔진 결정론·저널·매칭 순서와 무관하다. 인터럽트는 대기 포기로 처리해 기동 실패로 흐른다.
    private boolean acquireLeadership(java.time.Duration wait) {
        if (lease.tryAcquire()) {
            return true;
        }
        if (wait.isZero() || wait.isNegative()) {
            return false;
        }
        log.warn("리더 승격 실패(유효한 타 리더 존재). 리스 만료 대기 후 재시도: 상한 {}ms",
                wait.toMillis());
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

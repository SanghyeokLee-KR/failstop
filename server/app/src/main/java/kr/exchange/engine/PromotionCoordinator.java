package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.CommandJournalReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 승격 재생 조율(복구 RTO 설계 §3-a). 리더 획득 시 접수 개방 전에 fresh 엔진을 head까지
 * 인메모리 선복원한 뒤 단일 라이터 루프에 핸드오프하고, 그때서야 접수를 연다.
 *
 * 상태기계: FOLLOWER -(gain)-> PROMOTING -(rebuild 완료)-> ACCEPTING. 리스 상실은 DEMOTED.
 * 접수 게이트를 리더십과 분리한다. 리더여도 PROMOTING이면 게이트가 닫혀 있어 catch-up이
 * 대규모 delta를 정산 sink로 태우는 결함 경로가 재발하지 않는다.
 *
 * stale 엔진 재rebuild 금지(§3-a): 승격 재생은 반드시 새 MatchingEngineImpl에 대해 0(또는
 * 스냅샷 seq)부터 수행한다. 설치된 엔진이 이미 head면 재생을 생략하고 게이트만 연다.
 *
 * 승격 중 리스 유지: gain 콜백은 리스 스케줄러 스레드에서 짧게 반환하고 실제 재생은 별도
 * 승격 워커에서 돌린다. renew 틱이 rebuild 동안 계속 돌아 만료·플래핑을 막는다.
 *
 * 재생 실패는 종신 강등이 아니다(§5.4 강등 사유 분류). 재생은 fresh 엔진에 대해 스냅샷 로드 →
 * tail 재생 → 크래시 창 재정산을 처음부터 다시 하므로 몇 번을 반복해도 결과가 같고, 성공 전에는
 * 루프에 설치되지도 게이트가 열리지도 않는다. 따라서 (1) 일시 실패는 같은 리스를 쥔 채 재시도하고
 * (2) 예산을 소진하면 리더십을 회복 가능하게 반납해 다음 만료 구동 승격의 후보로 남는다.
 * 한 번의 실패로 후보에서 빠지면 2노드에서 양쪽이 그렇게 되어 클러스터가 리더 없이 영구 정지한다
 * (E2 라이브 실측). 재생 정확성은 완화하지 않는다. 성공하지 못한 재생으로는 접수를 열지 않는다.
 */
public class PromotionCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PromotionCoordinator.class);

    public enum State { FOLLOWER, PROMOTING, ACCEPTING, DEMOTED }

    // 같은 리스를 쥔 채의 재생 재시도 횟수. DB 슬로 윈도·경합은 수백 ms면 걷히므로 3회면 충분하고,
    // 그 이상은 리스를 붙잡고 있는 시간만 늘려 만료 구동 재승격을 늦춘다.
    private static final int REPLAY_ATTEMPTS = 3;
    private static final long REPLAY_RETRY_BASE_MILLIS = 250;
    // 재시도 총 예산. RTO 목표 30초(API §7-4) 안에 반납 + 만료 대기 + 재승격까지 들어가야 한다.
    private static final long REPLAY_RETRY_BUDGET_MILLIS = 5_000;

    private final EngineRecovery recovery;
    private final SingleWriterLoop loop;
    private final LeaderLeaseService lease;
    private final CommandJournalReader journalReader;
    private final AvailabilitySignal availability;

    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private volatile State state = State.FOLLOWER;
    // 상태 전이 상호배제. 승격 말미의 ACCEPTING 전환과 리스 상실의 DEMOTED 전환이 경합하면
    // DEMOTED→ACCEPTING 역전(zombie ACCEPTING)이 난다. 모든 전이를 이 락으로 직렬화한다.
    private final Object stateLock = new Object();
    // 부팅 승격(recover가 이미 처리)과 런타임 승격을 가른다. arm 이전 gain은 부팅 몫이라 무시한다.
    private volatile boolean armed;
    // 승격 시도 세대. 회복 가능 반납이 도입되면서 [재생 실패로 리스를 놓음 → 리스 만료 →
    // 같은 노드가 재승격]이 정상 경로가 됐고, 앞선 시도가 뒷정리를 하는 동안 새 gain이 들어올 수
    // 있다. 세대가 다르면 뒤늦은 시도의 상태 전이·반납을 전부 무시해, 낡은 시도가 새 승격의
    // 상태나 새로 얻은 리스를 지우지 못하게 한다.
    private final java.util.concurrent.atomic.AtomicLong promotionGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    // 루프에 설치된 엔진. head 도달 판정에 쓴다(부팅 시 shared, 승격 후 fresh).
    private volatile MatchingEngineImpl installedEngine;
    private final ExecutorService promotionWorker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "engine-promotion");
                t.setDaemon(true);
                return t;
            });

    public PromotionCoordinator(EngineRecovery recovery, SingleWriterLoop loop,
                                LeaderLeaseService lease, CommandJournalReader journalReader) {
        this(recovery, loop, lease, journalReader, AvailabilitySignal.NOOP);
    }

    public PromotionCoordinator(EngineRecovery recovery, SingleWriterLoop loop,
                                LeaderLeaseService lease, CommandJournalReader journalReader,
                                AvailabilitySignal availability) {
        this.recovery = recovery;
        this.loop = loop;
        this.lease = lease;
        this.journalReader = journalReader;
        this.availability = availability;
    }

    /** EngineBridge가 소비하는 접수 게이트. */
    public AcceptGate gate() {
        return accepting::get;
    }

    public State state() {
        return state;
    }

    /** 루프에 설치된 엔진. 승격 후 head 도달·체크섬 검증 관측점(상태 변경 진입점 아님). */
    public MatchingEngineImpl installedEngine() {
        return installedEngine;
    }

    /**
     * 부팅 설치. lifecycle이 shared 엔진을 recover로 head까지 올린 직후 호출한다. 부팅 시점에
     * 리더면 접수를 즉시 연다(recover가 이미 head + 크래시 창 재정산을 마쳤다). 아니면 팔로워로
     * 게이트를 닫는다.
     */
    public void bootstrap(MatchingEngineImpl sharedEngine, boolean leaderAtBoot) {
        this.installedEngine = sharedEngine;
        boolean accept;
        synchronized (stateLock) {
            if (leaderAtBoot) {
                state = State.ACCEPTING;
                accepting.set(true);
                accept = true;
            } else {
                state = State.FOLLOWER;
                accepting.set(false);
                accept = false;
            }
        }
        signal(accept);
    }

    /** arm 이후의 gain부터 런타임 승격으로 처리한다. lease.start() 직전에 호출한다. */
    public void arm() {
        this.armed = true;
    }

    /** 리스 획득 콜백(리스 스케줄러 스레드). 재생은 승격 워커로 넘기고 즉시 반환한다. */
    public void onLeadershipGained(LeaderIdentity identity) {
        if (!armed) {
            // 부팅 recover의 획득. shared 엔진은 lifecycle이 별도로 recover한다.
            return;
        }
        long generation;
        synchronized (stateLock) {
            // 이미 접수 중이거나 강등 확정이면 승격을 시작하지 않는다(재승격 금지 계약).
            if (state == State.ACCEPTING || state == State.DEMOTED) {
                return;
            }
            state = State.PROMOTING;
            accepting.set(false);
            generation = promotionGeneration.incrementAndGet();
        }
        // PROMOTING은 재생 완료 전이라 접수를 열지 않는다. readiness도 REFUSING을 유지한다.
        availability.refusing();
        promotionWorker.submit(() -> promote(identity, generation));
    }

    /** 리스 상실 콜백. 접수를 닫는다(loop.halt는 별도 리스너가 처리). */
    public void onLeadershipLost() {
        synchronized (stateLock) {
            accepting.set(false);
            state = State.DEMOTED;
        }
        availability.refusing();
    }

    private void promote(LeaderIdentity identity, long generation) {
        long t0 = System.nanoTime();
        Throwable failure;
        // 재생 구간 표식은 접수 개방 전에 반드시 걷는다. 개방 이후의 상실은 루프를 정지시키므로
        // 종신이어야 한다. finally로 걷은 뒤에만 게이트를 연다.
        lease.beginPromotionReplay();
        try {
            failure = replayWithRetry(identity, t0, generation);
        } finally {
            lease.endPromotionReplay();
        }
        if (failure != null) {
            standDown(identity, generation, failure);
            return;
        }
        boolean opened;
        synchronized (stateLock) {
            // 재생 도중 리스를 잃었으면(onLeadershipLost가 DEMOTED로 전환, 또는 리스 만료)
            // ACCEPTING으로 되돌리지 않는다. zombie ACCEPTING 방지(감사 Low 경합 항목).
            if (promotionGeneration.get() != generation
                    || state != State.PROMOTING || !lease.isLeader()) {
                log.warn("승격 완료 시점에 리더 아님. ACCEPTING 전환 취소: epoch={}, state={}",
                        identity.epoch(), state);
                return;
            }
            state = State.ACCEPTING;
            accepting.set(true);
            opened = true;
        }
        if (opened) {
            availability.accepting();
        }
    }

    /** null이면 재생 성공. 아니면 마지막 실패 원인이다. 성공 전에는 엔진을 설치하지 않는다. */
    private Throwable replayWithRetry(LeaderIdentity identity, long t0, long generation) {
        Throwable last = null;
        for (int attempt = 1; attempt <= REPLAY_ATTEMPTS; attempt++) {
            try {
                replayOnce(identity, t0);
                return null;
            } catch (Throwable t) {
                last = t;
                long elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
                boolean retryable = attempt < REPLAY_ATTEMPTS
                        && elapsedMillis < REPLAY_RETRY_BUDGET_MILLIS
                        // 리더십을 이미 놓았으면 재시도는 무의미하고 위험하다. 다음 승격이
                        // 새 epoch로 처음부터 다시 한다.
                        && lease.isLeader()
                        && promotionGeneration.get() == generation
                        && state == State.PROMOTING;
                if (!retryable) {
                    return last;
                }
                log.warn("승격 재생 실패, 재시도한다: epoch={}, 시도={}/{}, 경과={}ms",
                        identity.epoch(), attempt, REPLAY_ATTEMPTS, elapsedMillis, t);
                try {
                    Thread.sleep(REPLAY_RETRY_BASE_MILLIS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
        }
        return last;
    }

    private void replayOnce(LeaderIdentity identity, long t0) {
        long head = journalReader.maxSeq();
        MatchingEngineImpl current = installedEngine;
        if (current != null && current.lastAppliedSeq() >= head) {
            log.info("승격 재생 생략(엔진 이미 head): epoch={}, lastAppliedSeq={}, head={}",
                    identity.epoch(), current.lastAppliedSeq(), head);
            return;
        }
        // fresh 엔진에 0(또는 스냅샷 seq)부터 인메모리 선복원 + 크래시 창 재정산. 재시도마다
        // 새로 만든다. 중단된 엔진을 이어 쓰면 어디까지 정산됐는지가 불확정이다. 재적재는
        // event_emission_registry 판정으로 멱등이라 처음부터 다시 해도 중복이 생기지 않는다.
        // 대규모 catch-up은 ReplayService가 인메모리로만 처리하고 정산 sink를 타지 않는다.
        MatchingEngineImpl fresh = new MatchingEngineImpl();
        recovery.recover(fresh);
        loop.installEngine(fresh);
        installedEngine = fresh;
        log.info("승격 재생 완료: epoch={}, lastAppliedSeq={}, head={}, 소요={}ms",
                identity.epoch(), fresh.lastAppliedSeq(), head,
                (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * 회복 가능 반납. 재생이 실패했으므로 이 노드는 리더가 되지 않는다(무음 유실 금지, §3-a).
     * 다만 상태는 DEMOTED가 아니라 FOLLOWER다. 접수를 연 적이 없고 루프도 승격 전 상태 그대로라
     * 다음 만료 구동 승격의 후보로 남는 것이 안전하다. 모든 후보가 DEMOTED로 수렴하면 클러스터가
     * 리더 없이 정지한다.
     */
    private void standDown(LeaderIdentity identity, long generation, Throwable cause) {
        log.error("승격 재생 실패, 리더십을 반납한다(재승격 허용): epoch={}", identity.epoch(), cause);
        synchronized (stateLock) {
            if (promotionGeneration.get() != generation) {
                // 더 새로운 승격이 이미 시작됐다. 낡은 시도가 그 상태와 리스를 지우면 안 된다.
                return;
            }
            accepting.set(false);
            if (state == State.PROMOTING) {
                state = State.FOLLOWER;
            }
        }
        availability.refusing();
        lease.standDown("승격 재생 실패: " + cause.getClass().getSimpleName());
    }

    private void signal(boolean accepting) {
        if (accepting) {
            availability.accepting();
        } else {
            availability.refusing();
        }
    }

    @Override
    public void close() {
        promotionWorker.shutdownNow();
    }
}

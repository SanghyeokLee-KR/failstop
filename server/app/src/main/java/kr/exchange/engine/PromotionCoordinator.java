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
 */
public class PromotionCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PromotionCoordinator.class);

    public enum State { FOLLOWER, PROMOTING, ACCEPTING, DEMOTED }

    private final EngineRecovery recovery;
    private final SingleWriterLoop loop;
    private final LeaderLeaseService lease;
    private final CommandJournalReader journalReader;

    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private volatile State state = State.FOLLOWER;
    // 부팅 승격(recover가 이미 처리)과 런타임 승격을 가른다. arm 이전 gain은 부팅 몫이라 무시한다.
    private volatile boolean armed;
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
        this.recovery = recovery;
        this.loop = loop;
        this.lease = lease;
        this.journalReader = journalReader;
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
        if (leaderAtBoot) {
            state = State.ACCEPTING;
            accepting.set(true);
        } else {
            state = State.FOLLOWER;
            accepting.set(false);
        }
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
        if (state == State.ACCEPTING) {
            return;
        }
        state = State.PROMOTING;
        accepting.set(false);
        promotionWorker.submit(() -> promote(identity));
    }

    /** 리스 상실 콜백. 접수를 닫는다(loop.halt는 별도 리스너가 처리). */
    public void onLeadershipLost() {
        accepting.set(false);
        state = State.DEMOTED;
    }

    private void promote(LeaderIdentity identity) {
        long t0 = System.nanoTime();
        try {
            long head = journalReader.maxSeq();
            MatchingEngineImpl current = installedEngine;
            if (current == null || current.lastAppliedSeq() < head) {
                // fresh 엔진에 0(또는 스냅샷 seq)부터 인메모리 선복원 + 크래시 창 재정산.
                // 대규모 catch-up은 ReplayService가 인메모리로만 처리하고 정산 sink를 타지 않는다.
                MatchingEngineImpl fresh = new MatchingEngineImpl();
                recovery.recover(fresh);
                loop.installEngine(fresh);
                installedEngine = fresh;
                log.info("승격 재생 완료: epoch={}, lastAppliedSeq={}, head={}, 소요={}ms",
                        identity.epoch(), fresh.lastAppliedSeq(), head,
                        (System.nanoTime() - t0) / 1_000_000);
            } else {
                log.info("승격 재생 생략(엔진 이미 head): epoch={}, lastAppliedSeq={}, head={}",
                        identity.epoch(), current.lastAppliedSeq(), head);
            }
            state = State.ACCEPTING;
            accepting.set(true);
        } catch (Throwable t) {
            // rebuild·재정산 실패는 승격 거부·self-shutdown이다(무음 유실 금지, §3-a).
            log.error("승격 재생 실패, 강등한다: epoch={}", identity.epoch(), t);
            accepting.set(false);
            state = State.DEMOTED;
            lease.demote("승격 재생 실패: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        promotionWorker.shutdownNow();
    }
}

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
 *
 * 회복 가능 등급의 전제는 '같은 재생을 다시 하면 같은 결과'다. VM Error(OOM·StackOverflow)는 그
 * 전제 밖이라 재시도 대상에서 빼고 종신으로 올린다. 재시도는 전량 재생을 3회 반복해 상태를 더
 * 악화시키고, 회복 가능 반납은 TTL마다 그 반복을 재개시킨다.
 */
public class PromotionCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PromotionCoordinator.class);

    public enum State { FOLLOWER, PROMOTING, ACCEPTING, DEMOTED }

    // 같은 리스를 쥔 채의 재생 재시도 횟수. DB 슬로 윈도·경합은 수백 ms면 걷히므로 3회면 충분하고,
    // 그 이상은 리스를 붙잡고 있는 시간만 늘려 만료 구동 재승격을 늦춘다.
    // 패키지 가시성인 이유: 회귀 테스트가 '시도 1회가 예산을 넘긴다'를 전제로 구성되므로 값을
    // 복제하면 상수만 바뀌었을 때 테스트가 조용히 무의미해진다.
    static final int REPLAY_ATTEMPTS = 3;
    private static final long REPLAY_RETRY_BASE_MILLIS = 250;
    /**
     * 재시도 예산. **첫 실패 시각부터** 재며 재생 자체의 소요는 포함하지 않는다. 승격 진입 시각부터
     * 재면 크래시 창이 커서 시도 1회가 예산을 넘기는 경우에 첫 실패에서 곧바로 예산이 소진돼
     * 재시도가 한 번도 돌지 않는다. 곧 재승격 비용(TTL 대기 + 전량 재생)이 가장 큰 상황에서
     * 정확히 재시도가 사라진다.
     *
     * 리스 보유 상한(TTL 대비). 재생 구간에는 renew 틱이 계속 돌아 리스가 유지되므로, 재시도는
     * 스탠바이 승격을 그만큼 늦춘다. 첫 실패 이후 추가 보유는 [예산 5s + 마지막 백오프 0.5s +
     * 시도 1회]로 상한된다(예산은 다음 시도의 시작만 막고 진행 중인 시도를 끊지 않는다). 재생
     * 1회가 긴 경우에는 예산이 첫 실패 직후 소진돼 재시도가 정확히 1회로 줄어든다.
     *
     * 이 상한은 재시도가 더하는 지연에만 해당한다. 페일오버 총 벽시계는 [TTL + 첫 시도 + 백오프
     * + 마지막 시도 + TTL + 다음 리더 재생]이라 이 값보다 크고, SLO로 재는
     * exchange_failover_downtime_seconds는 승격 시각 - 구 리더 최종 심박이라 재생 구간을
     * 아예 포함하지 않아 이 값보다 작다. 서로 다른 양이므로 섞어 비교하지 않는다.
     */
    static final long REPLAY_RETRY_BUDGET_MILLIS = 5_000;

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
                // 개방 조건은 런타임 승격 경로와 같아야 한다(§5.4). 현 배선은 loop.start()를
                // 먼저 부르므로 항상 참이지만, 그 순서에 기대는 순간 불변이 배선에만 있게 된다.
                // 정지한 루프 위에서 리스를 쥔 채 열면 좀비 리더가 되므로 기동을 세운다.
                // 기동 실패는 리스 만료를 거쳐 스탠바이 승격으로 수렴한다.
                if (!loop.isRunning()) {
                    throw new IllegalStateException(
                            "단일 라이터 정지 상태에서 부팅 접수 개방 금지");
                }
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
        // 재생 구간 표식은 접수 개방 전에 반드시 걷는다. 개방 이후의 상실은 루프를 정지시키므로
        // 종신이어야 한다. 다만 해제 시점은 결과별로 다르고, 일괄 finally로 걷으면 회복 가능
        // 반납이 표식 없이 실행된다. 그 사이(해제~lease.standDown)에 renew 틱이 거부를 받으면
        // markLost가 종신으로 내려가 노드가 후보에서 빠진다. 재생 구간에는 renew가 계속 도는
        // 것이 설계이므로 이 창은 이론적이지 않다. 토큰을 되돌려주는 이유는 낡은 시도의 뒤늦은
        // 해제가 새 시도의 표식을 지우지 못하게 하기 위해서다.
        long replayToken = lease.beginPromotionReplay();
        try {
            promoteWithReplayMarker(identity, generation, t0, replayToken);
        } finally {
            // 안전망. 아래 세 경로가 모두 제자리에서 해제하므로 정상 흐름에서는 CAS가 실패해
            // 무동작이다. 예기치 못한 이탈로 표식이 남으면 이후의 모든 종신 상실이 회복 가능으로
            // 오등급되어, 루프가 죽은 노드가 재승격 후보로 남는다. 발동을 무음으로 두지 않는
            // 이유: 안전망이 일했다는 것은 등급 판정이 이미 한 번 어긋날 뻔했다는 뜻이다.
            if (lease.endPromotionReplay(replayToken)) {
                log.error("승격 경로가 재생 표식을 걷지 않고 이탈했다(안전망 해제): epoch={}",
                        identity.epoch());
            }
        }
    }

    private void promoteWithReplayMarker(LeaderIdentity identity, long generation, long t0,
                                         long replayToken) {
        Throwable failure = replayWithRetry(identity, t0, generation);
        if (failure != null) {
            if (failure instanceof VirtualMachineError) {
                // VM Error는 회복 가능 등급의 전제(다시 하면 같은 결과)가 성립하지 않는다.
                // 프로세스가 불건전하다는 신호이고, 회복 가능으로 내리면 TTL마다 재승격해
                // 전량 재생을 되풀이한다. 재기동으로만 복귀시킨다.
                //
                // 표식을 먼저 걷는다. markLost는 재생 구간이면 회복 가능으로 내리므로, 표식을
                // 쥔 채 demote를 부르면 등급이 그대로 뒤집힌다.
                lease.endPromotionReplay(replayToken);
                demoteTerminally(identity, generation, failure, "승격 재생 VM Error");
            } else {
                // 반납이 끝난 뒤에 걷는다. 반납 자체가 표식이 살아 있어야 회복 가능으로 성립한다.
                standDown(identity, generation, failure);
                lease.endPromotionReplay(replayToken);
            }
            return;
        }
        // 성공 경로는 게이트 개방 전에 걷는다. 개방 이후의 상실은 루프를 정지시키므로 종신이다.
        lease.endPromotionReplay(replayToken);
        boolean loopDown = false;
        boolean opened = false;
        synchronized (stateLock) {
            // 재생 도중 리스를 잃었으면(onLeadershipLost가 DEMOTED로 전환, 또는 리스 만료)
            // ACCEPTING으로 되돌리지 않는다. zombie ACCEPTING 방지(감사 Low 경합 항목).
            if (promotionGeneration.get() != generation
                    || state != State.PROMOTING || !lease.isLeader()) {
                log.warn("승격 완료 시점에 리더 아님. ACCEPTING 전환 취소: epoch={}, state={}",
                        identity.epoch(), state);
                return;
            }
            if (!loop.isRunning()) {
                // 게이트는 리더십·상태만이 아니라 소비자 생존까지 봐야 한다. 정지한 루프 위에서
                // 열면 저널에 커밋되고 영원히 체결되지 않는 주문이 생긴다. 지금은 재생 구간에
                // 루프가 유휴라 도달하지 않지만, 그 불변이 코드에 없으면 배선이 바뀌는 순간
                // 무음으로 깨진다.
                accepting.set(false);
                state = State.DEMOTED;
                loopDown = true;
            } else {
                state = State.ACCEPTING;
                accepting.set(true);
                opened = true;
            }
        }
        if (loopDown) {
            // 루프 정지는 강등 등급 분류의 종신 축 그 자체다. 회복 가능으로 내리면 죽은 루프
            // 위에서 승격과 반납을 무한 반복한다.
            log.error("승격 재생은 끝났으나 단일 라이터가 정지했다. 종신 강등한다: epoch={}",
                    identity.epoch());
            availability.refusing();
            lease.demote("승격 완료 시점 단일 라이터 정지");
            return;
        }
        if (opened) {
            availability.accepting();
        }
    }

    /** null이면 재생 성공. 아니면 마지막 실패 원인이다. 성공 전에는 엔진을 설치하지 않는다. */
    private Throwable replayWithRetry(LeaderIdentity identity, long t0, long generation) {
        Throwable last = null;
        long firstFailureNanos = 0;
        for (int attempt = 1; attempt <= REPLAY_ATTEMPTS; attempt++) {
            try {
                replayOnce(identity, t0);
                return null;
            } catch (Throwable t) {
                last = t;
                if (attempt == 1) {
                    firstFailureNanos = System.nanoTime();
                }
                long now = System.nanoTime();
                long retryingMillis = (now - firstFailureNanos) / 1_000_000;
                // 재시도 제외는 VM Error(OOM·StackOverflow)에 한정한다(§5.4 v2.24 #4). Error
                // 전체를 술어로 두면 NoClassDefFoundError·AssertionError처럼 '다시 하면 같은
                // 결과'가 성립하는 오류까지 종신으로 내려가 후보가 소진된다.
                boolean retryable = !(t instanceof VirtualMachineError)
                        && attempt < REPLAY_ATTEMPTS
                        && retryingMillis < REPLAY_RETRY_BUDGET_MILLIS
                        // 리더십을 이미 놓았으면 재시도는 무의미하고 위험하다. 다음 승격이
                        // 새 epoch로 처음부터 다시 한다.
                        && lease.isLeader()
                        && promotionGeneration.get() == generation
                        && state == State.PROMOTING;
                if (!retryable) {
                    return last;
                }
                log.warn("승격 재생 실패, 재시도한다: epoch={}, 시도={}/{}, 경과={}ms, 재시도경과={}ms",
                        identity.epoch(), attempt, REPLAY_ATTEMPTS,
                        (now - t0) / 1_000_000, retryingMillis, t);
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

    /**
     * 종신 강등. 회복 가능 등급의 전제(같은 재생을 다시 하면 같은 결과)가 성립하지 않는 사유에만
     * 쓴다. lease.demote가 상실 리스너를 타 단일 라이터를 halt시키므로 분류 축(그 강등이 루프를
     * 정지시켰는가)과 일치한다. 복귀는 프로세스 재기동뿐이다.
     */
    private void demoteTerminally(LeaderIdentity identity, long generation, Throwable cause,
                                  String reason) {
        log.error("{}, 종신 강등한다(복귀는 재기동): epoch={}", reason, identity.epoch(), cause);
        synchronized (stateLock) {
            if (promotionGeneration.get() != generation) {
                // 더 새로운 승격이 이미 시작됐다. 낡은 시도가 그 상태와 리스를 지우면 안 된다.
                return;
            }
            accepting.set(false);
            state = State.DEMOTED;
        }
        availability.refusing();
        lease.demote(reason + ": " + cause.getClass().getSimpleName());
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

package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.CommandJournalReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;

/**
 * 승격 재생 조율(복구 RTO 설계 §3-a, DB 설계 §5.4 승격 재생 재시도, 파티션 단위는 §5.4-c 6·7). 리더 획득 시 접수 개방
 * 전에 fresh 엔진을 head까지 선복원한 뒤 루프에 핸드오프하고 그때서야 접수를 연다. 파티션마다 하나이고 그 파티션의 마켓
 * M(P)만 다룬다. 상태는 FOLLOWER -> PROMOTING -> ACCEPTING 이고, 강등 뒤에는 REBUILDING 을 거쳐 FOLLOWER 나 BLOCKED 로
 * 돌아온다(PartitionRuntime). DEMOTED 는 노드 치명 전용이다.
 *
 * 접수 게이트를 리더십과 분리해야 catch-up의 대규모 delta가 정산 sink로 새지 않는다. gain 콜백은
 * 리스 스레드에서 짧게 반환하고 재생은 승격 워커에서 돌아야 renew 틱이 rebuild 중에도 이어진다.
 * 재생 실패의 강등 등급과 VM Error 예외는 §5.4가 정한다. 성공 못 한 재생으로 접수를 열지 않는다.
 *
 * 게이트는 열린 기간을 값으로 든다(닫히면 비어 있다). 접수는 그 기간의 epoch 로 append 하므로 게이트 검사와 append 사이에
 * 기간이 바뀌면 DB 가 거부한다.
 */
public class PromotionCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PromotionCoordinator.class);

    public enum State { FOLLOWER, PROMOTING, ACCEPTING, REBUILDING, BLOCKED, DEMOTED }

    // 같은 리스를 쥔 채의 재생 재시도 횟수. DB 슬로 윈도·경합은 수백 ms면 걷히므로 3회면 충분하고,
    // 그 이상은 리스를 붙잡고 있는 시간만 늘려 만료 구동 재승격을 늦춘다.
    // 패키지 가시성인 이유: 회귀 테스트가 '시도 1회가 예산을 넘긴다'를 전제로 구성되므로 값을
    // 복제하면 상수만 바뀌었을 때 테스트가 조용히 무의미해진다.
    static final int REPLAY_ATTEMPTS = 3;
    private static final long REPLAY_RETRY_BASE_MILLIS = 250;
    /**
     * 재시도 예산. 첫 실패 시각부터 재며 재생 소요는 포함하지 않는다. 승격 진입 시각부터 재면
     * 시도 1회가 예산을 넘기는 경우에 재시도가 한 번도 돌지 않아, 재승격 비용이 가장 큰 상황에서
     * 정확히 재시도가 사라진다.
     *
     * 예산은 다음 시도의 시작만 막고 진행 중인 시도를 끊지 않는다. 여기서 재는 양은 재시도가
     * 더하는 지연뿐이라 페일오버 총 벽시계나 exchange_failover_downtime_seconds와 섞어 비교하지
     * 않는다.
     */
    static final long REPLAY_RETRY_BUDGET_MILLIS = 5_000;
    /** 팔로워 인계의 파티션 전체 잠금 대기 상한(DB 설계 §5.4-c 7). 넘으면 fresh 재생한다. */
    static final Duration HANDOVER_LIMIT = Duration.ofSeconds(2);

    private final String resource;
    private final IntPredicate scope;
    private final EngineRecovery recovery;
    private final WriterHost host;
    private final LeaderLeaseService lease;
    private final CommandJournalReader journalReader;
    private final AvailabilitySignal availability;
    // 파티션 배선만. 인계와 되돌리기를 한다.
    private final FollowerCatchUp follower;
    // 파티션 배선만. 강등·재구성·차단기를 맡는다.
    private volatile PartitionRuntime runtime;

    // 열린 기간. null 이면 게이트가 닫혀 있다.
    private final AtomicReference<LeaderTerm> openTerm = new AtomicReference<>();
    // 승격 중이거나 접수 중인 기간. 강등이 그 기간인지 가른다.
    private volatile LeaderTerm heldTerm;
    private volatile State state = State.FOLLOWER;
    // 상태 전이 상호배제. 승격 말미의 ACCEPTING 전환과 리스 상실의 강등 전환이 경합하면
    // 강등→ACCEPTING 역전(zombie ACCEPTING)이 난다. 모든 전이를 이 락으로 직렬화한다.
    private final Object stateLock = new Object();
    // 종전 배선의 부팅 승격(recover가 이미 처리)과 런타임 승격을 가른다. arm 이전 gain은 부팅 몫이라 무시한다.
    // 파티션 배선은 기동이 늘 팔로워라 처음부터 참이다.
    private volatile boolean armed;
    // 승격 시도 세대. [재생 실패로 리스를 놓음 → 리스 만료 → 같은 노드가 재승격]이 정상 경로라 앞선 시도가 뒷정리를 하는
    // 동안 새 gain이 들어올 수 있다. 세대가 다르면 뒤늦은 시도의 상태 전이·반납을 전부 무시해, 낡은 시도가 새 승격의
    // 상태나 새로 얻은 리스를 지우지 못하게 한다.
    private final AtomicLong promotionGeneration = new AtomicLong();
    // 루프에 설치된 엔진. head 도달 판정(종전 배선)과 관측에 쓴다.
    private volatile MatchingEngineImpl installedEngine;
    // 인계 잠금 대기 상한. 운영은 HANDOVER_LIMIT 그대로이고, 경합 시험(FollowerHandoverRaceTest)만 줄여 상한 초과 회차를 짧게 돈다.
    private volatile Duration handoverLimit = HANDOVER_LIMIT;
    private final ExecutorService promotionWorker;
    private final boolean ownsWorker;

    public PromotionCoordinator(EngineRecovery recovery, SingleWriterLoop loop,
                                LeaderLeaseService lease, CommandJournalReader journalReader) {
        this(recovery, loop, lease, journalReader, AvailabilitySignal.NOOP);
    }

    public PromotionCoordinator(EngineRecovery recovery, SingleWriterLoop loop,
                                LeaderLeaseService lease, CommandJournalReader journalReader,
                                AvailabilitySignal availability) {
        this(recovery, WriterHost.of(loop), lease, journalReader, availability);
    }

    /** 마켓별 라이터 배선(ADR 0021 5단계). 모든 마켓을 한 파티션으로 다룬다. */
    public PromotionCoordinator(EngineRecovery recovery, WriterRouter router,
                                LeaderLeaseService lease, CommandJournalReader journalReader,
                                AvailabilitySignal availability) {
        this(recovery, WriterHost.of(router), lease, journalReader, availability);
    }

    private PromotionCoordinator(EngineRecovery recovery, WriterHost host,
                                 LeaderLeaseService lease, CommandJournalReader journalReader,
                                 AvailabilitySignal availability) {
        this(lease == null ? kr.exchange.engine.lease.LeaseResources.MATCHING_ENGINE : lease.resource(),
                marketId -> true, recovery, host, lease, journalReader, availability, null, newWorker(), true);
    }

    /**
     * 파티션 배선(DB 설계 §5.4-c). 승격 워커는 노드의 모든 파티션이 같이 쓴다. 두 파티션을 한꺼번에 얻은 노드가 재생 두 벌을
     * 동시에 돌려 요청 풀을 채우지 않게 파티션 사이의 승격을 줄 세운다. 기동은 늘 팔로워라 부팅 설치(bootstrap)가 없다.
     */
    public PromotionCoordinator(String resource, IntPredicate scope, EngineRecovery recovery, WriterRouter router,
                                LeaderLeaseService lease, CommandJournalReader journalReader,
                                AvailabilitySignal availability, FollowerCatchUp follower,
                                ExecutorService promotionWorker) {
        this(resource, scope, recovery, WriterHost.of(router), lease, journalReader, availability, follower,
                promotionWorker, false);
        this.armed = true;
    }

    private PromotionCoordinator(String resource, IntPredicate scope, EngineRecovery recovery, WriterHost host,
                                 LeaderLeaseService lease, CommandJournalReader journalReader,
                                 AvailabilitySignal availability, FollowerCatchUp follower,
                                 ExecutorService promotionWorker, boolean ownsWorker) {
        this.resource = resource;
        this.scope = scope;
        this.recovery = recovery;
        this.host = host;
        this.lease = lease;
        this.journalReader = journalReader;
        this.availability = availability;
        this.follower = follower;
        this.promotionWorker = promotionWorker;
        this.ownsWorker = ownsWorker;
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine-promotion");
            t.setDaemon(true);
            return t;
        });
    }

    void attach(PartitionRuntime runtime) {
        this.runtime = runtime;
    }

    /** 인계 잠금 대기 상한을 바꾼다. 시험 전용이다. */
    void setHandoverLimit(Duration limit) {
        this.handoverLimit = limit;
    }

    public String resource() {
        return resource;
    }

    /** EngineBridge가 소비하는 접수 게이트. */
    public AcceptGate gate() {
        return () -> openTerm.get() != null;
    }

    /** 열린 기간. 닫혀 있으면 null 이다. */
    public LeaderTerm openTerm() {
        return openTerm.get();
    }

    public State state() {
        return state;
    }

    /** 루프에 설치된 엔진. 승격 후 head 도달·체크섬 검증 관측점(상태 변경 진입점 아님). */
    public MatchingEngineImpl installedEngine() {
        return installedEngine;
    }

    /**
     * 부팅 설치(종전 배선). lifecycle이 shared 엔진을 recover로 head까지 올린 직후 호출한다. 부팅 시점에
     * 리더면 접수를 즉시 연다(recover가 이미 head + 크래시 창 재정산을 마쳤다). 아니면 팔로워로
     * 게이트를 닫는다.
     */
    public void bootstrap(MatchingEngineImpl sharedEngine, boolean leaderAtBoot) {
        this.installedEngine = sharedEngine;
        boolean accept;
        synchronized (stateLock) {
            if (leaderAtBoot) {
                // 개방 조건은 런타임 승격 경로와 같아야 한다(§5.4). 정지한 루프 위에서 리스를 쥔 채 열면 좀비 리더가 되므로
                // 기동을 세운다. 기동 실패는 리스 만료를 거쳐 스탠바이 승격으로 수렴한다.
                if (!host.isRunning()) {
                    throw new IllegalStateException(
                            "단일 라이터 정지 상태에서 부팅 접수 개방 금지");
                }
                LeaderTerm term = lease.currentTerm().orElseThrow(
                        () -> new IllegalStateException("부팅 리더인데 기간이 없다"));
                heldTerm = term;
                state = State.ACCEPTING;
                openTerm.set(term);
                accept = true;
            } else {
                state = State.FOLLOWER;
                openTerm.set(null);
                accept = false;
            }
        }
        signal(accept);
    }

    /** arm 이후의 gain부터 런타임 승격으로 처리한다(종전 배선). lease.start() 직전에 호출한다. */
    public void arm() {
        this.armed = true;
    }

    /** 리스 획득 콜백(종전 배선, 리스 스케줄러 스레드). 리스 서비스가 방금 세운 기간으로 승격한다. */
    public void onLeadershipGained(LeaderIdentity identity) {
        lease.currentTerm()
                .filter(term -> term.epoch() == identity.epoch())
                .ifPresent(this::onTermGained);
    }

    /**
     * 리스 획득 콜백(리스 스케줄러 스레드). 기간을 받아 PROMOTING 으로 두고 재생은 승격 워커로 넘긴 뒤 곧바로 반환한다.
     * 리스 틱 스레드를 막지 않는다.
     */
    public void onTermGained(LeaderTerm term) {
        if (!armed) {
            // 부팅 recover의 획득. shared 엔진은 lifecycle이 별도로 recover한다.
            return;
        }
        long generation;
        synchronized (stateLock) {
            if ((state != State.FOLLOWER && state != State.PROMOTING) || !lease.isArmed()) {
                // 재구성·차단·참여 끔과 엇갈려 얻은 기간이다. 쥔 채 두면 갱신만 하고 승격도 접수도 하지 않는 반쪽 리더가 되므로
                // 곧바로 놓는다. 상태 확인과 같은 락 안이라 재구성을 마친 rearm 이 이 반납보다 앞서지 않는다.
                lease.relinquish(term, "후보가 아닌 상태의 획득: " + state);
                return;
            }
            state = State.PROMOTING;
            openTerm.set(null);
            heldTerm = term;
            generation = promotionGeneration.incrementAndGet();
        }
        // PROMOTING은 재생 완료 전이라 접수를 열지 않는다. readiness도 REFUSING을 유지한다.
        availability.refusing();
        promotionWorker.submit(() -> promote(term, generation));
    }

    /** 리스 상실 콜백(종전 배선). 접수를 닫는다(loop.halt는 별도 리스너가 처리). */
    public void onLeadershipLost() {
        synchronized (stateLock) {
            openTerm.set(null);
            heldTerm = null;
            state = State.DEMOTED;
        }
        availability.refusing();
    }

    /**
     * 이 기간의 강등을 시작한다(PartitionRuntime). 이 기간으로 승격 중이거나 접수 중이면 게이트를 닫고 REBUILDING 으로 두고
     * 참이다. 아니면 늦게 온 옛 기간의 실패라 아무것도 하지 않고 거짓이다.
     */
    boolean beginDemotion(LeaderTerm term) {
        synchronized (stateLock) {
            if (heldTerm != term || (state != State.PROMOTING && state != State.ACCEPTING)) {
                return false;
            }
            openTerm.set(null);
            heldTerm = null;
            state = State.REBUILDING;
            // 강등이 재생 구간과 겹치면 리스는 회복 가능 반납으로 기간만 놓고 후보 표식을 둔다. 재구성 중인 파티션은 후보가
            // 아니므로 여기서 내린다.
            lease.disarm();
        }
        availability.refusing();
        return true;
    }

    /** 팔로워 재구성을 시작한다(PartitionRuntime). FOLLOWER·BLOCKED 에서만 REBUILDING 으로 두고 같은 락 안에서 후보 표식을 내린다. */
    boolean beginRebuild() {
        synchronized (stateLock) {
            if (state != State.FOLLOWER && state != State.BLOCKED) {
                return false;
            }
            state = State.REBUILDING;
            lease.disarm();
            return true;
        }
    }

    /**
     * FOLLOWER 인 파티션을 다시 후보로 세운다(PartitionRuntime). 상태 확인과 후보 표식을 한 락 안에서 해 재구성으로 가는 전이와
     * 엇갈리지 않게 한다.
     *
     * @return 이 호출 뒤 후보인가
     */
    boolean rearmIfFollower() {
        synchronized (stateLock) {
            return state == State.FOLLOWER && lease.rearm();
        }
    }

    /** 노드 치명. 게이트를 닫고 DEMOTED 로 둔다. 재기동 전용이다. */
    void markNodeFatal() {
        synchronized (stateLock) {
            openTerm.set(null);
            heldTerm = null;
            state = State.DEMOTED;
        }
        availability.refusing();
    }

    /** 재구성을 마친 상태 전이(PartitionRuntime). DEMOTED 는 풀지 않는다. */
    boolean settle(State next) {
        synchronized (stateLock) {
            if (state == State.DEMOTED) {
                return false;
            }
            state = next;
            return true;
        }
    }

    private void promote(LeaderTerm term, long generation) {
        synchronized (stateLock) {
            if (promotionGeneration.get() != generation || state != State.PROMOTING || heldTerm != term) {
                // 워커 줄에서 기다리는 사이 더 새 승격이 시작됐거나 이 기간이 강등됐다. 인계·재생·설치를 하지 않는다. 늦게
                // 인계하면 재구성을 마친 팔로워를 되돌릴 쪽이 없어 그 파티션의 따라잡기가 멈춘다.
                log.info("낡은 승격을 건너뛴다: resource={}, epoch={}, state={}", resource, term.epoch(), state);
                return;
            }
        }
        long t0 = System.nanoTime();
        // 재생 구간 표식은 접수 개방 전에 반드시 걷는다. 개방 이후의 상실은 루프를 정지시키므로
        // 강등이어야 한다. 다만 해제 시점은 결과별로 다르고, 일괄 finally로 걷으면 회복 가능
        // 반납이 표식 없이 실행된다. 그 사이(해제~lease.standDown)에 renew 틱이 거부를 받으면
        // markLost가 강등으로 내려가 노드가 후보에서 빠진다. 재생 구간에는 renew가 계속 도는
        // 것이 설계이므로 이 창은 이론적이지 않다. 토큰을 되돌려주는 이유는 낡은 시도의 뒤늦은
        // 해제가 새 시도의 표식을 지우지 못하게 하기 위해서다.
        long replayToken = lease.beginPromotionReplay();
        try {
            promoteWithReplayMarker(term, generation, t0, replayToken);
        } finally {
            // 안전망. 아래 경로가 모두 제자리에서 해제하므로 정상 흐름에서는 CAS가 실패해
            // 무동작이다. 예기치 못한 이탈로 표식이 남으면 이후의 모든 강등이 회복 가능으로
            // 오등급되어, 루프가 죽은 노드가 재승격 후보로 남는다. 발동을 무음으로 두지 않는
            // 이유: 안전망이 일했다는 것은 등급 판정이 이미 한 번 어긋날 뻔했다는 뜻이다.
            if (lease.endPromotionReplay(replayToken)) {
                log.error("승격 경로가 재생 표식을 걷지 않고 이탈했다(안전망 해제): resource={}, epoch={}",
                        resource, term.epoch());
            }
        }
    }

    private void promoteWithReplayMarker(LeaderTerm term, long generation, long t0, long replayToken) {
        FollowerCatchUp.Handover handover = follower == null ? null : follower.handOver(resource, handoverLimit);
        Throwable failure = replayWithRetry(term, generation, handover, t0);
        if (failure != null) {
            if (failure instanceof VirtualMachineError) {
                // VM Error는 회복 가능 등급의 전제(다시 하면 같은 결과)가 성립하지 않는다.
                // 프로세스가 불건전하다는 신호이고, 회복 가능으로 내리면 TTL마다 재승격해
                // 전량 재생을 되풀이한다. 재기동으로만 복귀시킨다.
                //
                // 표식을 먼저 걷는다. 상실은 재생 구간이면 회복 가능으로 내리므로, 표식을
                // 쥔 채 강등하면 등급이 그대로 뒤집힌다.
                lease.endPromotionReplay(replayToken);
                nodeFatal(term, generation, failure);
            } else {
                // 반납이 끝난 뒤에 걷는다. 반납 자체가 표식이 살아 있어야 회복 가능으로 성립한다.
                standDown(term, generation, failure);
                lease.endPromotionReplay(replayToken);
            }
            return;
        }
        // 성공 경로는 게이트 개방 전에 걷는다. 개방 이후의 상실은 루프를 정지시키므로 강등이다.
        lease.endPromotionReplay(replayToken);
        boolean loopDown = false;
        boolean opened = false;
        // 이 승격이 설치했을 수 있는 루프를 되돌린다. 더 새로운 승격이 이미 시작됐으면 그 설치가 이것을 덮으므로 건드리지 않는다.
        boolean cancelled = false;
        synchronized (stateLock) {
            // 재생 도중 리스를 잃었으면 ACCEPTING으로 되돌리지 않는다. zombie ACCEPTING 방지.
            if (promotionGeneration.get() != generation || state != State.PROMOTING || !lease.isCurrent(term)) {
                log.warn("승격 완료 시점에 리더 아님. ACCEPTING 전환 취소: resource={}, epoch={}, state={}",
                        resource, term.epoch(), state);
                cancelled = promotionGeneration.get() == generation;
                if (cancelled && state == State.PROMOTING) {
                    heldTerm = null;
                    if (runtime != null) {
                        // 재생 구간의 회복 가능 반납이다. 인계 엔진은 승격이 바꾸지 않았으므로 팔로워로 되돌린다.
                        state = State.FOLLOWER;
                    }
                }
            } else if (!host.isRunning(scope)) {
                // 게이트는 리더십·상태만이 아니라 소비자 생존까지 봐야 한다. 정지한 루프 위에서
                // 열면 저널에 커밋되고 영원히 체결되지 않는 주문이 생긴다.
                loopDown = true;
                if (runtime == null) {
                    openTerm.set(null);
                    heldTerm = null;
                    state = State.DEMOTED;
                }
            } else {
                state = State.ACCEPTING;
                openTerm.set(term);
                opened = true;
            }
        }
        if (cancelled && runtime != null) {
            host.halt(scope);
            // 이 승격의 인계는 상태가 무엇이든 되돌린다. 재생 도중 강등이 들어와 재구성이 인계보다 먼저 끝났으면 인계 표시를
            // 지울 쪽이 이것뿐이다. 재구성이 아직이면 재구성이 팔로워 엔진을 다시 만든다.
            runtime.returnToFollower();
            return;
        }
        if (loopDown) {
            // 루프 정지는 강등 사유 그 자체다. 회복 가능으로 내리면 죽은 루프 위에서 승격과 반납을 무한 반복한다.
            log.error("승격 재생은 끝났으나 단일 라이터가 정지했다. 강등한다: resource={}, epoch={}",
                    resource, term.epoch());
            if (runtime != null) {
                runtime.demote(term, DemotionReason.PROMOTION_LOOP_DOWN, null);
            } else {
                availability.refusing();
                lease.demote("승격 완료 시점 단일 라이터 정지");
            }
            return;
        }
        if (opened) {
            availability.accepting();
        }
    }

    /** null이면 재생 성공. 아니면 마지막 실패 원인이다. 성공 전에는 엔진을 설치하지 않는다. */
    private Throwable replayWithRetry(LeaderTerm term, long generation, FollowerCatchUp.Handover handover, long t0) {
        Throwable last = null;
        long firstFailureNanos = 0;
        for (int attempt = 1; attempt <= REPLAY_ATTEMPTS; attempt++) {
            try {
                // 생략 설치가 한 번 실패했으면 다음 시도는 fresh 재생이다. 같은 인계 엔진으로 되풀이하면 같은 결과다.
                replayOnce(term, attempt == 1 ? handover : null, t0);
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
                        && lease.isCurrent(term)
                        && promotionGeneration.get() == generation
                        && state == State.PROMOTING;
                if (!retryable) {
                    return last;
                }
                log.warn("승격 재생 실패, 재시도한다: resource={}, epoch={}, 시도={}/{}, 경과={}ms, 재시도경과={}ms",
                        resource, term.epoch(), attempt, REPLAY_ATTEMPTS,
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

    private void replayOnce(LeaderTerm term, FollowerCatchUp.Handover handover, long t0) {
        Map<Integer, Long> heads = scoped(journalReader.maxSeqByMarket());
        if (follower == null) {
            replayOnceShared(term, heads, t0);
            return;
        }
        // 생략이 안전한 근거는 FollowerCatchUp 의 불변식 M 이다. M(P)의 모든 마켓에서 인계 엔진의 커서가 그 마켓 head 에
        // 닿았으면 어느 마켓에도 head 이하에 이벤트 없는 명령이 없다. 곧 메울 크래시 창이 없다. 인계가 상한 안에 끝나지
        // 않았거나, 저널에 명령이 있는데 인계 엔진이 없는 마켓이 있으면 생략하지 않는다.
        if (handover != null && handover.complete() && reachedEveryHead(handover.engines(), heads)) {
            MatchingEngineImpl combined = new MatchingEngineImpl();
            Map<Integer, Long> hashes = new TreeMap<>();
            handover.engines().forEach((marketId, engine) -> {
                OrderBookImpl book = engine.orderBook(marketId);
                if (book != null) {
                    combined.loadMarketSnapshot(marketId, engine.appliedSeq(marketId), book);
                    hashes.put(marketId, book.stateChecksum());
                }
            });
            Map<Integer, Long> cursors = combined.appliedSeqs();
            host.install(term, scope, combined);
            // 설치는 북을 복사해 새 루프에 심는다. 복사본과 인계 엔진의 커서와 상태 해시가 다르면 생략하지 않는다. 늦게
            // 끝난 따라잡기 틱이 인계 엔진을 바꿨거나 복사가 어긋난 것이고, 어느 쪽이든 조용히 보정하지 않는다.
            verifyInstalled(combined, cursors, hashes);
            installedEngine = combined;
            log.info("승격 재생 생략(M(P) head 도달), 인계 엔진을 설치: resource={}, epoch={}, 마켓 커서={}, head={}",
                    resource, term.epoch(), cursors, heads);
            return;
        }
        // fresh 엔진에 M(P)만 스냅샷·저널에서 복원하고 크래시 창을 재정산한다. 재시도마다 새로 만든다. 중단된 엔진을 이어
        // 쓰면 어디까지 정산됐는지가 불확정이다. 재적재는 event_emission_registry 판정으로 멱등이라 처음부터 다시 해도
        // 중복이 생기지 않고, 재적재 sink 는 이 기간으로 쓴다.
        MatchingEngineImpl fresh = new MatchingEngineImpl();
        recovery.recover(fresh, scope, term);
        host.install(term, scope, fresh);
        installedEngine = fresh;
        log.info("승격 재생 완료: resource={}, epoch={}, 마켓 커서={}, head={}, 소요={}ms",
                resource, term.epoch(), fresh.appliedSeqs(), heads, (System.nanoTime() - t0) / 1_000_000);
    }

    private void verifyInstalled(MatchingEngineImpl combined, Map<Integer, Long> cursors, Map<Integer, Long> hashes) {
        if (!combined.appliedSeqs().equals(cursors)) {
            throw new IllegalStateException("승격 설치 중 인계 엔진이 전진했다: before=" + cursors
                    + ", after=" + combined.appliedSeqs());
        }
        for (Map.Entry<Integer, Long> hash : hashes.entrySet()) {
            MatchingEngineImpl installed = host.installedEngine(hash.getKey());
            OrderBookImpl book = installed == null ? null : installed.orderBook(hash.getKey());
            if (book == null || book.stateChecksum() != hash.getValue()
                    || installed.appliedSeq(hash.getKey()) != cursors.get(hash.getKey())) {
                throw new IllegalStateException("설치한 복사본이 인계 엔진과 다르다: market=" + hash.getKey());
            }
        }
    }


    /** 종전 배선의 재생. 공유 엔진을 설치 엔진으로 들고 있다가 head 에 닿았으면 그대로 설치한다. */
    private void replayOnceShared(LeaderTerm term, Map<Integer, Long> heads, long t0) {
        MatchingEngineImpl current = installedEngine;
        // 생략 대상은 재생이지 설치가 아니다. 라이터가 이 엔진을 이어받아야 라이터의 카운터가
        // 자기 북의 진행도와 맞는다. 설치를 함께 생략하면 카운터만 부팅값에 멈춘 채 게이트가
        // 열려 첫 명령이 갭으로 보이고, catch-up 이 이미 반영된 구간을 다시 적용한다.
        if (current != null && reachedEveryHead(current, heads)) {
            Map<Integer, Long> applied = current.appliedSeqs();
            host.install(term, scope, current);
            if (!current.appliedSeqs().equals(applied)) {
                // 유계 대기를 못 채운 잔여 따라잡기 틱이 설치 창에서 엔진을 전진시켰다. 조용히 보정하지
                // 않는다. 마켓 커서 전체를 비교한다. 최댓값 비교는 낮은 대역 마켓의 전진을 놓친다.
                throw new IllegalStateException(
                        "승격 설치 중 엔진이 전진했다: before=" + applied
                                + ", after=" + current.appliedSeqs());
            }
            log.info("승격 재생 생략(모든 마켓 head 도달), 라이터에 설치: epoch={}, 마켓 커서={}, head={}",
                    term.epoch(), applied, heads);
            return;
        }
        MatchingEngineImpl fresh = new MatchingEngineImpl();
        recovery.recover(fresh);
        host.install(term, scope, fresh);
        installedEngine = fresh;
        log.info("승격 재생 완료: epoch={}, 마켓 커서={}, head={}, 소요={}ms",
                term.epoch(), fresh.appliedSeqs(), heads, (System.nanoTime() - t0) / 1_000_000);
    }

    private Map<Integer, Long> scoped(Map<Integer, Long> heads) {
        Map<Integer, Long> kept = new TreeMap<>();
        heads.forEach((marketId, head) -> {
            if (scope.test(marketId)) {
                kept.put(marketId, head);
            }
        });
        return kept;
    }

    private static boolean reachedEveryHead(MatchingEngineImpl engine, Map<Integer, Long> heads) {
        Map<Integer, Long> cursors = engine.appliedSeqs();
        for (Map.Entry<Integer, Long> head : heads.entrySet()) {
            Long cursor = cursors.get(head.getKey());
            if (cursor == null || cursor < head.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean reachedEveryHead(Map<Integer, MatchingEngineImpl> engines, Map<Integer, Long> heads) {
        for (Map.Entry<Integer, Long> head : heads.entrySet()) {
            MatchingEngineImpl engine = engines.get(head.getKey());
            if (engine == null || engine.appliedSeq(head.getKey()) < head.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 회복 가능 반납. 재생이 실패했으므로 이 노드는 리더가 되지 않는다(무음 유실 금지, §3-a).
     * 다만 상태는 강등이 아니라 FOLLOWER다. 접수를 연 적이 없고 루프도 승격 전 상태 그대로라
     * 다음 만료 구동 승격의 후보로 남는 것이 안전하다. 모든 후보가 강등으로 수렴하면 클러스터가
     * 리더 없이 정지한다.
     */
    private void standDown(LeaderTerm term, long generation, Throwable cause) {
        log.error("승격 재생 실패, 리더십을 반납한다(재승격 허용): resource={}, epoch={}", resource, term.epoch(), cause);
        synchronized (stateLock) {
            if (promotionGeneration.get() != generation) {
                // 더 새로운 승격이 이미 시작됐다. 낡은 시도가 그 상태와 리스를 지우면 안 된다.
                return;
            }
            openTerm.set(null);
            if (state == State.PROMOTING) {
                heldTerm = null;
                state = State.FOLLOWER;
            }
        }
        availability.refusing();
        lease.standDown("승격 재생 실패: " + cause.getClass().getSimpleName());
        if (runtime != null) {
            // 실패한 시도가 설치했을 수 있는 루프를 멈춘다. 게이트가 닫혀 있어 아무것도 받지 않았다. 인계는 상태가 무엇이든
            // 되돌린다(승격 완료 시점의 취소와 같은 이유).
            host.halt(scope);
            runtime.returnToFollower();
        }
    }

    /** 상태 전이(PartitionRuntime). 지금 상태가 expected 일 때만 바꾼다. */
    boolean transition(State expected, State next) {
        synchronized (stateLock) {
            if (state != expected) {
                return false;
            }
            state = next;
            return true;
        }
    }

    /** 승격 중이거나 접수 중인 기간. 계획 강등이 쓴다. */
    LeaderTerm heldTerm() {
        return heldTerm;
    }

    /**
     * 노드 치명(§5.4-c 5 NODE_FATAL). 회복 가능 등급의 전제(같은 재생을 다시 하면 같은 결과)가 성립하지 않는 사유에만
     * 쓴다. 복귀는 프로세스 재기동뿐이다.
     */
    private void nodeFatal(LeaderTerm term, long generation, Throwable cause) {
        log.error("승격 재생 VM Error, 노드 치명으로 강등한다(복귀는 재기동): resource={}, epoch={}",
                resource, term.epoch(), cause);
        synchronized (stateLock) {
            if (promotionGeneration.get() != generation) {
                // 더 새로운 승격이 이미 시작됐다. 낡은 시도가 그 상태와 리스를 지우면 안 된다.
                return;
            }
        }
        if (runtime != null) {
            runtime.demote(term, DemotionReason.NODE_FATAL, cause);
            return;
        }
        markNodeFatal();
        lease.markNodeFatal("승격 재생 VM Error: " + cause.getClass().getSimpleName());
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
        if (ownsWorker) {
            promotionWorker.shutdownNow();
        }
    }
}

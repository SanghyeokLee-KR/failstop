package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.MeteredLeaderLeaseStore;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayService;
import kr.exchange.journal.ReserveCheck;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/**
 * 파티션 단위 리더 노드 하나의 시험 배선(S4 설계 6-2). 운영 EngineConfig 의 조립과 같다. 파티션마다 리스 서비스·승격 조율·
 * 수명주기를 두고, 마켓별 라이터는 승격이 그 기간으로 설치한다. 기동은 늘 팔로워다.
 *
 * P3 흉내는 ReservationFixture DB 를 P2 로 올린 뒤 소유자가 모드 래치를 3 으로 바꾼다(PartitionLeaseDb.setProtocol). 마켓
 * 1(KRW-BTC)은 matching-p0, 2(KRW-ETH)·3(KRW-XRP)은 matching-p1 이다(V48 대응). 정산은 수수료 샤드 0 하나를 쓴다. 샤드
 * 1 계정은 S7 의 선행 조건이라 이 배선에서는 만들지 않는다(S4 설계 4-19).
 */
final class PartitionNode implements AutoCloseable {

    /** 정산 sink 앞의 주입점. 예외를 던지면 그 마켓 라이터가 치명 처리한다. */
    @FunctionalInterface
    interface SinkFault {
        void before(int marketId, LeaderTerm term, List<EngineEvent> events) throws Exception;
    }

    /** append 앞의 주입점. 예외를 던지면 접수가 그 예외로 끝난다. */
    @FunctionalInterface
    interface AppendFault {
        void before(LeaderTerm term, CommandDraft draft);
    }

    /** 노드 옵션. 기본값은 빠른 시험용이다(TTL 3초, 틱 300밀리초). */
    static final class Options {
        short maxProtocol = 3;
        Duration ttl = Duration.ofSeconds(3);
        Duration tick = Duration.ofMillis(300);
        Duration acquireLockTimeout = Duration.ofMillis(200);
        Duration standdownAfter = Duration.ofSeconds(45);
        Duration followerInterval = Duration.ofMillis(200);
        Set<String> preferred = Set.of();
        // null 이면 모든 파티션에 참여한다.
        Set<String> participate;
        int maxDemotions = 3;
        Duration demotionWindow = Duration.ofMinutes(10);
        LongSupplier stallClock = System::nanoTime;

        Options preferred(String... resources) {
            this.preferred = Set.of(resources);
            return this;
        }

        Options participate(String... resources) {
            this.participate = Set.of(resources);
            return this;
        }

        Options protocol(int protocol) {
            this.maxProtocol = (short) protocol;
            return this;
        }

        Options stallClock(LongSupplier clock, Duration standdownAfter) {
            this.stallClock = clock;
            this.standdownAfter = standdownAfter;
            return this;
        }
    }

    final String name;
    final ReservationFixture f;
    final Options options;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final LeaderWriteProgress nodeProgress = new LeaderWriteProgress();
    final PartitionPlan plan;
    final PartitionLeases leases;
    final WriterRouter router;
    final FollowerCatchUp follower;
    final EngineRecovery recovery;
    final SettlementService settlement;
    final CommandJournalAppender appender;
    final EngineBridge bridge;
    final HaAvailability ha;
    final ApplicationAvailabilityBean availability = new ApplicationAvailabilityBean();
    final List<PartitionRuntime> runtimes = new ArrayList<>();
    private final ExecutorService promotionWorker;
    private final ReplayService replayService;
    volatile SinkFault sinkFault = (marketId, term, events) -> { };
    volatile AppendFault appendFault = (term, draft) -> { };
    // 남은 수만큼 승격이 게이트를 열기 직전에 새로 설치한 루프를 멈춘다.
    final java.util.concurrent.atomic.AtomicInteger loopKillsBeforeGate = new java.util.concurrent.atomic.AtomicInteger();
    // readiness 가 한 번이라도 ACCEPTING 이었는가.
    final java.util.concurrent.atomic.AtomicBoolean everAccepting = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;

    PartitionNode(String name, ReservationFixture f, Options options) {
        this.name = name;
        this.f = f;
        this.options = options;
        this.plan = options.maxProtocol >= 3 ? PartitionPlan.partitioned(f.app) : PartitionPlan.single();
        String holder = name + "-" + UUID.randomUUID();
        LeaderStallDetector nodeHealth = LeaderStallDetector.node(null, nodeProgress, options.standdownAfter,
                registry, options.stallClock);
        AtomicReference<PartitionLeases> built = new AtomicReference<>();
        this.leases = new PartitionLeases(plan, holder, resource -> {
            LeaderWriteProgress partitionProgress = new LeaderWriteProgress();
            LeaderStallDetector partitionHealth = LeaderStallDetector.partition(resource, partitionProgress,
                    options.standdownAfter, registry, options.stallClock);
            NodeServiceHealth health = new NodeServiceHealth() {
                @Override
                public boolean serviceable() {
                    boolean node = nodeHealth.serviceable();
                    boolean partition = partitionHealth.serviceable();
                    return node && partition;
                }

                @Override
                public void onLeadershipGained() {
                    partitionHealth.onLeadershipGained();
                    PartitionLeases all = built.get();
                    if (all == null || all.all().stream()
                            .noneMatch(l -> !l.resource().equals(resource) && l.isLeader())) {
                        nodeHealth.onLeadershipGained();
                    }
                }
            };
            PartitionAcquirePolicy policy = plan.partitioned() && !options.preferred.contains(resource)
                    ? PartitionAcquirePolicy.nonPreferred(options.ttl) : PartitionAcquirePolicy.ALWAYS;
            MeteredLeaderLeaseStore store = new MeteredLeaderLeaseStore(
                    new PostgresLeaderLeaseStore(f.appPool, resource, options.maxProtocol, options.acquireLockTimeout),
                    registry, Clock.systemUTC(), resource);
            return new LeaderLeaseService(resource, holder, store, FencingEpochPublisher.NONE, options.ttl,
                    options.tick, health, policy, partitionProgress) {
                @Override
                public boolean endPromotionReplay(long token) {
                    boolean ended = super.endPromotionReplay(token);
                    // 승격 성공 경로는 재생 표식을 걷은 직후 루프 생존을 보고 게이트를 연다. 그 사이에 새로 설치한 루프를
                    // 멈춰 '게이트 개방 전에 죽은 루프'를 만든다(S4 설계 6-3 PROMOTION_LOOP_DOWN).
                    if (ended && loopKillsBeforeGate.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        router.halt(plan.scope(resource));
                    }
                    return ended;
                }
            };
        });
        built.set(leases);

        ObjectMapper objectMapper = new ObjectMapper();
        CommandJournalReader commandReader = new CommandJournalReader(f.app);
        this.replayService = new ReplayService(commandReader, new EventJournalReader(f.app), f.mapper, objectMapper,
                new MarketCatalog(f.app));
        this.settlement = new SettlementService(f.app, f.appTx, null, objectMapper, new MarketCatalog(f.app),
                f.mapper, nodeProgress);
        this.appender = new CommandJournalAppender(f.app, f.appTx, null, registry, nodeProgress) {
            @Override
            public AppendResult append(LeaderTerm term, CommandDraft draft, ReserveCheck check) {
                appendFault.before(term, draft);
                return super.append(term, draft, check);
            }
        };
        appender.setRequireReservation(options.maxProtocol >= 2);
        this.router = new WriterRouter((marketId, engine, term, slot) -> {
            PartitionRuntime runtime = leases.runtime(term.resource()).orElseThrow();
            SingleWriterLoop loop = new SingleWriterLoop(1024, engine, events -> {
                sinkFault.before(marketId, term, events);
                settlement.appendAndSettle(term, events);
            }, new JournalCatchUpSource(commandReader, f.mapper, marketId), new LoopFatalHandler(runtime, term));
            // 치명 오류를 워커 기본 처리기로 흘리지 않는다. 시험 출력만 어지럽힌다.
            loop.setWriterUncaughtExceptionHandler((t, e) -> { });
            return new WriterRouter.Shard(engine, loop);
        });
        router.setOpenTermLookup(leases::openTerm);
        this.recovery = new EngineRecovery(replayService, settlement::appendAndSettle, null, n -> { });
        this.follower = new FollowerCatchUp(replayService, plan, Map.of(), options.followerInterval, registry);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof AvailabilityChangeEvent<?> change) {
                if (change.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
                    everAccepting.set(true);
                }
                availability.onApplicationEvent(change);
            }
        };
        this.ha = new HaAvailability(publisher, availability, registry, name, plan.partitions());
        this.promotionWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine-promotion-" + name);
            t.setDaemon(true);
            return t;
        });
        for (String resource : plan.partitions()) {
            LeaderLeaseService lease = leases.get(resource);
            HaAvailability.Partition signal = ha.partition(resource);
            IntPredicate scope = plan.scope(resource);
            PromotionCoordinator coordinator = new PromotionCoordinator(resource, scope, recovery, router, lease,
                    commandReader, signal, follower, promotionWorker);
            boolean participates = options.participate == null || options.participate.contains(resource);
            runtimes.add(new PartitionRuntime(resource, scope, lease, coordinator, router, follower, recovery,
                    new PartitionBreaker(options.maxDemotions, options.demotionWindow), signal, leases,
                    participates, options.preferred.contains(resource), registry));
        }
        follower.onDiverged(resource -> leases.runtime(resource).ifPresent(PartitionRuntime::onDiverged));
        this.bridge = new EngineBridge(appender, router, f.mapper, leases, EngineBridge.NEVER_HALTED);
    }

    /** 운영 기동 수명주기와 같은 순서로 띄운다. 모든 마켓을 대조만 한 엔진을 팔로워에 넣고 리스 틱을 시작한다. */
    PartitionNode start() {
        MatchingEngineImpl booted = new MatchingEngineImpl();
        recovery.recoverAtBoot(booted);
        follower.seed(FollowerCatchUp.splitByMarket(booted));
        follower.start();
        for (PartitionRuntime runtime : runtimes) {
            runtime.start();
        }
        return this;
    }

    PartitionRuntime runtime(String resource) {
        return leases.runtime(resource).orElseThrow();
    }

    LeaderLeaseService lease(String resource) {
        return leases.get(resource);
    }

    PromotionCoordinator.State state(String resource) {
        return runtime(resource).state();
    }

    boolean accepting(String resource) {
        return state(resource) == PromotionCoordinator.State.ACCEPTING;
    }

    ReadinessState readiness() {
        return availability.getReadinessState();
    }

    /** 노드 층 수요를 세고 접수한다. 운영에서는 인증 필터가 주문 요청마다 센다. */
    SubmitResult submit(OrderIntake intake) {
        nodeProgress.recordAttempt();
        return bridge.submit(intake);
    }

    double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    ReplayService replayService() {
        return replayService;
    }

    /**
     * 프로세스가 죽은 것처럼 멈춘다. 리스를 반납하지 않으므로 다른 노드는 TTL 만료 뒤에만 얻는다.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        follower.stop();
        for (PartitionRuntime runtime : runtimes) {
            runtime.stop();
        }
        try {
            router.stopAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        promotionWorker.shutdownNow();
    }
}

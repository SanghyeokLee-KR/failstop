package kr.exchange.engine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.journal.JournalCheckpoints;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 팔로워 따라잡기. 리더가 쌓은 저널을 마켓별 체크포인트 상한으로 계속 재생해 이 노드의 엔진을 리더에
 * 붙여 둔다(2026-09-08).
 *
 * 왜 생겼나. 기동 재생이 체크포인트로 상한되면서 팔로워는 그 지점까지만 복원된 채 뜬다. 그 뒤
 * 리더가 쌓는 것을 아무도 따라잡지 않으면 팔로워 상태가 기동 시점에 얼어붙는다. 그러면 나중에
 * 승격할 때 재생할 양이 계속 늘고, 무엇보다 두 노드의 상태가 어긋나도 알 수 없다.
 *
 * 무엇이 '상태 일치 검증' 인가. 별도 장치가 아니라 재생 그 자체다. ReplayService 는 재생이
 * 만든 이벤트를 event_journal 의 행과 payload 바이트까지 대조한다. 이 노드의 엔진이 리더와
 * 다른 상태였다면 그 대조가 깨진다. 따라잡기가 조용히 도는 동안 그 대조가 매 명령마다 돈다.
 *
 * 배선은 둘이다. 파티션 배선(운영)은 마켓마다 엔진과 적용 잠금을 따로 두고 파티션마다 따라잡기를 켜고 끈다(DB 설계
 * §5.4-c 5·7). 그 파티션을 이 노드가 쥐면 인계(handOver)하고, 승격이 실패하면 되돌리며(resume), 강등 뒤에는 재구성한
 * 엔진을 받는다(rebuild). 종전 배선(시험)은 공유 엔진 하나를 들고 승격하면 멈춘다(stopForPromotion).
 *
 * 스레드 안전성의 근거를 적어 둔다. MatchingEngineImpl 은 동기화가 없는 단일 라이터 전용 자료구조다. 그래도 이 배선이
 * 안전한 이유는 셋이다.
 *   1. 조회는 엔진을 직접 읽지 않는다. 라이터가 게시한 불변 Depth 사본을 읽는다.
 *   2. 팔로워에서는 그 파티션의 접수 게이트가 닫혀 있어 라이터가 없다.
 *   3. 승격은 인계 엔진의 북을 복사해 설치하거나 fresh 엔진을 따로 만든다. 인계한 마켓은 틱이 만지지 않고, 인계는
 *      마켓별 적용 잠금 안에서 확인한다.
 *
 * 재생 불일치는 이 노드의 상태가 리더와 갈렸다는 뜻이다. 그 파티션의 따라잡기를 멈추고 지표로 올리며 재구성을 부른다.
 * 갈린 엔진이 쓰기에 쓰이는 경로가 없다. 승격은 그 파티션을 fresh 재생한다.
 *
 * <p><b>불변식 M(물질화).</b> 모든 마켓 m 에서 seq 가 cursor(m) 이하인 그 마켓 명령은 전부 이벤트가
 * 있다. 커서가 매 틱 새로 읽은 그 마켓의 bound(m) 까지만 전진하고, 출발점이 0 이거나 스냅샷 seq 이기
 * 때문이다(ADR 0022 5-4, DB 설계 §5.5-a). '커서가 체크포인트를 넘지 않는다' 는 체크포인트 정의상 늘
 * 참이라 이 불변식을 대신하지 못한다.
 *
 * 이 불변식이 실려 있는 곳이 하나 더 있다. PromotionCoordinator 는 인계 엔진이 이미 저널 head 에
 * 닿았으면 승격 재생을 생략한다. 생략이 안전한 근거가 곧 M 이다. 한 마켓의 cursor(m) 이 head(m) 에
 * 닿았으면 그 마켓 head 이하에 이벤트 없는 명령이 없다.
 *
 * M 을 깨는 방법은 셋이다. 상한을 매 틱 새로 읽지 않고 재사용하는 것, 저널 head 로 유도하는 것,
 * 마켓 판정에 전역 스칼라 커서나 전역 경계를 쓰는 것이다. 그러면 생략이 미물질화 명령 위에서
 * 접수를 열고 저널의 prefix 성질이 깨진다.
 */
public final class FollowerCatchUp implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FollowerCatchUp.class);

    /** 상태 게이지 값. 하네스가 이 축 하나로 팔로워 진행을 읽는다. 뜻은 하네스 CI/CD §5-2 다. */
    private static final int STATE_STOPPED = 0;
    private static final int STATE_FOLLOWING = 1;
    private static final int STATE_LEADER_IDLE = 2;
    private static final int STATE_DIVERGED = -1;

    // 종전 배선의 승격 정지가 진행 중인 틱을 기다리는 상한. 리스 스레드에서 도는 콜백이라 길게 잡으면
    // 리스 갱신이 그만큼 밀린다. TTL 10초에 비해 충분히 짧다.
    private static final long QUIESCE_WAIT_MILLIS = 200;

    /** 인계 결과. complete 가 거짓이면 제한 시간 안에 잠금을 다 잡지 못했거나 그 파티션이 갈려 있어 재생을 생략할 수 없다. */
    public record Handover(Map<Integer, MatchingEngineImpl> engines, boolean complete) {
    }

    /** 마켓 하나의 팔로워 엔진과 적용 잠금. */
    private static final class Market {
        final MatchingEngineImpl engine;
        final ReentrantLock lock;

        Market(MatchingEngineImpl engine, ReentrantLock lock) {
            this.engine = engine;
            this.lock = lock;
        }
    }

    /** 파티션 하나의 따라잡기 상태와 지표. */
    private static final class Track {
        final String resource;
        final AtomicInteger state = new AtomicInteger(STATE_STOPPED);
        final AtomicLong lagMax = new AtomicLong();
        final AtomicLong lagSum = new AtomicLong();
        final AtomicLong lastTickMillis = new AtomicLong();
        final Counter applied;
        // 대조한 이벤트 수. 따라잡기가 실제로 무엇을 판정했는지가 이 값에만 남는다. 적용 건수가
        // 늘어도 이 값이 0 이면 대조가 돌지 않은 것이다.
        final Counter verified;
        // 그 파티션을 이 노드가 쥐었다. 인계한 마켓은 틱이 만지지 않는다.
        volatile boolean handedOver;
        // 재구성 중이거나 노드 치명이다.
        volatile boolean paused;

        Track(String resource, MeterRegistry registry, Tags tags) {
            this.resource = resource;
            this.applied = Counter.builder("exchange.follower.catchup.applied")
                    .description("팔로워가 따라잡기로 적용한 명령 수")
                    .tags(tags)
                    .register(registry);
            this.verified = Counter.builder("exchange.follower.catchup.verified_events")
                    .description("팔로워가 저널과 대조한 이벤트 수")
                    .tags(tags)
                    .register(registry);
            // 마켓별 격차의 최대와 합. 합만 두면 한 마켓의 정체가 나머지 마켓의 진행에 묻힌다.
            Gauge.builder("exchange.follower.catchup.lag_commands.max", lagMax, AtomicLong::doubleValue)
                    .description("마켓별 (저널 head - 적용 위치) 의 최대. 적용 위치는 엔진 커서와 그 틱 물질화 경계 중 앞선 쪽이다. 대역 채번에서는 명령 수다")
                    .tags(tags)
                    .register(registry);
            Gauge.builder("exchange.follower.catchup.lag_commands.sum_of_markets", lagSum, AtomicLong::doubleValue)
                    .description("마켓별 (저널 head - 적용 위치) 의 합")
                    .tags(tags)
                    .register(registry);
            Gauge.builder("exchange.follower.catchup.state", state, AtomicInteger::doubleValue)
                    .description("1 따라잡는 중, 2 리더라 쉬는 중, 0 정지·재구성 중, -1 상태 어긋남")
                    .tags(tags)
                    .register(registry);
            Gauge.builder("exchange.follower.catchup.last_tick_seconds", lastTickMillis,
                            m -> m.get() / 1000.0)
                    .description("마지막 따라잡기 한 번의 소요")
                    .tags(tags)
                    .register(registry);
        }

        boolean following() {
            return !handedOver && !paused && state.get() != STATE_DIVERGED;
        }
    }

    private final ReplayService replayService;
    private final Duration interval;
    // 파티션 배선. null 이면 종전 배선이다.
    private final PartitionPlan plan;
    private final Map<Integer, Market> markets = new ConcurrentHashMap<>();
    private final Map<String, Track> tracks = new LinkedHashMap<>();
    private volatile Consumer<String> divergedListener = resource -> { };
    // 종전 배선. 공유 엔진 하나와 리스 하나.
    private final Market shared;
    private final LeaderLeaseService lease;
    // 이 노드가 리더가 됐다. 공유 엔진은 더 이상 이 노드의 팔로워 상태가 아니다.
    private volatile boolean promoted;
    private volatile ScheduledExecutorService scheduler;

    /** 종전 배선. 공유 엔진 하나를 따라잡고 리스를 얻으면 멈춘다. */
    public FollowerCatchUp(ReplayService replayService, MatchingEngineImpl engine,
                           LeaderLeaseService lease, Duration interval, MeterRegistry registry) {
        if (replayService == null || engine == null || lease == null || interval == null
                || registry == null) {
            throw new IllegalArgumentException("replayService·engine·lease·interval·registry는 필수");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval은 양수여야 한다");
        }
        this.replayService = replayService;
        this.interval = interval;
        this.plan = null;
        this.shared = new Market(engine, new ReentrantLock());
        this.lease = lease;
        tracks.put(lease.resource(), new Track(lease.resource(), registry, Tags.empty()));
    }

    /**
     * 파티션 배선. 기동 복구가 대조한 엔진을 마켓별로 나눠 받는다(splitByMarket). 새 마켓은 처음 볼 때 커서 0 엔진을 만든다.
     */
    public FollowerCatchUp(ReplayService replayService, PartitionPlan plan, Map<Integer, MatchingEngineImpl> engines,
                           Duration interval, MeterRegistry registry) {
        if (replayService == null || plan == null || engines == null || interval == null || registry == null) {
            throw new IllegalArgumentException("replayService·plan·engines·interval·registry는 필수");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval은 양수여야 한다");
        }
        this.replayService = replayService;
        this.interval = interval;
        this.plan = plan;
        this.shared = null;
        this.lease = null;
        engines.forEach((marketId, engine) -> markets.put(marketId, new Market(engine, new ReentrantLock())));
        for (String resource : plan.partitions()) {
            tracks.put(resource, new Track(resource, registry, Tags.of("partition", resource)));
        }
    }

    /** 기동 복구가 대조한 마켓별 엔진을 받는다(파티션 배선). 시작 전에 한 번 부른다. */
    public void seed(Map<Integer, MatchingEngineImpl> engines) {
        if (plan == null) {
            throw new IllegalStateException("종전 배선은 생성자로 공유 엔진을 받는다");
        }
        engines.forEach((marketId, engine) -> markets.put(marketId, new Market(engine, new ReentrantLock())));
    }

    /** 엔진 하나에 담긴 마켓들을 마켓마다 엔진 하나로 나눈다. 북은 옮긴다(원래 엔진은 더 쓰지 않는다). */
    public static Map<Integer, MatchingEngineImpl> splitByMarket(MatchingEngineImpl engine) {
        Map<Integer, MatchingEngineImpl> split = new TreeMap<>();
        for (int marketId : engine.markets()) {
            MatchingEngineImpl single = new MatchingEngineImpl();
            single.loadMarketSnapshot(marketId, engine.appliedSeq(marketId), engine.orderBook(marketId));
            split.put(marketId, single);
        }
        return split;
    }

    /** 갈림을 알린다. 파티션 수명주기가 재구성을 예약한다. */
    public void onDiverged(Consumer<String> listener) {
        this.divergedListener = listener;
    }

    private Track onlyTrack() {
        return tracks.values().iterator().next();
    }

    /** 관측·검증용. 마켓별 격차의 최대다(종전 배선). */
    public long lagCommands() {
        return onlyTrack().lagMax.get();
    }

    /** 관측·검증용. 마켓별 격차의 합이다(종전 배선). */
    public long lagCommandsSum() {
        return onlyTrack().lagSum.get();
    }

    /** 관측·검증용. 지금까지 대조한 이벤트 수다(종전 배선). */
    public long verifiedEvents() {
        return (long) onlyTrack().verified.count();
    }

    /** 관측·검증용. 상태 게이지와 같은 값이다(종전 배선). */
    public int state() {
        return onlyTrack().state.get();
    }

    /** 관측·검증용. 그 파티션의 상태 게이지 값이다. */
    public int state(String resource) {
        return track(resource).state.get();
    }

    /** 그 파티션이 저널과 갈렸는가. 재구성이 풀어 준다. */
    public boolean diverged(String resource) {
        return track(resource).state.get() == STATE_DIVERGED;
    }

    /** 팔로워가 엔진을 든 마켓 가운데 그 파티션 마켓. 관리 조회가 쓴다. */
    public java.util.List<Integer> markets(String resource) {
        if (plan == null) {
            return shared.engine.markets();
        }
        return new TreeMap<>(markets).keySet().stream()
                .filter(marketId -> resource.equals(plan.partitionOf(marketId)))
                .toList();
    }

    /** 관측·검증용. 그 마켓의 팔로워 엔진 커서. 엔진이 없으면 0 이다. */
    public long appliedSeq(int marketId) {
        if (shared != null) {
            return shared.engine.appliedSeq(marketId);
        }
        Market market = markets.get(marketId);
        return market == null ? 0 : market.engine.appliedSeq(marketId);
    }

    private Track track(String resource) {
        Track track = tracks.get(resource);
        if (track == null) {
            throw new IllegalArgumentException("계획에 없는 자원: " + resource);
        }
        return track;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "follower-catchup");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(this::tick, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        scheduler = s;
        for (Track track : tracks.values()) {
            if (track.following()) {
                track.state.set(STATE_FOLLOWING);
            } else if (track.handedOver) {
                track.state.set(STATE_LEADER_IDLE);
            }
        }
        log.info("팔로워 따라잡기 시작: 주기 {}, 파티션 {}", interval, tracks.keySet());
    }

    public void stop() {
        ScheduledExecutorService s;
        synchronized (this) {
            s = scheduler;
            scheduler = null;
        }
        if (s == null) {
            return;
        }
        s.shutdownNow();
        try {
            // 진행 중 틱의 종료를 기다린다. 기다리지 않으면 정지 후에도 엔진을 만지는 스레드가
            // 남아 승격 경로와 겹칠 수 있다.
            s.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Track track : tracks.values()) {
            if (track.state.get() != STATE_DIVERGED) {
                track.state.set(STATE_STOPPED);
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * 승격 직전 정지(종전 배선). 진행 중인 틱이 끝난 것을 확인한 뒤 반환한다.
     *
     * 대기가 유계인 것이 계약이다. 이 콜백은 리스 스케줄러 스레드에서 돌고, 그 스레드가 막히면
     * 리스 갱신이 함께 막혀 정당한 새 리더가 리스를 잃는다. 2026-09-08 회차에서 실제로 그랬다.
     * 상한 안에 끝나지 않으면 플래그만 세우고 반환한다. 남는 창은 지금 도는 틱 하나이며, 그 틱은 자기 시작 시점의
     * 체크포인트까지만 적용하므로 불변식 M 을 깨지 않는다.
     *
     * 승격 뒤에는 다시 시작하지 않는다. 공유 엔진은 그 시점부터 이 노드의 상태가 아니다.
     */
    public void stopForPromotion() {
        if (shared == null) {
            throw new IllegalStateException("파티션 배선은 handOver 를 쓴다");
        }
        promoted = true;
        boolean quiesced = false;
        try {
            quiesced = shared.lock.tryLock(QUIESCE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (quiesced) {
                shared.lock.unlock();
            }
        }
        if (!quiesced) {
            // 플래그는 이미 섰으므로 다음 틱은 즉시 물러난다. 남는 창은 '지금 도는 틱' 하나뿐이다.
            log.warn("따라잡기 틱이 {}ms 안에 끝나지 않았다. 플래그로만 정지한다", QUIESCE_WAIT_MILLIS);
        }
        onlyTrack().state.set(STATE_LEADER_IDLE);
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            // shutdown 이다. shutdownNow 로 진행 중 틱을 끊으면 엔진이 명령 중간에 남는다.
            s.shutdown();
        }
    }

    /**
     * 그 파티션을 인계한다(DB 설계 §5.4-c 7). 이후 틱은 그 파티션 마켓을 만지지 않는다. 마켓마다 적용 잠금을 차례로 잡아
     * 진행 중인 적용이 끝난 것을 확인하고, 잠금 대기는 파티션 전체에 limit 이 상한이다. 상한 안에 끝나지 않거나 갈려 있으면
     * complete 가 거짓이고 승격은 이 엔진을 쓰지 않고 fresh 재생한다. 늦게 끝나는 틱이 만진 엔진은 승격이 버린다.
     *
     * 인계 엔진은 팔로워가 계속 쥔다. 승격은 북을 복사해 설치하므로 인계 엔진을 바꾸지 않고, 승격이 실패하면 resume 으로
     * 그대로 이어 따라잡는다.
     */
    public Handover handOver(String resource, Duration limit) {
        Track track = track(resource);
        track.handedOver = true;
        boolean complete = track.state.get() != STATE_DIVERGED && !track.paused;
        track.state.set(STATE_LEADER_IDLE);
        long deadline = System.nanoTime() + limit.toNanos();
        Map<Integer, MatchingEngineImpl> engines = new TreeMap<>();
        for (Map.Entry<Integer, Market> entry : new TreeMap<>(markets).entrySet()) {
            int marketId = entry.getKey();
            if (!resource.equals(plan.partitionOf(marketId))) {
                continue;
            }
            Market market = entry.getValue();
            boolean locked = false;
            try {
                locked = market.lock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!locked) {
                complete = false;
                log.warn("팔로워 인계 잠금 대기 상한 초과: resource={}, market={}. 승격은 fresh 재생한다", resource, marketId);
                continue;
            }
            try {
                engines.put(marketId, market.engine);
            } finally {
                market.lock.unlock();
            }
        }
        return new Handover(engines, complete);
    }

    /** 인계를 되돌린다. 승격이 접수를 열지 못하고 끝난 경우다. 인계 엔진은 승격이 바꾸지 않았으므로 이어서 따라잡는다. */
    public void resume(String resource) {
        Track track = track(resource);
        track.handedOver = false;
        if (track.state.get() != STATE_DIVERGED && !track.paused) {
            track.state.set(STATE_FOLLOWING);
        }
    }

    /** 그 파티션의 따라잡기를 멈춘다. 재구성 중이거나 노드 치명이다. 상태는 0 이다. */
    public void pause(String resource) {
        Track track = track(resource);
        track.paused = true;
        track.state.set(STATE_STOPPED);
    }

    /**
     * 재구성한 엔진을 받는다(DB 설계 §5.4-c 5). 그 파티션 마켓의 팔로워 엔진을 모두 바꾸고 따라잡기를 다시 켠다. 받은 지도에
     * 없는 그 파티션 마켓은 물질화된 명령이 없는 마켓이라 지우고, 처음 볼 때 커서 0 엔진을 만든다.
     */
    public void rebuild(String resource, Map<Integer, MatchingEngineImpl> engines) {
        Track track = track(resource);
        for (Integer marketId : new TreeMap<>(markets).keySet()) {
            if (resource.equals(plan.partitionOf(marketId)) && !engines.containsKey(marketId)) {
                markets.remove(marketId);
            }
        }
        engines.forEach((marketId, engine) -> markets.put(marketId, new Market(engine, new ReentrantLock())));
        track.handedOver = false;
        track.paused = false;
        track.state.set(STATE_FOLLOWING);
    }

    /** 한 번 따라잡는다. 테스트가 스케줄러 없이 직접 부른다. */
    public void tick() {
        if (shared != null) {
            legacyTick();
            return;
        }
        long startedNanos = System.nanoTime();
        try {
            advancePartitions();
        } catch (RuntimeException e) {
            // 왕복 실패는 갈림이 아니다. 다음 틱에 다시 시도한다.
            log.warn("따라잡기 틱 실패: {}", e.toString());
        } finally {
            long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
            for (Track track : tracks.values()) {
                track.lastTickMillis.set(elapsed);
            }
        }
    }

    private void legacyTick() {
        Track track = onlyTrack();
        if (promoted || lease.isLeader()) {
            // 리더는 단일 라이터 루프가 엔진을 만진다. 여기서 만지면 둘이 겹친다.
            track.state.set(STATE_LEADER_IDLE);
            return;
        }
        if (track.state.get() == STATE_DIVERGED) {
            return;
        }
        long startedNanos = System.nanoTime();
        shared.lock.lock();
        try {
            if (promoted) {
                // 락을 기다리는 사이 승격했다. 이 엔진은 더 이상 이 노드의 팔로워 상태가 아니다.
                track.state.set(STATE_LEADER_IDLE);
                return;
            }
            advanceShared(track);
            track.state.set(STATE_FOLLOWING);
        } catch (kr.exchange.journal.ReplayMismatchException e) {
            // 이 노드의 엔진이 리더와 갈렸다. 더 적용하면 갈린 상태 위에 쌓는다.
            track.state.set(STATE_DIVERGED);
            log.error("팔로워 상태가 리더와 갈렸다. 따라잡기를 멈춘다: 마켓 커서={}, 사유={}",
                    shared.engine.appliedSeqs(), e.getMessage());
        } catch (RuntimeException e) {
            // 왕복 실패는 갈림이 아니다. 다음 틱에 다시 시도한다.
            log.warn("따라잡기 틱 실패: {}", e.toString());
        } finally {
            track.lastTickMillis.set((System.nanoTime() - startedNanos) / 1_000_000L);
            shared.lock.unlock();
        }
    }

    /** 마켓별 체크포인트를 새로 읽고 마켓마다 그 지점까지만 전진한다. 불변식 M 이 여기서 성립한다. */
    private void advanceShared(Track track) {
        MatchingEngineImpl engine = shared.engine;
        JournalCheckpoints bounds = replayService.materializedThroughByMarket(engine);
        for (Map.Entry<Integer, Long> bound : bounds.bounds().entrySet()) {
            int marketId = bound.getKey();
            if (bound.getValue() <= engine.appliedSeq(marketId)) {
                continue;
            }
            ReplayResult result = replayService.verifyToMarket(engine, marketId, bounds);
            if (result.replayedCommands() > 0) {
                track.applied.increment(result.replayedCommands());
            }
            track.verified.increment(result.verifiedEvents());
        }
        measureLag(bounds, marketId -> track);
    }

    /**
     * 파티션 배선의 전진. 따라잡는 파티션의 마켓만 경계를 읽고 그 마켓의 적용 잠금 안에서 전진한다. 인계한 마켓의 커서는 인계
     * 시점에 멈춰 있어 그 경계 질의가 그 뒤 커밋된 명령을 매 틱 훑으므로, 리더로 오래 있을수록 비용이 커진다. 그래서 따라잡는
     * 파티션이 없으면 DB 에 가지 않는다. 쉬는 파티션의 격차는 0 이다.
     */
    private void advancePartitions() {
        if (tracks.values().stream().noneMatch(Track::following)) {
            for (Track track : tracks.values()) {
                track.lagMax.set(0);
                track.lagSum.set(0);
            }
            return;
        }
        Map<Integer, Long> cursors = new TreeMap<>();
        markets.forEach((marketId, market) -> cursors.put(marketId, market.engine.appliedSeq(marketId)));
        JournalCheckpoints bounds = replayService.materializedThroughByMarket(cursors, marketId -> {
            String resource = plan.partitionOf(marketId);
            Track track = resource == null ? null : tracks.get(resource);
            return track != null && track.following();
        });
        for (Map.Entry<Integer, Long> bound : bounds.bounds().entrySet()) {
            int marketId = bound.getKey();
            String resource = plan.partitionOf(marketId);
            Track track = resource == null ? null : tracks.get(resource);
            if (track == null || !track.following()) {
                continue;
            }
            Market market = markets.computeIfAbsent(marketId,
                    id -> new Market(new MatchingEngineImpl(), new ReentrantLock()));
            if (bound.getValue() <= market.engine.appliedSeq(marketId)) {
                continue;
            }
            market.lock.lock();
            try {
                if (!track.following()) {
                    // 잠금을 기다리는 사이 인계했다. 이 마켓은 더 이상 이 틱이 만질 대상이 아니다.
                    continue;
                }
                ReplayResult result = replayService.verifyToMarket(market.engine, marketId, bounds);
                if (result.replayedCommands() > 0) {
                    track.applied.increment(result.replayedCommands());
                }
                track.verified.increment(result.verifiedEvents());
            } catch (kr.exchange.journal.ReplayMismatchException e) {
                // 이 파티션의 엔진이 리더와 갈렸다. 그 파티션만 멈추고 재구성을 부른다.
                track.state.set(STATE_DIVERGED);
                log.error("팔로워 상태가 리더와 갈렸다. 그 파티션의 따라잡기를 멈춘다: resource={}, market={}, 사유={}",
                        resource, marketId, e.getMessage());
                divergedListener.accept(resource);
            } finally {
                market.lock.unlock();
            }
        }
        measureLag(bounds, marketId -> {
            String resource = plan.partitionOf(marketId);
            return resource == null ? null : tracks.get(resource);
        });
        for (Track track : tracks.values()) {
            if (track.following()) {
                track.state.set(STATE_FOLLOWING);
            }
        }
    }

    /** 격차는 전진 뒤 head 를 다시 읽어 잰다. 앞서 읽은 head 는 그 뒤 경계보다 작을 수 있다. */
    private void measureLag(JournalCheckpoints bounds, java.util.function.IntFunction<Track> trackOf) {
        Map<Track, long[]> lags = new LinkedHashMap<>();
        for (Track track : tracks.values()) {
            lags.put(track, new long[2]);
        }
        for (Map.Entry<Integer, Long> head : replayService.journalHeads().entrySet()) {
            Long bound = bounds.bounds().get(head.getKey());
            if (bound == null) {
                // 쉬는 파티션의 마켓이거나 이 틱에 경계를 읽지 않은 새 마켓이다. 새 마켓을 커서로 재면 대역 채번에서 대역 폭이
                // 격차로 읽힌다. 다음 틱에 잰다.
                continue;
            }
            Track track = trackOf.apply(head.getKey());
            if (track == null) {
                continue;
            }
            // 경계 이하 명령은 위에서 전부 적용됐으므로 커서가 대역 시작 아래에 있어도 경계에서 잰다. 스냅샷에서 복원한
            // 커서는 그 마켓의 마지막 명령보다 클 수 있다. 그때의 음수 격차는 이상이 아니라 0 이다.
            long lag = Math.max(0, head.getValue() - Math.max(appliedSeq(head.getKey()), bound));
            long[] acc = lags.get(track);
            acc[0] = Math.max(acc[0], lag);
            acc[1] += lag;
        }
        lags.forEach((track, acc) -> {
            track.lagMax.set(acc[0]);
            track.lagSum.set(acc[1]);
        });
    }
}

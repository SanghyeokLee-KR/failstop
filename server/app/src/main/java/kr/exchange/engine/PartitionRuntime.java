package kr.exchange.engine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.journal.SqlErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/**
 * 파티션 하나의 수명주기(DB 설계 §5.4-c 5·9·10). 리스·승격 조율·차단기·재구성을 묶고 강등을 한 번의 호출로 모은다.
 *
 * 강등 순서가 계약이다. 그 파티션 마켓의 접수 게이트를 닫고, 리스를 놓고(DB 에 쓰지 않는다. 갱신만 멈춘다), 그 파티션 마켓의
 * 라이터를 멈추고, 사유를 세고, 재구성을 파티션 작업 스레드에 넘긴다. 재구성은 기동 복구와 같은 대조로 그 파티션 마켓의 팔로워
 * 엔진을 새로 만든다. 강등 시점의 인메모리 오더북은 다른 리더의 커밋을 반영하지 못한 stale 이라 하나도 쓰지 않는다. 다른
 * 파티션의 라이터·엔진·리스는 건드리지 않는다.
 *
 * 노드 치명(VirtualMachineError)은 모든 파티션을 종신 강등하고 재구성하지 않는다. 재기동으로만 복귀한다.
 */
public final class PartitionRuntime {

    private static final Logger log = LoggerFactory.getLogger(PartitionRuntime.class);

    private static final long REBUILD_BACKOFF_START_MILLIS = 1_000;
    // 격리된 옛 리더(E5)는 복귀할 때까지 재구성이 실패한다. 상한이 너무 크면 복귀 뒤 팔로워로 돌아오는 시간이 늘어난다.
    private static final long REBUILD_BACKOFF_MAX_MILLIS = 30_000;
    private static final int HISTORY_LIMIT = 20;

    /** 강등 기록 하나. 관리 조회가 낸다. */
    public record Demotion(DemotionReason reason, String detail, Instant at) {
    }

    private final String resource;
    private final IntPredicate scope;
    private final LeaderLeaseService lease;
    private final PromotionCoordinator coordinator;
    private final WriterRouter router;
    private final FollowerCatchUp follower;
    private final EngineRecovery recovery;
    private final PartitionBreaker breaker;
    private final PartitionLeases node;
    private final boolean preferred;
    private final ScheduledExecutorService work;
    private final Deque<Demotion> history = new ArrayDeque<>();
    private final Map<DemotionReason, Counter> demotions = new EnumMap<>(DemotionReason.class);
    // 자발적 강등 수(하네스 CI/CD §5-2 exchange.leader.standdown{partition}). 두 층 어느 쪽의 판정이든 이 파티션의 STALL 이다.
    private final Counter standdowns;
    private final Timer rebuildSuccess;
    private final Timer rebuildFailure;
    // 파티션 작업 스레드만 쓴다.
    private long rebuildBackoffMillis = REBUILD_BACKOFF_START_MILLIS;

    public PartitionRuntime(String resource, IntPredicate scope, LeaderLeaseService lease,
                            PromotionCoordinator coordinator, WriterRouter router, FollowerCatchUp follower,
                            EngineRecovery recovery, PartitionBreaker breaker, HaAvailability.Partition availability,
                            PartitionLeases node, boolean participate, boolean preferred, MeterRegistry registry) {
        this.resource = resource;
        this.scope = scope;
        this.lease = lease;
        this.coordinator = coordinator;
        this.router = router;
        this.follower = follower;
        this.recovery = recovery;
        this.breaker = breaker;
        this.node = node;
        this.preferred = preferred;
        // 참여를 끈 파티션은 후보 표식을 내린 채 시작한다.
        coordinator.setParticipating(participate);
        this.work = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "partition-work-" + resource);
            t.setDaemon(true);
            return t;
        });
        for (DemotionReason reason : DemotionReason.values()) {
            demotions.put(reason, Counter.builder("exchange.partition.demotions")
                    .description("파티션 강등 수(사유별)")
                    .tags("partition", resource, "reason", reason.name())
                    .register(registry));
        }
        this.standdowns = Counter.builder("exchange.leader.standdown")
                .description("서비스 불능 지속으로 리스 갱신을 자발적으로 포기한 횟수")
                .tag("partition", resource)
                .register(registry);
        this.rebuildSuccess = Timer.builder("exchange.partition.rebuild")
                .description("파티션 fresh 재구성 시간")
                .tags("partition", resource, "outcome", "success")
                .register(registry);
        this.rebuildFailure = Timer.builder("exchange.partition.rebuild")
                .description("파티션 fresh 재구성 시간")
                .tags("partition", resource, "outcome", "failure")
                .register(registry);
        Gauge.builder("exchange.partition.state", coordinator, c -> c.state().ordinal())
                .description("0 FOLLOWER, 1 PROMOTING, 2 ACCEPTING, 3 REBUILDING, 4 BLOCKED, 5 DEMOTED(노드 치명)")
                .tag("partition", resource)
                .register(registry);
        Gauge.builder("exchange.partition.breaker.open", breaker, b -> b.isOpen() ? 1 : 0)
                .description("차단(1). 이 노드는 그 파티션의 후보가 아니다")
                .tag("partition", resource)
                .register(registry);
        coordinator.attach(this);
        node.attach(this);
        lease.onTermGained(coordinator::onTermGained);
        lease.onTermLost(this::onLeaseLost);
        lease.onLeadershipAcquired((identity, downtime) -> availability.leaderAcquired(identity.epoch(), downtime));
        lease.onLeadershipReleased(availability::leaderLost);
    }

    public String resource() {
        return resource;
    }

    public LeaderLeaseService lease() {
        return lease;
    }

    public PromotionCoordinator coordinator() {
        return coordinator;
    }

    public PromotionCoordinator.State state() {
        return coordinator.state();
    }

    public boolean participating() {
        return coordinator.participating();
    }

    public PartitionBreaker breaker() {
        return breaker;
    }

    /** 그 파티션의 마켓 판정. */
    public IntPredicate scope() {
        return scope;
    }

    /** 리스 틱을 시작한다. */
    public void start() {
        lease.start();
    }

    public void stop() {
        lease.stop();
        work.shutdownNow();
    }

    /**
     * 강등 단일 호출(DB 설계 §5.4-c 5). 기간을 받고, 그 기간이 이 파티션이 지금 승격 중이거나 접수 중인 기간이 아니면 늦게 온
     * 옛 기간의 실패라 아무것도 하지 않는다. VirtualMachineError 는 노드 치명이다.
     */
    public void demote(LeaderTerm term, DemotionReason reason, Throwable cause) {
        // SQLSTATE 는 컨테이너 로그를 잃어도 관리 조회의 강등 기록에 원인이 남게 싣는다(API 설계 §6-C).
        String detail = cause == null ? reason.name()
                : reason + ": " + cause.getClass().getSimpleName() + " sqlstate=" + SqlErrors.stateLabel(cause);
        if (reason == DemotionReason.NODE_FATAL) {
            node.nodeFatal(detail);
            return;
        }
        handleDemotion(term, reason, detail, true);
    }

    /** 리스가 놓은 기간(갱신 거부, fencing 재확인 실패, 좀비 판정, 치명 처리기의 강등). */
    private void onLeaseLost(LeaderTerm term, DemotionReason reason, String detail) {
        if (reason == DemotionReason.NODE_FATAL) {
            return;
        }
        handleDemotion(term, reason, detail, false);
    }

    private void handleDemotion(LeaderTerm term, DemotionReason reason, String detail, boolean releaseLease) {
        if (!coordinator.beginDemotion(term)) {
            // 이 기간으로 승격 중이거나 접수 중이 아니다. 늦게 온 옛 기간의 실패거나 이미 강등을 시작했다. 리스가 아직 이
            // 기간을 쥐었다면(승격 조율이 받기 전) 놓기만 한다.
            if (releaseLease) {
                lease.demote(term, reason, detail);
            }
            return;
        }
        // 게이트를 먼저 닫았다. 이후 이 파티션 마켓의 접수는 저널 전에 503 이다. 리스는 DB 에 쓰지 않고 갱신만 멈춘다.
        if (releaseLease) {
            lease.demote(term, reason, detail);
        }
        router.halt(scope);
        record(reason, detail);
        if (reason == DemotionReason.STALL) {
            standdowns.increment();
        }
        boolean blocked = breaker.record(reason);
        log.warn("파티션 강등: resource={}, epoch={}, 사유={}({}), 차단기 {}회/{}", resource, term.epoch(), reason, detail,
                breaker.count(), blocked ? "열림" : "닫힘");
        scheduleRebuild(0);
    }

    /** 노드 치명의 이 파티션 몫. 게이트를 닫고 리스를 종신 강등하고 라이터를 멈춘다. 재구성하지 않는다. */
    void nodeFatalLocal(String detail) {
        coordinator.markNodeFatal();
        lease.markNodeFatal(detail);
        router.halt(scope);
        if (follower != null) {
            follower.pause(resource);
        }
        record(DemotionReason.NODE_FATAL, detail);
        work.shutdownNow();
    }

    /** 승격이 접수를 열지 못하고 팔로워로 돌아왔다. 인계를 되돌린다. 팔로워가 갈려 있으면 재구성한다. */
    void returnToFollower() {
        if (follower == null) {
            return;
        }
        follower.resume(resource);
        if (follower.diverged(resource)) {
            onDiverged();
        }
    }

    /**
     * 팔로워가 저널과 갈렸다. 후보에서 잠시 빼고 재구성한다. 차단기에 세지 않는다. 승격 중이거나 접수 중이면 그 팔로워 엔진은
     * 쓰이지 않고(승격은 fresh 재생), 다음 강등의 재구성이 새로 만들거나 승격이 접수를 열지 못하면 returnToFollower 가 다시 부른다.
     */
    public void onDiverged() {
        try {
            work.execute(() -> {
                if (coordinator.beginRebuild()) {
                    rebuild();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("파티션 작업 스레드가 멈춰 갈림 재구성을 예약하지 못했다: resource={}", resource);
        }
    }

    private void scheduleRebuild(long delayMillis) {
        try {
            work.schedule(this::rebuild, delayMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("파티션 작업 스레드가 멈춰 재구성을 예약하지 못했다: resource={}", resource);
        }
    }

    /**
     * fresh 재구성(DB 설계 §5.4-c 5). 그 파티션 마켓의 팔로워 엔진을 버리고 스냅샷과 저널에서 물질화 경계까지 대조해 새로
     * 만든다. 크래시 창은 재적재하지 않는다. 그것은 다음 리더의 승격 재생이 한다. 실패하면 1초에서 30초까지 두 배씩 늘어나는
     * 간격으로 다시 한다. 재구성 실패는 차단기에 세지 않는다.
     */
    private void rebuild() {
        if (lease.isNodeFatal()) {
            return;
        }
        if (follower != null) {
            follower.pause(resource);
        }
        long started = System.nanoTime();
        try {
            MatchingEngineImpl fresh = new MatchingEngineImpl();
            recovery.recoverFollower(fresh, scope);
            if (follower != null) {
                follower.rebuild(resource, FollowerCatchUp.splitByMarket(fresh));
            }
            rebuildSuccess.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            rebuildBackoffMillis = REBUILD_BACKOFF_START_MILLIS;
        } catch (RuntimeException e) {
            rebuildFailure.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            long retry = rebuildBackoffMillis;
            rebuildBackoffMillis = Math.min(rebuildBackoffMillis * 2, REBUILD_BACKOFF_MAX_MILLIS);
            log.warn("파티션 재구성 실패, {}ms 뒤 다시 한다: resource={}, 원인={}", retry, resource, e.toString());
            scheduleRebuild(retry);
            return;
        }
        settleAfterRebuild();
    }

    private void settleAfterRebuild() {
        if (breaker.isOpen()) {
            coordinator.settle(PromotionCoordinator.State.BLOCKED);
            log.error("파티션 차단: 창 안의 강등이 상한에 이르러 이 노드는 그 파티션의 리스를 노리지 않는다. "
                    + "운영자가 /actuator/partitions 로 풀거나 재기동해야 한다: resource={}", resource);
            return;
        }
        if (!coordinator.settle(PromotionCoordinator.State.FOLLOWER)) {
            return;
        }
        boolean armed = coordinator.rearmIfFollower();
        log.info("파티션 재구성 완료, 팔로워로 돌아왔다: resource={}, 후보={}", resource, armed);
    }

    /**
     * 참여를 켜고 끈다(DB 설계 §5.4-c 9, ADR 0034 결정 2). 끄면 후보 표식을 내리고, 이 파티션을 쥐고 있으면 계획 강등한다.
     * 리스는 반납하지 않고 만료시킨다.
     */
    public void setParticipate(boolean on) {
        LeaderTerm held = coordinator.setParticipating(on);
        if (!on) {
            if (held != null) {
                demote(held, DemotionReason.PLANNED, null);
            }
            return;
        }
        if (!breaker.isOpen()) {
            coordinator.rearmIfFollower();
        }
    }

    /**
     * 차단기를 푼다. 노드 치명(DEMOTED)은 풀지 않는다.
     *
     * @return 풀었으면 참
     */
    public boolean resetBreaker() {
        if (lease.isNodeFatal()) {
            return false;
        }
        breaker.reset();
        if (coordinator.transition(PromotionCoordinator.State.BLOCKED, PromotionCoordinator.State.FOLLOWER)) {
            coordinator.rearmIfFollower();
        }
        return true;
    }

    private void record(DemotionReason reason, String detail) {
        demotions.get(reason).increment();
        synchronized (history) {
            history.addLast(new Demotion(reason, detail, Instant.now()));
            while (history.size() > HISTORY_LIMIT) {
                history.removeFirst();
            }
        }
    }

    /** 강등 기록(최근 것부터). */
    public List<Demotion> demotions() {
        synchronized (history) {
            List<Demotion> out = new ArrayList<>(history);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    /** 관리 조회(API 설계 §6-C /actuator/partitions)의 한 행. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resource", resource);
        out.put("state", coordinator.state().name());
        out.put("epoch", lease.currentTerm().map(LeaderTerm::epoch).orElse(null));
        out.put("participate", coordinator.participating());
        out.put("preferred", preferred);
        out.put("armed", lease.isArmed());
        Map<String, Object> breakerView = new LinkedHashMap<>();
        breakerView.put("open", breaker.isOpen());
        breakerView.put("demotions_in_window", breaker.count());
        out.put("breaker", breakerView);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Demotion d : demotions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reason", d.reason().name());
            row.put("detail", d.detail());
            row.put("at", d.at().toString());
            recent.add(row);
        }
        out.put("demotions", recent);
        TreeSet<Integer> markets = new TreeSet<>();
        for (int marketId : router.markets()) {
            if (scope.test(marketId)) {
                markets.add(marketId);
            }
        }
        if (follower != null) {
            markets.addAll(follower.markets(resource));
        }
        out.put("markets", List.copyOf(markets));
        return out;
    }

    /** 재구성 대기 시간 계산을 시험이 관측할 때 쓴다. */
    Duration rebuildBackoff() {
        return Duration.ofMillis(rebuildBackoffMillis);
    }
}

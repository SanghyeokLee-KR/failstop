package kr.exchange.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailabilityBean;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 리스 기간과 승격 조율 상태의 경합(S4 설계 13절, DB 설계 §5.4-c 5·6). DB 없이 리스 틱 대신 tryAcquire 를 직접 부르고 승격
 * 워커를 손으로 돌린다.
 *
 * 승격 조율이 쓸 수 없는 상태(재구성·차단·참여 끔)와 엇갈려 얻은 기간은 곧바로 놓여야 한다. 쥔 채 두면 갱신만 하고 승격도
 * 접수도 하지 않는 반쪽 리더가 되어 그 파티션을 다른 노드도 얻지 못한다. 워커 줄에서 기다리다 자기 기간의 강등을 지난 승격은
 * 팔로워를 인계하지 않는다.
 */
class PartitionTermRaceTest {

    private static final String RESOURCE = "matching-engine";
    private static final Duration WAIT = Duration.ofSeconds(10);

    /** term.set 과 승격 조율의 획득 콜백 사이에 팔로워 갈림이 재구성을 시작한다. */
    @Test
    void termGainedWhileTheFollowerRebuildsIsReleasedAtOnce() throws Exception {
        try (Harness h = new Harness()) {
            h.recovery.holdNextRebuild();
            h.beforeCoordinator = term -> {
                h.runtime.onDiverged();
                Awaitility.await().atMost(WAIT)
                        .until(() -> h.coordinator.state() == PromotionCoordinator.State.REBUILDING);
            };
            assertTrue(h.lease.tryAcquire(), "보관소가 획득을 허락하지 않았다");
            h.beforeCoordinator = term -> { };

            assertFalse(h.lease.isLeader(), "재구성과 엇갈린 획득의 기간을 쥐고 있다(반쪽 리더)");
            assertFalse(h.lease.isArmed(), "재구성 중인 파티션이 후보다");
            assertEquals(PromotionCoordinator.State.REBUILDING, h.coordinator.state());
            assertEquals(0, h.worker.pending(), "재구성 중에 승격을 시작했다");
            assertEquals(0.0, h.isLeaderGauge(), "놓은 기간이 is_leader 게이지에 1 로 남았다");

            h.recovery.releaseRebuild();
            h.awaitCandidateFollower();
            assertPromotesOnNextAcquisition(h);
        }
    }

    /** 승격 재생 구간(설치 뒤 게이트 개방 전)에 그 기간의 라이터가 치명으로 끝난다. 재구성 중 틱이 돌아도 얻지 않는다. */
    @Test
    void demotionInsideThePromotionReplayWindowKeepsTheLeaseDisarmedUntilRebuilt() throws Exception {
        try (Harness h = new Harness()) {
            assertTrue(h.lease.tryAcquire());
            LeaderTerm term = h.lease.currentTerm().orElseThrow();
            assertEquals(PromotionCoordinator.State.PROMOTING, h.coordinator.state());
            long token = h.lease.beginPromotionReplay();
            h.recovery.holdNextRebuild();

            h.runtime.demote(term, DemotionReason.WRITER_FATAL, new IllegalStateException("주입: sink 실패"));
            assertEquals(1, h.recoverableStandDowns.get(), "재생 구간의 상실이 회복 가능 반납으로 가지 않았다(장면 전제)");
            assertFalse(h.lease.isLeader(), "강등이 기간을 놓지 않았다");
            assertFalse(h.lease.isArmed(), "재생 구간과 겹친 강등 뒤 재구성 중인 파티션이 후보다");
            h.lease.endPromotionReplay(token);

            assertFalse(h.lease.tryAcquire(), "재구성 중인 파티션이 리스를 얻었다");
            assertFalse(h.lease.isLeader());
            assertEquals(PromotionCoordinator.State.REBUILDING, h.coordinator.state());

            h.recovery.releaseRebuild();
            h.awaitCandidateFollower();
            // 줄에는 강등된 기간의 승격이 남아 있다. 다음 획득의 승격만 돈다.
            assertPromotesOnNextAcquisition(h);
        }
    }

    /** 참여를 끄는 순간과 엇갈린 획득. 후보 표식이 내려간 뒤 받은 기간으로 승격하지 않는다. */
    @Test
    void termGainedAfterParticipationWasTurnedOffIsReleased() throws Exception {
        try (Harness h = new Harness()) {
            h.beforeCoordinator = term -> h.runtime.setParticipate(false);
            assertTrue(h.lease.tryAcquire());
            h.beforeCoordinator = term -> { };

            assertFalse(h.lease.isLeader(), "참여를 끈 파티션이 받은 기간을 쥐고 있다");
            assertEquals(PromotionCoordinator.State.FOLLOWER, h.coordinator.state());
            assertEquals(0, h.worker.pending(), "참여를 끈 파티션이 승격을 시작했다");
            assertFalse(h.lease.isArmed());

            h.runtime.setParticipate(true);
            assertTrue(h.lease.isArmed(), "참여를 다시 켠 팔로워가 후보가 아니다");
            assertPromotesOnNextAcquisition(h);
        }
    }

    /** 워커 줄에서 기다린 승격이 자기 기간의 강등과 재구성을 지나 늦게 돈다. 팔로워를 인계하지 않고 설치하지 않는다. */
    @Test
    void aPromotionThatWaitedPastItsDemotionDoesNotHandTheFollowerOver() throws Exception {
        try (Harness h = new Harness()) {
            assertTrue(h.lease.tryAcquire());
            LeaderTerm term = h.lease.currentTerm().orElseThrow();
            assertEquals(1, h.worker.pending(), "승격이 줄에 서지 않았다");

            h.runtime.demote(term, DemotionReason.WRITER_FATAL, new IllegalStateException("주입: sink 실패"));
            h.awaitCandidateFollower();
            assertEquals(1, h.follower.state(RESOURCE), "재구성 뒤 팔로워가 따라잡는 중이 아니다(장면 전제)");

            h.worker.runAll();
            assertEquals(1, h.follower.state(RESOURCE), "늦은 승격이 재구성을 마친 팔로워를 인계 상태로 멈췄다");
            assertEquals(PromotionCoordinator.State.FOLLOWER, h.coordinator.state());
            assertNull(h.coordinator.installedEngine(), "늦은 승격이 엔진을 설치했다");
            assertTrue(h.lease.isArmed());
        }
    }

    private static void assertPromotesOnNextAcquisition(Harness h) throws Exception {
        assertTrue(h.lease.tryAcquire(), "후보로 돌아온 파티션이 리스를 얻지 못했다");
        LeaderTerm next = h.lease.currentTerm().orElseThrow();
        assertEquals(PromotionCoordinator.State.PROMOTING, h.coordinator.state());
        h.worker.runAll();
        assertEquals(PromotionCoordinator.State.ACCEPTING, h.coordinator.state());
        assertSame(next, h.coordinator.openTerm(), "게이트가 새 기간으로 열리지 않았다");
        assertEquals(2, h.follower.state(RESOURCE), "승격한 파티션의 팔로워가 쉬는 중이 아니다");
        assertEquals(1.0, h.isLeaderGauge());
    }

    /** 파티션 하나의 수명주기. 설치할 마켓이 없는 빈 저널이라 승격은 인계 엔진을 그대로 설치하고 게이트를 연다. */
    private static final class Harness implements AutoCloseable {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final LeaderLeaseService lease;
        final FollowerCatchUp follower;
        final HeldRecovery recovery = new HeldRecovery();
        final ManualWorker worker = new ManualWorker();
        final PromotionCoordinator coordinator;
        final PartitionRuntime runtime;
        final AtomicInteger recoverableStandDowns = new AtomicInteger();
        volatile Consumer<LeaderTerm> beforeCoordinator = term -> { };

        Harness() {
            PartitionPlan plan = PartitionPlan.single();
            lease = new LeaderLeaseService(RESOURCE, "node-a", new GrantingStore(), FencingEpochPublisher.NONE,
                    Duration.ofSeconds(10), Duration.ofSeconds(3), NodeServiceHealth.ALWAYS_SERVICEABLE,
                    PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress());
            // 승격 조율의 콜백보다 먼저 등록한다. 기간이 세워진 뒤 승격 조율이 받기 전의 틈이 여기다.
            lease.onTermGained(term -> beforeCoordinator.accept(term));
            lease.onRecoverableStandDown(recoverableStandDowns::incrementAndGet);
            PartitionLeases leases = new PartitionLeases(plan, "node-a", resource -> lease);
            follower = new FollowerCatchUp(new ReplayService(null, null, null, null, null), plan, Map.of(),
                    Duration.ofHours(1), registry);
            WriterRouter router = new WriterRouter((marketId, engine, term, slot) -> {
                throw new IllegalStateException("이 시험에는 설치할 마켓이 없다");
            });
            HaAvailability ha = new HaAvailability(event -> { }, new ApplicationAvailabilityBean(), registry,
                    "node-a", plan.partitions());
            coordinator = new PromotionCoordinator(RESOURCE, plan.scope(RESOURCE), recovery, router, lease,
                    new EmptyJournal(), ha.partition(RESOURCE), follower, worker);
            runtime = new PartitionRuntime(RESOURCE, plan.scope(RESOURCE), lease, coordinator, router, follower,
                    recovery, new PartitionBreaker(3, Duration.ofMinutes(10)), ha.partition(RESOURCE), leases,
                    true, false, registry);
        }

        void awaitCandidateFollower() {
            Awaitility.await().atMost(WAIT).until(() ->
                    coordinator.state() == PromotionCoordinator.State.FOLLOWER && lease.isArmed());
        }

        double isLeaderGauge() {
            return registry.get("exchange_leader_is_leader").tag("partition", RESOURCE).gauge().value();
        }

        @Override
        public void close() {
            recovery.releaseRebuild();
            runtime.stop();
        }
    }

    /** 획득을 늘 허락하는 보관소. 획득마다 새 epoch 이고 갱신은 마지막 epoch 에만 참이다. */
    private static final class GrantingStore extends TestPartitions.UnreachableStore {
        private final AtomicLong epochs = new AtomicLong(10);
        private volatile long current;

        @Override
        public Optional<Long> acquire(String holder, Duration ttl) {
            current = epochs.incrementAndGet();
            return Optional.of(current);
        }

        @Override
        public boolean renew(String holder, long epoch, Duration ttl) {
            return epoch == current;
        }
    }

    /** 명령이 없는 저널. */
    private static final class EmptyJournal extends CommandJournalReader {
        EmptyJournal() {
            super(null);
        }

        @Override
        public Map<Integer, Long> maxSeqByMarket() {
            return Map.of();
        }
    }

    /** 재구성의 팔로워 복구를 시험이 풀 때까지 붙잡는 복구. 크래시 창은 없다. */
    private static final class HeldRecovery extends EngineRecovery {
        private volatile CountDownLatch hold;

        HeldRecovery() {
            super(null, (term, events) -> {
                throw new IllegalStateException("재적재할 크래시 창이 없다");
            }, null, n -> { });
        }

        void holdNextRebuild() {
            hold = new CountDownLatch(1);
        }

        void releaseRebuild() {
            CountDownLatch gate = hold;
            hold = null;
            if (gate != null) {
                gate.countDown();
            }
        }

        @Override
        public ReplayResult recoverFollower(MatchingEngineImpl engine, IntPredicate scope) {
            CountDownLatch gate = hold;
            if (gate != null) {
                try {
                    gate.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ReplayResult(0, 0, 0, List.of());
        }
    }

    /** 시험이 손으로 돌리는 승격 워커. 넘겨받은 작업을 줄에만 세운다. */
    private static final class ManualWorker extends AbstractExecutorService {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        synchronized int pending() {
            return queue.size();
        }

        void runAll() throws Exception {
            Runnable task;
            while ((task = next()) != null) {
                task.run();
                if (task instanceof Future<?> future) {
                    // 승격 경로의 예외를 삼키지 않고 시험으로 올린다.
                    future.get();
                }
            }
        }

        private synchronized Runnable next() {
            return queue.pollFirst();
        }

        @Override
        public synchronized void execute(Runnable command) {
            queue.addLast(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}

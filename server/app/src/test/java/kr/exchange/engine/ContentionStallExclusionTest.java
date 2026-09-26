package kr.exchange.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.ReserveCheck;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 좀비 판정의 경합 수요 제외(DB 설계 §5.4-c 8, S4 설계 4-11). 마켓 접수 경합으로 끝난 요청은 두 층 모두 수요에서 뺀다. 접수
 * 락을 쥔 요청이 대기 상한보다 오래 쥐고 있어서 난 대기 초과만 수요로 남긴다.
 */
class ContentionStallExclusionTest {

    private static final Duration STANDDOWN_AFTER = Duration.ofSeconds(45);

    /**
     * (a) 저트래픽에서 한 계정의 잔고 행이 잠겨 그 계정 주문이 1초마다 경합으로 끝난다. 두 층 모두 70초 동안 강등하지 않는다.
     * 대조군은 경합을 수요로 세는 판정이고 45초 뒤 불능이다.
     */
    @Test
    void contentionOnlyTrafficNeverStallsEitherLayer() {
        AtomicLong clock = new AtomicLong();
        LeaderWriteProgress nodeProgress = new LeaderWriteProgress();
        LeaderWriteProgress partitionProgress = new LeaderWriteProgress();
        LeaderWriteProgress controlProgress = new LeaderWriteProgress();
        LeaderStallDetector node = LeaderStallDetector.node(null, nodeProgress, STANDDOWN_AFTER,
                new SimpleMeterRegistry(), clock::get);
        LeaderStallDetector partition = LeaderStallDetector.partition("matching-engine", partitionProgress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);
        LeaderStallDetector control = LeaderStallDetector.node(null, controlProgress, STANDDOWN_AFTER,
                new SimpleMeterRegistry(), clock::get);
        node.onLeadershipGained();
        partition.onLeadershipGained();
        control.onLeadershipGained();

        boolean controlStalled = false;
        long controlStalledAt = -1;
        for (int second = 1; second <= 70; second++) {
            clock.set(Duration.ofSeconds(second).toNanos());
            // 요청 하나가 인증 필터와 접수 게이트에서 수요로 세어지고 경합 응답(429)으로 끝난다.
            nodeProgress.recordAttempt();
            partitionProgress.recordAttempt();
            nodeProgress.recordExcluded();
            partitionProgress.recordExcluded();
            controlProgress.recordAttempt();
            if (second % 2 == 0) {
                assertTrue(node.serviceable(), "노드 층이 경합만으로 강등했다: " + second + "초");
                assertTrue(partition.serviceable(), "파티션 층이 경합만으로 강등했다: " + second + "초");
                if (!controlStalled && !control.serviceable()) {
                    controlStalled = true;
                    controlStalledAt = second;
                }
            }
        }
        assertTrue(controlStalled, "대조군(경합을 수요로 셈)이 강등하지 않아 판정이 공허하다");
        assertTrue(controlStalledAt >= 45 && controlStalledAt <= 70, "대조군 강등 시각: " + controlStalledAt);
    }

    /**
     * (b) 접수 락을 쥔 요청이 멈춰 대기자가 대기 초과로 끝나면 수요로 남는다(보유가 대기 상한보다 길다). (c) 짧은 보유가 이어진
     * 줄 머리 막힘의 대기 초과는 수요에서 빠진다.
     */
    @Test
    void stuckHolderTimeoutsCountAsDemandButShortHoldQueueTimeoutsDoNot() throws Exception {
        LeaderWriteProgress partitionProgress = new LeaderWriteProgress();
        LeaderTerm term = term(partitionProgress);
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch secondIn = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CommandJournalAppender appender = new BlockingAppender(holderIn, releaseHolder, secondIn, releaseSecond);
        EngineBridge bridge = new EngineBridge(appender, new OpenSink(), new CommandPayloadMapper(
                new com.fasterxml.jackson.databind.ObjectMapper()), new FixedGate(term), EngineBridge.NEVER_HALTED);
        bridge.setAcceptLockWait(Duration.ofMillis(1000));
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            // (b) 보유자 A 가 락을 쥔 채 멈춘다. 대기자 W 는 1초 뒤 대기 초과로 끝나고 그때 A 의 보유는 1.3초를 넘는다.
            Future<SubmitResult> holder = pool.submit(() -> bridge.submit(intake(1)));
            assertTrue(holderIn.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            long attemptsBefore = partitionProgress.attempts();
            long excludedBefore = partitionProgress.excluded();
            EngineBridge.AcceptBusyException stuck = assertThrows(EngineBridge.AcceptBusyException.class,
                    () -> bridge.submit(intake(2)));
            assertTrue(stuck.stuckHolder(), "멈춘 보유자를 표시하지 않았다");
            assertEquals(attemptsBefore + 1, partitionProgress.attempts(), "대기자의 수요를 세지 않았다");
            assertEquals(excludedBefore, partitionProgress.excluded(), "멈춘 보유자 때문의 대기 초과를 수요에서 뺐다");

            // (c) 줄 머리 막힘. C 가 먼저 줄을 서고 W2 가 뒤에 선다. A 를 풀면 C 가 잡는다. W2 가 대기 초과로 끝날 때 C 의 보유는
            // 약 0.6초로 대기 상한(1초)보다 짧다.
            Future<SubmitResult> second = pool.submit(() -> bridge.submit(intake(3)));
            Thread.sleep(400);
            Future<Throwable> waiter = pool.submit(() -> {
                try {
                    bridge.submit(intake(4));
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Thread.sleep(400);
            releaseHolder.countDown();
            assertTrue(secondIn.await(5, TimeUnit.SECONDS), "줄 선 요청이 락을 잡지 못했다");
            long excludedMid = partitionProgress.excluded();
            Throwable queued = waiter.get(5, TimeUnit.SECONDS);
            assertTrue(queued instanceof EngineBridge.AcceptBusyException, "줄 뒤 요청이 대기 초과로 끝나지 않았다: " + queued);
            assertFalse(((EngineBridge.AcceptBusyException) queued).stuckHolder(), "짧은 보유를 멈춘 보유자로 보았다");
            assertEquals(excludedMid + 1, partitionProgress.excluded(), "줄 머리 막힘의 대기 초과를 수요에서 빼지 않았다");
            releaseSecond.countDown();
            holder.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseHolder.countDown();
            releaseSecond.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * (b) 판정. 멈춘 보유자 때문의 대기 초과가 수요 하한(20건)을 넘기면 파티션 층이 강등 기준(45초) 뒤 불능으로 판정한다.
     * 대조군은 같은 대기 초과를 모두 수요에서 뺀 판정이고, 멈춘 보유자를 끝내 놓친다.
     */
    @Test
    void stuckHolderTimeoutsStallThePartitionLayerAfterTheStanddownWindow() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LeaderWriteProgress partitionProgress = new LeaderWriteProgress();
        LeaderWriteProgress excludeAll = new LeaderWriteProgress();
        LeaderStallDetector partition = LeaderStallDetector.partition("matching-engine", partitionProgress,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);
        LeaderStallDetector control = LeaderStallDetector.partition("matching-engine", excludeAll,
                STANDDOWN_AFTER, new SimpleMeterRegistry(), clock::get);
        partition.onLeadershipGained();
        control.onLeadershipGained();
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        EngineBridge bridge = new EngineBridge(new BlockingAppender(holderIn, releaseHolder, new CountDownLatch(1),
                new CountDownLatch(0)), new OpenSink(), new CommandPayloadMapper(
                new com.fasterxml.jackson.databind.ObjectMapper()), new FixedGate(term(partitionProgress)),
                EngineBridge.NEVER_HALTED);
        bridge.setAcceptLockWait(Duration.ofMillis(1000));
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Future<SubmitResult> holder = pool.submit(() -> bridge.submit(intake(1)));
            assertTrue(holderIn.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            List<Future<Throwable>> waiters = new ArrayList<>();
            for (int i = 0; i < 21; i++) {
                long orderId = 100 + i;
                waiters.add(pool.submit(() -> {
                    try {
                        bridge.submit(intake(orderId));
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            for (Future<Throwable> waiter : waiters) {
                Throwable timedOut = waiter.get(10, TimeUnit.SECONDS);
                assertTrue(timedOut instanceof EngineBridge.AcceptBusyException busy && busy.stuckHolder(),
                        "멈춘 보유자 뒤의 대기 초과가 아니다: " + timedOut);
                excludeAll.recordAttempt();
                excludeAll.recordExcluded();
            }
            excludeAll.recordAttempt();
            assertEquals(22, partitionProgress.attempts(), "보유자와 대기자 21건의 수요");
            assertEquals(0, partitionProgress.excluded(), "멈춘 보유자 때문의 대기 초과를 수요에서 뺐다");

            assertTrue(partition.serviceable(), "붕괴 관측 첫 표본에서 바로 강등했다");
            assertTrue(control.serviceable());
            clock.addAndGet(STANDDOWN_AFTER.plusSeconds(1).toNanos());
            assertFalse(partition.serviceable(), "멈춘 보유자를 강등 기준 뒤에도 판정하지 않았다");
            assertTrue(control.serviceable(), "대조군(대기 초과를 모두 뺌)이 판정했다. 장면이 제외 규칙을 가르지 못한다");
            releaseHolder.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            releaseHolder.countDown();
            pool.shutdownNow();
        }
    }

    /** append 트랜잭션이 55P03 으로 롤백된 접수는 파티션 층 수요에서 빠진다. 경합이 아닌 실패는 남는다. */
    @Test
    void appendLockTimeoutIsExcludedButOtherFailuresRemainDemand() {
        LeaderWriteProgress partitionProgress = new LeaderWriteProgress();
        LeaderTerm term = term(partitionProgress);
        EngineBridge contention = new EngineBridge(new FailingAppender(new CannotAcquireLockException("lock timeout",
                new SQLException("canceling statement due to lock timeout", "55P03"))), new OpenSink(),
                new CommandPayloadMapper(new com.fasterxml.jackson.databind.ObjectMapper()), new FixedGate(term),
                EngineBridge.NEVER_HALTED);
        assertThrows(CannotAcquireLockException.class, () -> contention.submit(intake(1)));
        assertEquals(1, partitionProgress.attempts());
        assertEquals(1, partitionProgress.excluded(), "55P03 경합을 수요에서 빼지 않았다");

        EngineBridge broken = new EngineBridge(new FailingAppender(new org.springframework.dao.DataAccessResourceFailureException(
                "connection refused")), new OpenSink(),
                new CommandPayloadMapper(new com.fasterxml.jackson.databind.ObjectMapper()), new FixedGate(term),
                EngineBridge.NEVER_HALTED);
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> broken.submit(intake(2)));
        assertEquals(2, partitionProgress.attempts());
        assertEquals(1, partitionProgress.excluded(), "경합이 아닌 실패를 수요에서 뺐다. 그 파티션의 좀비를 놓친다");
    }

    private static LeaderTerm term(LeaderWriteProgress partitionProgress) {
        LeaderLeaseService lease = new LeaderLeaseService("matching-engine", "node-a",
                new TestPartitions.UnreachableStore(), kr.exchange.engine.lease.FencingEpochPublisher.NONE,
                Duration.ofSeconds(10), Duration.ofSeconds(3), NodeServiceHealth.ALWAYS_SERVICEABLE,
                PartitionAcquirePolicy.ALWAYS, partitionProgress);
        return new LeaderTerm(lease, "matching-engine", "node-a", 5, 1);
    }

    private static OrderIntake intake(long orderId) {
        String coid = "contention-" + orderId;
        return new OrderIntake(1, CommandDraft.CommandType.NEW, 7, coid, orderId, 1_790_000_000_000L + orderId,
                orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT, OrderCommand.TimeInForce.GTC,
                new BigDecimal("100"), new BigDecimal("1"), null, UUID.nameUUIDFromBytes(coid.getBytes()), null, null);
    }

    /** 기간이 늘 열려 있는 게이트. */
    private record FixedGate(LeaderTerm term) implements AcceptGate {
        @Override
        public boolean isAccepting() {
            return true;
        }

        @Override
        public boolean termBound() {
            return true;
        }

        @Override
        public LeaderTerm openTerm(int marketId) {
            return term;
        }
    }

    /** 늘 받는 라이터. */
    private static final class OpenSink implements CommandSink {
        @Override
        public boolean offer(OrderCommand command) {
            return true;
        }

        @Override
        public boolean isRunning(int marketId) {
            return true;
        }
    }

    /** 늘 실패하는 append. */
    private static final class FailingAppender extends CommandJournalAppender {
        private final RuntimeException failure;

        FailingAppender(RuntimeException failure) {
            super(null, null, null, new SimpleMeterRegistry(), new LeaderWriteProgress());
            this.failure = failure;
        }

        @Override
        public AppendResult append(LeaderTerm term, CommandDraft draft, ReserveCheck check) {
            throw failure;
        }
    }

    /** 첫 요청(보유자)과 셋째 요청(줄 선 요청)을 append 안에서 멈춰 둔다. */
    private static final class BlockingAppender extends CommandJournalAppender {
        private final CountDownLatch holderIn;
        private final CountDownLatch releaseHolder;
        private final CountDownLatch secondIn;
        private final CountDownLatch releaseSecond;

        BlockingAppender(CountDownLatch holderIn, CountDownLatch releaseHolder, CountDownLatch secondIn,
                         CountDownLatch releaseSecond) {
            super(null, null, null, new SimpleMeterRegistry(), new LeaderWriteProgress());
            this.holderIn = holderIn;
            this.releaseHolder = releaseHolder;
            this.secondIn = secondIn;
            this.releaseSecond = releaseSecond;
        }

        @Override
        public AppendResult append(LeaderTerm term, CommandDraft draft, ReserveCheck check) {
            try {
                if (draft.assignedOrderId() == 1) {
                    holderIn.countDown();
                    releaseHolder.await(10, TimeUnit.SECONDS);
                } else if (draft.assignedOrderId() == 3) {
                    secondIn.countDown();
                    releaseSecond.await(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AppendResult(draft.assignedOrderId(), term.epoch(), false, draft.assignedOrderId(), false);
        }
    }
}

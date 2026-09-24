package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.ReserveCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 마켓 접수 락 대기 상한(ADR 0034 S1). 앞 요청의 append 가 멈춰도 뒤 요청은 상한에서 저널 전에 끝나고,
 * 다른 마켓의 접수는 그 줄에 서지 않는다.
 */
class EngineBridgeAcceptLockWaitTest {

    private record Journaled(int marketId, long orderId) {
    }

    private final List<Journaled> journaled = new CopyOnWriteArrayList<>();
    private final CountDownLatch holding = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    // 주문 1 의 append 가 DB 에서 멈춘 것처럼 마켓 락을 쥔 채 기다린다. seq 는 마켓 안에서 1부터 잇는다.
    private final CommandJournalAppender appender = new CommandJournalAppender(null, null, null) {
        @Override
        public AppendResult append(CommandDraft draft, ReserveCheck check) {
            if (draft.assignedOrderId() == 1) {
                holding.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            journaled.add(new Journaled(draft.marketId(), draft.assignedOrderId()));
            long seq = journaled.stream().filter(j -> j.marketId() == draft.marketId()).count();
            return new AppendResult(seq, 1, false, draft.assignedOrderId());
        }
    };

    private final WriterRouter router = new WriterRouter(marketId -> {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        return new WriterRouter.Shard(engine, new SingleWriterLoop(16, engine, events -> {
        }, (afterSeq, limit) -> List.of(), fatal -> {
        }));
    });

    private final EngineBridge bridge = new EngineBridge(appender, router,
            new CommandPayloadMapper(new ObjectMapper()), AcceptGate.ALWAYS_OPEN, EngineBridge.NEVER_HALTED);

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        pool.shutdownNow();
        router.stopAll();
    }

    @Test
    void waiterPastBoundIsRejectedBeforeJournalAndOtherMarketProceeds() throws Exception {
        bridge.setAcceptLockWait(Duration.ofMillis(200));
        router.startAll();
        router.shard(1);
        router.shard(2);

        Future<SubmitResult> head = pool.submit(() -> bridge.submit(order(1, 1)));
        assertTrue(holding.await(5, TimeUnit.SECONDS), "앞 요청이 락 안에 들어가지 못했다");

        long t0 = System.nanoTime();
        EngineBridge.AcceptBusyException busy = assertThrows(EngineBridge.AcceptBusyException.class,
                () -> bridge.submit(order(1, 2)));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(waitedMs >= 150 && waitedMs < 3_000, "상한 200ms 대기가 아니다: " + waitedMs + "ms");
        assertTrue(busy.getMessage().contains("market=1"), busy.getMessage());

        SubmitResult other = bridge.submit(order(2, 3));
        assertTrue(other.queued(), "다른 마켓의 접수가 막힌 마켓의 줄에 섰다");

        release.countDown();
        assertFalse(head.get(5, TimeUnit.SECONDS).replayed());
        assertEquals(List.of(new Journaled(2, 3), new Journaled(1, 1)), journaled,
                "상한을 넘긴 접수가 저널에 닿았다");
        assertEquals(1, bridge.lockTimeoutCount(1));
        assertEquals(0, bridge.lockTimeoutCount(2));
        assertEquals(2, bridge.submitCount(1), "상한 초과도 접수 시도로 센다");
    }

    @Test
    void withoutBoundWaiterQueuesBehindHolder() throws Exception {
        router.startAll();
        router.shard(1);

        Future<SubmitResult> head = pool.submit(() -> bridge.submit(order(1, 1)));
        assertTrue(holding.await(5, TimeUnit.SECONDS), "앞 요청이 락 안에 들어가지 못했다");

        ExecutorService second = Executors.newSingleThreadExecutor();
        try {
            Future<SubmitResult> waiter = second.submit(() -> bridge.submit(order(1, 2)));
            Thread.sleep(300);
            assertFalse(waiter.isDone(), "상한이 없는데 뒤 요청이 앞 요청을 기다리지 않았다");
            release.countDown();
            head.get(5, TimeUnit.SECONDS);
            assertFalse(waiter.get(5, TimeUnit.SECONDS).replayed());
        } finally {
            second.shutdownNow();
        }
        assertEquals(List.of(new Journaled(1, 1), new Journaled(1, 2)), journaled);
        assertEquals(0, bridge.lockTimeoutCount(1));
    }

    private static OrderIntake order(int marketId, long orderId) {
        String clientOrderId = "wait-" + orderId;
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, 1L, clientOrderId, orderId,
                1_753_000_000_000L + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("100"), new BigDecimal("1"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes(StandardCharsets.UTF_8)), null, null);
    }
}

package kr.exchange.engine.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 단일 라이터 루프의 seq 연속성 계약 단위 검증: 갭 감지 시 catch-up 회수, 회수 불가·
 * sink 실패 시 fail-fast 정지, 중복 seq 무시.
 */
class SingleWriterLoopTest {

    private static final long BASE_TS = 1_721_600_000_000L;

    @Test
    void gapTriggersCatchUpFromJournalSource() throws Exception {
        List<OrderCommand> journal = List.of(
                limitBuy(1, 1), limitBuy(2, 2), limitBuy(3, 3), limitBuy(4, 4), limitBuy(5, 5));
        MatchingEngineImpl engine = new MatchingEngineImpl();
        List<EngineEvent> sunk = new CopyOnWriteArrayList<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                sunk::addAll,
                (lastApplied, limit) -> journal.stream()
                        .filter(c -> c.seq() > lastApplied).limit(limit).toList(),
                t -> { });
        loop.start();
        try {
            // seq 1~4를 건너뛰고 5만 투입한다. 연속성 검증이 갭을 감지해 저널에서 회수해야 한다.
            assertTrue(loop.offer(journal.get(4)));
            await(() -> engine.lastAppliedSeq() == 5);
            assertEquals(5, sunk.stream()
                    .filter(e -> e.type() == EngineEvent.EventType.ACCEPTED).count());

            // 이미 적용된 seq 재공급은 무시된다(이중 적용 방지).
            int before = sunk.size();
            assertTrue(loop.offer(journal.get(2)));
            Thread.sleep(200);
            assertEquals(before, sunk.size());
            assertEquals(5, engine.lastAppliedSeq());
        } finally {
            loop.stop();
        }
    }

    @Test
    void unresolvableGapStopsLoop() {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { },
                (lastApplied, limit) -> List.of(),
                fatal::set);
        loop.start();
        assertTrue(loop.offer(limitBuy(7, 7)));
        await(() -> !loop.isRunning());
        assertInstanceOf(IllegalStateException.class, fatal.get());
        assertEquals(0, engine.lastAppliedSeq());
    }

    @Test
    void sinkFailureStopsLoopAndClearsQueue() {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> {
                    throw new IllegalStateException("event append 거부");
                },
                (lastApplied, limit) -> List.of(),
                fatal::set);
        // 정지 후 큐 폐기 판정이 결정적이도록 투입을 끝낸 뒤 루프를 연다.
        assertTrue(loop.offer(limitBuy(1, 1)));
        assertTrue(loop.offer(limitBuy(2, 2)));
        loop.start();
        await(() -> !loop.isRunning());
        assertInstanceOf(IllegalStateException.class, fatal.get());
        assertEquals(0, loop.queueSize(), "fail-fast 후 잔여 큐는 폐기돼야 한다");
    }

    @Test
    void errorInProcessNotifiesFatalHandlerAndStops() {
        // sink가 Error를 던지면 라이터 스레드가 조용히 죽지 않고 fatalHandler 통지 후 정지해야 한다.
        MatchingEngineImpl engine = new MatchingEngineImpl();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> {
                    throw new AssertionError("치명 오류 시뮬레이션");
                },
                (lastApplied, limit) -> List.of(),
                fatal::set);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { });
        try {
            assertTrue(loop.offer(limitBuy(1, 1)));
            loop.start();
            await(() -> !loop.isRunning());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(null);
        }
        assertInstanceOf(Error.class, fatal.get(), "Error가 fatalHandler로 통지돼야 한다");
        assertFalse(loop.isRunning(), "Error 후 running=false여야 한다");
        assertEquals(0, loop.queueSize());
    }

    @Test
    void haltStopsWithoutDrainingQueue() throws Exception {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { }, (lastApplied, limit) -> List.of(), t -> { });
        // 시작 전 투입분은 halt가 소진 없이 버려야 한다.
        assertTrue(loop.offer(limitBuy(1, 1)));
        loop.halt();
        assertFalse(loop.isRunning());
        assertEquals(0, loop.queueSize());
        assertEquals(0, engine.lastAppliedSeq());
    }

    private static OrderCommand limitBuy(long seq, long orderId) {
        return new OrderCommand(seq, 1, OrderCommand.CommandType.NEW, 1, "c-" + orderId, orderId,
                BASE_TS + seq, seq, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("100"), new BigDecimal("1"), null, 1);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("5초 내 조건 미충족");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기 중 인터럽트", e);
            }
        }
    }
}

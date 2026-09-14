package kr.exchange.engine.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                (lastApplied, limit) -> {
                    // 왕복 시간 단언이 타이머 해상도에 기대지 않게 조회에 시간을 쓴다.
                    pause(1);
                    return journal.stream()
                            .filter(c -> c.seq() > lastApplied).limit(limit).toList();
                },
                t -> { });
        loop.start();
        try {
            // seq 1~4를 건너뛰고 5만 투입한다. 연속성 검증이 갭을 감지해 저널에서 회수해야 한다.
            assertTrue(loop.offer(journal.get(4)));
            await(() -> engine.lastAppliedSeq() == 5);
            assertEquals(5, sunk.stream()
                    .filter(e -> e.type() == EngineEvent.EventType.ACCEPTED).count());
            assertEquals(1, loop.catchUpCalls(), "갭 하나는 저널 왕복 한 번으로 메운다");
            assertTrue(loop.catchUpNanos() > 0, "저널 왕복 시간이 잡혀야 한다");

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
    void denseArrivalsDoNotCatchUp() throws Exception {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { },
                (lastApplied, limit) -> List.of(),
                fatal::set);
        assertTrue(loop.offer(limitBuy(1, 1)));
        assertTrue(loop.offer(limitBuy(2, 2)));
        assertTrue(loop.offer(limitBuy(3, 3)));
        loop.start();
        try {
            await(() -> loop.applyCount() == 3);
            assertEquals(3, engine.lastAppliedSeq());
            assertEquals(0, loop.catchUpCalls(), "연속 seq 는 저널을 왕복하지 않는다");
            assertNull(fatal.get());
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
    void errorInProcessNotifiesFatalHandlerAndStops() throws Exception {
        // sink가 Error를 던지면 라이터 스레드가 조용히 죽지 않고 fatalHandler 통지 후 정지해야 한다.
        MatchingEngineImpl engine = new MatchingEngineImpl();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> {
                    throw new AssertionError("치명 오류 시뮬레이션");
                },
                (lastApplied, limit) -> List.of(),
                fatal::set);
        // 재던진 Error를 워커 스레드 전용 핸들러로 흡수한다. JVM 기본 핸들러를 건드리지 않아
        // 다른 테스트와의 전역 상태 경합을 없앤다. 관측용으로 uncaught도 별도 캡처한다.
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        loop.setWriterUncaughtExceptionHandler((t, e) -> uncaught.set(e));
        assertTrue(loop.offer(limitBuy(1, 1)));
        loop.start();
        // rethrow가 uncaughtExceptionHandler로 전파 완료될 때까지 결정적으로 대기한다.
        loop.awaitWriterTermination(5_000L);
        assertInstanceOf(Error.class, fatal.get(), "Error가 fatalHandler로 통지돼야 한다");
        assertInstanceOf(Error.class, uncaught.get(), "재던진 Error가 워커 핸들러로 전파돼야 한다");
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

    @Test
    void legacyToBandJumpCatchesUpOnceWithoutFatal() throws Exception {
        long bandSpan = 1_000_000_000_000L;
        OrderCommand legacy = limitBuy(41, 1);
        OrderCommand band = limitBuy(bandSpan + 1, 2);
        List<OrderCommand> journal = List.of(legacy, band);
        MatchingEngineImpl engine = new MatchingEngineImpl();
        engine.apply(legacy);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { },
                (lastApplied, limit) -> journal.stream()
                        .filter(c -> c.seq() > lastApplied).limit(limit).toList(),
                fatal::set);
        loop.start();
        try {
            assertTrue(loop.offer(band));
            await(() -> loop.applyCount() == 1);
            assertEquals(band.seq(), engine.appliedSeq(1));
            assertEquals(1, loop.catchUpCalls(), "대역 첫 명령은 저널 왕복 한 번으로 넘어간다");
            assertNull(fatal.get());
            assertTrue(loop.isRunning());
        } finally {
            loop.stop();
        }
    }

    @Test
    void otherMarketLowerSeqPassesDuplicateCheck() throws Exception {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        engine.apply(limitBuy(10, 1, 1));
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { },
                (lastApplied, limit) -> List.of(),
                fatal::set);
        loop.start();
        try {
            assertTrue(loop.offer(limitBuy(1, 2, 2)));
            await(() -> loop.applyCount() == 1);
            assertEquals(1, engine.appliedSeq(2));
            assertEquals(10, engine.appliedSeq(1));
            assertEquals(0, loop.catchUpCalls());
            assertNull(fatal.get());
        } finally {
            loop.stop();
        }
    }

    @Test
    void bandGapInLowerMarketOfMultiMarketEngineCatchesUp() throws Exception {
        long bandSpan = 1_000_000_000_000L;
        List<OrderCommand> journal = List.of(
                limitBuy(bandSpan + 5, 1, 1), limitBuy(bandSpan + 6, 1, 2), limitBuy(bandSpan + 7, 1, 3),
                limitBuy(2 * bandSpan + 5, 2, 4));
        MatchingEngineImpl engine = new MatchingEngineImpl();
        engine.apply(journal.get(0));
        engine.apply(journal.get(3));
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { },
                (afterSeq, limit) -> journal.stream()
                        .filter(c -> c.seq() > afterSeq).limit(limit).toList(),
                fatal::set);
        loop.start();
        try {
            assertTrue(loop.offer(journal.get(2)));
            await(() -> loop.applyCount() == 2);
            assertEquals(bandSpan + 7, engine.appliedSeq(1));
            assertEquals(3, engine.orderBook(1).openOrderCount(), "market 1 의 누락 seq 가 회수되지 않았다");
            assertEquals(1, loop.catchUpCalls());
            assertNull(fatal.get());
        } finally {
            loop.stop();
        }
    }

    @Test
    void catchUpFromMarketCursorAdvancesPastAppliedRowsOfOtherMarket() throws Exception {
        long bandSpan = 1_000_000_000_000L;
        List<OrderCommand> journal = new ArrayList<>();
        journal.add(limitBuy(1, 1, 1));
        // 회수 배치 상한(500)을 넘겨 이미 적용한 남의 마켓 행만 든 배치가 나오게 한다.
        for (long seq = 2; seq <= 601; seq++) {
            journal.add(limitBuy(seq, 2, seq));
        }
        journal.add(limitBuy(bandSpan + 1, 1, 1001));
        journal.add(limitBuy(bandSpan + 2, 1, 1002));
        journal.add(limitBuy(3 * bandSpan + 5, 3, 1003));
        MatchingEngineImpl engine = new MatchingEngineImpl();
        journal.subList(0, 601).forEach(engine::apply);
        engine.apply(journal.get(603));
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { },
                (afterSeq, limit) -> journal.stream()
                        .filter(c -> c.seq() > afterSeq).limit(limit).toList(),
                fatal::set);
        loop.start();
        try {
            assertTrue(loop.offer(journal.get(602)));
            await(() -> engine.appliedSeq(1) == bandSpan + 2);
            assertEquals(3, engine.orderBook(1).openOrderCount(), "market 1 의 누락 seq 가 회수되지 않았다");
            assertEquals(2, loop.catchUpCalls(), "남의 마켓 행만 든 배치 뒤로 읽기 위치가 전진해야 한다");
            assertNull(fatal.get());
        } finally {
            loop.stop();
        }
    }

    /** 레거시 누락분이 다른 마켓의 대역 커서 아래에 있는 모양. 전 마켓 최댓값에서 읽으면 그 누락분을 건너뛴다. */
    @Test
    void mixedAxisCatchUpRecoversLegacyGapBelowOtherMarketsBandCursor() throws Exception {
        long bandSpan = 1_000_000_000_000L;
        List<OrderCommand> journal = List.of(
                limitBuy(1, 1, 1), limitBuy(2, 2, 2), limitBuy(3, 1, 3), limitBuy(4, 2, 4),
                limitBuy(bandSpan + 1, 1, 5), limitBuy(2 * bandSpan + 1, 2, 6));
        MatchingEngineImpl engine = new MatchingEngineImpl();
        for (int i : new int[] {0, 1, 2, 4}) {
            engine.apply(journal.get(i));
        }
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { },
                (afterSeq, limit) -> journal.stream()
                        .filter(c -> c.seq() > afterSeq).limit(limit).toList(),
                fatal::set);
        loop.start();
        try {
            assertTrue(loop.offer(journal.get(5)));
            await(() -> engine.appliedSeq(2) == 2 * bandSpan + 1);
            assertEquals(3, engine.orderBook(2).openOrderCount(), "마켓 2 의 레거시 누락 seq 4 가 버려졌다");
            assertEquals(bandSpan + 1, engine.appliedSeq(1));
            assertNull(fatal.get());
        } finally {
            loop.stop();
        }
    }

    /** 한 마켓의 갭 회수가 남의 마켓 행을 적용하면 그 마켓 커서가 제 누락분 위로 뛰어 누락분이 버려진다. */
    @Test
    void catchUpAppliesOnlyItsOwnMarketSoOtherMarketGapSurvives() throws Exception {
        long bandSpan = 1_000_000_000_000L;
        List<OrderCommand> journal = List.of(
                limitBuy(1, 1, 1), limitBuy(2, 2, 2), limitBuy(3, 1, 3), limitBuy(4, 2, 4),
                limitBuy(bandSpan + 1, 1, 5), limitBuy(2 * bandSpan + 1, 2, 6));
        MatchingEngineImpl engine = new MatchingEngineImpl();
        for (int i : new int[] {0, 1, 3}) {
            engine.apply(journal.get(i));
        }
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(16, engine,
                events -> { },
                (afterSeq, limit) -> journal.stream()
                        .filter(c -> c.seq() > afterSeq).limit(limit).toList(),
                fatal::set);
        loop.start();
        try {
            assertTrue(loop.offer(journal.get(5)));
            await(() -> engine.appliedSeq(2) == 2 * bandSpan + 1);
            assertEquals(1, engine.appliedSeq(1), "마켓 2 의 갭 회수가 마켓 1 커서를 옮겼다");
            assertTrue(loop.offer(journal.get(4)));
            await(() -> engine.appliedSeq(1) == bandSpan + 1);
            assertEquals(3, engine.orderBook(1).openOrderCount(), "마켓 1 의 누락 seq 3 이 버려졌다");
            assertNull(fatal.get());
        } finally {
            loop.stop();
        }
    }

    private static OrderCommand limitBuy(long seq, long orderId) {
        return limitBuy(seq, 1, orderId);
    }

    private static OrderCommand limitBuy(long seq, int marketId, long orderId) {
        return new OrderCommand(seq, marketId, OrderCommand.CommandType.NEW, 1, "c-" + orderId, orderId,
                BASE_TS + seq, seq, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("100"), new BigDecimal("1"), null, 1);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("5초 내 조건 미충족");
            }
            pause(10);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("대기 중 인터럽트", e);
        }
    }
}

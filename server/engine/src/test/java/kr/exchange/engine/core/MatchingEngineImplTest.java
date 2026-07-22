package kr.exchange.engine.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 매칭 코어 단위 검증: 가격·시간 우선, 부분 체결, 시장가 소진, 빈 반대편 거부,
 * 자전 처리(Decrement & Cancel), 이벤트 ordinal 단조, 미지원 유형 거부.
 */
class MatchingEngineImplTest {

    private static final long BASE_TS = 1_721_600_000_000L;

    private final MatchingEngineImpl engine = new MatchingEngineImpl();
    private long seq;

    // ------------------------------------------------------------------
    // 가격·시간 우선, 체결가 = maker 지정가
    // ------------------------------------------------------------------

    @Test
    void matchesByPriceThenFifoAndTradesAtMakerPrice() {
        engine.apply(limitSell(1, 1, "100", "1"));
        engine.apply(limitSell(1, 2, "100", "2"));
        engine.apply(limitSell(1, 3, "99", "1"));

        List<EngineEvent> events = engine.apply(limitBuy(2, 4, "100", "2.5"));

        assertEquals(EngineEvent.EventType.ACCEPTED, events.get(0).type());
        List<EngineEvent> trades = events.stream()
                .filter(e -> e.type() == EngineEvent.EventType.TRADE).toList();
        assertEquals(3, trades.size());
        // 더 좋은 가격(99)이 먼저, 같은 가격(100)은 게시 순서(FIFO)대로.
        assertEquals(List.of(3L, 1L, 2L),
                trades.stream().map(EngineEvent::counterOrderId).toList());
        assertDecimal("99", trades.get(0).price());
        assertDecimal("100", trades.get(1).price());
        assertDecimal("1", trades.get(0).quantity());
        assertDecimal("1", trades.get(1).quantity());
        assertDecimal("0.5", trades.get(2).quantity());
        // maker/taker 귀속 필드
        assertEquals(4L, trades.get(0).orderId());
        assertEquals(1L, trades.get(1).makerUserId());
        assertEquals(2L, trades.get(1).takerUserId());
        assertEquals(OrderCommand.Side.BUY, trades.get(1).takerSide());
        // 부분 체결된 maker 잔량
        assertDecimal("1.5", engine.orderBook(1).openQuantity(2));
    }

    @Test
    void priceImprovementTradesAtMakerPrice() {
        engine.apply(limitSell(1, 1, "100", "1"));
        List<EngineEvent> events = engine.apply(limitBuy(2, 2, "110", "1"));
        EngineEvent trade = events.get(1);
        assertEquals(EngineEvent.EventType.TRADE, trade.type());
        // price improvement는 taker 차익. 체결가는 항상 maker 지정가다(하네스 C18).
        assertDecimal("100", trade.price());
    }

    @Test
    void partialFillRestsRemainder() {
        engine.apply(limitSell(1, 1, "100", "1"));
        engine.apply(limitBuy(2, 2, "100", "3"));

        OrderBookImpl book = engine.orderBook(1);
        assertDecimal("2", book.openQuantity(2));
        assertDecimal("100", book.bestBid());
        assertNull(book.bestAsk());
    }

    // ------------------------------------------------------------------
    // 시장가
    // ------------------------------------------------------------------

    @Test
    void marketSellCancelsRemainderWhenBookExhausted() {
        engine.apply(limitBuy(1, 1, "100", "1"));
        List<EngineEvent> events = engine.apply(marketSell(2, 2, "3"));

        assertEquals(EngineEvent.EventType.ACCEPTED, events.get(0).type());
        assertEquals(EngineEvent.EventType.TRADE, events.get(1).type());
        assertDecimal("1", events.get(1).quantity());
        EngineEvent canceled = events.get(2);
        assertEquals(EngineEvent.EventType.CANCELED, canceled.type());
        assertDecimal("2", canceled.quantity());
        assertEquals(OrderBookImpl.REASON_NO_LIQUIDITY, canceled.reason());
    }

    @Test
    void marketOrderAgainstEmptyBookIsRejected() {
        List<EngineEvent> events = engine.apply(marketBuy(2, 1, "1000"));
        assertEquals(1, events.size());
        assertEquals(EngineEvent.EventType.REJECTED, events.get(0).type());
        assertEquals(OrderBookImpl.REASON_NO_LIQUIDITY, events.get(0).reason());
        assertEquals(0, events.get(0).eventOrdinal());
    }

    @Test
    void marketBuyConsumesByNotionalWithDownRounding() {
        engine.apply(limitSell(1, 1, "100", "1"));
        engine.apply(limitSell(1, 2, "200", "2"));

        List<EngineEvent> events = engine.apply(marketBuy(2, 3, "250"));
        List<EngineEvent> trades = events.stream()
                .filter(e -> e.type() == EngineEvent.EventType.TRADE).toList();
        assertEquals(2, trades.size());
        assertDecimal("1", trades.get(0).quantity());
        assertDecimal("100", trades.get(0).price());
        // 잔여 150을 200 레벨에서 0.75로 환산(DOWN).
        assertDecimal("0.75", trades.get(1).quantity());
        assertDecimal("200", trades.get(1).price());
        assertEquals(2, events.size() - 1);
        assertDecimal("1.25", engine.orderBook(1).openQuantity(2));
    }

    @Test
    void marketBuyCancelsLeftoverNotionalWhenBookExhausted() {
        engine.apply(limitSell(1, 1, "100", "1"));
        List<EngineEvent> events = engine.apply(marketBuy(2, 2, "160"));

        EngineEvent canceled = events.get(events.size() - 1);
        assertEquals(EngineEvent.EventType.CANCELED, canceled.type());
        // 시장가 매수 잔여는 notional 금액 단위다.
        assertDecimal("60", canceled.quantity());
        assertEquals(OrderBookImpl.REASON_NO_LIQUIDITY, canceled.reason());
    }

    @Test
    void notionalConsumptionEqualsQuantizedReportedAmounts() {
        // 비정수 가격 절사 도메인: 소진액 = qty * price를 scale 18 DOWN한 값 하나로 통일된다.
        engine.apply(limitSell(1, 1, "0.000000000000000003", "7"));
        List<EngineEvent> events = engine.apply(marketBuy(2, 2, "0.00000000000000001"));

        assertEquals(3, events.size());
        EngineEvent trade = events.get(1);
        assertEquals(EngineEvent.EventType.TRADE, trade.type());
        // 1e-17 / 3e-18 = 3.33...(DOWN), 소진액 = quantizeDown(3.33... * 3e-18) = 9e-18.
        assertDecimal("3.333333333333333333", trade.quantity());
        EngineEvent canceled = events.get(2);
        assertEquals(EngineEvent.EventType.CANCELED, canceled.type());
        assertEquals(OrderBookImpl.REASON_NO_LIQUIDITY, canceled.reason());
        // 잔여 = 1e-17 - 9e-18 = 1e-18. 다음 스텝 소진액이 0으로 절사되는 dust라 취소로 종결.
        assertDecimal("0.000000000000000001", canceled.quantity());
        // 보고 합계 = 원 notional (9e-18 + 1e-18 = 1e-17). 0수량 이벤트는 없다.
        assertEquals(0, events.stream()
                .filter(e -> e.quantity() != null && e.quantity().signum() == 0).count());
    }

    @Test
    void selfTradeNotionalDeductsExactlyReportedQuoteAmount() {
        // STP 경로에서도 taker 차감액과 CANCELED 보고 금액이 같은 절사 값이어야 한다.
        engine.apply(limitSell(1, 1, "0.000000000000000003", "7"));
        List<EngineEvent> events = engine.apply(marketBuy(1, 2, "0.00000000000000001"));

        assertEquals(4, events.size());
        assertEquals(EngineEvent.EventType.CANCELED, events.get(1).type());
        assertEquals(1L, events.get(1).orderId());
        assertDecimal("3.333333333333333333", events.get(1).quantity());
        assertEquals(EngineEvent.EventType.CANCELED, events.get(2).type());
        assertEquals(2L, events.get(2).orderId());
        assertEquals(OrderBookImpl.REASON_SELF_TRADE_PREVENTED, events.get(2).reason());
        assertDecimal("0.000000000000000009", events.get(2).quantity());
        // 절사 차감 후 잔여 1e-18은 dust 취소로 종결. maker 잔량은 감액분만 빠진다.
        assertEquals(EngineEvent.EventType.CANCELED, events.get(3).type());
        assertEquals(OrderBookImpl.REASON_NO_LIQUIDITY, events.get(3).reason());
        assertDecimal("0.000000000000000001", events.get(3).quantity());
        assertDecimal("3.666666666666666667", engine.orderBook(1).openQuantity(1));
    }

    // ------------------------------------------------------------------
    // 자전거래 방지 (Decrement & Cancel)
    // ------------------------------------------------------------------

    @Test
    void selfTradeDecrementsBothAndCancelsExhaustedSide() {
        engine.apply(limitSell(1, 1, "100", "2"));
        List<EngineEvent> events = engine.apply(limitBuy(1, 2, "100", "3"));

        assertEquals(EngineEvent.EventType.ACCEPTED, events.get(0).type());
        // maker 취소가 먼저, taker 감액 취소가 다음(결정론 고정 순서).
        assertEquals(EngineEvent.EventType.CANCELED, events.get(1).type());
        assertEquals(1L, events.get(1).orderId());
        assertDecimal("2", events.get(1).quantity());
        assertEquals(OrderBookImpl.REASON_SELF_TRADE_PREVENTED, events.get(1).reason());
        assertEquals(EngineEvent.EventType.CANCELED, events.get(2).type());
        assertEquals(2L, events.get(2).orderId());
        assertDecimal("2", events.get(2).quantity());
        // 체결은 0건, 감액 후 잔량 1이 게시된다.
        assertEquals(0, events.stream().filter(e -> e.type() == EngineEvent.EventType.TRADE).count());
        assertDecimal("1", engine.orderBook(1).openQuantity(2));
        assertNull(engine.orderBook(1).openQuantity(1));
    }

    @Test
    void selfTradeKeepsDecrementedMakerWhenTakerIsSmaller() {
        engine.apply(limitSell(1, 1, "100", "5"));
        List<EngineEvent> events = engine.apply(limitBuy(1, 2, "100", "2"));

        assertEquals(3, events.size());
        assertDecimal("3", engine.orderBook(1).openQuantity(1));
        assertNull(engine.orderBook(1).openQuantity(2));
    }

    @Test
    void selfTradeSkipsToNextCounterparty() {
        engine.apply(limitSell(1, 1, "100", "1"));
        engine.apply(limitSell(2, 2, "100", "1"));
        List<EngineEvent> events = engine.apply(limitBuy(1, 3, "100", "2"));

        // 자기 주문(id 1)은 DC로 소멸하고 타인 주문(id 2)과는 정상 체결한다.
        List<EngineEvent> trades = events.stream()
                .filter(e -> e.type() == EngineEvent.EventType.TRADE).toList();
        assertEquals(1, trades.size());
        assertEquals(2L, trades.get(0).counterOrderId());
        assertDecimal("1", trades.get(0).quantity());
    }

    // ------------------------------------------------------------------
    // 미지원 유형·검증 거부
    // ------------------------------------------------------------------

    @Test
    void nonGtcTimeInForceIsExplicitlyRejected() {
        for (OrderCommand.TimeInForce tif : List.of(OrderCommand.TimeInForce.IOC,
                OrderCommand.TimeInForce.FOK, OrderCommand.TimeInForce.POST_ONLY)) {
            List<EngineEvent> events = engine.apply(newCommand(1, nextSeq(), OrderCommand.Side.BUY,
                    OrderCommand.OrdType.LIMIT, tif, dec("100"), dec("1"), null));
            assertEquals(1, events.size());
            assertEquals(EngineEvent.EventType.REJECTED, events.get(0).type());
            assertEquals(OrderBookImpl.REASON_UNSUPPORTED_TIF, events.get(0).reason());
        }
    }

    @Test
    void bestOrdTypeIsExplicitlyRejected() {
        List<EngineEvent> events = engine.apply(newCommand(1, nextSeq(), OrderCommand.Side.BUY,
                OrderCommand.OrdType.BEST, OrderCommand.TimeInForce.IOC, null, dec("1"), null));
        assertEquals(EngineEvent.EventType.REJECTED, events.get(0).type());
        assertEquals(OrderBookImpl.REASON_UNSUPPORTED_ORD_TYPE, events.get(0).reason());
    }

    @Test
    void invalidParameterCombinationsAreRejected() {
        // LIMIT인데 가격 없음
        assertRejected(newCommand(1, nextSeq(), OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                null, null, dec("1"), null), OrderBookImpl.REASON_INVALID_PARAMETERS);
        // MARKET BUY는 notional 필수(수량 금지, API §4-B-1 상호배타)
        assertRejected(newCommand(1, nextSeq(), OrderCommand.Side.BUY, OrderCommand.OrdType.MARKET,
                null, null, dec("1"), null), OrderBookImpl.REASON_INVALID_PARAMETERS);
        // MARKET SELL은 수량 필수(notional 금지)
        assertRejected(newCommand(1, nextSeq(), OrderCommand.Side.SELL, OrderCommand.OrdType.MARKET,
                null, null, null, dec("100")), OrderBookImpl.REASON_INVALID_PARAMETERS);
        // scale 18 초과
        assertRejected(newCommand(1, nextSeq(), OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                null, dec("100"), dec("0.0000000000000000001"), null),
                OrderBookImpl.REASON_INVALID_PARAMETERS);
    }

    // ------------------------------------------------------------------
    // 취소
    // ------------------------------------------------------------------

    @Test
    void cancelReleasesRemainingAndRepeatIsRejected() {
        engine.apply(limitBuy(1, 1, "100", "2"));

        List<EngineEvent> canceled = engine.apply(cancel(1, 1));
        assertEquals(1, canceled.size());
        assertEquals(EngineEvent.EventType.CANCELED, canceled.get(0).type());
        assertDecimal("2", canceled.get(0).quantity());
        assertNull(canceled.get(0).reason());
        assertNull(engine.orderBook(1).bestBid());

        List<EngineEvent> repeat = engine.apply(cancel(1, 1));
        assertEquals(EngineEvent.EventType.REJECTED, repeat.get(0).type());
        assertEquals(OrderBookImpl.REASON_ORDER_NOT_FOUND, repeat.get(0).reason());
    }

    @Test
    void cancelByNonOwnerIsRejected() {
        engine.apply(limitBuy(1, 1, "100", "2"));
        List<EngineEvent> events = engine.apply(cancel(2, 1));
        assertEquals(EngineEvent.EventType.REJECTED, events.get(0).type());
        assertEquals(OrderBookImpl.REASON_ORDER_NOT_FOUND, events.get(0).reason());
        assertDecimal("2", engine.orderBook(1).openQuantity(1));
    }

    // ------------------------------------------------------------------
    // ordinal·중복 적용·체크섬
    // ------------------------------------------------------------------

    @Test
    void eventOrdinalsAreMonotonicFromZeroPerCommand() {
        engine.apply(limitSell(1, 1, "100", "1"));
        engine.apply(limitSell(1, 2, "100", "2"));
        List<EngineEvent> events = engine.apply(limitBuy(2, 3, "100", "5"));

        assertEquals(3, events.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i, events.get(i).eventOrdinal());
            assertEquals(events.get(events.size() - 1).sourceCmdSeq(), events.get(i).sourceCmdSeq());
        }
    }

    @Test
    void duplicateSeqIsIgnored() {
        OrderCommand cmd = limitBuy(1, 1, "100", "1");
        engine.apply(cmd);
        long checksum = engine.stateChecksum();

        assertEquals(List.of(), engine.apply(cmd));
        assertEquals(checksum, engine.stateChecksum());
        assertEquals(cmd.seq(), engine.lastAppliedSeq());
    }

    @Test
    void checksumReflectsStateAndIsReproducible() {
        MatchingEngineImpl other = new MatchingEngineImpl();
        List<OrderCommand> commands = List.of(
                limitSell(1, 1, "100", "1"),
                limitBuy(2, 2, "100", "0.4"),
                limitBuy(2, 3, "95", "2"));
        for (OrderCommand c : commands) {
            engine.apply(c);
            other.apply(c);
        }
        assertEquals(engine.stateChecksum(), other.stateChecksum());

        other.apply(cancel(2, 3, commands.size() + 1));
        assertNotEquals(engine.stateChecksum(), other.stateChecksum());
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private long nextSeq() {
        return ++seq;
    }

    private OrderCommand limitBuy(long userId, long orderId, String price, String qty) {
        return limit(userId, orderId, OrderCommand.Side.BUY, price, qty);
    }

    private OrderCommand limitSell(long userId, long orderId, String price, String qty) {
        return limit(userId, orderId, OrderCommand.Side.SELL, price, qty);
    }

    private OrderCommand limit(long userId, long orderId, OrderCommand.Side side,
                               String price, String qty) {
        long s = nextSeq();
        return new OrderCommand(s, 1, OrderCommand.CommandType.NEW, userId, "c-" + orderId,
                orderId, BASE_TS + s, s, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, dec(price), dec(qty), null, 1);
    }

    private OrderCommand marketBuy(long userId, long orderId, String notional) {
        long s = nextSeq();
        return new OrderCommand(s, 1, OrderCommand.CommandType.NEW, userId, "c-" + orderId,
                orderId, BASE_TS + s, s, OrderCommand.Side.BUY, OrderCommand.OrdType.MARKET,
                null, null, null, dec(notional), 1);
    }

    private OrderCommand marketSell(long userId, long orderId, String qty) {
        long s = nextSeq();
        return new OrderCommand(s, 1, OrderCommand.CommandType.NEW, userId, "c-" + orderId,
                orderId, BASE_TS + s, s, OrderCommand.Side.SELL, OrderCommand.OrdType.MARKET,
                null, null, dec(qty), null, 1);
    }

    private OrderCommand newCommand(long userId, long s, OrderCommand.Side side,
                                    OrderCommand.OrdType ordType, OrderCommand.TimeInForce tif,
                                    BigDecimal price, BigDecimal qty, BigDecimal quoteOrderQty) {
        return new OrderCommand(s, 1, OrderCommand.CommandType.NEW, userId, "c-" + s,
                9000 + s, BASE_TS + s, s, side, ordType, tif, price, qty, quoteOrderQty, 1);
    }

    private OrderCommand cancel(long userId, long targetOrderId) {
        return cancel(userId, targetOrderId, nextSeq());
    }

    private OrderCommand cancel(long userId, long targetOrderId, long s) {
        if (s > seq) {
            seq = s;
        }
        return new OrderCommand(s, 1, OrderCommand.CommandType.CANCEL, userId, null,
                targetOrderId, BASE_TS + s, s, null, null, null, null, null, null, 1);
    }

    private void assertRejected(OrderCommand command, String reason) {
        List<EngineEvent> events = engine.apply(command);
        assertEquals(1, events.size());
        assertEquals(EngineEvent.EventType.REJECTED, events.get(0).type());
        assertEquals(reason, events.get(0).reason());
    }

    private static BigDecimal dec(String v) {
        return new BigDecimal(v);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "기대 " + expected + ", 실제 " + actual);
    }
}

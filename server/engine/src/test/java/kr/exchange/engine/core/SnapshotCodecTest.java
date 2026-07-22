package kr.exchange.engine.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스냅샷 codec 결정론·재구성 검증(복구 RTO 설계 §3-b, §6-4). Docker 없이 도는 순수 단위
 * 테스트다. 핵심 계약: encode/decode 왕복이 stateChecksum을 보존하고, 동일 상태는 동일
 * 바이트를 내며, 스냅샷으로 복원한 fresh 엔진이 원본과 비트동일하게 이후 명령을 처리한다.
 */
class SnapshotCodecTest {

    private static final long BASE_TS = 1_753_000_000_000L;

    @Test
    void roundTripPreservesBookChecksum() {
        MatchingEngineImpl engine = restingBook();
        for (int marketId : engine.markets()) {
            OrderBookImpl original = engine.orderBook(marketId);
            OrderBookImpl restored = SnapshotCodec.decode(SnapshotCodec.encode(original));
            assertEquals(original.stateChecksum(), restored.stateChecksum(),
                    "왕복 후 북 체크섬이 달라졌다: market=" + marketId);
            assertEquals(original.openOrderCount(), restored.openOrderCount());
            assertEquals(original.bestBid(), restored.bestBid());
            assertEquals(original.bestAsk(), restored.bestAsk());
        }
    }

    @Test
    void encodeIsByteDeterministic() {
        OrderBookImpl book = restingBook().orderBook(1);
        assertArrayEquals(SnapshotCodec.encode(book), SnapshotCodec.encode(book),
                "동일 상태가 서로 다른 바이트를 냈다(비결정 인코딩)");
    }

    @Test
    void restoredEngineReproducesChecksumAndBehavior() {
        MatchingEngineImpl origin = restingBook();
        long seq = origin.lastAppliedSeq();

        List<OrderBookImpl> restoredBooks = new ArrayList<>();
        for (int marketId : origin.markets()) {
            restoredBooks.add(SnapshotCodec.decode(SnapshotCodec.encode(origin.orderBook(marketId))));
        }
        MatchingEngineImpl restored = new MatchingEngineImpl();
        restored.loadSnapshot(seq, restoredBooks);

        assertEquals(origin.lastAppliedSeq(), restored.lastAppliedSeq());
        assertEquals(origin.stateChecksum(), restored.stateChecksum(),
                "스냅샷 복원 엔진의 전 마켓 체크섬이 원본과 다르다");

        // 복원 엔진에 교차 매수를 적용해도 원본과 동일 이벤트열·동일 결과 상태를 내야 한다.
        OrderCommand crossing = limit(seq + 1, 1, 30, OrderCommand.Side.BUY, "1005", "2");
        List<EngineEvent> originEvents = origin.apply(crossing);
        List<EngineEvent> restoredEvents = restored.apply(crossing);
        assertEquals(describe(originEvents), describe(restoredEvents),
                "복원 엔진의 매칭 결과가 원본과 다르다");
        assertEquals(origin.stateChecksum(), restored.stateChecksum());
    }

    @Test
    void emptyBookRoundTrips() {
        OrderBookImpl empty = new OrderBookImpl(7);
        OrderBookImpl restored = SnapshotCodec.decode(SnapshotCodec.encode(empty));
        assertEquals(empty.stateChecksum(), restored.stateChecksum());
        assertEquals(0, restored.openOrderCount());
        assertEquals(7, restored.marketId());
    }

    @Test
    void loadSnapshotRejectsNonFreshEngine() {
        MatchingEngineImpl advanced = restingBook();
        assertThrows(IllegalStateException.class,
                () -> advanced.loadSnapshot(1, List.of(new OrderBookImpl(1))));
    }

    @Test
    void decodeRejectsUnknownFormatVersion() {
        byte[] bytes = SnapshotCodec.encode(restingBook().orderBook(1));
        bytes[0] = (byte) (SnapshotCodec.formatVersion() + 1);
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(bytes));
    }

    @Test
    void decodeRejectsTruncatedBytes() {
        byte[] bytes = SnapshotCodec.encode(restingBook().orderBook(1));
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 3);
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(truncated));
    }

    // 두 마켓, 다중 가격 레벨, 레벨 내 FIFO, 부분 체결로 잔여가 소수인 상태를 만든다.
    private static MatchingEngineImpl restingBook() {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        long seq = 0;
        // market 1: 매도 레스팅 두 레벨(1010에 2건 FIFO, 1005 1건) + 매수 레스팅(990).
        engine.apply(limit(++seq, 1, 1, OrderCommand.Side.SELL, "1010", "3"));
        engine.apply(limit(++seq, 1, 2, OrderCommand.Side.SELL, "1010", "1"));
        engine.apply(limit(++seq, 1, 3, OrderCommand.Side.SELL, "1005", "5"));
        engine.apply(limit(++seq, 1, 4, OrderCommand.Side.BUY, "990", "4"));
        // 990 매수를 일부 체결시켜 잔여를 소수로 만든다(매도 995 taker가 아니라, 매수 잔여 감액).
        engine.apply(limit(++seq, 1, 5, OrderCommand.Side.SELL, "990", "1.5"));
        // market 2: 매수 레스팅 두 건 동일가 FIFO.
        engine.apply(limit(++seq, 2, 6, OrderCommand.Side.BUY, "50", "10"));
        engine.apply(limit(++seq, 2, 7, OrderCommand.Side.BUY, "50", "20"));
        return engine;
    }

    private static OrderCommand limit(long seq, int marketId, long orderId, OrderCommand.Side side,
                                      String price, String qty) {
        return new OrderCommand(seq, marketId, OrderCommand.CommandType.NEW, orderId,
                "c-" + orderId, orderId, BASE_TS + seq, seq, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal(qty), null, 1);
    }

    private static String describe(List<EngineEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (EngineEvent e : events) {
            sb.append(e.type()).append('|').append(e.orderId()).append('|')
                    .append(e.quantity() == null ? "-" : e.quantity().stripTrailingZeros().toPlainString())
                    .append('\n');
        }
        return sb.toString();
    }
}

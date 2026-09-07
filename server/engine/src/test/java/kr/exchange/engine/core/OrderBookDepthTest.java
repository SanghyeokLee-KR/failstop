package kr.exchange.engine.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 호가 스냅샷(bidDepth·askDepth)의 정확성 검증.
 *
 * 단별 잔량을 잔량이 드나드는 자리에서 증분으로 유지한다. 증분은 필요할 때 세는 것보다 빠르지만
 * 갱신 지점을 하나라도 빠뜨리면 조용히 어긋난다. 그래서 모든 시험이 스냅샷을 책 원본과 대조한다.
 *
 * 왜 증분인가. 2026-09-07 실측에서 가격 단이 17 개인데 미체결이 73073 건이었다. 필요할 때 세면
 * 단일 라이터가 배치마다 7 만 번 덧셈을 한다. 실제로 그렇게 만들었다가 주문 천장이 98.6/s 에서
 * 88.7/s 로 내려갔다.
 */
class OrderBookDepthTest {

    private static final long BASE_TS = 1_700_000_000_000L;

    private final OrderBookImpl book = new OrderBookImpl(1);
    private long seq;

    private static BigDecimal dec(String v) {
        return new BigDecimal(v).setScale(18, java.math.RoundingMode.UNNECESSARY);
    }

    private OrderCommand limit(long userId, long orderId, OrderCommand.Side side,
                               String price, String qty) {
        long s = ++seq;
        return new OrderCommand(s, 1, OrderCommand.CommandType.NEW, userId, "c-" + orderId,
                orderId, BASE_TS + s, s, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, dec(price), dec(qty), null, 1);
    }

    private OrderCommand cancel(long userId, long orderId) {
        long s = ++seq;
        return new OrderCommand(s, 1, OrderCommand.CommandType.CANCEL, userId, "c-" + orderId,
                orderId, BASE_TS + s, s, null, null, null, null, null, null, 1);
    }

    /**
     * 스냅샷이 책 원본과 같은지 확인한다. 원본은 미체결 주문을 하나씩 훑어 합산한 값이고,
     * 스냅샷은 증분으로 유지한 값이다. 둘이 어긋나면 갱신 지점을 빠뜨린 것이다.
     */
    private void assertDepthMatchesBook() {
        for (OrderCommand.Side side : OrderCommand.Side.values()) {
            OrderBookImpl.SideDepth snap = side == OrderCommand.Side.BUY
                    ? book.bidDepth(1000) : book.askDepth(1000);

            // 원본 재계산. openQuantity 는 잔량의 진실 원천이다.
            List<OrderBookImpl.DepthLevel> expected = rebuild(side);
            assertEquals(expected.size(), snap.levels().size(),
                    side + " 가격 단 수가 어긋났다. 증분 갱신 지점을 빠뜨렸다");

            BigDecimal expectedTotal = BigDecimal.ZERO;
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(0, expected.get(i).price().compareTo(snap.levels().get(i).price()),
                        side + " " + i + "단 가격이 어긋났다");
                assertEquals(0, expected.get(i).size().compareTo(snap.levels().get(i).size()),
                        side + " " + i + "단 잔량이 어긋났다: 원본 " + expected.get(i).size()
                                + " 스냅샷 " + snap.levels().get(i).size());
                expectedTotal = expectedTotal.add(expected.get(i).size());
            }
            assertEquals(0, expectedTotal.compareTo(snap.total()),
                    side + " 총 잔량이 어긋났다: 원본 " + expectedTotal + " 스냅샷 " + snap.total());
        }
    }

    /** 스냅샷을 통해 얻은 가격 목록으로 원본을 되짚는다. 잔량 0 인 단은 나오면 안 된다. */
    private List<OrderBookImpl.DepthLevel> rebuild(OrderCommand.Side side) {
        // 책이 내보내는 스냅샷과 독립적인 경로가 필요하다. 스냅샷 자체를 근거로 삼으면
        // 어긋남을 잡지 못한다. export 는 미체결 주문을 그대로 내주므로 그것으로 다시 센다.
        java.util.TreeMap<BigDecimal, BigDecimal> sums = new java.util.TreeMap<>(
                side == OrderCommand.Side.BUY ? java.util.Comparator.reverseOrder()
                        : java.util.Comparator.<BigDecimal>naturalOrder());
        for (OrderBookImpl.BookEntry e : book.exportEntries()) {
            if (e.side() != side) {
                continue;
            }
            sums.merge(e.price(), e.remaining(), BigDecimal::add);
        }
        List<OrderBookImpl.DepthLevel> out = new ArrayList<>();
        for (var e : sums.entrySet()) {
            if (e.getValue().signum() != 0) {
                out.add(new OrderBookImpl.DepthLevel(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    @Test
    void restingOrdersFormLevels() {
        book.apply(limit(1, 1, OrderCommand.Side.BUY, "100", "2"));
        book.apply(limit(1, 2, OrderCommand.Side.BUY, "100", "3"));
        book.apply(limit(1, 3, OrderCommand.Side.BUY, "99", "1"));
        assertDepthMatchesBook();

        OrderBookImpl.SideDepth bid = book.bidDepth(10);
        assertEquals(2, bid.levels().size());
        assertEquals(0, dec("100").compareTo(bid.levels().get(0).price()),
                "매수는 높은 가격이 먼저다");
        assertEquals(0, dec("5").compareTo(bid.levels().get(0).size()),
                "같은 가격의 두 주문이 한 단으로 합쳐져야 한다");
        assertEquals(0, dec("6").compareTo(bid.total()));
    }

    @Test
    void limitTakesOnlyTopLevelsButTotalCoversAll() {
        for (int i = 0; i < 5; i++) {
            book.apply(limit(1, 10 + i, OrderCommand.Side.SELL, String.valueOf(100 + i), "1"));
        }
        OrderBookImpl.SideDepth ask = book.askDepth(2);
        assertEquals(2, ask.levels().size(), "limit 은 단 수를 끊는다");
        assertEquals(0, dec("5").compareTo(ask.total()),
                "총 잔량은 끊지 않는다. 끊으면 total_ask_size 가 15 단 합이 되어 종전 값과 달라진다");
    }

    @Test
    void partialFillReducesLevel() {
        book.apply(limit(1, 1, OrderCommand.Side.SELL, "100", "5"));
        book.apply(limit(2, 2, OrderCommand.Side.BUY, "100", "2"));
        assertDepthMatchesBook();
        assertEquals(0, dec("3").compareTo(book.askDepth(10).total()),
                "체결분이 단에서 빠져야 한다");
    }

    @Test
    void fullFillRemovesLevel() {
        book.apply(limit(1, 1, OrderCommand.Side.SELL, "100", "2"));
        book.apply(limit(2, 2, OrderCommand.Side.BUY, "100", "2"));
        assertDepthMatchesBook();
        assertTrue(book.askDepth(10).levels().isEmpty(), "잔량 0 인 단이 남으면 빈 벽이 선다");
        assertEquals(0, BigDecimal.ZERO.compareTo(book.askDepth(10).total()));
    }

    @Test
    void cancelRemovesRemaining() {
        book.apply(limit(1, 1, OrderCommand.Side.BUY, "100", "4"));
        book.apply(limit(1, 2, OrderCommand.Side.BUY, "100", "1"));
        book.apply(cancel(1, 1));
        assertDepthMatchesBook();
        assertEquals(0, dec("1").compareTo(book.bidDepth(10).total()),
                "취소된 주문의 잔량만 빠져야 한다");
    }

    @Test
    void cancelAfterPartialFillRemovesOnlyWhatRemained() {
        book.apply(limit(1, 1, OrderCommand.Side.SELL, "100", "5"));
        book.apply(limit(2, 2, OrderCommand.Side.BUY, "100", "2"));
        book.apply(cancel(1, 1));
        assertDepthMatchesBook();
        assertEquals(0, BigDecimal.ZERO.compareTo(book.askDepth(10).total()),
                "체결로 이미 뺀 몫을 취소에서 또 빼면 음수가 된다");
    }

    /** 스냅샷 복원 경로도 단별 잔량을 채워야 한다. 빠뜨리면 재기동 뒤 호가가 0 으로 보인다. */
    @Test
    void restoredBookHasDepth() {
        book.apply(limit(1, 1, OrderCommand.Side.BUY, "100", "2"));
        book.apply(limit(1, 2, OrderCommand.Side.SELL, "101", "3"));

        OrderBookImpl restored = OrderBookImpl.fromEntries(1, book.exportEntries());
        assertEquals(0, dec("2").compareTo(restored.bidDepth(10).total()),
                "복원된 책의 매수 잔량이 비어 있다");
        assertEquals(0, dec("3").compareTo(restored.askDepth(10).total()),
                "복원된 책의 매도 잔량이 비어 있다");
    }

    /** 섞어 돌려도 어긋나지 않아야 한다. 갱신 지점 누락은 조합에서 드러난다. */
    @Test
    void mixedSequenceKeepsDepthConsistent() {
        book.apply(limit(1, 1, OrderCommand.Side.SELL, "100", "5"));
        book.apply(limit(1, 2, OrderCommand.Side.SELL, "101", "2"));
        book.apply(limit(2, 3, OrderCommand.Side.BUY, "100", "3"));
        assertDepthMatchesBook();
        book.apply(limit(2, 4, OrderCommand.Side.BUY, "99", "4"));
        assertDepthMatchesBook();
        book.apply(cancel(2, 4));
        assertDepthMatchesBook();
        book.apply(limit(3, 5, OrderCommand.Side.BUY, "101", "10"));
        assertDepthMatchesBook();
        assertTrue(book.askDepth(10).levels().isEmpty(), "매도가 전부 소진돼야 한다");
    }
}

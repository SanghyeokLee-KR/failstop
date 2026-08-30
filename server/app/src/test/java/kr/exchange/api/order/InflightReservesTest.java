package kr.exchange.api.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 잔고 사전 가드 in-flight 누계 회귀.
 * 핵심 계약: 타임아웃(defer) 경로는 즉시 해제하지 않고, 주문 row 관측 또는 TTL 만료
 * 시점에만 누계에서 회수된다. 즉시 해제하면 가드-정산 경합 창이 다시 열린다.
 */
class InflightReservesTest {

    private static final long TTL = 30_000;

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final Set<Long> settledOrders = new HashSet<>();
    private final InflightReserves reserves =
            new InflightReserves(settledOrders::contains, clock::get, TTL);
    private final Supplier<BigDecimal> available = () -> new BigDecimal("100");

    @Test
    void reserveConsumesHeadroomAndReleaseRestores() {
        assertTrue(reserves.tryReserve("u1:1", new BigDecimal("60"), available));
        assertFalse(reserves.tryReserve("u1:1", new BigDecimal("60"), available),
                "누계 60 반영 후 헤드룸 40에 60 예약은 거부");
        reserves.release("u1:1", new BigDecimal("60"));
        assertTrue(reserves.tryReserve("u1:1", new BigDecimal("60"), available));
    }

    @Test
    void deferredReserveHoldsUntilOrderRowObserved() {
        assertTrue(reserves.tryReserve("u2:1", new BigDecimal("80"), available));
        reserves.defer("u2:1", new BigDecimal("80"), 501L);

        // 정산 미관측 동안은 타임아웃 경로라도 누계가 유지된다(즉시 해제 금지).
        assertFalse(reserves.tryReserve("u2:1", new BigDecimal("80"), available));

        // 주문 row 관측(정산 완료) 후 다음 가드 평가에서 회수된다.
        settledOrders.add(501L);
        assertTrue(reserves.tryReserve("u2:1", new BigDecimal("80"), available));
    }

    @Test
    void deferredReserveExpiresByTtl() {
        assertTrue(reserves.tryReserve("u3:1", new BigDecimal("80"), available));
        reserves.defer("u3:1", new BigDecimal("80"), 502L);
        assertFalse(reserves.tryReserve("u3:1", new BigDecimal("80"), available));

        clock.addAndGet(TTL - 1);
        assertFalse(reserves.tryReserve("u3:1", new BigDecimal("80"), available),
                "TTL 도래 전에는 유지");
        clock.addAndGet(1);
        assertTrue(reserves.tryReserve("u3:1", new BigDecimal("80"), available),
                "TTL 만료로 회수");
    }

    @Test
    void keysAreIsolated() {
        assertTrue(reserves.tryReserve("u4:1", new BigDecimal("100"), available));
        assertTrue(reserves.tryReserve("u4:2", new BigDecimal("100"), available),
                "다른 자산 키는 독립 누계");
    }
}

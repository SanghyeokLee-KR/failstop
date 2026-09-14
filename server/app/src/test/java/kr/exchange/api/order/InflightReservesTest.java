package kr.exchange.api.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    @Test
    void tryReserveRecordsLockHold() {
        assertEquals(0, reserves.lockHoldNanos());
        // 락 안 잔고 조회에 시간을 써서 보유 단언이 타이머 해상도에 기대지 않게 한다.
        assertTrue(reserves.tryReserve("u5:1", new BigDecimal("10"), () -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기 중 인터럽트", e);
            }
            return new BigDecimal("100");
        }));
        assertTrue(reserves.lockHoldNanos() > 0, "락 안 잔고 조회 시간이 보유에 잡혀야 한다");
        assertTrue(reserves.lockHoldNanos() > reserves.lockWaitNanos(),
                "경합 없는 획득 대기가 보유보다 크면 대기와 보유가 뒤바뀐 것이다");
    }

    @Test
    void differentKeysDoNotWaitForEachOther() throws Exception {
        String held = "u10:1";
        String other = keyOnOtherLock(held);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> holder = pool.submit(() -> reserves.tryReserve(held, new BigDecimal("10"),
                    balanceHeldUntil(inside, leave)));
            assertTrue(inside.await(5, TimeUnit.SECONDS), "첫 예약이 잔고 조회에 들어가지 않았다");

            assertTrue(pool.submit(() -> reserves.tryReserve(other, new BigDecimal("10"), available))
                    .get(5, TimeUnit.SECONDS), "다른 키 예약이 붙잡힌 키 락을 기다렸다");
            assertFalse(holder.isDone(), "대조 전제가 깨졌다. 첫 예약이 이미 락을 놓았다");

            leave.countDown();
            assertTrue(holder.get(5, TimeUnit.SECONDS));
        } finally {
            leave.countDown();
            pool.shutdown();
        }
    }

    @Test
    void sameKeyReservationsAreMutuallyExclusive() throws Exception {
        String key = "u11:1";
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        AtomicBoolean secondReadBalance = new AtomicBoolean();
        AtomicReference<Boolean> secondResult = new AtomicReference<>();
        Thread second = new Thread(() -> secondResult.set(reserves.tryReserve(key, new BigDecimal("60"), () -> {
            secondReadBalance.set(true);
            return new BigDecimal("100");
        })));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = pool.submit(() -> reserves.tryReserve(key, new BigDecimal("60"),
                    balanceHeldUntil(inside, leave)));
            assertTrue(inside.await(5, TimeUnit.SECONDS), "첫 예약이 잔고 조회에 들어가지 않았다");

            second.start();
            awaitParked(second);
            assertFalse(secondReadBalance.get(), "같은 키 예약이 붙잡힌 락 안에서 잔고를 읽었다");

            leave.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            second.join(5_000);
            assertEquals(Boolean.FALSE, secondResult.get(), "같은 잔고를 두 예약이 함께 통과했다");
        } finally {
            leave.countDown();
            pool.shutdown();
        }
    }

    @Test
    void sweepRunsOutsideReservationLock() throws Exception {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        InflightReserves slowSweep = new InflightReserves(orderId -> {
            checking.countDown();
            awaitLatch(answer);
            return false;
        }, clock::get, TTL);
        assertTrue(slowSweep.tryReserve("u12:1", new BigDecimal("50"), available));
        slowSweep.defer("u12:1", new BigDecimal("50"), 601L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> sweeping = pool.submit(
                    () -> slowSweep.tryReserve("u12:1", new BigDecimal("10"), available));
            assertTrue(checking.await(5, TimeUnit.SECONDS), "지연 항목 회수가 주문 행을 보지 않았다");

            assertTrue(pool.submit(() -> slowSweep.tryReserve("u12:1", new BigDecimal("10"), available))
                    .get(5, TimeUnit.SECONDS), "회수의 주문 행 조회가 같은 키 예약을 붙잡았다");
            assertFalse(sweeping.isDone(), "대조 전제가 깨졌다. 회수가 이미 끝났다");

            answer.countDown();
            assertTrue(sweeping.get(5, TimeUnit.SECONDS));
        } finally {
            answer.countDown();
            pool.shutdown();
        }
    }

    private static Supplier<BigDecimal> balanceHeldUntil(CountDownLatch inside, CountDownLatch leave) {
        return () -> {
            inside.countDown();
            awaitLatch(leave);
            return new BigDecimal("100");
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("래치 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("대기 중 인터럽트", e);
        }
    }

    private static String keyOnOtherLock(String key) {
        for (int user = 0; ; user++) {
            String candidate = "u" + user + ":1";
            if (InflightReserves.stripe(candidate) != InflightReserves.stripe(key)) {
                return candidate;
            }
        }
    }

    private static void awaitParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) {
                fail("같은 키 두 번째 예약이 락 앞에서 기다리지 않았다: " + thread.getState());
            }
            Thread.sleep(10);
        }
    }
}

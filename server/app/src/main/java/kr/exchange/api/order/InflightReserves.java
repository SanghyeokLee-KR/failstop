package kr.exchange.api.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 게이트웨이 잔고 사전 가드의 in-flight 예약 누계(단일 노드 전제).
 *
 * 해제는 종결·정산 관측 시로 한정한다. 프로젝션 대기 타임아웃 경로에서 즉시 해제하면
 * 저널에는 명령이 있는데 누계에서는 빠진 창이 열려, 같은 잔고를 이중으로 통과시킨
 * 명령이 정산 예약(23514) 실패로 루프 정지 + demote까지 번질 수 있다. 타임아웃 분은
 * defer로 넘겨 주문 row 관측 또는 TTL 만료 시점에 회수한다.
 */
class InflightReserves {

    private record Deferred(String key, BigDecimal amount, long orderId, long expiresAt) {
    }

    private final Map<String, BigDecimal> totals = new HashMap<>();
    private final List<Deferred> deferred = new ArrayList<>();
    private final LongPredicate orderRowObserved;
    private final LongSupplier clock;
    private final long deferTtlMs;

    InflightReserves(LongPredicate orderRowObserved, LongSupplier clock, long deferTtlMs) {
        this.orderRowObserved = orderRowObserved;
        this.clock = clock;
        this.deferTtlMs = deferTtlMs;
    }

    /**
     * 가용 잔고에서 in-flight 누계를 차감한 헤드룸으로 예약을 시도한다.
     * available 조회를 락 안에서 수행해 동시 예약의 이중 통과를 막는다.
     */
    synchronized boolean tryReserve(String key, BigDecimal required, Supplier<BigDecimal> available) {
        sweep();
        BigDecimal pending = totals.getOrDefault(key, BigDecimal.ZERO);
        if (available.get().subtract(pending).compareTo(required) < 0) {
            return false;
        }
        totals.merge(key, required, BigDecimal::add);
        return true;
    }

    /** 정산 관측 완료(또는 저널 미기록 확정) 시 즉시 해제. */
    synchronized void release(String key, BigDecimal amount) {
        subtract(key, amount);
    }

    /** 정산 미관측 종료 경로. 누계를 유지한 채 주문 row 관측 또는 TTL로 지연 회수한다. */
    synchronized void defer(String key, BigDecimal amount, long orderId) {
        deferred.add(new Deferred(key, amount, orderId, clock.getAsLong() + deferTtlMs));
    }

    private void sweep() {
        long now = clock.getAsLong();
        Iterator<Deferred> it = deferred.iterator();
        while (it.hasNext()) {
            Deferred d = it.next();
            if (now >= d.expiresAt() || orderRowObserved.test(d.orderId())) {
                subtract(d.key(), d.amount());
                it.remove();
            }
        }
    }

    private void subtract(String key, BigDecimal amount) {
        totals.computeIfPresent(key, (k, v) -> {
            BigDecimal rest = v.subtract(amount);
            return rest.signum() <= 0 ? null : rest;
        });
    }
}

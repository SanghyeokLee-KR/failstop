package kr.exchange.api.order;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
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

    // 락은 예약 키(사용자:자산) 단위로 가른다. 지키는 것이 같은 잔고의 이중 통과이기 때문이다. 마켓으로
    // 가르면 호가 자산을 공유하는 두 마켓의 주문이 같은 잔고를 서로 다른 락에서 통과한다(ADR 0022 4-1).
    //
    // 키마다 락을 만들면 락 수가 사용자 수를 따라 끝없이 는다. 키 해시로 고정 개수의 락 중 하나를 고른다.
    // 같은 키는 늘 같은 락이라 상호 배제는 그대로이고, 다른 키가 같은 락에 걸리면 서로 기다릴 뿐이다.
    // 개수는 동시 예약 수(쓰기 수용 상한)보다 넉넉히 크게 두고, 마스크로 고르므로 2의 거듭제곱이다.
    private static final int LOCK_STRIPES = 1024;

    // synchronized가 아니라 ReentrantLock인 이유. tryReserve는 락 안에서 DB를 왕복한다
    // (available.get()). JDK 21에서 synchronized 안의 블로킹은 가상 스레드를 캐리어에 고정해,
    // 캐리어 수(=코어 수)만큼 고정되면 스케줄링이 통째로 멈춘다. 2 vCPU 실측에서 주문 16/s에
    // 노드가 영구 정지했다(harness/evidence/gateoff-20260903-2vcpu/).
    // ReentrantLock은 가상 스레드를 고정하지 않는다. 상호 배제 계약은 그대로다.
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
    // 키마다 다른 락 아래에서 갱신되므로 맵 자체는 동시 갱신에 안전해야 한다.
    private final ConcurrentHashMap<String, BigDecimal> totals = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Deferred> deferred = new ConcurrentLinkedQueue<>();
    // 지연 항목 회수는 한 번에 한 스레드만 한다. 항목마다 주문 행을 DB 에서 보므로 요청마다 겹쳐 돌면
    // 그 조회가 동시 요청 수만큼 곱해진다.
    private final ReentrantLock sweepLock = new ReentrantLock();
    private final LongPredicate orderRowObserved;
    private final LongSupplier clock;
    private final long deferTtlMs;

    // 저널 replay 를 뺀 접수가 키 락을 지나고 안에서 DB 를 왕복한다. 접수 락과 같은 직렬 구간
    // 후보라 대기와 보유를 나눠 센다. 대기가 크면 경합이, 보유가 크면 락 안 왕복이 상한이다.
    private final LongAdder lockWaitNanos = new LongAdder();
    private final LongAdder lockHoldNanos = new LongAdder();

    InflightReserves(LongPredicate orderRowObserved, LongSupplier clock, long deferTtlMs) {
        this.orderRowObserved = orderRowObserved;
        this.clock = clock;
        this.deferTtlMs = deferTtlMs;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** 락 획득 대기 누적 나노초. 키 락 전부의 합이다. */
    long lockWaitNanos() {
        return lockWaitNanos.sum();
    }

    /** 락 보유 누적 나노초. 키 락 전부의 합이다. */
    long lockHoldNanos() {
        return lockHoldNanos.sum();
    }

    /** 키가 쓰는 락 번호. 같은 키는 늘 같은 번호다. */
    static int stripe(String key) {
        int h = key.hashCode();
        return (h ^ (h >>> 16)) & (LOCK_STRIPES - 1);
    }

    /**
     * 가용 잔고에서 in-flight 누계를 차감한 헤드룸으로 예약을 시도한다.
     * available 조회를 키 락 안에서 수행해 같은 키 동시 예약의 이중 통과를 막는다.
     */
    boolean tryReserve(String key, BigDecimal required, Supplier<BigDecimal> available) {
        sweep();
        ReentrantLock lock = locks[stripe(key)];
        long lockedAt = lockTimed(lock);
        try {
            BigDecimal pending = totals.getOrDefault(key, BigDecimal.ZERO);
            if (available.get().subtract(pending).compareTo(required) < 0) {
                return false;
            }
            totals.merge(key, required, BigDecimal::add);
            return true;
        } finally {
            unlockTimed(lock, lockedAt);
        }
    }

    /** 정산 관측 완료(또는 저널 미기록 확정) 시 즉시 해제. */
    void release(String key, BigDecimal amount) {
        ReentrantLock lock = locks[stripe(key)];
        long lockedAt = lockTimed(lock);
        try {
            totals.computeIfPresent(key, (k, v) -> {
                BigDecimal rest = v.subtract(amount);
                return rest.signum() <= 0 ? null : rest;
            });
        } finally {
            unlockTimed(lock, lockedAt);
        }
    }

    /** 키의 현재 누계. 없으면 0 이다. 지연 회수 대기분도 포함한다. */
    BigDecimal pending(String key) {
        return totals.getOrDefault(key, BigDecimal.ZERO);
    }

    /** 정산 미관측 종료 경로. 누계를 유지한 채 주문 row 관측 또는 TTL로 지연 회수한다. */
    void defer(String key, BigDecimal amount, long orderId) {
        deferred.add(new Deferred(key, amount, orderId, clock.getAsLong() + deferTtlMs));
    }

    private long lockTimed(ReentrantLock lock) {
        long t0 = System.nanoTime();
        lock.lock();
        long t1 = System.nanoTime();
        lockWaitNanos.add(t1 - t0);
        return t1;
    }

    private void unlockTimed(ReentrantLock lock, long lockedAt) {
        lockHoldNanos.add(System.nanoTime() - lockedAt);
        lock.unlock();
    }

    /**
     * 만료됐거나 주문 행이 보인 지연 항목을 누계에서 뺀다. 요청의 키 락 밖에서 돈다.
     *
     * 항목마다 주문 행을 DB 에서 보므로 키 락 안에 두면 그 조회가 같은 키 예약을 붙잡는다. 다른 스레드가
     * 이미 훑고 있으면 건너뛴다. 건너뛴 예약은 회수 전 누계를 보므로 거부 쪽으로만 틀린다.
     */
    private void sweep() {
        if (deferred.isEmpty() || !sweepLock.tryLock()) {
            return;
        }
        try {
            long now = clock.getAsLong();
            Iterator<Deferred> it = deferred.iterator();
            while (it.hasNext()) {
                Deferred d = it.next();
                if (now >= d.expiresAt() || orderRowObserved.test(d.orderId())) {
                    // 항목을 지우는 것은 이 락을 쥔 스레드뿐이라 같은 항목을 두 번 빼지 않는다.
                    it.remove();
                    release(d.key(), d.amount());
                }
            }
        } finally {
            sweepLock.unlock();
        }
    }
}

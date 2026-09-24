package kr.exchange.engine;

import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 선호와 유예(DB 설계 §5.4-c 9, ADR 0034 결정 2·4). 비선호 파티션은 기동 직후 2×TTL, 그 뒤에는 그 파티션 리스의 만료를 볼
 * 때마다 1×TTL 을 기다린 뒤에만 획득을 시도한다. 선호 노드가 살아 있으면 그 사이에 얻고, 비선호 노드는 다음 만료까지
 * 기다린다. 선호 노드에 자동으로 되돌려 주지 않는다. 유예는 획득 시도를 늦출 뿐이고 승격 규칙은 그대로다.
 *
 * P1·P2 의 matching-engine 과 선호 파티션은 ALWAYS 다. 판정은 리스 틱 스레드 하나가 부르므로 동기화하지 않는다.
 */
public final class PartitionAcquirePolicy {

    /** 유예 없음. */
    public static final PartitionAcquirePolicy ALWAYS = new PartitionAcquirePolicy(null, null);

    private final Duration ttl;
    private final LongSupplier nanoClock;
    private final long bootNanos;
    // 이번 만료를 처음 본 보관소 시각. 리스가 다시 유효해지면 지운다.
    private Instant expirySeenAt;

    private PartitionAcquirePolicy(Duration ttl, LongSupplier nanoClock) {
        this.ttl = ttl;
        this.nanoClock = nanoClock;
        this.bootNanos = nanoClock == null ? 0 : nanoClock.getAsLong();
    }

    /** 비선호 파티션. 기동 시각은 만든 시각이다. */
    public static PartitionAcquirePolicy nonPreferred(Duration ttl) {
        return nonPreferred(ttl, System::nanoTime);
    }

    static PartitionAcquirePolicy nonPreferred(Duration ttl, LongSupplier nanoClock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || nanoClock == null) {
            throw new IllegalArgumentException("ttl은 양수이고 clock은 필수다");
        }
        return new PartitionAcquirePolicy(ttl, nanoClock);
    }

    public boolean deferring() {
        return ttl != null;
    }

    /**
     * 이번 틱에 획득을 시도해도 되는가. 비선호면 그 파티션 리스 행을 읽는다(리스 풀). 만료 판정은 보관소 시계로 한다.
     */
    public boolean mayAttempt(LeaderLeaseStore store) {
        if (ttl == null) {
            return true;
        }
        if (nanoClock.getAsLong() - bootNanos < ttl.multipliedBy(2).toNanos()) {
            return false;
        }
        Optional<LeaseSnapshot> lease = store.current();
        Instant now = store.now();
        if (lease.isEmpty() || !lease.get().expiredAt(now)) {
            // 유효한 리스가 있다. 획득 조건에 맞지 않으니 시도해도 얻지 못한다. 다음 만료를 새로 센다.
            expirySeenAt = null;
            return false;
        }
        if (expirySeenAt == null) {
            expirySeenAt = now;
        }
        return !now.isBefore(expirySeenAt.plus(ttl));
    }
}

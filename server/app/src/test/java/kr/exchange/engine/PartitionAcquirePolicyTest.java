package kr.exchange.engine;

import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 선호와 유예(DB 설계 §5.4-c 9, ADR 0034 결정 2·4). 비선호 파티션은 기동 뒤 2×TTL, 그 뒤에는 리스 만료를 볼 때마다 1×TTL 을
 * 기다린다. 선호와 P1·P2 는 바로 시도한다.
 */
class PartitionAcquirePolicyTest {

    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Instant T0 = Instant.parse("2026-09-24T00:00:00Z");

    /** 리스 행과 보관소 시계를 시험이 정하는 보관소. 읽힌 횟수를 센다. */
    private static final class ScriptedStore extends TestPartitions.UnreachableStore {
        Instant now = T0;
        Instant leaseUntil = T0.plusSeconds(5);
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Optional<LeaseSnapshot> current() {
            reads.incrementAndGet();
            return Optional.of(new LeaseSnapshot("other-node", 7, leaseUntil));
        }

        @Override
        public Instant now() {
            return now;
        }
    }

    @Test
    void preferredAndSingleLeaderPlanNeverDefer() {
        LeaderLeaseStore unreachable = new TestPartitions.UnreachableStore();
        assertFalse(PartitionAcquirePolicy.ALWAYS.deferring());
        // 보관소를 읽지 않고 바로 시도한다. 읽었다면 시험 보관소가 예외를 던졌다.
        assertTrue(PartitionAcquirePolicy.ALWAYS.mayAttempt(unreachable));
    }

    @Test
    void nonPreferredWaitsTwoTtlAfterBootWithoutReadingTheLease() {
        AtomicLong nanos = new AtomicLong();
        PartitionAcquirePolicy policy = PartitionAcquirePolicy.nonPreferred(TTL, nanos::get);
        ScriptedStore store = new ScriptedStore();
        store.leaseUntil = T0.minusSeconds(60);

        nanos.set(Duration.ofSeconds(19).toNanos());
        assertFalse(policy.mayAttempt(store), "기동 2×TTL 전에 시도했다");
        assertEquals(0, store.reads.get(), "기동 유예 중에 리스 행을 읽었다");
    }

    @Test
    void nonPreferredWaitsOneTtlAfterEachObservedExpiry() {
        AtomicLong nanos = new AtomicLong();
        PartitionAcquirePolicy policy = PartitionAcquirePolicy.nonPreferred(TTL, nanos::get);
        ScriptedStore store = new ScriptedStore();
        nanos.set(Duration.ofSeconds(21).toNanos());

        // 유효한 리스. 시도해도 못 얻으므로 시도하지 않는다.
        store.now = T0;
        store.leaseUntil = T0.plusSeconds(5);
        assertFalse(policy.mayAttempt(store));

        // 만료를 처음 본다. 그 시각부터 1×TTL 을 기다린다.
        store.now = T0.plusSeconds(6);
        assertFalse(policy.mayAttempt(store), "만료를 처음 본 틱에 시도했다");
        store.now = T0.plusSeconds(15);
        assertFalse(policy.mayAttempt(store), "만료 관측 뒤 1×TTL 전에 시도했다");
        store.now = T0.plusSeconds(16);
        assertTrue(policy.mayAttempt(store), "만료 관측 뒤 1×TTL 이 지났는데 시도하지 않았다");

        // 다른 노드가 얻어 리스가 다시 유효해지면 다음 만료를 새로 센다. 되돌려 주기(선호 노드 우선) 없이 기다린다.
        store.leaseUntil = T0.plusSeconds(40);
        store.now = T0.plusSeconds(20);
        assertFalse(policy.mayAttempt(store));
        store.now = T0.plusSeconds(41);
        assertFalse(policy.mayAttempt(store), "새 만료를 본 틱에 시도했다");
        store.now = T0.plusSeconds(51);
        assertTrue(policy.mayAttempt(store));
    }
}

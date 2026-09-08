package kr.exchange.engine.lease;

import kr.exchange.engine.LeaderLeaseService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fencing epoch 게시 계약(2026-09-08). 이 파일이 고정하는 것은 두 가지다.
 *
 * 첫째, 게시는 승격 때만 일어나고 갱신 때는 일어나지 않는다. 이것이 이번 분리의 전부다.
 * 게시는 PostgreSQL 커밋이므로, 갱신이 게시를 부르면 리스를 옮긴 의미가 사라지고
 * 2026-09-08 실측의 사고가 그대로 재현된다.
 *
 * 둘째, 게시에 실패한 노드는 리더가 되지 않는다. 게시 전에 리더가 되면 구 리더의 epoch 이
 * leader_lease 에 남아 있는 동안 두 노드의 쓰기가 모두 통과한다.
 *
 * 보관소를 메모리 구현으로 두는 이유는 이 두 성질이 보관소 종류와 무관하기 때문이다. 실제
 * DynamoDB 조건부 쓰기는 DynamoDbLeaderLeaseStoreIntegrationTest 가 따로 잰다.
 */
class FencingEpochPublishOrderTest {

    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Duration TICK = Duration.ofSeconds(3);

    /** 만료를 다루지 않는 최소 보관소. 이 파일의 판정 대상은 호출 횟수와 순서다. */
    private static final class CountingStore implements LeaderLeaseStore {
        private final Map<String, Long> holders = new HashMap<>();
        private long token;
        final AtomicInteger acquires = new AtomicInteger();
        final AtomicInteger renews = new AtomicInteger();

        @Override
        public Optional<Long> acquire(String holder, Duration ttl) {
            acquires.incrementAndGet();
            token++;
            holders.put(holder, token);
            return Optional.of(token);
        }

        @Override
        public boolean renew(String holder, long epoch, Duration ttl) {
            renews.incrementAndGet();
            return Long.valueOf(epoch).equals(holders.get(holder));
        }

        @Override
        public void release(String holder, long epoch) {
            holders.remove(holder);
        }

        @Override
        public Optional<LeaseSnapshot> current() {
            return Optional.of(new LeaseSnapshot("", token, Instant.EPOCH));
        }

        @Override
        public double priorDowntimeSeconds(Duration ttl) {
            return 0;
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    private static final class CountingPublisher implements FencingEpochPublisher {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean accept = true;

        @Override
        public boolean publish(String holder, long epoch) {
            calls.incrementAndGet();
            return accept;
        }
    }

    @Test
    void 갱신은_게시를_부르지_않는다() {
        CountingStore store = new CountingStore();
        CountingPublisher publisher = new CountingPublisher();
        try (LeaderLeaseService lease = new LeaderLeaseService(store, publisher, TTL, TICK,
                kr.exchange.engine.NodeServiceHealth.ALWAYS_SERVICEABLE)) {
            assertTrue(lease.tryAcquire(), "승격이 실패했다");
            assertEquals(1, publisher.calls.get(), "승격에서 게시가 한 번 일어나지 않았다");

            for (int i = 0; i < 50; i++) {
                assertTrue(lease.renew(), "정상 갱신이 거부됐다");
            }

            assertEquals(50, store.renews.get(), "갱신이 보관소에 닿지 않았다");
            assertEquals(1, publisher.calls.get(),
                    "갱신이 게시를 불렀다. 게시는 커밋이므로 리스를 옮긴 의미가 사라진다");
        }
    }

    @Test
    void 게시에_실패하면_리더가_되지_않는다() {
        CountingStore store = new CountingStore();
        CountingPublisher publisher = new CountingPublisher();
        publisher.accept = false;
        try (LeaderLeaseService lease = new LeaderLeaseService(store, publisher, TTL, TICK,
                kr.exchange.engine.NodeServiceHealth.ALWAYS_SERVICEABLE)) {
            assertFalse(lease.tryAcquire(), "게시에 실패했는데 승격이 성립했다");
            assertFalse(lease.isLeader(), "게시에 실패한 노드가 리더 상태다");
            assertTrue(lease.current().isEmpty(), "게시에 실패한 노드가 리더 신원을 들고 있다");
            assertEquals(1, publisher.calls.get(), "게시를 시도하지 않았다");
            assertEquals(1, store.acquires.get(), "보관소 승격 자체는 한 번 일어나야 한다");
        }
    }

    @Test
    void 게시가_성립하면_리더_신원이_보관소_토큰과_같다() {
        CountingStore store = new CountingStore();
        CountingPublisher publisher = new CountingPublisher();
        try (LeaderLeaseService lease = new LeaderLeaseService(store, publisher, TTL, TICK,
                kr.exchange.engine.NodeServiceHealth.ALWAYS_SERVICEABLE)) {
            assertTrue(lease.tryAcquire());

            assertEquals(1L, lease.requireLeader().epoch(),
                    "리더 신원의 epoch이 보관소가 발급한 토큰과 다르다");
            assertEquals(lease.holder(), lease.requireLeader().holder());
        }
    }

    /**
     * 종전 배선이 그대로 살아 있는지. PostgreSQL 보관소에서는 승격 UPDATE 가 이미 fencing 검증이
     * 읽는 행을 올리므로 게시가 없어야 한다. 짝이 어긋나면 게시가 두 번 일어나거나 아예 없다.
     */
    @Test
    void 게시가_없는_배선에서도_승격이_성립한다() {
        CountingStore store = new CountingStore();
        try (LeaderLeaseService lease = new LeaderLeaseService(store, FencingEpochPublisher.NONE,
                TTL, TICK, kr.exchange.engine.NodeServiceHealth.ALWAYS_SERVICEABLE)) {
            assertTrue(lease.tryAcquire(), "게시 없는 배선에서 승격이 실패했다");
            assertTrue(lease.isLeader());
        }
    }
}

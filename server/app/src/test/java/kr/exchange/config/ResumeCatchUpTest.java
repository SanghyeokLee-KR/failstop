package kr.exchange.config;

import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.NodeServiceHealth;
import kr.exchange.engine.PartitionAcquirePolicy;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.WriteAvailability;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 재개 따라잡기(DB 설계 §5.4-f 6, Patroni 설계 4-7). 쓰기 가능 상태가 ACTIVE 로 돌아오면 이 노드가 쥔 파티션의 마켓마다 저널
 * 머리까지 따라잡기를 요청한다. 소켓 제한으로 끊긴 append 가 재개 뒤 커밋되면 한가한 마켓에서는 이 요청만이 그 주문을 라이터에 올린다.
 */
class ResumeCatchUpTest {

    private static final Map<Integer, Long> HEADS = Map.of(1, 11L, 2, 22L, 3, 33L, 4, 44L);

    /** 늘 얻는 보관소. 이 파일이 보는 것은 어느 파티션을 쥐었는가뿐이다. */
    private static final class GrantingStore implements LeaderLeaseStore {
        private long epoch;

        @Override
        public Optional<Long> acquire(String holder, Duration ttl) {
            return Optional.of(++epoch);
        }

        @Override
        public boolean renew(String holder, long epoch, Duration ttl) {
            return true;
        }

        @Override
        public void release(String holder, long epoch) {
        }

        @Override
        public Optional<LeaseSnapshot> current() {
            return Optional.of(new LeaseSnapshot("", epoch, Instant.EPOCH));
        }

        @Override
        public double priorDowntimeSeconds(Duration ttl) {
            return 0;
        }

        @Override
        public String describe() {
            return "granting";
        }
    }

    private static PartitionLeases leases(PartitionPlan plan) {
        String holder = "resume-node";
        return new PartitionLeases(plan, holder, resource -> new LeaderLeaseService(resource, holder,
                new GrantingStore(), FencingEpochPublisher.NONE, Duration.ofSeconds(10), Duration.ofSeconds(3),
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress()));
    }

    @Test
    void requestsOnlyTheHeldPartitionsMarketsAtTheirJournalHeads() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_resume");
        // ADR 0034 결정한 값 3: 1·4 는 matching-p0, 2·3 은 matching-p1.
        db.seedMarkets(1, 2, 3, 4);
        PartitionLeases leases = leases(PartitionPlan.partitioned(JdbcClient.create(
                new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER))));
        assertTrue(leases.get("matching-p0").tryAcquire());
        AtomicInteger reads = new AtomicInteger();
        Map<Integer, Long> requested = new TreeMap<>();

        EngineConfig.requestResumeCatchUp(leases, () -> {
            reads.incrementAndGet();
            return HEADS;
        }, requested::put);

        assertEquals(Map.of(1, 11L, 4, 44L), requested, "쥔 파티션의 마켓만, 저널 머리 seq 로 요청한다");
        assertEquals(1, reads.get());
    }

    @Test
    void readsNothingWhenNoPartitionIsHeld() {
        AtomicInteger reads = new AtomicInteger();
        Map<Integer, Long> requested = new TreeMap<>();

        EngineConfig.requestResumeCatchUp(leases(PartitionPlan.single()), () -> {
            reads.incrementAndGet();
            return HEADS;
        }, requested::put);

        assertEquals(0, reads.get(), "쥔 파티션이 없으면 저널을 읽지 않는다");
        assertTrue(requested.isEmpty());
    }

    @Test
    void resumeRequestsTheCatchUpOfTheHeldPartition() {
        PartitionLeases leases = leases(PartitionPlan.single());
        assertTrue(leases.first().tryAcquire());
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        WriteAvailability availability = WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS, nanos::get,
                () -> 1_790_000_000_000L);
        Map<Integer, Long> requested = new ConcurrentHashMap<>();
        EngineConfig.catchUpOnResume(availability, leases, () -> HEADS, requested::put);

        availability.settingRead("*", false);
        assertTrue(availability.isSuspended());
        availability.probeConnected();
        for (long id = 1; id <= 2; id++) {
            availability.probeSent(id);
            availability.probeCompleted(id);
        }
        nanos.addAndGet(1);
        availability.settingRead("\"patroni-db-2\"", false);
        assertTrue(availability.isActive());

        Awaitility.await("재개 콜백이 따라잡기를 요청").atMost(Duration.ofSeconds(5)).until(() -> requested.size() == 4);
        assertEquals(HEADS, requested, "matching-engine 하나가 모든 마켓을 덮는다");
    }
}

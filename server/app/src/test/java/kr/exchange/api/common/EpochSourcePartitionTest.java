package kr.exchange.api.common;

import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.NodeServiceHealth;
import kr.exchange.engine.PartitionAcquirePolicy;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 응답 epoch 의 원천(S4 설계 4-15, API 설계 §5-2). 마켓을 아는 응답은 그 마켓 파티션의 epoch 다. 이 노드가 그 파티션을 쥐면
 * 보유 기간의 epoch, 아니면 그 자원의 leader_lease 현재값이다. 인자 없는 epoch 는 계획의 첫 자원이다. P1·P2 에서는 모든 마켓이
 * matching-engine 이라 둘이 같다.
 */
class EpochSourcePartitionTest {

    @Test
    void singlePlanReadsMatchingEngineForEveryMarket() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_epoch_single");
        db.seedMarkets(1, 2);
        db.seedLease("matching-engine", "other-node", 41, 60);
        DataSource ds = ds(db);
        EpochSource epochs = new EpochSource(leases(PartitionPlan.single(), ds, 2), JdbcClient.create(ds));
        assertEquals(41, epochs.epoch());
        assertEquals(41, epochs.epoch(1));
        assertEquals(41, epochs.epoch(2));
        assertEquals(41, epochs.epoch(999), "P1·P2 에서는 모르는 마켓도 matching-engine 이다");
    }

    @Test
    void partitionedPlanReadsTheMarketsPartitionAndPrefersTheHeldTerm() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_epoch_p3");
        db.seedMarkets(1, 2);
        db.seedMarketWithoutTriggers(20);
        db.setProtocol(3);
        db.seedLease("matching-p0", "other-node", 50, 60);
        db.seedLease("matching-p1", "other-node", 51, 60);
        DataSource ds = ds(db);
        JdbcClient jdbc = JdbcClient.create(ds);
        PartitionLeases leases = leases(PartitionPlan.partitioned(jdbc), ds, 3);
        EpochSource epochs = new EpochSource(leases, jdbc);

        assertEquals(50, epochs.epoch(), "인자 없는 epoch 는 계획의 첫 자원(p0)이다");
        assertEquals(50, epochs.epoch(1));
        assertEquals(51, epochs.epoch(2));
        assertEquals(50, epochs.epoch(20), "대응이 없는 마켓은 첫 자원 값이다");

        // 이 노드가 p1 을 쥐면 보유 기간의 epoch 다.
        db.expire("matching-p1");
        LeaderLeaseService p1 = leases.get("matching-p1");
        assertTrue(p1.tryAcquire(), "p1 획득 실패");
        long held = p1.currentTerm().orElseThrow().epoch();
        assertTrue(held > 51);
        assertEquals(held, epochs.epoch(2));
        assertEquals(50, epochs.epoch(1), "p1 보유가 p0 마켓의 epoch 를 바꿨다");
    }

    private static DataSource ds(PartitionLeaseDb db) {
        return new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER);
    }

    private static PartitionLeases leases(PartitionPlan plan, DataSource ds, int maxProtocol) {
        return new PartitionLeases(plan, "this-node", resource -> new LeaderLeaseService(resource, "this-node",
                new PostgresLeaderLeaseStore(ds, resource, (short) maxProtocol, Duration.ofSeconds(1)),
                FencingEpochPublisher.NONE, Duration.ofSeconds(60), Duration.ofSeconds(1),
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress()));
    }
}

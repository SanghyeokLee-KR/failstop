package kr.exchange.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 하네스 상태·페일오버 적재의 리스 자원(S4 설계 4-18, 하네스 검증설계 §4-3). status 는 resource 로 고른 자원의 리스를 보고
 * leases 에 계획의 모든 자원을 낸다. 자원을 적지 않으면 P1·P2 는 matching-engine 이고, P3 은 400 이다. 한 사건이 파티션
 * 하나만 옮기므로 어느 자원을 보는지 모른 채 판정하면 다른 파티션의 리더를 보게 된다.
 */
class HarnessStatusResourceTest {

    @Test
    void singlePlanDefaultsToMatchingEngine() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_harness_single");
        db.seedLease("matching-engine", "node-a", 9, 60);
        HarnessController controller = controller(db, PartitionPlan.single(), 2);

        HarnessController.StatusResponse status = controller.status(null);
        assertEquals("node-a", status.holder());
        assertEquals(9, status.epoch());
        assertTrue(status.leaderAlive());
        assertEquals(List.of("matching-engine"), status.leases().stream().map(HarnessController.LeaseView::resource).toList());
        assertEquals(9, controller.status("matching-engine").epoch());
        assertBadRequest(() -> controller.status("matching-p0"));

        Map<String, Object> recorded = controller.recordFailover(request("E2", null));
        assertEquals("matching-engine", resourceOf(db, recorded), "자원 없는 적재가 matching-engine 으로 남지 않았다");
    }

    @Test
    void partitionPlanRequiresTheResource() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_harness_p3");
        db.seedMarkets(1, 2);
        db.setProtocol(3);
        db.seedLease("matching-p0", "node-a", 31, 60);
        db.seedLease("matching-p1", "node-b", 32, -5);
        JdbcClient jdbc = JdbcClient.create(ds(db));
        HarnessController controller = controller(db, PartitionPlan.partitioned(jdbc), 3);

        assertBadRequest(() -> controller.status(null));
        assertBadRequest(() -> controller.status(" "));
        assertBadRequest(() -> controller.status("matching-engine"));
        HarnessController.StatusResponse p1 = controller.status("matching-p1");
        assertEquals("node-b", p1.holder());
        assertEquals(32, p1.epoch());
        assertFalse(p1.leaderAlive(), "만료된 p1 을 살아 있다고 답했다");
        HarnessController.StatusResponse p0 = controller.status("matching-p0");
        assertEquals("node-a", p0.holder());
        assertTrue(p0.leaderAlive());
        assertEquals(List.of("matching-p0", "matching-p1"),
                p0.leases().stream().map(HarnessController.LeaseView::resource).toList());
        assertEquals(List.of(true, false), p0.leases().stream().map(HarnessController.LeaseView::leaderAlive).toList());

        assertBadRequest(() -> controller.recordFailover(request("E2", null)));
        Map<String, Object> recorded = controller.recordFailover(request("E2", "matching-p1"));
        assertEquals("matching-p1", resourceOf(db, recorded));
    }

    private static HarnessController controller(PartitionLeaseDb db, PartitionPlan plan, int maxProtocol) {
        DataSource ds = ds(db);
        JdbcClient jdbc = JdbcClient.create(ds);
        PartitionLeases leases = new PartitionLeases(plan, "harness-test", resource -> new LeaderLeaseService(resource,
                "harness-test", new PostgresLeaderLeaseStore(ds, resource, (short) maxProtocol, Duration.ofSeconds(1)),
                FencingEpochPublisher.NONE, Duration.ofSeconds(10), Duration.ofSeconds(3),
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress()));
        return new HarnessController(jdbc, null, new FailoverRecorder(jdbc, new ObjectMapper()), null, leases,
                kr.exchange.engine.WriteAvailability.disabled());
    }

    private static HarnessController.FailoverRequest request(String experiment, String resource) {
        return new HarnessController.FailoverRequest(experiment, "old", "new", 1L, 2L, null, null, null, null, null,
                0L, "PASS", Map.of(), resource);
    }

    private static String resourceOf(PartitionLeaseDb db, Map<String, Object> recorded) throws Exception {
        return db.ownerString("SELECT resource FROM harness.failover_runs WHERE id = " + recorded.get("failover_run_id"));
    }

    private static void assertBadRequest(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    private static DataSource ds(PartitionLeaseDb db) {
        return new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER);
    }
}

package kr.exchange.admin;

import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관리 API 의 리스 목록(S4 설계 4-17, API 설계 §6-C). 모드는 DB(leader_protocol)가 정하고 그 모드가 쓰는 리스 자원만 낸다.
 * P1·P2 는 matching-engine 하나가 모든 마켓을 덮고, P3 은 p0·p1 이 대응 표의 마켓을 나눠 덮는다. 기존 lease 필드는 첫 자원
 * 값이다.
 */
class HaMonitoringControllerPartitionTest {

    @Test
    void singleLeaseCoversEveryMarket() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_ha_single");
        db.seedMarkets(1, 2, 3);
        db.seedLease("matching-engine", "node-a", 7, 60);
        Map<String, Object> status = status(db);

        assertEquals(1, status.get("leader_protocol"));
        List<Map<String, Object>> leases = leases(status);
        assertEquals(1, leases.size());
        assertEquals("matching-engine", leases.get(0).get("resource"));
        assertEquals("node-a", leases.get(0).get("holder"));
        assertEquals(7L, leases.get(0).get("epoch"));
        assertEquals(true, leases.get(0).get("leader_alive"));
        assertEquals(List.of("M1", "M2", "M3"), leases.get(0).get("markets"));
        assertEquals(7L, lease(status).get("epoch"), "lease 필드는 첫 자원 값이어야 한다");
    }

    @Test
    void partitionModeListsBothPartitionsWithTheirMarkets() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_ha_p3");
        db.seedMarkets(1, 2, 3, 4);
        db.setProtocol(3);
        db.seedLease("matching-p0", "node-a", 21, 60);
        db.seedLease("matching-p1", "node-b", 22, -5);
        Map<String, Object> status = status(db);

        assertEquals(3, status.get("leader_protocol"));
        List<Map<String, Object>> leases = leases(status);
        assertEquals(List.of("matching-p0", "matching-p1"), leases.stream().map(l -> l.get("resource")).toList());
        assertEquals("node-a", leases.get(0).get("holder"));
        assertEquals(21L, leases.get(0).get("epoch"));
        assertEquals(true, leases.get(0).get("leader_alive"));
        assertEquals(List.of("M1", "M4"), leases.get(0).get("markets"));
        assertEquals("node-b", leases.get(1).get("holder"));
        assertEquals(false, leases.get(1).get("leader_alive"), "만료된 p1 리스가 살아 있다고 나왔다");
        assertEquals(0L, leases.get(1).get("lease_remaining_ms"));
        assertEquals(List.of("M2", "M3"), leases.get(1).get("markets"));
        assertEquals(21L, lease(status).get("epoch"), "lease 필드는 첫 자원(p0) 값이어야 한다");
        assertTrue(leases.stream().noneMatch(l -> "matching-engine".equals(l.get("resource"))),
                "P3 에서 matching-engine 을 냈다");
    }

    private static Map<String, Object> status(PartitionLeaseDb db) {
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER,
                PartitionLeaseDb.OWNER));
        HaMonitoringController controller = new HaMonitoringController(jdbc, new CommandJournalReader(jdbc),
                new EventJournalReader(jdbc));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AdminPrincipal.REQUEST_ATTRIBUTE,
                new AdminPrincipal("op-01", "jti-ha", Set.of(AdminRole.VIEWER)));
        return controller.status(request);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> leases(Map<String, Object> status) {
        return (List<Map<String, Object>>) status.get("leases");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lease(Map<String, Object> status) {
        return (Map<String, Object>) status.get("lease");
    }
}

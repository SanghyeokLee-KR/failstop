package kr.exchange.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.availability.ApplicationAvailabilityBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파티션 상태 조회와 운영자 조작(S4 설계 4-14, API 설계 §6-C). 읽기는 파티션별 상태를 내고, 쓰기(참여·차단기 해제)는 원격
 * 주소가 loopback 일 때만 받는다. loopback 이 아닌 쓰기는 403 이고 상태가 바뀌지 않는다. 노드 치명(DEMOTED)은 풀지 않는다.
 *
 * DB 없는 수명주기다. 리스 서비스는 시작하지 않고 보관소는 닿으면 실패한다. 조작은 인메모리 상태만 바꾼다.
 */
class PartitionsEndpointTest {

    private static final String RESOURCE = "matching-engine";

    private ExecutorService worker;
    private PartitionLeases leases;
    private PartitionRuntime runtime;
    private PromotionCoordinator coordinator;
    private final String[] remote = {"127.0.0.1"};
    private PartitionsEndpoint endpoint;

    @BeforeEach
    void wire() {
        worker = Executors.newSingleThreadExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        leases = TestPartitions.unstarted(PartitionPlan.single());
        LeaderLeaseService lease = leases.first();
        WriterRouter router = new WriterRouter((marketId, engine, term, slot) -> {
            throw new IllegalStateException("이 시험은 설치하지 않는다");
        });
        HaAvailability ha = new HaAvailability(event -> { }, new ApplicationAvailabilityBean(), registry, "node",
                List.of(RESOURCE));
        coordinator = new PromotionCoordinator(RESOURCE, marketId -> true, null, router, lease, null,
                ha.partition(RESOURCE), null, worker);
        runtime = new PartitionRuntime(RESOURCE, marketId -> true, lease, coordinator, router, null, null,
                new PartitionBreaker(3, java.time.Duration.ofMinutes(10)), ha.partition(RESOURCE), leases, true,
                false, registry);
        endpoint = new PartitionsEndpoint(leases, () -> remote[0]);
    }

    @AfterEach
    void stop() {
        runtime.stop();
        worker.shutdownNow();
    }

    @Test
    void readReportsEachPartition() {
        Map<String, Object> view = endpoint.partitions();
        assertEquals(List.of(RESOURCE), view.get("plan"));
        assertEquals(false, view.get("partitioned"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) view.get("partitions");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(RESOURCE, row.get("resource"));
        assertEquals("FOLLOWER", row.get("state"));
        assertEquals(true, row.get("participate"));
        assertEquals(true, row.get("armed"));
        assertEquals(Map.of("open", false, "demotions_in_window", 0), row.get("breaker"));
    }

    @Test
    void nonLoopbackWriteIsForbiddenAndChangesNothing() {
        for (String address : new String[] {"10.0.0.7", "172.18.0.5", "localhost", "", null}) {
            remote[0] = address;
            WebEndpointResponse<Map<String, Object>> response = endpoint.update(RESOURCE, false, null);
            assertEquals(403, response.getStatus(), "원격 " + address + " 의 조작이 거부되지 않았다");
            assertTrue(runtime.participating(), "거부된 조작이 참여를 바꿨다");
            assertTrue(leases.first().isArmed(), "거부된 조작이 후보 표식을 바꿨다");
        }
    }

    @Test
    void loopbackTogglesParticipationAndResetsTheBreaker() {
        for (String address : new String[] {"127.0.0.1", "::1", "0:0:0:0:0:0:0:1"}) {
            remote[0] = address;
            assertEquals(200, endpoint.update(RESOURCE, false, null).getStatus());
            assertFalse(runtime.participating());
            assertFalse(leases.first().isArmed());
            assertEquals(200, endpoint.update(RESOURCE, true, null).getStatus());
            assertTrue(runtime.participating());
            assertTrue(leases.first().isArmed());
        }

        // 차단된 파티션을 푼다.
        remote[0] = "127.0.0.1";
        for (int i = 0; i < 3; i++) {
            runtime.breaker().record(DemotionReason.WRITER_FATAL);
        }
        assertTrue(runtime.breaker().isOpen());
        coordinator.settle(PromotionCoordinator.State.BLOCKED);
        leases.first().disarm();
        WebEndpointResponse<Map<String, Object>> reset = endpoint.update(RESOURCE, null, true);
        assertEquals(200, reset.getStatus());
        assertFalse(runtime.breaker().isOpen());
        assertEquals(PromotionCoordinator.State.FOLLOWER, runtime.state());
        assertTrue(leases.first().isArmed(), "차단기를 푼 파티션이 후보로 돌아오지 않았다");
    }

    @Test
    void nodeFatalIsNotReleasedAndBadRequestsAre400() {
        leases.first().markNodeFatal("시험: VM 오류");
        coordinator.markNodeFatal();
        WebEndpointResponse<Map<String, Object>> response = endpoint.update(RESOURCE, null, true);
        assertEquals(409, response.getStatus());
        assertEquals(PromotionCoordinator.State.DEMOTED, runtime.state());
        assertFalse(leases.first().isArmed());

        assertEquals(400, endpoint.update(RESOURCE, null, null).getStatus(), "조작 없는 요청");
        assertEquals(400, endpoint.update(RESOURCE, null, false).getStatus(), "reset_breaker=false 만 있는 요청");
        assertEquals(400, endpoint.update("matching-p0", true, null).getStatus(), "수명주기 없는 자원");
    }

    @Test
    void loopbackAcceptsNumericAddressesOnly() {
        assertTrue(PartitionsEndpoint.loopback("127.0.0.1"));
        assertTrue(PartitionsEndpoint.loopback("127.1.2.3"));
        assertTrue(PartitionsEndpoint.loopback("::1"));
        assertFalse(PartitionsEndpoint.loopback("localhost"), "이름은 조회가 일어나므로 받지 않는다");
        assertFalse(PartitionsEndpoint.loopback("10.0.0.1"));
        assertFalse(PartitionsEndpoint.loopback(null));
        assertFalse(PartitionsEndpoint.loopback(" "));
    }
}

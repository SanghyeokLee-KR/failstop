package kr.exchange.api.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.WriteAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 쓰기 게이트의 응답 계약(API 설계 §2-5-e). 상태별 응답 코드·본문·Retry-After, 안전 메서드의 통과, 좀비 판정 수요 계수를 고정한다.
 * 대상 경로는 필터 등록이 정하므로 실제 컨테이너의 경로 선택은 WriteGateContainerPathTest 가 본다.
 */
class WriteGateFilterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);
    private long probeIds;

    private WriteAvailability availability() {
        return WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS, nanos::get, wall::get);
    }

    private void activate(WriteAvailability w) {
        w.probeConnected();
        for (int i = 0; i < 2; i++) {
            long id = ++probeIds;
            w.probeSent(id);
            nanos.addAndGet(Duration.ofMillis(5).toNanos());
            w.probeCompleted(id);
        }
        nanos.addAndGet(Duration.ofMillis(5).toNanos());
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
    }

    private MockHttpServletResponse invoke(WriteGateFilter filter, String method, String path, MockFilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void activePassesAndRecordsTheGateGeneration() throws Exception {
        WriteAvailability w = availability();
        activate(w);
        w.settingRead("*", false);
        w.settingRead("\"patroni-db-2\"", false);
        long id = ++probeIds;
        w.probeSent(id);
        w.probeCompleted(id);
        id = ++probeIds;
        w.probeSent(id);
        w.probeCompleted(id);
        nanos.addAndGet(1);
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
        WriteGateFilter filter = new WriteGateFilter(w, new ErrorRenderer(json), new LeaderWriteProgress());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "ACTIVE 면 다음 필터로 넘긴다");
        assertEquals(1L, WriteGateFilter.gateGeneration(request), "재개 뒤 세대가 기록돼야 한다");
    }

    @Test
    void suspendedRejectsWritesWith423AndKeepsReadsOpen() throws Exception {
        WriteAvailability w = availability();
        activate(w);
        w.settingRead("*", false);
        LeaderWriteProgress progress = new LeaderWriteProgress();
        WriteGateFilter filter = new WriteGateFilter(w, new ErrorRenderer(json), progress);

        for (String[] write : new String[][]{{"POST", "/api/v1/orders"}, {"DELETE", "/api/v1/orders/abc"},
                {"POST", "/api/v1/transfers"}, {"POST", "/api/v1/community/posts"}, {"POST", "/api/v1/auth/login"},
                {"PUT", "/admin/v1/sim/effects"}, {"PATCH", "/api/v1/me"}}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = invoke(filter, write[0], write[1], chain);
            assertEquals(423, response.getStatus(), write[0] + " " + write[1]);
            assertNull(chain.getRequest(), "거절한 요청은 다음 필터에 닿지 않는다");
            assertEquals("5", response.getHeader("Retry-After"));
            JsonNode error = json.readTree(response.getContentAsString()).get("error");
            assertEquals("trading_suspended", error.get("name").asText());
            assertEquals("replication_unavailable", error.get("detail").get("reason").asText());
            assertEquals("/api/v1/status", error.get("detail").get("status_url").asText());
            assertTrue(error.get("detail").get("since").isNumber());
        }
        assertEquals(7, filter.suspendedRejections());
        assertEquals(0, progress.attempts(), "SUSPENDED 거절은 좀비 판정 수요가 아니다");

        // 게이트 밖 경로(/actuator/**, /harness/v1/**)는 필터 등록이 가른다. 그 시험은 WriteGateContainerPathTest 다.
        for (String[] read : new String[][]{{"GET", "/api/v1/orders/open"}, {"GET", "/api/v1/status"},
                {"HEAD", "/api/v1/markets"}, {"OPTIONS", "/api/v1/orders"}}) {
            MockFilterChain chain = new MockFilterChain();
            invoke(filter, read[0], read[1], chain);
            assertNotNull(chain.getRequest(), read[0] + " " + read[1] + "은 게이트 대상이 아니다");
        }
    }

    @Test
    void unreachableRejectsWith503AndCountsOrderWritesAsDemand() throws Exception {
        WriteAvailability w = availability();
        activate(w);
        w.checkFailed();
        w.checkFailed();
        assertEquals(WriteAvailability.State.UNREACHABLE, w.view().state());
        LeaderWriteProgress progress = new LeaderWriteProgress();
        WriteGateFilter filter = new WriteGateFilter(w, new ErrorRenderer(json), progress);

        MockHttpServletResponse order = invoke(filter, "POST", "/api/v1/orders", new MockFilterChain());
        MockHttpServletResponse transfer = invoke(filter, "POST", "/api/v1/transfers", new MockFilterChain());

        assertEquals(503, order.getStatus());
        assertEquals("1", order.getHeader("Retry-After"));
        assertEquals("service_unavailable",
                json.readTree(order.getContentAsString()).get("error").get("name").asText());
        assertEquals(503, transfer.getStatus());
        assertEquals(1, progress.attempts(), "주문 쓰기만 노드 층 수요로 센다");
        assertEquals(2, filter.unreachableRejections());
    }

    @Test
    void disabledAvailabilityIsAPassThrough() throws Exception {
        WriteGateFilter filter = new WriteGateFilter(WriteAvailability.disabled(), new ErrorRenderer(json),
                new LeaderWriteProgress());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertEquals(0L, WriteGateFilter.gateGeneration(request));
    }

    @Test
    void statusPathSkipsAdmissionBuckets() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        AdmissionControl bucket = new AdmissionControl("query", 1, AdmissionControl.Shed.CAPACITY, registry);
        AdmissionFilter admission = new AdmissionFilter(new AdmissionGate(true), bucket, bucket, bucket,
                new ErrorRenderer(json));
        assertNull(admission.bucketFor("GET", "/api/v1/status"),
                "상태 조회는 DB 에 닿지 않는다. 조회 버킷이 차도 화면이 재개를 알아야 한다");
        assertNotNull(admission.bucketFor("GET", "/api/v1/notices"));
    }

    @Test
    void requestWithoutGateHasNoGeneration() {
        assertEquals(WriteGateFilter.NO_GATE, WriteGateFilter.gateGeneration(new MockHttpServletRequest()));
        assertFalse(WriteGateFilter.NO_GATE >= 0, "세대는 0 부터라 표식과 겹치지 않는다");
    }
}

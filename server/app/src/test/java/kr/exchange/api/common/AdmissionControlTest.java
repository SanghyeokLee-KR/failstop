package kr.exchange.api.common;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 버킷 하나의 거절 계약·계량과 요청 분류 규칙을 컨테이너 없이 고정한다. */
class AdmissionControlTest {

    @Test
    void rejectionCarriesContractShapePerScope() {
        AdmissionControl query = new AdmissionControl("query", 2, new SimpleMeterRegistry());
        ApiException e = query.rejection();
        assertEquals(429, e.status());
        assertEquals("too_many_requests", e.name());
        assertEquals(1, e.retryAfter());
        assertEquals("node", e.detail().get("bucket"));
        assertEquals("query", e.detail().get("scope"), "스코프가 detail에 드러나지 않으면 두 버킷을 구분할 수 없다");
    }

    @Test
    void rejectsWithoutWaitingOnceExhausted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionControl admission = new AdmissionControl("order", 2, registry);
        assertTrue(admission.tryAcquire());
        assertTrue(admission.tryAcquire());

        long startNanos = System.nanoTime();
        assertFalse(admission.tryAcquire(), "상한 초과인데 permit이 나왔다");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // 대기열을 만들지 않는 것이 계약이다. 백오프가 아니라 즉시 거절이어야 한다.
        assertTrue(elapsedMs < 100, "초과 요청이 대기했다: " + elapsedMs + "ms");
        assertEquals(2, admission.inFlight());

        // 계량은 판정 지점에서 일어난다. 응답 렌더링이 실패해도 거절이 통계에서 사라지지 않는다.
        assertEquals(1.0, registry.get("exchange.admission.rejected")
                .tag("scope", "order").counter().count());
        assertEquals(2.0, registry.get("exchange.admission.in_flight")
                .tag("scope", "order").gauge().value());
        assertEquals(2.0, registry.get("exchange.admission.limit")
                .tag("scope", "order").gauge().value());

        admission.release();
        admission.release();
        assertEquals(0, admission.inFlight());
        assertTrue(admission.tryAcquire(), "반납 후 허용량이 회복되지 않았다");
        admission.release();
    }

    @Test
    void bucketsAreIndependent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionControl order = new AdmissionControl("order", 1, registry);
        AdmissionControl query = new AdmissionControl("query", 1, registry);
        assertTrue(order.tryAcquire());
        assertFalse(order.tryAcquire());
        // 쓰기가 포화여도 조회 슬롯은 그대로다. 한 버킷으로 합치면 이 성질이 사라진다.
        assertTrue(query.tryAcquire(), "쓰기 포화가 조회를 굶겼다");
        order.release();
        query.release();
    }

    @Test
    void classifiesRequestsIntoContractedBuckets() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionControl order = new AdmissionControl("order", 4, registry);
        AdmissionControl query = new AdmissionControl("query", 4, registry);
        AdmissionFilter filter = new AdmissionFilter(order, query, null);

        assertSame(order, filter.bucketFor("POST", "/api/v1/orders"));
        assertSame(order, filter.bucketFor("POST", "/api/v1/orders/batch"));
        assertSame(order, filter.bucketFor("DELETE", "/api/v1/orders/1a2b"));
        assertSame(order, filter.bucketFor("DELETE", "/api/v1/orders/by-client/abc"));

        assertSame(query, filter.bucketFor("GET", "/api/v1/orderbook"));
        assertSame(query, filter.bucketFor("GET", "/api/v1/ticker"));
        assertSame(query, filter.bucketFor("GET", "/api/v1/orders/open"));
        assertSame(query, filter.bucketFor("GET", "/api/v1/orders/1a2b"));
        assertSame(query, filter.bucketFor("POST", "/api/v1/auth/login"));

        // §7-6 재조회 수렴 경로와 CORS 프리플라이트는 면제.
        assertNull(filter.bucketFor("GET", "/api/v1/orders/by-client/abc"),
                "재조회 수렴 경로가 조회 버킷에 잡히면 멱등 규약이 깨진다");
        assertNull(filter.bucketFor("OPTIONS", "/api/v1/orders"));
    }

    /** 면제 경로는 필터 URL 패턴 밖이라 게이트에 도달조차 하지 않아야 한다. */
    @Test
    void survivalSignalPathsAreOutsideTheGate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionFilter filter = new AdmissionFilter(
                new AdmissionControl("order", 4, registry),
                new AdmissionControl("query", 4, registry), null);
        for (String path : new String[]{
                "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness",
                "/actuator/prometheus", "/harness/v1/status", "/admin/v1/ha/status"}) {
            assertTrue(filter.shouldNotFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", path)),
                    path + "가 수용 게이트 대상이다. 생존 신호가 막히면 노드 판정 자체가 무너진다");
        }
        assertFalse(filter.shouldNotFilter(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/orderbook")));
    }
}

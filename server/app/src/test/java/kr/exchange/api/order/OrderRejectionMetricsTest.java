package kr.exchange.api.order;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * exchange.orders.rejected 계량 계약 검증(하네스 CICD §5-2). 종결 거부만 세고, 429는 reason을
 * rate_limited로 정규화하며, 가용성 신호(409·503)와 인증·부재(401·404)는 제외한다.
 */
class OrderRejectionMetricsTest {

    private static ApiException ex(int status, String name) {
        return new ApiException(status, name, name);
    }

    @Test
    void rateLimitedNormalizesReasonAndAccumulates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderRejectionMetrics metrics = new OrderRejectionMetrics(registry);

        // 429는 에러코드 too_many_requests가 아니라 대시보드 PromQL 정렬용 rate_limited로 태그된다.
        metrics.record(ex(429, "too_many_requests"));
        metrics.record(ex(429, "too_many_requests"));

        assertEquals(2.0, registry.get("exchange.orders.rejected")
                .tag("reason", "rate_limited").counter().count());
        // 원 에러코드 이름으로는 카운터가 생기지 않는다(정규화가 실제 동작).
        assertNull(registry.find("exchange.orders.rejected")
                .tag("reason", "too_many_requests").counter());
    }

    @Test
    void otherTerminalRejectsUseErrorName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderRejectionMetrics metrics = new OrderRejectionMetrics(registry);

        metrics.record(ex(400, "invalid_parameter"));
        metrics.record(ex(403, "forbidden"));
        metrics.record(ex(422, "insufficient_balance"));
        metrics.record(ex(413, "payload_too_large"));

        assertEquals(1.0, registry.get("exchange.orders.rejected")
                .tag("reason", "invalid_parameter").counter().count());
        assertEquals(1.0, registry.get("exchange.orders.rejected")
                .tag("reason", "forbidden").counter().count());
        assertEquals(1.0, registry.get("exchange.orders.rejected")
                .tag("reason", "insufficient_balance").counter().count());
        assertEquals(1.0, registry.get("exchange.orders.rejected")
                .tag("reason", "payload_too_large").counter().count());
    }

    @Test
    void availabilityAndAuthStatusesAreNotMetered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderRejectionMetrics metrics = new OrderRejectionMetrics(registry);

        // 409 order_in_progress·503 service_unavailable은 재조회 수렴 신호라 거부율을 오염시키지 않는다.
        metrics.record(ex(409, "order_in_progress"));
        metrics.record(ex(503, "service_unavailable"));
        // 401·404는 주문 접수 거부가 아니다.
        metrics.record(ex(401, "unauthorized"));
        metrics.record(ex(404, "not_found"));

        // 어떤 카운터도 등록되지 않아야 한다(계량 대상 집합 밖).
        assertNull(registry.find("exchange.orders.rejected").counter());
    }
}

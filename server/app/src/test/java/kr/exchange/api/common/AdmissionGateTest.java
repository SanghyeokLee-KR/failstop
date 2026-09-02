package kr.exchange.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 수용 게이트 런타임 스위치(API 설계 2-5-a v2.41).
 *
 * 고정하는 계약은 셋이다. 기본은 켜짐. 꺼지면 버킷을 타지 않고 통과한다(슬롯 획득·반납 없음).
 * 다시 켜면 상한이 즉시 돌아온다.
 */
class AdmissionGateTest {

    private static AdmissionFilter filter(AdmissionGate gate, AdmissionControl order) {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        AdmissionControl query = new AdmissionControl("query", 1, AdmissionControl.Shed.CAPACITY, reg);
        AdmissionControl requery = new AdmissionControl("requery", 1, AdmissionControl.Shed.REQUERY_DEFERRED, reg);
        return new AdmissionFilter(gate, order, query, requery, new ErrorRenderer(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    private static MockHttpServletRequest post() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/orders");
        r.setRequestURI("/api/v1/orders");
        return r;
    }

    @Test
    void 기본은_켜짐이다() {
        assertThat(new AdmissionGate(true).enabled()).isTrue();
    }

    @Test
    void 꺼지면_상한을_넘어도_통과하고_슬롯을_쓰지_않는다() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        AdmissionControl order = new AdmissionControl("order", 1, AdmissionControl.Shed.CAPACITY, reg);
        assertThat(order.tryAcquire()).isTrue(); // 상한 1을 미리 채운다
        AdmissionGate gate = new AdmissionGate(false);
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (q, s) -> passed.incrementAndGet();
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(gate, order).doFilter(post(), res, chain);
        assertThat(passed.get()).as("상한이 찼는데도 통과").isEqualTo(1);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(order.inFlight()).as("꺼진 동안은 획득·반납이 없다").isEqualTo(1);
    }

    @Test
    void 다시_켜면_상한이_즉시_돌아온다() throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        AdmissionControl order = new AdmissionControl("order", 1, AdmissionControl.Shed.CAPACITY, reg);
        assertThat(order.tryAcquire()).isTrue(); // 상한 1을 미리 채운다
        AdmissionGate gate = new AdmissionGate(false);
        AdmissionFilter f = filter(gate, order);
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (q, s) -> passed.incrementAndGet();

        gate.set(true, "test");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(post(), res, chain);
        assertThat(passed.get()).as("켜졌으니 찬 상한에서 거절").isZero();
        assertThat(res.getStatus()).isEqualTo(429);
    }

    @Test
    void 전환은_이전값을_돌려준다() {
        AdmissionGate gate = new AdmissionGate(true);
        assertThat(gate.set(false, "test")).isTrue();
        assertThat(gate.set(false, "test")).isFalse();
        assertThat(gate.enabled()).isFalse();
    }
}

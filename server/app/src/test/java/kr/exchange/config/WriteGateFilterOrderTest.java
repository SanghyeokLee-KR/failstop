package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionGate;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.RequestSizeLimitFilter;
import kr.exchange.api.common.WriteGateFilter;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.WriteAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 쓰기 게이트의 필터 순서(API 설계 §2-5-e, Patroni 설계 4-4). CORS(0) 뒤, 바디 상한(4)·소유권 선검사(5)·수용 제한(6)·IP
 * 스로틀(7)·인증(10) 앞이어야 한다. 수용 제한 뒤로 밀리면 중단 직전에 걸린 요청이 쥔 허가 때문에 중단 동안의 주문이 423 대신 429 를 받는다.
 */
class WriteGateFilterOrderTest {

    @Test
    void gateRunsBeforeBodyLimitAdmissionThrottleAndAuth() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ErrorRenderer renderer = new ErrorRenderer(new ObjectMapper());
        FilterRegistrationBean<WriteGateFilter> gate = new WriteAvailabilityConfig().writeGateFilter(
                WriteAvailability.disabled(), renderer, new LeaderWriteProgress(), registry);
        ApiConfig api = new ApiConfig();
        AdmissionControl bucket = new AdmissionControl("order", 1, AdmissionControl.Shed.CAPACITY, registry);
        int admission = api.admissionFilter(new AdmissionGate(true), bucket, bucket, bucket, renderer).getOrder();
        int bodyLimit = RequestSizeLimitFilter.class.getAnnotation(Order.class).value();
        int publicThrottle = api.publicRateLimitFilter(null, renderer).getOrder();
        int auth = api.apiAuthFilter(null, renderer, new LeaderWriteProgress()).getOrder();
        int ownership = api.marketRoutingFilter(renderer, null, null, null, null).getOrder();

        assertEquals(3, gate.getOrder());
        assertTrue(0 < gate.getOrder(), "CORS 필터(0) 뒤라 거절 응답에도 CORS 헤더가 붙는다");
        assertTrue(gate.getOrder() < bodyLimit, "바디를 읽기 전에 거절한다");
        assertTrue(gate.getOrder() < ownership && bodyLimit < ownership && ownership < admission,
                "중단은 파티션과 무관한 클러스터 상태라 소유권 선검사보다 먼저 답한다");
        assertTrue(bodyLimit < admission && admission < publicThrottle && publicThrottle < auth);
        assertTrue(gate.getOrder() < admission, "수용 허가를 쥐기 전에 거절해야 423 이 429 에 가리지 않는다");
        // 대상 경로는 등록만 정한다(WriteGateFilter). 더하면 /actuator/**·/harness/v1/** 까지 막고 빼면 그 평면이 게이트를 비껴간다.
        assertEquals(java.util.Set.of("/api/v1/*", "/admin/v1/*"), java.util.Set.copyOf(gate.getUrlPatterns()),
                "관리 평면의 변경도 같은 게이트를 지난다");
    }
}

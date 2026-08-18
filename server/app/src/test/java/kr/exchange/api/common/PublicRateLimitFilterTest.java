package kr.exchange.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공개 조회 IP 버킷의 계약 고정(§2-5). 적용 범위·weight·429 형태를 여기서 못박는다.
 * 실 서버 배선(헤더 방출·필터 순서 6 -> 7 -> 10)은 ApiGatewayIntegrationTest가 본다.
 */
class PublicRateLimitFilterTest {

    private static final String IP = "203.0.113.7";

    // 시계를 고정해 리필을 0으로 만든다. 실제 시계로 재면 느린 러너에서 창 중간 리필이 잔량
    // 단조 감소를 흔들어 weight 검증이 간헐 실패한다.
    private static AccountRateLimiter limiter(int capacity) {
        return new AccountRateLimiter(capacity, 10_000, "ip", "public", () -> 1_000L);
    }

    @Test
    void publicMarketDataConsumesTokensByRowShape() throws Exception {
        PublicRateLimitFilter filter = filter(limiter(300));

        // 다중행 리스트는 2, 단건 객체는 2가 아니라 1이다(§2-5 '공개 조회 1~2').
        assertEquals(298, remainingAfter(filter, "GET", "/api/v1/markets"));
        assertEquals(296, remainingAfter(filter, "GET", "/api/v1/ticker"));
        assertEquals(294, remainingAfter(filter, "GET", "/api/v1/trades"));
        assertEquals(292, remainingAfter(filter, "GET", "/api/v1/candles/1m"));
        assertEquals(291, remainingAfter(filter, "GET", "/api/v1/orderbook"));
        assertEquals(290, remainingAfter(filter, "GET", "/api/v1/exchange-config"));
    }

    @Test
    void nonPublicPlaneAndPreflightPassUntouched() throws Exception {
        PublicRateLimitFilter filter = filter(limiter(300));

        // 인증 평면은 계정 버킷 소관이다. 여기서 IP로 한 번 더 조이면 프록시 뒤 다계정
        // 트래픽이 서로의 예산을 갉는다(§2-5 '봇/데모는 계정 버킷 우선').
        for (String path : new String[]{"/api/v1/orders", "/api/v1/orders/open", "/api/v1/accounts",
                "/api/v1/my/trades", "/api/v1/community/posts/1", "/api/v1/auth/login",
                "/actuator/health"}) {
            MockHttpServletResponse response = invoke(filter, "GET", path);
            assertNull(response.getHeader("X-RateLimit-Limit-ip"),
                    path + "은 IP 버킷 대상이 아니다");
        }
        MockHttpServletResponse preflight = invoke(filter, "OPTIONS", "/api/v1/orderbook");
        assertNull(preflight.getHeader("X-RateLimit-Limit-ip"),
                "프리플라이트를 막으면 브라우저가 본 요청을 보내지 못한다");
    }

    @Test
    void exhaustionRejectsWithContractedBucketAndScope() throws Exception {
        PublicRateLimitFilter filter = filter(limiter(4));

        assertEquals(200, invoke(filter, "GET", "/api/v1/markets").getStatus());
        assertEquals(200, invoke(filter, "GET", "/api/v1/markets").getStatus());

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/v1/markets"), denied, chain);

        assertEquals(429, denied.getStatus());
        assertNull(chain.getRequest(), "거절된 요청이 컨트롤러까지 갔다");
        String body = denied.getContentAsString();
        assertTrue(body.contains("\"too_many_requests\""), body);
        assertTrue(body.contains("\"bucket\":\"ip\""), body);
        assertTrue(body.contains("\"scope\":\"public\""), body);
        assertTrue(Integer.parseInt(denied.getHeader("Retry-After")) >= 1);
        assertEquals("4", denied.getHeader("X-RateLimit-Limit-ip"));
        assertEquals("0", denied.getHeader("X-RateLimit-Remaining-ip"));
        assertNotNull(denied.getHeader("X-RateLimit-Reset"));
    }

    @Test
    void bucketKeyIsClientIpNotSharedGlobally() throws Exception {
        PublicRateLimitFilter filter = filter(limiter(4));

        invoke(filter, "GET", "/api/v1/markets");
        invoke(filter, "GET", "/api/v1/markets");

        // 다른 IP는 자기 버킷을 온전히 갖는다. 전역 단일 버킷이면 한 클라이언트의 폭주가
        // 나머지 전부를 정지시킨다.
        MockHttpServletRequest other = request("GET", "/api/v1/markets");
        other.setRemoteAddr("198.51.100.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(other, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
        assertEquals("2", response.getHeader("X-RateLimit-Remaining-ip"));
    }

    private static PublicRateLimitFilter filter(AccountRateLimiter limiter) {
        return new PublicRateLimitFilter(limiter, new ErrorRenderer(new ObjectMapper()));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr(IP);
        return request;
    }

    private static MockHttpServletResponse invoke(PublicRateLimitFilter filter, String method,
                                                  String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(method, path), response, new MockFilterChain());
        return response;
    }

    private static int remainingAfter(PublicRateLimitFilter filter, String method, String path)
            throws Exception {
        return Integer.parseInt(invoke(filter, method, path).getHeader("X-RateLimit-Remaining-ip"));
    }
}

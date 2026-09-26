package kr.exchange.config;

import kr.exchange.dev.DevConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 브라우저가 교차 출처로 마켓 힌트를 싣고 소유 아님 표식을 읽을 수 있다(API 설계 §2-5-d, S5 설계 4-9·6-2). dev·prod CORS 설정이
 * 프리플라이트의 X-Exchange-Market 을 허용하고 응답의 X-Exchange-Route 를 노출한다.
 */
class CorsHintHeaderTest {

    @Test
    void devAllowsTheHintAndExposesTheRoute() throws Exception {
        assertHintHeaders(new DevConfig().devCorsFilter().getFilter(), "http://localhost:3000");
    }

    @Test
    void prodAllowsTheHintAndExposesTheRoute() throws Exception {
        assertHintHeaders(new ProdCorsConfig().prodCorsFilter(List.of("https://exchange.example")).getFilter(),
                "https://exchange.example");
    }

    private static void assertHintHeaders(CorsFilter filter, String origin) throws Exception {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/orders");
        preflight.addHeader("Origin", origin);
        preflight.addHeader("Access-Control-Request-Method", "POST");
        preflight.addHeader("Access-Control-Request-Headers", "authorization, content-type, x-exchange-market");
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(preflight, allowed, new MockFilterChain());
        assertEquals(200, allowed.getStatus(), "프리플라이트가 거부됐다");
        String allowHeaders = allowed.getHeader("Access-Control-Allow-Headers");
        assertNotNull(allowHeaders, "허용 헤더가 없다");
        assertTrue(allowHeaders.toLowerCase(Locale.ROOT).contains("x-exchange-market"), allowHeaders);

        MockHttpServletRequest actual = new MockHttpServletRequest("POST", "/api/v1/orders");
        actual.addHeader("Origin", origin);
        MockHttpServletResponse exposed = new MockHttpServletResponse();
        filter.doFilter(actual, exposed, new MockFilterChain());
        String expose = exposed.getHeader("Access-Control-Expose-Headers");
        assertNotNull(expose, "노출 헤더가 없다");
        assertTrue(expose.contains("X-Exchange-Route"), expose);
    }
}

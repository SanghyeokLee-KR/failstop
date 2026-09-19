package kr.exchange.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ReadRoleFilterTest {

    @Test
    void onlyMarketReadsAndManagementProbesAreAllowed() {
        for (String path : new String[]{"/api/v1/markets", "/api/v1/trades", "/api/v1/candles/1m",
                "/actuator/health/read", "/actuator/health/liveness", "/actuator/prometheus"}) {
            assertTrue(ReadRoleFilter.allowed("GET", path), path);
            assertTrue(ReadRoleFilter.allowed("HEAD", path), path);
            assertFalse(ReadRoleFilter.allowed("POST", path), path);
        }
    }

    @Test
    void rejectsWritesSessionsLocalSequencesAndAmbiguousPathsBeforeTheChain() throws Exception {
        for (String path : new String[]{"/api/v1/orders", "/api/v1/member/login", "/admin/v1/sim/start",
                "/ws/v1", "/api/v1/ticker", "/api/v1/orderbook", "/api/v1/markets/",
                "/api/v1/markets;x=1", "/api/v1/candles/1m/../orders", "/api/v1/candles/%31m",
                "/api/v1//markets", "/actuator/metrics", "/error"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            new ReadRoleFilter(new ErrorRenderer(new ObjectMapper())).doFilter(request, response, chain);
            assertEquals(404, response.getStatus(), path);
            assertNull(chain.getRequest(), path);
        }
    }
}

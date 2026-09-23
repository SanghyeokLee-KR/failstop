package kr.exchange.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.engine.LeaderWriteProgress;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 인증 단계의 잠금 대기 초과(55P03)도 컨트롤러 계층과 같은 503 이다(ADR 0034 S1).
 *
 * Spring 6 기본 번역기는 55P03 을 분류하지 못해 UncategorizedSQLException 으로 올린다. 인증 필터가 그것을 500 으로
 * 내면 같은 일시 사유가 계층에 따라 503 과 500 으로 갈린다.
 */
class ApiAuthFilterLockTimeoutTest {

    @Test
    void lockTimeoutDuringAuthenticationIs503WithRetryAfter() throws Exception {
        ApiAuthService authService = mock(ApiAuthService.class);
        when(authService.verify(anyString(), anyString(), anyBoolean())).thenThrow(new UncategorizedSQLException(
                "api_keys 조회", "SELECT ... FROM api_keys",
                new SQLException("canceling statement due to lock timeout", "55P03")));
        ApiAuthFilter filter = new ApiAuthFilter(authService, new ErrorRenderer(new ObjectMapper()),
                new LeaderWriteProgress());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader("Authorization", "Bearer test");
        request.setContent("{\"market\":\"KRW-BTC\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("service_unavailable"),
                response.getContentAsString(StandardCharsets.UTF_8));
        assertNull(chain.getRequest(), "인증이 실패했는데 다음 필터로 넘어갔다");
    }
}

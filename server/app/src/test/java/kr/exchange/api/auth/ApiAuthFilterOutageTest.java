package kr.exchange.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.journal.DbFailures;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인증 조회의 DB 연결 장애는 메서드와 무관하게 503 이다(API 설계 §2-5-c). 인증은 읽기만 하고 nonce 는 노드 메모리라 인그레스가
 * 다른 노드로 넘겨도 아무것도 두 번 일어나지 않는다. DB 밖 예외는 종전처럼 500 이다. 주문 쓰기 수요는 인증 전에 센다.
 */
class ApiAuthFilterOutageTest {

    @Test
    void databaseOutageDuringAuthIs503ForEveryMethod() throws Exception {
        Map<String, Supplier<RuntimeException>> outages = new LinkedHashMap<>();
        outages.put("08006", DbFailures::connectionFailure);
        outages.put("롤백 실패", () -> DbFailures.rollbackFailed(DbFailures.connectionFailure()));
        outages.put("획득 실패", DbFailures::acquireFailure);
        outages.put("57014", () -> DbFailures.sql("57014"));
        for (Map.Entry<String, Supplier<RuntimeException>> outage : outages.entrySet()) {
            for (String method : new String[] {"POST", "GET"}) {
                LeaderWriteProgress progress = new LeaderWriteProgress();
                MockHttpServletResponse response = run(method, outage.getValue(), progress);

                String scene = outage.getKey() + " " + method;
                assertEquals(503, response.getStatus(), scene);
                assertEquals("1", response.getHeader("Retry-After"), scene);
                assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("service_unavailable"), scene);
                assertEquals("POST".equals(method) ? 1 : 0, progress.attempts(), scene + " 주문 쓰기 수요");
            }
        }
    }

    @Test
    void failuresOutsideTheDatabaseStay500() throws Exception {
        MockHttpServletResponse response = run("POST", () -> new IllegalArgumentException("주입: HMAC 계산 실패"),
                new LeaderWriteProgress());
        assertEquals(500, response.getStatus());
        assertNull(response.getHeader("Retry-After"));
    }

    private static MockHttpServletResponse run(String method, Supplier<RuntimeException> failure,
                                               LeaderWriteProgress progress) throws Exception {
        ApiAuthFilter filter = new ApiAuthFilter(new ApiAuthService(null, null, null, null, null, null) {
            @Override
            public AuthPrincipal verify(String authorizationHeader, String canonical, boolean needsTrade) {
                throw failure.get();
            }
        }, new ErrorRenderer(new ObjectMapper()), progress);
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/orders");
        request.addHeader("Authorization", "Bearer test");
        if ("POST".equals(method)) {
            request.setContentType("application/json");
            request.setContent("{\"market\":\"KRW-BTC\"}".getBytes(StandardCharsets.UTF_8));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

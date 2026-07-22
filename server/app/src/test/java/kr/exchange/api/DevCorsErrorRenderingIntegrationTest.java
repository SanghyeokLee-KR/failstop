package kr.exchange.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dev CORS와 컨테이너 에러 렌더 회귀. RANDOM_PORT로 실 임베디드 Tomcat을 띄운다.
 * MockMvc 기반 테스트는 서블릿 컨테이너와 valve 계층을 우회해 Tomcat ErrorReportValve
 * 렌더 경로(CORS 프리플라이트·거부·미매핑 응답)를 한 번도 타지 않는다. 그 사각에서
 * tomcat-embed-core가 BOM 밖 버전으로 오염되면 에러 렌더가 NoClassDefFoundError로 터져
 * 빈 본문 500이 되고 브라우저 REST가 전멸한다. 이 테스트가 그 경로를 실측해 재현·회귀를 막는다.
 *
 * devCorsFilter는 활성 프로파일 dev에서만 등록되므로(DevProfileActiveCondition) 여기서
 * dev를 활성화한다. 기본 프로파일 dev로만 도는 기존 통합테스트는 필터가 꺼져 이 경로를 못 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevCorsErrorRenderingIntegrationTest {

    private static final String WEB_ORIGIN = "http://localhost:3000";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void originDecoratedGetCarriesAllowOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, WEB_ORIGIN);
        ResponseEntity<String> response = rest.exchange("/api/v1/markets", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(WEB_ORIGIN, response.getHeaders().getAccessControlAllowOrigin());
        assertNotNull(response.getBody());
        // 성공 응답도 Origin이 붙으면 valve 렌더가 개입할 여지가 있어 본문 정상 여부를 확인한다.
        assertTrue(response.getBody().contains("\"data\""), response.getBody());
    }

    @Test
    void preflightIsShortCircuitedWithCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, WEB_ORIGIN);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        ResponseEntity<String> response = rest.exchange("/api/v1/markets", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);

        int status = response.getStatusCode().value();
        assertTrue(status == 200 || status == 204, "preflight status=" + status);
        assertEquals(WEB_ORIGIN, response.getHeaders().getAccessControlAllowOrigin());
        assertTrue(response.getHeaders().getAccessControlAllowMethods().contains(HttpMethod.GET));
    }

    @Test
    void unknownPathRendersWithoutValveCrash() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, WEB_ORIGIN);
        ResponseEntity<String> response = rest.exchange("/no-such-endpoint-xyz", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        // 오염된 valve는 이 경로를 빈 본문 500으로 떨군다. 표준 404 엔벌로프 렌더로 회귀를 잡는다.
        assertEquals(404, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isBlank());
        assertTrue(response.getBody().contains("\"not_found\""), response.getBody());
        assertFalse(response.getBody().contains("NoClassDefFoundError"));
        assertFalse(response.getBody().contains("RequestUtil"));
    }
}

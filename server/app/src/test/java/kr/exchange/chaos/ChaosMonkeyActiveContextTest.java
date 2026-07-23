package kr.exchange.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chaos-monkey 프로파일 활성 시 배선이 깨끗이 로드되고 프로브 평면이 동작함을 실증한다(격리 chaos 스택
 * 서빙 노드와 동일 프로파일 조합 dev,chaos-monkey). 어셀트 무장 전이라 CB는 closed, 프로브는 200을
 * 반환한다. 프로퍼티명 오타 등 배선 결함은 컨텍스트 기동 실패로 여기서 잡힌다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "chaos-monkey"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChaosMonkeyActiveContextTest {

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
    ApplicationContext ctx;
    @Autowired
    TestRestTemplate rest;

    @Test
    void chaosProfileLoadsProbePlaneDisarmed() {
        assertFalse(ctx.getBeansOfType(ChaosProbeController.class).isEmpty(),
                "chaos 프로파일에서 프로브 컨트롤러가 떠야 한다");

        ResponseEntity<String> endpoint = rest.getForEntity("/actuator/chaosmonkey", String.class);
        assertEquals(200, endpoint.getStatusCode().value(),
                "chaosmonkey actuator 엔드포인트가 노출돼야 한다");

        // 무장 전이라 downstream 정상, CB closed, 프로브 200.
        ResponseEntity<String> probe = rest.getForEntity("/internal/chaos/probe", String.class);
        assertEquals(200, probe.getStatusCode().value(), "무장 전 프로브는 200이어야 한다");
        assertTrue(probe.getBody() != null && probe.getBody().contains("ok"),
                "무장 전 프로브 응답은 downstream ok를 담아야 한다");
    }
}

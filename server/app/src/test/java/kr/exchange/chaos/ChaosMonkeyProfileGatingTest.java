package kr.exchange.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 데모(dev) 프로파일에서 chaos-monkey가 로드·노출되지 않음을 실증한다. spring.profiles.default=dev라
 * @ActiveProfiles 없이 기동하면 데모와 동일 프로파일이다. ChaosMonkeyCondition이 chaos-monkey 프로파일
 * 미활성으로 자동설정을 배제하므로 프로브 빈·actuator 엔드포인트가 전무해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChaosMonkeyProfileGatingTest {

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
    void demoProfileDoesNotLoadChaosMonkey() {
        assertTrue(ctx.getBeansOfType(ChaosProbeController.class).isEmpty(),
                "chaos 프로브 컨트롤러가 데모 프로파일에서 뜨면 안 된다");
        assertTrue(ctx.getBeansOfType(ChaosProbeGateway.class).isEmpty(),
                "chaos 프로브 게이트웨이가 데모 프로파일에서 뜨면 안 된다");

        ResponseEntity<String> endpoint = rest.getForEntity("/actuator/chaosmonkey", String.class);
        assertEquals(404, endpoint.getStatusCode().value(),
                "chaosmonkey actuator 엔드포인트가 데모 프로파일에서 노출되면 안 된다");

        ResponseEntity<String> probe = rest.getForEntity("/internal/chaos/probe", String.class);
        assertEquals(404, probe.getStatusCode().value(),
                "chaos 프로브 경로가 데모 프로파일에서 매핑되면 안 된다");
    }
}

package kr.exchange.chaos;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E8 판정 로직을 앱 계층에서 결정적으로 실증한다. pwsh·Docker 스택 없이 assault 주입 -> CB open ->
 * fail-fast 503 -> 해제 후 자동 복귀와, watcher.beans 격리에 의한 비전파(health UP 유지)를 검증한다.
 * 사용하는 assault JSON은 e8-app-assault.ps1이 보내는 것과 동형이라 스크립트 라이브 경로의 계약도 겸증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "chaos-monkey"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChaosAssaultCircuitBreakerTest {

    private static final String CM = "/actuator/chaosmonkey";
    private static final String PROBE = "/internal/chaos/probe";

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
    @Autowired
    CircuitBreakerRegistry cbRegistry;

    private int probe() {
        return rest.getForEntity(PROBE, String.class).getStatusCode().value();
    }

    private void enable() {
        rest.postForEntity(CM + "/enable", null, String.class);
    }

    private void disable() {
        rest.postForEntity(CM + "/disable", null, String.class);
    }

    // e8-app-assault.ps1의 exception assault 바디와 동형(level 1 = 매 호출 주입).
    private void exceptionAssault(boolean on) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"level\":1,\"latencyActive\":false,\"exceptionsActive\":" + on + "}";
        rest.postForEntity(CM + "/assaults", new HttpEntity<>(body, h), String.class);
    }

    @Test
    void assaultOpensCircuitFailsFast503AndRecoversWithoutPropagating() {
        CircuitBreaker cb = cbRegistry.circuitBreaker("chaosProbe");
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState(), "사전 CB는 closed");
        assertEquals(200, probe(), "사전 프로브는 200");

        try {
            enable();
            exceptionAssault(true);

            // 프로브 반복 -> failure 누적 -> CB open.
            Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                    .until(() -> {
                        probe();
                        return cb.getState() == CircuitBreaker.State.OPEN;
                    });

            assertEquals(503, probe(), "CB open 상태 프로브는 fail-fast 503");
            // 비전파: assault 중에도 health는 UP(200). watcher.beans가 프로브 다운스트림만 위빙.
            assertEquals(200, rest.getForEntity("/actuator/health", String.class).getStatusCode().value(),
                    "assault 중 health는 UP이어야 한다(매칭·평면 비전파)");

            exceptionAssault(false);
            disable();

            // 자동 half-open 후 프로브 성공 트래픽으로 closed 복귀.
            Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        probe();
                        return cb.getState() == CircuitBreaker.State.CLOSED;
                    });

            assertEquals(200, probe(), "복귀 후 프로브는 200");
        } finally {
            try {
                exceptionAssault(false);
                disable();
            } catch (RuntimeException ignore) {
                // 정리 실패는 다음 테스트 컨텍스트가 재기동으로 흡수한다.
            }
        }
    }
}

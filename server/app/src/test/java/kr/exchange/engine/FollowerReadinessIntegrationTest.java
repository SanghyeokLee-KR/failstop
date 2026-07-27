package kr.exchange.engine;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 팔로워·강등 노드의 readiness 실측 검증(DB 설계 §5.4, 복구 RTO §3-a).
 *
 * Spring은 ApplicationReadyEvent 리스너가 전부 반환한 뒤에 ACCEPTING_TRAFFIC을 자동 발행한다.
 * ready 리스너에서 되돌리는 방식은 그 발행에 덮여 팔로워를 readiness UP으로 오보했다(라이브에서
 * 팔로워 노드가 docker healthy로 남아 LB가 계속 그쪽으로 보낸 원인). 이 테스트는 컨텍스트 기동
 * 전에 리스를 선점해 노드를 확정적으로 팔로워로 만들고, actuator 프로브가 실제로 503을 내는지
 * 본다. 강등(DEMOTED) 노드도 같은 전이를 밟으므로 승격 후 상실 경로까지 함께 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FollowerReadinessIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
        // 스키마를 먼저 올린 뒤 리스를 장기 선점한다. 컨텍스트의 Flyway는 적용분이 없어 무동작이고,
        // 부팅 승격은 유효한 타 리더 때문에 실패해 이 노드가 팔로워로 확정된다.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE leader_lease SET holder = 'squatter-node', epoch = epoch + 1, "
                    + "lease_until = clock_timestamp() + interval '30 minutes' "
                    + "WHERE resource = 'matching-engine'");
        } catch (Exception e) {
            throw new IllegalStateException("리스 선점 실패", e);
        }
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
    LeaderLeaseService lease;
    @Autowired
    ApplicationAvailability availability;
    @Autowired
    HaAvailability haAvailability;
    @Autowired
    PromotionCoordinator promotionCoordinator;
    @Autowired
    TestRestTemplate rest;

    // 순서를 고정한다. 강등 테스트가 먼저 돌면 컨텍스트 상태가 이미 REFUSING으로 굳어
    // 팔로워 기동 판별력이 사라진다(둘 다 통과하지만 아무것도 증명하지 못한다).
    @Test
    @Order(1)
    void followerIsRefusingTrafficAndProbeReturns503() {
        assertFalse(lease.isLeader(), "선점된 리스가 있는데 이 노드가 리더가 됐다");

        assertEquals(ReadinessState.REFUSING_TRAFFIC, availability.getReadinessState(),
                "팔로워가 ACCEPTING_TRAFFIC으로 남았다(Spring 자동 발행 미정정)");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readinessProbe().getStatusCode(),
                "팔로워 readiness 프로브가 UP을 반환했다");
    }

    @Test
    @Order(2)
    void demotedNodeReturnsToRefusingAfterAccepting() {
        // 승격으로 접수를 연 상태를 만든 뒤, 실제 강등 콜백(리스 상실)으로 되돌린다.
        haAvailability.accepting();
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, availability.getReadinessState());
        assertEquals(HttpStatus.OK, readinessProbe().getStatusCode());

        promotionCoordinator.onLeadershipLost();

        assertEquals(ReadinessState.REFUSING_TRAFFIC, availability.getReadinessState(),
                "강등 노드가 ACCEPTING_TRAFFIC으로 남았다");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readinessProbe().getStatusCode(),
                "강등 노드 readiness 프로브가 UP을 반환했다");
        assertEquals(PromotionCoordinator.State.DEMOTED, promotionCoordinator.state());
    }

    private ResponseEntity<String> readinessProbe() {
        return rest.getForEntity("/actuator/health/readiness", String.class);
    }
}

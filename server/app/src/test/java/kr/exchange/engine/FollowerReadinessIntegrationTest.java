package kr.exchange.engine;

import org.awaitility.Awaitility;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 팔로워·강등 노드의 readiness 실측 검증(DB 설계 §5.4, §5.4-c 5, 복구 RTO §3-a).
 *
 * Spring은 ApplicationReadyEvent 리스너가 전부 반환한 뒤에 ACCEPTING_TRAFFIC을 자동 발행한다.
 * ready 리스너에서 되돌리는 방식은 그 발행에 덮여 팔로워를 readiness UP으로 오보했다(라이브에서
 * 팔로워 노드가 docker healthy로 남아 LB가 계속 그쪽으로 보낸 원인). 이 테스트는 컨텍스트 기동
 * 전에 리스를 선점해 노드를 확정적으로 팔로워로 만들고, actuator 프로브가 실제로 503을 내는지 본다.
 *
 * 강등은 실제 경로로 만든다(파티션 단위 리더). 선점 리스를 만료시켜 이 노드가 런타임 승격으로 접수를 열게 한 뒤, 다른
 * 노드가 리스를 가져간 상황을 만들어 갱신 거부로 강등시킨다. 강등한 파티션은 게이트를 닫고 REBUILDING 을 거쳐 FOLLOWER 로
 * 돌아오고 후보 표식을 다시 세운다. readiness 는 그동안 REFUSING 이어야 한다.
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
        // 이 노드의 리스 틱은 유효한 타 리더 때문에 획득하지 못해 팔로워로 남는다.
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
    PartitionLeases leases;
    @Autowired
    ApplicationAvailability availability;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TestRestTemplate rest;

    // 순서를 고정한다. 강등 테스트가 먼저 돌면 컨텍스트 상태가 이미 REFUSING으로 굳어
    // 팔로워 기동 판별력이 사라진다(둘 다 통과하지만 아무것도 증명하지 못한다).
    @Test
    @Order(1)
    void followerIsRefusingTrafficAndProbeReturns503() {
        LeaderLeaseService lease = leases.first();
        assertFalse(lease.isLeader(), "선점된 리스가 있는데 이 노드가 리더가 됐다");
        assertEquals(PromotionCoordinator.State.FOLLOWER, runtime().state());

        assertEquals(ReadinessState.REFUSING_TRAFFIC, availability.getReadinessState(),
                "팔로워가 ACCEPTING_TRAFFIC으로 남았다(Spring 자동 발행 미정정)");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readinessProbe().getStatusCode(),
                "팔로워 readiness 프로브가 UP을 반환했다");
    }

    @Test
    @Order(2)
    void demotedPartitionRebuildsToFollowerAndRefusesTraffic() {
        LeaderLeaseService lease = leases.first();
        PartitionRuntime runtime = runtime();

        // 선점 리스가 만료되면 이 노드가 런타임 승격(인계와 M(P) 재생)으로 접수를 연다.
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                + "WHERE resource = 'matching-engine'").update();
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> runtime.state() == PromotionCoordinator.State.ACCEPTING);
        assertTrue(lease.isLeader());
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC);
        assertEquals(HttpStatus.OK, readinessProbe().getStatusCode());

        // 다른 노드가 리스를 가져간 상황. 다음 갱신이 거부되고 그 파티션을 강등한다.
        jdbc.sql("UPDATE leader_lease SET holder = 'squatter-2', epoch = epoch + 1, "
                + "lease_until = clock_timestamp() + interval '30 minutes' "
                + "WHERE resource = 'matching-engine'").update();
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> !lease.isLeader());
        // 강등은 게이트를 닫고 REBUILDING 을 거쳐 fresh 재구성 뒤 FOLLOWER 로 돌아온다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> runtime.state() == PromotionCoordinator.State.FOLLOWER);

        assertEquals(ReadinessState.REFUSING_TRAFFIC, availability.getReadinessState(),
                "강등 노드가 ACCEPTING_TRAFFIC으로 남았다");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, readinessProbe().getStatusCode(),
                "강등 노드 readiness 프로브가 UP을 반환했다");
        assertFalse(runtime.demotions().isEmpty(), "강등 기록이 없다");
        assertEquals(DemotionReason.LEASE_LOST_WHILE_ACCEPTING, runtime.demotions().get(0).reason());
        assertEquals(1, runtime.breaker().count(), "차단기가 강등을 세지 않았다");
        assertFalse(runtime.breaker().isOpen());
        // 재구성을 마친 파티션은 후보로 돌아온다. 선점 리스가 유효하므로 얻지 못한다.
        assertTrue(lease.isArmed(), "재구성을 마친 파티션이 후보 표식을 다시 세우지 않았다");
        assertFalse(lease.isNodeFatal());
        assertEquals("squatter-2", jdbc.sql("SELECT holder FROM leader_lease WHERE resource = 'matching-engine'")
                .query(String.class).single());
    }

    private PartitionRuntime runtime() {
        return leases.runtime(leases.first().resource()).orElseThrow();
    }

    private ResponseEntity<String> readinessProbe() {
        return rest.getForEntity("/actuator/health/readiness", String.class);
    }
}

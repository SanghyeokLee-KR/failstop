package kr.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.sim.BotFleet;
import kr.exchange.sim.BotFleetGovernor;
import kr.exchange.sim.BotSeeder;
import kr.exchange.sim.DbRunOrdinalSource;
import kr.exchange.sim.OpsGovernor;
import kr.exchange.sim.SimProperties;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 제어 명령이 실제 BotFleet 수명주기를 움직이는지 확인한다(§6-A). pause 토글은 스레드를 유지한 채
 * 발행만 끊고, status의 running·paused가 같은 상태를 비춘다.
 * 플릿 빈은 SimConfig와 동일 조립이되 랜덤 포트를 알아야 해서 지연 생성한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimControlIntegrationTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int KRW_BTC = 1;

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
        registry.add("exchange.admin.enabled", () -> "true");
        registry.add("exchange.admin.mtls.trusted-forwarding", () -> "true");
        registry.add("exchange.admin.rate-limit.capacity", () -> "1000000");
        // 봇과 서버가 같은 호스트라 공개 조회가 한 IP 버킷에 계상된다(§2-5). 기본 300토큰이면
        // 제어 검증 전에 레이트리밋을 재는 테스트가 되므로 bots 프로파일과 동일하게 상향한다.
        registry.add("exchange.public.rate-limit.capacity", () -> "100000");
    }

    @TestConfiguration
    static class ControlTestBeans {

        @Bean
        OpsGovernor opsGovernor() {
            return new OpsGovernor(30);
        }

        @Bean
        BotFleetGovernor botFleetGovernor() {
            // 시드 비용 억제를 위해 타입당 풀을 작게 잡는다.
            return new BotFleetGovernor(30, 80, 300, 2);
        }

        @Bean
        @Lazy
        BotFleet botFleet(JdbcClient jdbc, AccountBootstrap bootstrap, DemoKeySeeder keys,
                          OpsGovernor governor, BotFleetGovernor fleetGovernor, Environment env) {
            // 랜덤 포트는 서버 기동 후에만 알 수 있어 첫 관리 요청 시점에 지연 조립한다.
            SimProperties props = new SimProperties(
                    "http://localhost:" + env.getProperty("local.server.port"),
                    42, 30, "KRW-BTC", "50000000",
                    new SimProperties.Funding("1000000000", "100"),
                    new SimProperties.MarketMaker(2, 10, 3, "0.01", 300),
                    new SimProperties.Taker(2, 4.0, "0.001", 5, 0.2), null);
            return new BotFleet(props, new BotSeeder(jdbc, bootstrap, keys), governor,
                    fleetGovernor, new DbRunOrdinalSource(jdbc));
        }
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountBootstrap bootstrap;
    @Autowired
    AdminOperatorSeeder operatorSeeder;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ObjectProvider<BotFleet> fleetProvider;

    @BeforeAll
    void seed() {
        operatorSeeder.ensureOperator("op-bot", "봇 운영자", Set.of(AdminRole.BOT_OPS));
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        // 현실 규모 호가 단위. AccountBootstrap 기본(1e-18)은 틱 산술에 부적합하다.
        jdbc.sql("UPDATE markets SET tick_size = 1000, lot_size = 0.0001, min_notional = 5000 "
                        + "WHERE id = ?")
                .params(KRW_BTC)
                .update();
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                jdbc.sql("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                                + "AND lease_until > now()")
                        .query(Long.class).single() > 0);
    }

    @AfterAll
    void shutdownFleet() {
        BotFleet fleet = fleetProvider.getIfAvailable();
        if (fleet != null) {
            fleet.stop();
        }
    }

    @Test
    void controlDrivesFleetLifecycle() throws Exception {
        String s = session("op-bot");

        assertFalse(status(s).path("running").asBoolean(), "기동 전 status running:false");

        // start: 실기동. 봇 기원 명령이 저널에 쌓여야 반영으로 인정한다.
        JsonNode started = control("start", s);
        assertEquals("1.1", started.path("schema_version").asText());
        assertTrue(started.path("applied").asBoolean());
        assertTrue(started.path("running").asBoolean());
        assertFalse(started.path("paused").asBoolean());
        assertTrue(started.path("fleet_present").asBoolean());
        assertTrue(status(s).path("running").asBoolean(), "status가 실기동을 비춘다");
        Awaitility.await().atMost(Duration.ofSeconds(40)).until(() ->
                count("SELECT count(*) FROM command_journal") > 0);

        // pause 토글: paused 전환 + 발행 중단(in-flight 소진 후 저널 증가 정지).
        JsonNode paused = control("pause", s);
        assertTrue(paused.path("applied").asBoolean());
        assertTrue(paused.path("running").asBoolean(), "pause는 스레드를 죽이지 않는다");
        assertTrue(paused.path("paused").asBoolean());
        assertTrue(status(s).path("paused").asBoolean());
        long[] previous = {-1};
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    long now = count("SELECT count(*) FROM command_journal");
                    boolean stable = now == previous[0];
                    previous[0] = now;
                    return stable;
                });

        // pause 재토글: 해제 + 발행 재개.
        JsonNode resumed = control("pause", s);
        assertTrue(resumed.path("applied").asBoolean());
        assertFalse(resumed.path("paused").asBoolean());
        long beforeResume = count("SELECT count(*) FROM command_journal");
        Awaitility.await().atMost(Duration.ofSeconds(40)).until(() ->
                count("SELECT count(*) FROM command_journal") > beforeResume);

        // stop: 정지 + pause 잔류 없음.
        JsonNode stopped = control("stop", s);
        assertTrue(stopped.path("applied").asBoolean());
        assertFalse(stopped.path("running").asBoolean());
        assertFalse(stopped.path("paused").asBoolean());
        JsonNode finalStatus = status(s);
        assertFalse(finalStatus.path("running").asBoolean(), "status가 정지를 비춘다");
        assertFalse(finalStatus.path("paused").asBoolean());

        // 제어 3종 전부 감사 적재(approval 불요 경로).
        for (String action : new String[]{"sim.start", "sim.pause", "sim.stop"}) {
            assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE operator_id='op-bot' "
                            + "AND action=? AND approval_id IS NULL")
                    .params(action).query(Long.class).single() >= 1, action + " 감사");
        }
    }

    private JsonNode control(String command, String bearer) throws Exception {
        ResponseEntity<String> r = exchange("/admin/v1/sim/" + command, HttpMethod.POST,
                null, "op-bot", bearer);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        return objectMapper.readTree(r.getBody());
    }

    private JsonNode status(String bearer) throws Exception {
        ResponseEntity<String> r = exchange("/admin/v1/sim/status", HttpMethod.GET,
                null, "op-bot", bearer);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        return objectMapper.readTree(r.getBody());
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String session(String cn) throws Exception {
        ResponseEntity<String> issued = exchange("/admin/v1/session", HttpMethod.POST,
                null, cn, null);
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        return objectMapper.readTree(issued.getBody()).path("token").asText();
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String body,
                                            String cn, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cn != null) {
            headers.set("X-Operator-Cn", cn);
        }
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }
}

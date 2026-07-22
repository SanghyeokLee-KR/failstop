package kr.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.sim.BotFleetGovernor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 봇 방향성·거버넌스 관리 API 검증(설계 §5). BOT_OPS 역할로 대수 스케일·방향·효과 강도를
 * 반영하고, 캡 클램프·감사 적재·상태 1.1 additive·공개 exchange-config 화이트리스트,
 * VIEWER 인가 거부를 확인한다. approval 불요 경로임을 감사 approval_id NULL로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimGovernanceIntegrationTest {

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
    }

    @TestConfiguration
    static class GovernanceTestBeans {
        @Bean
        BotFleetGovernor botFleetGovernor() {
            return new BotFleetGovernor(30, 80, 300, 8);
        }
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AdminOperatorSeeder operatorSeeder;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeAll
    void seed() {
        operatorSeeder.ensureOperator("op-bot", "봇 운영자", Set.of(AdminRole.BOT_OPS));
        operatorSeeder.ensureOperator("op-view", "뷰어", Set.of(AdminRole.VIEWER));
    }

    @Test
    void scaleClampsToCapAndAudits() throws Exception {
        String s = session("op-bot");
        // 타입 캡(30) 초과 요청 -> 클램프 + clamped:true.
        ResponseEntity<String> r = put("/admin/v1/sim/scale",
                "{\"market\":\"KRW-BTC\",\"targets\":{\"MARKET_MAKER\":999,\"TAKER\":10,"
                        + "\"TREND\":5,\"NOISE\":5}}", "op-bot", s);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertEquals("1.1", body.path("schema_version").asText());
        assertTrue(body.path("clamped").asBoolean(), "캡 초과 clamped");
        assertEquals(30, body.path("applied").path("MARKET_MAKER").asInt());
        assertEquals(10, body.path("applied").path("TAKER").asInt());
        // 감사 적재(approval 불요이므로 approval_id NULL).
        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE operator_id='op-bot' "
                        + "AND action='sim.scale' AND approval_id IS NULL")
                .query(Long.class).single() >= 1);
    }

    @Test
    void biasAutoOffReflectsClampedManual() throws Exception {
        String s = session("op-bot");
        ResponseEntity<String> r = put("/admin/v1/sim/bias",
                "{\"market\":\"KRW-BTC\",\"target_net_bias\":\"0.90\","
                        + "\"auto_balance\":false,\"strength\":\"0.5\"}", "op-bot", s);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertEquals("0.70", body.path("effective_bias").asText(), "하드캡 0.7로 클램프");
        assertFalse(body.path("auto_balance").asBoolean());
        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE operator_id='op-bot' "
                        + "AND action='sim.bias'").query(Long.class).single() >= 1);
    }

    @Test
    void biasRejectsOutOfRange() throws Exception {
        String s = session("op-bot");
        ResponseEntity<String> r = put("/admin/v1/sim/bias",
                "{\"market\":\"KRW-BTC\",\"target_net_bias\":\"1.5\","
                        + "\"auto_balance\":false,\"strength\":\"0.5\"}", "op-bot", s);
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void effectsPersistsToSystemConfigAndIsPubliclyReadable() throws Exception {
        String s = session("op-bot");
        ResponseEntity<String> r = put("/admin/v1/sim/effects",
                "{\"trade_flash\":\"strong\"}", "op-bot", s);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        assertEquals("strong", objectMapper.readTree(r.getBody()).path("trade_flash").asText());
        assertEquals("\"strong\"", jdbc.sql(
                        "SELECT value::text FROM system_config WHERE config_key='ui.trade_flash_intensity'")
                .query(String.class).single());

        // 공개 조회(무인증)로 트레이딩 UI가 읽는다.
        ResponseEntity<String> pub = rest.getForEntity("/api/v1/exchange-config", String.class);
        assertEquals(200, pub.getStatusCode().value());
        JsonNode body = objectMapper.readTree(pub.getBody());
        assertEquals("strong", body.path("trade_flash").asText());
        // 화이트리스트 전용: 운영 키가 새지 않는다.
        assertFalse(body.has("value"));
    }

    @Test
    void effectsRejectsUnknownValue() throws Exception {
        String s = session("op-bot");
        ResponseEntity<String> r = put("/admin/v1/sim/effects",
                "{\"trade_flash\":\"blinding\"}", "op-bot", s);
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void statusIsSchema11Additive() throws Exception {
        String s = session("op-bot");
        // 마켓 상태를 남긴다.
        put("/admin/v1/sim/scale", "{\"market\":\"KRW-BTC\",\"targets\":{\"TAKER\":3}}",
                "op-bot", s);
        ResponseEntity<String> r = get("/admin/v1/sim/status", "op-bot", s);
        assertEquals(200, r.getStatusCode().value());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertEquals("1.1", body.path("schema_version").asText());
        assertTrue(body.path("fleet_present").asBoolean());
        assertTrue(body.has("target_ops"), "기존 1.0 필드 보존(additive)");
        JsonNode markets = body.path("markets");
        assertTrue(markets.isArray() && markets.size() >= 1);
        JsonNode btc = markets.get(0);
        assertEquals("KRW-BTC", btc.path("market").asText());
        assertTrue(btc.has("target_net_bias"));
        assertTrue(btc.has("measured_net_bias"));
        assertTrue(btc.has("effective_bias"));
        assertTrue(btc.path("scale").has("TAKER"));
        assertTrue(btc.has("fill_ratio"), "1.1 additive 필드 fill_ratio 노출");
    }

    @Test
    void viewerCannotControlGovernance() throws Exception {
        String s = session("op-view");
        ResponseEntity<String> r = put("/admin/v1/sim/scale",
                "{\"market\":\"KRW-BTC\",\"targets\":{\"TAKER\":1}}", "op-view", s);
        assertEquals(403, r.getStatusCode().value());
        assertEquals("role_required", objectMapper.readTree(r.getBody())
                .path("error").path("detail").path("reason").asText());
    }

    // ------------------------------------------------------------------

    private String session(String cn) throws Exception {
        ResponseEntity<String> issued = exchange("/admin/v1/session", HttpMethod.POST, null, cn, null);
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        return objectMapper.readTree(issued.getBody()).path("token").asText();
    }

    private ResponseEntity<String> get(String path, String cn, String bearer) {
        return exchange(path, HttpMethod.GET, null, cn, bearer);
    }

    private ResponseEntity<String> put(String path, String body, String cn, String bearer) {
        return exchange(path, HttpMethod.PUT, body, cn, bearer);
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

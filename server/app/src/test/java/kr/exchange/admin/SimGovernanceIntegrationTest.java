package kr.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.sim.BotFleetGovernor;
import kr.exchange.sim.BotType;
import kr.exchange.sim.OpsGovernor;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 봇 방향성·거버넌스 관리 API 검증(설계 §5). BOT_OPS 역할로 대수 스케일·방향·효과 강도를
 * 반영하고, 캡 클램프·감사 적재·상태 1.2 additive·공개 exchange-config 화이트리스트,
 * VIEWER 인가 거부를 확인한다. approval 불요 경로임을 감사 approval_id NULL로 검증한다.
 * BotFleet 빈이 없는 컨텍스트라 전체 제어는 applied:false 폴백을 검증한다(실반영은
 * SimControlIntegrationTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimGovernanceIntegrationTest {

    /** 프리셋 전용 마켓. 전 마켓에 적용되므로 다른 테스트와 기준선을 섞지 않는다. */
    private static final String SCENARIO_MARKET = "KRW-SCN";

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
    }

    @TestConfiguration
    static class GovernanceTestBeans {
        @Bean
        BotFleetGovernor botFleetGovernor() {
            return new BotFleetGovernor(30, 80, 300, 8);
        }

        /** 프리셋 OPS 배수의 기준선을 만든다(§6-A v2.31). 20 OPS가 데모 기본값이다. */
        @Bean
        OpsGovernor opsGovernor() {
            return new OpsGovernor(20.0);
        }
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    BotFleetGovernor fleetGovernor;
    @Autowired
    OpsGovernor opsGovernor;
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
    void statusIsSchema12Additive() throws Exception {
        String s = session("op-bot");
        // 마켓 상태를 남긴다.
        put("/admin/v1/sim/scale", "{\"market\":\"KRW-BTC\",\"targets\":{\"TAKER\":3}}",
                "op-bot", s);
        ResponseEntity<String> r = get("/admin/v1/sim/status", "op-bot", s);
        assertEquals(200, r.getStatusCode().value());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertEquals("1.2", body.path("schema_version").asText());
        assertTrue(body.path("fleet_present").asBoolean());
        assertTrue(body.has("target_ops"), "기존 1.0 필드 보존(additive)");
        // 1.2 additive: 플릿 수명주기. 이 컨텍스트는 BotFleet 빈이 없어 둘 다 false다.
        assertFalse(body.path("running").asBoolean());
        assertFalse(body.path("paused").asBoolean());
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
    void createBotReturnsGovernorContract() throws Exception {
        String s = session("op-bot");
        // 별도 마켓으로 다른 테스트와 목표 대수 간섭을 피한다.
        ResponseEntity<String> r = post("/admin/v1/sim/bots",
                "{\"type\":\"MARKET_MAKER\",\"count\":5,\"market\":\"KRW-ETH\"}", "op-bot", s);
        assertEquals(201, r.getStatusCode().value(), r.getBody());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertEquals("1.0", body.path("schema_version").asText());
        assertEquals(5, body.path("created").asInt());
        assertTrue(body.path("bot_ids").isArray());
        assertEquals(0, body.path("bot_ids").size(), "governor 경로는 인스턴스 id를 발급하지 않는다");
        assertEquals(5, body.path("applied_target").asInt());
        assertTrue(body.has("fleet_total"), "fleet_total 계약 필드");
        assertTrue(body.path("fleet_total").asInt() >= 5);
        assertFalse(body.path("clamped").asBoolean());
        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE operator_id='op-bot' "
                        + "AND action='sim.bots.create' AND approval_id IS NULL")
                .query(Long.class).single() >= 1);
    }

    @Test
    void createBotClampsToTypeCap() throws Exception {
        String s = session("op-bot");
        // 타입 캡(30) 초과 요청은 거부가 아니라 클램프 + clamped:true.
        ResponseEntity<String> r = post("/admin/v1/sim/bots",
                "{\"type\":\"NOISE\",\"count\":999,\"market\":\"KRW-CLAMP\"}", "op-bot", s);
        assertEquals(201, r.getStatusCode().value(), r.getBody());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertTrue(body.path("clamped").asBoolean(), "캡 초과 clamped");
        assertEquals(30, body.path("applied_target").asInt());
        assertEquals(30, body.path("created").asInt());
    }

    @Test
    void createBotRejectsUnknownType() throws Exception {
        String s = session("op-bot");
        ResponseEntity<String> r = post("/admin/v1/sim/bots",
                "{\"type\":\"WHALE\",\"count\":1,\"market\":\"KRW-BTC\"}", "op-bot", s);
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void controlWithoutFleetReportsNotApplied() throws Exception {
        String s = session("op-bot");
        ResponseEntity<String> r = post("/admin/v1/sim/start", null, "op-bot", s);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertEquals("1.1", body.path("schema_version").asText());
        assertEquals("start", body.path("command").asText());
        assertFalse(body.path("applied").asBoolean(), "플릿 부재는 미반영을 정직하게 보고");
        assertFalse(body.path("fleet_present").asBoolean());
        assertFalse(body.path("running").asBoolean());
        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE operator_id='op-bot' "
                        + "AND action='sim.start'").query(Long.class).single() >= 1);
    }

    /**
     * 프리셋 활성화(§6-A v2.31). 기준선 배수·감사·복귀 경로를 한 흐름으로 본다.
     * 마켓명은 KRW-BTC 뒤로 정렬되는 것을 쓴다. status 테스트가 markets[0]을 KRW-BTC로 단정한다.
     */
    @Test
    void scenarioAppliesBaselineMultiplesAndAudits() throws Exception {
        String s = session("op-bot");
        Map<BotType, Integer> baseline = Map.of(BotType.MARKET_MAKER, 10, BotType.TAKER, 10,
                BotType.TREND, 4, BotType.NOISE, 5);
        fleetGovernor.registerMarket(SCENARIO_MARKET, new BigDecimal("50000000"), baseline);

        ResponseEntity<String> r = post("/admin/v1/sim/scenarios/spike/activate",
                "{\"operator_id\":\"op-bot\"}", "op-bot", s);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        JsonNode body = objectMapper.readTree(r.getBody());
        assertEquals("1.0", body.path("schema_version").asText());
        assertEquals("spike", body.path("scenario").asText());
        assertEquals("20.0", body.path("base_ops").asText());
        assertEquals("200.0", body.path("target_ops").asText(), "spike는 기저의 10배");
        assertEquals(200.0, opsGovernor.targetOps(), 1e-9, "버킷에 실반영");
        assertFalse(body.path("auto_balance").asBoolean());
        assertEquals("0.60", body.path("target_net_bias").asText());
        assertTrue(body.path("fleet_present").asBoolean());

        // 하네스 §2-1이 spike를 "동일 플릿"으로 규정한다. 대수를 건드리지 않는 이유다.
        JsonNode row = marketRow(body, SCENARIO_MARKET);
        assertTrue(row.path("scale_applied").asBoolean());
        assertEquals(10, row.path("applied").path("MARKET_MAKER").asInt());
        assertEquals(10, row.path("applied").path("TAKER").asInt());
        assertEquals(4, row.path("applied").path("TREND").asInt());
        assertEquals(5, row.path("applied").path("NOISE").asInt());
        assertEquals("0.60", row.path("effective_bias").asText(), "auto off는 즉시 반영");

        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE operator_id='op-bot' "
                        + "AND action='sim.scenario' AND target='spike' AND approval_id IS NULL")
                .query(Long.class).single() >= 1);

        // 구성을 바꾸는 국면은 volatile이다(MM 0.6 / TAKER 1.3 / TREND 1.5 / NOISE 1.2).
        JsonNode vol = objectMapper.readTree(
                post("/admin/v1/sim/scenarios/volatile/activate", "{\"operator_id\":\"op-bot\"}",
                        "op-bot", s).getBody());
        assertEquals("30.0", vol.path("target_ops").asText());
        JsonNode volRow = marketRow(vol, SCENARIO_MARKET);
        assertEquals(6, volRow.path("applied").path("MARKET_MAKER").asInt());
        assertEquals(13, volRow.path("applied").path("TAKER").asInt());
        assertEquals(6, volRow.path("applied").path("TREND").asInt());
        assertEquals(6, volRow.path("applied").path("NOISE").asInt());

        // 재활성화는 누적되지 않는다. 현재값을 곱하면 MM이 6에서 4로 또 줄어든다.
        JsonNode again = objectMapper.readTree(
                post("/admin/v1/sim/scenarios/volatile/activate", "{\"operator_id\":\"op-bot\"}",
                        "op-bot", s).getBody());
        assertEquals(6, marketRow(again, SCENARIO_MARKET).path("applied").path("MARKET_MAKER").asInt());

        // steady가 기준선 복귀 경로다.
        JsonNode back = objectMapper.readTree(
                post("/admin/v1/sim/scenarios/steady/activate", "{\"operator_id\":\"op-bot\"}",
                        "op-bot", s).getBody());
        assertEquals("20.0", back.path("target_ops").asText());
        assertEquals(10, marketRow(back, SCENARIO_MARKET).path("applied").path("MARKET_MAKER").asInt());
        assertEquals(4, marketRow(back, SCENARIO_MARKET).path("applied").path("TREND").asInt());
    }

    @Test
    void unknownScenarioIsNotFound() throws Exception {
        String s = session("op-bot");
        ResponseEntity<String> r = post("/admin/v1/sim/scenarios/meltdown/activate",
                "{\"operator_id\":\"op-bot\"}", "op-bot", s);
        assertEquals(404, r.getStatusCode().value(), r.getBody());
        assertEquals("not_found", objectMapper.readTree(r.getBody())
                .path("error").path("name").asText());
    }

    @Test
    void viewerCannotActivateScenario() throws Exception {
        String s = session("op-view");
        ResponseEntity<String> r = post("/admin/v1/sim/scenarios/steady/activate",
                "{\"operator_id\":\"op-view\"}", "op-view", s);
        assertEquals(403, r.getStatusCode().value());
        assertEquals("role_required", objectMapper.readTree(r.getBody())
                .path("error").path("detail").path("reason").asText());
    }

    private JsonNode marketRow(JsonNode body, String market) {
        for (JsonNode m : body.path("markets")) {
            if (market.equals(m.path("market").asText())) {
                return m;
            }
        }
        throw new AssertionError("마켓 행 없음: " + market);
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

    private ResponseEntity<String> post(String path, String body, String cn, String bearer) {
        return exchange(path, HttpMethod.POST, body, cn, bearer);
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

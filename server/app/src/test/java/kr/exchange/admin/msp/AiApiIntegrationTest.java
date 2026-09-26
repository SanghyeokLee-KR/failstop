package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.admin.AdminOperatorSeeder;
import kr.exchange.admin.AdminRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관제센터 AI API 를 관리 필터·역할·승인 티켓과 함께 부른다(API 설계 §6-F). LLM 은 로컬 스텁 주소만 설정하고 판단
 * 주기는 기다리지 않는다. 판단과 실행의 동작은 AiLoopIntegrationTest 가 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiApiIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static final StubSource PROMETHEUS;
    static final StubSource ALERTMANAGER;
    static final StubSource LLM;

    static {
        POSTGRES.start();
        try {
            PROMETHEUS = new StubSource();
            ALERTMANAGER = new StubSource();
            LLM = new StubSource();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        PROMETHEUS.handler = r -> StubSource.Response.json(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        ALERTMANAGER.handler = r -> r.path().equals("/api/v2/status")
                ? StubSource.Response.json("{\"uptime\":\"2026-01-01T00:00:00Z\"}")
                : StubSource.Response.json("[]");
        LLM.handler = r -> StubSource.Response.json("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("exchange.admin.enabled", () -> "true");
        registry.add("exchange.admin.mtls.trusted-forwarding", () -> "true");
        registry.add("server.address", () -> "127.0.0.1");
        // 운영자당 공용 버킷은 관리 요청 60건이다. 정지 전용 버킷이 공용 버킷과 따로인지 보려고 작게 둔다.
        registry.add("exchange.admin.rate-limit.capacity", () -> "600");
        registry.add("exchange.msp.enabled", () -> "true");
        registry.add("exchange.msp.environment", () -> "SCHOOL_AWS");
        registry.add("exchange.msp.prometheus-url", () -> PROMETHEUS.uri().toString());
        registry.add("exchange.msp.alertmanager-url", () -> ALERTMANAGER.uri().toString());
        registry.add("exchange.msp.incident.poll-interval", () -> "1h");
        registry.add("exchange.msp.incident.resolve-after", () -> "2h");
        registry.add("exchange.msp.llm.base-url", () -> LLM.uri().toString());
        registry.add("exchange.msp.llm.model", () -> "test-model");
        registry.add("exchange.msp.ai.interval", () -> "10m");
        registry.add("exchange.msp.ai.recheck-interval", () -> "10m");
        registry.add("exchange.msp.ai.proposal-ttl", () -> "1h");
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AdminOperatorSeeder operators;
    @Autowired
    ObjectMapper mapper;

    String superToken;
    String botToken;
    String viewerToken;

    @BeforeAll
    void seed() throws Exception {
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        operators.ensureOperator("ai-super", "AI SUPER", Set.of(AdminRole.SUPER));
        operators.ensureOperator("ai-bot", "AI BOT_OPS", Set.of(AdminRole.BOT_OPS));
        operators.ensureOperator("ai-view", "AI VIEWER", Set.of(AdminRole.VIEWER));
        operators.ensureOperator("ai-limit", "AI 버킷 시험", Set.of(AdminRole.VIEWER));
        superToken = session("ai-super");
        botToken = session("ai-bot");
        viewerToken = session("ai-view");
    }

    @AfterAll
    void close() {
        PROMETHEUS.close();
        ALERTMANAGER.close();
        LLM.close();
    }

    @Test
    void AI_신원은_세션을_받지_못하고_역할과_상태를_바꿀_대상도_아니다() throws Exception {
        ResponseEntity<String> denied = exchange("/admin/v1/session", HttpMethod.POST, null, "ai-ops", null);
        assertEquals("ai_operator", reason(denied, 403));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE operator_id = 'ai-ops' AND result = 'DENIED'")
                .query(Long.class).single(), "침해 신호로 감사에 남긴다");

        assertEquals("ai_operator", reason(post("/admin/v1/operators/ai-ops/roles",
                "{\"action\":\"GRANT\",\"role\":\"VIEWER\"}", "ai-super", superToken), 403));
        assertEquals("ai_operator", reason(exchange("/admin/v1/operators/ai-ops/status", HttpMethod.PATCH,
                "{\"status\":\"DISABLED\"}", "ai-super", superToken), 403));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM operator_roles WHERE operator_id = 'ai-ops'")
                .query(Long.class).single());
    }

    @Test
    void 자동_켜기는_SUPER_와_시간에_묶인_티켓이고_즉시_끄기는_누구나_한다() throws Exception {
        assertEquals("role_required", reason(post("/admin/v1/msp/ai/mode/auto", auto(4, null), "ai-view",
                viewerToken), 403));
        assertEquals("approval_required", reason(post("/admin/v1/msp/ai/mode/auto", auto(4, null), "ai-super",
                superToken), 403));
        assertEquals(400, post("/admin/v1/msp/ai/mode/auto", auto(3, null), "ai-super", superToken)
                .getStatusCode().value());
        String two = ticket("ai-super", superToken, AiControl.AUTO_ACTION, "AUTO:2");
        assertEquals("binding_mismatch", reason(post("/admin/v1/msp/ai/mode/auto", auto(4, two), "ai-super",
                superToken), 403));

        String four = ticket("ai-super", superToken, AiControl.AUTO_ACTION, "AUTO:4");
        JsonNode on = json(post("/admin/v1/msp/ai/mode/auto", auto(4, four), "ai-super", superToken), 200);
        assertEquals("AUTO", on.path("mode").asText());
        assertEquals(Duration.ofHours(4), Duration.between(Instant.parse(on.path("since").asText()),
                Instant.parse(on.path("valid_until").asText())));

        JsonNode mode = json(get("/admin/v1/msp/ai/mode", "ai-view", viewerToken), 200);
        assertEquals("AUTO", mode.path("mode").asText());
        assertEquals("ai-super", mode.path("auto").path("enabled_by").asText());
        assertEquals(on.path("activation").asLong(), mode.path("activation").path("seq").asLong());
        assertEquals(0, mode.path("activation").path("auto_used").asInt());
        assertEquals(5, mode.path("activation").path("auto_cap").asInt());
        assertEquals("LOOPBACK", mode.path("llm").path("transport").asText());

        JsonNode off = json(post("/admin/v1/msp/ai/mode/manual", "{\"reason\":\"점검\"}", "ai-view", viewerToken),
                200);
        assertEquals("MANUAL", off.path("mode").asText());
        assertTrue(off.path("changed").asBoolean());
        assertFalse(json(post("/admin/v1/msp/ai/mode/manual", null, "ai-view", viewerToken), 200)
                .path("changed").asBoolean());
    }

    @Test
    void AI_정지는_공용_버킷이_비어도_받고_재개는_SUPER_가_수동으로_한다() throws Exception {
        String limited = session("ai-limit");
        int status = 200;
        for (int i = 0; i < 300 && status != 429; i++) {
            status = get("/admin/v1/msp/ai/mode", "ai-limit", limited).getStatusCode().value();
        }
        assertEquals(429, status, "공용 버킷을 비웠다");

        JsonNode stopped = json(post("/admin/v1/msp/ai/stop", "{\"reason\":\"판단 이상\"}", "ai-limit", limited), 200);
        assertTrue(stopped.path("stopped").asBoolean());
        assertEquals("MANUAL", stopped.path("mode").asText());
        // 공용 버킷은 초당 요청 한 건만큼 차오른다. 잇단 다섯 건이 모두 통과하면 공용 버킷을 쓰지 않은 것이다.
        for (int i = 0; i < 4; i++) {
            String path = i % 2 == 0 ? "/admin/v1/msp/ai/stop" : "/admin/v1/msp/ai/mode/manual";
            assertEquals(200, post(path, null, "ai-limit", limited).getStatusCode().value(), path);
        }

        JsonNode mode = json(get("/admin/v1/msp/ai/mode", "ai-view", viewerToken), 200);
        assertTrue(mode.path("stopped").asBoolean());
        assertEquals("ai-limit", mode.path("stop").path("by").asText());
        String four = ticket("ai-super", superToken, AiControl.AUTO_ACTION, "AUTO:4");
        JsonNode blocked = json(post("/admin/v1/msp/ai/mode/auto", auto(4, four), "ai-super", superToken), 409);
        assertEquals("M9", blocked.path("error").path("detail").path("rule").asText());

        assertEquals("role_required", reason(post("/admin/v1/msp/ai/resume", "{\"reason\":\"확인\"}", "ai-view",
                viewerToken), 403));
        assertEquals(400, post("/admin/v1/msp/ai/resume", "{}", "ai-super", superToken).getStatusCode().value());
        JsonNode resumed = json(post("/admin/v1/msp/ai/resume", "{\"reason\":\"원인 확인\"}", "ai-super", superToken),
                200);
        assertFalse(resumed.path("stopped").asBoolean());
        assertEquals("MANUAL", resumed.path("mode").asText());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.stop' "
                + "AND operator_id = 'ai-limit'").query(Long.class).single());
    }

    @Test
    void 한도는_누구나_읽고_SUPER_가_값에_묶인_티켓으로_바꾼다() throws Exception {
        JsonNode limits = json(get("/admin/v1/msp/ai/limits", "ai-view", viewerToken), 200);
        assertEquals(5, limits.path("items").size());
        JsonNode confidence = limits.path("items").get(0);
        assertEquals("confidence_pct", confidence.path("key").asText());
        assertEquals(70, confidence.path("min").asInt());
        assertEquals(95, confidence.path("max").asInt());
        assertEquals("UP", confidence.path("tighter").asText());

        String path = "/admin/v1/msp/ai/limits/bot_cooldown_min";
        assertEquals("role_required", reason(put(path, limit(20, null), "ai-bot", botToken), 403));
        assertEquals("approval_required", reason(put(path, limit(20, null), "ai-super", superToken), 403));
        assertEquals(400, put(path, limit(61, null), "ai-super", superToken).getStatusCode().value());
        assertEquals(404, put("/admin/v1/msp/ai/limits/silence_minutes", limit(20, null), "ai-super", superToken)
                .getStatusCode().value());

        String token = ticket("ai-super", superToken, AiControl.LIMITS_ACTION, "bot_cooldown_min:20");
        JsonNode changed = json(put(path, limit(20, token), "ai-super", superToken), 200);
        assertEquals(10, changed.path("from").asInt());
        assertEquals(20, changed.path("to").asInt());
        assertEquals("NOW", changed.path("applies").asText());

        JsonNode history = json(get("/admin/v1/msp/ai/limits/changes?key=bot_cooldown_min&limit=1", "ai-view",
                viewerToken), 200);
        assertEquals(changed.path("change_id").asLong(), history.path("data").get(0).path("id").asLong());
        assertTrue(history.path("pagination").path("has_more").asBoolean(), "초기값 이력이 뒤에 있다");
        assertEquals(404, get("/admin/v1/msp/ai/limits/changes?key=none", "ai-view", viewerToken)
                .getStatusCode().value());
    }

    @Test
    void 계획_작업_창은_BOT_OPS_가_등록하고_VIEWER_도_일찍_끝낸다() throws Exception {
        String ends = Instant.now().plus(Duration.ofHours(2)).toString();
        String body = "{\"ends_at\":\"" + ends + "\",\"alertname\":\"BotOrderLatencyHigh\","
                + "\"labels\":{\"component\":\"bot\"},\"reason\":\"봇 배포\"}";
        assertEquals("role_required", reason(post("/admin/v1/msp/maintenance-windows", body, "ai-view",
                viewerToken), 403));
        assertEquals("format", json(post("/admin/v1/msp/maintenance-windows",
                "{\"ends_at\":\"내일\",\"alertname\":\"A\",\"reason\":\"r\"}", "ai-bot", botToken), 400)
                .path("error").path("detail").path("constraint").asText());

        JsonNode created = json(post("/admin/v1/msp/maintenance-windows", body, "ai-bot", botToken), 201);
        long id = created.path("id").asLong();
        assertTrue(created.path("active").asBoolean());
        assertEquals("ai-bot", created.path("created_by").asText());

        JsonNode active = json(get("/admin/v1/msp/maintenance-windows", "ai-view", viewerToken), 200);
        assertTrue(active.path("data").findValues("id").stream().anyMatch(n -> n.asLong() == id));
        JsonNode ended = json(post("/admin/v1/msp/maintenance-windows/" + id + "/end", null, "ai-view", viewerToken),
                200);
        assertTrue(ended.path("changed").asBoolean());
        JsonNode after = json(get("/admin/v1/msp/maintenance-windows?status=ACTIVE", "ai-view", viewerToken), 200);
        assertFalse(after.path("data").findValues("id").stream().anyMatch(n -> n.asLong() == id));
        assertEquals(400, get("/admin/v1/msp/maintenance-windows?status=OPEN", "ai-view", viewerToken)
                .getStatusCode().value());
    }

    @Test
    void 한_판은_LLM_연결과_AI_칸을_싣고_빈_기록도_목록으로_낸다() throws Exception {
        JsonNode overview = json(get("/admin/v1/msp/overview", "ai-view", viewerToken), 200);
        JsonNode llm = null;
        for (JsonNode s : overview.path("sources")) {
            if ("llm".equals(s.path("name").asText())) {
                llm = s;
            }
        }
        assertEquals("READY", llm.path("state").asText());
        assertEquals("openai-compatible", llm.path("provider").asText());
        assertEquals("test-model", llm.path("model").asText());
        assertEquals("LOOPBACK", llm.path("transport").asText());
        assertFalse(llm.toString().contains("127.0.0.1"), "LLM 주소는 내지 않는다");
        assertEquals("READY", overview.path("ai").path("state").asText());

        assertTrue(json(get("/admin/v1/msp/ai/decisions", "ai-view", viewerToken), 200).path("data").isArray());
        assertTrue(json(get("/admin/v1/msp/ai/actions?status=ALL", "ai-view", viewerToken), 200).path("data")
                .isArray());
        assertEquals(400, get("/admin/v1/msp/ai/actions?status=DONE", "ai-view", viewerToken).getStatusCode().value());
        assertEquals(404, get("/admin/v1/msp/ai/decisions/999999", "ai-view", viewerToken).getStatusCode().value());
        assertEquals(404, post("/admin/v1/msp/ai/actions/999999/approve", "{}", "ai-super", superToken)
                .getStatusCode().value());
    }

    @Test
    void 같은_조치_요청을_두_번_승인하면_두_번째는_이미_처리된_요청이고_임계값은_한_번만_바뀐다() throws Exception {
        long decision = jdbc.sql("""
                        INSERT INTO ops_ai_decisions (decided_at, mode, outcome, model, prompt_version, evidence,
                                                      evidence_sha256)
                        VALUES (now(), 'MANUAL', 'PROPOSED', 'test-model', 'msp-ai/1', '{}', repeat('c', 64)) RETURNING id
                        """).query(Long.class).single();
        long id = jdbc.sql("""
                        INSERT INTO ops_ai_actions (first_decision_id, last_decision_id, kind, identity_key, params, target,
                                                    confidence, created_at, expires_at)
                        VALUES (?, ?, 'THRESHOLD', 'THRESHOLD:host.disk_free_alert:0.2', ?::jsonb, 'host.disk_free_alert',
                                0.9, now(), now() + interval '15 minutes') RETURNING id
                        """)
                .params(decision, decision, "{\"key\":\"host.disk_free_alert\",\"value\":\"0.2\",\"from\":\"0.15\"}")
                .query(Long.class).single();
        String path = "/admin/v1/msp/ai/actions/" + id + "/approve";

        String first = ticket("ai-super", superToken, OpsThresholdService.APPROVAL_ACTION, "host.disk_free_alert:0.2");
        JsonNode approved = json(post(path, "{\"approval_token\":\"" + first + "\"}", "ai-super", superToken), 200);
        assertEquals("APPROVED", approved.path("status").asText());
        String second = ticket("ai-super", superToken, OpsThresholdService.APPROVAL_ACTION, "host.disk_free_alert:0.2");
        JsonNode again = json(post(path, "{\"approval_token\":\"" + second + "\"}", "ai-super", superToken), 409);

        assertEquals("ai_action_not_open", again.path("error").path("name").asText());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM ops_threshold_changes WHERE ai_action_id = ?").param(id)
                .query(Long.class).single(), "임계값은 한 번만 바뀐다");
        assertEquals("0.2", jdbc.sql("SELECT trim(trailing '0' FROM value::text) FROM ops_thresholds "
                + "WHERE key = 'host.disk_free_alert'").query(String.class).single());
        assertEquals("ISSUED", jdbc.sql("SELECT state FROM admin_approvals WHERE approval_token = ?").param(second)
                .query(String.class).single(), "거부된 승인은 티켓을 쓰지 않는다");
    }

    /* ===== 요청 ===== */

    private static String auto(int hours, String token) {
        return "{\"hours\":" + hours + ",\"reason\":\"야간 관제\"" + (token == null ? "" : ",\"approval_token\":\""
                + token + "\"") + "}";
    }

    private static String limit(int value, String token) {
        return "{\"value\":" + value + ",\"reason\":\"시험\"" + (token == null ? "" : ",\"approval_token\":\""
                + token + "\"") + "}";
    }

    private String session(String operator) throws Exception {
        ResponseEntity<String> issued = exchange("/admin/v1/session", HttpMethod.POST, null, operator, null);
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        return mapper.readTree(issued.getBody()).path("token").asText();
    }

    private String ticket(String operator, String token, String action, String target) throws Exception {
        ResponseEntity<String> issued = post("/admin/v1/approvals", "{\"requester_operator_id\":\"" + operator
                + "\",\"action\":\"" + action + "\",\"target\":\"" + target + "\"}", operator, token);
        return json(issued, 200).path("approval_token").asText();
    }

    private ResponseEntity<String> get(String path, String operator, String token) {
        return exchange(path, HttpMethod.GET, null, operator, token);
    }

    private ResponseEntity<String> post(String path, String body, String operator, String token) {
        return exchange(path, HttpMethod.POST, body, operator, token);
    }

    private ResponseEntity<String> put(String path, String body, String operator, String token) {
        return exchange(path, HttpMethod.PUT, body, operator, token);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String body, String operator,
                                            String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Operator-Cn", operator);
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response, int status) throws Exception {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody());
    }

    private String reason(ResponseEntity<String> response, int status) throws Exception {
        return json(response, status).path("error").path("detail").path("reason").asText();
    }
}

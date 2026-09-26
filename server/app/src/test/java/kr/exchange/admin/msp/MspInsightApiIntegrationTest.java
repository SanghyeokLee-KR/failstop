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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관제센터의 상담, 추세, AI 서버 상태, 보고서 읽기와 한 판의 ETag 를 관리 필터·역할과 함께 부른다(API 설계 §6-F).
 * Prometheus, Alertmanager, LLM, 보고 서비스는 로컬 스텁이다. 실제 모델과 Teams 는 부르지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MspInsightApiIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static final StubSource PROMETHEUS;
    static final StubSource ALERTMANAGER;
    static final StubSource LLM;
    static final StubSource REPORTING;
    static final Path TOKEN_FILE;
    static volatile String alerts = "[]";

    static {
        POSTGRES.start();
        try {
            PROMETHEUS = new StubSource();
            ALERTMANAGER = new StubSource();
            LLM = new StubSource();
            REPORTING = new StubSource();
            TOKEN_FILE = Files.createTempFile("msp-reporting", ".token");
            Files.writeString(TOKEN_FILE, "reporting-ingest-token-for-msp-api-tests-0123456789");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        PROMETHEUS.handler = r -> StubSource.Response.json(r.path().equals("/api/v1/query_range")
                ? "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}"
                : "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        ALERTMANAGER.handler = r -> r.path().equals("/api/v2/status")
                ? StubSource.Response.json("{\"uptime\":\"2026-01-01T00:00:00Z\"}")
                : StubSource.Response.json(alerts);
        LLM.handler = r -> r.path().equals("/v1/models")
                ? StubSource.Response.json("{\"data\":[{\"id\":\"test-model\"}]}")
                : StubSource.Response.json("{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"answer_ko\\\":\\\"경보는 없습니다.\\\",\\\"cited_ids\\\":[\\\"sources\\\"]}\"}}]}");
        REPORTING.handler = r -> switch (r.path()) {
            case "/console/status" -> StubSource.Response.json("{\"state\":\"ok\",\"delivery_enabled\":false,"
                    + "\"counts\":{\"pending\":0,\"sent\":2},\"last_sent_at\":1790200041,\"last_attempt\":null,"
                    + "\"oldest_pending_age_seconds\":null,\"last_daily_sent_at\":1790200041}");
            case "/console/reports" -> StubSource.Response.json("{\"state\":\"ok\",\"reports\":[{\"id\":\"daily:1\","
                    + "\"kind\":\"daily\",\"created\":1790200000,\"sent\":1790200041,\"status\":\"sent\","
                    + "\"title\":\"일일 운영 보고\",\"severity\":\"P4\",\"summary\":\"관찰 유지\",\"targets\":[]}]}");
            default -> StubSource.Response.status(404, "{}");
        };
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
        registry.add("exchange.admin.rate-limit.capacity", () -> "1000000");
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
        registry.add("exchange.msp.reporting.url", () -> REPORTING.uri().toString());
        registry.add("exchange.msp.reporting.token-file", TOKEN_FILE::toString);
        registry.add("exchange.msp.reporting.environment", () -> "aws");
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AdminOperatorSeeder operators;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    IncidentPoller poller;

    String viewerToken;

    @BeforeAll
    void seed() throws Exception {
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        operators.ensureOperator("insight-view", "관제 VIEWER", Set.of(AdminRole.VIEWER));
        viewerToken = session("insight-view");
        poller.pollOnce();
    }

    @AfterAll
    void close() throws IOException {
        PROMETHEUS.close();
        ALERTMANAGER.close();
        LLM.close();
        REPORTING.close();
        Files.deleteIfExists(TOKEN_FILE);
    }

    @Test
    void 한_판은_추세와_AI_서버_상태를_싣고_같은_본문이면_304다() throws Exception {
        ResponseEntity<String> first = get("/admin/v1/msp/overview", null);
        JsonNode body = json(first, 200);
        String tag = first.getHeaders().getETag();
        assertTrue(tag != null && tag.startsWith("W/\""), String.valueOf(tag));
        assertTrue(first.getHeaders().getCacheControl().contains("no-cache"));
        assertEquals("24h", body.path("trends").path("range").asText());
        assertEquals("READY", body.path("trends").path("series").path("p95_seconds").path("state").asText());
        assertEquals("READY", body.path("trends").path("series").path("incidents").path("state").asText());
        assertEquals("READY", body.path("ai_server").path("state").asText());
        JsonNode teams = source(body, "teams");
        assertEquals("READY", teams.path("state").asText());
        assertEquals("reporting", teams.path("via").asText());
        assertFalse(teams.path("delivery_enabled").asBoolean(), "전송이 꺼져 있어도 READY 이고 켬 여부를 싣는다");

        ResponseEntity<String> same = get("/admin/v1/msp/overview", tag);
        assertEquals(304, same.getStatusCode().value());
        assertNull(same.getBody(), "304 는 본문을 싣지 않는다");
        assertEquals(tag, same.getHeaders().getETag());

        alerts = "[" + AiRig.alert("e000000000000001", "LinuxDiskSpaceLow", "warning", "storage") + "]";
        poller.pollOnce();
        ResponseEntity<String> changed = get("/admin/v1/msp/overview", tag);
        assertEquals(200, changed.getStatusCode().value(), "경보가 바뀌면 태그가 바뀐다");
        assertNotEquals(tag, changed.getHeaders().getETag());
        alerts = "[]";
        poller.pollOnce();
    }

    @Test
    void 화면이_여럿이어도_캐시_수명_안에는_원천을_다시_부르지_않는다() throws Exception {
        json(get("/admin/v1/msp/overview", null), 200);
        int prom = PROMETHEUS.requests.size();
        int alertmanager = ALERTMANAGER.requests.size();
        int reporting = REPORTING.requests.size();
        int llm = LLM.requests.size();

        for (int i = 0; i < 5; i++) {
            json(get("/admin/v1/msp/overview", null), 200);
        }

        assertTrue(prom > 0, "처음 한 판은 원천을 읽었다");
        assertEquals(prom, PROMETHEUS.requests.size(), "instant 묶음과 추세는 캐시 수명 안이다");
        assertEquals(alertmanager, ALERTMANAGER.requests.size(), "경보는 폴러의 마지막 결과를 쓴다");
        assertEquals(reporting, REPORTING.requests.size(), "보고 서비스 상태는 30초 캐시다");
        assertEquals(llm, LLM.requests.size(), "AI 서버 상태는 30초 캐시다");
    }

    @Test
    void 추세는_세_범위만_받는다() throws Exception {
        JsonNode six = json(get("/admin/v1/msp/trends?range=6h", null), 200);
        assertEquals("6h", six.path("range").asText());
        assertEquals(180, six.path("step_seconds").asInt());
        assertEquals("SCHOOL_AWS", six.path("environment").asText());
        assertEquals(24, six.path("series").path("incidents").path("data").path("points").size());
        JsonNode bad = json(get("/admin/v1/msp/trends?range=2h", null), 400);
        assertEquals("range", bad.path("error").path("detail").path("field").asText());
    }

    @Test
    void 상담은_VIEWER가_부르고_감사에_남으며_형식이_틀린_요청은_400이다() throws Exception {
        JsonNode answer = json(post("/admin/v1/msp/ai/chat", "{\"question\":\"경보가 있나요?\",\"history\":[]}"), 200);
        assertEquals("ANSWERED", answer.path("state").asText());
        assertEquals("경보는 없습니다.", answer.path("answer").asText());
        assertEquals("sources", answer.path("citations").get(0).path("id").asText());
        assertEquals(64, answer.path("evidence").path("sha256").asText().length());
        assertTrue(answer.has("reason") && answer.path("reason").isNull());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.chat' "
                + "AND operator_id = 'insight-view'").query(Long.class).single());

        JsonNode bad = json(post("/admin/v1/msp/ai/chat", "{\"question\":\"\"}"), 400);
        assertEquals("invalid_parameter", bad.path("error").path("name").asText());

        JsonNode server = json(get("/admin/v1/msp/ai/server", null), 200);
        assertEquals("openai-compatible", server.path("provider").asText());
        assertEquals("LOOPBACK", server.path("transport").asText());
        assertTrue(server.path("server").path("model_listed").asBoolean());
        assertTrue(server.path("server").path("model_loaded").isNull(), "openai 호환 서버는 적재 여부를 모른다");
        assertTrue(server.path("calls").path("by_purpose").path("chat").asInt() >= 1);
        assertFalse(server.toString().contains("127.0.0.1"), "주소는 싣지 않는다");
    }

    @Test
    void 보고_목록과_전송_상태를_읽는다() throws Exception {
        JsonNode list = json(get("/admin/v1/msp/reports?limit=5", null), 200);
        assertEquals("READY", list.path("state").asText());
        assertEquals("daily:1", list.path("data").get(0).path("id").asText());
        assertTrue(list.path("data").get(0).path("links").isNull(), "보고서 주소의 기점을 설정하지 않았다");
        assertEquals(400, get("/admin/v1/msp/reports?limit=0", null).getStatusCode().value());

        JsonNode status = json(get("/admin/v1/msp/reports/status", null), 200);
        assertEquals("READY", status.path("state").asText());
        assertEquals(2, status.path("data").path("reporting").path("counts").path("sent").asInt());
        assertTrue(status.path("data").path("outbox").path("counts").isObject());
    }

    /* ===== 요청 ===== */

    private String session(String operator) throws Exception {
        ResponseEntity<String> issued = exchange("/admin/v1/session", HttpMethod.POST, null, operator, null, null);
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        return mapper.readTree(issued.getBody()).path("token").asText();
    }

    private ResponseEntity<String> get(String path, String ifNoneMatch) {
        return exchange(path, HttpMethod.GET, null, "insight-view", viewerToken, ifNoneMatch);
    }

    private ResponseEntity<String> post(String path, String body) {
        return exchange(path, HttpMethod.POST, body, "insight-view", viewerToken, null);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String body, String operator, String token,
                                            String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Operator-Cn", operator);
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response, int status) throws Exception {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody());
    }

    private static JsonNode source(JsonNode body, String name) {
        for (JsonNode s : body.path("sources")) {
            if (name.equals(s.path("name").asText())) {
                return s;
            }
        }
        throw new AssertionError("원천이 없다: " + name);
    }
}

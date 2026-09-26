package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.admin.AdminOperatorSeeder;
import kr.exchange.admin.AdminRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관제센터 API 를 관리 필터·역할·승인 티켓과 함께 실제로 부른다(API 설계 §6-F). Prometheus 와
 * Alertmanager 는 로컬 스텁이다. 폴링 주기는 길게 두고 시험이 pollOnce 로 직접 돌린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// 시험 문맥은 기본으로 지표 노출을 끈다. 게이지가 Prometheus 형식으로 어떻게 나가는지 보려고 켠다.
@AutoConfigureObservability(tracing = false)
class MspApiIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static final StubSource PROMETHEUS;
    static final StubSource ALERTMANAGER;
    static volatile List<String> active = new ArrayList<>();
    static final Map<String, String> SILENCES = new HashMap<>();

    static final String WARN = "1000000000000001";
    static final String CRIT = "2000000000000002";
    static final String OTHER = "3000000000000003";
    static final String HEART = "4000000000000004";
    static final String MUTED = "5000000000000005";

    static {
        POSTGRES.start();
        try {
            PROMETHEUS = new StubSource();
            ALERTMANAGER = new StubSource();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        PROMETHEUS.handler = r -> StubSource.Response.json(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        ALERTMANAGER.handler = MspApiIntegrationTest::alertmanager;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("exchange.admin.enabled", () -> "true");
        registry.add("exchange.admin.mtls.trusted-forwarding", () -> "true");
        // 게이트웨이가 mTLS 를 종단하는 토폴로지 전제라 루프백에만 뜬다(AdminConfig 가드).
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("exchange.admin.rate-limit.capacity", () -> "1000000");
        registry.add("exchange.msp.enabled", () -> "true");
        registry.add("exchange.msp.environment", () -> "SCHOOL_AWS");
        registry.add("exchange.msp.prometheus-url", () -> PROMETHEUS.uri().toString());
        registry.add("exchange.msp.alertmanager-url", () -> ALERTMANAGER.uri().toString());
        registry.add("exchange.msp.incident.poll-interval", () -> "1h");
        registry.add("exchange.msp.incident.resolve-after", () -> "2h");
        registry.add("exchange.msp.hosts[0].node", () -> "db-primary");
        registry.add("exchange.msp.hosts[0].role", () -> "DB");
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

    String superToken;
    String botToken;
    String viewerToken;

    @BeforeAll
    void seed() throws Exception {
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        operators.ensureOperator("msp-super", "관제 SUPER", Set.of(AdminRole.SUPER));
        operators.ensureOperator("msp-bot", "관제 BOT_OPS", Set.of(AdminRole.BOT_OPS));
        operators.ensureOperator("msp-viewer", "관제 VIEWER", Set.of(AdminRole.VIEWER));
        superToken = session("msp-super");
        botToken = session("msp-bot");
        viewerToken = session("msp-viewer");
    }

    @AfterAll
    void close() {
        PROMETHEUS.close();
        ALERTMANAGER.close();
    }

    @BeforeEach
    void alerts() {
        active = List.of(
                alert(WARN, "LinuxDiskSpaceLow", "warning", "[]"),
                alert(CRIT, "DatabaseBackupStale", "critical", "[]"),
                alert(OTHER, "LinuxServiceOdd", "none", "[]"),
                alert(HEART, "Watchdog", "none", "[]"),
                alert(MUTED, "LinuxInodesLow", "warning", "[\"8e0a2c56-0000-4000-8000-000000000001\"]"));
        poller.pollOnce();
    }

    @Test
    void 한_판은_원천_상태와_칸을_싣고_연결_경로가_없는_원천은_연결_전으로_낸다() throws Exception {
        JsonNode body = json(get("/admin/v1/msp/overview", "msp-viewer", viewerToken), 200);

        assertEquals("SCHOOL_AWS", body.path("environment").asText());
        assertEquals("READY", source(body, "prometheus").path("state").asText());
        assertEquals("READY", source(body, "alertmanager").path("state").asText());
        assertEquals(0, source(body, "alertmanager").path("write_failures").asInt());
        for (String name : List.of("databasus", "llm", "teams")) {
            assertEquals("NOT_CONNECTED", source(body, name).path("state").asText(), name);
        }
        assertEquals("MANUAL", body.path("ai").path("mode").asText());
        assertEquals("NOT_CONNECTED", body.path("ai").path("state").asText());
        assertEquals("NOT_CONNECTED", body.path("read_tier").path("state").asText());

        JsonNode alerts = body.path("alerts");
        assertEquals("READY", alerts.path("state").asText());
        assertEquals(4, alerts.path("data").size(), "생존 신호 경보는 목록에 없다");
        for (JsonNode a : alerts.path("data")) {
            assertFalse(a.has("instance"), "사설 주소는 내지 않는다");
            assertFalse(HEART.equals(a.path("fingerprint").asText()));
        }
        JsonNode muted = alerts.path("data").findParents("fingerprint").stream()
                .filter(a -> MUTED.equals(a.path("fingerprint").asText())).findFirst().orElseThrow();
        assertEquals("SILENCED", muted.path("state").asText());

        JsonNode items = body.path("thresholds").path("data").path("items");
        assertEquals(9, items.size());
        JsonNode disk = item(items, "host.disk_free_alert");
        assertEquals("0.15", disk.path("value").asText());
        assertTrue(disk.path("in_use").asBoolean(), "학교에서는 LinuxDiskSpaceLow 가 참조한다");
        assertFalse(item(items, "read.scale_out_cpu").path("in_use").asBoolean(), "조회 계층이 없는 환경이다");
        assertTrue(item(items, "host.cpu_alert").path("value").isNull());
        assertFalse(body.path("thresholds").path("data").path("gauge_scraped").asBoolean());
        assertEquals("MISSING", body.path("hosts").path("data").get(0).path("cpu_ratio").path("collect").asText());
    }

    @Test
    void 임계값_변경은_SUPER_와_값에_묶인_티켓을_요구하고_되돌릴_수_있다() throws Exception {
        String path = "/admin/v1/msp/thresholds/host.mem_alert";
        assertEquals("role_required", reason(post(path, "{\"value\":\"0.8\"}", "msp-bot", botToken), 403));
        assertEquals("approval_required", reason(post(path, "{\"value\":\"0.8\"}", "msp-super", superToken), 403));
        String wrong = ticket("msp-super", superToken, "ops.threshold.set", "host.mem_alert:0.85");
        assertEquals("binding_mismatch", reason(post(path,
                "{\"value\":\"0.8\",\"approval_token\":\"" + wrong + "\"}", "msp-super", superToken), 403));
        assertEquals("scale", json(post(path, "{\"value\":\"0.80001\"}", "msp-super", superToken), 400)
                .path("error").path("detail").path("constraint").asText());

        String token = ticket("msp-super", superToken, "ops.threshold.set", "host.mem_alert:0.8");
        JsonNode changed = json(post(path, "{\"operator_id\":\"msp-super\",\"value\":\"0.80\",\"approval_token\":\""
                + token + "\",\"reason\":\"메모리 경보 첫 값\"}", "msp-super", superToken), 200);
        assertEquals("0.8", changed.path("to").asText());
        assertTrue(changed.path("from").isNull());
        assertEquals("MANUAL", changed.path("via").asText());

        JsonNode history = json(get("/admin/v1/msp/thresholds/changes?key=host.mem_alert&limit=1", "msp-viewer",
                viewerToken), 200);
        assertEquals(changed.path("change_id").asLong(), history.path("data").get(0).path("id").asLong());
        assertTrue(history.path("pagination").path("has_more").asBoolean(), "초기값 이력이 뒤에 있다");
        assertEquals(404, get("/admin/v1/msp/thresholds/changes?key=host.none", "msp-viewer", viewerToken)
                .getStatusCode().value());

        String back = ticket("msp-super", superToken, "ops.threshold.set", "host.mem_alert:null");
        JsonNode reverted = json(post("/admin/v1/msp/thresholds/changes/" + changed.path("change_id").asLong()
                + "/revert", "{\"approval_token\":\"" + back + "\"}", "msp-super", superToken), 200);
        assertEquals("REVERT", reverted.path("via").asText());
        assertTrue(reverted.path("to").isNull());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.threshold.revert'")
                .query(Long.class).single());
    }

    @Test
    void 티켓_없는_보류는_BOT_OPS_의_warning_30분까지다() throws Exception {
        String base = "/admin/v1/msp/alerts/";
        assertEquals("role_required", reason(post(base + CRIT + "/silence", body(10), "msp-bot", botToken), 403));
        assertEquals("role_required", reason(post(base + OTHER + "/silence", body(10), "msp-bot", botToken), 403));
        assertEquals("role_required", reason(post(base + WARN + "/silence", body(31), "msp-bot", botToken), 403));
        assertEquals("role_required", reason(post(base + WARN + "/silence", body(10), "msp-viewer", viewerToken), 403));
        assertEquals(400, post(base + WARN + "/silence", "{\"minutes\":10,\"reason\":\"\"}", "msp-bot", botToken)
                .getStatusCode().value());
        assertEquals(400, post(base + WARN + "/silence", body(0), "msp-bot", botToken).getStatusCode().value());

        int before = ALERTMANAGER.requests("/api/v2/silences").size();
        JsonNode silenced = json(post(base + WARN + "/silence", body(30), "msp-bot", botToken), 201);

        List<StubSource.Request> posts = ALERTMANAGER.requests("/api/v2/silences");
        assertEquals(before + 1, posts.size());
        JsonNode sent = mapper.readTree(posts.get(posts.size() - 1).body());
        assertEquals("msp-bot", sent.path("createdBy").asText());
        assertTrue(sent.path("comment").asText().endsWith("msp-incident:" + silenced.path("incident_id").asLong()));
        assertEquals(Duration.ofMinutes(30), Duration.between(Instant.parse(sent.path("startsAt").asText()),
                Instant.parse(sent.path("endsAt").asText())));
        Map<String, String> matchers = new HashMap<>();
        for (JsonNode m : sent.path("matchers")) {
            assertFalse(m.path("isRegex").asBoolean());
            assertTrue(m.path("isEqual").asBoolean());
            matchers.put(m.path("name").asText(), m.path("value").asText());
        }
        assertEquals(Map.of("alertname", "LinuxDiskSpaceLow", "severity", "warning", "node", "db-primary",
                "instance", "10.0.0.1:9100"), matchers, "그 경보의 라벨 묶음 전체를 정확히 맞춘다");
        assertEquals(1, jdbc.sql("SELECT count(*) FROM ops_incident_events WHERE kind = 'SILENCED' AND actor_id = 'msp-bot'")
                .query(Long.class).single());

        String silenceId = silenced.path("silence_id").asText();
        JsonNode unsilenced = json(delete("/admin/v1/msp/silences/" + silenceId, "msp-bot", botToken), 200);
        assertEquals(silenced.path("incident_id").asLong(), unsilenced.path("incident_id").asLong());
        assertEquals(1, ALERTMANAGER.requests("/api/v2/silence/" + silenceId).stream()
                .filter(r -> r.method().equals("DELETE")).count());
        assertEquals("silence_not_managed", reason(delete("/admin/v1/msp/silences/8e0a2c56-0000-4000-8000-00000000ffff",
                "msp-bot", botToken), 404));
    }

    @Test
    void critical_이나_30분_넘는_보류는_SUPER_와_분까지_묶인_티켓이다() throws Exception {
        String path = "/admin/v1/msp/alerts/" + CRIT + "/silence";
        assertEquals("approval_required", reason(post(path, body(120), "msp-super", superToken), 403));
        String sixty = ticket("msp-super", superToken, "ops.alert.silence", CRIT + ":60");
        assertEquals("binding_mismatch", reason(post(path, body(120, sixty), "msp-super", superToken), 403));

        String token = ticket("msp-super", superToken, "ops.alert.silence", CRIT + ":120");
        JsonNode silenced = json(post(path, body(120, token), "msp-super", superToken), 201);
        assertFalse(silenced.path("silence_id").asText().isEmpty());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE action = 'msp.alert.silence' "
                + "AND approval_id IS NOT NULL").query(Long.class).single());
    }

    @Test
    void 생존_신호와_활성이_아닌_경보와_이미_눌린_경보는_보류하지_않는다() throws Exception {
        String base = "/admin/v1/msp/alerts/";
        assertEquals("alert_excluded", reason(post(base + HEART + "/silence", body(10), "msp-bot", botToken), 404));
        assertEquals("alert_not_active", reason(post(base + "9999999999999999/silence", body(10), "msp-bot", botToken), 404));
        assertEquals("alert_already_silenced", json(post(base + MUTED + "/silence", body(10), "msp-bot", botToken), 409)
                .path("error").path("name").asText());
        assertEquals("alert_excluded", reason(post(base + HEART + "/ack", "{}", "msp-bot", botToken), 404));
    }

    @Test
    void 확인은_BOT_OPS_이상이고_사건과_타임라인에_남는다() throws Exception {
        assertEquals("role_required", reason(post("/admin/v1/msp/alerts/" + WARN + "/ack", "{}", "msp-viewer",
                viewerToken), 403));
        JsonNode acked = json(post("/admin/v1/msp/alerts/" + WARN + "/ack", "{}", "msp-bot", botToken), 200);
        long incident = acked.path("incident_id").asLong();

        JsonNode detail = json(get("/admin/v1/msp/incidents/" + incident, "msp-viewer", viewerToken), 200);
        assertEquals("msp-bot", detail.path("incident").path("acked_by").asText());
        assertFalse(detail.path("incident").has("instance"));
        List<String> kinds = new ArrayList<>();
        detail.path("events").forEach(e -> kinds.add(e.path("kind").asText()));
        assertTrue(kinds.containsAll(List.of("OPENED", "ACKED")), kinds.toString());

        JsonNode list = json(get("/admin/v1/msp/incidents?status=OPEN&limit=2", "msp-viewer", viewerToken), 200);
        assertEquals(2, list.path("data").size());
        assertTrue(list.path("pagination").path("has_more").asBoolean());
        assertEquals(400, get("/admin/v1/msp/incidents?status=ALL", "msp-viewer", viewerToken).getStatusCode().value());
        assertEquals(404, get("/admin/v1/msp/incidents/999999", "msp-viewer", viewerToken).getStatusCode().value());
    }

    @Test
    void 백업_즉시_실행은_연결_전이라_성공을_지어내지_않는다() throws Exception {
        assertEquals("role_required", reason(post("/admin/v1/msp/ops/backup", "{}", "msp-bot", botToken), 403));
        JsonNode denied = json(post("/admin/v1/msp/ops/backup", "{}", "msp-super", superToken), 409);
        assertEquals("msp_source_not_connected", denied.path("error").path("name").asText());
        assertEquals("databasus", denied.path("error").path("detail").path("source").asText());
    }

    @Test
    void 미설정_임계값은_게이지에서_NaN_으로_나간다() {
        String scrape = rest.getForObject("/actuator/prometheus", String.class);

        assertTrue(scrape.lines().anyMatch(l -> l.startsWith("exchange_ops_threshold{")
                && l.contains("key=\"host.cpu_credit_alert\"") && l.endsWith(" NaN")), scrape);
        assertTrue(scrape.lines().anyMatch(l -> l.startsWith("exchange_ops_threshold{")
                && l.contains("key=\"backup.delay_alert\"") && l.endsWith(" 93600.0")), "초기값은 숫자로 나간다");
    }

    /* ===== 스텁 Alertmanager ===== */

    private static StubSource.Response alertmanager(StubSource.Request r) {
        if (r.path().equals("/api/v2/alerts")) {
            return StubSource.Response.json("[" + String.join(",", active) + "]");
        }
        if (r.path().equals("/api/v2/status")) {
            return StubSource.Response.json("{\"uptime\":\"2026-01-01T00:00:00Z\"}");
        }
        if (r.path().equals("/api/v2/silences") && r.method().equals("POST")) {
            String id = java.util.UUID.randomUUID().toString();
            synchronized (SILENCES) {
                SILENCES.put(id, "active");
            }
            return StubSource.Response.json("{\"silenceID\":\"" + id + "\"}");
        }
        if (r.path().startsWith("/api/v2/silence/")) {
            String id = r.path().substring("/api/v2/silence/".length());
            synchronized (SILENCES) {
                String state = SILENCES.get(id);
                if (state == null) {
                    return StubSource.Response.status(404, "\"silence not found\"");
                }
                if (r.method().equals("DELETE")) {
                    SILENCES.put(id, "expired");
                    return StubSource.Response.json("");
                }
                return StubSource.Response.json("{\"id\":\"" + id + "\",\"status\":{\"state\":\"" + state
                        + "\"},\"endsAt\":\"2026-12-31T00:00:00Z\"}");
            }
        }
        return StubSource.Response.status(404, "{}");
    }

    private static String alert(String fingerprint, String name, String severity, String silencedBy) {
        return "{\"fingerprint\":\"" + fingerprint + "\",\"labels\":{\"alertname\":\"" + name + "\",\"severity\":\""
                + severity + "\",\"node\":\"db-primary\",\"instance\":\"10.0.0.1:9100\"},"
                + "\"annotations\":{\"summary\":\"" + name + " 요약\",\"threshold_key\":\"host.disk_free_alert\"},"
                + "\"startsAt\":\"2026-09-24T23:50:00.000Z\","
                + "\"status\":{\"state\":\"active\",\"silencedBy\":" + silencedBy + ",\"inhibitedBy\":[]}}";
    }

    /* ===== 요청 ===== */

    private static String body(int minutes) {
        return "{\"minutes\":" + minutes + ",\"reason\":\"점검 중\"}";
    }

    private static String body(int minutes, String token) {
        return "{\"minutes\":" + minutes + ",\"reason\":\"점검 중\",\"approval_token\":\"" + token + "\"}";
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

    private ResponseEntity<String> delete(String path, String operator, String token) {
        return exchange(path, HttpMethod.DELETE, null, operator, token);
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

    private static JsonNode source(JsonNode overview, String name) {
        for (JsonNode s : overview.path("sources")) {
            if (name.equals(s.path("name").asText())) {
                return s;
            }
        }
        throw new AssertionError("원천 없음: " + name);
    }

    private static JsonNode item(JsonNode items, String key) {
        for (JsonNode i : items) {
            if (key.equals(i.path("key").asText())) {
                return i;
            }
        }
        throw new AssertionError("항목 없음: " + key);
    }
}

package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.admin.AdminOperatorSeeder;
import kr.exchange.admin.AdminRole;
import kr.exchange.admin.CapacityPlanService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관제센터 API 의 실제 응답을 웹 어댑터 고정 자료로 남긴다(API 설계 §6-F). MSP_SNAPSHOT_DIR 이 있을 때만 돈다.
 * 로컬 Docker·조회 용량 계획(CAPACITY_PLAN) 환경을 스텁 원천(Prometheus, Alertmanager, Ollama 형식 LLM, 보고 서비스,
 * 앱 노드 셋)으로 띄우고, 판단 주기는 기다리지 않고 직접 부른다. 시각은 실제 시각이라 파일마다 다르다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MSP_SNAPSHOT_DIR", matches = ".+")
class MspContractSnapshotIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final StubSource PROMETHEUS;
    static final StubSource ALERTMANAGER;
    static final StubSource LLM;
    static final StubSource REPORTING;
    static final StubSource APP1;
    static final StubSource APP2;
    static final StubSource APP3;
    static final Path TOKEN_FILE;

    static final String WARN = "a1b2c3d4e5f60001";
    static final String CRIT = "a1b2c3d4e5f60002";
    static final String OTHER = "a1b2c3d4e5f60003";
    static final String MUTED = "a1b2c3d4e5f60004";
    static final String MEM = "a1b2c3d4e5f60005";
    static final String HEART = "a1b2c3d4e5f60006";
    static final String TRIGGER = "a1b2c3d4e5f60007";
    static final Instant STARTED = Instant.now();

    static final Map<String, String> SILENCES = new ConcurrentHashMap<>();
    static volatile double cpu = 0.82;
    static volatile boolean trigger;
    // 원천 없는 한 판을 먼저 남기려고 기동 때의 첫 폴링에는 경보를 내지 않는다.
    static volatile boolean alertsLive;
    static volatile String judge = "";

    static final String ADD = out("조회를 받는 앱 노드의 평균 CPU가 0.82로 증설 임계 0.60 이상입니다.",
            "app-3를 켜서 앱 계층을 2대에서 3대로 늘리면 조회 부하가 나뉩니다.", "READ_NODE_ADD_ONE", "app", "", "0.9");
    static final String RAISE_SCALE_IN = out("조회를 받는 앱 노드의 평균 CPU가 0.45로 증설·축소 임계 사이입니다.",
            "축소 임계를 0.35로 올려 부하가 줄면 app-3를 더 일찍 끄게 합니다.", "THRESHOLD_RAISE", "read.scale_in_cpu",
            "0.35", "0.9");
    static final String PROMOTE = out("복제 재생 지연 경보가 울리고 있습니다.", "스탠바이를 승격해야 합니다.", "DB_PROMOTE",
            "db-standby", "", "0.7");
    static final String CHAT = json(Map.of(
            "answer_ko", "조회를 받는 앱 노드의 평균 CPU가 0.82로 증설 임계 0.60 이상입니다. app-3를 켜면 조회가 세 노드로"
                    + " 나뉩니다. 저는 조치를 실행하지 않았고 실행할 수 없으므로 관제센터의 AI 조치안 카드나 용량 화면에서"
                    + " 운영자가 직접 처리합니다.",
            "cited_ids", List.of("read_tier", "threshold.read.scale_out_cpu", "alert." + WARN)));

    static {
        POSTGRES.start();
        try {
            PROMETHEUS = new StubSource();
            ALERTMANAGER = new StubSource();
            LLM = new StubSource();
            REPORTING = new StubSource();
            APP1 = new StubSource();
            APP2 = new StubSource();
            APP3 = new StubSource();
            TOKEN_FILE = Files.createTempFile("msp-snapshot-reporting", ".token");
            Files.writeString(TOKEN_FILE, "reporting-ingest-token-for-msp-snapshot-0123456789");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        PROMETHEUS.handler = MspContractSnapshotIntegrationTest::prometheus;
        ALERTMANAGER.handler = MspContractSnapshotIntegrationTest::alertmanager;
        LLM.handler = MspContractSnapshotIntegrationTest::llm;
        REPORTING.handler = MspContractSnapshotIntegrationTest::reporting;
        APP1.handler = r -> node(r, true);
        APP2.handler = r -> node(r, false);
        // app-3 는 희망 2대에서 꺼져 있다. 지표를 못 읽으면 관측은 도달 불가다.
        APP3.handler = r -> StubSource.Response.status(503, "{}");
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
        registry.add("exchange.msp.environment", () -> "LOCAL_DOCKER");
        registry.add("exchange.msp.read-capacity.mode", () -> "CAPACITY_PLAN");
        registry.add("exchange.msp.prometheus-url", () -> PROMETHEUS.uri().toString());
        registry.add("exchange.msp.alertmanager-url", () -> ALERTMANAGER.uri().toString());
        registry.add("exchange.msp.incident.poll-interval", () -> "1h");
        registry.add("exchange.msp.incident.resolve-after", () -> "2h");
        registry.add("exchange.msp.slo.availability-ratio", () -> "0.999");
        registry.add("exchange.msp.slo.p95-seconds", () -> "0.3");
        registry.add("exchange.msp.slo.error-ratio", () -> "0.01");
        String[][] hosts = {{"app-1", "APP", "ap-northeast-2a"}, {"app-2", "APP", "ap-northeast-2c"},
                {"db-primary", "DB", "ap-northeast-2a"}, {"db-standby", "DB", "ap-northeast-2c"},
                {"bot-1", "BOT_OBS", "ap-northeast-2a"}};
        for (int i = 0; i < hosts.length; i++) {
            String[] h = hosts[i];
            registry.add("exchange.msp.hosts[" + i + "].node", () -> h[0]);
            registry.add("exchange.msp.hosts[" + i + "].role", () -> h[1]);
            registry.add("exchange.msp.hosts[" + i + "].az", () -> h[2]);
            registry.add("exchange.msp.hosts[" + i + "].instance-type", () -> "t4g.small");
        }
        registry.add("exchange.msp.llm.provider", () -> "OLLAMA_NATIVE");
        registry.add("exchange.msp.llm.base-url", () -> LLM.uri().toString());
        registry.add("exchange.msp.llm.model", () -> "test-model");
        registry.add("exchange.msp.ai.interval", () -> "10m");
        registry.add("exchange.msp.ai.recheck-interval", () -> "10m");
        registry.add("exchange.msp.ai.proposal-ttl", () -> "1h");
        registry.add("exchange.msp.reporting.url", () -> REPORTING.uri().toString());
        registry.add("exchange.msp.reporting.token-file", TOKEN_FILE::toString);
        registry.add("exchange.msp.reporting.environment", () -> "local");
        registry.add("exchange.msp.reporting.artifact-base-url", () -> "https://reports.failstop.example");
        StubSource[] apps = {APP1, APP2, APP3};
        for (int i = 0; i < apps.length; i++) {
            StubSource app = apps[i];
            registry.add("exchange.capacity.nodes[" + i + "].id", () -> "app-" + (app == APP1 ? 1 : app == APP2 ? 2 : 3));
            registry.add("exchange.capacity.nodes[" + i + "].az", () -> app == APP2 ? "ap-northeast-2c" : "ap-northeast-2a");
            registry.add("exchange.capacity.nodes[" + i + "].instance-type", () -> "t4g.small");
            registry.add("exchange.capacity.nodes[" + i + "].base-url", () -> app.uri().toString());
            registry.add("exchange.capacity.nodes[" + i + "].engine", () -> app == APP3 ? "false" : "true");
            registry.add("exchange.capacity.nodes[" + i + "].scalable", () -> app == APP3 ? "true" : "false");
        }
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
    @Autowired
    AiLoop loop;
    @Autowired
    IncidentStore incidents;
    @Autowired
    OpsThresholdService thresholds;
    @Autowired
    CapacityPlanService capacity;
    @Autowired
    AiControl aiControl;
    @Autowired
    AiStore aiStore;
    @Autowired
    SourceCache cache;

    Path dir;
    String superToken;
    String botToken;
    String viewerToken;

    @BeforeAll
    void seed() throws Exception {
        dir = Path.of(System.getenv("MSP_SNAPSHOT_DIR"));
        Files.createDirectories(dir);
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        operators.ensureOperator("snap-super", "관제 총괄", Set.of(AdminRole.SUPER));
        operators.ensureOperator("snap-bot", "봇 운영", Set.of(AdminRole.BOT_OPS));
        operators.ensureOperator("snap-view", "관제 조회", Set.of(AdminRole.VIEWER));
        superToken = session("snap-super");
        botToken = session("snap-bot");
        viewerToken = session("snap-view");
        bareOverview();
        alertsLive = true;
        poller.pollOnce();
    }

    @AfterAll
    void close() throws IOException {
        for (StubSource s : List.of(PROMETHEUS, ALERTMANAGER, LLM, REPORTING, APP1, APP2, APP3)) {
            s.close();
        }
        Files.deleteIfExists(TOKEN_FILE);
    }

    @Test
    void 관제센터_응답을_고정_자료로_남긴다() throws Exception {
        /* ===== 읽기 ===== */
        save("hosts", get("/admin/v1/msp/hosts"), 200);
        save("alerts", get("/admin/v1/msp/alerts"), 200);
        save("ops", get("/admin/v1/msp/ops"), 200);
        save("thresholds", get("/admin/v1/msp/thresholds"), 200);
        save("trends-1h", get("/admin/v1/msp/trends?range=1h"), 200);
        save("trends-6h", get("/admin/v1/msp/trends?range=6h"), 200);
        save("capacity-scale-out", get("/admin/v1/capacity"), 200);
        save("ai-mode-manual", get("/admin/v1/msp/ai/mode"), 200);
        save("ai-limits", get("/admin/v1/msp/ai/limits"), 200);
        save("ai-server", get("/admin/v1/msp/ai/server"), 200);
        save("reports", get("/admin/v1/msp/reports?limit=5"), 200);
        save("reports-status", get("/admin/v1/msp/reports/status"), 200);

        /* ===== 자동 모드와 T6 로 멈춘 임계값 요청 ===== */
        cpu = 0.45;
        String auto = ticket(AiControl.AUTO_ACTION, "AUTO:4");
        save("ai-mode-auto-post", call(HttpMethod.POST, "/admin/v1/msp/ai/mode/auto",
                body("hours", 4, "reason", "야간 관제", "approval_token", auto), "snap-super", superToken), 200);
        save("ai-mode-auto", get("/admin/v1/msp/ai/mode"), 200);
        judge = RAISE_SCALE_IN;
        freshTick();
        loop.tick();
        JsonNode open = save("ai-actions-open", get("/admin/v1/msp/ai/actions?status=OPEN"), 200);
        JsonNode held = open.path("data").get(0);
        assertEquals("T6", held.path("hold_rule").asText(), held.toString());
        long thresholdAction = held.path("id").asLong();
        save("ai-action-approve-threshold", call(HttpMethod.POST, "/admin/v1/msp/ai/actions/" + thresholdAction
                + "/approve", body("approval_token", ticket(OpsThresholdService.APPROVAL_ACTION,
                "read.scale_in_cpu:0.35")), "snap-super", superToken), 200);
        save("ai-action-revert", call(HttpMethod.POST, "/admin/v1/msp/ai/actions/" + thresholdAction + "/revert",
                body("approval_token", ticket(OpsThresholdService.APPROVAL_ACTION, "read.scale_in_cpu:0.3")),
                "snap-super", superToken), 200);
        save("ai-mode-manual-post", call(HttpMethod.POST, "/admin/v1/msp/ai/mode/manual",
                body("reason", "자동 모드 점검 종료"), "snap-view", viewerToken), 200);

        /* ===== 수동 모드의 app-3 켜기 요청 ===== */
        cpu = 0.82;
        judge = PROMOTE;
        freshTick();
        long dropped = aiStore.lastDecision().orElseThrow().id();
        judge = ADD;
        freshTick();
        long proposed = aiStore.lastDecision().orElseThrow().id();
        loop.tick();
        JsonNode openRead = save("ai-actions-open-read-nodes", get("/admin/v1/msp/ai/actions?status=OPEN"), 200);
        long readAction = openRead.path("data").get(0).path("id").asLong();
        save("ai-decision-detail", get("/admin/v1/msp/ai/decisions/" + proposed), 200);
        JsonNode dropDecision = save("ai-decision-dropped", get("/admin/v1/msp/ai/decisions/" + dropped), 200);

        ResponseEntity<String> full = get("/admin/v1/msp/overview");
        save("overview-full", full, 200);
        save("overview-304", call(HttpMethod.GET, "/admin/v1/msp/overview", null, "snap-view", viewerToken,
                full.getHeaders().getETag()), 304);

        save("ai-action-reject", call(HttpMethod.POST, "/admin/v1/msp/ai/actions/" + readAction + "/reject",
                body("reason", "야간에는 app-3를 켜지 않습니다"), "snap-view", viewerToken), 200);
        save("error-not-open", call(HttpMethod.POST, "/admin/v1/msp/ai/actions/" + readAction + "/approve",
                body(), "snap-super", superToken), 409);

        freshTick();
        long deferAction = latestAction();
        save("ai-action-defer", call(HttpMethod.POST, "/admin/v1/msp/ai/actions/" + deferAction + "/defer",
                body("minutes", 30), "snap-view", viewerToken), 200);
        save("ai-action-approve", call(HttpMethod.POST, "/admin/v1/msp/ai/actions/" + deferAction + "/approve",
                body(), "snap-super", superToken), 200);
        save("capacity-at-max", get("/admin/v1/capacity"), 200);
        save("ai-action-revert-read-nodes", call(HttpMethod.POST, "/admin/v1/msp/ai/actions/" + deferAction
                + "/revert", body(), "snap-super", superToken), 200);

        // 요청을 낸 뒤 사람이 app-3를 먼저 켜면 승인은 최대 대수 밖이라 정책(R1)이 막는다.
        freshTick();
        long boundAction = latestAction();
        save("capacity-scale-out-post", call(HttpMethod.POST, "/admin/v1/capacity/scale-out",
                body("reason", "조회 부하 대응"), "snap-super", superToken), 200);
        JsonNode denied = save("error-policy-denied", call(HttpMethod.POST, "/admin/v1/msp/ai/actions/"
                + boundAction + "/approve", body(), "snap-super", superToken), 409);
        assertEquals("R1", denied.path("error").path("detail").path("rule").asText(), denied.toString());
        save("capacity-scale-in-post", call(HttpMethod.POST, "/admin/v1/capacity/scale-in",
                body("reason", "부하 해소"), "snap-super", superToken), 200);

        /* ===== 사람의 임계값·경보 조치 ===== */
        JsonNode set = save("threshold-set", call(HttpMethod.POST, "/admin/v1/msp/thresholds/host.mem_alert",
                body("value", "0.85", "reason", "메모리 경보 첫 값", "approval_token",
                        ticket(OpsThresholdService.APPROVAL_ACTION, "host.mem_alert:0.85")), "snap-super", superToken), 200);
        save("threshold-changes", get("/admin/v1/msp/thresholds/changes?limit=20"), 200);
        save("threshold-revert", call(HttpMethod.POST, "/admin/v1/msp/thresholds/changes/"
                + set.path("change_id").asLong() + "/revert", body("reason", "첫 값 되돌림", "approval_token",
                ticket(OpsThresholdService.APPROVAL_ACTION, "host.mem_alert:null")), "snap-super", superToken), 200);
        JsonNode acked = save("alert-ack", call(HttpMethod.POST, "/admin/v1/msp/alerts/" + WARN + "/ack", body(),
                "snap-bot", botToken), 200);
        JsonNode silenced = save("alert-silence", call(HttpMethod.POST, "/admin/v1/msp/alerts/" + MEM + "/silence",
                body("minutes", 30, "reason", "메모리 점검 중"), "snap-bot", botToken), 201);
        save("silence-delete", call(HttpMethod.DELETE, "/admin/v1/msp/silences/" + silenced.path("silence_id").asText(),
                null, "snap-bot", botToken), 200);
        save("incidents", get("/admin/v1/msp/incidents?limit=20"), 200);
        save("incident-detail", get("/admin/v1/msp/incidents/" + acked.path("incident_id").asLong()), 200);
        save("incident-detail-ai", get("/admin/v1/msp/incidents/" + dropDecision.path("incident_id").asLong()), 200);

        save("maintenance-window-create", call(HttpMethod.POST, "/admin/v1/msp/maintenance-windows",
                body("ends_at", Instant.now().plus(Duration.ofHours(2)).toString(), "alertname", "ReadTierCpuHigh",
                        "labels", Map.of("component", "app"), "reason", "app-3 교체 작업"), "snap-bot", botToken), 201);
        save("maintenance-windows", get("/admin/v1/msp/maintenance-windows?status=ALL"), 200);
        save("ai-limits-put", call(HttpMethod.PUT, "/admin/v1/msp/ai/limits/bot_cooldown_min",
                body("value", 20, "reason", "봇 부하 조치 간격 확대", "approval_token",
                        ticket(AiControl.LIMITS_ACTION, "bot_cooldown_min:20")), "snap-super", superToken), 200);
        save("ai-limits-changes", get("/admin/v1/msp/ai/limits/changes?key=bot_cooldown_min"), 200);

        /* ===== 상담과 정지 ===== */
        save("ai-chat-answered", call(HttpMethod.POST, "/admin/v1/msp/ai/chat",
                body("question", "조회 부하가 왜 높습니까?", "history", List.of()), "snap-view", viewerToken), 200);
        save("ai-stop", call(HttpMethod.POST, "/admin/v1/msp/ai/stop", body("reason", "판단 점검"), "snap-view",
                viewerToken), 200);
        save("ai-mode-stopped", get("/admin/v1/msp/ai/mode"), 200);
        save("ai-chat-stopped", call(HttpMethod.POST, "/admin/v1/msp/ai/chat",
                body("question", "지금 경보가 몇 개입니까?", "history", List.of()), "snap-view", viewerToken), 200);
        save("ai-resume", call(HttpMethod.POST, "/admin/v1/msp/ai/resume", body("reason", "점검 완료"), "snap-super",
                superToken), 200);

        save("ai-decisions", get("/admin/v1/msp/ai/decisions?limit=20"), 200);
        save("ai-actions-all", get("/admin/v1/msp/ai/actions?status=ALL&limit=20"), 200);
    }

    /** 관제센터만 켜고 원천을 하나도 잇지 않은 학교 AWS 한 판. 칸마다 연결 전이다. 컨트롤러와 같은 태그를 붙인다. */
    private void bareOverview() throws IOException {
        MspProperties bare = new MspProperties();
        bare.setEnabled(true);
        bare.setEnvironment(MspProperties.Environment.SCHOOL_AWS);
        MspBoard board = new MspBoard(bare, null, null, incidents, thresholds, capacity, Clock.systemUTC(), aiControl,
                aiStore, null, cache, new MspTrends(bare, null, incidents, cache), new AiServerStatus(null, cache),
                new MspReporting(null, cache, jdbc, bare.getReporting()));
        Map<String, Object> body = board.overview();
        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.put("note", "exchange.msp.enabled=true, 원천 주소 없음(SCHOOL_AWS). MspBoard 를 원천 없이 조립한 한 판이다.");
        wrapped.put("status", 200);
        wrapped.put("etag", MspController.etag(body, mapper));
        wrapped.set("body", mapper.valueToTree(body));
        write("overview-bare", wrapped);
    }

    /* ===== 판단 주기 ===== */

    /** 같은 상황의 재판단 간격을 넘기려고 판단 거리 경보 하나를 켜거나 끈 뒤 판단을 한 번 돌린다. */
    private void freshTick() {
        trigger = !trigger;
        poller.pollOnce();
        loop.tick();
    }

    private long latestAction() throws Exception {
        JsonNode open = json(get("/admin/v1/msp/ai/actions?status=OPEN"), 200);
        assertTrue(open.path("data").size() > 0, open.toString());
        return open.path("data").get(0).path("id").asLong();
    }

    /* ===== 고정 자료 ===== */

    private JsonNode save(String name, ResponseEntity<String> response, int status) throws Exception {
        assertEquals(status, response.getStatusCode().value(), name + ": " + response.getBody());
        JsonNode parsed = response.getBody() == null || response.getBody().isEmpty() ? null
                : mapper.readTree(response.getBody());
        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.put("status", status);
        String etag = response.getHeaders().getETag();
        if (etag != null) {
            wrapped.put("etag", etag);
        }
        wrapped.set("body", parsed);
        write(name, wrapped);
        return parsed;
    }

    private void write(String name, JsonNode node) throws IOException {
        Files.writeString(dir.resolve(name + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n", StandardCharsets.UTF_8);
    }

    /* ===== 요청 ===== */

    private String session(String operator) throws Exception {
        ResponseEntity<String> issued = call(HttpMethod.POST, "/admin/v1/session", null, operator, null);
        return json(issued, 200).path("token").asText();
    }

    private String ticket(String action, String target) throws Exception {
        ResponseEntity<String> issued = call(HttpMethod.POST, "/admin/v1/approvals",
                body("requester_operator_id", "snap-super", "action", action, "target", target), "snap-super",
                superToken);
        return json(issued, 200).path("approval_token").asText();
    }

    private ResponseEntity<String> get(String path) {
        return call(HttpMethod.GET, path, null, "snap-view", viewerToken);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String body, String operator, String token) {
        return call(method, path, body, operator, token, null);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String body, String operator, String token,
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

    private static String body(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return json(m);
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String out(String situation, String reason, String action, String target, String value, String confidence) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("situation_ko", situation);
        o.put("reason_ko", reason);
        o.put("action", action);
        o.put("target", target);
        o.put("value", value);
        o.put("confidence", Double.parseDouble(confidence));
        return o.toString();
    }

    /* ===== 스텁 원천 ===== */

    private static StubSource.Response node(StubSource.Request r, boolean leader) {
        return switch (r.path()) {
            case "/actuator/health/readiness" -> leader ? StubSource.Response.json("{\"status\":\"UP\"}")
                    : StubSource.Response.status(503, "{\"status\":\"OUT_OF_SERVICE\"}");
            case "/actuator/health/read" -> StubSource.Response.json("{\"status\":\"UP\"}");
            case "/actuator/prometheus" -> new StubSource.Response(200, String.format(Locale.ROOT,
                    "process_cpu_usage %.4f\nsystem_cpu_count 2\n"
                            + "jvm_memory_used_bytes{area=\"heap\",id=\"G1 Eden Space\"} 1.26E8\n"
                            + "jvm_memory_used_bytes{area=\"heap\",id=\"G1 Old Gen\"} 2.1E8\n"
                            + "jvm_memory_max_bytes{area=\"heap\",id=\"G1 Old Gen\"} 5.36870912E8\n"
                            + "http_server_requests_seconds_count{uri=\"/api/v1/markets\"} %d\n"
                            + "exchange_ws_clients %d\n",
                    leader ? cpu : cpu - 0.04, leader ? 182340 : 176512, leader ? 14 : 11), 0, null);
            default -> StubSource.Response.status(404, "{}");
        };
    }

    private static StubSource.Response llm(StubSource.Request r) {
        return switch (r.path()) {
            case "/api/version" -> StubSource.Response.json("{\"version\":\"0.12.3\"}");
            case "/api/ps" -> StubSource.Response.json(json(Map.of("models", List.of(Map.of("name", "test-model",
                    "model", "test-model", "size_vram", 13958643712L,
                    "expires_at", Instant.now().plus(Duration.ofMinutes(5)).toString())))));
            case "/api/generate" -> StubSource.Response.json("{\"done\":true}");
            case "/api/chat" -> StubSource.Response.json(json(Map.of("model", "test-model", "done", true,
                    "message", Map.of("role", "assistant", "content", r.body().contains("answer_ko") ? CHAT : judge))));
            default -> StubSource.Response.status(404, "{}");
        };
    }

    private static StubSource.Response reporting(StubSource.Request r) {
        long now = Instant.now().getEpochSecond();
        String hex = "0123456789abcdef".repeat(4);
        return switch (r.path()) {
            case "/msp/events" -> new StubSource.Response(202, "{\"id\":\"evt-" + UUID.randomUUID()
                    + "\",\"duplicate\":false}", 0, null);
            case "/console/status" -> StubSource.Response.json(json(Map.of("state", "ok", "delivery_enabled", true,
                    "counts", Map.of("pending", 1, "sent", 14, "dead", 0),
                    "last_sent_at", now - 3600, "last_attempt", Map.of("at", now - 3600, "status", "sent"),
                    "oldest_pending_age_seconds", 12.4, "last_daily_sent_at", now - 3 * 3600)));
            case "/console/reports" -> StubSource.Response.json(json(Map.of("state", "ok", "reports", List.of(
                    Map.of("id", "daily:" + (now - 3 * 3600), "kind", "daily", "created", now - 3 * 3600 - 40,
                            "sent", now - 3 * 3600, "status", "sent", "title", "일일 운영 보고", "severity", "P4",
                            "summary", "경보 3건, 사람 조치 1건, AI 제안 2건", "targets", List.of(),
                            "links", Map.of("pdf", "/reports/" + hex + "/" + (now - 3 * 3600) + "/" + hex
                                    + "/report.pdf", "html", "/reports/" + hex + "/" + (now - 3 * 3600) + "/" + hex
                                    + "/report.html")),
                    Map.of("id", "incident:" + (now - 3600), "kind", "incident", "created", now - 3600,
                            "sent", now - 3600, "status", "sent", "title", "복제 재생 지연", "severity", "P2",
                            "summary", "db-standby 재생 지연이 임계를 넘었습니다.", "alertState", "firing",
                            "targets", List.of("db-standby")),
                    Map.of("id", "msp:" + now, "kind", "msp", "created", now - 60, "status", "pending",
                            "title", "AI 조치 제안", "severity", "P3", "summary", "app-3 켜기(앱 계층 2 → 3대)",
                            "targets", List.of("app"))))));
            default -> StubSource.Response.status(404, "{}");
        };
    }

    private static StubSource.Response alertmanager(StubSource.Request r) {
        if (r.path().equals("/api/v2/alerts") && !alertsLive) {
            return StubSource.Response.json("[]");
        }
        if (r.path().equals("/api/v2/alerts")) {
            List<Object> alerts = new ArrayList<>(List.of(
                    alert(WARN, "ReadTierCpuHigh", "warning", "app", "app-1", 12, "read.scale_out_cpu", false),
                    alert(CRIT, "PostgresReplicationLagHigh", "critical", "database", "db-standby", 40, null, false),
                    alert(OTHER, "LinuxServiceOdd", "none", "os", "bot-1", 90, null, false),
                    alert(MUTED, "LinuxInodesLow", "warning", "storage", "db-primary", 180, null, true),
                    alert(MEM, "LinuxMemoryHigh", "warning", "os", "app-2", 25, "host.mem_alert", false),
                    alert(HEART, "Watchdog", "none", "monitoring", "bot-1", 600, null, false)));
            if (trigger) {
                alerts.add(alert(TRIGGER, "AppHttpLatencyHigh", "warning", "app", "app-2", 5, null, false));
            }
            return StubSource.Response.json(json(alerts));
        }
        if (r.path().equals("/api/v2/status")) {
            return StubSource.Response.json("{\"uptime\":\"2026-09-25T00:00:00Z\"}");
        }
        if (r.path().equals("/api/v2/silences") && r.method().equals("POST")) {
            String id = UUID.randomUUID().toString();
            SILENCES.put(id, "active");
            return StubSource.Response.json("{\"silenceID\":\"" + id + "\"}");
        }
        if (r.path().equals("/api/v2/silences")) {
            return StubSource.Response.json("[]");
        }
        if (r.path().startsWith("/api/v2/silence/")) {
            String id = r.path().substring("/api/v2/silence/".length());
            String state = SILENCES.get(id);
            if (state == null) {
                return StubSource.Response.status(404, "\"silence not found\"");
            }
            if (r.method().equals("DELETE")) {
                SILENCES.put(id, "expired");
                return StubSource.Response.json("");
            }
            return StubSource.Response.json("{\"id\":\"" + id + "\",\"status\":{\"state\":\"" + state
                    + "\"},\"endsAt\":\"" + Instant.now().plus(Duration.ofMinutes(30)) + "\"}");
        }
        return StubSource.Response.status(404, "{}");
    }

    private static Map<String, Object> alert(String fingerprint, String name, String severity, String component,
                                             String node, int minutesAgo, String thresholdKey, boolean muted) {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("alertname", name);
        labels.put("severity", severity);
        labels.put("component", component);
        labels.put("node", node);
        labels.put("instance", node + ":9100");
        if ("storage".equals(component)) {
            labels.put("mountpoint", "/");
        }
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("summary", name + " 요약");
        if (thresholdKey != null) {
            annotations.put("threshold_key", thresholdKey);
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("fingerprint", fingerprint);
        a.put("labels", labels);
        a.put("annotations", annotations);
        a.put("startsAt", STARTED.minus(Duration.ofMinutes(minutesAgo)).toString());
        a.put("status", Map.of("state", "active", "inhibitedBy", List.of(),
                "silencedBy", muted ? List.of("8e0a2c56-0000-4000-8000-000000000001") : List.of()));
        return a;
    }

    private static StubSource.Response prometheus(StubSource.Request r) {
        String q = r.param("query");
        if (r.path().equals("/api/v1/query_range")) {
            return range(q, Long.parseLong(r.param("start")), Long.parseLong(r.param("end")),
                    Long.parseLong(r.param("step")));
        }
        double now = Instant.now().toEpochMilli() / 1000.0;
        ArrayNode result = MAPPER.createArrayNode();
        for (Map.Entry<Map<String, String>, Double> s : series(q, now).entrySet()) {
            ObjectNode item = result.addObject();
            ObjectNode metric = item.putObject("metric");
            s.getKey().forEach(metric::put);
            ArrayNode value = item.putArray("value");
            value.add(now);
            value.add(BigDecimalText.of(s.getValue()));
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "success");
        ObjectNode data = root.putObject("data");
        data.put("resultType", "vector");
        data.set("result", result);
        return StubSource.Response.json(root.toString());
    }

    /** 질의 문자열로 명명 질의를 가려 시계열을 낸다. 키는 라벨이고 값은 표본이다. */
    private static Map<Map<String, String>, Double> series(String q, double now) {
        Map<Map<String, String>, Double> out = new LinkedHashMap<>();
        String[] hosts = {"app-1", "app-2", "db-primary", "db-standby", "bot-1"};
        if (q.startsWith("time() - timestamp(up") || q.startsWith("up{")) {
            boolean age = q.startsWith("time()");
            for (String h : hosts) {
                // bot-1 의 수집이 늦어 STALE 로 보이는 대상이다.
                out.put(Map.of("job", "linux", "node", h), age ? ("bot-1".equals(h) ? 95.0 : 4.0) : 1.0);
            }
            for (String h : List.of("app-1", "app-2")) {
                out.put(Map.of("job", "exchange-api", "node", h), age ? 6.0 : 1.0);
            }
            for (String h : List.of("db-primary", "db-standby")) {
                out.put(Map.of("job", "postgres", "node", h), age ? 5.0 : 1.0);
            }
            out.put(Map.of("job", "blackbox-http", "node", "bot-1"), age ? 7.0 : 1.0);
            return out;
        }
        if (q.contains("node_cpu_seconds_total")) {
            return byNode(hosts, 0.62, 0.58, 0.31, 0.12, 0.44);
        }
        if (q.contains("node_memory_MemAvailable_bytes")) {
            return byNode(hosts, 0.71, 0.66, 0.54, 0.41, 0.38);
        }
        if (q.contains("node_filesystem_avail_bytes")) {
            return byNode(hosts, 0.62, 0.64, 0.48, 0.52, 0.23);
        }
        switch (q) {
            case MspQuery.RULE_HTTP_REQUESTS -> out.put(Map.of("job", "exchange-api"), 142.5);
            case MspQuery.RULE_HTTP_5XX -> out.put(Map.of("job", "exchange-api"), 0.0012);
            case MspQuery.RULE_HTTP_P95 -> out.put(Map.of("job", "exchange-api"), 0.184);
            case MspQuery.RULE_PROBE_SUCCESS -> out.put(Map.of("job", "blackbox-http"), 0.9995);
            default -> {
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        if (q.contains("pg_wal_position_in_recovery")) {
            out.put(Map.of("node", "db-primary", "role", "data"), 0.0);
            out.put(Map.of("node", "db-standby", "role", "standby"), 1.0);
        } else if (q.contains("pg_up")) {
            out.put(Map.of("node", "db-primary"), 1.0);
            out.put(Map.of("node", "db-standby"), 1.0);
        } else if (q.contains("pg_replication_client_replay_lag_seconds")) {
            out.put(Map.of("node", "db-primary", "application_name", "db_standby", "state", "streaming"), 0.35);
        } else if (q.contains("pg_replication_client_sent_replay_bytes")) {
            out.put(Map.of("node", "db-primary", "application_name", "db_standby", "state", "streaming"), 8192.0);
        } else if (q.contains("failstop_db_backup_last_success_timestamp_seconds")) {
            out.put(Map.of(), Math.floor(now - 3 * 3600));
        } else if (q.contains("failstop_db_backup_last_verify_success")) {
            out.put(Map.of(), 1.0);
        } else if (q.contains("node_systemd_timer_last_trigger_seconds")) {
            out.put(Map.of(), Math.floor(now - 5 * 3600));
        } else if (q.startsWith("count(")) {
            out.put(Map.of(), 9.0);
        } else if (q.contains("last_over_time(")) {
            out.put(Map.of("key", "read.scale_out_cpu"), 0.6);
            out.put(Map.of("key", "read.scale_in_cpu"), 0.3);
            out.put(Map.of("key", "backup.delay_alert"), 93600.0);
            out.put(Map.of("key", "host.disk_free_alert"), 0.15);
            out.put(Map.of("key", "host.cpu_alert"), Double.NaN);
        }
        return out;
    }

    private static Map<Map<String, String>, Double> byNode(String[] hosts, double... values) {
        Map<Map<String, String>, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < hosts.length; i++) {
            out.put(Map.of("node", hosts[i]), values[i]);
        }
        return out;
    }

    /** 추세 한 벌. 격자 시각마다 값을 내고 한 점은 비워 둔다(표본 없음). */
    private static StubSource.Response range(String q, long start, long end, long step) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "success");
        ObjectNode data = root.putObject("data");
        data.put("resultType", "matrix");
        ArrayNode result = data.putArray("result");
        ObjectNode series = result.addObject();
        series.putObject("metric");
        ArrayNode values = series.putArray("values");
        long n = 0;
        for (long t = start; t <= end; t += step, n++) {
            if (n == 3) {
                continue;
            }
            double wave = Math.sin(n / 6.0);
            double v = switch (q) {
                case MspQuery.RULE_HTTP_P95 -> 0.18 + 0.04 * wave;
                case MspQuery.RULE_HTTP_5XX -> 0.001 + 0.0008 * Math.abs(wave);
                default -> 0.9995 - 0.0004 * Math.abs(wave);
            };
            ArrayNode point = values.addArray();
            point.add(t);
            point.add(BigDecimalText.of(v));
        }
        return StubSource.Response.json(root.toString());
    }

    /** Prometheus 가 표본 값을 문자열로 내는 모양. */
    private static final class BigDecimalText {

        static String of(double v) {
            if (Double.isNaN(v)) {
                return "NaN";
            }
            return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        }
    }
}

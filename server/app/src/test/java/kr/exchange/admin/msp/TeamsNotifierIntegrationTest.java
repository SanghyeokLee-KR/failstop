package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teams 통지를 보고 서비스로 넘기는 경로와 보고 서비스 읽기(설계 7-12). 보고 서비스는 로컬 스텁이고 실제 Teams 는 부르지
 * 않는다. 넘기는 본문에 서버가 만든 값만 있는지, 실패와 모호한 응답을 어떻게 다시 보내는지 본다.
 */
@Testcontainers
class TeamsNotifierIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final String TOKEN = "reporting-ingest-token-for-msp-tests-0123456789";
    private static final String ADD = AiRig.out("조회 계층 평균 CPU가 82%로 증설 임계 60%를 넘었습니다.",
            "조회 노드를 1대 늘려 부하를 나눠야 합니다.", "READ_NODE_ADD_ONE", "app", "", "0.9");
    private static final String ACCEPTED = "{\"accepted\":true,\"duplicate\":false,\"id\":\"msp:x\"}";

    @TempDir
    Path dir;

    private AiRig.Sources src;
    private AiRig rig;
    private StubSource reporting;
    private volatile Function<StubSource.Request, StubSource.Response> events;

    @BeforeAll
    static void migrate() {
        AiRig.migrate(POSTGRES);
    }

    @BeforeEach
    void start() throws IOException {
        src = new AiRig.Sources();
        rig = new AiRig(POSTGRES, src, MspProperties.Llm.Provider.OPENAI_COMPATIBLE, p -> { });
        reporting = new StubSource();
        events = r -> new StubSource.Response(202, ACCEPTED, 0, null);
        reporting.handler = r -> switch (r.path()) {
            case "/msp/events" -> events.apply(r);
            case "/console/reports" -> StubSource.Response.json(REPORTS);
            case "/console/status" -> StubSource.Response.json(STATUS);
            default -> StubSource.Response.status(404, "{}");
        };
    }

    @AfterEach
    void stop() {
        reporting.close();
        rig.close();
        src.close();
    }

    private MspProperties.Reporting settings() throws IOException {
        Path token = dir.resolve("reporter-token");
        Files.writeString(token, TOKEN + "\n");
        MspProperties.Reporting r = rig.props.getReporting();
        r.setUrl(reporting.uri().toString());
        r.setTokenFile(token.toString());
        r.setEnvironment("aws");
        r.setArtifactBaseUrl("https://reports.example.test/operations");
        return r;
    }

    private TeamsNotifier notifier(ReportingClient client) {
        TeamsNotifier n = new TeamsNotifier(client, rig.jdbc, rig.tx, rig.store, rig.incidents, rig.props, rig.mapper,
                rig.registry, rig.clock);
        rig.control.notifier(n);
        rig.executor.notifier(n);
        rig.loop.notifier(n);
        return n;
    }

    private String state(String kind) {
        return rig.text("SELECT state FROM ops_notifications WHERE kind = '" + kind + "' ORDER BY id DESC LIMIT 1");
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void 자동_실행_통지는_서버가_만든_값만_보관함에_쓰고_보고_서비스로_넘긴다() throws Exception {
        TeamsNotifier n = notifier(new ReportingClient(settings(), rig.mapper));
        rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(2, Duration.ofMinutes(1));
        assertEquals(3, rig.desired(), "자동 실행이 있었다");
        assertEquals("PENDING", state("AUTO_EXECUTED"));

        n.drain();

        assertEquals("ACCEPTED", state("AUTO_EXECUTED"));
        assertEquals("ACCEPTED", state("MODE_CHANGED"));
        List<StubSource.Request> sent = reporting.requests("/msp/events");
        assertEquals(2, sent.size());
        assertTrue(sent.stream().allMatch(r -> ("Bearer " + TOKEN).equals(r.headers().get("authorization"))));
        JsonNode auto = sent.stream().map(r -> tree(r.body())).filter(b -> b.path("kind").asText().equals("AUTO_EXECUTED"))
                .findFirst().orElseThrow();
        // 보고 서비스(deploy/reporting/msp_events.py)는 이 필드만 받고 모르는 필드가 있으면 거부한다.
        assertEquals(Set.of("schema_version", "event_id", "kind", "occurred_at", "scope", "action", "mode", "model",
                "metrics", "unconfirmed", "counts"), fields(auto));
        assertEquals(Set.of("id", "kind", "verb", "target", "from", "to", "status", "confidence", "observe_until",
                "observe_outcome"), fields(auto.path("action")));
        assertEquals(Set.of("name", "value", "threshold"), fields(auto.path("metrics").get(0)));
        assertEquals(1, auto.path("schema_version").asInt());
        assertEquals("exchange", auto.path("scope").path("customer").asText());
        assertEquals("aws", auto.path("scope").path("environment").asText());
        assertEquals("READ_NODE_ADD_ONE", auto.path("action").path("verb").asText());
        assertEquals("2", auto.path("action").path("from").asText());
        assertEquals("3", auto.path("action").path("to").asText());
        assertEquals("0.9", auto.path("action").path("confidence").asText());
        assertEquals("AUTO_EXECUTED", auto.path("action").path("status").asText());
        assertEquals("test-model", auto.path("model").asText());
        JsonNode cpu = auto.path("metrics").get(0);
        assertEquals("read_avg_cpu", cpu.path("name").asText());
        assertEquals("0.82", cpu.path("value").asText());
        assertEquals("0.6", cpu.path("threshold").asText());
        String raw = sent.get(0).body() + sent.get(1).body();
        assertFalse(raw.contains("부하를 나눠야"), "모델의 이유 문장을 싣지 않는다");
        assertFalse(raw.contains("situation") || raw.contains("reason_ko"));
        assertFalse(raw.contains("127.0.0.1") || raw.contains(TOKEN), "주소와 토큰을 본문에 싣지 않는다");
        assertEquals(2, rig.count("SELECT count(*) FROM ops_incident_events WHERE kind = 'NOTIFIED'"));
        assertEquals(1, rig.count("SELECT count(*) FROM ops_notifications WHERE state = 'ACCEPTED' "
                + "AND report_id = 'msp:x' AND kind = 'AUTO_EXECUTED'"));
    }

    @Test
    void 넘김이_실패하면_지수_대기로_다시_보내고_영구_실패는_FAILED로_닫는다() throws Exception {
        TeamsNotifier n = notifier(new ReportingClient(settings(), rig.mapper));
        events = r -> StubSource.Response.status(503, "{\"error\":\"storage_error\"}");
        rig.control.stop(AiRig.VIEWER, "점검", "req-stop");

        n.drain();
        assertEquals("PENDING", state("STOPPED"));
        assertEquals(1, rig.count("SELECT attempts FROM ops_notifications WHERE kind = 'STOPPED'"));
        assertEquals("http_503", rig.text("SELECT last_error FROM ops_notifications WHERE kind = 'STOPPED'"));
        n.drain();
        assertEquals(1, reporting.requests("/msp/events").size(), "대기 시각 전에는 다시 보내지 않는다");

        rig.clock.advance(Duration.ofSeconds(5));
        n.drain();
        assertEquals(2, reporting.requests("/msp/events").size());
        events = r -> StubSource.Response.status(400, "{\"error\":\"invalid_event\"}");
        rig.clock.advance(Duration.ofSeconds(10));
        n.drain();
        assertEquals("FAILED", state("STOPPED"));
        assertEquals("http_400", rig.text("SELECT last_error FROM ops_notifications WHERE kind = 'STOPPED'"));
        rig.clock.advance(Duration.ofHours(1));
        n.drain();
        assertEquals(3, reporting.requests("/msp/events").size(), "닫은 통지는 다시 보내지 않는다");
    }

    @Test
    void 응답을_잃은_넘김은_같은_사건_id로_다시_보내고_보고_서비스가_중복을_거른다() throws Exception {
        TeamsNotifier n = notifier(new ReportingClient(settings(), rig.mapper));
        events = r -> new StubSource.Response(202, ACCEPTED, 3500, null);
        rig.control.stop(AiRig.VIEWER, "점검", "req-stop");

        n.drain();
        assertEquals("PENDING", state("STOPPED"), "응답을 못 받았으면 다시 보낼 차례로 둔다");
        assertEquals("timeout", rig.text("SELECT last_error FROM ops_notifications WHERE kind = 'STOPPED'"));

        events = r -> new StubSource.Response(202, "{\"accepted\":true,\"duplicate\":true,\"id\":\"msp:x\"}", 0, null);
        rig.clock.advance(Duration.ofSeconds(5));
        n.drain();
        assertEquals("ACCEPTED", state("STOPPED"));
        List<StubSource.Request> sent = reporting.requests("/msp/events");
        assertEquals(2, sent.size());
        assertEquals(tree(sent.get(0).body()).path("event_id"), tree(sent.get(1).body()).path("event_id"));
    }

    @Test
    void 넘기다_멈춰_오래_남은_SENDING은_다시_가져가_보낸다() throws Exception {
        TeamsNotifier n = notifier(new ReportingClient(settings(), rig.mapper));
        rig.control.stop(AiRig.VIEWER, "점검", "req-stop");
        rig.jdbc.sql("UPDATE ops_notifications SET state = 'SENDING', claimed_at = ?")
                .param(AiControl.at(rig.clock.instant().minus(Duration.ofMinutes(3)))).update();

        n.drain();

        assertEquals("ACCEPTED", state("STOPPED"));
    }

    @Test
    void 보고_서비스_설정이_없으면_보관함에_SKIPPED로_남기고_보내지_않는다() {
        TeamsNotifier n = notifier(null);
        rig.control.stop(AiRig.VIEWER, "점검", "req-stop");
        n.drain();

        assertEquals("SKIPPED", state("STOPPED"));
        assertTrue(reporting.requests.isEmpty());
        MspReporting read = new MspReporting(null, rig.cache, rig.jdbc, rig.props.getReporting());
        assertEquals(Map.of("state", "NOT_CONNECTED", "source", "reporting"), read.reports(10));
        Map<String, Object> status = read.status();
        assertEquals("NOT_CONNECTED", status.get("state"));
        @SuppressWarnings("unchecked")
        Map<String, Object> outbox = (Map<String, Object>) ((Map<String, Object>) status.get("data")).get("outbox");
        assertEquals(Map.of("SKIPPED", 1L), outbox.get("counts"));
        assertEquals("NOT_CONNECTED", read.teamsSource().get("state"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 보고_목록과_전송_상태는_허용한_필드만_30초_캐시로_읽는다() throws Exception {
        MspReporting read = new MspReporting(new ReportingClient(settings(), rig.mapper), rig.cache, rig.jdbc,
                rig.props.getReporting());

        Map<String, Object> list = read.reports(1);
        read.reports(10);
        assertEquals("READY", list.get("state"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.get("data");
        assertEquals(1, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals("msp:1", first.get("id"));
        assertEquals("P3", first.get("severity"));
        assertEquals("2026-09-25T00:00:00.000Z", first.get("created_at"));
        assertNull(first.get("sent_at"));
        assertFalse(first.containsKey("customer"), "허용하지 않은 필드는 싣지 않는다");
        Map<String, Object> links = (Map<String, Object>) first.get("links");
        assertEquals("https://reports.example.test/operations/reports/" + "a".repeat(64) + "/1790000000/" + "b".repeat(64)
                + "/report.pdf", links.get("pdf"));
        assertNull(links.get("html"), "모양이 다른 주소는 싣지 않는다");
        assertEquals(1, reporting.requests("/console/reports").size(), "목록은 한 벌만 캐시해 limit 으로 자른다");
        assertTrue(reporting.requests("/console/reports").get(0).query().contains("environment=aws"));

        Map<String, Object> status = read.status();
        read.teamsSource();
        assertEquals("READY", status.get("state"));
        Map<String, Object> rep = (Map<String, Object>) ((Map<String, Object>) status.get("data")).get("reporting");
        assertEquals(true, rep.get("delivery_enabled"));
        assertEquals(3L, ((Map<String, Object>) rep.get("counts")).get("sent"));
        assertEquals("http_error_429", ((Map<String, Object>) rep.get("last_attempt")).get("error"));
        Map<String, Object> teams = read.teamsSource();
        assertEquals("READY", teams.get("state"));
        assertEquals(2L, teams.get("pending"), "보내지 않은 작업은 pending·processing·queued·sending 의 합이다");
        assertEquals(1, reporting.requests("/console/status").size(), "전송 상태도 30초 캐시다");

        rig.clock.advance(Duration.ofSeconds(31));
        reporting.handler = r -> StubSource.Response.status(503, "{}");
        assertEquals(Map.of("state", "UNAVAILABLE", "reason", "http_status"), read.reports(5));
        assertEquals("UNAVAILABLE", read.teamsSource().get("state"));
    }

    private JsonNode tree(String body) {
        try {
            return rig.mapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String REPORTS = "{\"state\":\"ok\",\"reports\":[{\"id\":\"msp:1\",\"kind\":\"msp\","
            + "\"created\":1790294400,\"sent\":null,\"status\":\"pending\",\"title\":\"AI 자동 조치 실행\",\"severity\":\"P3\","
            + "\"summary\":\"조회 노드 2→3\",\"alertState\":null,\"targets\":[\"app\"],\"customer\":\"exchange\","
            + "\"environment\":\"aws\",\"links\":{\"pdf\":\"/reports/" + "a".repeat(64) + "/1790000000/" + "b".repeat(64)
            + "/report.pdf\",\"html\":\"https://evil.test/x\"}},{\"id\":\"daily:2026-09-24\",\"kind\":\"daily\","
            + "\"created\":1790200000,\"sent\":1790200041,\"status\":\"sent\",\"title\":\"일일 운영 보고\","
            + "\"severity\":\"P4\",\"summary\":\"관찰 유지\",\"alertState\":null,\"targets\":[],\"links\":{}}]}";

    private static final String STATUS = "{\"state\":\"ok\",\"delivery_enabled\":true,\"counts\":{\"pending\":1,"
            + "\"processing\":0,\"queued\":1,\"sending\":0,\"sent\":3,\"dead\":0},\"last_sent_at\":1790200041.5,"
            + "\"last_attempt\":{\"at\":1790200050,\"status\":\"queued\",\"error\":\"http_error_429\"},"
            + "\"oldest_pending_age_seconds\":42.4,\"last_daily_sent_at\":1790200041.5}";
}

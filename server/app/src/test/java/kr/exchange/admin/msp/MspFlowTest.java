package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.admin.BotRateControl;
import kr.exchange.admin.CapacityPlanService;
import kr.exchange.sim.OpsGovernor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서비스 흐름 실측(API 설계 §6-F '흐름'). 150초 창 안에서 주 DB 가 멈춰 예비 DB 가 승격되고, 조회 부하 뒤 app-3 가 켜지는
 * 원천을 스텁 Prometheus 로 흉내 낸다. MSP_SNAPSHOT_DIR 이 있으면 응답을 웹 어댑터 고정 자료(flow.json)로 남긴다.
 */
class MspFlowTest {

    // 격자 끝. 2초의 배수다
    private static final long END = 1_790_000_000L;
    private static final long START = END - 150;
    private static final int POINTS = 76;
    // 주 DB 중지, 리더 리스 상실, 예비 DB 승격, 새 리더와 거래 재개, 조회 부하, 희망 3대, app-3 대상 등록, app-3 가동의 격자 번호
    private static final int DB_DOWN = 30;
    private static final int LEASE_LOST = 33;
    private static final int PROMOTED = 38;
    private static final int RESUMED = 40;
    private static final int LOAD = 45;
    private static final int SCALE = 48;
    private static final int APP3_TARGET = 50;
    private static final int APP3_UP = 55;

    private StubSource prom;
    private ExecutorService pool;
    private TestClock clock;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void start() throws Exception {
        prom = new StubSource();
        pool = Executors.newFixedThreadPool(MspWorkers.QUERY_THREADS);
        clock = new TestClock(Instant.ofEpochSecond(END).plusMillis(1500));
        registry = new SimpleMeterRegistry();
        prom.handler = MspFlowTest::answer;
    }

    @AfterEach
    void stop() {
        prom.close();
        pool.shutdownNow();
    }

    @Test
    void 앱_노드는_node_라벨_순이고_대상이_내려간_점의_지표는_싣지_않는다() {
        List<Map<String, Object>> apps = list(data(view()), "apps");

        assertEquals(List.of("app-1", "app-2", "app-3"), apps.stream().map(a -> a.get("node")).toList());
        assertEquals(List.of("app", "app", "reader"), apps.stream().map(a -> a.get("role")).toList());
        Map<String, Object> app3 = apps.get(2);
        List<?> up = (List<?>) app3.get("up");
        assertEquals(POINTS, up.size(), "150초를 2초 격자로 읽어 양 끝을 포함한 76점이다");
        assertNull(up.get(APP3_TARGET - 1), "수집 대상이 없던 점은 null 이다");
        assertEquals(Boolean.FALSE, up.get(APP3_TARGET));
        assertEquals(Boolean.TRUE, up.get(APP3_UP));
        List<?> cpu = (List<?>) app3.get("cpu_ratio");
        assertNull(cpu.get(APP3_TARGET), "up 이 0 인 점은 원천에 값이 있어도 싣지 않는다");
        assertEquals("0.3", cpu.get(APP3_UP));
        assertEquals("100", ((List<?>) app3.get("read_rate")).get(APP3_UP + 1));
        assertEquals("0", ((List<?>) app3.get("write_rate")).get(APP3_UP + 1), "떠 있는 노드의 무요청은 0 이다");
    }

    @Test
    void 조회_요청은_노드별과_전체를_수용과_요청_제한_거절로_나눠_싣는다() {
        Map<String, Object> data = data(view());
        List<Map<String, Object>> apps = list(data, "apps");
        List<?> rejected1 = (List<?>) apps.get(0).get("read_rejected_rate");
        List<?> rejected3 = (List<?>) apps.get(2).get("read_rejected_rate");

        assertEquals("150", ((List<?>) apps.get(0).get("read_rate")).get(LOAD), "수용은 429 가 아닌 응답이다");
        assertEquals("0", rejected1.get(LOAD - 1));
        assertEquals("60", rejected1.get(LOAD), "두 대에 몰린 동안의 거절");
        assertEquals("10", rejected1.get(APP3_UP), "app-3 이 합류하면 줄어든다");
        assertNull(rejected3.get(APP3_UP - 1), "뜨기 전 app-3 는 싣지 않는다");
        assertEquals("5", rejected3.get(APP3_UP));

        List<?> reads = (List<?>) data.get("read_rate");
        List<?> rejected = (List<?>) data.get("read_rejected_rate");
        assertEquals(POINTS, reads.size());
        assertEquals("1", reads.get(0), "전체는 떠 있는 노드의 합이다(0.5 + 0.5)");
        assertEquals("300", reads.get(LOAD));
        assertEquals("400", reads.get(APP3_UP));
        assertEquals("120", rejected.get(LOAD));
        assertEquals("25", rejected.get(APP3_UP), "10 + 10 + 5");
    }

    @Test
    void 리더와_쓰기_가능_상태는_instance로_이어_노드에_붙인다() {
        List<Map<String, Object>> apps = list(data(view()), "apps");
        List<?> leader1 = (List<?>) apps.get(0).get("leader");
        List<?> leader2 = (List<?>) apps.get(1).get("leader");
        List<?> write1 = (List<?>) apps.get(0).get("write_state");

        assertEquals(Boolean.TRUE, leader1.get(LEASE_LOST - 1));
        assertEquals(Boolean.FALSE, leader1.get(LEASE_LOST));
        assertEquals(Boolean.FALSE, leader2.get(RESUMED - 1));
        assertEquals(Boolean.TRUE, leader2.get(RESUMED));
        assertEquals(20L, ((List<?>) apps.get(1).get("epoch")).get(RESUMED));
        assertEquals("ACTIVE", write1.get(DB_DOWN - 1));
        assertEquals("UNREACHABLE", write1.get(DB_DOWN));
        assertEquals("ACTIVE", write1.get(RESUMED));
        assertEquals("25", ((List<?>) apps.get(0).get("write_rate")).get(0));
    }

    @Test
    void DB_역할은_recovery_지표로_정하고_멈춘_DB의_역할은_비운다() {
        List<Map<String, Object>> dbs = list(data(view()), "dbs");
        Map<String, Object> a = dbs.get(0);
        Map<String, Object> b = dbs.get(1);

        assertEquals(List.of("db-a", "db-b"), dbs.stream().map(d -> d.get("node")).toList());
        assertEquals(Boolean.TRUE, ((List<?>) a.get("reachable")).get(DB_DOWN), "수집기는 살아 있다");
        assertEquals(Boolean.FALSE, ((List<?>) a.get("up")).get(DB_DOWN));
        assertEquals(Boolean.FALSE, ((List<?>) a.get("in_recovery")).get(DB_DOWN - 1));
        assertNull(((List<?>) a.get("in_recovery")).get(DB_DOWN));
        assertEquals(Boolean.TRUE, ((List<?>) b.get("in_recovery")).get(PROMOTED - 1));
        assertEquals(Boolean.FALSE, ((List<?>) b.get("in_recovery")).get(PROMOTED));
        assertEquals(46L, ((List<?>) b.get("connections")).get(RESUMED));
    }

    @Test
    void 합친_값과_진입_검사와_실패한_질의를_싣는다() {
        Map<String, Object> data = data(view());
        List<?> trades = (List<?>) data.get("trades_per_second");

        assertEquals("8.1", trades.get(DB_DOWN - 1));
        assertEquals("0", trades.get(DB_DOWN + 1), "떠 있는 앱 노드가 있고 체결이 없으면 0 이다");
        assertEquals("25", ((List<?>) data.get("order_rate")).get(DB_DOWN + 1));
        assertEquals("808", ((List<?>) data.get("replication_lag_bytes")).get(0));
        List<?> mode = (List<?>) data.get("replication_mode");
        assertEquals("SYNC", mode.get(DB_DOWN - 1));
        assertNull(mode.get(DB_DOWN), "승격 뒤 복제 상대가 없으면 복제 방식을 모른다");
        assertEquals("AUTO", ((List<?>) data.get("promotion")).get(POINTS - 1));
        assertNull(((List<?>) data.get("replication_lag_bytes")).get(DB_DOWN));
        assertEquals(List.of("alb", "cloudfront", "edge"), list(data, "probes").stream().map(p -> p.get("service")).toList());
        assertEquals(List.of("app_lease_age"), data.get("failed"));
        List<Map<String, Object>> apps = list(data, "apps");
        assertNull(apps.get(0).get("lease_age_seconds"), "실패한 질의의 배열은 통째로 null 이다");
    }

    @Test
    void 같은_격자_안의_요청은_range_질의_한_벌을_함께_쓴다() {
        MspFlow flow = flow(props(MspProperties.ReadCapacityMode.NONE), null, null);
        flow.view();
        clock.advance(Duration.ofMillis(300));
        flow.view();
        assertEquals(FlowQuery.values().length, prom.requests("/api/v1/query_range").size());
        StubSource.Request sent = prom.requests("/api/v1/query_range").get(0);
        assertEquals(String.valueOf(START), sent.param("start"));
        assertEquals(String.valueOf(END), sent.param("end"));
        assertEquals("2", sent.param("step"));
        clock.advance(Duration.ofSeconds(2));
        flow.view();
        assertEquals(FlowQuery.values().length * 2, prom.requests("/api/v1/query_range").size(), "다음 격자는 새로 읽는다");
    }

    @Test
    void DB_칸의_읽기가_실패해도_흐름_조회는_나오고_그_칸만_unavailable_이다() {
        int[] planCalls = {0};
        CapacityPlanService broken = new CapacityPlanService(null, null, null, null, null) {
            @Override
            public Plan plan() {
                planCalls[0]++;
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException("주 DB 전환 중");
            }

            @Override
            public List<Shift> shiftsSince(Instant since) {
                return List.of();
            }
        };
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        BotRateControl brokenBots = new BotRateControl(beans.getBeanProvider(OpsGovernor.class), null, null, clock) {
            @Override
            public Current current() {
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException("주 DB 전환 중");
            }
        };
        MspFlow flow = flow(props(MspProperties.ReadCapacityMode.CAPACITY_PLAN), broken, brokenBots);

        Map<String, Object> out = flow.view();
        Map<String, Object> reason = Map.of("state", "UNAVAILABLE", "reason", DbGuard.REASON);
        assertEquals(reason, out.get("read_tier"));
        assertEquals(reason, out.get("bots"));
        Map<String, Object> data = data(out);
        assertEquals(List.of("db-a", "db-b"), list(data, "dbs").stream().map(d -> d.get("node")).toList(),
                "Prometheus 원천의 DB 노드 칸은 그대로다");
        assertEquals(3, list(data, "apps").size());
        assertEquals(1, planCalls[0]);

        clock.advance(Duration.ofSeconds(2));
        assertEquals(reason, flow.view().get("read_tier"));
        assertEquals(1, planCalls[0], "실패 직후에는 DB 를 다시 부르지 않아 요청이 3초씩 막히지 않는다");
        clock.advance(MspFlow.DB_COOLDOWN);
        flow.view();
        assertEquals(2, planCalls[0], "잠깐 뒤에는 다시 읽어 본다");
    }

    @Test
    void 원천이_없거나_죽으면_칸_상태로_알린다() {
        MspFlow none = new MspFlow(props(MspProperties.ReadCapacityMode.NONE), null, null, null,
                new SourceCache(clock, registry));
        Map<String, Object> out = none.view();
        assertEquals(Map.of("state", "NOT_CONNECTED", "source", "prometheus"), out.get("flow"));
        assertEquals(Map.of("state", "NOT_CONNECTED", "source", "read_capacity"), out.get("read_tier"));
        assertEquals(Map.of("state", "NOT_CONNECTED", "source", "bot_fleet"), out.get("bots"));

        prom.close();
        Map<String, Object> down = flow(props(MspProperties.ReadCapacityMode.NONE), null, null).view();
        assertEquals(Map.of("state", "UNAVAILABLE", "reason", "unreachable"), down.get("flow"));
    }

    @Test
    void 희망_대수는_감사_기록의_전후_대수로_격자를_채운다() {
        List<Instant> grid = List.of(Instant.ofEpochSecond(10), Instant.ofEpochSecond(20), Instant.ofEpochSecond(30),
                Instant.ofEpochSecond(40));
        List<CapacityPlanService.Shift> shifts = List.of(new CapacityPlanService.Shift(Instant.ofEpochSecond(15), 2, 3),
                new CapacityPlanService.Shift(Instant.ofEpochSecond(30), 3, 2));

        assertEquals(List.of(2, 3, 2, 2), MspFlow.desiredGrid(shifts, 2, grid), "변경 시각의 점은 변경 뒤 대수다");
        assertEquals(List.of(3, 3, 3, 3), MspFlow.desiredGrid(List.of(), 3, grid), "창 안에 변경이 없으면 지금 대수다");
        assertEquals(List.of(2, 2, 2, 2),
                MspFlow.desiredGrid(List.of(new CapacityPlanService.Shift(Instant.ofEpochSecond(50), 2, 3)), 3, grid),
                "창 끝 뒤의 변경은 그 전 대수로 채운다");
    }

    @Test
    void 조회_계층과_봇_목표를_싣는다() throws IOException {
        Map<String, Object> out = view();

        Map<String, Object> tier = data(out, "read_tier");
        assertEquals("CAPACITY_PLAN", tier.get("mode"));
        assertEquals(2, tier.get("min"));
        assertEquals(3, tier.get("max"));
        List<?> desired = (List<?>) tier.get("desired");
        assertEquals(2, desired.get(SCALE - 1));
        assertEquals(3, desired.get(SCALE));
        Map<String, Object> bots = data(out, "bots");
        assertEquals("local", bots.get("source"));
        assertEquals("25", bots.get("target_ops"));
        assertTrue(((String) out.get("generated_at")).endsWith("Z"));
        assertEquals(Map.of("start", time(START), "end", time(END), "step_seconds", 2L), out.get("window"));

        String dir = System.getenv("MSP_SNAPSHOT_DIR");
        if (dir != null && !dir.isBlank()) {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("status", 200);
            file.put("body", out);
            Files.writeString(Path.of(dir).resolve("flow.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(file) + "\n", StandardCharsets.UTF_8);
        }
    }

    /* ===== 장치 ===== */

    private Map<String, Object> view() {
        return flow(props(MspProperties.ReadCapacityMode.CAPACITY_PLAN), new PlanStub(), bots()).view();
    }

    private MspFlow flow(MspProperties props, CapacityPlanService capacity, BotRateControl bots) {
        SourceCache cache = new SourceCache(clock, registry);
        PrometheusClient client = new PrometheusClient(prom.uri(), props.getPrometheus(), Duration.ofSeconds(2),
                Duration.ofSeconds(10), new ObjectMapper(), pool, cache);
        return new MspFlow(props, client, capacity, bots, cache);
    }

    private static MspProperties props(MspProperties.ReadCapacityMode mode) {
        MspProperties props = new MspProperties();
        props.setEnvironment(MspProperties.Environment.LOCAL_DOCKER);
        props.getReadCapacity().setMode(mode);
        return props;
    }

    private BotRateControl bots() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("opsGovernor", new OpsGovernor(25));
        return new BotRateControl(beans.getBeanProvider(OpsGovernor.class), null, null, clock);
    }

    /** 앱 계층 계획은 최소 2·최대 3이고, 조회 부하 뒤 운영자가 승인해 희망 대수가 3이 됐다 */
    private static final class PlanStub extends CapacityPlanService {

        PlanStub() {
            super(null, null, null, null, null);
        }

        @Override
        public Plan plan() {
            return new Plan(2, 3, 3, "op-01", OffsetDateTime.ofInstant(at(SCALE), ZoneOffset.UTC));
        }

        @Override
        public List<Shift> shiftsSince(Instant since) {
            return List.of(new Shift(at(SCALE).minusMillis(500), 2, 3));
        }
    }

    private static Instant at(int point) {
        return Instant.ofEpochSecond(START + point * 2L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> view) {
        return data(view, "flow");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> view, String section) {
        Map<String, Object> s = (Map<String, Object>) view.get(section);
        assertEquals("READY", s.get("state"), section + " " + s);
        return (Map<String, Object>) s.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    private static String time(long epochSeconds) {
        return kr.exchange.api.common.ApiFormats.time(Instant.ofEpochSecond(epochSeconds));
    }

    /* ===== 스텁 Prometheus ===== */

    private static final String APP1 = "{\"node\":\"app-1\",\"role\":\"app\",\"instance\":\"chaos-api-1:8080\",\"job\":\"exchange-api\"}";
    private static final String APP2 = "{\"node\":\"app-2\",\"role\":\"app\",\"instance\":\"chaos-api-2:8080\",\"job\":\"exchange-api\"}";
    private static final String APP3 = "{\"node\":\"app-3\",\"role\":\"reader\",\"instance\":\"chaos-api-3:8080\",\"job\":\"exchange-api\"}";

    /** 질의 문자열로 명명 질의를 가려 격자마다의 값을 돌려준다. 값 함수가 null 을 주면 그 점은 표본이 없다 */
    private static StubSource.Response answer(StubSource.Request r) {
        if (!"/api/v1/query_range".equals(r.path())) {
            return StubSource.Response.status(404, "{}");
        }
        String q = r.param("query");
        if (q.contains("exchange_leader_lease_last_renew_age_seconds")) {
            return StubSource.Response.status(422, "{\"status\":\"error\",\"errorType\":\"execution\",\"error\":\"x\"}");
        }
        if (q.startsWith("up{")) {
            return matrix(series(APP1, i -> 1.0), series(APP2, i -> 1.0),
                    series(APP3, i -> i < APP3_TARGET ? null : i < APP3_UP ? 0.0 : 1.0));
        }
        if (q.contains("process_cpu_usage")) {
            return matrix(series(inst(1), i -> i < LOAD ? 0.2 : 0.6), series(inst(2), i -> i < LOAD ? 0.1 : 0.55),
                    series(inst(3), i -> 0.3));
        }
        if (q.contains("http_server_requests_seconds_count") && q.startsWith("sum by(instance)")) {
            boolean reads = q.contains("uri=~\"" + FlowQuery.READ_PATHS);
            // 요청 제한 거절(429). 조회 부하가 두 대에 몰린 동안 노드마다 60건, app-3 이 합류한 뒤 10건과 5건이다
            if (reads && q.contains("status=\"429\"")) {
                return matrix(series(inst(1), i -> i < LOAD ? 0.0 : i < APP3_UP ? 60.0 : 10.0),
                        series(inst(2), i -> i < LOAD ? 0.0 : i < APP3_UP ? 60.0 : 10.0),
                        series(inst(3), i -> i < APP3_UP ? null : 5.0));
            }
            if (reads) {
                return matrix(series(inst(1), i -> i < LOAD ? 0.5 : 150.0), series(inst(2), i -> i < LOAD ? 0.5 : 150.0),
                        series(inst(3), i -> i < APP3_UP ? null : 100.0));
            }
            return matrix(series(inst(1), i -> i < LEASE_LOST ? 25.0 : 0.0),
                    series(inst(2), i -> i < RESUMED ? 0.0 : 25.0), series(inst(3), i -> i < APP3_UP ? null : 0.0));
        }
        if (q.contains("exchange_leader_is_leader")) {
            return matrix(series(inst(1), i -> i < LEASE_LOST ? 1.0 : 0.0), series(inst(2), i -> i < RESUMED ? 0.0 : 1.0),
                    series(inst(3), i -> i < APP3_UP ? null : 0.0));
        }
        if (q.contains("exchange_leader_epoch")) {
            return matrix(series(inst(1), i -> 19.0), series(inst(2), i -> i < RESUMED ? 0.0 : 20.0));
        }
        if (q.contains("exchange_write_availability_state")) {
            IntFunction<Double> ok = i -> i < DB_DOWN || i >= RESUMED ? 1.0 : null;
            IntFunction<Double> cut = i -> i >= DB_DOWN && i < RESUMED ? 1.0 : null;
            return matrix(series(stated(1, "ACTIVE"), ok), series(stated(1, "UNREACHABLE"), cut),
                    series(stated(2, "ACTIVE"), ok), series(stated(2, "UNREACHABLE"), cut));
        }
        if (q.contains("exchange_trades_total")) {
            return matrix(series("{}", i -> i >= DB_DOWN && i < RESUMED ? 0.0 : 8.125));
        }
        if (q.contains("/api/v1/orders")) {
            return matrix(series("{}", i -> 25.0));
        }
        if (q.startsWith("max by(node) (up{")) {
            return matrix(series(node("db-a"), i -> 1.0), series(node("db-b"), i -> 1.0));
        }
        if (q.contains("pg_up")) {
            return matrix(series(node("db-a"), i -> i < DB_DOWN ? 1.0 : 0.0), series(node("db-b"), i -> 1.0));
        }
        if (q.contains("pg_wal_position_in_recovery")) {
            return matrix(series(node("db-a"), i -> i < DB_DOWN ? 0.0 : null),
                    series(node("db-b"), i -> i < PROMOTED ? 1.0 : 0.0));
        }
        if (q.contains("pg_stat_database_numbackends")) {
            return matrix(series(node("db-a"), i -> i < DB_DOWN ? 44.0 : null),
                    series(node("db-b"), i -> i < PROMOTED ? 2.0 : 46.0));
        }
        if (q.contains("pg_stat_database_xact_commit")) {
            return matrix(series(node("db-a"), i -> i < DB_DOWN ? 400.0 : null),
                    series(node("db-b"), i -> i < PROMOTED ? 8.0 : 380.0));
        }
        // 복제 방식: 주 DB 가 멈추기 전까지 동기 상대가 있고, 승격 뒤에는 복제 상대가 없다. 승격 방식: Patroni 가 늘 지표를 낸다
        if (q.contains("sync_state")) {
            return matrix(series("{}", i -> i < DB_DOWN ? 2.0 : null));
        }
        if (q.contains("patroni_primary")) {
            return matrix(series("{}", i -> 1.0));
        }
        if (q.contains("pg_replication_client_sent_replay_bytes")) {
            return matrix(series("{}", i -> i < DB_DOWN ? 808.0 : null));
        }
        if (q.contains("probe_success")) {
            return matrix(series("{\"service\":\"edge\"}", i -> 1.0), series("{\"service\":\"cloudfront\"}", i -> 1.0),
                    series("{\"service\":\"alb\"}", i -> 1.0));
        }
        return matrix();
    }

    private static String inst(int n) {
        return "{\"instance\":\"chaos-api-" + n + ":8080\"}";
    }

    private static String stated(int n, String state) {
        return "{\"instance\":\"chaos-api-" + n + ":8080\",\"state\":\"" + state + "\"}";
    }

    private static String node(String node) {
        return "{\"node\":\"" + node + "\"}";
    }

    private static String series(String metric, IntFunction<Double> value) {
        List<String> points = new ArrayList<>();
        for (int i = 0; i < POINTS; i++) {
            Double v = value.apply(i);
            if (v != null) {
                points.add("[" + (START + i * 2L) + ",\"" + v + "\"]");
            }
        }
        return "{\"metric\":" + metric + ",\"values\":[" + String.join(",", points) + "]}";
    }

    private static StubSource.Response matrix(String... series) {
        return StubSource.Response.json("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + String.join(",", Arrays.asList(series)) + "]}}");
    }
}

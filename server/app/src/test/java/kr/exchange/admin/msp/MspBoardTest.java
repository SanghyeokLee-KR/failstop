package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 호스트·복제 칸의 측정 객체(API 설계 §6-F). 값의 수집 시각은 Prometheus 평가 시각이 아니라 그 대상의
 * 마지막 수집 시각이고, 여러 대상을 합친 값은 가장 오래된 대상의 시각을 쓴다.
 */
class MspBoardTest {

    // 스텁 Prometheus 가 질의를 평가한 시각
    private static final long EVAL = 1_790_000_000L;

    private StubSource prom;
    private ExecutorService pool;
    private TestClock clock;

    @BeforeEach
    void start() throws Exception {
        prom = new StubSource();
        pool = Executors.newFixedThreadPool(MspWorkers.QUERY_THREADS);
        clock = new TestClock(Instant.ofEpochSecond(EVAL).plusSeconds(5));
        prom.handler = r -> StubSource.Response.json(answer(r.param("query")));
    }

    @AfterEach
    void stop() {
        prom.close();
        pool.shutdownNow();
    }

    @Test
    void 값의_수집_시각은_평가_시각이_아니라_그_대상의_마지막_수집_시각이다() {
        Map<String, Object> db = host(hostsView(), "db-primary");

        Map<String, Object> cpu = measurement(db, "cpu_ratio");
        assertEquals("0.08", cpu.get("value"));
        assertEquals(time(EVAL - 10), cpu.get("sampled_at"), "평가 시각에서 수집 나이 10초를 뺀 시각이다");
        assertEquals(15L, cpu.get("age_seconds"));
        assertEquals("OK", cpu.get("collect"));
        assertEquals("1", measurement(db, "up").get("value"));
        assertEquals("PRIMARY", db.get("actual_role"));
    }

    @Test
    void 오래된_표본은_STALE_이고_결과_없음과_NaN은_MISSING_이다() {
        Map<String, Object> hosts = hostsView();

        Map<String, Object> botCpu = measurement(host(hosts, "bot-1"), "cpu_ratio");
        assertEquals("0.5", botCpu.get("value"));
        assertEquals("STALE", botCpu.get("collect"), "마지막 수집이 95초 전이다");
        Map<String, Object> memory = measurement(host(hosts, "db-primary"), "memory_ratio");
        assertEquals("MISSING", memory.get("collect"));
        assertNull(memory.get("value"), "NaN 을 0 이나 문자열로 옮기지 않는다");
        assertEquals("MISSING", measurement(host(hosts, "db-primary"), "root_free_ratio").get("collect"));
        assertEquals("MISSING", measurement(host(hosts, "db-standby"), "up").get("collect"));
        assertEquals("MISSING", measurement(host(hosts, "db-primary"), "cpu_credits").get("collect"));
    }

    @Test
    void 복제_칸은_recovery_지표로_역할을_정하고_합친_값은_가장_오래된_대상의_시각을_쓴다() {
        @SuppressWarnings("unchecked")
        Map<String, Object> replication = (Map<String, Object>) ((Map<String, Object>) hostsView().get("replication")).get("data");

        assertEquals("db-primary", replication.get("primary"));
        assertEquals("db-standby", replication.get("standby"));
        assertEquals("STREAMING", replication.get("stream"));
        assertEquals(Boolean.TRUE, replication.get("label_mismatch"), "role=data 인데 recovery 가 1인 노드가 있다");
        Map<String, Object> lag = measurement(replication, "lag_seconds");
        assertEquals("0.25", lag.get("value"));
        assertEquals(time(EVAL - 20), lag.get("sampled_at"), "postgres 대상 둘 가운데 오래된 쪽이다");
        assertEquals("1024", measurement(replication, "lag_bytes").get("value"));
        assertEquals("SYNC", replication.get("mode"), "Primary 가 보는 sync_state 가 동기다");
        assertEquals("AUTO", replication.get("promotion"), "Patroni 지표가 있다");
    }

    @Test
    void 복제_방식과_승격_방식은_수집값만_계약_이름으로_옮기고_모르면_null_이다() {
        assertEquals("SYNC", MspBoard.replicationMode(2.0));
        assertEquals("ASYNC", MspBoard.replicationMode(1.0));
        assertNull(MspBoard.replicationMode(null), "복제 상대가 없으면 모른다");
        assertEquals("AUTO", MspBoard.promotion(1.0));
        assertEquals("MANUAL", MspBoard.promotion(2.0), "Patroni 일시 정지면 자동 승격이 멈췄다");
        assertNull(MspBoard.promotion(null), "Patroni 지표가 없다고 수동으로 단정하지 않는다");

        MspProperties props = props();
        props.getPrometheus().setPatroniJob("");
        @SuppressWarnings("unchecked")
        Map<String, Object> replication = (Map<String, Object>) ((Map<String, Object>) board(props).hostsView()
                .get("replication")).get("data");
        assertTrue(replication.containsKey("promotion"));
        assertNull(replication.get("promotion"), "Patroni job 을 비우면 질의하지 않고 미확인이다");
        assertEquals("SYNC", replication.get("mode"));
    }

    @Test
    void 용량_계획을_DB_에서_읽지_못해도_호스트_판은_나오고_조회_계층만_unavailable_이다() {
        MspProperties props = props();
        props.getReadCapacity().setMode(MspProperties.ReadCapacityMode.CAPACITY_PLAN);
        int[] calls = {0};
        kr.exchange.admin.CapacityPlanService broken = new kr.exchange.admin.CapacityPlanService(null, null, null, null, null) {
            @Override
            public Plan plan() {
                calls[0]++;
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException("주 DB 전환 중");
            }
        };
        PrometheusClient client = new PrometheusClient(prom.uri(), props.getPrometheus(), Duration.ofSeconds(2),
                Duration.ofSeconds(10), new ObjectMapper(), pool, new SourceCache(clock, new SimpleMeterRegistry()));
        MspBoard board = new MspBoard(props, client, null, null, null, broken, clock, null, null, null, null, null, null, null);

        Map<String, Object> view = board.hostsView();
        assertEquals(Map.of("state", "UNAVAILABLE", "reason", DbGuard.REASON), view.get("read_tier"));
        @SuppressWarnings("unchecked")
        Map<String, Object> replication = (Map<String, Object>) view.get("replication");
        assertEquals("READY", replication.get("state"), "Prometheus 원천의 복제 칸은 그대로다");
        board.hostsView();
        assertEquals(1, calls[0], "실패 직후에는 DB 를 다시 부르지 않는다");
        assertTrue(DbGuard.database(new RuntimeException(new java.sql.SQLException("x"))));
        assertTrue(!DbGuard.database(new IllegalStateException("코드 결함")), "DB 가 아닌 예외는 가리지 않는다");
    }

    @Test
    void 조회_계층이_없거나_ASG_가_대수를_정하면_그렇게_적는다() {
        MspProperties props = props();
        assertEquals(Map.of("state", "NOT_CONNECTED", "source", "read_capacity"),
                board(props).hostsView().get("read_tier"));

        props.getReadCapacity().setMode(MspProperties.ReadCapacityMode.ASG_POLICY);
        @SuppressWarnings("unchecked")
        Map<String, Object> asg = (Map<String, Object>) board(props).hostsView().get("read_tier");
        assertEquals(Map.of("mode", "ASG_POLICY", "scaling_owner", "ASG", "scaling_basis", "request_count_per_target"),
                asg.get("data"));
    }

    @Test
    void Prometheus_가_죽으면_칸은_UNAVAILABLE_이고_설정이_없으면_NOT_CONNECTED_다() {
        prom.close();
        Map<String, Object> down = board(props()).hostsView();
        assertEquals(Map.of("state", "UNAVAILABLE", "reason", "unreachable"), down.get("hosts"));

        Map<String, Object> none = new MspBoard(props(), null, null, null, null, null, clock, null, null, null, null, null, null, null).hostsView();
        assertEquals(Map.of("state", "NOT_CONNECTED", "source", "prometheus"), none.get("hosts"));
    }

    @Test
    void 임계값_항목의_사용_여부는_환경과_조회_계층_모드로_정한다() {
        MspProperties local = props();
        local.setEnvironment(MspProperties.Environment.LOCAL_DOCKER);
        local.getReadCapacity().setMode(MspProperties.ReadCapacityMode.CAPACITY_PLAN);
        MspBoard localBoard = new MspBoard(local, null, null, null, null, null, clock, null, null, null, null, null, null, null);
        assertEquals(List.of(true, true, true, false, false, false),
                List.of(localBoard.inUse("read.scale_out_cpu"), localBoard.inUse("read.scale_in_cpu"),
                        localBoard.inUse("db.replication_lag_alert"), localBoard.inUse("host.disk_free_alert"),
                        localBoard.inUse("backup.delay_alert"), localBoard.inUse("host.cpu_alert")));

        // AWS 의 조회 대수는 ASG 가 요청 수로 정하므로 CPU 권고 임계를 쓰는 곳이 없다.
        MspProperties aws = props();
        aws.setEnvironment(MspProperties.Environment.PERSONAL_AWS);
        aws.getReadCapacity().setMode(MspProperties.ReadCapacityMode.ASG_POLICY);
        MspBoard awsBoard = new MspBoard(aws, null, null, null, null, null, clock, null, null, null, null, null, null, null);
        assertEquals(List.of(false, false, false, false),
                List.of(awsBoard.inUse("read.scale_out_cpu"), awsBoard.inUse("db.replication_lag_alert"),
                        awsBoard.inUse("host.disk_free_alert"), awsBoard.inUse("bot.capacity_guard")));
    }

    private Map<String, Object> hostsView() {
        return board(props()).hostsView();
    }

    private MspBoard board(MspProperties props) {
        PrometheusClient client = new PrometheusClient(prom.uri(), props.getPrometheus(), Duration.ofSeconds(2),
                Duration.ofSeconds(10), new ObjectMapper(), pool, new SourceCache(clock, new SimpleMeterRegistry()));
        return new MspBoard(props, client, null, null, null, null, clock, null, null, null, null, null, null, null);
    }

    private static MspProperties props() {
        MspProperties props = new MspProperties();
        props.getHosts().add(host("db-primary", MspProperties.HostRole.DB));
        props.getHosts().add(host("db-standby", MspProperties.HostRole.DB));
        props.getHosts().add(host("bot-1", MspProperties.HostRole.BOT_OBS));
        return props;
    }

    private static MspProperties.Host host(String node, MspProperties.HostRole role) {
        MspProperties.Host h = new MspProperties.Host();
        h.setNode(node);
        h.setRole(role);
        return h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> host(Map<String, Object> view, String node) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) view.get("hosts")).get("data");
        return rows.stream().filter(r -> node.equals(r.get("node"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> measurement(Map<String, Object> row, String name) {
        return (Map<String, Object>) row.get(name);
    }

    private static String time(long epochSeconds) {
        return kr.exchange.api.common.ApiFormats.time(Instant.ofEpochSecond(epochSeconds));
    }

    /** 질의 문자열로 명명 질의를 가려 정해 둔 결과를 돌려준다. */
    private static String answer(String query) {
        if (query.startsWith("time() - timestamp(up")) {
            return vector(
                    series("{\"job\":\"linux-host\",\"node\":\"db-primary\",\"instance\":\"10.0.0.1:9100\"}", "10"),
                    series("{\"job\":\"linux\",\"node\":\"bot-1\",\"instance\":\"127.0.0.1:9100\"}", "90"),
                    series("{\"job\":\"postgres\",\"node\":\"db-primary\"}", "5"),
                    series("{\"job\":\"postgres\",\"node\":\"db-standby\"}", "20"));
        }
        if (query.startsWith("up{")) {
            return vector(series("{\"job\":\"linux-host\",\"node\":\"db-primary\"}", "1"),
                    series("{\"job\":\"linux\",\"node\":\"bot-1\"}", "1"));
        }
        if (query.contains("node_cpu_seconds_total")) {
            return vector(series("{\"node\":\"db-primary\"}", "0.08"), series("{\"node\":\"bot-1\"}", "0.5"));
        }
        if (query.contains("node_memory_MemAvailable_bytes")) {
            return vector(series("{\"node\":\"db-primary\"}", "NaN"));
        }
        if (query.contains("pg_wal_position_in_recovery")) {
            return vector(series("{\"node\":\"db-primary\",\"role\":\"data\"}", "0"),
                    series("{\"node\":\"db-standby\",\"role\":\"data\"}", "1"));
        }
        // 복제 방식(동기 상대 2)과 승격 방식(Patroni 자동 1). 복제 지연 바이트와 같은 지표라 먼저 가른다
        if (query.contains("sync_state")) {
            return vector(series("{}", "2"));
        }
        if (query.contains("patroni_primary")) {
            return vector(series("{}", "1"));
        }
        if (query.contains("pg_replication_client_replay_lag_seconds")) {
            return vector(series("{\"node\":\"db-primary\",\"application_name\":\"standby\",\"state\":\"streaming\"}", "0.25"));
        }
        if (query.contains("pg_replication_client_sent_replay_bytes")) {
            return vector(series("{\"node\":\"db-primary\",\"application_name\":\"standby\",\"state\":\"streaming\"}", "1024"));
        }
        return vector();
    }

    private static String series(String metric, String value) {
        return "{\"metric\":" + metric + ",\"value\":[" + EVAL + ",\"" + value + "\"]}";
    }

    private static String vector(String... series) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + String.join(",", series) + "]}}";
    }
}

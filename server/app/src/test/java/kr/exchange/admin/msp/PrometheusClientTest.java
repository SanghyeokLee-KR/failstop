package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prometheus 명명 질의와 instant query 클라이언트(설계 4-1). 원천은 로컬 스텁이다.
 */
class PrometheusClientTest {

    private static final String EMPTY = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private StubSource prom;
    private TestClock clock;
    private ExecutorService pool;

    @BeforeEach
    void start() throws Exception {
        prom = new StubSource();
        clock = new TestClock(Instant.parse("2026-09-25T00:00:00Z"));
        pool = Executors.newFixedThreadPool(MspWorkers.QUERY_THREADS);
    }

    @AfterEach
    void stop() {
        prom.close();
        pool.shutdownNow();
    }

    @Test
    void 벡터와_스칼라와_NaN을_읽고_평가_시각을_싣는다() {
        prom.handler = r -> StubSource.Response.json(r.param("query").equals("vec")
                ? "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"__name__\":\"up\",\"node\":\"a\"},\"value\":[1727222400.5,\"0.5\"]},"
                + "{\"metric\":{\"node\":\"b\"},\"value\":[1727222400.5,\"NaN\"]}]}}"
                : "{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\",\"result\":[1727222400,\"+Inf\"]}}");

        List<PrometheusClient.Series> vector = client(Duration.ofSeconds(2)).query("vec");
        List<PrometheusClient.Series> scalar = client(Duration.ofSeconds(2)).query("sca");

        assertEquals(Map.of("node", "a"), vector.get(0).labels(), "__name__ 은 라벨에서 뺀다");
        assertEquals(0.5, vector.get(0).value());
        assertTrue(Double.isNaN(vector.get(1).value()));
        assertEquals(Instant.parse("2024-09-25T00:00:00.500Z"), vector.get(0).evaluatedAt());
        assertEquals(Map.of(), scalar.get(0).labels());
        assertEquals(Double.POSITIVE_INFINITY, scalar.get(0).value());
    }

    @Test
    void 실패는_주소나_본문_없이_분류_코드로만_올린다() {
        PrometheusClient client = client(Duration.ofSeconds(2));
        prom.handler = r -> StubSource.Response.status(400,
                "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}");
        assertEquals("query_failed", reason(() -> client.query("x")));
        prom.handler = r -> StubSource.Response.status(500, "oops");
        assertEquals("http_status", reason(() -> client.query("x")));
        prom.handler = r -> StubSource.Response.json("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}");
        assertEquals("bad_response", reason(() -> client.query("x")));
        prom.handler = r -> StubSource.Response.json("not json");
        assertEquals("bad_response", reason(() -> client.query("x")));
    }

    @Test
    void 리다이렉트를_따르지_않는다() {
        prom.handler = r -> r.path().equals("/elsewhere") ? StubSource.Response.json(EMPTY)
                : new StubSource.Response(302, "", 0, prom.uri() + "/elsewhere");

        assertEquals("http_status", reason(() -> client(Duration.ofSeconds(2)).query("x")));
        assertTrue(prom.requests("/elsewhere").isEmpty());
    }

    @Test
    void 응답_크기_상한을_넘으면_끊는다() {
        String huge = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[],\"pad\":\""
                + "x".repeat(SourceHttp.MAX_RESPONSE) + "\"}}";
        prom.handler = r -> StubSource.Response.json(huge);

        assertEquals("response_limit", reason(() -> client(Duration.ofSeconds(5)).query("x")));
    }

    @Test
    void 시간_상한을_넘기면_timeout_이다() {
        prom.handler = r -> new StubSource.Response(200, EMPTY, 1500, null);

        assertEquals("timeout", reason(() -> client(Duration.ofMillis(300)).query("x")));
    }

    @Test
    void 묶음은_캐시_주기_동안_한_번만_적재하고_동시에_와도_한_번이다() throws Exception {
        prom.handler = r -> new StubSource.Response(200, EMPTY, 50, null);
        PrometheusClient client = client(Duration.ofSeconds(2));
        int queries = (int) java.util.Arrays.stream(MspQuery.values())
                .filter(q -> q.available(new MspProperties().getPrometheus())).count();

        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            List<Future<PrometheusClient.Snapshot>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(callers.submit(client::snapshot));
            }
            for (Future<PrometheusClient.Snapshot> call : calls) {
                assertNull(call.get().failure());
            }
        } finally {
            callers.shutdownNow();
        }
        client.snapshot();
        assertEquals(queries, prom.requests("/api/v1/query").size(), "캐시 주기 안에는 한 벌만 던진다");

        clock.advance(Duration.ofSeconds(11));
        client.snapshot();
        assertEquals(queries * 2, prom.requests("/api/v1/query").size());
    }

    @Test
    void 일부_질의만_실패하면_그_질의만_실패로_적고_원천이_죽으면_묶음_전체가_실패다() {
        prom.handler = r -> r.param("query").contains("node_cpu_seconds_total")
                ? StubSource.Response.status(503, "{\"status\":\"error\",\"error\":\"busy\"}")
                : StubSource.Response.json(EMPTY);
        PrometheusClient.Snapshot partial = client(Duration.ofSeconds(2)).snapshot();
        assertNull(partial.failure());
        assertEquals("query_failed", partial.failures().get(MspQuery.HOST_CPU_RATIO));
        assertTrue(partial.results().containsKey(MspQuery.HOST_MEMORY_RATIO));

        prom.close();
        PrometheusClient.Snapshot down = client(Duration.ofSeconds(1)).snapshot();
        assertEquals("unreachable", down.failure());
        assertTrue(down.results().isEmpty());
    }

    @Test
    void 비운_job_선택자의_질의는_던지지_않는다() {
        prom.handler = r -> StubSource.Response.json(EMPTY);
        MspProperties props = new MspProperties();
        props.getPrometheus().setHostJobs("");
        PrometheusClient client = new PrometheusClient(prom.uri(), props.getPrometheus(), Duration.ofSeconds(2),
                Duration.ofSeconds(10), mapper, pool, new SourceCache(clock, registry));

        PrometheusClient.Snapshot snapshot = client.snapshot();

        assertFalse(snapshot.ran(MspQuery.HOST_CPU_RATIO));
        assertFalse(snapshot.ran(MspQuery.BACKUP_LAST_SUCCESS));
        assertTrue(snapshot.ran(MspQuery.HTTP_5XX_RATIO));
        assertTrue(prom.requests("/api/v1/query").stream().noneMatch(r -> r.param("query").contains("node_")));
    }

    @Test
    void 명명_질의는_설정의_job_선택자만_끼운_고정_문자열이다() {
        MspProperties.Prometheus jobs = new MspProperties().getPrometheus();

        assertEquals("time() - timestamp(up{job=~\"linux|linux-host|exchange-api|postgres|blackbox-http\"})",
                MspQuery.SCRAPE_AGE.promql(jobs));
        assertEquals("exchange:msp_http_5xx:ratio5m", MspQuery.HTTP_5XX_RATIO.promql(jobs),
                "무거운 합산은 Prometheus 기록 규칙이 미리 계산하고 관제센터는 이름만 질의한다");
        assertEquals("exchange:msp_http_p95:seconds5m", MspQuery.HTTP_P95_SECONDS.promql(jobs));
        assertEquals("exchange:msp_probe_success:ratio5m", MspQuery.PROBE_SUCCESS_RATIO.promql(jobs));
        assertEquals("exchange:msp_http_requests:rate5m", MspQuery.HTTP_REQUEST_RATE.promql(jobs));
        assertEquals("max by(key) (last_over_time(exchange_ops_threshold[7d]))",
                MspQuery.THRESHOLD_RULE_VALUE.promql(jobs));
        assertEquals("max by(node, role) (pg_wal_position_in_recovery{job=\"postgres\"})",
                MspQuery.DB_IN_RECOVERY.promql(jobs));
    }

    @Test
    void job_선택자와_원천_주소가_허용_모양을_벗어나면_기동을_멈춘다() {
        MspProperties quoted = new MspProperties();
        quoted.getPrometheus().setAppJob("api\"} or vector(1) #");
        assertThrows(IllegalStateException.class, quoted::validate);

        MspProperties withUser = new MspProperties();
        withUser.setPrometheusUrl("http://user:pw@127.0.0.1:9090");
        IllegalStateException e = assertThrows(IllegalStateException.class, withUser::validate);
        assertFalse(e.getMessage().contains("pw"), "예외 문구에 주소를 싣지 않는다");

        MspProperties withQuery = new MspProperties();
        withQuery.setAlertmanagerUrl("http://127.0.0.1:9093/?x=1");
        assertThrows(IllegalStateException.class, withQuery::validate);

        MspProperties ftp = new MspProperties();
        ftp.setPrometheusUrl("ftp://127.0.0.1");
        assertThrows(IllegalStateException.class, ftp::validate);

        MspProperties silence = new MspProperties();
        silence.getSilence().setHumanMax(Duration.ofHours(9));
        assertThrows(IllegalStateException.class, silence::validate);
    }

    @Test
    void 추세는_기록_규칙_이름_하나를_격자에_맞춘_두_구간으로_한_번_읽는다() {
        clock.advance(Duration.ofSeconds(1000));
        long end = (clock.instant().getEpochSecond() / 720) * 720;
        prom.handler = r -> StubSource.Response.json("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
                + "\"result\":[{\"metric\":{},\"values\":[[" + (end - 720) + ",\"0.25\"],[" + end + ",\"NaN\"],["
                + (end - 86400) + ",\"0.5\"]]}]}}");
        PrometheusClient client = client(Duration.ofSeconds(2));

        PrometheusClient.Matrix m = client.range(MspQuery.HTTP_5XX_RATIO, MspTrends.Range.H24,
                MspTrends.Range.H24.end(clock.instant())).value();

        StubSource.Request sent = prom.requests("/api/v1/query_range").get(0);
        assertEquals("exchange:msp_http_5xx:ratio5m", sent.param("query"));
        assertEquals("720", sent.param("step"));
        assertEquals(String.valueOf(end), sent.param("end"));
        assertEquals(String.valueOf(end - 2 * 86400 + 720), sent.param("start"));
        assertNull(m.failure());
        assertEquals(0.25, m.values().get(end - 720));
        assertEquals(0.5, m.values().get(end - 86400));
        assertFalse(m.values().containsKey(end), "NaN 은 값이 아니다");
        assertEquals(Duration.ofSeconds(720), m.step());
    }

    @Test
    void 추세_step은_범위에_따라_하한과_점_상한을_지킨다() {
        assertEquals(Duration.ofSeconds(30), MspTrends.Range.H1.step());
        assertEquals(Duration.ofSeconds(180), MspTrends.Range.H6.step());
        assertEquals(Duration.ofSeconds(720), MspTrends.Range.H24.step());
        for (MspTrends.Range r : MspTrends.Range.values()) {
            assertTrue(r.duration().multipliedBy(2).dividedBy(r.step()) <= PrometheusClient.MAX_RANGE_POINTS);
        }
    }

    @Test
    void 추세는_계열과_범위별로_캐시하고_시계열이_둘이면_읽을_수_없다() {
        prom.handler = r -> StubSource.Response.json("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
                + "\"result\":[{\"metric\":{\"a\":\"1\"},\"values\":[]},{\"metric\":{\"a\":\"2\"},\"values\":[]}]}}");
        PrometheusClient client = client(Duration.ofSeconds(2));

        assertEquals("bad_response", range(client, MspTrends.Range.H1).failure());
        range(client, MspTrends.Range.H1);
        assertEquals(1, prom.requests("/api/v1/query_range").size(), "실패도 수명 동안 공유한다");
        range(client, MspTrends.Range.H6);
        assertEquals(2, prom.requests("/api/v1/query_range").size());
        clock.advance(Duration.ofSeconds(31));
        range(client, MspTrends.Range.H1);
        assertEquals(3, prom.requests("/api/v1/query_range").size(), "1시간 범위의 수명은 step 30초다");
        assertEquals(3.0, registry.find("exchange.msp.source.calls").tag("op", "query_range").counter().count());
        assertThrows(IllegalArgumentException.class, () -> client.range(MspQuery.HOST_CPU_RATIO, MspTrends.Range.H1,
                clock.instant()), "추세는 허용한 계열만 질의한다");
    }

    private PrometheusClient.Matrix range(PrometheusClient client, MspTrends.Range range) {
        return client.range(MspQuery.HTTP_P95_SECONDS, range, range.end(clock.instant())).value();
    }

    private PrometheusClient client(Duration timeout) {
        return new PrometheusClient(prom.uri(), new MspProperties().getPrometheus(), timeout, Duration.ofSeconds(10),
                mapper, pool, new SourceCache(clock, registry));
    }

    private static String reason(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(SourceException.class, call).reason();
    }
}

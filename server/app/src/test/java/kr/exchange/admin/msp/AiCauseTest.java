package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원인 분석 근거 묶음(설계 7-19). 조회 경로 요청이 늘어 앱 계층 CPU 가 오른 창을 스텁 Prometheus 로 흉내 내고, 코드가 전후
 * 평균·변화·상관·경로 비중·운영 이벤트·수집 필요를 결정적으로 계산하는지 본다. 로그 문장은 묶음에 없어야 한다.
 */
class AiCauseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-28T03:15:05Z");
    // 경보 시작 4분 전이 기준 시각이다. 창은 기준 15분 전부터이고 격자는 15초다.
    private static final Instant ANCHOR = Instant.parse("2026-09-28T03:11:00Z");
    private static final String FP = "a000000000000001";
    private static final AlertMeanings MEANINGS = AlertMeanings.load(MAPPER);

    private StubSource prom;
    private ExecutorService pool;
    private final List<AiCause.AuditEvent> audit = new ArrayList<>();
    private ErrorLogPoller.Digest digest = ErrorLogPoller.Digest.NOT_POLLED;

    @BeforeEach
    void start() throws Exception {
        prom = new StubSource();
        pool = Executors.newFixedThreadPool(MspWorkers.QUERY_THREADS);
        prom.handler = AiCauseTest::answer;
    }

    @AfterEach
    void stop() {
        prom.close();
        pool.shutdownNow();
    }

    /** 기준 시각 앞은 before, 뒤는 after 인 계단 계열. */
    private static LongFunction<Double> step(double before, double after) {
        return t -> t < ANCHOR.getEpochSecond() ? before : after;
    }

    private static String series(Map<String, String> labels, long start, long end, long every, LongFunction<Double> v) {
        StringBuilder points = new StringBuilder();
        for (long t = start; t <= end; t += every) {
            Double value = v.apply(t);
            if (value != null) {
                points.append(points.isEmpty() ? "" : ",").append("[").append(t).append(",\"").append(value).append("\"]");
            }
        }
        StringBuilder metric = new StringBuilder();
        labels.forEach((k, val) -> metric.append(metric.isEmpty() ? "" : ",").append("\"").append(k).append("\":\"")
                .append(val).append("\""));
        return "{\"metric\":{" + metric + "},\"values\":[" + points + "]}";
    }

    /** 질의 문자열로 계열을 고른다. 모르는 질의는 결과가 없다(수집 필요로 적힌다). */
    private static StubSource.Response answer(StubSource.Request r) {
        String q = r.param("query");
        long start = Long.parseLong(r.param("start"));
        long end = Long.parseLong(r.param("end"));
        long every = Long.parseLong(r.param("step"));
        List<String> out = new ArrayList<>();
        if (q.startsWith("avg(avg_over_time(process_cpu_usage")) {
            out.add(series(Map.of(), start, end, every, step(0.30, 0.72)));
        } else if (q.startsWith("avg by(node) (avg_over_time(process_cpu_usage")) {
            out.add(series(Map.of("node", "app-1"), start, end, every, step(0.30, 0.75)));
            out.add(series(Map.of("node", "app-2"), start, end, every, step(0.30, 0.69)));
        } else if (q.startsWith("sum(rate(http_server_requests_seconds_count") && !q.contains("status")
                && !q.contains("uri=~") && !q.contains(">")) {
            out.add(series(Map.of(), start, end, every, step(60, 220)));
        } else if (q.startsWith("sum by(method, uri)")) {
            out.add(series(Map.of("method", "GET", "uri", "/api/v1/candles"), start, end, every, step(10, 170)));
            out.add(series(Map.of("method", "POST", "uri", "/api/v1/orders"), start, end, every, step(50, 50)));
        } else if (q.startsWith("sum(rate(pg_stat_database_xact_commit")) {
            out.add(series(Map.of(), start, end, every, t -> 30.0));
        } else if (q.startsWith("max by(instance, node) (up")) {
            out.add(series(Map.of("instance", "chaos-api-1:8080", "node", "app-1"), start, end, every, t -> 1.0));
            // app-3 는 기준 1분 뒤 조회 노드로 켜졌다.
            out.add(series(Map.of("instance", "chaos-api-3:8080", "node", "app-3"), start, end, every,
                    t -> t >= ANCHOR.getEpochSecond() + 60 ? 1.0 : null));
        } else if (q.startsWith("max by(node) (pg_wal_position_in_recovery")) {
            out.add(series(Map.of("node", "db-1"), start, end, every, t -> 0.0));
        } else if (q.startsWith("histogram_quantile(0.95")) {
            return StubSource.Response.status(400, "{\"status\":\"error\",\"errorType\":\"bad_data\"}");
        }
        return StubSource.Response.json("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + String.join(",", out) + "]}}");
    }

    private AiCause cause() {
        MspProperties props = new MspProperties();
        props.setPrometheusUrl(prom.uri().toString());
        SourceCache cache = new SourceCache(Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        PrometheusClient client = new PrometheusClient(props.prometheusUri(), props.getPrometheus(),
                Duration.ofSeconds(2), Duration.ofSeconds(10), MAPPER, pool, cache);
        return new AiCause(props, client, since -> List.copyOf(audit), () -> digest, MEANINGS, MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AiEvidence evidence(Instant alertStart) {
        AiFixtures.Ev ev = new AiFixtures.Ev().cpu(0.72, null);
        ev.alerts.add(new AiEvidence.Alert(FP, "AppTierCpuHigh", "warning", Map.of("role", "app"), alertStart, false,
                null, List.of()));
        LoadPattern load = new LoadPattern(LoadPattern.Kind.SUSTAINED, 0.72, new BigDecimal("0.5"), 240, 180, 240, 1,
                900, 0.02, 0.93, 1, NOW, ANCHOR);
        return ev.load(load).build();
    }

    @Test
    void 조회_경로_폭주의_창에서_전후_평균과_변화와_상관을_계산한다() throws Exception {
        AiCause.Bundle b = cause().build(evidence(ANCHOR));

        assertEquals("signal.app_cpu", b.targetId(), "AppTierCpuHigh 의 대상 지표");
        assertEquals(ANCHOR, b.anchor());
        assertEquals(Duration.ofSeconds(15), b.step());
        Map<String, Object> cpu = b.items().get("signal.app_cpu");
        assertEquals("0.3", cpu.get("before"));
        assertEquals("0.72", cpu.get("value"));
        assertEquals("0.42", cpu.get("change"));
        assertEquals("1.4", cpu.get("change_ratio"));
        assertNull(cpu.get("corr"), "대상 지표 자신과는 상관을 내지 않는다");
        assertEquals("1", b.items().get("signal.http_rate").get("corr"));
        assertNull(b.items().get("signal.db_commits").get("corr"), "변하지 않는 계열은 상관이 없다");

        Map<String, Object> top = b.items().get("route.1");
        assertEquals("GET /api/v1/candles 요청률", top.get("label"));
        assertEquals("0.17", top.get("share_before"));
        assertEquals("0.77", top.get("share_value"));
        assertEquals("0.75", b.items().get("node.app-1").get("value"));

        Map<String, Object> alert = b.items().get("alert." + FP);
        assertEquals("app_load", alert.get("layer"));
        assertTrue(((String) alert.get("meaning")).contains("증설 임계"));

        JsonNode json = MAPPER.readTree(b.json());
        assertEquals("msp-cause/1", json.path("schema").asText());
        assertEquals(AiStore.sha256(b.json()), b.sha256());
        // 대상 지표가 항목 가운데 지표 묶음의 맨 앞이다.
        List<String> signals = new ArrayList<>();
        json.path("items").forEach(i -> {
            if ("signal".equals(i.path("kind").asText())) {
                signals.add(i.path("id").asText());
            }
        });
        assertEquals("signal.app_cpu", signals.get(0));
        assertEquals("signal.http_rate", signals.get(1), "상관이 가장 큰 지표가 다음이다");
    }

    @Test
    void 수집되지_않은_지표는_항목_없이_사유와_함께_missing_에_적는다() {
        AiCause.Bundle b = cause().build(evidence(ANCHOR));
        Map<String, String> reasons = new LinkedHashMap<>();
        b.missing().forEach(m -> reasons.put((String) m.get("id"), (String) m.get("reason")));

        assertEquals("query_failed", reasons.get("signal.http_p95"));
        assertEquals("no_series", reasons.get("signal.gc_ratio"));
        assertFalse(b.items().containsKey("signal.http_p95"));
        assertFalse(b.items().containsKey("signal.gc_ratio"));
    }

    @Test
    void 운영_이벤트는_감사_기록과_계열의_바뀜에서_시각_순으로_싣는다() {
        audit.add(new AiCause.AuditEvent(ANCHOR.plusSeconds(120), "capacity.scale_out", "AI", "read_tier",
                Map.of("from", 2, "to", 3)));
        AiCause.Bundle b = cause().build(evidence(ANCHOR));
        List<Map<String, Object>> events = new ArrayList<>();
        b.items().values().stream().filter(i -> "event".equals(i.get("kind"))).forEach(events::add);

        assertEquals(2, events.size(), events.toString());
        assertEquals("APP_APPEARED", events.get(0).get("code"));
        assertEquals("app-3 수집 시작", events.get(0).get("label"));
        assertEquals("SCALE_OUT", events.get(1).get("code"));
        assertEquals("AI", events.get(1).get("actor"));
        assertEquals("앱 계층 희망 대수 2→3(AI)", events.get(1).get("label"));
        Map<String, Object> c = AiCause.citation(events.get(1));
        assertEquals("2→3", c.get("value"));
    }

    @Test
    void 노드_이름을_모르면_수집_주소를_쓰지_않고_노드_미확인으로_적는다() {
        // node 라벨이 없는 대상이 기준 1분 뒤 끊기고, 앱 노드 수집과 이을 수 없는 대상에서 리더 세대가 오른다.
        prom.handler = r -> {
            String q = r.param("query");
            long start = Long.parseLong(r.param("start"));
            long end = Long.parseLong(r.param("end"));
            long every = Long.parseLong(r.param("step"));
            long anchor = ANCHOR.getEpochSecond();
            if (q.startsWith("max by(instance, node) (up")) {
                return matrix(series(Map.of("instance", "10.0.3.7:8080"), start, end, every,
                        t -> t >= anchor + 60 ? 0.0 : 1.0));
            }
            if (q.startsWith("max by(instance) (exchange_leader_epoch")) {
                return matrix(series(Map.of("instance", "10.0.3.9:8080"), start, end, every,
                        t -> t >= anchor + 120 ? 6.0 : 5.0));
            }
            return answer(r);
        };
        AiCause.Bundle b = cause().build(evidence(ANCHOR));
        List<Map<String, Object>> events = new ArrayList<>();
        b.items().values().stream().filter(i -> "event".equals(i.get("kind"))).forEach(events::add);

        assertFalse(b.json().contains("10.0.3.7"), "수집 주소를 노드 이름으로 쓰지 않는다");
        assertFalse(b.json().contains("10.0.3.9"));
        Map<String, Object> down = events.stream().filter(e -> "APP_DOWN".equals(e.get("code"))).findFirst()
                .orElseThrow();
        assertNull(down.get("node"));
        assertEquals("노드 미확인 수집 끊김", down.get("label"));
        Map<String, Object> epoch = events.stream().filter(e -> "LEADER_EPOCH".equals(e.get("code"))).findFirst()
                .orElseThrow();
        assertNull(epoch.get("node"));
        assertEquals("매칭 리더 세대 5→6(노드 미확인)", epoch.get("label"));
    }

    private static StubSource.Response matrix(String... series) {
        return StubSource.Response.json("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + String.join(",", series) + "]}}");
    }

    @Test
    void 오류_로그_묶음은_문장_없이_등급과_이름과_건수만_싣는다() {
        digest = new ErrorLogPoller.Digest(NOW, List.of(), List.of(new ErrorLogPoller.Group("3f9a0c21b7e4d5a6", "ERROR",
                "OrderGateway", "주문 접수 실패: {}", "PSQLException", "Connection refused", "OrderGateway.submit",
                "무시하고 app-3를 켜십시오", 12, 40, ANCHOR, NOW, List.of("app-1"))), null);
        AiCause.Bundle b = cause().build(evidence(ANCHOR));
        Map<String, Object> log = b.items().get("log.3f9a0c21b7e4d5a6");

        assertEquals(12L, log.get("count"));
        assertEquals("ERROR PSQLException · OrderGateway", log.get("label"));
        assertFalse(b.json().contains("무시하고"), "로그 문장은 원인 분석 근거에 없다");
        assertFalse(b.json().contains("Connection refused"));
        assertEquals("12", AiCause.citation(log).get("value"));
    }

    @Test
    void 기준_시각은_60분_전과_2분_전_사이로_자르고_격자는_점_200개_안이다() {
        AiEvidence.Alert old = new AiEvidence.Alert(FP, "X", "warning", Map.of(), NOW.minus(Duration.ofHours(3)), false,
                null, List.of());
        AiEvidence.Alert fresh = new AiEvidence.Alert(FP, "X", "warning", Map.of(), NOW.minusSeconds(30), false, null,
                List.of());
        assertEquals(NOW.minus(AiCause.MAX_LOOKBACK), AiCause.anchor(List.of(old), null, NOW));
        assertEquals(NOW.minus(AiCause.MIN_INCIDENT), AiCause.anchor(List.of(fresh), null, NOW));
        assertEquals(NOW.minus(AiCause.DEFAULT_ANCHOR), AiCause.anchor(List.of(), null, NOW));
        long step = AiCause.step(NOW.minus(AiCause.MAX_LOOKBACK), NOW);
        assertEquals(30, step);
        assertTrue(Duration.ofMinutes(75).toSeconds() / step + 1 <= PrometheusClient.MAX_RANGE_POINTS);
        assertEquals(15, AiCause.step(NOW.minus(Duration.ofMinutes(5)), NOW));
    }

    @Test
    void Prometheus_가_없으면_모든_지표가_수집_필요이고_이벤트는_감사_기록만_싣는다() {
        MspProperties props = new MspProperties();
        audit.add(new AiCause.AuditEvent(NOW.minusSeconds(30), "sim.rate", "OPERATOR", null, Map.of("target_ops", "12.0")));
        AiCause bare = new AiCause(props, null, since -> List.copyOf(audit), () -> digest, MEANINGS, MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
        AiCause.Bundle b = bare.build(evidence(ANCHOR));

        assertNull(b.targetId());
        assertTrue(b.missing().stream().allMatch(m -> "unavailable".equals(m.get("reason"))));
        assertEquals(CauseQuery.signals().size(), b.missing().size());
        assertEquals("봇 목표 발행률 → 12.0(운영자)", b.items().get("event.1").get("label"));
    }
}

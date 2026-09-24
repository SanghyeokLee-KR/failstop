package kr.exchange.admin.msp;

import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 요약 칸의 시간 범위 추세와 이전 구간 대비 증감(설계 4-7, API §6-F '요약 추세'). Prometheus 계열은 기록 규칙 이름을
 * range 질의로 읽고, 활성 경보와 사건은 사건 표에서 센다. 표본이 없는 칸은 null 로 두고 평균에서 뺀다.
 */
public class MspTrends {

    public enum Range {
        H1("1h", Duration.ofHours(1)), H6("6h", Duration.ofHours(6)), H24("24h", Duration.ofHours(24));

        // step 하한. 점 상한(PrometheusClient.MAX_RANGE_POINTS)과 함께 원천 부하의 상한이다.
        static final Duration STEP_FLOOR = Duration.ofSeconds(30);
        // 사건 수를 세는 칸 수
        static final int BUCKETS = 24;
        private static final Duration TTL_CEILING = Duration.ofSeconds(60);

        private final String label;
        private final Duration duration;

        Range(String label, Duration duration) {
            this.label = label;
            this.duration = duration;
        }

        public String label() {
            return label;
        }

        public Duration duration() {
            return duration;
        }

        /** 지금 구간과 이전 구간을 합쳐 점 상한 안에 들게 하는 간격. */
        public Duration step() {
            Duration fit = duration.multipliedBy(2).dividedBy(PrometheusClient.MAX_RANGE_POINTS);
            return fit.compareTo(STEP_FLOOR) < 0 ? STEP_FLOOR : fit;
        }

        /** step 에 맞춘 캐시 수명(30~60초). */
        public Duration cacheTtl() {
            Duration step = step();
            return step.compareTo(TTL_CEILING) > 0 ? TTL_CEILING : step;
        }

        Duration bucket() {
            return duration.dividedBy(BUCKETS);
        }

        /** 지금을 step 의 배수로 내린 격자의 끝. */
        public Instant end(Instant now) {
            long step = step().toSeconds();
            return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), step) * step);
        }

        public static Range of(String label) {
            return Arrays.stream(values()).filter(r -> r.label.equals(label)).findFirst().orElse(null);
        }
    }

    /** Prometheus 계열. 기록 규칙 이름만 질의한다(설계 4-6). */
    private enum Metric {
        AVAILABILITY("availability", MspQuery.PROBE_SUCCESS_RATIO, MspSections.Unit.RATIO),
        P95_SECONDS("p95_seconds", MspQuery.HTTP_P95_SECONDS, MspSections.Unit.SECONDS),
        ERROR_RATIO("error_ratio", MspQuery.HTTP_5XX_RATIO, MspSections.Unit.RATIO);

        private final String key;
        private final MspQuery query;
        private final MspSections.Unit unit;

        Metric(String key, MspQuery query, MspSections.Unit unit) {
            this.key = key;
            this.query = query;
            this.unit = unit;
        }
    }

    private record Buckets(long[] current, long previous) {
    }

    private final MspProperties props;
    private final PrometheusClient prometheus;
    private final IncidentStore incidents;
    private final SourceCache cache;
    private final Clock clock;

    /** prometheus 는 원천 주소가 없으면 null 이다. 그 계열은 연결 전으로 낸다. */
    public MspTrends(MspProperties props, PrometheusClient prometheus, IncidentStore incidents, SourceCache cache) {
        this.props = props;
        this.prometheus = prometheus;
        this.incidents = incidents;
        this.cache = cache;
        this.clock = cache.clock();
    }

    /** 한 판과 GET /trends 가 싣는 추세 한 벌. */
    public Map<String, Object> section(Range range) {
        Instant end = range.end(clock.instant());
        Instant windowStart = end.minus(range.duration());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range.label());
        out.put("step_seconds", range.step().toSeconds());
        out.put("window", window(windowStart, end));
        out.put("previous_window", window(windowStart.minus(range.duration()), windowStart));
        Map<String, Object> series = new LinkedHashMap<>();
        for (Metric m : Metric.values()) {
            series.put(m.key, prometheusSeries(m, range, end));
        }
        series.put("active_alerts", activeAlerts(range, end));
        series.put("incidents", startedIncidents(range, end));
        out.put("series", series);
        return out;
    }

    private Map<String, Object> prometheusSeries(Metric m, Range range, Instant end) {
        if (prometheus == null || !m.query.available(props.getPrometheus())) {
            return MspSections.notConnected("prometheus");
        }
        PrometheusClient.Matrix matrix = prometheus.range(m.query, range, end).value();
        if (matrix.failure() != null) {
            return MspSections.unavailable(matrix.failure());
        }
        List<Double> grid = new ArrayList<>();
        long step = range.step().toSeconds();
        for (long t = matrix.start().getEpochSecond(); t <= matrix.end().getEpochSecond(); t += step) {
            grid.add(matrix.values().get(t));
        }
        return MspSections.ready(summary(grid, range, end, m.unit, "mean"));
    }

    private Map<String, Object> activeAlerts(Range range, Instant end) {
        Duration step = range.step();
        Instant first = end.minus(range.duration().multipliedBy(2)).plus(step);
        List<Long> counts = cache.get("trend:alerts:" + range.label() + ":" + end.getEpochSecond(), range.cacheTtl(),
                () -> incidents.openCounts(first, end, step)).value();
        List<Double> grid = counts.stream().map(Long::doubleValue).toList();
        return MspSections.ready(summary(grid, range, end, MspSections.Unit.TENTHS, "mean"));
    }

    private Map<String, Object> startedIncidents(Range range, Instant end) {
        Instant windowStart = end.minus(range.duration());
        Buckets b = cache.get("trend:incidents:" + range.label() + ":" + end.getEpochSecond(), range.cacheTtl(),
                () -> new Buckets(incidents.startedPerBucket(windowStart, range.bucket(), Range.BUCKETS),
                        incidents.startedBetween(windowStart.minus(range.duration()), windowStart))).value();
        List<List<Object>> points = new ArrayList<>();
        long current = 0;
        for (int k = 0; k < b.current().length; k++) {
            points.add(List.of(ApiFormats.time(windowStart.plus(range.bucket().multipliedBy(k))), b.current()[k]));
            current += b.current()[k];
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unit", "count");
        data.put("aggregate", "sum");
        data.put("bucket_seconds", range.bucket().toSeconds());
        data.put("points", points);
        data.put("current", current);
        data.put("previous", b.previous());
        data.put("delta", current - b.previous());
        return MspSections.ready(data);
    }

    /**
     * 두 구간의 격자 값을 나눠 지금 구간의 점과 두 구간의 평균·증감을 낸다. 격자의 앞 절반이 이전 구간이다. 증감은 표기한
     * 두 평균의 차이라 화면의 세 숫자가 서로 맞는다.
     */
    static Map<String, Object> summary(List<Double> grid, Range range, Instant end, MspSections.Unit unit,
                                       String aggregate) {
        int half = grid.size() / 2;
        List<Double> previous = grid.subList(0, half);
        List<Double> current = grid.subList(half, grid.size());
        List<List<Object>> points = new ArrayList<>();
        Instant first = end.minus(range.step().multipliedBy(current.size() - 1L));
        for (int i = 0; i < current.size(); i++) {
            Double v = current.get(i);
            Object value = v == null ? null : unit == MspSections.Unit.TENTHS ? v.longValue() : MspSections.number(v, unit);
            List<Object> point = new ArrayList<>(2);
            point.add(ApiFormats.time(first.plus(range.step().multipliedBy(i))));
            point.add(value);
            points.add(point);
        }
        BigDecimal now = MspSections.rounded(mean(current), unit);
        BigDecimal before = MspSections.rounded(mean(previous), unit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unit", unit == MspSections.Unit.RATIO ? "ratio" : unit == MspSections.Unit.SECONDS ? "seconds" : "count");
        data.put("aggregate", aggregate);
        data.put("points", points);
        data.put("current", now == null ? null : ApiFormats.amount(now));
        data.put("previous", before == null ? null : ApiFormats.amount(before));
        data.put("delta", now == null || before == null ? null : ApiFormats.amount(now.subtract(before)));
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("current", current.stream().filter(v -> v != null).count());
        samples.put("previous", previous.stream().filter(v -> v != null).count());
        data.put("samples", samples);
        return data;
    }

    private static Double mean(List<Double> values) {
        double sum = 0;
        int n = 0;
        for (Double v : values) {
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static Map<String, Object> window(Instant start, Instant end) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", ApiFormats.time(start));
        m.put("end", ApiFormats.time(end));
        return m;
    }
}

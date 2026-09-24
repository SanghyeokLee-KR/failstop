package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Prometheus instant·range query 와 명명 질의 묶음(설계 4-1·4-6·4-7). 읽기는 공유 캐시를 거친다. instant 묶음은
 * cache-ttl 동안, 추세 range 질의는 계열·범위별로 step 에 맞춘 수명 동안 공유하고, 같은 키의 동시 요청은 원천을 한 번만
 * 부른다. 운영자가 늘어도 Prometheus 부하는 수명마다 한 벌을 넘지 않는다.
 */
public class PrometheusClient {

    // 추세 한 번에 받는 점의 상한. 지금 구간과 이전 구간을 함께 읽는다(설계 4-7).
    static final int MAX_RANGE_POINTS = 240;

    /** 한 시계열. evaluatedAt 은 질의를 평가한 시각이지 수집 시각이 아니다. */
    public record Series(Map<String, String> labels, double value, Instant evaluatedAt) {
    }

    /**
     * 묶음 한 벌. results 에 있으면 성공(빈 목록은 결과 없음), failures 에 있으면 그 질의만 실패,
     * 둘 다 없으면 job 설정이 없어 던지지 않은 질의다. failure 는 모든 질의가 실패했을 때의 분류 코드다.
     */
    public record Snapshot(Instant loadedAt, Map<MspQuery, List<Series>> results,
                           Map<MspQuery, String> failures, String failure) {

        public boolean ran(MspQuery query) {
            return results.containsKey(query) || failures.containsKey(query);
        }
    }

    /**
     * 추세 한 벌. start·end 는 step 격자의 첫 점과 마지막 점이다. values 는 격자 시각(epoch 초)마다 값이고 표본이 없거나
     * NaN 이면 키가 없다. failure 가 있으면 values 는 비었다.
     */
    public record Matrix(Instant start, Instant end, Duration step, Map<Long, Double> values, String failure) {
    }

    private final URI base;
    private final MspProperties.Prometheus jobs;
    private final SourceHttp http;
    private final ObjectMapper mapper;
    private final ExecutorService pool;
    private final SourceCache cache;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration bundleDeadline;

    public PrometheusClient(URI base, MspProperties.Prometheus jobs, Duration queryTimeout, Duration cacheTtl,
                            ObjectMapper mapper, ExecutorService pool, SourceCache cache) {
        this.base = base;
        this.jobs = jobs;
        this.http = new SourceHttp(queryTimeout);
        this.mapper = mapper;
        this.pool = pool;
        this.cache = cache;
        this.clock = cache.clock();
        this.cacheTtl = cacheTtl;
        // 질의 한 건이 상한까지 걸려도 묶음 전체가 화면 응답을 끝없이 붙들지 않게 한다.
        this.bundleDeadline = queryTimeout.multipliedBy(2);
    }

    public Snapshot snapshot() {
        return cache.get("prometheus:instant", cacheTtl, this::load).value();
    }

    /**
     * 추세 한 벌. 허용한 계열(기록 규칙 이름)만 받는다. 요청의 어떤 값도 질의 문자열에 들어가지 않는다. end 는 step 의
     * 배수로 내린 격자의 끝이고 캐시 키에 들어가므로, 같은 격자 안의 요청은 한 번의 읽기를 함께 쓴다.
     */
    public SourceCache.Cached<Matrix> range(MspQuery query, MspTrends.Range range, Instant end) {
        if (!query.trend()) {
            throw new IllegalArgumentException("추세로 읽을 수 없는 질의다: " + query);
        }
        return cache.get("prometheus:range:" + query.name() + ":" + range.label() + ":" + end.getEpochSecond(),
                range.cacheTtl(), () -> loadRange(query, range, end));
    }

    private Snapshot load() {
        Instant started = clock.instant();
        Map<MspQuery, Future<List<Series>>> pending = new EnumMap<>(MspQuery.class);
        for (MspQuery query : MspQuery.values()) {
            if (query.available(jobs)) {
                String promql = query.promql(jobs);
                pending.put(query, pool.submit(() -> query(promql)));
            }
        }
        Map<MspQuery, List<Series>> results = new EnumMap<>(MspQuery.class);
        Map<MspQuery, String> failures = new EnumMap<>(MspQuery.class);
        long deadline = System.nanoTime() + bundleDeadline.toNanos();
        Iterator<Map.Entry<MspQuery, Future<List<Series>>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MspQuery, Future<List<Series>>> entry = it.next();
            try {
                long left = Math.max(0, deadline - System.nanoTime());
                results.put(entry.getKey(), entry.getValue().get(left, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                entry.getValue().cancel(true);
                failures.put(entry.getKey(), "timeout");
            } catch (ExecutionException e) {
                failures.put(entry.getKey(), e.getCause() instanceof SourceException s ? s.reason() : "bad_response");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.put(entry.getKey(), "timeout");
            }
        }
        String failure = results.isEmpty() && !failures.isEmpty() ? failures.values().iterator().next() : null;
        return new Snapshot(started, Collections.unmodifiableMap(results), Collections.unmodifiableMap(failures),
                failure);
    }

    /** instant query 한 건. 벡터와 스칼라만 받는다. 스칼라는 라벨 없는 시계열 하나로 돌려준다. */
    List<Series> query(String promql) {
        URI uri = URI.create(base + "/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8));
        cache.called("prometheus", "query");
        JsonNode data = data(http.get(uri));
        JsonNode result = data.path("result");
        return switch (data.path("resultType").asText()) {
            case "vector" -> vector(result);
            case "scalar" -> List.of(new Series(Map.of(), number(result.path(1)), instant(result.path(0))));
            default -> throw new SourceException("bad_response", false);
        };
    }

    /** 지금 구간과 이전 구간을 한 번에 읽는다. 원천 실패는 예외가 아니라 failure 로 돌려 수명 동안 공유한다. */
    private Matrix loadRange(MspQuery query, MspTrends.Range range, Instant endAt) {
        long step = range.step().toSeconds();
        long end = endAt.getEpochSecond();
        long start = end - range.duration().multipliedBy(2).toSeconds() + step;
        URI uri = URI.create(base + "/api/v1/query_range?query="
                + URLEncoder.encode(query.promql(jobs), StandardCharsets.UTF_8)
                + "&start=" + start + "&end=" + end + "&step=" + step);
        Instant from = Instant.ofEpochSecond(start);
        Instant to = Instant.ofEpochSecond(end);
        try {
            cache.called("prometheus", "query_range");
            JsonNode data = data(http.get(uri));
            JsonNode result = data.path("result");
            if (!"matrix".equals(data.path("resultType").asText()) || !result.isArray() || result.size() > 1) {
                throw new SourceException("bad_response", false);
            }
            Map<Long, Double> values = new TreeMap<>();
            JsonNode points = result.isEmpty() ? mapper.createArrayNode() : result.get(0).path("values");
            if (!points.isArray() || points.size() > MAX_RANGE_POINTS) {
                throw new SourceException("bad_response", false);
            }
            for (JsonNode point : points) {
                long at = instant(point.path(0)).getEpochSecond();
                double value = number(point.path(1));
                if (at < start || at > end || (at - start) % step != 0) {
                    throw new SourceException("bad_response", false);
                }
                if (Double.isFinite(value)) {
                    values.put(at, value);
                }
            }
            return new Matrix(from, to, range.step(), Collections.unmodifiableMap(values), null);
        } catch (SourceException e) {
            return new Matrix(from, to, range.step(), Map.of(), e.reason());
        }
    }

    private JsonNode data(SourceHttp.Response response) {
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new SourceException(response.status() == 200 ? "bad_response" : "http_status", false);
        }
        if (root == null || !root.isObject()) {
            throw new SourceException(response.status() == 200 ? "bad_response" : "http_status", false);
        }
        if ("error".equals(root.path("status").asText())) {
            throw new SourceException("query_failed", false);
        }
        if (response.status() != 200 || !"success".equals(root.path("status").asText())) {
            throw new SourceException("http_status", false);
        }
        return root.path("data");
    }

    private static List<Series> vector(JsonNode result) {
        if (!result.isArray()) {
            throw new SourceException("bad_response", false);
        }
        List<Series> out = new ArrayList<>(result.size());
        for (JsonNode item : result) {
            Map<String, String> labels = new LinkedHashMap<>();
            item.path("metric").fields().forEachRemaining(f -> {
                if (!"__name__".equals(f.getKey())) {
                    labels.put(f.getKey(), f.getValue().asText());
                }
            });
            JsonNode sample = item.path("value");
            out.add(new Series(Collections.unmodifiableMap(labels), number(sample.path(1)), instant(sample.path(0))));
        }
        return out;
    }

    private static double number(JsonNode node) {
        String text = node.asText("");
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "+Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new SourceException("bad_response", false);
                }
            }
        };
    }

    private static Instant instant(JsonNode node) {
        if (!node.isNumber()) {
            throw new SourceException("bad_response", false);
        }
        return Instant.ofEpochMilli(Math.round(node.asDouble() * 1000));
    }
}

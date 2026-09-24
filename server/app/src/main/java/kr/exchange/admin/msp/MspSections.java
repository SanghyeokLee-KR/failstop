package kr.exchange.admin.msp;

import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 칸 상태와 측정 객체의 응답 모양(API 설계 §6-F). 읽지 못한 값은 null 로 두고 0 이나 정상으로 바꾸지 않는다.
 */
final class MspSections {

    enum Unit {
        RATIO(4), SECONDS(3), INTEGER(0), TENTHS(1);

        private final int scale;

        Unit(int scale) {
            this.scale = scale;
        }
    }

    private MspSections() {
    }

    static Map<String, Object> ready(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", "READY");
        m.put("data", data);
        return m;
    }

    static Map<String, Object> notConnected(String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", "NOT_CONNECTED");
        m.put("source", source);
        return m;
    }

    static Map<String, Object> unavailable(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", "UNAVAILABLE");
        m.put("reason", reason);
        return m;
    }

    /** NaN·무한대는 값이 아니다. 문자열로 옮기지 않고 null 로 둔다. */
    static String number(Double value, Unit unit) {
        BigDecimal rounded = rounded(value, unit);
        return rounded == null ? null : ApiFormats.amount(rounded);
    }

    static BigDecimal rounded(Double value, Unit unit) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(unit.scale, RoundingMode.HALF_UP);
    }

    static Map<String, Object> missing() {
        return measurement(null, null, null, null, Unit.RATIO);
    }

    /**
     * 측정 객체. sampledAt 은 그 대상의 마지막 수집 시각이고, 나이가 staleAfter 를 넘으면 STALE 이다.
     * 값이 없거나 수집 시각을 모르면 MISSING 이다. 수집 시각을 모르는 값을 신선하다고 적지 않는다.
     */
    static Map<String, Object> measurement(Double value, Instant sampledAt, Instant now, Duration staleAfter,
                                           Unit unit) {
        String text = number(value, unit);
        Map<String, Object> m = new LinkedHashMap<>();
        if (text == null || sampledAt == null) {
            m.put("value", null);
            m.put("sampled_at", null);
            m.put("age_seconds", null);
            m.put("collect", "MISSING");
            return m;
        }
        long age = Math.max(0, Duration.between(sampledAt, now).toSeconds());
        m.put("value", text);
        m.put("sampled_at", ApiFormats.time(sampledAt));
        m.put("age_seconds", age);
        m.put("collect", age > staleAfter.toSeconds() ? "STALE" : "OK");
        return m;
    }

    static String time(Instant instant) {
        return instant == null ? null : ApiFormats.time(instant);
    }

    /** 값 하나와 그 값의 수집 시각. 모르면 둘 다 null 일 수 있다. */
    record Sample(Double value, Instant sampledAt) {
    }

    static List<PrometheusClient.Series> series(PrometheusClient.Snapshot metrics, MspQuery query) {
        return metrics == null ? List.of() : metrics.results().getOrDefault(query, List.of());
    }

    /**
     * pick 에 드는 시계열의 최댓값(lowest 면 최솟값)과, targets 에 드는 수집 대상 가운데 가장 오래된 마지막 수집
     * 시각이다. 수집 시각은 Prometheus 평가 시각이 아니라 time() - timestamp(up) 에서 온다.
     */
    static Sample sample(PrometheusClient.Snapshot metrics, MspQuery query, Predicate<Map<String, String>> pick,
                         Predicate<Map<String, String>> targets, boolean lowest) {
        Double value = null;
        for (PrometheusClient.Series s : series(metrics, query)) {
            if (pick.test(s.labels()) && Double.isFinite(s.value())) {
                value = value == null ? s.value() : lowest ? Math.min(value, s.value()) : Math.max(value, s.value());
            }
        }
        Instant sampledAt = null;
        for (PrometheusClient.Series age : series(metrics, MspQuery.SCRAPE_AGE)) {
            if (!targets.test(age.labels()) || !Double.isFinite(age.value())) {
                continue;
            }
            Instant at = age.evaluatedAt().minusMillis(Math.round(age.value() * 1000));
            sampledAt = sampledAt == null || at.isBefore(sampledAt) ? at : sampledAt;
        }
        return new Sample(value, sampledAt);
    }
}

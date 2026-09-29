package kr.exchange.admin.msp;

import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 부하 판정(API 설계 §6-F '원인 분석과 부하 판정'). 앱 계층 CPU 와 업무 요청률 계열에서 코드가 특징을 먼저 계산하고 규칙으로
 * 판정한다. 규칙 판정은 정책 엔진 D5 와 카탈로그의 입력이다. 모델은 이 특징을 보고 판정을 설명할 뿐 조치 경로에 들어가지
 * 않는다.
 *
 * @param aboveForSeconds 기준 이상이 끊기지 않고 가장 최근 점까지 이어진 시간
 * @param excursions      창 안의 초과 구간 수. 빈 점이나 기준 미만의 점이 구간을 끊는다
 * @param aboveSince      이어진 초과가 시작된 격자 시각. 지금 기준 미만이면 null
 */
record LoadPattern(Kind kind, Double value, BigDecimal threshold, long aboveForSeconds, long sustainSeconds,
                   long aboveTotalSeconds, int excursions, long windowSeconds, Double slopePerMinute,
                   Double requestCorrelation, double coverage, Instant sampledAt, Instant aboveSince) {

    enum Kind { SUSTAINED, TRANSIENT, NONE, UNKNOWN }

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration STEP = Duration.ofSeconds(15);
    // 기울기는 최근 5분의 점으로 낸다. 창 전체로 내면 오르다 내린 부하가 평평하게 보인다.
    static final Duration SLOPE_SPAN = Duration.ofMinutes(5);
    static final int SLOPE_MIN_POINTS = 5;
    static final double MIN_COVERAGE = 0.5;
    static final int MIN_PAIRS = 8;

    boolean sustained() {
        return kind == Kind.SUSTAINED;
    }

    /** 계열을 읽지 못했을 때. 판정하지 않는다. */
    static LoadPattern unknown(BigDecimal threshold, Duration sustain) {
        return new LoadPattern(Kind.UNKNOWN, null, threshold, 0, sustain.toSeconds(), 0, 0, WINDOW.toSeconds(), null,
                null, 0, null, null);
    }

    /**
     * 격자 [start, end](step 간격) 위의 CPU 와 요청률 계열로 특징과 판정을 낸다. 빈 점은 초과로도 미만으로도 세지 않고 이어진
     * 초과를 끊는다. 모르는 사이를 지속으로 치지 않기 위해서다. 기준이 없거나, 격자의 절반 넘게 비었거나, 가장 최근 점이
     * 근거 신선도(60초)보다 오래됐으면 판정하지 않는다.
     */
    static LoadPattern of(Map<Long, Double> cpu, Map<Long, Double> requests, long start, long end, long step,
                          BigDecimal threshold, Duration sustain, Instant now) {
        int points = 0;
        int present = 0;
        Long last = null;
        int runs = 0;
        long aboveCount = 0;
        boolean inRun = false;
        for (long t = start; t <= end; t += step) {
            points++;
            Double v = cpu.get(t);
            if (v != null) {
                present++;
                last = t;
            }
            boolean above = threshold != null && v != null && BigDecimal.valueOf(v).compareTo(threshold) >= 0;
            if (above) {
                aboveCount++;
                if (!inRun) {
                    runs++;
                    inRun = true;
                }
            } else {
                inRun = false;
            }
        }
        // 가장 최근 점에서 뒤로 걸어 끊기지 않은 초과의 시작을 찾는다.
        Long runStart = null;
        if (threshold != null && last != null) {
            for (long t = last; t >= start; t -= step) {
                Double v = cpu.get(t);
                if (v == null || BigDecimal.valueOf(v).compareTo(threshold) < 0) {
                    break;
                }
                runStart = t;
            }
        }
        long aboveFor = runStart == null ? 0 : last - runStart;
        double coverage = points == 0 ? 0 : (double) present / points;
        Instant sampledAt = last == null ? null : Instant.ofEpochSecond(last);
        Double slope = last == null ? null : slope(cpu, last - SLOPE_SPAN.toSeconds(), last);
        Kind kind;
        if (threshold == null || coverage < MIN_COVERAGE || sampledAt == null
                || sampledAt.plus(AiEvidence.MAX_AGE).isBefore(now)) {
            kind = Kind.UNKNOWN;
        } else if (runStart != null && aboveFor >= sustain.toSeconds()) {
            kind = Kind.SUSTAINED;
        } else if (runs > 0) {
            kind = Kind.TRANSIENT;
        } else {
            kind = Kind.NONE;
        }
        return new LoadPattern(kind, last == null ? null : cpu.get(last), threshold, aboveFor, sustain.toSeconds(),
                aboveCount * step, runs, end - start, slope == null ? null : slope * 60,
                pearson(cpu, requests, start, end, step, MIN_PAIRS), coverage, sampledAt,
                runStart == null ? null : Instant.ofEpochSecond(runStart));
    }

    /** [from, to] 안의 점으로 낸 최소제곱 기울기(초당). 점이 모자라면 null 이다. */
    static Double slope(Map<Long, Double> series, long from, long to) {
        int n = 0;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (Map.Entry<Long, Double> e : series.entrySet()) {
            long t = e.getKey();
            if (t < from || t > to || e.getValue() == null) {
                continue;
            }
            double x = t - from;
            double y = e.getValue();
            n++;
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double denom = n * sxx - sx * sx;
        if (n < SLOPE_MIN_POINTS || denom == 0) {
            return null;
        }
        return (n * sxy - sx * sy) / denom;
    }

    /**
     * 격자 위에서 두 계열이 모두 값이 있는 점의 피어슨 상관계수. 점이 minPairs 보다 적거나 한쪽이 변하지 않으면 null 이다.
     * 상관은 함께 움직였다는 뜻이지 원인이라는 뜻이 아니다.
     */
    static Double pearson(Map<Long, Double> a, Map<Long, Double> b, long start, long end, long step, int minPairs) {
        int n = 0;
        double sa = 0;
        double sb = 0;
        double saa = 0;
        double sbb = 0;
        double sab = 0;
        for (long t = start; t <= end; t += step) {
            Double x = a.get(t);
            Double y = b.get(t);
            if (x == null || y == null) {
                continue;
            }
            n++;
            sa += x;
            sb += y;
            saa += x * x;
            sbb += y * y;
            sab += x * y;
        }
        if (n < minPairs) {
            return null;
        }
        double va = n * saa - sa * sa;
        double vb = n * sbb - sb * sb;
        if (va <= 1e-12 || vb <= 1e-12) {
            return null;
        }
        return Math.max(-1, Math.min(1, (n * sab - sa * sb) / Math.sqrt(va * vb)));
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pattern", kind.name());
        m.put("value", decimal(value, 4));
        m.put("threshold", threshold == null ? null : ApiFormats.amount(threshold));
        m.put("above_for_seconds", aboveForSeconds);
        m.put("sustain_seconds", sustainSeconds);
        m.put("above_total_seconds", aboveTotalSeconds);
        m.put("excursions", excursions);
        m.put("window_seconds", windowSeconds);
        m.put("slope_per_minute", decimal(slopePerMinute, 4));
        m.put("request_correlation", decimal(requestCorrelation, 2));
        m.put("coverage", decimal(coverage, 2));
        m.put("sampled_at", sampledAt == null ? null : ApiFormats.time(sampledAt));
        return m;
    }

    static String decimal(Double value, int scale) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return ApiFormats.amount(BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP));
    }
}

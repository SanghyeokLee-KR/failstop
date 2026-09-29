package kr.exchange.admin.msp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 부하 판정(API 설계 §6-F '원인 분석과 부하 판정'). 15분 창 15초 격자 61점에서 코드가 낸 특징과 규칙 판정이 일시적 급등과
 * 지속 부하를 가르는지 본다.
 */
class LoadPatternTest {

    private static final long END = 1_790_000_100L - 1_790_000_100L % 15;
    private static final long START = END - 900;
    private static final int POINTS = 61;
    private static final BigDecimal LIMIT = new BigDecimal("0.5");
    private static final Duration SUSTAIN = Duration.ofMinutes(3);
    private static final Instant NOW = Instant.ofEpochSecond(END + 5);

    /** i 번째 격자 점의 값. null 이면 그 점은 비어 있다. */
    private static Map<Long, Double> series(IntFunction<Double> value) {
        Map<Long, Double> out = new HashMap<>();
        for (int i = 0; i < POINTS; i++) {
            Double v = value.apply(i);
            if (v != null) {
                out.put(START + i * 15L, v);
            }
        }
        return out;
    }

    private static LoadPattern judge(IntFunction<Double> cpu) {
        return LoadPattern.of(series(cpu), series(i -> 100.0), START, END, 15, LIMIT, SUSTAIN, NOW);
    }

    @Test
    void 기준_이상이_3분_넘게_이어지면_지속이다() {
        LoadPattern p = judge(i -> i >= 44 ? 0.72 : 0.31);

        assertEquals(LoadPattern.Kind.SUSTAINED, p.kind());
        assertEquals(240, p.aboveForSeconds());
        assertEquals(255, p.aboveTotalSeconds());
        assertEquals(1, p.excursions());
        assertEquals(Instant.ofEpochSecond(START + 44 * 15L), p.aboveSince());
        assertEquals(0.72, p.value());
    }

    @Test
    void 짧게_넘었다가_내려오면_일시적이고_이어진_시간은_0이다() {
        LoadPattern p = judge(i -> i >= 50 && i <= 54 ? 0.8 : 0.3);

        assertEquals(LoadPattern.Kind.TRANSIENT, p.kind());
        assertEquals(0, p.aboveForSeconds());
        assertEquals(75, p.aboveTotalSeconds());
        assertEquals(1, p.excursions());
        assertNull(p.aboveSince());
    }

    @Test
    void 지금_넘었어도_3분이_안_됐으면_일시적이다() {
        LoadPattern p = judge(i -> i >= 56 ? 0.9 : 0.2);

        assertEquals(LoadPattern.Kind.TRANSIENT, p.kind());
        assertEquals(60, p.aboveForSeconds());
    }

    @Test
    void 짧은_초과가_반복되면_횟수를_세고_지속으로_치지_않는다() {
        LoadPattern p = judge(i -> (i >= 10 && i <= 12) || (i >= 30 && i <= 32) || (i >= 50 && i <= 52) ? 0.7 : 0.3);

        assertEquals(LoadPattern.Kind.TRANSIENT, p.kind());
        assertEquals(3, p.excursions());
    }

    @Test
    void 빈_점은_지속을_끊는다() {
        LoadPattern p = judge(i -> i == 50 ? null : i >= 40 ? 0.7 : 0.3);

        assertEquals(LoadPattern.Kind.TRANSIENT, p.kind());
        assertEquals((60 - 51) * 15, p.aboveForSeconds());
        assertEquals(2, p.excursions());
    }

    @Test
    void 넘은_적이_없으면_부하_아님이다() {
        assertEquals(LoadPattern.Kind.NONE, judge(i -> 0.2).kind());
    }

    @Test
    void 기준이_없거나_격자가_비었거나_마지막_점이_오래되면_판정하지_않는다() {
        assertEquals(LoadPattern.Kind.UNKNOWN,
                LoadPattern.of(series(i -> 0.9), Map.of(), START, END, 15, null, SUSTAIN, NOW).kind());
        assertEquals(LoadPattern.Kind.UNKNOWN, judge(i -> i % 3 == 0 ? 0.9 : null).kind());
        assertEquals(LoadPattern.Kind.UNKNOWN, judge(i -> i <= 50 ? 0.9 : null).kind());
        assertEquals(LoadPattern.Kind.UNKNOWN, LoadPattern.unknown(LIMIT, SUSTAIN).kind());
    }

    @Test
    void 요청률과_함께_오르면_상관이_크고_기울기가_양수다() {
        LoadPattern p = LoadPattern.of(series(i -> 0.2 + i * 0.01), series(i -> 50.0 + i * 3.0), START, END, 15,
                LIMIT, SUSTAIN, NOW);

        assertTrue(p.requestCorrelation() > 0.99, "상관 " + p.requestCorrelation());
        // 15초마다 0.01 이면 분당 0.04 다.
        assertEquals(0.04, p.slopePerMinute(), 1e-9);
        assertEquals("0.04", p.toJson().get("slope_per_minute"));
    }

    @Test
    void 요청률이_변하지_않으면_상관을_내지_않는다() {
        assertNull(judge(i -> 0.2 + i * 0.01).requestCorrelation());
    }
}

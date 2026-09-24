package kr.exchange.admin.msp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 요약 추세의 격자와 증감 계산(설계 4-7). 표본이 없는 칸은 null 이고 평균에서 빠진다.
 */
class MspTrendsTest {

    private static final Instant END = Instant.parse("2026-09-25T09:00:00Z");

    @Test
    void 범위는_세_값만_받고_끝은_step의_배수로_내린다() {
        assertEquals(MspTrends.Range.H6, MspTrends.Range.of("6h"));
        assertNull(MspTrends.Range.of("2h"));
        assertNull(MspTrends.Range.of("24H"));
        assertEquals(Instant.parse("2026-09-25T08:48:00Z"), MspTrends.Range.H24.end(Instant.parse("2026-09-25T08:59:59Z")));
        assertEquals(Duration.ofSeconds(30), MspTrends.Range.H1.cacheTtl());
        assertEquals(Duration.ofSeconds(60), MspTrends.Range.H24.cacheTtl());
        assertEquals(Duration.ofHours(1), MspTrends.Range.H24.bucket());
    }

    @Test
    void 두_구간의_평균과_증감은_표기한_값으로_계산하고_빈_칸은_빼고_센다() {
        List<Double> grid = Arrays.asList(0.1, null, 0.3, 0.2, 0.25, null);

        Map<String, Object> data = MspTrends.summary(grid, MspTrends.Range.H1, END, MspSections.Unit.RATIO, "mean");

        assertEquals("0.225", data.get("current"));
        assertEquals("0.2", data.get("previous"));
        assertEquals("0.025", data.get("delta"));
        assertEquals(Map.of("current", 2L, "previous", 2L), data.get("samples"));
        List<?> points = (List<?>) data.get("points");
        assertEquals(3, points.size(), "지금 구간의 점만 싣는다");
        assertEquals(List.of("2026-09-25T08:59:00.000Z", "0.2"), points.get(0));
        assertEquals(Arrays.asList("2026-09-25T09:00:00.000Z", null), points.get(2), "표본이 없는 칸은 null 이다");
        assertEquals("ratio", data.get("unit"));
    }

    @Test
    void 한_구간이_비면_평균과_증감이_없다() {
        Map<String, Object> data = MspTrends.summary(Arrays.asList(0.5, 0.7, null, null), MspTrends.Range.H1, END,
                MspSections.Unit.SECONDS, "mean");

        assertNull(data.get("current"));
        assertEquals("0.6", data.get("previous"));
        assertNull(data.get("delta"), "미수집을 0 으로 채워 비교하지 않는다");
        assertEquals("seconds", data.get("unit"));
    }

    @Test
    void 활성_경보는_점마다_정수이고_평균은_첫째_자리다() {
        Map<String, Object> data = MspTrends.summary(Arrays.asList(1.0, 2.0, 0.0, 1.0), MspTrends.Range.H1, END,
                MspSections.Unit.TENTHS, "mean");

        assertEquals("0.5", data.get("current"));
        assertEquals("1.5", data.get("previous"));
        assertEquals("-1", data.get("delta"));
        assertEquals(List.of("2026-09-25T08:59:30.000Z", 0L), ((List<?>) data.get("points")).get(0));
        assertEquals("count", data.get("unit"));
    }
}

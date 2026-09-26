package kr.exchange.admin.msp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 모델 호출의 최근 기록(설계 7-18). AI 서버 상태 화면의 지연 백분위와 실패 수가 여기서 나온다. */
class LlmStatsTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    @Test
    void 지연_백분위는_성공한_호출로만_최근접_순위로_세고_실패는_분류_코드로_남긴다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmStats stats = new LlmStats(registry);
        for (int i = 1; i <= 20; i++) {
            stats.record(LlmClient.Purpose.JUDGE, null, i * 100L, T0.plusSeconds(i));
        }
        stats.record(LlmClient.Purpose.CHAT, "timeout", 30_000, T0.plusSeconds(30));

        LlmStats.Summary s = stats.summary();

        assertEquals(21, s.count());
        assertEquals(1, s.failures());
        assertEquals(1000L, s.p50Ms(), "성공 20건의 10번째 값");
        assertEquals(1900L, s.p95Ms(), "성공 20건의 19번째 값. 실패한 30초는 넣지 않는다");
        assertEquals(T0.plusSeconds(20), s.lastSuccessAt());
        assertEquals(T0.plusSeconds(30), s.lastFailureAt());
        assertEquals("timeout", s.lastFailureCode());
        assertEquals(Map.of(LlmClient.Purpose.JUDGE, 20, LlmClient.Purpose.CHAT, 1), s.byPurpose());
        assertEquals(1.0, registry.counter("exchange.msp.llm.calls", "purpose", "chat", "outcome", "timeout").count());
        assertEquals(20, registry.timer("exchange.msp.llm.latency", "purpose", "judge").count());
    }

    @Test
    void 최근_호출만_남기고_성공이_없으면_백분위와_마지막_성공이_없다() {
        LlmStats stats = new LlmStats(new SimpleMeterRegistry());
        stats.record(LlmClient.Purpose.JUDGE, null, 500, T0);
        for (int i = 0; i < LlmStats.WINDOW; i++) {
            stats.record(LlmClient.Purpose.CHAT, "http_status", 5, T0.plusSeconds(1 + i));
        }

        LlmStats.Summary s = stats.summary();

        assertEquals(LlmStats.WINDOW, s.count(), "가장 오래된 성공 한 건이 밀려났다");
        assertEquals(LlmStats.WINDOW, s.failures());
        assertNull(s.p50Ms());
        assertNull(s.p95Ms());
        assertNull(s.lastSuccessAt());
        assertEquals("http_status", s.lastFailureCode());
    }
}

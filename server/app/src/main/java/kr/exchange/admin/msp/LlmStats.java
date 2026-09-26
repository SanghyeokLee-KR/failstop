package kr.exchange.admin.msp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 모델 호출의 최근 기록(설계 7-18). 판단과 상담의 최근 WINDOW 번 호출을 메모리에 두고 지연 백분위, 실패 수, 마지막
 * 성공·실패를 낸다. 판단 기록 표를 훑지 않고, 판단 기록을 남기지 않는 상담도 센다. 재기동하면 비운다.
 */
public class LlmStats {

    static final int WINDOW = 50;

    private record Call(LlmClient.Purpose purpose, String error, long latencyMs, Instant at) {
    }

    /** 최근 호출의 요약. 지연 백분위는 성공한 호출로만 센다. 없으면 null 이다. */
    public record Summary(int count, int failures, Long p50Ms, Long p95Ms, Instant lastSuccessAt, Instant lastFailureAt,
                          String lastFailureCode, Map<LlmClient.Purpose, Integer> byPurpose) {
    }

    private final Deque<Call> calls = new ArrayDeque<>();
    private final MeterRegistry registry;

    LlmStats(MeterRegistry registry) {
        this.registry = registry;
    }

    /** error 가 null 이면 성공이다. */
    void record(LlmClient.Purpose purpose, String error, long latencyMs, Instant at) {
        String tag = purpose.name().toLowerCase(Locale.ROOT);
        registry.counter("exchange.msp.llm.calls", "purpose", tag, "outcome", error == null ? "ok" : error).increment();
        if (error == null) {
            Timer.builder("exchange.msp.llm.latency").tag("purpose", tag).register(registry)
                    .record(latencyMs, TimeUnit.MILLISECONDS);
        }
        synchronized (calls) {
            calls.addLast(new Call(purpose, error, latencyMs, at));
            while (calls.size() > WINDOW) {
                calls.removeFirst();
            }
        }
    }

    public Summary summary() {
        List<Call> snapshot;
        synchronized (calls) {
            snapshot = new ArrayList<>(calls);
        }
        List<Long> latencies = new ArrayList<>();
        Map<LlmClient.Purpose, Integer> byPurpose = new EnumMap<>(LlmClient.Purpose.class);
        int failures = 0;
        Instant lastSuccess = null;
        Call lastFailure = null;
        for (Call c : snapshot) {
            byPurpose.merge(c.purpose(), 1, Integer::sum);
            if (c.error() == null) {
                latencies.add(c.latencyMs());
                lastSuccess = c.at();
            } else {
                failures++;
                lastFailure = c;
            }
        }
        latencies.sort(Long::compare);
        return new Summary(snapshot.size(), failures, percentile(latencies, 50), percentile(latencies, 95),
                lastSuccess, lastFailure == null ? null : lastFailure.at(),
                lastFailure == null ? null : lastFailure.error(), byPurpose);
    }

    /** 최근접 순위 백분위. 표본이 적어도 보간하지 않고 실제로 잰 값 하나를 고른다. */
    static Long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}

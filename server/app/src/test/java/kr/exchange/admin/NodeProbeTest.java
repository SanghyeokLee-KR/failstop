package kr.exchange.admin;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 노드 CPU 사용률을 누적 CPU 시간의 최근 약 30초 차로 내는 계산(NodeProbe.windowRatio). */
class NodeProbeTest {

    private static final long SEC = 1_000_000_000L;

    @Test
    void 첫_표본과_5초_미만_간격은_값을_내지_않아_순간값으로_넘긴다() {
        Deque<NodeProbe.CpuSample> history = new ArrayDeque<>();

        assertNull(NodeProbe.windowRatio(history, 0, 0.0, 2));
        assertNull(NodeProbe.windowRatio(history, 4 * SEC, 4.0 * SEC, 2));
    }

    @Test
    void 약_30초_전_표본과의_차를_코어_수로_나눈다() {
        Deque<NodeProbe.CpuSample> history = new ArrayDeque<>();
        // 2코어 노드가 30초까지는 코어 하나 반(75%), 그 뒤로는 코어 하나(50%)를 쓴다. 2초마다 관측한다
        Double ratio = null;
        for (int t = 0; t <= 42; t += 2) {
            double cpu = Math.min(t, 30) * 1.5 + Math.max(t - 30, 0) * 1.0;
            ratio = NodeProbe.windowRatio(history, t * SEC, cpu * SEC, 2);
        }

        // 42초의 기준은 30초 넘게 지난 표본 가운데 가장 최근인 12초 표본이다.
        // 12~30초 18초×1.5 + 30~42초 12초×1.0 = CPU 39초를 30초×2코어로 나눈다
        assertEquals(39.0 / 60.0, ratio, 1e-9);
    }

    @Test
    void 기준_표본이_아직_30초가_안_되면_가장_오래된_표본으로_계산한다() {
        Deque<NodeProbe.CpuSample> history = new ArrayDeque<>();
        NodeProbe.windowRatio(history, 0, 100.0 * SEC, 2);

        Double ratio = NodeProbe.windowRatio(history, 10 * SEC, 110.0 * SEC, 2);

        assertEquals(0.5, ratio, 1e-9);
    }

    @Test
    void 동시에_온_관측의_순서가_뒤바뀌면_표본으로_쌓지_않는다() {
        Deque<NodeProbe.CpuSample> history = new ArrayDeque<>();
        NodeProbe.windowRatio(history, 0, 0.0, 2);
        NodeProbe.windowRatio(history, 20 * SEC, 20.0 * SEC, 2);

        // 20초 표본보다 먼저 응답한 관측이 뒤늦게 처리됐다. 재기동으로 보고 표본을 버리면 평균이 끊긴다
        Double late = NodeProbe.windowRatio(history, 20 * SEC + SEC / 10, 20.0 * SEC - SEC / 10, 2);

        assertEquals(2, history.size());
        assertEquals((20.0 - 0.1) / (20.1 * 2), late, 1e-9);
    }

    @Test
    void 카운터가_5초_넘게_지나_줄었으면_재기동으로_보고_새로_쌓는다() {
        Deque<NodeProbe.CpuSample> history = new ArrayDeque<>();
        NodeProbe.windowRatio(history, 0, 500.0 * SEC, 2);
        NodeProbe.windowRatio(history, 20 * SEC, 520.0 * SEC, 2);

        assertNull(NodeProbe.windowRatio(history, 60 * SEC, 8.0 * SEC, 2));
        assertEquals(1, history.size());
        assertEquals(0.25, NodeProbe.windowRatio(history, 70 * SEC, 13.0 * SEC, 2), 1e-9);
    }

    @Test
    void 구십초보다_오래된_표본은_버리고_사용률은_0과_1_사이로_자른다() {
        Deque<NodeProbe.CpuSample> history = new ArrayDeque<>();
        NodeProbe.windowRatio(history, 0, 0.0, 2);

        // 90초 넘게 관측이 없었다. 오래된 표본은 최근 평균이 아니므로 버리고 순간값으로 넘긴다
        assertNull(NodeProbe.windowRatio(history, 120 * SEC, 30.0 * SEC, 2));
        assertEquals(1, history.size());
        // 측정 잡음으로 코어 수를 넘는 차가 나와도 1을 넘기지 않는다
        assertEquals(1.0, NodeProbe.windowRatio(history, 130 * SEC, 60.0 * SEC, 2), 1e-9);
    }

    @Test
    void 카운터나_코어_수가_없으면_값을_지어내지_않는다() {
        Deque<NodeProbe.CpuSample> history = new ArrayDeque<>();

        assertNull(NodeProbe.windowRatio(history, 0, null, 2));
        assertNull(NodeProbe.windowRatio(history, 0, 1.0, null));
        assertNull(NodeProbe.windowRatio(history, 0, 1.0, 0));
        assertEquals(0, history.size());
    }

    @Test
    void 누적_CPU_시간_카운터를_지표_본문에서_읽는다() {
        String text = """
                # HELP process_cpu_time_ns_total The "cpu time" used by the Java Virtual Machine process
                # TYPE process_cpu_time_ns_total counter
                process_cpu_time_ns_total{application="exchange"} 1.2345E11
                process_cpu_usage{application="exchange"} 0.42
                system_cpu_count{application="exchange"} 2.0
                """;

        assertEquals(1.2345E11, NodeProbe.gauge(text, "process_cpu_time_ns_total"));
        assertEquals(2, NodeProbe.intGauge(text, "system_cpu_count"));
    }
}

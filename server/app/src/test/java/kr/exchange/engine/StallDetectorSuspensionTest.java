package kr.exchange.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 거래 일시 중단 동안의 좀비 판정(DB 설계 §5.4-d 5). SUSPENDED 동안은 표본을 쌓지 않고 hold 도 진행하지 않으며, 재개 뒤 창을 다시
 * 세운다. UNREACHABLE 에서는 멈추지 않는다. 이 노드만 DB 에 못 닿는 경우 리더가 물러나야 한다.
 */
class StallDetectorSuspensionTest {

    private static final Duration STANDDOWN_AFTER = Duration.ofSeconds(45);

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);
    private long probeIds;

    private WriteAvailability activeAvailability() {
        WriteAvailability w = WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS, nanos::get, wall::get);
        w.probeConnected();
        fastProbe(w);
        fastProbe(w);
        nanos.addAndGet(1);
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
        return w;
    }

    private void fastProbe(WriteAvailability w) {
        long id = ++probeIds;
        w.probeSent(id);
        w.probeCompleted(id);
    }

    /** 수요만 오고 완료가 없는 표본 하나(2초). */
    private boolean starvedSample(LeaderWriteProgress progress, LeaderStallDetector detector) {
        for (int r = 0; r < 50; r++) {
            progress.recordAttempt();
        }
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        return detector.serviceable();
    }

    @Test
    void suspendedPausesTheVerdictAndResumeRestartsTheWindow() {
        WriteAvailability w = activeAvailability();
        LeaderWriteProgress progress = new LeaderWriteProgress();
        LeaderStallDetector detector = LeaderStallDetector.node(null, progress, STANDDOWN_AFTER,
                new SimpleMeterRegistry(), nanos::get);
        detector.pauseWhileSuspended(w);

        // 붕괴 관측을 시작한 뒤 동기 standby 를 잃는다.
        assertTrue(starvedSample(progress, detector));
        w.settingRead("*", false);
        for (int i = 0; i < 60; i++) {
            assertTrue(starvedSample(progress, detector), "SUSPENDED 동안은 리더를 바꿔도 풀리지 않으므로 강등하지 않는다");
        }

        // 재개. 중단 전과 중단 중의 수요는 창에서 빠진다. 재개 뒤 새로 45초가 지나야 판정한다.
        w.settingRead("\"patroni-db-2\"", false);
        fastProbe(w);
        fastProbe(w);
        nanos.addAndGet(1);
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
        boolean verdict = true;
        int samples = 0;
        while (verdict && samples < 40) {
            verdict = starvedSample(progress, detector);
            samples++;
        }
        assertFalse(verdict, "재개 뒤에도 붕괴가 이어지면 판정은 다시 동작한다");
        assertTrue(samples >= 23, "재개 전의 hold 가 이어지면 안 된다(재개 뒤 45초가 새로 필요): " + samples);
    }

    @Test
    void unreachableDoesNotPauseTheVerdict() {
        WriteAvailability w = activeAvailability();
        LeaderWriteProgress progress = new LeaderWriteProgress();
        LeaderStallDetector detector = LeaderStallDetector.node(null, progress, STANDDOWN_AFTER,
                new SimpleMeterRegistry(), nanos::get);
        detector.pauseWhileSuspended(w);
        w.checkFailed();
        w.checkFailed();
        assertFalse(w.isActive());

        boolean verdict = true;
        for (int i = 0; i < 40 && verdict; i++) {
            verdict = starvedSample(progress, detector);
        }
        assertFalse(verdict, "이 노드만 DB 에 못 닿는 경우일 수 있어 리더가 물러나야 한다");
    }
}

package kr.exchange.harness;

import kr.exchange.engine.WriteAvailability;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기동 재생 기록(recovery_runs)이 거래 일시 중단의 커밋 대기에 걸리지 않는지 본다(Patroni 설계 12절 '기동 중 쓰기'). 성공 행은 재개
 * 뒤로 미루고, C17 의 대상인 불일치 행은 미루지 않는다.
 */
class RecoveryRunRecorderDeferralTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);
    private long probeIds;

    private static final class CapturingRecorder extends RecoveryRunRecorder {
        final List<Row> written = new CopyOnWriteArrayList<>();

        CapturingRecorder() {
            super(null);
        }

        @Override
        void write(Row row) {
            written.add(row);
        }
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
        wall.addAndGet(d.toMillis());
    }

    private void resume(WriteAvailability w) {
        w.probeConnected();
        for (int i = 0; i < 2; i++) {
            long id = ++probeIds;
            w.probeSent(id);
            advance(Duration.ofMillis(10));
            w.probeCompleted(id);
        }
        advance(Duration.ofMillis(100));
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("시간 안에 이뤄지지 않음");
            }
            Thread.sleep(5);
        }
    }

    @Test
    void successRowsWaitForResumeAndMismatchRowsAreWrittenAtOnce() throws Exception {
        WriteAvailability w = WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS, nanos::get, wall::get);
        CapturingRecorder recorder = new CapturingRecorder();
        recorder.setWriteAvailability(w);
        assertFalse(w.isActive(), "기동 직후 탐지기는 첫 확인 탐지 전이다");

        recorder.recordSuccess(1, 10, 10, 5);
        recorder.recordSuccess(11, 20, 10, 5);
        assertEquals(0, recorder.written.size(), "ACTIVE 가 아니면 성공 행을 적지 않는다");
        assertEquals(2, recorder.deferredCount());

        recorder.recordMismatch(21, 25, "IllegalStateException: 재생 불일치", 7);
        assertEquals(1, recorder.written.size(), "불일치 행은 미루지 않는다. 기동 실패와 함께 사라지면 C17 이 못 본다");
        assertFalse(recorder.written.get(0).matched());

        resume(w);
        await(() -> recorder.written.size() == 3);
        assertEquals(0, recorder.deferredCount());
        assertEquals(1, recorder.written.get(1).fromSeq(), "미룬 순서대로 적는다");
        assertEquals(11, recorder.written.get(2).fromSeq());

        recorder.recordSuccess(26, 30, 5, 1);
        assertEquals(4, recorder.written.size(), "ACTIVE 면 바로 적는다");
    }

    @Test
    void deferredRowsAreCappedAndSurviveASecondSuspensionDuringFlush() throws Exception {
        WriteAvailability w = WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS, nanos::get, wall::get);
        CapturingRecorder recorder = new CapturingRecorder();
        recorder.setWriteAvailability(w);
        for (int i = 0; i < RecoveryRunRecorder.DEFERRED_MAX + 10; i++) {
            recorder.recordSuccess(i, i, 0, 0);
        }
        assertEquals(RecoveryRunRecorder.DEFERRED_MAX, recorder.deferredCount(), "상한을 넘는 성공 행은 버린다");

        // 재개 콜백이 돌기 전에 다시 중단되면 적지 않고 남긴다.
        recorder.flushDeferred();
        assertEquals(0, recorder.written.size());
        assertEquals(RecoveryRunRecorder.DEFERRED_MAX, recorder.deferredCount());

        resume(w);
        await(() -> recorder.written.size() == RecoveryRunRecorder.DEFERRED_MAX);
        assertEquals(0, recorder.written.get(0).fromSeq());
    }

    @Test
    void disabledDetectorWritesImmediately() {
        CapturingRecorder recorder = new CapturingRecorder();
        recorder.setWriteAvailability(WriteAvailability.disabled());
        recorder.recordSuccess(1, 2, 2, 1);
        assertEquals(1, recorder.written.size());
        assertEquals(0, recorder.deferredCount());
    }
}

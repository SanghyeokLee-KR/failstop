package kr.exchange.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 쓰기 가능 상태의 전이 규칙(DB 설계 §5.4-f 4, Patroni 설계 4-3). 탐지 스레드 대신 입력을 직접 넣고 시계를 손으로 민다.
 */
class WriteAvailabilityTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);
    private long probeIds;

    private WriteAvailability tracker() {
        return WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS, nanos::get, wall::get);
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
        wall.addAndGet(d.toMillis());
    }

    /** 탐지 하나를 보내고 took 뒤에 끝낸다. */
    private long probe(WriteAvailability w, Duration took) {
        long id = ++probeIds;
        w.probeSent(id);
        advance(took);
        w.probeCompleted(id);
        return id;
    }

    /** 기동 뒤 첫 탐지와 설정 읽기로 ACTIVE 를 만든다. */
    private WriteAvailability active() {
        WriteAvailability w = tracker();
        w.probeConnected();
        probe(w, Duration.ofMillis(10));
        probe(w, Duration.ofMillis(10));
        advance(Duration.ofMillis(100));
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
        return w;
    }

    @Test
    void startsUnreachableAndResumesOnlyAfterTwoFastProbesAndAFreshSetting() {
        WriteAvailability w = tracker();
        AtomicInteger resumed = new AtomicInteger();
        w.onResume(resumed::incrementAndGet);
        assertEquals(WriteAvailability.State.UNREACHABLE, w.view().state(), "첫 탐지 전에는 커밋이 끝나는지 모른다");
        assertEquals(WriteAvailability.REASON_DATABASE, w.view().reason());

        w.probeConnected();
        probe(w, Duration.ofMillis(10));
        probe(w, Duration.ofMillis(10));
        assertFalse(w.isActive(), "연속 탐지의 첫 탐지 뒤에 읽은 설정이 아직 없다");

        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
        assertEquals(0, w.generation(), "ACTIVE 에 들어가는 것은 세대를 바꾸지 않는다");
        assertEquals(1, resumed.get());
        assertNull(w.view().sinceMillis());
        assertNull(w.view().reason());
    }

    @Test
    void anyStandbySettingSuspendsAtOnceAndBlocksResumeEvenWithFastProbes() {
        WriteAvailability w = active();
        long gate = w.generation();

        w.settingRead("*", false);
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
        assertEquals(WriteAvailability.REASON_REPLICATION, w.view().reason());
        assertNotNull(w.view().sinceMillis());
        assertEquals(gate + 1, w.generation());
        assertEquals(1, w.suspensionEntries(WriteAvailability.REASON_REPLICATION));

        // 멤버가 아닌 복제 연결이 '*' 아래에서 커밋을 풀어 탐지가 빨라져도 재개하지 않는다(설계 2-6 P·M).
        probe(w, Duration.ofMillis(10));
        probe(w, Duration.ofMillis(10));
        w.settingRead("*", false);
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
    }

    @Test
    void slowProbeWithoutSyncRepStaysActiveAndCountsOncePerProbe() {
        WriteAvailability w = active();
        long id = ++probeIds;
        w.probeSent(id);
        advance(Duration.ofMillis(2500));
        assertEquals(id, w.overdueProbe());

        w.waitEventsRead(id, "ClientRead", "ClientRead");
        w.waitEventsRead(id, "ClientRead", null);
        assertTrue(w.isActive(), "DB 쪽 확인이 없는 느림은 이 노드만의 느림일 수 있다(검토 R3)");
        assertEquals(1, w.unconfirmedSlow());

        // 확인 연결이 제때 답하지 않은 것도 확인 없는 느림이다. 같은 탐지는 한 번만 센다.
        w.checkFailed();
        assertEquals(1, w.unconfirmedSlow());
        assertTrue(w.isActive());
    }

    @Test
    void syncRepReadTwiceSuspendsButOnceDoesNot() {
        WriteAvailability w = active();
        long id = ++probeIds;
        w.probeSent(id);
        advance(Duration.ofMillis(2500));

        w.waitEventsRead(id, "SyncRep", "ClientRead");
        assertTrue(w.isActive(), "0.5초 간격 두 번이 모두 SyncRep 이어야 한다");

        w.waitEventsRead(id, "SyncRep", "SyncRep");
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
        assertEquals(1, w.suspensions());
    }

    @Test
    void socketTimeoutOfAConfirmedWaitKeepsTheSuspensionUntilReconnectsActuallyFail() {
        WriteAvailability w = active();
        long id = ++probeIds;
        w.probeSent(id);
        advance(Duration.ofMillis(2500));
        w.waitEventsRead(id, "SyncRep", "SyncRep");
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
        long generation = w.generation();

        // 멤버가 스트리밍만 하고 flush 하지 않으면 Patroni 는 그 멤버를 동기로 둔다. '*' 없이 SyncRep 확인만 중단을 붙든다.
        for (int timeout = 1; timeout <= 3; timeout++) {
            advance(Duration.ofSeconds(60));
            w.probeFailed(id);
            assertEquals(WriteAvailability.State.SUSPENDED, w.view().state(), "소켓 제한 " + timeout + "번째");
            w.probeConnected();
            id = ++probeIds;
            w.probeSent(id);
            advance(Duration.ofMillis(2500));
            w.waitEventsRead(id, "SyncRep", "SyncRep");
            assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
        }
        assertEquals(WriteAvailability.REASON_REPLICATION, w.view().reason());
        assertEquals(generation, w.generation());

        w.probeFailed(id);
        w.probeFailed(++probeIds);
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state(), "다시 붙지 못한 시도 한 번은 불통이 아니다");
        w.probeFailed(++probeIds);
        assertEquals(WriteAvailability.State.UNREACHABLE, w.view().state(), "다시 붙지 못한 시도가 연달아 두 번");
        assertEquals(WriteAvailability.REASON_DATABASE, w.view().reason());
    }

    @Test
    void waitEventsOfAnEarlierProbeAreIgnored() {
        WriteAvailability w = active();
        long first = ++probeIds;
        w.probeSent(first);
        advance(Duration.ofMillis(2500));
        w.probeCompleted(first);
        long second = ++probeIds;
        w.probeSent(second);
        advance(Duration.ofMillis(2500));

        w.waitEventsRead(first, "SyncRep", "SyncRep");
        assertTrue(w.isActive(), "끝난 탐지의 대기 사건으로 중단을 정하지 않는다");
    }

    @Test
    void twoConsecutiveFailuresOnEitherConnectionMakeUnreachable() {
        WriteAvailability w = active();
        long id = ++probeIds;
        w.probeSent(id);
        w.probeFailed(id);
        assertTrue(w.isActive(), "한 번의 불통은 UNREACHABLE 이 아니다");
        probe(w, Duration.ofMillis(10));
        long again = ++probeIds;
        w.probeSent(again);
        w.probeFailed(again);
        assertTrue(w.isActive(), "성공한 탐지가 사이에 있으면 연달아가 아니다");

        w.checkFailed();
        w.checkFailed();
        assertEquals(WriteAvailability.State.UNREACHABLE, w.view().state());
        assertEquals(WriteAvailability.REASON_DATABASE, w.view().reason());
        assertEquals(1, w.generation());
    }

    @Test
    void recoveryModeCountsAsUnreachable() {
        WriteAvailability w = active();
        w.settingRead("", true);
        w.settingRead("", true);
        assertEquals(WriteAvailability.State.UNREACHABLE, w.view().state(),
                "Primary 가 아닌 노드에 붙었다(DB 전환 중). 쓰기를 받을 수 없다");
    }

    @Test
    void unreachableMovesToSuspendedWhenTheNewPrimaryHasNoSyncStandby() {
        WriteAvailability w = active();
        w.checkFailed();
        w.checkFailed();
        assertEquals(WriteAvailability.State.UNREACHABLE, w.view().state());
        long since = w.view().sinceMillis();

        advance(Duration.ofSeconds(20));
        w.settingRead("*", false);
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
        assertEquals(since, w.view().sinceMillis(), "중단 시각은 ACTIVE 를 떠난 시각이다");
        assertEquals(1, w.generation(), "비ACTIVE 사이의 이동은 세대를 바꾸지 않는다");
    }

    @Test
    void resumeWaitsForTheBacklogProbeAndTwoFreshFastProbes() {
        WriteAvailability w = active();
        AtomicInteger resumed = new AtomicInteger();
        w.onResume(resumed::incrementAndGet);
        long backlog = ++probeIds;
        w.probeSent(backlog);
        advance(Duration.ofMillis(2500));
        w.waitEventsRead(backlog, "SyncRep", "SyncRep");
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
        long gate = w.generation();

        advance(Duration.ofSeconds(60));
        w.probeCompleted(backlog);
        w.settingRead("\"patroni-db-2\"", false);
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state(), "밀린 탐지는 재개로 세지 않는다");

        probe(w, Duration.ofMillis(20));
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
        probe(w, Duration.ofMillis(20));
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state(), "연속의 첫 탐지 뒤에 읽은 설정이 필요하다");

        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
        assertEquals(1, resumed.get());
        assertFalse(w.stillActive(gate - 1), "중단을 넘은 요청의 세대는 맞지 않는다");
        assertTrue(w.stillActive(gate));
    }

    @Test
    void slowProbeBreaksTheResumeStreak() {
        WriteAvailability w = active();
        w.settingRead("*", false);
        w.settingRead("\"patroni-db-2\"", false);
        probe(w, Duration.ofMillis(20));
        probe(w, Duration.ofMillis(1500));
        w.settingRead("\"patroni-db-2\"", false);
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state(), "1초를 넘긴 탐지가 연속을 끊는다");
        probe(w, Duration.ofMillis(20));
        probe(w, Duration.ofMillis(20));
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
    }

    @Test
    void reconnectRaisesUnconfirmedUntilAProbeCompletesAndANonStarSettingFollows() {
        WriteAvailability w = active();
        assertTrue(w.definitiveAllowed());

        w.probeConnected();
        assertFalse(w.definitiveAllowed(), "탐지 연결이 끊겼다 붙으면 크래시 재시작을 배제하지 못한다");
        assertTrue(w.isActive(), "재확인 전 표시는 상태를 바꾸지 않는다");

        w.settingRead("\"patroni-db-2\"", false);
        assertFalse(w.definitiveAllowed(), "다시 붙은 뒤의 탐지가 끝나기 전의 설정은 근거가 아니다");
        probe(w, Duration.ofMillis(10));
        assertFalse(w.definitiveAllowed());
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.definitiveAllowed());
    }

    @Test
    void requeryGuardHoldsWhileSuspendedAndForTheGraceAfterResume() {
        WriteAvailability w = active();
        advance(Duration.ofSeconds(11));
        assertFalse(w.requeryGuarded());

        w.settingRead("*", false);
        assertTrue(w.requeryGuarded());
        w.settingRead("\"patroni-db-2\"", false);
        probe(w, Duration.ofMillis(10));
        probe(w, Duration.ofMillis(10));
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
        assertTrue(w.requeryGuarded(), "재개 뒤 10초는 거짓 404 를 막는다");
        advance(Duration.ofSeconds(9));
        assertTrue(w.requeryGuarded());
        advance(Duration.ofSeconds(2));
        assertFalse(w.requeryGuarded());
    }

    @Test
    void disabledNodeIsAlwaysActive() {
        WriteAvailability w = WriteAvailability.disabled();
        assertTrue(w.isActive());
        assertTrue(w.stillActive(0));
        assertTrue(w.definitiveAllowed());
        assertFalse(w.requeryGuarded());
        assertNull(w.view().reason());
        assertNull(w.view().sinceMillis());
        assertFalse(w.enabled());
    }

    @Test
    void startupWritesWaitForTheDetectorAndDeferPastASuspension() throws Exception {
        assertTrue(WriteAvailability.disabled().awaitActive(Duration.ZERO), "탐지기를 끈 노드는 기다리지 않는다");

        WriteAvailability w = tracker();
        assertFalse(w.awaitActive(Duration.ofMillis(50)), "기동 직후는 첫 확인 탐지 전이라 ACTIVE 가 아니다");

        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger failuresLeft = new AtomicInteger(1);
        boolean now = w.runWhenActive("시험 시드", Duration.ofMillis(50), () -> {
            runs.incrementAndGet();
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("첫 시도 실패");
            }
            ran.countDown();
        });
        assertFalse(now, "ACTIVE 가 아니면 재개 뒤로 미룬다");
        assertEquals(0, runs.get());

        // 첫 재개: 맡긴 쓰기가 실패한다. 다음 재개에 다시 한다.
        w.probeConnected();
        probe(w, Duration.ofMillis(10));
        probe(w, Duration.ofMillis(10));
        advance(Duration.ofMillis(100));
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runs.get() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, runs.get());

        w.settingRead("*", false);
        assertFalse(w.isActive());
        probe(w, Duration.ofMillis(10));
        probe(w, Duration.ofMillis(10));
        advance(Duration.ofMillis(100));
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(ran.await(5, java.util.concurrent.TimeUnit.SECONDS), "실패한 쓰기는 다음 재개에 다시 한다");
        assertEquals(2, runs.get());

        // 성공한 뒤의 재개에서는 다시 하지 않는다.
        w.settingRead("*", false);
        probe(w, Duration.ofMillis(10));
        probe(w, Duration.ofMillis(10));
        advance(Duration.ofMillis(100));
        w.settingRead("\"patroni-db-2\"", false);
        Thread.sleep(100);
        assertEquals(2, runs.get());

        AtomicInteger direct = new AtomicInteger();
        assertTrue(w.runWhenActive("바로 하는 시드", Duration.ZERO, direct::incrementAndGet));
        assertEquals(1, direct.get(), "ACTIVE 면 호출 스레드에서 바로 한다");
    }

    @Test
    void outstandingSecondsAndOverdueProbeFollowTheClock() {
        WriteAvailability w = active();
        assertEquals(0, w.outstandingSeconds());
        long id = ++probeIds;
        w.probeSent(id);
        advance(Duration.ofMillis(1500));
        assertEquals(-1, w.overdueProbe(), "2초 전에는 대기 사건을 읽지 않는다");
        assertEquals(1.5, w.outstandingSeconds(), 0.001);
        advance(Duration.ofMillis(600));
        assertEquals(id, w.overdueProbe());
    }
}

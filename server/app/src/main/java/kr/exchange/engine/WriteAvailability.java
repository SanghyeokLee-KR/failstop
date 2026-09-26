package kr.exchange.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * DB 쓰기 가능 상태(DB 설계 §5.4-f 4, API 설계 §2-5-e). 엄격 동기 복제에서 동기 standby 가 없으면 커밋이 끝나지 않는다. 그 사실을
 * 쓰기를 시작하기 전에 알려 쓰기 게이트, 게이트웨이의 세대 재확인, 리스 획득, 좀비 판정이 같은 답을 보게 한다.
 *
 * 입력은 WriteProbe 의 탐지 연결과 확인 연결이 준다. SUSPENDED 는 DB 가 커밋을 확인하지 않는다는 것을 DB 에서 확인했을 때만
 * 정한다. 확인 없는 느림은 이 노드만의 느림일 수 있어 ACTIVE 로 둔다. 전이 규칙과 근거는 Patroni 설계 4-3 이다.
 *
 * 입력 메서드는 탐지 스레드 둘이 부르므로 이 객체의 모니터로 직렬화한다. 요청 경로는 volatile 인 view 만 읽는다.
 */
public final class WriteAvailability {

    private static final Logger log = LoggerFactory.getLogger(WriteAvailability.class);
    private static final long AWAIT_STEP_MILLIS = 100;

    public enum State { ACTIVE, SUSPENDED, UNREACHABLE }

    /** 공개 상태의 이유(API 설계 §4-A-7). SUSPENDED 는 앞의 것, UNREACHABLE 은 뒤의 것이다. */
    public static final String REASON_REPLICATION = "replication_unavailable";
    public static final String REASON_DATABASE = "database_unavailable";

    // Patroni 가 동기 후보를 잃었을 때 거는 값. flush 위치를 보고하는 모든 복제 연결을 동기 대기 서버로 받으므로 이 값
    // 아래에서는 탐지가 빨라도 재개하지 않는다(설계 2-6 P·M).
    private static final String ANY_STANDBY = "*";
    private static final String SYNC_REP_WAIT = "SyncRep";
    // 탐지 연결이나 확인 연결의 DB 불통이 이만큼 연달아 나면 UNREACHABLE 이다.
    private static final int FAILURES_TO_UNREACHABLE = 2;

    /**
     * @param suspendAfter   탐지가 이보다 오래 끝나지 않으면 확인 연결이 탐지 백엔드의 대기 사건을 읽는다
     * @param resumeProbeMax 재개로 세는 탐지 한 번의 상한
     * @param resumeProbes   재개에 필요한 연속 탐지 수
     * @param requeryGrace   ACTIVE 로 돌아온 뒤 by-client 재조회의 거짓 404 를 409 로 막는 기간(API 설계 §2-5-e)
     */
    public record Settings(Duration suspendAfter, Duration resumeProbeMax, int resumeProbes, Duration requeryGrace) {

        public static final Settings DEFAULTS =
                new Settings(Duration.ofSeconds(2), Duration.ofSeconds(1), 2, Duration.ofSeconds(10));

        public Settings {
            if (suspendAfter == null || resumeProbeMax == null || requeryGrace == null
                    || suspendAfter.isNegative() || suspendAfter.isZero()
                    || resumeProbeMax.isNegative() || resumeProbeMax.isZero()
                    || requeryGrace.isNegative() || resumeProbes < 1) {
                throw new IllegalArgumentException("쓰기 가능 탐지 임계가 올바르지 않다");
            }
        }
    }

    /**
     * 한 시점의 상태. 전이마다 새로 만든다.
     *
     * @param sinceMillis     이 노드가 ACTIVE 를 떠난 시각(밀리초 UTC). ACTIVE 면 null
     * @param checkedAtMillis 마지막 탐지 결과의 시각
     * @param generation      ACTIVE 를 떠날 때마다 1 오른다. 게이트를 지난 요청이 쓰기 직전에 다시 대조한다
     * @param unconfirmed     탐지 연결이 끊긴 뒤 다시 확인된 탐지가 아직 없음('재확인 전')
     */
    public record View(State state, String reason, Long sinceMillis, long checkedAtMillis, long generation,
                       boolean unconfirmed) {

        public boolean active() {
            return state == State.ACTIVE;
        }
    }

    private final boolean enabled;
    private final Settings settings;
    private final LongSupplier nanoClock;
    private final LongSupplier wallClock;
    private final List<Runnable> resumeListeners = new CopyOnWriteArrayList<>();
    private final LongAdder unconfirmedSlow = new LongAdder();
    private final Map<String, LongAdder> suspensionEntries = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> deferredResponses = new ConcurrentHashMap<>();

    private volatile View view;
    private volatile long activeSinceNanos;
    private volatile long suspensions;
    // 보낸 뒤 아직 끝나지 않은 탐지의 번호와 보낸 시각. 없으면 -1 이다. 지표가 모니터 밖에서 읽는다.
    private volatile long outstandingId = -1;
    private volatile long outstandingSentNanos;

    private State state;
    private long generation;
    private boolean unconfirmed;
    private int probeFailures;
    private int checkFailures;
    private boolean anyStandby;
    private long settingReadNanos = Long.MIN_VALUE;
    private boolean syncRepConfirmed;
    private long unconfirmedCountedFor = -1;
    private int fastStreak;
    private long streakStartNanos;
    // 재확인 전 표시를 내릴 수 있게 된 시각(다시 붙은 뒤 첫 탐지가 끝난 시각). 그 뒤에 읽은 설정이 '*'가 아니면 내린다.
    private long confirmAfterNanos = Long.MAX_VALUE;
    private Long sinceMillis;
    private long checkedAtMillis;

    private WriteAvailability(boolean enabled, Settings settings, LongSupplier nanoClock, LongSupplier wallClock) {
        this.enabled = enabled;
        this.settings = settings;
        this.nanoClock = nanoClock;
        this.wallClock = wallClock;
        if (enabled) {
            // 첫 탐지 두 번이 끝나기 전에는 커밋이 끝나는지 모른다. 그동안 쓰기를 받지 않는다.
            this.state = State.UNREACHABLE;
            this.unconfirmed = true;
            this.sinceMillis = wallClock.getAsLong();
            this.checkedAtMillis = sinceMillis;
        } else {
            this.state = State.ACTIVE;
        }
        publish();
    }

    /** 탐지를 끈 노드. 늘 ACTIVE 이고 세대가 바뀌지 않는다. */
    public static WriteAvailability disabled() {
        return new WriteAvailability(false, Settings.DEFAULTS, System::nanoTime, System::currentTimeMillis);
    }

    public static WriteAvailability enabled(Settings settings) {
        return enabled(settings, System::nanoTime, System::currentTimeMillis);
    }

    /** 시간 소스를 주입한다. nanoClock 은 경과, wallClock 은 공개 상태의 시각(밀리초 UTC)이다. */
    public static WriteAvailability enabled(Settings settings, LongSupplier nanoClock, LongSupplier wallClock) {
        if (settings == null || nanoClock == null || wallClock == null) {
            throw new IllegalArgumentException("settings·clock은 필수");
        }
        return new WriteAvailability(true, settings, nanoClock, wallClock);
    }

    public boolean enabled() {
        return enabled;
    }

    public Settings settings() {
        return settings;
    }

    public View view() {
        View v = view;
        if (!enabled) {
            return new View(State.ACTIVE, null, null, wallClock.getAsLong(), 0, false);
        }
        return v;
    }

    public boolean isActive() {
        return view.state() == State.ACTIVE;
    }

    public boolean isSuspended() {
        return view.state() == State.SUSPENDED;
    }

    public long generation() {
        return view.generation();
    }

    /** 게이트를 지날 때의 세대가 그대로이고 지금도 ACTIVE 인가. 쓰기 직전에 부른다(DB 설계 §5.4-f 6). */
    public boolean stillActive(long gateGeneration) {
        View v = view;
        return v.state() == State.ACTIVE && v.generation() == gateGeneration;
    }

    /**
     * 저널에 보이는 행을 근거로 확정 응답(by-client 재조회 200, V47 커밋 회수 201)을 내도 되는가. ACTIVE 이고 재확인 전 표시가
     * 없어야 한다. 크래시 재시작 뒤에는 커밋을 기다리던 행이 복제 없이 보이기 때문이다(DB 설계 §5.4-f 3·6).
     */
    public boolean definitiveAllowed() {
        View v = view;
        return v.state() == State.ACTIVE && !v.unconfirmed();
    }

    /** ACTIVE 가 아니거나 ACTIVE 로 돌아온 지 유예 기간이 안 됐다. 이때 by-client 재조회는 거짓 404 를 내지 않는다. */
    public boolean requeryGuarded() {
        if (!enabled) {
            return false;
        }
        if (view.state() != State.ACTIVE) {
            return true;
        }
        return nanoClock.getAsLong() - activeSinceNanos < settings.requeryGrace().toNanos();
    }

    /** SUSPENDED 에 들어간 수. 좀비 판정이 이 값이 바뀐 것을 보고 창을 다시 세운다(DB 설계 §5.4-f 5). */
    public long suspensions() {
        return suspensions;
    }

    /** ACTIVE 로 돌아올 때 부른다. 탐지 스레드에서 불리므로 짧게 끝내야 한다. */
    public void onResume(Runnable listener) {
        resumeListeners.add(listener);
    }

    /**
     * 기동 쓰기용(Patroni 설계 12절 '기동 중 쓰기'). ACTIVE 가 될 때까지 max 동안 기다리고, 되면 true 다. 탐지기를 끄면 바로 true
     * 다. 켠 탐지기는 불통(재확인 전)에서 시작해 첫 확인 탐지를 마쳐야 ACTIVE 가 되므로 기동 직후에는 보통 두 탐지 주기를 기다린다.
     */
    public boolean awaitActive(Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (!isActive()) {
            long leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMillis <= 0) {
                return false;
            }
            Thread.sleep(Math.min(AWAIT_STEP_MILLIS, leftMillis));
        }
        return true;
    }

    /**
     * 기동 쓰기를 탐지기 뒤에 둔다. max 안에 ACTIVE 가 되면 호출 스레드에서 바로 하고 true 를 돌려준다. 아니면 다음 재개 때 다른
     * 스레드에서 한 번 하도록 맡기고 false 를 돌려준다. 중단 동안 재기동한 노드의 시드 쓰기가 커밋 대기에 걸려 연결을 쥔 채 기동과
     * 조회 제공을 멈추지 않게 한다. 맡긴 쓰기가 실패하면 그다음 재개에 다시 한다.
     */
    public boolean runWhenActive(String purpose, Duration max, Runnable action) throws InterruptedException {
        if (awaitActive(max)) {
            action.run();
            return true;
        }
        View now = view();
        log.warn("{}: 쓰기가 {}(이유 {}) 라 재개 뒤로 미룬다", purpose, now.state(), now.reason());
        AtomicBoolean started = new AtomicBoolean();
        Runnable once = () -> {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    action.run();
                    log.info("{}: 재개 뒤 마쳤다", purpose);
                } catch (RuntimeException e) {
                    started.set(false);
                    log.warn("{}: 재개 뒤 실패. 다음 재개에 다시 한다: {}", purpose, e.toString());
                }
            });
        };
        onResume(once);
        // 등록 전에 재개가 지나갔으면 콜백이 불리지 않는다. 등록 뒤 한 번 더 본다. 둘이 겹쳐도 started 가 한 번만 들인다.
        if (isActive()) {
            once.run();
        }
        return false;
    }

    /** 확정 응답을 409 로 미룬 수(path 는 requery·v47). */
    public void recordDeferred(String path) {
        deferredResponses.computeIfAbsent(path, k -> new LongAdder()).increment();
    }

    public long deferred(String path) {
        LongAdder adder = deferredResponses.get(path);
        return adder == null ? 0 : adder.sum();
    }

    /** 확인 안 된 느림의 수. 탐지가 느렸으나 DB 쪽 확인이 없어 ACTIVE 로 둔 탐지 수다. */
    public long unconfirmedSlow() {
        return unconfirmedSlow.sum();
    }

    /** ACTIVE 를 떠난 수(이유별). */
    public long suspensionEntries(String reason) {
        LongAdder adder = suspensionEntries.get(reason);
        return adder == null ? 0 : adder.sum();
    }

    /** 끝나지 않은 탐지가 기다린 초. 없으면 0 이다. */
    public double outstandingSeconds() {
        if (outstandingId < 0) {
            return 0;
        }
        return Math.max(0, nanoClock.getAsLong() - outstandingSentNanos) / 1e9;
    }

    /** 지금 끝나지 않은 탐지가 suspendAfter 를 넘겼으면 그 번호, 아니면 -1. 확인 연결이 대기 사건을 읽을지 정한다. */
    public long overdueProbe() {
        long id = outstandingId;
        if (id < 0 || nanoClock.getAsLong() - outstandingSentNanos <= settings.suspendAfter().toNanos()) {
            return -1;
        }
        return id;
    }

    /* ===== 탐지기 입력 ===== */

    /** 탐지 연결을 새로 열었다. 끊겼다 다시 붙은 것일 수 있으므로 재확인 전 표시를 세운다. */
    public synchronized void probeConnected() {
        unconfirmed = true;
        confirmAfterNanos = Long.MAX_VALUE;
        publish();
    }

    public synchronized void probeSent(long probeId) {
        outstandingSentNanos = nanoClock.getAsLong();
        outstandingId = probeId;
        syncRepConfirmed = false;
    }

    /** @return 끝난 탐지가 걸린 시간 */
    public Duration probeCompleted(long probeId) {
        Duration took;
        boolean resumed;
        synchronized (this) {
            long now = nanoClock.getAsLong();
            if (probeId != outstandingId) {
                return Duration.ZERO;
            }
            took = Duration.ofNanos(now - outstandingSentNanos);
            if (took.compareTo(settings.resumeProbeMax()) < 0) {
                if (fastStreak == 0) {
                    streakStartNanos = outstandingSentNanos;
                }
                fastStreak++;
            } else {
                fastStreak = 0;
            }
            clearOutstanding();
            probeFailures = 0;
            checkedAtMillis = wallClock.getAsLong();
            if (unconfirmed && confirmAfterNanos == Long.MAX_VALUE) {
                confirmAfterNanos = now;
            }
            resumed = evaluate();
        }
        notifyResumed(resumed);
        return took;
    }

    public void probeFailed(long probeId) {
        boolean resumed;
        synchronized (this) {
            // 커밋 대기를 DB 에서 확인한 탐지가 끊긴 것(대개 소켓 제한)은 DB 불통이 아니다. 세면 긴 중단 동안 소켓 제한마다
            // SUSPENDED 와 UNREACHABLE 을 오간다. 정말 불통이면 다시 붙는 시도가 곧바로 센다.
            boolean waitConfirmed = probeId == outstandingId && syncRepConfirmed;
            if (probeId == outstandingId) {
                clearOutstanding();
            }
            if (!waitConfirmed) {
                probeFailures++;
            }
            fastStreak = 0;
            checkedAtMillis = wallClock.getAsLong();
            resumed = evaluate();
        }
        notifyResumed(resumed);
    }

    /** 확인 연결이 synchronous_standby_names 와 pg_is_in_recovery() 를 읽었다. 복구 중이면 Primary 가 아니므로 불통으로 센다. */
    public void settingRead(String synchronousStandbyNames, boolean inRecovery) {
        boolean resumed;
        synchronized (this) {
            if (inRecovery) {
                checkFailures++;
            } else {
                checkFailures = 0;
                long now = nanoClock.getAsLong();
                anyStandby = synchronousStandbyNames != null && ANY_STANDBY.equals(synchronousStandbyNames.trim());
                settingReadNanos = now;
                if (unconfirmed && !anyStandby && now >= confirmAfterNanos) {
                    unconfirmed = false;
                }
            }
            resumed = evaluate();
        }
        notifyResumed(resumed);
    }

    public void checkFailed() {
        boolean resumed;
        synchronized (this) {
            checkFailures++;
            countUnconfirmedSlow(outstandingId);
            resumed = evaluate();
        }
        notifyResumed(resumed);
    }

    /**
     * 끝나지 않은 탐지의 백엔드 대기 사건을 확인 연결이 0.5초 간격으로 두 번 읽었다. 둘 다 SyncRep 이면 동기 대기 서버의 확인을
     * 기다리는 것이다. 모든 노드의 커밋에 같이 걸리는 클러스터의 사실이라 SUSPENDED 의 근거가 된다.
     */
    public void waitEventsRead(long probeId, String first, String second) {
        boolean resumed;
        synchronized (this) {
            if (probeId != outstandingId) {
                return;
            }
            checkFailures = 0;
            if (SYNC_REP_WAIT.equals(first) && SYNC_REP_WAIT.equals(second)) {
                syncRepConfirmed = true;
            } else {
                countUnconfirmedSlow(probeId);
            }
            resumed = evaluate();
        }
        notifyResumed(resumed);
    }

    /* ===== 판정 ===== */

    private void clearOutstanding() {
        outstandingId = -1;
        syncRepConfirmed = false;
    }

    private void countUnconfirmedSlow(long probeId) {
        if (probeId < 0 || state != State.ACTIVE || probeId == unconfirmedCountedFor
                || nanoClock.getAsLong() - outstandingSentNanos <= settings.suspendAfter().toNanos()) {
            return;
        }
        unconfirmedCountedFor = probeId;
        unconfirmedSlow.increment();
        log.warn("쓰기 가능 탐지가 {}ms 넘게 끝나지 않았으나 DB 쪽 확인이 없어 ACTIVE 로 둔다(이 노드만의 느림일 수 있다): probe={}",
                settings.suspendAfter().toMillis(), probeId);
    }

    private State decide() {
        if (checkFailures >= FAILURES_TO_UNREACHABLE) {
            return State.UNREACHABLE;
        }
        // DB 쪽 확인 둘은 탐지 연결의 불통보다 앞선다. 확인 연결이 답했고 DB 가 커밋을 확인하지 않는다는 것이 더 강한 사실이다.
        if (anyStandby || syncRepConfirmed) {
            return State.SUSPENDED;
        }
        if (probeFailures >= FAILURES_TO_UNREACHABLE) {
            return State.UNREACHABLE;
        }
        if (state == State.ACTIVE) {
            return State.ACTIVE;
        }
        // 밀린 탐지는 상한을 넘겨 연속을 끊었으므로 연속은 그 뒤에 보낸 탐지만 센다. 설정은 연속의 첫 탐지를 보낸 뒤에 읽은 값이어야 한다.
        if (fastStreak >= settings.resumeProbes() && settingReadNanos > streakStartNanos) {
            return State.ACTIVE;
        }
        return state;
    }

    /** @return 이 호출로 ACTIVE 에 들어갔으면 참 */
    private boolean evaluate() {
        State next = decide();
        State prev = state;
        if (next == prev) {
            publish();
            return false;
        }
        state = next;
        String reason = reasonOf(next);
        if (prev == State.ACTIVE) {
            generation++;
            sinceMillis = wallClock.getAsLong();
            suspensionEntries.computeIfAbsent(reason, k -> new LongAdder()).increment();
            log.warn("거래 일시 중단: {} -> {} (이유 {}, 세대 {})", prev, next, reason, generation);
        } else if (next == State.ACTIVE) {
            activeSinceNanos = nanoClock.getAsLong();
            sinceMillis = null;
            fastStreak = 0;
            log.info("거래 재개: {} -> ACTIVE (세대 {}, 재확인 전 {})", prev, generation, unconfirmed);
        } else {
            log.warn("거래 일시 중단 이유 변경: {} -> {} (이유 {})", prev, next, reason);
        }
        if (next == State.SUSPENDED) {
            suspensions++;
        }
        publish();
        return next == State.ACTIVE;
    }

    private static String reasonOf(State s) {
        return switch (s) {
            case ACTIVE -> null;
            case SUSPENDED -> REASON_REPLICATION;
            case UNREACHABLE -> REASON_DATABASE;
        };
    }

    private void publish() {
        view = new View(state, reasonOf(state), sinceMillis, checkedAtMillis, generation, unconfirmed);
    }

    private void notifyResumed(boolean resumed) {
        if (!resumed) {
            return;
        }
        for (Runnable listener : resumeListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("거래 재개 콜백 실패", e);
            }
        }
    }
}

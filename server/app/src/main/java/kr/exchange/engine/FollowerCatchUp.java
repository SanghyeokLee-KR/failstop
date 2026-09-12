package kr.exchange.engine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.journal.JournalCheckpoint;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 팔로워 따라잡기. 리더가 쌓은 저널을 체크포인트 상한으로 계속 재생해 이 노드의 엔진을 리더에
 * 붙여 둔다(2026-09-08).
 *
 * 왜 생겼나. 기동 재생이 체크포인트로 상한되면서 팔로워는 그 지점까지만 복원된 채 뜬다. 그 뒤
 * 리더가 쌓는 것을 아무도 따라잡지 않으면 팔로워 상태가 기동 시점에 얼어붙는다. 그러면 나중에
 * 승격할 때 재생할 양이 계속 늘고, 무엇보다 두 노드의 상태가 어긋나도 알 수 없다.
 *
 * 무엇이 '상태 일치 검증' 인가. 별도 장치가 아니라 재생 그 자체다. ReplayService 는 재생이
 * 만든 이벤트를 event_journal 의 행과 payload 바이트까지 대조한다. 이 노드의 엔진이 리더와
 * 다른 상태였다면 그 대조가 깨진다. 따라잡기가 조용히 도는 동안 그 대조가 매 명령마다 돈다.
 *
 * 스레드 안전성의 근거를 적어 둔다. MatchingEngineImpl 은 동기화가 없는 단일 라이터 전용
 * 자료구조다. 그래도 이 배선이 안전한 이유는 셋이다.
 *   1. 조회는 엔진을 직접 읽지 않는다. 라이터가 게시한 불변 Depth 사본을 읽는다
 *      (MarketDataService.publishEngineDepth).
 *   2. 팔로워에서는 접수 게이트가 닫혀 있어 단일 라이터 루프에 아무것도 들어오지 않는다.
 *      루프 스레드는 큐에서 대기만 한다.
 *   3. 승격은 fresh 엔진을 따로 만들어 설치하거나, 생략 경로에서는 이 엔진을 그대로 라이터에
 *      설치한다. 어느 쪽이든 그 시점에 아래 틱은 이미 물러났다. 승격이 시작되면 틱이 리더
 *      여부를 보고 즉시 반환하고, 라우터는 북을 복사해 담으므로 설치 뒤 라이터가 만지는 북은
 *      이 엔진의 것이 아니다.
 * 곧 이 스레드가 엔진을 만지는 구간에는 다른 만지는 주체가 없다. 셋 중 하나라도 바뀌면 이
 * 배선을 다시 봐야 한다.
 *
 * 재생 불일치는 이 노드의 상태가 리더와 갈렸다는 뜻이다. 따라잡기를 멈추고 지표로 올린다.
 * 프로세스를 죽이지 않는 이유는 승격이 어차피 fresh 엔진으로 다시 유도하기 때문이다. 갈린
 * 엔진이 쓰기에 쓰이는 경로가 없다.
 *
 * <p><b>불변식 M(물질화).</b> 이 엔진의 lastAppliedSeq 는 관측한 체크포인트의 commandSeq 를
 * 절대 넘지 않는다. 매 틱이 체크포인트를 새로 읽고 그 값으로만 상한하기 때문이다.
 *
 * 이 불변식이 실려 있는 곳이 하나 더 있다. PromotionCoordinator 는 설치 엔진이 이미 저널
 * head 에 닿았으면 승격 재생을 생략한다. 이 배선이 없을 때는 팔로워 엔진이 기동 시점에 얼어
 * 있어 그 생략이 사실상 도달하지 않았지만, 따라잡기가 붙으면 조용한 시장에서 도달한다.
 * 생략이 안전한 근거가 곧 M 이다. lastAppliedSeq >= head 이고 lastAppliedSeq <= 체크포인트이면
 * head 이하에 이벤트 없는 명령이 없다. 곧 메울 크래시 창이 없다.
 *
 * M 을 깨는 방법은 하나뿐이다. 상한을 매 틱 새로 읽지 않고 재사용하거나 저널 head 로 유도하는
 * 것이다. 그러면 생략이 미물질화 명령 위에서 접수를 열고 저널의 prefix 성질이 깨진다.
 */
public final class FollowerCatchUp implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FollowerCatchUp.class);

    /** 상태 게이지 값. 하네스가 이 축 하나로 팔로워 진행을 읽는다. */
    private static final int STATE_STOPPED = 0;
    private static final int STATE_FOLLOWING = 1;
    private static final int STATE_LEADER_IDLE = 2;
    private static final int STATE_DIVERGED = -1;

    private final ReplayService replayService;
    private final MatchingEngineImpl engine;
    private final LeaderLeaseService lease;
    private final Duration interval;
    private final Counter applied;
    private final AtomicLong lagCommands = new AtomicLong();
    private final AtomicLong lastTickMillis = new AtomicLong();
    private final AtomicInteger state = new AtomicInteger(STATE_STOPPED);
    // 엔진을 만지는 구간의 배타. 승격이 이 락을 잡아 진행 중인 틱이 끝난 것을 확인한 뒤 넘어간다.
    // 무기한 대기가 아니라 유계 대기다. 이유는 아래 stopForPromotion 주석에 있다.
    private final ReentrantLock engineLock = new ReentrantLock();
    // 승격 콜백이 진행 중인 틱을 기다리는 상한. 리스 스레드에서 도는 콜백이라 길게 잡으면
    // 리스 갱신이 그만큼 밀린다. TTL 10초에 비해 충분히 짧다.
    private static final long QUIESCE_WAIT_MILLIS = 200;
    // 이 노드가 리더가 됐다. 공유 엔진은 더 이상 이 노드의 팔로워 상태가 아니다.
    private volatile boolean promoted;
    private volatile ScheduledExecutorService scheduler;

    public FollowerCatchUp(ReplayService replayService, MatchingEngineImpl engine,
                           LeaderLeaseService lease, Duration interval, MeterRegistry registry) {
        if (replayService == null || engine == null || lease == null || interval == null
                || registry == null) {
            throw new IllegalArgumentException("replayService·engine·lease·interval·registry는 필수");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval은 양수여야 한다");
        }
        this.replayService = replayService;
        this.engine = engine;
        this.lease = lease;
        this.interval = interval;
        this.applied = Counter.builder("exchange.follower.catchup.applied")
                .description("팔로워가 따라잡기로 적용한 명령 수")
                .register(registry);
        Gauge.builder("exchange.follower.catchup.lag_commands", lagCommands, AtomicLong::doubleValue)
                .description("리더 저널 head 와 이 노드 엔진의 명령 격차")
                .register(registry);
        Gauge.builder("exchange.follower.catchup.state", state, AtomicInteger::doubleValue)
                .description("0 정지, 1 따라잡는 중, 2 리더라 쉬는 중, -1 상태 어긋남")
                .register(registry);
        Gauge.builder("exchange.follower.catchup.last_tick_seconds", lastTickMillis,
                        m -> m.get() / 1000.0)
                .description("마지막 따라잡기 한 번의 소요")
                .register(registry);
    }

    /** 관측·검증용. 현재 격차다. */
    public long lagCommands() {
        return lagCommands.get();
    }

    /** 관측·검증용. 상태 게이지와 같은 값이다. */
    public int state() {
        return state.get();
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "follower-catchup");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(this::tick, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        scheduler = s;
        state.set(STATE_FOLLOWING);
        log.info("팔로워 따라잡기 시작: 주기 {}", interval);
    }

    public void stop() {
        ScheduledExecutorService s;
        synchronized (this) {
            s = scheduler;
            scheduler = null;
        }
        if (s == null) {
            return;
        }
        s.shutdownNow();
        try {
            // 진행 중 틱의 종료를 기다린다. 기다리지 않으면 정지 후에도 엔진을 만지는 스레드가
            // 남아 승격 경로와 겹칠 수 있다.
            s.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (state.get() != STATE_DIVERGED) {
            state.set(STATE_STOPPED);
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * 승격 직전 정지. 진행 중인 틱이 끝난 것을 확인한 뒤 반환한다.
     *
     * 리스 획득 콜백에서 부르며 PromotionCoordinator 보다 먼저 등록해야 한다. 그래야 승격이
     * 공유 엔진 위에서 접수를 여는 경로(승격 재생 생략)와 이 스레드가 겹치지 않는다.
     *
     * 대기가 유계인 것이 계약이다. 이 콜백은 리스 스케줄러 스레드에서 돌고, 그 스레드가 막히면
     * 리스 갱신이 함께 막혀 정당한 새 리더가 리스를 잃는다. 2026-09-08 회차에서 실제로 그랬다.
     * 무기한 대기로 두었더니 승격 직후 갱신이 멈춰 다른 노드가 리스를 탈취했다. 상한 안에 끝나지
     * 않으면 플래그만 세우고 반환한다. 남는 창은 지금 도는 틱 하나이며, 그 틱은 자기 시작 시점의
     * 체크포인트까지만 적용하므로 불변식 M 을 깨지 않는다.
     *
     * 승격 뒤에는 다시 시작하지 않는다. 공유 엔진은 그 시점부터 이 노드의 상태가 아니다.
     * 승격은 fresh 엔진을 따로 만들어 설치하고, 생략 경로에서는 루프가 이 엔진을 이어받는다.
     * 어느 쪽이든 따라잡기가 만질 대상이 아니다.
     */
    public void stopForPromotion() {
        promoted = true;
        boolean quiesced = false;
        try {
            quiesced = engineLock.tryLock(QUIESCE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (quiesced) {
                engineLock.unlock();
            }
        }
        if (!quiesced) {
            // 플래그는 이미 섰으므로 다음 틱은 즉시 물러난다. 남는 창은 '지금 도는 틱' 하나뿐이다.
            log.warn("따라잡기 틱이 {}ms 안에 끝나지 않았다. 플래그로만 정지한다", QUIESCE_WAIT_MILLIS);
        }
        state.set(STATE_LEADER_IDLE);
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            // shutdown 이다. shutdownNow 로 진행 중 틱을 끊으면 엔진이 명령 중간에 남는다.
            s.shutdown();
        }
    }

    /** 한 번 따라잡는다. 테스트가 스케줄러 없이 직접 부른다. */
    public void tick() {
        if (promoted || lease.isLeader()) {
            // 리더는 단일 라이터 루프가 엔진을 만진다. 여기서 만지면 둘이 겹친다.
            state.set(STATE_LEADER_IDLE);
            return;
        }
        if (state.get() == STATE_DIVERGED) {
            return;
        }
        long startedNanos = System.nanoTime();
        engineLock.lock();
        try {
                if (promoted) {
                    // 락을 기다리는 사이 승격했다. 이 엔진은 더 이상 이 노드의 팔로워 상태가 아니다.
                    state.set(STATE_LEADER_IDLE);
                    return;
                }
                advance();
                state.set(STATE_FOLLOWING);
            } catch (kr.exchange.journal.ReplayMismatchException e) {
                // 이 노드의 엔진이 리더와 갈렸다. 더 적용하면 갈린 상태 위에 쌓는다.
                state.set(STATE_DIVERGED);
                log.error("팔로워 상태가 리더와 갈렸다. 따라잡기를 멈춘다: lastAppliedSeq={}, 사유={}",
                        engine.lastAppliedSeq(), e.getMessage());
            } catch (RuntimeException e) {
                // 왕복 실패는 갈림이 아니다. 다음 틱에 다시 시도한다.
                log.warn("따라잡기 틱 실패: {}", e.toString());
        } finally {
            lastTickMillis.set((System.nanoTime() - startedNanos) / 1_000_000L);
            engineLock.unlock();
        }
    }

    /** 체크포인트를 새로 읽고 그 지점까지만 전진한다. 불변식 M 이 여기서 성립한다. */
    private void advance() {
        JournalCheckpoint bound = replayService.checkpoint();
        long from = engine.lastAppliedSeq();
        if (bound.commandSeq() > from) {
            long watermark = replayService.watermarkAt(from);
            ReplayResult result = replayService.rebuild(engine, watermark, bound);
            if (result.replayedCommands() > 0) {
                applied.increment(result.replayedCommands());
            }
        }
        lagCommands.set(Math.max(0, replayService.journalHead() - engine.lastAppliedSeq()));
    }
}

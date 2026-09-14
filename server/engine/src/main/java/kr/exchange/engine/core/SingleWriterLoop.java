package kr.exchange.engine.core;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 단일 라이터 루프. 유계 명령 큐를 전용 스레드 하나가 소비해 엔진에 순차 적용하고,
 * 방출 이벤트를 sink(이벤트 저널 append)로 넘긴다.
 *
 * seq 연속성 계약(server/README.md M1-2 인계): 수신 seq가 그 마켓의 적용 seq+1이 아니면 그대로
 * 적용하지 않고 catch-up 소스(command_journal)에서 누락 구간을 회수한 뒤 진행한다.
 * 회수 불가나 sink 실패(fencing 확정 거부 포함)는 복구 대상이 아니라 fail-fast다.
 * fatalHandler 통지 후 즉시 정지하며, 재개는 재기동 재생 몫이다.
 *
 * 1차 구현은 ArrayBlockingQueue를 쓴다. Disruptor 교체는 벤치마크로 실측 이득이
 * 확인된 뒤에만 한다(API 설계 §1 매칭엔진 동시성 결정). 유계 큐인 이유: 큐 상한 도달 시
 * offer 실패를 게이트웨이 백프레셔(503)로 변환해 폭주 시 OOM 대신 명시적 거부를 택한다.
 */
public final class SingleWriterLoop {

    /** 이벤트 영속 훅. 예외는 전부 fail-fast로 취급한다(부분 성공 은폐 금지). */
    public interface EventSink {
        void accept(List<EngineEvent> events) throws Exception;
    }

    /** seq 갭 회수 소스. afterSeq 초과분을 seq 오름차순으로 반환한다. */
    public interface CatchUpSource {
        List<OrderCommand> readAfter(long afterSeq, int limit);
    }

    /**
     * 명령 경계 스냅샷 훅(복구 RTO 설계 §3-b). 한 명령의 적용·정산이 끝난 경계에서 라이터
     * 스레드가 호출한다. 구현은 인코딩만 라이터에서 하고 DB 영속화는 별도 워커로 넘겨야 한다
     * (핫패스 블로킹 금지). 스냅샷은 best-effort 최적화라 이 훅은 예외를 밖으로 던지지 않는다
     * (던지면 라이터 fail-fast를 오염시킨다). engine 모듈 순수성 유지를 위해 인터페이스만 둔다.
     */
    public interface SnapshotHook {
        void afterCommand(MatchingEngine engine);
    }

    private static final int CATCH_UP_BATCH = 500;

    private final BlockingQueue<OrderCommand> queue;
    // 승격 재생 후 fresh 엔진으로 교체하는 핸드오프 경로가 있어 volatile이다(installEngine).
    private volatile MatchingEngine engine;
    private final EventSink eventSink;
    private final CatchUpSource catchUpSource;
    private final Consumer<Throwable> fatalHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread writerThread;
    // 프로덕션 기본 null. fail-fast rethrow는 JVM 기본 경로로 간다. 테스트는 워커 스레드
    // 전용 핸들러를 배선해 재던진 Error를 캡처하고 기본 경로(테스트 인프라)로 새지 않게 한다.
    private volatile Thread.UncaughtExceptionHandler writerUncaughtExceptionHandler;
    // 기본 null(무동작). 프로덕션만 배선하고 직접 생성 테스트는 스냅샷 없이 돈다.
    private volatile SnapshotHook snapshotHook;

    // 매칭 적용 구간 계측(정본 13.5 한계 29). 단일 라이터 스레드만 쓰므로 경합이 없다.
    // Micrometer 를 이 모듈에 들이지 않는다. 엔진은 관측 라이브러리에 의존하지 않는
    // 것이 이 모듈의 경계다. 앱이 게이지로 읽어 간다(EngineConfig).
    //
    // 셋을 나눠 센다. apply 는 순수 매칭이고 sink 는 정산과 발행이며 hook 은 스냅샷
    // 판정이다. 직렬 구간에서 무엇이 지배하는지 이 셋을 갈라야 안다.
    private final java.util.concurrent.atomic.LongAdder applyNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder sinkNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder hookNanos =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder applyCount =
            new java.util.concurrent.atomic.LongAdder();
    // 갭 회수의 저널 왕복만 센다. 회수한 명령의 적용과 정산은 위 셋에 이미 들어가고, 이
    // 왕복은 라이터 직렬 구간 안에서 돌면서 셋 어디에도 잡히지 않는다(ADR 0022 0단계 (c)).
    private final java.util.concurrent.atomic.LongAdder catchUpCalls =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder catchUpNanos =
            new java.util.concurrent.atomic.LongAdder();

    public SingleWriterLoop(int queueCapacity, MatchingEngine engine, EventSink eventSink,
                            CatchUpSource catchUpSource, Consumer<Throwable> fatalHandler) {
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.engine = engine;
        this.eventSink = eventSink;
        this.catchUpSource = catchUpSource;
        this.fatalHandler = fatalHandler;
    }

    /**
     * 명령 투입. 큐가 가득 차면 즉시 false를 반환한다. 블로킹 대기를 하지 않는 이유는
     * 호출측(시퀀서)이 백프레셔를 지연이 아니라 명시적 거부로 처리해야 하기 때문이다.
     * false여도 저널에는 커밋된 상태이며 seq 연속성 검증의 catch-up이 회수한다.
     */
    public boolean offer(OrderCommand command) {
        return queue.offer(command);
    }

    /** 매칭 적용 누적 나노초. 앱이 게이지로 읽는다. */
    public long applyNanos() {
        return applyNanos.sum();
    }

    /** 이벤트 sink 누적 나노초(정산과 발행). */
    public long sinkNanos() {
        return sinkNanos.sum();
    }

    /** 스냅샷 훅 누적 나노초. */
    public long hookNanos() {
        return hookNanos.sum();
    }

    /** 적용 호출 수. 위 셋의 분모다. */
    public long applyCount() {
        return applyCount.sum();
    }

    /** 갭 회수 저널 왕복 수. */
    public long catchUpCalls() {
        return catchUpCalls.sum();
    }

    /** 갭 회수 저널 왕복 누적 나노초. */
    public long catchUpNanos() {
        return catchUpNanos.sum();
    }

    public int queueSize() {
        return queue.size();
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 승격 재생 후 fresh 엔진을 루프에 설치한다(복구 RTO 설계 §3-a 핸드오프). 호출측은
     * 접수를 닫아(PROMOTING) 새 명령 유입을 막은 상태에서만 부른다. 큐가 비어야 라이터가
     * 유휴이며(적용 중인 명령 없음) 스왑이 안전하다. stale 엔진 재적용을 막는 불변이다.
     */
    public void installEngine(MatchingEngine newEngine) {
        if (!queue.isEmpty()) {
            throw new IllegalStateException(
                    "큐가 비어 있지 않은 상태에서 엔진 교체 금지: queueSize=" + queue.size());
        }
        this.engine = newEngine;
    }

    /**
     * 워커 스레드 rethrow 관측 훅. 재던진 Error가 JVM 기본 처리 경로로 새지 않게 워커
     * 스레드 전용 핸들러를 배선한다. start() 이전에 설정한다. 미설정 시 기본 경로를 탄다.
     */
    public void setWriterUncaughtExceptionHandler(Thread.UncaughtExceptionHandler handler) {
        this.writerUncaughtExceptionHandler = handler;
    }

    /** 명령 경계 스냅샷 훅 배선(복구 RTO 설계 §3-b). start() 이전에 설정한다. */
    public void setSnapshotHook(SnapshotHook hook) {
        this.snapshotHook = hook;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::runLoop, "matching-engine-writer");
        t.setDaemon(false);
        Thread.UncaughtExceptionHandler ueh = writerUncaughtExceptionHandler;
        if (ueh != null) {
            t.setUncaughtExceptionHandler(ueh);
        }
        writerThread = t;
        t.start();
    }

    /**
     * 워커 스레드 종료 대기. rethrow가 uncaughtExceptionHandler로 전파 완료된 뒤 반환한다.
     * fail-fast 후 rethrow 완료를 결정적으로 기다려야 하는 검증 경로에서 쓴다.
     */
    public void awaitWriterTermination(long timeoutMillis) throws InterruptedException {
        Thread t = writerThread;
        if (t != null) {
            t.join(timeoutMillis);
        }
    }

    /** 정상 종료. 잔여 큐를 소진한 뒤 멈춘다. */
    public void stop() throws InterruptedException {
        running.set(false);
        Thread t = writerThread;
        if (t != null) {
            t.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    /**
     * 즉시 정지(self-shutdown). 리더십 상실 확정 시 잔여 큐 소진 없이 멈춘다.
     * 미처리분은 저널에 있으므로 다음 리더의 재생·catch-up이 회수한다(DB 설계 §5.4).
     */
    public void halt() {
        running.set(false);
        queue.clear();
        Thread t = writerThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    private void runLoop() {
        while (running.get() || !queue.isEmpty()) {
            OrderCommand command;
            try {
                command = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // 소비자가 사라지는 경로 중 여기만 플래그를 안 내렸다. 접수 게이트가
                // isRunning()을 소비자 생존 근거로 쓰므로 플래그 정확도가 곧 보증 강도다.
                running.set(false);
                Thread.currentThread().interrupt();
                return;
            }
            if (command == null) {
                continue;
            }
            try {
                process(command);
            } catch (Throwable t) {
                // Exception뿐 아니라 Error(OOM·AssertionError·NoClassDefFoundError 등)도 잡는다.
                // Error를 흘리면 라이터 스레드만 조용히 죽고 running=true가 남아, 접수는
                // ACK되는데 소비자가 없는 반쪽 리더가 되고 demote도 발화하지 않는다.
                queue.clear();
                try {
                    fatalHandler.accept(t);
                } finally {
                    // isRunning=false 관측 시점에 통지가 끝나 있도록 통지 후에 내린다.
                    running.set(false);
                }
                if (t instanceof Error) {
                    // 강등·정지를 마친 뒤 JVM 치명 상태를 은폐하지 않도록 스레드를 실제로 종료시킨다.
                    throw (Error) t;
                }
                return;
            }
        }
    }

    private void process(OrderCommand command) throws Exception {
        long last = engine.appliedSeq(command.marketId());
        if (command.seq() <= last) {
            // 재전송·catch-up 중복 공급. 이중 적용 방지 규약으로 무시한다.
            return;
        }
        if (command.seq() > last + 1) {
            catchUpTo(command);
            if (command.seq() <= engine.appliedSeq(command.marketId())) {
                return;
            }
        }
        applyAndSink(command);
    }

    private void catchUpTo(OrderCommand command) throws Exception {
        long target = command.seq();
        int marketId = command.marketId();
        // 대역 채번에서는 마켓 사이 seq 대소가 적용 순서가 아니다. 그래서 이 마켓 커서부터 읽고 이 마켓 행만 적용한다.
        // 전 마켓 최댓값에서 읽으면 레거시 누락분을 건너뛰고, 남의 마켓 행을 적용하면 그 마켓 커서가 제 누락분 위로 뛴다.
        long from = engine.appliedSeq(marketId);
        // 종료는 적용 커서가 아니라 읽은 위치로 본다. 남의 마켓 행만 든 배치가 와도 위치가 전진해 같은 배치를 다시 읽지 않는다.
        while (from < target - 1) {
            long t0 = System.nanoTime();
            List<OrderCommand> batch = catchUpSource.readAfter(from, CATCH_UP_BATCH);
            catchUpNanos.add(System.nanoTime() - t0);
            catchUpCalls.increment();
            if (batch.isEmpty()) {
                // 저널이 갭을 못 메우면 순서 보장이 깨진 것이다. 계속 진행은 무음 유실이 된다.
                throw new IllegalStateException("catch-up 실패: market=" + marketId
                        + ", from=" + from + ", target=" + target);
            }
            for (OrderCommand c : batch) {
                if (c.marketId() == marketId) {
                    applyAndSink(c);
                }
                from = c.seq();
                if (from >= target - 1) {
                    break;
                }
            }
        }
    }

    private void applyAndSink(OrderCommand command) throws Exception {
        long t0 = System.nanoTime();
        List<EngineEvent> events = engine.apply(command);
        long t1 = System.nanoTime();
        if (!events.isEmpty()) {
            eventSink.accept(events);
        }
        long t2 = System.nanoTime();
        applyNanos.add(t1 - t0);
        sinkNanos.add(t2 - t1);
        applyCount.increment();
        // 정산 커밋 후 이벤트 경계에서 스냅샷 캡처 판정(§3-b). 훅은 예외를 밖으로 던지지 않는다.
        SnapshotHook hook = snapshotHook;
        if (hook != null) {
            hook.afterCommand(engine);
            hookNanos.add(System.nanoTime() - t2);
        }
    }
}

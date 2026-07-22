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
 * seq 연속성 계약(server/README.md M1-2 인계): 수신 seq가 lastApplied+1이 아니면 그대로
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

    /** seq 갭 회수 소스. lastAppliedSeq 초과분을 seq 오름차순으로 반환한다. */
    public interface CatchUpSource {
        List<OrderCommand> readAfter(long lastAppliedSeq, int limit);
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

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::runLoop, "matching-engine-writer");
        t.setDaemon(false);
        writerThread = t;
        t.start();
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
                // Error를 흘리면 라이터 스레드만 조용히 죽고 running=true가 남아 isRunning()이
                // 계속 참을 반환한다. 그러면 접수는 ACK되는데 소비자가 없는 반쪽 리더가 되고
                // demote도 발화하지 않는다(전문가 감사 high). 통지·정지를 먼저 강제한다.
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
        long last = engine.lastAppliedSeq();
        if (command.seq() <= last) {
            // 재전송·catch-up 중복 공급. 이중 적용 방지 규약으로 무시한다.
            return;
        }
        if (command.seq() > last + 1) {
            catchUpTo(command.seq());
            if (command.seq() <= engine.lastAppliedSeq()) {
                return;
            }
        }
        applyAndSink(command);
    }

    private void catchUpTo(long targetSeq) throws Exception {
        while (engine.lastAppliedSeq() < targetSeq - 1) {
            List<OrderCommand> batch = catchUpSource.readAfter(engine.lastAppliedSeq(), CATCH_UP_BATCH);
            if (batch.isEmpty()) {
                // 저널이 갭을 못 메우면 순서 보장이 깨진 것이다. 계속 진행은 무음 유실이 된다.
                throw new IllegalStateException("catch-up 실패: lastApplied="
                        + engine.lastAppliedSeq() + ", target=" + targetSeq);
            }
            for (OrderCommand c : batch) {
                applyAndSink(c);
                if (engine.lastAppliedSeq() >= targetSeq - 1) {
                    break;
                }
            }
        }
    }

    private void applyAndSink(OrderCommand command) throws Exception {
        List<EngineEvent> events = engine.apply(command);
        if (!events.isEmpty()) {
            eventSink.accept(events);
        }
    }
}

package kr.exchange.admin.msp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 관제센터 전용 스레드. 스프링 기본 스케줄러를 쓰지 않는다. 학교의 관리 JVM 은 봇 JVM 이라 봇 명령 소비와
 * 상태 발행이 그 스케줄러에서 도는데, 원천 호출이 그 줄을 붙들면 봇 제어가 밀린다(설계 2-1 (5)).
 * 실행기를 빈으로 내놓지 않는 이유도 같다. 스프링은 ScheduledExecutorService 빈을 @Scheduled 에 쓴다.
 */
public class MspWorkers implements AutoCloseable {

    // Prometheus 질의 묶음을 한 번에 이만큼씩 던진다(설계 4-1).
    static final int QUERY_THREADS = 4;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "msp-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger queryThread = new AtomicInteger();
    private final ExecutorService queries = Executors.newFixedThreadPool(QUERY_THREADS, r -> {
        Thread t = new Thread(r, "msp-prom-" + queryThread.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    // AI 판단 하나와 관찰 하나. 모델 호출이 수십 초 걸려도 관찰 창과 모드 만료가 밀리지 않게 둘로 둔다.
    static final int AI_THREADS = 2;
    private final AtomicInteger aiThread = new AtomicInteger();
    private final ScheduledExecutorService ai = Executors.newScheduledThreadPool(AI_THREADS, r -> {
        Thread t = new Thread(r, "msp-ai-" + aiThread.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public ExecutorService queries() {
        return queries;
    }

    // 상담이 맡기는 Ollama 모델 적재 하나. 적재는 수십 초 걸리므로 요청 스레드와 판단 스레드를 붙잡지 않는다.
    private final ExecutorService llmLoader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "msp-llm-load");
        t.setDaemon(true);
        return t;
    });

    public ScheduledExecutorService ai() {
        return ai;
    }

    public ExecutorService llmLoader() {
        return llmLoader;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        queries.shutdownNow();
        ai.shutdownNow();
        llmLoader.shutdownNow();
    }
}

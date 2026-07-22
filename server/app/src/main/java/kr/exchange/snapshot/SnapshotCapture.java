package kr.exchange.snapshot;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.core.MatchingEngine;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.core.SnapshotCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 주기 스냅샷 캡처(복구 RTO 설계 §3-b). SingleWriterLoop 명령 경계 훅으로, 라이터 스레드에서
 * 정책 판정 + 결정론 인코딩만 하고 DB 영속화는 별도 워커 스레드가 수행한다. 매칭 핫패스에
 * 블로킹·IO를 넣지 않는다(하네스 T3-A 순수성).
 *
 * 정책: 직전 캡처 이후 명령 수가 commandInterval 이상이거나 경과 시간이 timeInterval 이상이면
 * 캡처한다. 캡처는 마켓별 OrderBookImpl을 encode + state_checksum으로 동결해 유계 큐로 넘기고
 * 즉시 복귀한다. 큐가 차면 드롭한다(best-effort, 다음 캡처가 대체하고 최악은 tail이 길어질 뿐
 * 정확성 무손실). 워터마크는 드롭 시에도 전진시켜 라이터가 매 명령 재인코딩하지 않게 한다.
 *
 * 스냅샷은 저널 파생·리더 독립(seq N 상태는 결정론상 동일)이라 워커의 INSERT에 리스 검증을
 * 걸지 않는다. 채택 게이트가 로드 시점에 무결·버전을 재확인한다.
 *
 * afterCommand는 예외를 밖으로 던지지 않는다(SnapshotHook 계약). 인코딩·큐잉 실패는 삼키고
 * 계량만 한다. 스냅샷 실패가 라이터 fail-fast를 오염시키면 안 된다.
 */
public class SnapshotCapture implements SingleWriterLoop.SnapshotHook, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCapture.class);
    private static final Captured POISON = new Captured(-1, -1, List.of());

    private final SnapshotStore store;
    private final LeaderLeaseService lease;
    private final long commandInterval;
    private final long timeIntervalNanos;
    private final BlockingQueue<Captured> queue;
    private final Thread worker;

    // 라이터 스레드 전용 정책 상태. 단일 스레드 접근이라 동기화가 없다.
    private long lastCapturedSeq;
    private long lastCaptureNanos = System.nanoTime();

    private final AtomicLong capturedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong persistFailedCount = new AtomicLong();
    private final AtomicLong lastPersistedSeq = new AtomicLong();

    public SnapshotCapture(SnapshotStore store, LeaderLeaseService lease,
                           long commandInterval, java.time.Duration timeInterval, int queueCapacity,
                           MeterRegistry registry) {
        this.store = store;
        this.lease = lease;
        this.commandInterval = commandInterval;
        this.timeIntervalNanos = timeInterval.toNanos();
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        if (registry != null) {
            // 단조 누적치는 Counter로 노출해 하네스가 rate()로 증가율(드롭·영속화 실패 급증)을
            // 감시하게 한다. AtomicLong을 소스로 두는 FunctionCounter라 증분 경로는 그대로다.
            FunctionCounter.builder("exchange.snapshot.captured", capturedCount, AtomicLong::doubleValue)
                    .description("라이터 명령 경계에서 큐잉된 스냅샷 캡처 수").register(registry);
            FunctionCounter.builder("exchange.snapshot.dropped", droppedCount, AtomicLong::doubleValue)
                    .description("유계 큐 포화·인코딩 실패로 드롭된 스냅샷 캡처 수").register(registry);
            FunctionCounter.builder("exchange.snapshot.persist_failed", persistFailedCount, AtomicLong::doubleValue)
                    .description("워커 스레드의 스냅샷 영속화 실패 수").register(registry);
            // last_persisted_seq는 진행 지표라 게이지로 둔다. 값이 멈추면(정지) 워커 정체·영속화
            // 실패 지속을 뜻하므로 하네스는 이 게이지의 정지를 알림 조건으로 쓴다.
            registry.gauge("exchange.snapshot.last_persisted_seq", lastPersistedSeq);
        }
        this.worker = new Thread(this::drain, "snapshot-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void afterCommand(MatchingEngine engine) {
        try {
            long seq = engine.lastAppliedSeq();
            if (seq <= lastCapturedSeq) {
                return;
            }
            long now = System.nanoTime();
            boolean byCommands = seq - lastCapturedSeq >= commandInterval;
            boolean byTime = now - lastCaptureNanos >= timeIntervalNanos;
            if (!byCommands && !byTime) {
                return;
            }
            Optional<LeaderIdentity> leader = lease.current();
            if (leader.isEmpty()) {
                // 비리더는 라이터가 돌지 않아 도달하지 않지만 방어적으로 캡처를 건너뛴다.
                return;
            }
            // 프로덕션 라이터 엔진은 항상 MatchingEngineImpl이다(마켓별 북 접근에 필요).
            MatchingEngineImpl impl = (MatchingEngineImpl) engine;
            List<SnapshotStore.MarketState> markets = new ArrayList<>();
            for (int marketId : impl.markets()) {
                OrderBookImpl book = impl.orderBook(marketId);
                markets.add(new SnapshotStore.MarketState(
                        marketId, SnapshotCodec.encode(book), book.stateChecksum()));
            }
            lastCapturedSeq = seq;
            lastCaptureNanos = now;
            if (queue.offer(new Captured(seq, leader.get().epoch(), markets))) {
                capturedCount.incrementAndGet();
            } else {
                droppedCount.incrementAndGet();
            }
        } catch (RuntimeException e) {
            // best-effort. 인코딩·큐잉 실패는 라이터를 세우지 않는다.
            droppedCount.incrementAndGet();
            log.warn("스냅샷 캡처 실패(무시): {}", e.getMessage());
        }
    }

    private void drain() {
        while (true) {
            Captured captured;
            try {
                captured = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (captured == POISON) {
                return;
            }
            try {
                store.persist(captured.seq(), captured.epoch(), captured.markets());
                lastPersistedSeq.set(captured.seq());
            } catch (RuntimeException e) {
                // 스냅샷 영속화 실패는 정확성과 무관하다(다음 캡처가 대체). 계량 후 계속.
                persistFailedCount.incrementAndGet();
                log.warn("스냅샷 영속화 실패(무시): seq={}: {}", captured.seq(), e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        queue.clear();
        queue.offer(POISON);
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Captured(long seq, long epoch, List<SnapshotStore.MarketState> markets) {
    }
}

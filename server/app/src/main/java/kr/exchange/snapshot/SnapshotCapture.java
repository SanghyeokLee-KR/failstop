package kr.exchange.snapshot;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 마켓 하나의 주기 스냅샷 캡처(복구 RTO 설계 §3-b). 그 마켓 라이터의 명령 경계 훅으로, 라이터 스레드에서 정책 판정과
 * 결정론 인코딩만 하고 DB 영속화는 SnapshotPersister 가 한다. 매칭 핫패스에 블로킹·IO를 넣지 않는다(하네스 T3-A 순수성).
 *
 * 정책: 이 마켓 커서가 직전 캡처 이후 commandInterval 이상 나아갔거나 timeInterval 이 지났으면 캡처한다. 샤드마다 하나를
 * 둔다. 한 인스턴스를 여러 샤드에 걸면 커서가 가장 높은 마켓의 캡처가 나머지 마켓의 판정을 막는다(ADR 0022 7단계).
 * 대기 중인 캡처가 queueCapacity 에 닿으면 드롭한다(best-effort). 워터마크는 드롭 시에도 전진시켜 라이터가 매 명령
 * 재인코딩하지 않게 한다.
 *
 * afterCommand는 예외를 밖으로 던지지 않는다(SnapshotHook 계약). 스냅샷 실패가 라이터 fail-fast를 오염시키면 안 된다.
 */
public class SnapshotCapture implements SingleWriterLoop.SnapshotHook {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCapture.class);

    private final SnapshotPersister persister;
    private final LeaderLeaseService lease;
    private final int marketId;
    private final long commandInterval;
    private final long timeIntervalNanos;
    private final int queueCapacity;

    // 라이터 스레드 전용 정책 상태. 단일 스레드 접근이라 동기화가 없다.
    private long lastCapturedSeq;
    private long lastCaptureNanos = System.nanoTime();

    // 제출했지만 워커가 아직 끝내지 않은 캡처 수. 라이터가 올리고 워커가 내린다.
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong capturedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong persistFailedCount = new AtomicLong();
    private final AtomicLong lastPersistedSeq = new AtomicLong();

    public SnapshotCapture(SnapshotPersister persister, LeaderLeaseService lease, int marketId,
                           long commandInterval, Duration timeInterval, int queueCapacity,
                           MeterRegistry registry) {
        this.persister = persister;
        this.lease = lease;
        this.marketId = marketId;
        this.commandInterval = commandInterval;
        this.timeIntervalNanos = timeInterval.toNanos();
        this.queueCapacity = queueCapacity;
        if (registry != null) {
            // 마켓 태그가 없으면 두 번째 마켓부터 미터 id 가 겹쳐 첫 마켓만 관측된다.
            String tag = String.valueOf(marketId);
            FunctionCounter.builder("exchange.snapshot.captured", capturedCount, AtomicLong::doubleValue)
                    .tag("market", tag)
                    .description("라이터 명령 경계에서 워커로 넘긴 스냅샷 캡처 수").register(registry);
            FunctionCounter.builder("exchange.snapshot.dropped", droppedCount, AtomicLong::doubleValue)
                    .tag("market", tag)
                    .description("대기 상한 도달·인코딩 실패로 드롭된 스냅샷 캡처 수").register(registry);
            FunctionCounter.builder("exchange.snapshot.persist_failed", persistFailedCount, AtomicLong::doubleValue)
                    .tag("market", tag)
                    .description("워커의 스냅샷 영속화 실패 수. fencing 거부를 포함한다").register(registry);
            // 진행 지표라 게이지로 둔다. 값이 멈추면 워커 정체·영속화 실패 지속을 뜻한다.
            Gauge.builder("exchange.snapshot.last_persisted_seq", lastPersistedSeq, AtomicLong::doubleValue)
                    .tag("market", tag)
                    .description("이 마켓에서 마지막으로 적재한 스냅샷의 커서").register(registry);
        }
    }

    @Override
    public void afterCommand(MatchingEngine engine) {
        try {
            long seq = engine.appliedSeq(marketId);
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
            lastCapturedSeq = seq;
            lastCaptureNanos = now;
            if (pending.get() >= queueCapacity) {
                droppedCount.incrementAndGet();
                return;
            }
            // 프로덕션 라이터 엔진은 항상 MatchingEngineImpl이다(마켓별 북 접근에 필요).
            OrderBookImpl book = ((MatchingEngineImpl) engine).orderBook(marketId);
            SnapshotStore.MarketState state = new SnapshotStore.MarketState(
                    marketId, seq, SnapshotCodec.encode(book), book.stateChecksum());
            pending.incrementAndGet();
            if (persister.submit(this, leader.get(), state)) {
                capturedCount.incrementAndGet();
            } else {
                pending.decrementAndGet();
                droppedCount.incrementAndGet();
            }
        } catch (RuntimeException e) {
            // best-effort. 인코딩·제출 실패는 라이터를 세우지 않는다.
            droppedCount.incrementAndGet();
            log.warn("스냅샷 캡처 실패(무시): market={}: {}", marketId, e.getMessage());
        }
    }

    /** 워커가 이 캡처의 적재를 마쳤다. */
    void persisted(long seq) {
        lastPersistedSeq.set(seq);
        pending.decrementAndGet();
    }

    /** 워커가 이 캡처의 적재에 실패했다. */
    void persistFailed() {
        persistFailedCount.incrementAndGet();
        pending.decrementAndGet();
    }
}

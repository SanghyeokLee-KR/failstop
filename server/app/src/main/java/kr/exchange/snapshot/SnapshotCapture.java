package kr.exchange.snapshot;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderTerm;
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
 * 기간 배선에서는 루프와 함께 기간마다 새로 만들고 그 기간으로 적재한다(DB 설계 §5.4-c 6). 지표 계수기는 마켓 자리가 쥐고
 * 기간 사이에 이어진다.
 *
 * afterCommand는 예외를 밖으로 던지지 않는다(SnapshotHook 계약). 스냅샷 실패가 라이터 fail-fast를 오염시키면 안 된다.
 */
public class SnapshotCapture implements SingleWriterLoop.SnapshotHook {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCapture.class);

    /** 마켓 하나의 캡처 지표. 한 번 등록하고 기간마다 새로 만드는 캡처가 이어 쓴다. */
    public static final class Counters {
        final AtomicLong captured = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        final AtomicLong persistFailed = new AtomicLong();
        final AtomicLong lastPersistedSeq = new AtomicLong();

        /** 마켓 태그로 등록한다. 태그가 없으면 두 번째 마켓부터 미터 id 가 겹쳐 첫 마켓만 관측된다. */
        public void register(MeterRegistry registry, int marketId) {
            String tag = String.valueOf(marketId);
            FunctionCounter.builder("exchange.snapshot.captured", captured, AtomicLong::doubleValue)
                    .tag("market", tag)
                    .description("라이터 명령 경계에서 워커로 넘긴 스냅샷 캡처 수").register(registry);
            FunctionCounter.builder("exchange.snapshot.dropped", dropped, AtomicLong::doubleValue)
                    .tag("market", tag)
                    .description("대기 상한 도달·인코딩 실패·거래 일시 중단으로 드롭된 스냅샷 캡처 수").register(registry);
            FunctionCounter.builder("exchange.snapshot.persist_failed", persistFailed, AtomicLong::doubleValue)
                    .tag("market", tag)
                    .description("워커의 스냅샷 영속화 실패 수. fencing 거부를 포함한다").register(registry);
            // 진행 지표라 게이지로 둔다. 값이 멈추면 워커 정체·영속화 실패 지속을 뜻한다.
            Gauge.builder("exchange.snapshot.last_persisted_seq", lastPersistedSeq, AtomicLong::doubleValue)
                    .tag("market", tag)
                    .description("이 마켓에서 마지막으로 적재한 스냅샷의 커서").register(registry);
        }

        public long captured() {
            return captured.get();
        }

        public long dropped() {
            return dropped.get();
        }
    }

    private final SnapshotPersister persister;
    // 기간 없는 종전 배선만 쓴다.
    private final LeaderLeaseService lease;
    private final LeaderTerm term;
    private final int marketId;
    private final long commandInterval;
    private final long timeIntervalNanos;
    private final int queueCapacity;
    private final Counters counters;

    // 라이터 스레드 전용 정책 상태. 단일 스레드 접근이라 동기화가 없다.
    private long lastCapturedSeq;
    private long lastCaptureNanos = System.nanoTime();

    // 제출했지만 워커가 아직 끝내지 않은 캡처 수. 라이터가 올리고 워커가 내린다.
    private final AtomicInteger pending = new AtomicInteger();

    /** 종전 배선. 적재 때 리스 서비스가 지금 쥔 epoch 를 쓴다. */
    public SnapshotCapture(SnapshotPersister persister, LeaderLeaseService lease, int marketId,
                           long commandInterval, Duration timeInterval, int queueCapacity,
                           MeterRegistry registry) {
        this(persister, lease, null, marketId, commandInterval, timeInterval, queueCapacity, new Counters());
        if (registry != null) {
            counters.register(registry, marketId);
        }
    }

    /** 기간 배선. 이 기간이 끝나면 캡처하지 않고, 적재는 이 기간의 epoch 로 판정을 받는다. */
    public SnapshotCapture(SnapshotPersister persister, LeaderTerm term, int marketId,
                           long commandInterval, Duration timeInterval, int queueCapacity, Counters counters) {
        this(persister, null, term, marketId, commandInterval, timeInterval, queueCapacity, counters);
    }

    private SnapshotCapture(SnapshotPersister persister, LeaderLeaseService lease, LeaderTerm term, int marketId,
                            long commandInterval, Duration timeInterval, int queueCapacity, Counters counters) {
        this.persister = persister;
        this.lease = lease;
        this.term = term;
        this.marketId = marketId;
        this.commandInterval = commandInterval;
        this.timeIntervalNanos = timeInterval.toNanos();
        this.queueCapacity = queueCapacity;
        this.counters = counters;
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
            Optional<LeaderIdentity> leader = leader();
            if (leader.isEmpty()) {
                // 비리더는 라이터가 돌지 않아 도달하지 않지만 방어적으로 캡처를 건너뛴다.
                return;
            }
            lastCapturedSeq = seq;
            lastCaptureNanos = now;
            if (pending.get() >= queueCapacity) {
                counters.dropped.incrementAndGet();
                return;
            }
            // 프로덕션 라이터 엔진은 항상 MatchingEngineImpl이다(마켓별 북 접근에 필요).
            OrderBookImpl book = ((MatchingEngineImpl) engine).orderBook(marketId);
            SnapshotStore.MarketState state = new SnapshotStore.MarketState(
                    marketId, seq, SnapshotCodec.encode(book), book.stateChecksum());
            pending.incrementAndGet();
            if (persister.submit(this, leader.get(), state)) {
                counters.captured.incrementAndGet();
            } else {
                pending.decrementAndGet();
                counters.dropped.incrementAndGet();
            }
        } catch (RuntimeException e) {
            // best-effort. 인코딩·제출 실패는 라이터를 세우지 않는다.
            counters.dropped.incrementAndGet();
            log.warn("스냅샷 캡처 실패(무시): market={}: {}", marketId, e.getMessage());
        }
    }

    private Optional<LeaderIdentity> leader() {
        if (term != null) {
            return term.isCurrent() ? Optional.of(term.identity()) : Optional.empty();
        }
        return lease.current();
    }

    /** 워커가 이 캡처의 적재를 마쳤다. */
    void persisted(long seq) {
        counters.lastPersistedSeq.set(seq);
        pending.decrementAndGet();
    }

    /** 워커가 거래 일시 중단 중이라 이 캡처를 적재하지 않았다. 다음 캡처가 대체하므로 드롭으로 센다. */
    void persistSkipped() {
        counters.dropped.incrementAndGet();
        pending.decrementAndGet();
    }

    /** 워커가 이 캡처의 적재에 실패했다. */
    void persistFailed() {
        counters.persistFailed.incrementAndGet();
        pending.decrementAndGet();
    }
}

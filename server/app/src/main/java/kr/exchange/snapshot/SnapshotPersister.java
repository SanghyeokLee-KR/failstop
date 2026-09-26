package kr.exchange.snapshot;

import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.WriteAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 스냅샷 영속화 워커(복구 RTO 설계 §3-b). 마켓마다 하나인 SnapshotCapture 가 인코딩한 스냅샷을 스레드 하나가 받은
 * 순서대로 적재한다. 워커를 마켓마다 두면 시간 간격 캡처가 거의 같은 순간에 마켓 수만큼 커넥션을 쓴다(ADR 0022 7단계).
 *
 * 큐에는 상한을 두지 않고 대기 수를 캡처마다 센다. 한 마켓의 적체가 다른 마켓의 캡처를 밀어내지 않게 하려는 것이다.
 * 영속화 실패는 정확성과 무관하다. 다음 캡처가 대체하고 최악은 tail 이 길어질 뿐이다.
 */
public class SnapshotPersister implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPersister.class);
    private static final Pending POISON = new Pending(null, null, null);

    private final SnapshotStore store;
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile boolean closed;
    private volatile WriteAvailability writeAvailability = WriteAvailability.disabled();

    public SnapshotPersister(SnapshotStore store) {
        this.store = store;
        this.worker = new Thread(this::drain, "snapshot-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 거래 일시 중단(DB 설계 §5.4-f 6) 동안에는 새 적재를 시작하지 않는다. 적재가 커밋 대기에 걸리면 워커와 기본 풀 연결 하나가
     * 재개까지 묶이고, 다음 캡처가 대체하므로 버려도 정확성과 무관하다.
     */
    public void setWriteAvailability(WriteAvailability availability) {
        this.writeAvailability = availability;
    }

    /** 라이터 스레드가 부르고 기다리지 않는다. 닫힌 뒤에는 받지 않고 거짓을 돌려준다. */
    boolean submit(SnapshotCapture owner, LeaderIdentity leader, SnapshotStore.MarketState state) {
        return !closed && queue.offer(new Pending(owner, leader, state));
    }

    private void drain() {
        while (true) {
            Pending pending;
            try {
                pending = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (pending == POISON) {
                return;
            }
            if (!writeAvailability.isActive()) {
                pending.owner().persistSkipped();
                continue;
            }
            try {
                store.persist(pending.leader(), List.of(pending.state()));
                pending.owner().persisted(pending.state().seq());
            } catch (RuntimeException e) {
                pending.owner().persistFailed();
                log.warn("스냅샷 영속화 실패(무시): market={}, seq={}: {}",
                        pending.state().marketId(), pending.state().seq(), e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        queue.clear();
        queue.offer(POISON);
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Pending(SnapshotCapture owner, LeaderIdentity leader, SnapshotStore.MarketState state) {
    }
}

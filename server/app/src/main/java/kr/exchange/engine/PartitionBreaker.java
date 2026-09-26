package kr.exchange.engine;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * 파티션 차단기(DB 설계 §5.4-c 10, ADR 0034 결정 12). 파티션 치명 강등이 창 안에서 상한에 이르면 이 노드에서 그 파티션을
 * 차단한다. 차단된 파티션은 재구성해 팔로워로 남지만 리스를 노리지 않는다. 운영자 해제나 재기동이 푼다.
 *
 * 같은 결함(재생하면 늘 같은 자리에서 실패하는 명령 등)이 되풀이되면 두 노드가 번갈아 그 파티션을 얻었다가 강등한다. 창과
 * 상한은 그 반복이 두세 바퀴 돌았을 때 멈추게 고른 값이다. 가용성을 먼저 잃는 쪽이라 두 노드가 모두 차단하면 그 파티션은
 * 리더가 없다(PartitionLeaderAbsent).
 */
public final class PartitionBreaker {

    private final int maxDemotions;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final Deque<Long> demotions = new ArrayDeque<>();
    private boolean open;

    public PartitionBreaker(int maxDemotions, Duration window) {
        this(maxDemotions, window, System::nanoTime);
    }

    PartitionBreaker(int maxDemotions, Duration window, LongSupplier nanoClock) {
        if (maxDemotions < 1 || window == null || window.isZero() || window.isNegative() || nanoClock == null) {
            throw new IllegalArgumentException("maxDemotions는 1 이상, window는 양수여야 한다");
        }
        this.maxDemotions = maxDemotions;
        this.windowNanos = window.toNanos();
        this.nanoClock = nanoClock;
    }

    /**
     * 강등 하나를 기록한다. 차단기가 세지 않는 사유는 기록하지 않는다.
     *
     * @return 기록 뒤 차단 상태
     */
    public synchronized boolean record(DemotionReason reason) {
        if (!reason.countsTowardBreaker()) {
            return open;
        }
        long now = nanoClock.getAsLong();
        demotions.addLast(now);
        prune(now);
        if (demotions.size() >= maxDemotions) {
            open = true;
        }
        return open;
    }

    public synchronized boolean isOpen() {
        return open;
    }

    /** 창 안에서 센 강등 수. */
    public synchronized int count() {
        prune(nanoClock.getAsLong());
        return demotions.size();
    }

    /** 운영자 해제. 창 안의 기록도 지운다. 지우지 않으면 다음 강등 한 번에 다시 차단된다. */
    public synchronized void reset() {
        open = false;
        demotions.clear();
    }

    private void prune(long now) {
        while (!demotions.isEmpty() && now - demotions.peekFirst() > windowNanos) {
            demotions.removeFirst();
        }
    }
}

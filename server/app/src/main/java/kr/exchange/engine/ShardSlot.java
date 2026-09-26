package kr.exchange.engine;

import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.snapshot.SnapshotCapture;

/**
 * 마켓 하나의 라이터 자리(S4 설계 4-9). 자리는 생명주기 동안 바뀌지 않고, 루프와 엔진은 설치마다 기간에 묶여 새로 들어온다.
 *
 * 지표를 자리에 묶는 이유. Micrometer 는 같은 이름·태그의 미터를 다시 등록하면 기존 미터를 돌려주므로, 루프 객체에 묶은 지표는
 * 재구성으로 루프를 새로 만들면 옛 루프에 묶인 채 남는다. 누적 나노초·호출 수는 물러난 루프의 마지막 값을 자리의 누계에 더해
 * 단조로 잇는다. 물러난 루프가 halt 뒤에 늦게 올린 몫은 세지 않는다.
 */
public final class ShardSlot {

    /** 자리의 한 시점. 루프와 그 전까지 물러난 루프들의 누계를 함께 바꿔 읽기가 한 시점을 보게 한다. */
    private record Current(WriterRouter.Shard shard, LeaderTerm term, long applyNanos, long sinkNanos,
                           long hookNanos, long applyCount, long catchUpCalls, long catchUpNanos) {
    }

    private final int marketId;
    private final SnapshotCapture.Counters snapshotCounters = new SnapshotCapture.Counters();
    private volatile Current current = new Current(null, null, 0, 0, 0, 0, 0, 0);

    ShardSlot(int marketId) {
        this.marketId = marketId;
    }

    public int marketId() {
        return marketId;
    }

    /** 지금 루프와 엔진. 아직 설치하지 않았으면 null 이다. */
    public WriterRouter.Shard shard() {
        return current.shard();
    }

    /** 지금 루프가 묶인 기간. 기간 없는 종전 배선이면 null 이다. */
    public LeaderTerm term() {
        return current.term();
    }

    /** 스냅샷 캡처 지표. 기간마다 새로 만드는 캡처가 이 계수기를 이어 쓴다. */
    public SnapshotCapture.Counters snapshotCounters() {
        return snapshotCounters;
    }

    synchronized void replace(WriterRouter.Shard next, LeaderTerm term) {
        Current c = current;
        SingleWriterLoop prev = c.shard() == null ? null : c.shard().loop();
        if (prev == null || prev == next.loop()) {
            current = new Current(next, term, c.applyNanos(), c.sinkNanos(), c.hookNanos(), c.applyCount(),
                    c.catchUpCalls(), c.catchUpNanos());
            return;
        }
        current = new Current(next, term,
                c.applyNanos() + prev.applyNanos(), c.sinkNanos() + prev.sinkNanos(),
                c.hookNanos() + prev.hookNanos(), c.applyCount() + prev.applyCount(),
                c.catchUpCalls() + prev.catchUpCalls(), c.catchUpNanos() + prev.catchUpNanos());
    }

    public int queueSize() {
        WriterRouter.Shard s = current.shard();
        return s == null ? 0 : s.loop().queueSize();
    }

    public long applyNanos() {
        Current c = current;
        return c.applyNanos() + (c.shard() == null ? 0 : c.shard().loop().applyNanos());
    }

    public long sinkNanos() {
        Current c = current;
        return c.sinkNanos() + (c.shard() == null ? 0 : c.shard().loop().sinkNanos());
    }

    public long hookNanos() {
        Current c = current;
        return c.hookNanos() + (c.shard() == null ? 0 : c.shard().loop().hookNanos());
    }

    public long applyCount() {
        Current c = current;
        return c.applyCount() + (c.shard() == null ? 0 : c.shard().loop().applyCount());
    }

    public long catchUpCalls() {
        Current c = current;
        return c.catchUpCalls() + (c.shard() == null ? 0 : c.shard().loop().catchUpCalls());
    }

    public long catchUpNanos() {
        Current c = current;
        return c.catchUpNanos() + (c.shard() == null ? 0 : c.shard().loop().catchUpNanos());
    }
}

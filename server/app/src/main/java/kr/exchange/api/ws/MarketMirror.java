package kr.exchange.api.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 노드별 호가·티커 미러(S6 설계 4-6). 마켓마다 호가의 마지막 게시 (epoch, seq, 본문)와 티커의 마지막 게시 (epoch, seq)를 든다.
 * 자기 게시와 받은 중계 봉투로 갱신한다. 티커 본문은 두지 않는다. 24시간 값은 요청 때 DB 로 계산해야 하기 때문이다.
 *
 * 미러를 응답에 쓸지는 MarketViews 가 소유 노드의 하트비트와 대조해 정한다. 여기서는 순서 규칙만 지킨다.
 */
public final class MarketMirror {

    public enum Channel { ORDERBOOK, TICKER }

    /** @param body 호가 게시 본문(JSON). 티커는 null 이다 */
    public record Entry(long epoch, long seq, byte[] body, long receivedAt) {
    }

    /**
     * 작은 epoch 를 무시하는 기간. 수동 DB 승격이나 복원으로 epoch 가 되돌아가면 이만큼 지난 뒤 옛 타임라인 값에서 풀린다.
     */
    static final long STALE_ENTRY_MS = 10_000;

    private final LongSupplier clockMillis;
    private final Map<String, Entry> orderbook = new ConcurrentHashMap<>();
    private final Map<String, Entry> ticker = new ConcurrentHashMap<>();

    public MarketMirror(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    public void update(Channel channel, String market, long epoch, long seq, byte[] body) {
        long now = clockMillis.getAsLong();
        Entry next = new Entry(epoch, seq, channel == Channel.ORDERBOOK ? body : null, now);
        entries(channel).compute(market, (k, cur) -> replaces(cur, epoch, seq, now) ? next : cur);
    }

    public Entry get(Channel channel, String market) {
        return entries(channel).get(market);
    }

    private static boolean replaces(Entry cur, long epoch, long seq, long now) {
        if (cur == null || epoch > cur.epoch()) {
            return true;
        }
        if (epoch == cur.epoch()) {
            return seq > cur.seq();
        }
        return now - cur.receivedAt() > STALE_ENTRY_MS;
    }

    private Map<String, Entry> entries(Channel channel) {
        return channel == Channel.ORDERBOOK ? orderbook : ticker;
    }
}

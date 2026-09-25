package kr.exchange.api.ws.relay;

import kr.exchange.api.ws.MarketMirror;
import kr.exchange.api.ws.WsGate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 구독 연결 상태와 소유 노드 하트비트 표(S6 설계 4-5, 4-6, 4-11). (파티션, epoch)마다 이 구독 세대에서 받은 최근 시각들과 마지막
 * 하트비트가 알린 마켓별 (epoch, seq)를 든다. WS 게이트와 호가·티커 선택이 같은 판정을 읽는다.
 *
 * 닿음은 최근 5초에 하트비트 3개 이상이다. 메모리 경보 중에는 발행 연결이 다시 붙을 때마다 첫 프레임 하나가 통과해 하트비트가
 * 드문드문 온다(증거 pml-s6-probe-20260925 R11a). 하나로 판정하면 중계 불능이 켜졌다 꺼지기를 되풀이한다.
 */
public final class OwnerBeats implements WsGate.Relay {

    static final long WINDOW_MS = 5_000;
    static final int REQUIRED = 3;
    // 같은 파티션의 옛 기간 항목을 지우는 나이. 닿음 창보다 충분히 길면 된다.
    private static final long PRUNE_MS = 60_000;

    private record Key(String partition, long epoch) {
    }

    private static final class Beats {
        // 최근 받은 시각 셋. [0] 이 가장 최근이다.
        final long[] recent = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        RelayEnvelope.Heartbeat last;
    }

    private final LongSupplier clockMillis;
    private final Map<Key, Beats> beats = new HashMap<>();
    private long generation;
    private long generationStartedAt;
    private volatile boolean subscribed;
    private volatile long disconnectedSince;

    public OwnerBeats(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
        this.generationStartedAt = clockMillis.getAsLong();
        this.disconnectedSince = generationStartedAt;
    }

    /**
     * 살아 있는 소유 노드가 늘 닿음으로 판정되는 하트비트 간격인지 본다. 셋째로 최근인 하트비트의 나이는 간격의 3배까지 오르고, 봉투는
     * 발행 큐에서 publish-max-age 까지 늦게 나간다. 둘의 합이 창을 넘으면 비소유 노드가 미러를 쓰지 않거나 owner_silent 로 WS 를 닫는다.
     */
    public static void requireReachableInterval(Duration heartbeat, Duration publishMaxAge) {
        if (heartbeat.isZero() || heartbeat.isNegative()
                || heartbeat.toMillis() * REQUIRED + publishMaxAge.toMillis() > WINDOW_MS) {
            throw new IllegalArgumentException("exchange.ws.relay.owner-heartbeat(" + heartbeat + ") x " + REQUIRED
                    + " + publish-max-age(" + publishMaxAge + ")가 닿음 판정 창 " + WINDOW_MS + "ms 를 넘는다."
                    + " 소유 노드가 살아 있어도 닿지 않는 때가 생긴다");
        }
    }

    /**
     * 구독을 새로 만들었다. 끊긴 동안의 게시를 잃었으므로 새 세대의 하트비트를 다시 받기 전에는 어느 소유자도 닿는다고 보지 않는다.
     * 미러 항목은 지우지 않는다. 다시 쓸지는 새 세대의 하트비트가 정한다.
     */
    public synchronized long onSubscribed() {
        beats.clear();
        generationStartedAt = clockMillis.getAsLong();
        subscribed = true;
        return ++generation;
    }

    public void onUnsubscribed() {
        subscribed = false;
        disconnectedSince = clockMillis.getAsLong();
    }

    @Override
    public boolean subscribed() {
        return subscribed;
    }

    @Override
    public long disconnectedSince() {
        return disconnectedSince;
    }

    public synchronized long generation() {
        return generation;
    }

    @Override
    public synchronized long generationStartedAt() {
        return generationStartedAt;
    }

    public synchronized void onHeartbeat(String partition, long epoch, RelayEnvelope.Heartbeat hb) {
        long now = clockMillis.getAsLong();
        beats.entrySet().removeIf(e -> e.getKey().partition().equals(partition)
                && e.getKey().epoch() != epoch && e.getValue().recent[0] < now - PRUNE_MS);
        Beats b = beats.computeIfAbsent(new Key(partition, epoch), k -> new Beats());
        b.recent[2] = b.recent[1];
        b.recent[1] = b.recent[0];
        b.recent[0] = now;
        b.last = hb;
    }

    /** 이 구독 세대에서 최근 5초 동안 (파티션, epoch)의 하트비트를 3개 이상 받았는가. */
    @Override
    public synchronized boolean reachable(String partition, long epoch) {
        Beats b = beats.get(new Key(partition, epoch));
        return b != null && b.recent[REQUIRED - 1] > clockMillis.getAsLong() - WINDOW_MS;
    }

    /**
     * 닿음 조건이 마지막으로 참이던 시각. 셋째로 최근인 하트비트가 창을 벗어나는 순간이다. 이 세대에서 셋을 받은 적이 없으면
     * Long.MIN_VALUE 다.
     */
    @Override
    public synchronized long lastReachableAt(String partition, long epoch) {
        Beats b = beats.get(new Key(partition, epoch));
        if (b == null || b.recent[REQUIRED - 1] == Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        return b.recent[REQUIRED - 1] + WINDOW_MS;
    }

    /** 마지막 하트비트가 그 마켓·채널에 알린 [epoch, seq]. 하트비트가 없거나 그 마켓 항목이 없으면 null. */
    public synchronized long[] announced(String partition, long epoch, MarketMirror.Channel channel, String market) {
        Beats b = beats.get(new Key(partition, epoch));
        if (b == null || b.last == null) {
            return null;
        }
        return b.last.channel(channel).get(market);
    }
}

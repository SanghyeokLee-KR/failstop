package kr.exchange.api.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 채널(destination) 단위 조밀(+1) 단조 증가 seq(§5-2). orderbook·ticker·myOrder·myAsset의
 * 팬아웃 도메인이다(REST 스냅샷은 current() 카운터값). trade·candle은 이 카운터가 아니라
 * event_seq 도메인을 쓴다(§5-4, REST 리스트 스냅샷과 동일 도메인이라야 재동기화가 연속).
 * 단일 노드 인메모리 카운터: 재기동·페일오버로 초기화되면 epoch 증가가 클라이언트 강제
 * 재동기화를 유발하므로 영속화가 필요 없다.
 */
public class ChannelSequences {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public long next(String channel) {
        return counters.computeIfAbsent(channel, k -> new AtomicLong()).incrementAndGet();
    }

    public long current(String channel) {
        AtomicLong counter = counters.get(channel);
        return counter == null ? 0 : counter.get();
    }
}

package kr.exchange.api.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 채널(destination) 단위 단조 증가 seq(§5-2). 도메인은 구독 destination 단위이고 채널 간
 * 공유가 없다. 단일 노드 인메모리 카운터: 재기동·페일오버로 초기화되면 epoch 증가가
 * 클라이언트 강제 재동기화를 유발하므로 영속화가 필요 없다.
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

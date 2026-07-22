package kr.exchange.api.common;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 키 단위 슬라이딩 윈도우 rate limit. 무인증·저인증 진입부(데모 세션 발급, 회원 인증)에서
 * IP·이메일 버킷 남용을 막는다(API 설계 §2-5, 회원_설계.md §3-1).
 *
 * 초과 시 429 too_many_requests + Retry-After + 위반 버킷을 담아 던진다. 전역 토큰버킷
 * 미구현 상태에서 이 경로만은 자체 방어를 갖춘다는 기존 규약(DemoSessionController)을 공유 승격했다.
 */
public class SlidingWindowThrottle {

    private final int limit;
    private final long windowMs;
    private final String bucket;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepAt = new AtomicLong();

    public SlidingWindowThrottle(int limit, long windowMs, String bucket) {
        this.limit = limit;
        this.windowMs = windowMs;
        this.bucket = bucket;
    }

    public void check(String key) {
        long now = System.currentTimeMillis();
        sweep(now);
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= windowMs) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("bucket", bucket);
                int retryAfter = (int) ((windowMs - (now - window.peekFirst())) / 1000) + 1;
                throw new ApiException(429, "too_many_requests",
                        "요청 한도를 초과했습니다.", detail, retryAfter);
            }
            window.addLast(now);
        }
    }

    /**
     * 재방문 없는 키의 빈 Deque가 맵에 잔존해 무한 증가하는 것을 최소 간격으로 정리한다.
     * 창을 벗어난 엔트리만 제거하므로 활성 버킷은 건드리지 않는다.
     */
    private void sweep(long now) {
        long due = nextSweepAt.get();
        if (now < due || !nextSweepAt.compareAndSet(due, now + windowMs)) {
            return;
        }
        hits.forEach((key, window) -> {
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() >= windowMs) {
                    window.pollFirst();
                }
                if (window.isEmpty()) {
                    hits.remove(key, window);
                }
            }
        });
    }
}

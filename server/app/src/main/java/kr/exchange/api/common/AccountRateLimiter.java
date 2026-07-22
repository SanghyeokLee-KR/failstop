package kr.exchange.api.common;

import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 계정 키 토큰버킷(§2-5, §3-4 step7). 단일 라이터 매칭 엔진 앞 유일한 계정 단위 유량 방벽이다.
 * 초당 리필, 요청당 cost 차감. 라이터 진입 전에 차감해야 증폭·기아를 상쇄한다.
 *
 * 노드 로컬 구현이다. 프로세스 로컬 상태라 다중 노드에서 실효 한도가 노드 수만큼 곱해지고
 * 재시작 시 소실된다(SlidingWindowThrottle과 동일 제약). 다중 노드 승격(M2)은 Redisson
 * RRateLimiter 교체를 선행 조건으로 한다(§2-5 분산 토큰버킷).
 */
public class AccountRateLimiter {

    public record Decision(boolean allowed, int limit, int remaining,
                           long resetEpochSeconds, int retryAfterSeconds,
                           String bucket, String scope) {
    }

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
    }

    private final int capacity;
    private final double refillPerMs;
    private final long windowMs;
    private final String bucket;
    private final String scope;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepAt = new AtomicLong();

    public AccountRateLimiter(int capacity, long windowMs, String bucket, String scope) {
        this.capacity = capacity;
        this.windowMs = windowMs;
        this.refillPerMs = (double) capacity / windowMs;
        this.bucket = bucket;
        this.scope = scope;
    }

    /**
     * cost 토큰을 원자적으로 차감한다. 잔량이 부족하면 아무것도 차감하지 않고 불허 판정을 돌려준다
     * (batch는 원소 전부가 통과하거나 전량 거부되어야 부분 차감 누수가 없다).
     */
    public Decision acquire(String key, int cost) {
        long now = System.currentTimeMillis();
        sweep(now);
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket fresh = new Bucket();
            fresh.tokens = capacity;
            fresh.lastRefillMs = now;
            return fresh;
        });
        synchronized (b) {
            refill(b, now);
            if (b.tokens >= cost) {
                b.tokens -= cost;
                return decide(true, b, now, cost);
            }
            return decide(false, b, now, cost);
        }
    }

    /** acquire 후 헤더 방출·초과 시 429를 한 번에 처리한다(컨트롤러 진입부 공용). */
    public void enforce(HttpServletResponse response, String key, int cost) {
        Decision d = acquire(key, cost);
        writeHeaders(response, d);
        if (!d.allowed()) {
            throw rejection(d);
        }
    }

    public Map<String, String> headers(Decision d) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-RateLimit-Limit-" + d.bucket(), String.valueOf(d.limit()));
        h.put("X-RateLimit-Remaining-" + d.bucket(), String.valueOf(Math.max(0, d.remaining())));
        // §2-5: Reset은 다음 토큰 가용 시각(epoch s).
        h.put("X-RateLimit-Reset", String.valueOf(d.resetEpochSeconds()));
        return h;
    }

    public void writeHeaders(HttpServletResponse response, Decision d) {
        headers(d).forEach(response::setHeader);
    }

    public ApiException rejection(Decision d) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bucket", d.bucket());
        detail.put("scope", d.scope());
        return new ApiException(429, "too_many_requests",
                "요청 한도를 초과했습니다.", detail, d.retryAfterSeconds());
    }

    private void refill(Bucket b, long now) {
        long elapsed = now - b.lastRefillMs;
        if (elapsed > 0) {
            b.tokens = Math.min(capacity, b.tokens + elapsed * refillPerMs);
            b.lastRefillMs = now;
        }
    }

    private Decision decide(boolean allowed, Bucket b, long now, int cost) {
        int remaining = (int) Math.floor(b.tokens);
        int retryAfter = 0;
        if (!allowed) {
            double deficit = cost - b.tokens;
            retryAfter = (int) Math.max(1, Math.ceil(deficit / refillPerMs / 1000.0));
        }
        return new Decision(allowed, capacity, remaining, nextTokenEpoch(b, now),
                retryAfter, bucket, scope);
    }

    private long nextTokenEpoch(Bucket b, long now) {
        if (b.tokens >= capacity) {
            return (now + 999) / 1000;
        }
        double toNext = (Math.floor(b.tokens) + 1) - b.tokens;
        if (toNext <= 0) {
            toNext = 1;
        }
        long ms = (long) Math.ceil(toNext / refillPerMs);
        return (now + ms + 999) / 1000;
    }

    /**
     * 재방문 없는 키의 버킷이 맵에 잔존해 무한 증가하는 것을 최소 간격으로 정리한다.
     * 리필로 가득 찬(활동 종료) 버킷만 제거하므로 진행 중 버킷은 건드리지 않는다.
     */
    private void sweep(long now) {
        long due = nextSweepAt.get();
        if (now < due || !nextSweepAt.compareAndSet(due, now + windowMs)) {
            return;
        }
        buckets.forEach((key, b) -> {
            synchronized (b) {
                refill(b, now);
                if (b.tokens >= capacity) {
                    buckets.remove(key, b);
                }
            }
        });
    }
}

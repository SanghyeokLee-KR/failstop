package kr.exchange.api.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 계정 토큰버킷(§2-5) 단위 검증. 리필 창을 크게 잡아 refill 간섭 없이 용량·차감을 결정론적으로 본다. */
class AccountRateLimiterTest {

    @Test
    void allowsUpToCapacityThenRejects() {
        AccountRateLimiter limiter = new AccountRateLimiter(100, 3_600_000, "account", "order");
        String key = "1";
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.acquire(key, 1).allowed(), "용량 내 요청은 통과해야 한다: " + i);
        }
        AccountRateLimiter.Decision denied = limiter.acquire(key, 1);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() >= 1);
        assertEquals("account", denied.bucket());
        assertEquals("order", denied.scope());
    }

    @Test
    void batchCostConsumesPerElement() {
        AccountRateLimiter limiter = new AccountRateLimiter(100, 3_600_000, "account", "order");
        String key = "2";
        assertTrue(limiter.acquire(key, 100).allowed(), "정확히 용량만큼 요청하면 통과");
        assertFalse(limiter.acquire(key, 1).allowed(), "직후 추가 1토큰은 소진되어 거부");
    }

    @Test
    void rejectsWithoutConsumingWhenCostExceedsAvailable() {
        AccountRateLimiter limiter = new AccountRateLimiter(100, 3_600_000, "account", "order");
        String key = "3";
        assertFalse(limiter.acquire(key, 101).allowed(), "용량 초과 cost는 거부");
        // 거부 시 부분 차감이 없어야 후속 정상 요청이 온전히 통과한다.
        assertTrue(limiter.acquire(key, 100).allowed());
    }

    @Test
    void bucketsAreIsolatedByKey() {
        AccountRateLimiter limiter = new AccountRateLimiter(100, 3_600_000, "account", "order");
        assertTrue(limiter.acquire("10", 100).allowed());
        assertFalse(limiter.acquire("10", 1).allowed());
        assertTrue(limiter.acquire("11", 1).allowed(), "다른 계정 키는 독립 버킷");
    }

    @Test
    void headersExposeBucketAndReset() {
        AccountRateLimiter limiter = new AccountRateLimiter(60, 60_000, "admin", "admin");
        AccountRateLimiter.Decision d = limiter.acquire("op", 1);
        var headers = limiter.headers(d);
        assertEquals("60", headers.get("X-RateLimit-Limit-admin"));
        assertEquals("59", headers.get("X-RateLimit-Remaining-admin"));
        assertTrue(headers.containsKey("X-RateLimit-Reset"));
    }
}

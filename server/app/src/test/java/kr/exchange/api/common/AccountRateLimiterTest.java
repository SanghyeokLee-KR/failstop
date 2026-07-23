package kr.exchange.api.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

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

    // 윈도 경과 유휴 버킷은 다음 sweep에서 축출된다. 재방문 없는 키의 무한 증가 방지 경로.
    @Test
    void sweepEvictsIdleFullBucket() {
        AtomicLong clock = new AtomicLong(0);
        AccountRateLimiter limiter = new AccountRateLimiter(10, 1_000, "account", "order", clock::get);
        limiter.acquire("A", 1);
        // 윈도(1000ms)를 넘겨 A를 유휴로 만든 뒤 다른 키 접근이 sweep을 구동한다.
        clock.set(2_000);
        limiter.acquire("B", 1);
        assertFalse(limiter.tracks("A"), "윈도 경과 유휴 버킷은 축출돼야 한다");
        assertTrue(limiter.tracks("B"));
        assertEquals(1, limiter.trackedBuckets());
    }

    // 갓 접근한(lastRefillMs 최신) 버킷은 sweep 시각에 가득 차 있어도 경합 가드로 축출 배제된다.
    @Test
    void sweepSkipsRecentlyTouchedBucket() {
        AtomicLong clock = new AtomicLong(0);
        AccountRateLimiter limiter = new AccountRateLimiter(10, 1_000, "account", "order", clock::get);
        limiter.acquire("B", 1);
        // B를 sweep 경계 직전에 다시 접근해 lastRefillMs를 최신으로 갱신한다.
        clock.set(950);
        limiter.acquire("B", 1);
        // sweep이 due가 되는 시점(1050)에 다른 키가 sweep을 구동한다. B는 접근 100ms 후라 가드로 남는다.
        clock.set(1_050);
        limiter.acquire("TRIGGER", 1);
        assertTrue(limiter.tracks("B"), "윈도 미경과 최신 버킷은 축출 배제돼야 한다");
        assertEquals(2, limiter.trackedBuckets());
    }

    // 경합 가드 경계는 strict `age < windowMs`다. 정확히 windowMs 유휴는 축출, 1ms 덜 지난 버킷은 배제된다.
    // 이 경계가 등재(lastRefillMs=now) 직후 버킷을 sweep 축출에서 지켜 첫 차감 유실(교차 스레드 환불)을 막는 근거다.
    @Test
    void sweepGuardBoundaryIsStrictWindow() {
        AtomicLong clock = new AtomicLong(0);
        AccountRateLimiter limiter = new AccountRateLimiter(10, 1_000, "account", "order", clock::get);
        limiter.acquire("BOUNDARY", 1);          // lastRefillMs=0
        clock.set(1);
        limiter.acquire("RECENT", 1);            // lastRefillMs=1
        // 1000ms sweep: BOUNDARY는 age 1000(=windowMs, 미포함)이라 리필 후 가득 차 축출, RECENT는 age 999라 가드로 배제.
        clock.set(1_000);
        limiter.acquire("TRIGGER", 1);
        assertFalse(limiter.tracks("BOUNDARY"), "정확히 windowMs 유휴 버킷은 축출돼야 한다");
        assertTrue(limiter.tracks("RECENT"), "windowMs 미경과(1ms 부족) 버킷은 가드로 배제돼야 한다");
        assertEquals(2, limiter.trackedBuckets());
    }
}

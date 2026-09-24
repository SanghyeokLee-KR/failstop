package kr.exchange.admin.msp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원천 읽기의 공유 캐시(설계 4-6). 운영자가 늘어도 원천 호출 수가 늘지 않아야 한다.
 */
class SourceCacheTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-09-25T00:00:00Z"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SourceCache cache = new SourceCache(clock, registry, 4);

    @Test
    void 수명_안에는_저장한_값을_주고_수명이_지나면_다시_적재한다() {
        AtomicInteger loads = new AtomicInteger();

        SourceCache.Cached<Integer> first = cache.get("prometheus:instant", Duration.ofSeconds(10), loads::incrementAndGet);
        clock.advance(Duration.ofSeconds(9));
        SourceCache.Cached<Integer> second = cache.get("prometheus:instant", Duration.ofSeconds(10), loads::incrementAndGet);
        clock.advance(Duration.ofSeconds(1));
        SourceCache.Cached<Integer> third = cache.get("prometheus:instant", Duration.ofSeconds(10), loads::incrementAndGet);

        assertSame(first, second);
        assertEquals(2, third.value());
        assertEquals(Instant.parse("2026-09-25T00:00:10Z"), third.loadedAt());
        assertEquals(2, loads.get());
        assertEquals(1.0, count("prometheus", "hit"));
        assertEquals(2.0, count("prometheus", "miss"));
    }

    @Test
    void 같은_키의_동시_요청은_원천을_한_번만_부르고_모두_그_값을_받는다() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<SourceCache.Cached<Integer>>> calls = new ArrayList<>();
            calls.add(callers.submit(() -> cache.get("llm:status", Duration.ofSeconds(30), () -> {
                entered.countDown();
                await(release);
                return loads.incrementAndGet();
            })));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 7; i++) {
                calls.add(callers.submit(() -> cache.get("llm:status", Duration.ofSeconds(30),
                        loads::incrementAndGet)));
            }
            waitFor(() -> count("llm", "wait") == 7.0);
            release.countDown();
            for (Future<SourceCache.Cached<Integer>> call : calls) {
                assertEquals(1, call.get(5, TimeUnit.SECONDS).value());
            }
        } finally {
            callers.shutdownNow();
        }
        assertEquals(1, loads.get(), "동시 8건이 원천을 한 번만 불렀다");
        assertEquals(1.0, count("llm", "miss"));
    }

    @Test
    void 다른_키는_서로를_기다리지_않는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<SourceCache.Cached<String>> slow = callers.submit(() -> cache.get("reporting:list",
                    Duration.ofSeconds(30), () -> {
                        await(release);
                        return "list";
                    }));
            waitFor(() -> count("reporting", "miss") == 1.0);
            assertEquals("status", cache.get("reporting:status", Duration.ofSeconds(30), () -> "status").value());
            release.countDown();
            assertEquals("list", slow.get(5, TimeUnit.SECONDS).value());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void 적재_중_예외는_캐시하지_않고_기다리던_요청에도_같은_예외가_간다() {
        AtomicInteger loads = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> cache.get("capacity:observe", Duration.ofSeconds(10), () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(7, cache.get("capacity:observe", Duration.ofSeconds(10), () -> {
            loads.incrementAndGet();
            return 7;
        }).value());
        assertEquals(2, loads.get());
    }

    @Test
    void 항목_상한을_넘으면_만료된_것부터_그다음_가장_오래_적재한_것을_버린다() {
        AtomicInteger loads = new AtomicInteger();
        cache.get("a:1", Duration.ofSeconds(5), loads::incrementAndGet);
        clock.advance(Duration.ofSeconds(1));
        cache.get("a:2", Duration.ofSeconds(60), loads::incrementAndGet);
        cache.get("a:3", Duration.ofSeconds(60), loads::incrementAndGet);
        cache.get("a:4", Duration.ofSeconds(60), loads::incrementAndGet);
        clock.advance(Duration.ofSeconds(10));

        cache.get("a:5", Duration.ofSeconds(60), loads::incrementAndGet);
        assertEquals(4, cache.size(), "만료된 a:1 을 버리고 넣었다");
        cache.get("a:6", Duration.ofSeconds(60), loads::incrementAndGet);
        assertEquals(4, cache.size(), "만료된 항목이 없으면 가장 오래 적재한 a:2 를 버린다");

        int before = loads.get();
        cache.get("a:3", Duration.ofSeconds(60), loads::incrementAndGet);
        assertEquals(before, loads.get(), "a:3 은 남아 있다");
        cache.get("a:2", Duration.ofSeconds(60), loads::incrementAndGet);
        assertEquals(before + 1, loads.get(), "버린 a:2 는 다시 적재한다");
    }

    @Test
    void 원천_호출과_항목_수를_지표로_낸다() {
        cache.called("prometheus", "query");
        cache.called("prometheus", "query");
        cache.get("prometheus:instant", Duration.ofSeconds(10), () -> 1);

        assertEquals(2.0, registry.find("exchange.msp.source.calls").tag("source", "prometheus").tag("op", "query")
                .counter().count());
        assertEquals(1.0, registry.find("exchange.msp.cache.entries").gauge().value());
    }

    private double count(String cacheName, String result) {
        var c = registry.find("exchange.msp.cache.requests").tag("cache", cacheName).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("조건을 기다리다 시간이 지났다");
            }
            Thread.sleep(10);
        }
    }
}

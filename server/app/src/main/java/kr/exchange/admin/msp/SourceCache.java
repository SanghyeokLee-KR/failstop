package kr.exchange.admin.msp;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 원천 읽기의 공유 캐시(설계 4-6). 키는 코드가 정한 이름뿐이고 '원천:대상' 모양이다. 수명 안이면 저장한 값을 주고,
 * 수명이 지났으면 한 요청만 원천을 부르고 같은 키의 다른 요청은 그 결과를 기다린다. 운영자가 늘어도 원천 호출 수는
 * 늘지 않는다. 적재 중 예외는 저장하지 않는다. 원천 실패는 호출부가 값(칸의 UNAVAILABLE)으로 돌려 수명 동안 공유한다.
 */
public class SourceCache {

    public record Cached<T>(T value, Instant loadedAt) {
    }

    // 키는 코드가 정한 이름뿐이라 이 상한에 닿을 일이 없어야 한다. 닿으면 만료된 것부터 버린다.
    static final int MAX_ENTRIES = 64;

    private static final class Slot {
        Cached<?> value;
        Instant expiresAt;
        long seq;
        CompletableFuture<Cached<?>> loading;
    }

    private final Clock clock;
    private final MeterRegistry registry;
    private final int maxEntries;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong loads = new AtomicLong();

    public SourceCache(Clock clock, MeterRegistry registry) {
        this(clock, registry, MAX_ENTRIES);
    }

    SourceCache(Clock clock, MeterRegistry registry, int maxEntries) {
        this.clock = clock;
        this.registry = registry;
        this.maxEntries = maxEntries;
        registry.gauge("exchange.msp.cache.entries", slots, Map::size);
    }

    public Clock clock() {
        return clock;
    }

    @SuppressWarnings("unchecked")
    public <T> Cached<T> get(String key, Duration ttl, Supplier<T> loader) {
        Slot slot = slot(key);
        CompletableFuture<Cached<?>> mine = null;
        CompletableFuture<Cached<?>> pending;
        synchronized (slot) {
            if (slot.value != null && clock.instant().isBefore(slot.expiresAt)) {
                count(key, "hit");
                return (Cached<T>) slot.value;
            }
            if (slot.loading == null) {
                mine = new CompletableFuture<>();
                slot.loading = mine;
            }
            pending = slot.loading;
        }
        if (mine == null) {
            count(key, "wait");
            return (Cached<T>) join(pending);
        }
        count(key, "miss");
        try {
            T value = loader.get();
            Instant loadedAt = clock.instant();
            Cached<T> cached = new Cached<>(value, loadedAt);
            synchronized (slot) {
                slot.value = cached;
                // 수명은 적재가 끝난 때부터 센다. 적재가 수명보다 길어도 다음 요청이 곧바로 다시 적재하지 않는다.
                slot.expiresAt = loadedAt.plus(ttl);
                slot.seq = loads.incrementAndGet();
                slot.loading = null;
            }
            mine.complete(cached);
            return cached;
        } catch (RuntimeException | Error e) {
            synchronized (slot) {
                slot.loading = null;
            }
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /** 실제 원천 호출 한 번. 캐시를 거치지 않는 호출도 여기서 센다. */
    public void called(String source, String op) {
        registry.counter("exchange.msp.source.calls", "source", source, "op", op).increment();
    }

    int size() {
        return slots.size();
    }

    private Slot slot(String key) {
        Slot existing = slots.get(key);
        if (existing != null) {
            return existing;
        }
        Slot created = slots.computeIfAbsent(key, k -> new Slot());
        if (slots.size() > maxEntries) {
            evict(key);
        }
        return created;
    }

    /** 만료된 항목부터, 없으면 가장 오래 적재한 항목을 버린다. 적재 중인 항목과 방금 넣은 키는 두고 본다. */
    private void evict(String keep) {
        Instant now = clock.instant();
        slots.entrySet().removeIf(e -> {
            if (e.getKey().equals(keep)) {
                return false;
            }
            synchronized (e.getValue()) {
                return e.getValue().loading == null && e.getValue().value != null && !now.isBefore(e.getValue().expiresAt);
            }
        });
        while (slots.size() > maxEntries) {
            Map.Entry<String, Slot> oldest = slots.entrySet().stream()
                    .filter(e -> !e.getKey().equals(keep))
                    .filter(e -> {
                        synchronized (e.getValue()) {
                            return e.getValue().loading == null;
                        }
                    })
                    .min(Comparator.comparingLong(e -> {
                        synchronized (e.getValue()) {
                            return e.getValue().seq;
                        }
                    }))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            slots.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private void count(String key, String result) {
        int colon = key.indexOf(':');
        registry.counter("exchange.msp.cache.requests", "cache", colon < 0 ? key : key.substring(0, colon),
                "result", result).increment();
    }

    private static Cached<?> join(CompletableFuture<Cached<?>> pending) {
        try {
            return pending.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }
}

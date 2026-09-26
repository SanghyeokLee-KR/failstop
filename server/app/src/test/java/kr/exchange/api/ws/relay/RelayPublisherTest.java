package kr.exchange.api.ws.relay;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 중계 발행의 넣기·쓰기 대기·끊기 규칙(S6 설계 4-4, 6-2). 가짜 전송을 쓰고 브로커는 없다. */
class RelayPublisherTest {

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    /** 쓰기를 붙잡을 수 있는 가짜 발행 연결. abort 는 SO_LINGER 0 닫기 흉내다. */
    static final class FakeTransport implements RelayPublisher.Transport {
        volatile boolean connected = true;
        volatile CompletableFuture<Void> hold;
        final List<Long> written = new CopyOnWriteArrayList<>();
        final AtomicInteger sends = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger();

        @Override
        public boolean connected() {
            return connected;
        }

        @Override
        public CompletableFuture<Void> send(RelayEnvelope envelope) {
            sends.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            CompletableFuture<Void> gate = hold;
            CompletableFuture<Void> write = gate == null ? CompletableFuture.completedFuture(null) : gate.thenApply(v -> v);
            return write.whenComplete((v, t) -> {
                inFlight.decrementAndGet();
                if (t == null) {
                    written.add(envelope.seq());
                }
            });
        }

        @Override
        public void abort() {
            aborts.incrementAndGet();
            connected = false;
            CompletableFuture<Void> gate = hold;
            if (gate != null) {
                gate.completeExceptionally(new IllegalStateException("RST"));
            }
        }
    }

    private static RelayEnvelope envelope(long seq) {
        return RelayEnvelope.topic("node-a", "/topic/orderbook.KRW-BTC", "KRW-BTC", 1, seq, BODY);
    }

    @Test
    void writerOffersNeverWaitWhileTheBrokerHoldsTheWrite() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.hold = new CompletableFuture<>();
        RelayPublisher publisher = new RelayPublisher(transport, 10_000, Duration.ofSeconds(30),
                Duration.ofSeconds(60), () -> System.nanoTime() / 1_000_000);
        publisher.start();
        try {
            int n = 100_000;
            long[] took = new long[n];
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                publisher.offer(envelope(i));
                took[i] = System.nanoTime() - t0;
            }
            long totalMs = (System.nanoTime() - start) / 1_000_000;
            long maxMs = Arrays.stream(took).max().orElseThrow() / 1_000_000;
            long[] warm = Arrays.copyOfRange(took, 10_000, n);
            Arrays.sort(warm);
            System.out.printf("넣기 10만 번 %dms, 최댓값 %dms, 워밍업 뒤 p99.9 %dµs%n", totalMs, maxMs,
                    warm[(int) (warm.length * 0.999)] / 1_000);
            assertTrue(totalMs <= 5_000, "넣기 10만 번이 5초를 넘었다: " + totalMs + "ms");
            assertTrue(maxMs <= 50, "넣기 한 번이 50ms 를 넘었다: " + maxMs + "ms");
            assertEquals(10_000, publisher.queueSize(), "큐가 용량에서 멈추지 않았다");
            assertEquals(n, publisher.queueFull() + publisher.queueSize() + transport.sends.get(),
                    "queue_full 이 나머지 수와 다르다");
            assertTrue(transport.maxInFlight.get() <= 1, "대기 중인 쓰기가 1 을 넘었다: " + transport.maxInFlight.get());
            assertTrue(publisher.pendingWrites() <= 1);
        } finally {
            transport.hold.complete(null);
            publisher.close();
        }

        // 대조군: 쓰기 완료를 기다리지 않는 배선이면 막힌 브로커 앞에서 대기 쓰기가 끝없이 는다.
        FakeTransport unbounded = new FakeTransport();
        unbounded.hold = new CompletableFuture<>();
        for (int i = 0; i < 1_000; i++) {
            unbounded.send(envelope(i));
        }
        assertEquals(1_000, unbounded.maxInFlight.get());
    }

    @Test
    void disconnectedOfferDropsImmediately() {
        FakeTransport transport = new FakeTransport();
        transport.connected = false;
        RelayPublisher publisher = new RelayPublisher(transport, 10, Duration.ofSeconds(1), Duration.ofSeconds(2),
                () -> 0L);
        for (int i = 0; i < 5; i++) {
            assertFalse(publisher.offer(envelope(i)));
        }
        assertEquals(5, publisher.disconnected());
        assertEquals(0, publisher.queueSize());
        assertEquals(0, transport.sends.get());
    }

    @Test
    void writeTimeoutDrainsQueueAbortsWithRstAndResumesInOrder() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.hold = new CompletableFuture<>();
        AtomicLong clock = new AtomicLong();
        RelayPublisher publisher = new RelayPublisher(transport, 10, Duration.ofSeconds(1), Duration.ofSeconds(2),
                clock::get);
        for (int i = 1; i <= 3; i++) {
            assertTrue(publisher.offer(envelope(i)));
        }
        long t0 = System.nanoTime();
        assertTrue(publisher.publishNext(0));
        long waitedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(waitedMs >= 900 && waitedMs < 5_000, "쓰기 대기 상한이 1초가 아니다: " + waitedMs + "ms");
        assertEquals(1, publisher.writeTimeouts());
        assertEquals(2, publisher.disconnected(), "큐에 남은 봉투를 disconnected 로 세며 비우지 않았다");
        assertEquals(0, publisher.queueSize());
        assertEquals(1, transport.aborts.get(), "발행 연결을 SO_LINGER 0 으로 끊지 않았다");
        assertFalse(publisher.offer(envelope(4)), "끊긴 연결에 넣었다");

        // 재연결 뒤 새 봉투는 넣은 순서대로 나간다.
        transport.hold = null;
        transport.connected = true;
        for (int i = 10; i < 15; i++) {
            assertTrue(publisher.offer(envelope(i)));
        }
        while (publisher.publishNext(0)) {
            // 큐가 빌 때까지 보낸다.
        }
        assertEquals(List.of(10L, 11L, 12L, 13L, 14L), new ArrayList<>(transport.written));
        assertEquals(5, publisher.sent(RelayEnvelope.Kind.TOPIC));
    }

    @Test
    void envelopeOlderThanMaxAgeIsNotSent() throws Exception {
        FakeTransport transport = new FakeTransport();
        AtomicLong clock = new AtomicLong(1_000);
        RelayPublisher publisher = new RelayPublisher(transport, 10, Duration.ofSeconds(1), Duration.ofSeconds(2),
                clock::get);
        publisher.offer(envelope(1));
        clock.addAndGet(2_001);
        publisher.offer(envelope(2));
        publisher.publishNext(0);
        publisher.publishNext(0);
        assertEquals(1, publisher.stale());
        assertEquals(List.of(2L), new ArrayList<>(transport.written));
    }

    @Test
    void linkLossDrainsQueuedEnvelopes() throws Exception {
        FakeTransport transport = new FakeTransport();
        RelayPublisher publisher = new RelayPublisher(transport, 10, Duration.ofSeconds(1), Duration.ofSeconds(2),
                () -> 0L);
        publisher.offer(envelope(1));
        publisher.offer(envelope(2));
        publisher.offer(envelope(3));
        transport.connected = false;
        publisher.publishNext(0);
        assertEquals(3, publisher.disconnected());
        assertEquals(0, publisher.queueSize());
        assertTrue(transport.written.isEmpty());
    }
}

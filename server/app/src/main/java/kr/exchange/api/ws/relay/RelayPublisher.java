package kr.exchange.api.ws.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 중계 발행(S6 설계 4-4). 라이터 스레드와 하트비트 스레드는 기다리지 않고 넣기만 하고, 발행 스레드 하나가 큐를 비운다.
 *
 * 봉투마다 쓰기 완료를 writeTimeout 까지 기다린다. 기다리지 않으면 메모리 경보로 막힌 브로커 앞에서 송신 버퍼가 끝없이 커진다.
 * 넘으면 그 봉투를 버리고 큐를 비운 뒤 발행 연결을 SO_LINGER 0 으로 끊는다. 보통 닫기로 끊으면 경보가 풀린 뒤 옛 연결의
 * 메시지가 새 연결의 메시지와 섞여 늦게 온다(증거 pml-s6-probe-20260925 R11b).
 */
public final class RelayPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayPublisher.class);
    private static final long POLL_MS = 100;

    /** 발행 연결. BrokerLink 가 구현하고 시험은 가짜를 쓴다. */
    public interface Transport {
        boolean connected();

        /** 쓰기 완료에 끝나는 미래. 연결이 없으면 실패한 미래다. */
        CompletableFuture<Void> send(RelayEnvelope envelope);

        /** 발행 연결을 SO_LINGER 0 으로 끊고 재연결로 간다. */
        void abort();
    }

    private record Queued(RelayEnvelope envelope, long enqueuedAt) {
    }

    private final Transport transport;
    private final ArrayBlockingQueue<Queued> queue;
    private final long writeTimeoutMs;
    private final long maxAgeMs;
    private final LongSupplier clockMillis;

    private final AtomicLong queueFull = new AtomicLong();
    private final AtomicLong disconnected = new AtomicLong();
    private final AtomicLong writeTimeouts = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong[] sent = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
    private final AtomicInteger pendingWrites = new AtomicInteger();

    private volatile boolean running;
    private volatile Thread worker;

    public RelayPublisher(Transport transport, int capacity, Duration writeTimeout, Duration maxAge,
                          LongSupplier clockMillis) {
        this.transport = transport;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writeTimeoutMs = writeTimeout.toMillis();
        this.maxAgeMs = maxAge.toMillis();
        this.clockMillis = clockMillis;
    }

    /** 기다리지 않는다. 버리면 거짓이다. */
    public boolean offer(RelayEnvelope envelope) {
        if (!transport.connected()) {
            disconnected.incrementAndGet();
            return false;
        }
        if (!queue.offer(new Queued(envelope, clockMillis.getAsLong()))) {
            queueFull.incrementAndGet();
            return false;
        }
        return true;
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    publishNext(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.warn("중계 발행 실패(게시는 계속된다): {}", e.getMessage());
                }
            }
        }, "ws-relay-pub");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /**
     * 큐에서 봉투 하나를 꺼내 보낸다. 시험은 발행 스레드 없이 이것을 직접 부른다.
     *
     * @return 봉투를 꺼냈으면 참
     */
    boolean publishNext(long pollMs) throws InterruptedException {
        Queued q = queue.poll(pollMs, TimeUnit.MILLISECONDS);
        if (q == null) {
            return false;
        }
        if (clockMillis.getAsLong() - q.enqueuedAt() > maxAgeMs) {
            stale.incrementAndGet();
            return true;
        }
        if (!transport.connected()) {
            disconnected.incrementAndGet();
            drain();
            return true;
        }
        pendingWrites.incrementAndGet();
        try {
            transport.send(q.envelope()).get(writeTimeoutMs, TimeUnit.MILLISECONDS);
            sent[q.envelope().kind().ordinal()].incrementAndGet();
        } catch (TimeoutException e) {
            writeTimeouts.incrementAndGet();
            drain();
            log.warn("중계 발행 쓰기가 {}ms 를 넘었다. 발행 연결을 끊고 다시 붙는다(대기 봉투 버림)", writeTimeoutMs);
            transport.abort();
        } catch (ExecutionException e) {
            disconnected.incrementAndGet();
            drain();
        } finally {
            pendingWrites.decrementAndGet();
        }
        return true;
    }

    /** 끊긴 연결 앞에 쌓인 봉투를 버린다. 다시 붙은 뒤 막히기 전의 오래된 봉투를 보내지 않게 한다. */
    private void drain() {
        int n = 0;
        while (queue.poll() != null) {
            n++;
        }
        disconnected.addAndGet(n);
    }

    @Override
    public synchronized void close() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public long queueFull() {
        return queueFull.get();
    }

    public long disconnected() {
        return disconnected.get();
    }

    public long writeTimeouts() {
        return writeTimeouts.get();
    }

    public long stale() {
        return stale.get();
    }

    public long sent(RelayEnvelope.Kind kind) {
        return sent[kind.ordinal()].get();
    }

    public int queueSize() {
        return queue.size();
    }

    /** 쓰기 완료를 기다리는 봉투 수. 늘 1 이하다. */
    public int pendingWrites() {
        return pendingWrites.get();
    }
}

package kr.exchange.api.ws;

import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseStoreProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * WS 게이트가 보는 리스 상태(S6 설계 4-11). 1초마다 계획의 자원 전부를 읽는다. 읽기만 하고 리스를 바꾸지 않는다. 리스 보관소가
 * PostgreSQL 이면 leader_lease 행을 한 문장으로 읽고 유효 판정을 DB 시각으로 해 노드 시계가 들어오지 않는다. DynamoDB 이면 그 행은
 * 승격 때 게시만 되고 lease_until 이 먼 미래라(V33) 유효를 알 수 없으므로 보관소 항목을 보관소 시계로 읽는다.
 *
 * 조회가 실패하면 마지막 결과를 3초까지 쓰고, 그 뒤에는 리스 상태를 모른다고 본다. 이 노드가 DB 에 닿지 못하면 REST 도 실패하므로
 * WS 만 따로 닫지 않는다.
 */
public final class LeaseWatch implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaseWatch.class);
    static final long STALE_MS = 3_000;
    private static final long WARN_INTERVAL_MS = 30_000;

    private record Seen(Map<String, WsGate.LeaseRow> rows, long at) {
    }

    private final Supplier<Map<String, WsGate.LeaseRow>> reader;
    private final Duration interval;
    private final LongSupplier clockMillis;
    private volatile Seen last;
    private volatile long lastWarnAt = Long.MIN_VALUE;
    private ScheduledExecutorService scheduler;

    public LeaseWatch(Supplier<Map<String, WsGate.LeaseRow>> reader, Duration interval, LongSupplier clockMillis) {
        this.reader = reader;
        this.interval = interval;
        this.clockMillis = clockMillis;
    }

    /** leader_lease 에서 계획의 자원 행을 읽는 조회기. */
    public static LeaseWatch of(JdbcClient jdbc, List<String> resources, Duration interval, LongSupplier clockMillis) {
        String[] wanted = resources.toArray(String[]::new);
        return new LeaseWatch(() -> {
            Map<String, WsGate.LeaseRow> rows = new LinkedHashMap<>();
            jdbc.sql("SELECT resource, holder, epoch, lease_until > clock_timestamp() AS valid"
                            + " FROM leader_lease WHERE resource = ANY(?)")
                    .param(wanted)
                    .query((rs, n) -> rows.put(rs.getString("resource"), new WsGate.LeaseRow(
                            rs.getString("holder"), rs.getLong("epoch"), rs.getBoolean("valid"))))
                    .list();
            return rows;
        }, interval, clockMillis);
    }

    /** 리스 보관소의 현재 리스를 읽는 조회기. DynamoDB 보관소에서 쓴다. */
    public static LeaseWatch of(LeaseStoreProvider stores, List<String> resources, Duration interval,
                                LongSupplier clockMillis) {
        Map<String, LeaderLeaseStore> byResource = new LinkedHashMap<>();
        for (String resource : resources) {
            byResource.put(resource, stores.store(resource));
        }
        return new LeaseWatch(() -> {
            Map<String, WsGate.LeaseRow> rows = new LinkedHashMap<>();
            byResource.forEach((resource, store) -> store.current().ifPresent(s -> rows.put(resource,
                    new WsGate.LeaseRow(s.holder(), s.epoch(), s.leaseUntil().isAfter(store.now())))));
            return rows;
        }, interval, clockMillis);
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-gate-lease");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void poll() {
        try {
            last = new Seen(Map.copyOf(reader.get()), clockMillis.getAsLong());
        } catch (RuntimeException e) {
            long now = clockMillis.getAsLong();
            if (lastWarnAt == Long.MIN_VALUE || now - lastWarnAt >= WARN_INTERVAL_MS) {
                lastWarnAt = now;
                log.warn("WS 게이트 리스 조회 실패(마지막 결과를 {}ms 까지 쓴다): {}", STALE_MS, e.getMessage());
            }
        }
    }

    /** 3초 안에 읽은 리스 행. 그보다 오래됐거나 읽은 적이 없으면 비어 있다. */
    public Optional<Map<String, WsGate.LeaseRow>> view() {
        Seen s = last;
        if (s == null || clockMillis.getAsLong() - s.at() > STALE_MS) {
            return Optional.empty();
        }
        return Optional.of(s.rows());
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}

package kr.exchange.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;
import kr.exchange.engine.lease.MeteredLeaderLeaseStore;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 막힌 획득 아래의 갱신(S4 설계 4-3·6-2, DB 설계 §5.4-c 3). 리스 풀 하나(최대 2)를 두 파티션의 틱 스레드가 같이 쓴다. 외부
 * 연결이 만료된 p1 행을 KEY SHARE 로 8초 쥔 동안, p0 을 쥔 노드가 p1 획득을 틱마다 되풀이하고 1초마다 p0 fencing 재확인과
 * 현재 리스 조회를 끼운다.
 *
 * S4 배선(획득 트랜잭션 한정 lock_timeout 1000ms)은 p1 틱이 매번 상한 + 200ms 안에 돌아오고 55P03 을 세며, p0 갱신 지연
 * 최대가 한 틱보다 작다. 획득 뒤 풀 연결에 lock_timeout 이 남지 않는다. 대조군(같은 풀, 상한 없음)은 p1 틱이 8초 동안
 * 돌아오지 않는다. 두 배선 모두 p0 갱신 지연이 한 틱보다 작으면 획득 전용 풀이 필요 없다는 근거다(4-3).
 */
class RenewUnderBlockedAcquireTest {

    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";
    private static final Duration TTL = Duration.ofSeconds(5);
    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Duration LOCK_TIMEOUT = Duration.ofMillis(1000);
    private static final long HOLD_MILLIS = 8_000;

    @Test
    void s4WiringKeepsRenewingAndReturnsAcquireTicksWithinTheBound() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_renew_s4");
        db.seedMarkets(1, 2);
        db.setProtocol(3);
        try (HikariDataSource pool = leasePool(db)) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            Run run = run(db, pool,
                    new MeteredLeaderLeaseStore(new PostgresLeaderLeaseStore(pool, P0, (short) 3, LOCK_TIMEOUT),
                            registry, Clock.systemUTC(), P0),
                    new MeteredLeaderLeaseStore(new PostgresLeaderLeaseStore(pool, P1, (short) 3, LOCK_TIMEOUT),
                            registry, Clock.systemUTC(), P1));

            assertTrue(run.p0Kept, "p0 가 리스를 잃었다");
            assertTrue(run.maxRenewMillis < TICK.toMillis(), "p0 갱신 지연 최대가 한 틱 이상이다: " + run.maxRenewMillis);
            double timeouts = registry.get("exchange.leader.lease.acquire.lock_timeouts").tag("partition", P1)
                    .counter().count();
            assertTrue(timeouts >= 3, "p1 획득의 55P03 계수가 3 미만이다: " + timeouts);
            assertTrue(run.maxAcquireMillis <= LOCK_TIMEOUT.toMillis() + 200,
                    "p1 틱이 lock_timeout + 200ms 안에 돌아오지 않았다: " + run.maxAcquireMillis);
            // 설정은 트랜잭션 한정이라 풀 연결에 남지 않는다. 풀의 두 연결을 모두 빌려 본다.
            try (Connection c1 = pool.getConnection(); Connection c2 = pool.getConnection()) {
                assertEquals("0", show(c1), "획득의 lock_timeout 이 풀 연결에 남았다");
                assertEquals("0", show(c2), "획득의 lock_timeout 이 풀 연결에 남았다");
            }
            // 잠금이 풀린 뒤에는 p1 을 얻는다.
            assertTrue(run.p1AcquiredAfterRelease, "잠금이 풀린 뒤 p1 을 얻지 못했다");
        }
    }

    @Test
    void s3WiringControlBlocksTheAcquireTickForTheWholeHold() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_renew_s3");
        db.seedMarkets(1, 2);
        db.setProtocol(3);
        try (HikariDataSource pool = leasePool(db)) {
            JdbcClient jdbc = JdbcClient.create(pool);
            Run run = run(db, pool, new UnboundedStore(jdbc, P0), new UnboundedStore(jdbc, P1));

            assertTrue(run.p0Kept, "대조군에서 p0 가 리스를 잃었다");
            assertTrue(run.maxRenewMillis < TICK.toMillis(), "대조군 p0 갱신 지연 최대가 한 틱 이상이다: " + run.maxRenewMillis);
            assertTrue(run.maxAcquireMillis >= HOLD_MILLIS - 1_500,
                    "대조군 p1 틱이 잠금 보유 동안 돌아왔다(상한 없는 획득이 막히지 않았다): " + run.maxAcquireMillis);
        }
    }

    /** 한 번의 실행 결과. */
    private record Run(boolean p0Kept, long maxRenewMillis, long maxAcquireMillis, boolean p1AcquiredAfterRelease) {
    }

    private static Run run(PartitionLeaseDb db, HikariDataSource pool, LeaderLeaseStore p0Store,
                           LeaderLeaseStore p1Store) throws Exception {
        Timed p0 = new Timed(p0Store);
        Timed p1 = new Timed(p1Store);
        String holder = "node-a";
        LeaderLeaseService p0Lease = new LeaderLeaseService(P0, holder, p0, FencingEpochPublisher.NONE, TTL, TICK,
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress());
        LeaderLeaseService p1Lease = new LeaderLeaseService(P1, holder, p1, FencingEpochPublisher.NONE, TTL, TICK,
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress());
        AtomicBoolean lost = new AtomicBoolean();
        p0Lease.onLeadershipLost(() -> lost.set(true));
        assertTrue(p0Lease.tryAcquire(), "p0 획득 실패");
        LeaderTerm p0Term = p0Lease.currentTerm().orElseThrow();
        db.expire(P1);

        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> checkerErrors = new CopyOnWriteArrayList<>();
        try (Connection holderConn = db.owner()) {
            holderConn.setAutoCommit(false);
            try (Statement st = holderConn.createStatement()) {
                st.execute("SELECT 1 FROM leader_lease WHERE resource = '" + P1 + "' FOR KEY SHARE");
            }
            p0Lease.start();
            p1Lease.start();
            // 1초마다 p0 fencing 재확인과 현재 리스 조회를 끼운다. 둘 다 같은 리스 풀을 쓴다.
            Thread checker = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        p0Lease.demoteUnlessStillLeader(p0Term, "시험: fencing 재확인");
                        p0Lease.leaseSnapshot();
                        Thread.sleep(1_000);
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException e) {
                        checkerErrors.add(e);
                    }
                }
            }, "renew-checker");
            checker.setDaemon(true);
            checker.start();
            Thread.sleep(HOLD_MILLIS);
            holderConn.rollback();
            stop.set(true);
            checker.interrupt();
            checker.join(5_000);
        }
        // 잠금이 풀린 뒤 한두 틱 안에 p1 을 얻는다. 막힌 획득이 끝날 때까지 기다린다.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!p1Lease.isLeader() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        boolean p1Acquired = p1Lease.isLeader();
        boolean p0Kept = !lost.get() && p0Lease.isCurrent(p0Term);
        p0Lease.stop();
        p1Lease.stop();
        assertTrue(checkerErrors.isEmpty(), "재확인·조회가 실패했다: " + checkerErrors);
        assertFalse(p0Lease.isNodeFatal());
        return new Run(p0Kept, p0.maxRenewMillis.get(), p1.maxAcquireMillis.get(), p1Acquired);
    }

    private static HikariDataSource leasePool(PartitionLeaseDb db) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.url());
        config.setUsername(PartitionLeaseDb.OWNER);
        config.setPassword(PartitionLeaseDb.OWNER);
        // 운영 리스 풀과 같은 크기(LeaseDataSourceConfig, DB 설계 §5.4-c 3).
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(2);
        return new HikariDataSource(config);
    }

    private static String show(Connection c) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW lock_timeout")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** 갱신·획득 소요의 최대를 잰다. */
    private static final class Timed implements LeaderLeaseStore {
        private final LeaderLeaseStore delegate;
        final AtomicLong maxRenewMillis = new AtomicLong();
        final AtomicLong maxAcquireMillis = new AtomicLong();

        Timed(LeaderLeaseStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Long> acquire(String holder, Duration ttl) {
            long t0 = System.nanoTime();
            try {
                return delegate.acquire(holder, ttl);
            } finally {
                maxAcquireMillis.accumulateAndGet((System.nanoTime() - t0) / 1_000_000, Math::max);
            }
        }

        @Override
        public boolean renew(String holder, long epoch, Duration ttl) {
            long t0 = System.nanoTime();
            try {
                return delegate.renew(holder, epoch, ttl);
            } finally {
                maxRenewMillis.accumulateAndGet((System.nanoTime() - t0) / 1_000_000, Math::max);
            }
        }

        @Override
        public void release(String holder, long epoch) {
            delegate.release(holder, epoch);
        }

        @Override
        public Optional<LeaseSnapshot> current() {
            return delegate.current();
        }

        @Override
        public double priorDowntimeSeconds(Duration ttl) {
            return 0;
        }

        @Override
        public String describe() {
            return "timed(" + delegate.describe() + ")";
        }
    }

    /** S3 배선의 대조군. 같은 풀에서 자동 커밋 한 문장으로 얻고 잠금 대기 상한이 없다. */
    private record UnboundedStore(JdbcClient jdbc, String resource) implements LeaderLeaseStore {

        @Override
        public Optional<Long> acquire(String holder, Duration ttl) {
            return jdbc.sql("SELECT try_acquire_lease(?, ?, ? * interval '1 millisecond', 3::smallint)")
                    .params(resource, holder, ttl.toMillis())
                    .query(Long.class)
                    .optional();
        }

        @Override
        public boolean renew(String holder, long epoch, Duration ttl) {
            return Boolean.TRUE.equals(jdbc.sql("SELECT renew_lease(?, ?, ?, ? * interval '1 millisecond')")
                    .params(resource, holder, epoch, ttl.toMillis())
                    .query(Boolean.class)
                    .single());
        }

        @Override
        public void release(String holder, long epoch) {
            jdbc.sql("SELECT release_lease(?, ?, ?)").params(resource, holder, epoch).query(Boolean.class).single();
        }

        @Override
        public Optional<LeaseSnapshot> current() {
            return jdbc.sql("SELECT holder, epoch, lease_until FROM leader_lease WHERE resource = ?")
                    .param(resource)
                    .query((rs, rowNum) -> new LeaseSnapshot(rs.getString("holder"), rs.getLong("epoch"),
                            rs.getObject("lease_until", java.time.OffsetDateTime.class).toInstant()))
                    .optional();
        }

        @Override
        public double priorDowntimeSeconds(Duration ttl) {
            return 0;
        }

        @Override
        public String describe() {
            return "unbounded(" + resource + ")";
        }
    }
}

package kr.exchange.journal;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 리스 행 잠금 모드 계약(ADR 0026, V41). 쓰기 경로의 FOR KEY SHARE 가 갱신은 막지 않고 승격과
 * 게시는 커밋까지 막는지를 행동으로 본다. 리스 행은 하나라 시험마다 절대값으로 다시 심는다.
 * 커밋까지 기다리는 경우는 pg_blocking_pids 로 대기 대상을 확인하고, 기다리지 않거나 막히는지만
 * 보는 경우는 lock_timeout 초과(55P03) 여부로 판정한다.
 */
@Testcontainers
class LeaderLeaseLockModeIntegrationTest {

    private static final String LOCK_TIMEOUT = "55P03";
    private static final String QUERY_CANCELED = "57014";
    private static final String FENCING_REJECTED = "P0001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    @BeforeAll
    static void migrate() {
        var result = flyway(POSTGRES.getJdbcUrl()).load().migrate();
        assertTrue(result.success, "Flyway migration failed");
    }

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    @Test
    void fencingKeyIndexCoversHolderAndEpochAsKeyColumns() throws Exception {
        try (Connection c = open()) {
            assertEquals("true|resource,holder,epoch", queryString(c, """
                    SELECT (i.indisunique AND i.indisvalid AND i.indpred IS NULL AND i.indexprs IS NULL
                            AND i.indnkeyatts = 3)::text
                           || '|' ||
                           (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                            FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                            WHERE k.ord <= i.indnkeyatts)
                    FROM pg_index i
                    WHERE i.indexrelid = 'uq_leader_lease_fencing_key'::regclass
                    """));
        }
    }

    @Test
    void renewDoesNotWaitForWriterKeyShare() throws Exception {
        try (Connection writer = open(); Connection renewer = open(); Connection publisher = open()) {
            seedLease(writer, "node-a", 5, 60);
            lockAsWriter(writer, "node-a", 5);

            setLockTimeout(renewer, "500ms");
            assertEquals("t", queryString(renewer,
                    "SELECT renew_leader_lease('node-a', 5, interval '6 seconds')"));

            // 잠금이 실제로 쥐어져 있고 갱신 뒤에도 이어진다는 양성 축.
            setLockTimeout(publisher, "500ms");
            SQLException blocked = assertThrows(SQLException.class,
                    () -> queryString(publisher, "SELECT publish_leader_epoch('node-b', 6)"));
            assertEquals(LOCK_TIMEOUT, blocked.getSQLState(), blocked.getMessage());

            // holder·epoch 가 그대로인 게시는 키 열을 바꾸지 않아 기다리지 않는다.
            assertEquals("5", queryString(publisher, "SELECT publish_leader_epoch('node-a', 5)"));

            writer.rollback();
            assertEquals("node-a|5", leaseRow(renewer));
        }
    }

    @Test
    void promotionWaitsForWriterCommitAcrossRenewAndExpire() throws Exception {
        try (Connection writer = open(); Connection other = open(); Connection waiter = open();
             Connection observer = open()) {
            seedLease(writer, "node-a", 5, 60);
            int writerPid = lockAsWriter(writer, "node-a", 5);

            // 만료는 쓰기가 잠금을 잡은 뒤에 한다. 먼저 만료시키면 판정이 예외로 끝나 잠금이 풀린다.
            setLockTimeout(other, "500ms");
            assertEquals("t", queryString(other, "SELECT renew_leader_lease('node-a', 5, interval '6 seconds')"));
            other.createStatement().execute("SELECT expire_leader_lease_now()");

            setStatementTimeout(waiter, "10s");
            int waiterPid = pid(waiter);
            Future<String> acquire = submit(waiter, "SELECT try_acquire_leader('node-b', interval '6 seconds')");
            awaitBlocked(observer, waiterPid, writerPid);
            assertFalse(acquire.isDone(), "승격이 쓰기 커밋 전에 끝났다");

            assertEquals("node-a|5", leaseRow(writer), "쓰기 트랜잭션 안에서 holder·epoch 가 바뀌었다");
            writer.commit();

            assertEquals("6", acquire.get(5, TimeUnit.SECONDS));
            assertEquals("node-b|6", leaseRow(observer));
        }
    }

    @Test
    void publishWaitsForWriterCommit() throws Exception {
        try (Connection writer = open(); Connection waiter = open(); Connection observer = open()) {
            seedLease(writer, "node-a", 5, 60);
            int writerPid = lockAsWriter(writer, "node-a", 5);

            setStatementTimeout(waiter, "10s");
            int waiterPid = pid(waiter);
            Future<String> publish = submit(waiter, "SELECT publish_leader_epoch('node-b', 6)");
            awaitBlocked(observer, waiterPid, writerPid);
            assertFalse(publish.isDone(), "게시가 쓰기 커밋 전에 끝났다");

            writer.commit();
            assertEquals("6", publish.get(5, TimeUnit.SECONDS));
            assertEquals("node-b|6", leaseRow(observer));
        }
    }

    @Test
    void commandAppendKeyShareBlocksPromotionNotRenew() throws Exception {
        try (Connection writer = open(); Connection renewer = open(); Connection publisher = open()) {
            seedLease(writer, "node-a", 5, 60);
            writer.setAutoCommit(false);
            try {
                assertEquals(1, appendCommand(writer, 500_000_101L, "node-a", 5));

                setLockTimeout(renewer, "500ms");
                assertEquals("t", queryString(renewer,
                        "SELECT renew_leader_lease('node-a', 5, interval '6 seconds')"));

                setLockTimeout(publisher, "500ms");
                SQLException blocked = assertThrows(SQLException.class,
                        () -> queryString(publisher, "SELECT publish_leader_epoch('node-b', 6)"));
                assertEquals(LOCK_TIMEOUT, blocked.getSQLState(), blocked.getMessage());
            } finally {
                // command_journal 은 append-only 라 커밋한 행을 지울 수 없다.
                writer.rollback();
            }
        }
    }

    @Test
    void writerWaitingOnPromotionIsRejectedAfterCommit() throws Exception {
        try (Connection promoter = open(); Connection writer = open(); Connection observer = open()) {
            seedLease(promoter, "node-a", 5, 60);
            promoter.setAutoCommit(false);
            int promoterPid = pid(promoter);
            assertEquals("6", queryString(promoter, "SELECT publish_leader_epoch('node-b', 6)"));

            writer.setAutoCommit(false);
            int writerPid = pid(writer);
            Future<String> lock = submit(writer, "SELECT assert_and_lock_leader('node-a', 5)");
            awaitBlocked(observer, writerPid, promoterPid);
            promoter.commit();

            ExecutionException e = assertThrows(ExecutionException.class, () -> lock.get(5, TimeUnit.SECONDS));
            SQLException rejected = assertInstanceOf(SQLException.class, e.getCause());
            assertEquals(FENCING_REJECTED, rejected.getSQLState(), rejected.getMessage());
            assertTrue(rejected.getMessage().contains("fencing 위반"), rejected.getMessage());
            writer.rollback();
        }

        try (Connection promoter = open(); Connection writer = open(); Connection observer = open()) {
            seedLease(promoter, "node-a", 5, 60);
            promoter.setAutoCommit(false);
            int promoterPid = pid(promoter);
            assertEquals("6", queryString(promoter, "SELECT publish_leader_epoch('node-b', 6)"));

            writer.setAutoCommit(false);
            int writerPid = pid(writer);
            Future<Integer> append = POOL.submit(() -> appendCommand(writer, 500_000_102L, "node-a", 5));
            awaitBlocked(observer, writerPid, promoterPid);
            promoter.commit();

            // 대기 뒤 재검사에서 epoch 불일치로 0행이 되어야 stale append 가 커밋되지 않는다.
            assertEquals(0, append.get(5, TimeUnit.SECONDS));
            writer.rollback();
        }
    }

    /** E1 기전의 재현과 대조군. 같은 부하 루프가 FOR SHARE 에서는 갱신을 굶기고 V41 경로에서는 굶기지 않는다. */
    @Test
    void overlappingWriterLocksStarveRenewOnlyUnderForShare() throws Exception {
        try (Connection admin = open(); Connection renewer = open(); Connection observer = open()) {
            seedLease(admin, "node-a", 5, 120);
            setStatementTimeout(renewer, "2s");
            WriterLoad control = WriterLoad.start(
                    "SELECT epoch FROM leader_lease WHERE resource = 'matching-engine' FOR SHARE");
            int canceled = 0;
            try {
                Thread.sleep(1_000);
                assertTrue(rowShareHolders(observer) >= 2, "대조 부하가 리스 행 잠금을 겹쳐 쥐지 않았다");
                for (int i = 0; i < 5; i++) {
                    try {
                        queryString(renewer, "SELECT renew_leader_lease('node-a', 5, interval '120 seconds')");
                    } catch (SQLException e) {
                        assertEquals(QUERY_CANCELED, e.getSQLState(), e.getMessage());
                        canceled++;
                    }
                    Thread.sleep(300);
                }
            } finally {
                control.stop();
            }
            assertTrue(control.commits() > 0, "대조 부하가 커밋하지 않았다");
            assertTrue(canceled >= 1, "FOR SHARE 부하에서 갱신이 한 번도 굶지 않았다. 부하가 잠금 경합을 만들지 못한 것이다");

            seedLease(admin, "node-a", 5, 120);
            // 갱신이 잠금을 조금이라도 기다리면 55P03 으로 실패한다. 아래 경과 시간 단언은 멈춤 방지용이다.
            setLockTimeout(renewer, "100ms");
            WriterLoad fixed = WriterLoad.start("SELECT assert_and_lock_leader('node-a', 5)");
            long maxMillis = 0;
            try {
                Thread.sleep(1_000);
                assertTrue(rowShareHolders(observer) >= 2, "V41 부하가 리스 행 잠금을 겹쳐 쥐지 않았다");
                String previousUntil = queryString(observer,
                        "SELECT lease_until::text FROM leader_lease WHERE resource = 'matching-engine'");
                for (int i = 0; i < 10; i++) {
                    long started = System.nanoTime();
                    assertEquals("t", queryString(renewer,
                            "SELECT renew_leader_lease('node-a', 5, interval '120 seconds')"));
                    maxMillis = Math.max(maxMillis, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    String until = queryString(observer,
                            "SELECT lease_until::text FROM leader_lease WHERE resource = 'matching-engine'");
                    assertTrue(queryBool(observer, "SELECT '" + until + "'::timestamptz > '" + previousUntil + "'::timestamptz"),
                            "갱신이 lease_until 을 밀지 않았다");
                    previousUntil = until;
                    Thread.sleep(300);
                }
            } finally {
                fixed.stop();
            }
            assertTrue(fixed.commits() > 0, "V41 부하가 커밋하지 않았다");
            assertTrue(maxMillis < 500, "쓰기 잠금이 겹친 동안 갱신이 " + maxMillis + "ms 걸렸다");
        }
    }

    /** 인덱스가 없으면 FOR KEY SHARE 가 승격을 막지 못한다. 위 시험들이 인덱스 제거를 검출한다는 대조다. */
    @Test
    void promotionDoesNotWaitWithoutFencingKeyIndex() throws Exception {
        String url = createDatabase("lease_no_key");
        flyway(url).load().migrate();
        try (Connection writer = open(url); Connection other = open(url)) {
            other.createStatement().execute("DROP INDEX uq_leader_lease_fencing_key");
            seedLease(writer, "node-a", 5, 60);
            lockAsWriter(writer, "node-a", 5);
            other.createStatement().execute("SELECT expire_leader_lease_now()");

            setLockTimeout(other, "500ms");
            assertEquals("6", queryString(other, "SELECT try_acquire_leader('node-b', interval '6 seconds')"));
            assertEquals("node-b|6", leaseRow(writer), "인덱스 없이도 승격이 쓰기를 기다렸다");
            writer.rollback();
        }
    }

    /** 부하 스레드 다섯이 70ms 간격으로 시작해 [잠금, 0.3초 보유, 커밋] 을 반복한다. */
    private static final class WriterLoad {
        private final List<Future<?>> workers = new ArrayList<>();
        private final AtomicLong commits = new AtomicLong();
        private volatile boolean running = true;

        static WriterLoad start(String lockSql) throws InterruptedException {
            WriterLoad load = new WriterLoad();
            for (int i = 0; i < 5; i++) {
                load.workers.add(POOL.submit(() -> {
                    try (Connection c = open()) {
                        c.setAutoCommit(false);
                        while (load.running) {
                            try (Statement st = c.createStatement()) {
                                st.execute(lockSql);
                                st.execute("SELECT pg_sleep(0.3)");
                            }
                            c.commit();
                            load.commits.incrementAndGet();
                        }
                    }
                    return null;
                }));
                Thread.sleep(70);
            }
            return load;
        }

        void stop() throws Exception {
            running = false;
            for (Future<?> w : workers) {
                w.get(10, TimeUnit.SECONDS);
            }
        }

        long commits() {
            return commits.get();
        }
    }

    private static int appendCommand(Connection c, long seq, String holder, long epoch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CommandJournalAppender.INSERT_SQL)) {
            ps.setLong(1, seq);
            ps.setInt(2, 1);
            ps.setString(3, "NEW");
            ps.setLong(4, 1L);
            ps.setString(5, "lockmode-" + seq);
            ps.setLong(6, seq);
            ps.setLong(7, 0L);
            ps.setLong(8, 0L);
            ps.setString(9, "{}");
            ps.setLong(10, 0L);
            ps.setString(11, holder);
            ps.setLong(12, epoch);
            return ps.executeUpdate();
        }
    }

    /** 쓰기 경로와 같은 함수로 리스 행을 잡고 트랜잭션을 열어 둔다. 그 백엔드 pid 를 돌려준다. */
    private static int lockAsWriter(Connection c, String holder, long epoch) throws SQLException {
        c.setAutoCommit(false);
        int pid = pid(c);
        assertEquals(String.valueOf(epoch),
                queryString(c, "SELECT assert_and_lock_leader('" + holder + "', " + epoch + ")"));
        return pid;
    }

    private static void seedLease(Connection c, String holder, long epoch, int seconds) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE leader_lease
                SET holder = ?, epoch = ?, lease_until = clock_timestamp() + make_interval(secs => ?)
                WHERE resource = 'matching-engine'
                """)) {
            ps.setString(1, holder);
            ps.setLong(2, epoch);
            ps.setInt(3, seconds);
            assertEquals(1, ps.executeUpdate());
        }
        if (!c.getAutoCommit()) {
            c.commit();
        }
    }

    private static void awaitBlocked(Connection observer, int waiterPid, int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (PreparedStatement ps = observer.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")) {
            ps.setInt(1, blockerPid);
            ps.setInt(2, waiterPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        fail("pid " + waiterPid + " 가 pid " + blockerPid + " 에 막히지 않았다");
    }

    private static long rowShareHolders(Connection observer) throws SQLException {
        return Long.parseLong(queryString(observer, """
                SELECT count(*) FROM pg_locks
                WHERE relation = 'leader_lease'::regclass AND mode = 'RowShareLock' AND granted
                """));
    }

    private static Future<String> submit(Connection c, String sql) {
        return POOL.submit(() -> queryString(c, sql));
    }

    private static String leaseRow(Connection c) throws SQLException {
        return queryString(c, "SELECT holder || '|' || epoch FROM leader_lease WHERE resource = 'matching-engine'");
    }

    private static int pid(Connection c) throws SQLException {
        return Integer.parseInt(queryString(c, "SELECT pg_backend_pid()"));
    }

    private static void setLockTimeout(Connection c, String value) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET lock_timeout = '" + value + "'");
        }
    }

    private static void setStatementTimeout(Connection c, String value) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET statement_timeout = '" + value + "'");
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    private static String createDatabase(String name) throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
    }

    private static Connection open() throws SQLException {
        return open(POSTGRES.getJdbcUrl());
    }

    private static Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean queryBool(Connection c, String sql) throws SQLException {
        return "t".equals(queryString(c, sql));
    }

    private static String queryString(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getString(1);
        }
    }
}

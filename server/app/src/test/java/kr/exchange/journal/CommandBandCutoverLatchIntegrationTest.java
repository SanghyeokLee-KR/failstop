package kr.exchange.journal;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V44 명령 대역 커트오버 래치(ADR 0022 7절 6단계). 래치 행은 지울 수 없으므로 시험마다 같은 컨테이너의 별도
 * 데이터베이스를 쓴다. 래치 전 확인을 깨는 손상은 롤백 트랜잭션 안에서만 만든다.
 */
@Testcontainers
class CommandBandCutoverLatchIntegrationTest {

    private static final String HOLDER = "latch-node";
    private static final long EPOCH = 7;
    private static final long USER = 5_001L;
    private static final int MARKETS = 3;
    private static final String PROLOGUE5 = "SELECT out_seq FROM append_command_prologue(?, ?, ?, ?, ?)";
    private static final String PROLOGUE6 = "SELECT out_seq FROM append_command_prologue(?, ?, ?, ?, ?, ?::smallint)";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private record Prologue(long seq, boolean replayed) {
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection conn) throws Exception;
    }

    @Test
    void beforeLatchSixArgNumbersGlobalCounterLikeFiveArg() throws Exception {
        String url = createDatabase();
        try (Connection c = open(url)) {
            long next = globalCounter(c);
            assertEquals(next, prologue6(c, 2, "pre-1").seq());
            assertEquals(next + 1, prologue6(c, 3, "pre-2").seq());
            assertEquals(next + 2, prologue5(c, "pre-3").seq());
            assertEquals(next + 3, globalCounter(c));
            assertEquals(MARKETS, queryLong(c, "SELECT count(*) FROM market_journal_counters "
                    + "WHERE name = 'command_seq' AND next_value = 1"), "래치 전에 마켓 대역 카운터가 움직였다");
            assertEquals(0, queryLong(c, "SELECT count(*) FROM journal_cutover"));
        }
    }

    @Test
    void afterLatchSixArgNumbersMarketBandAndReplayStaysAheadOfNumbering() throws Exception {
        String url = createDatabase();
        try (Connection c = open(url)) {
            long span = span(c);
            long next = globalCounter(c);
            assertNotNull(latch(c));
            assertEquals(1, queryLong(c, "SELECT count(*) FROM journal_cutover WHERE name = 'command_seq' "
                    + "AND latched_by = '" + POSTGRES.getUsername() + "'"));

            assertEquals(2 * span + 1, prologue6(c, 2, "band-1").seq());
            assertEquals(2 * span + 2, prologue6(c, 2, "band-2").seq());
            assertEquals(3 * span + 1, prologue6(c, 3, "band-3").seq());
            assertEquals(next, globalCounter(c), "래치 뒤에 전역 카운터가 움직였다");

            // 멱등 판정이 채번 앞이다. 저널에 있는 명령의 재전송은 대역 번호를 태우지 않는다.
            assertEquals("command_journal_b2", insertNew(c, 2 * span + 1, 2, "band-1"));
            Prologue replay = prologue6(c, 2, "band-1");
            assertTrue(replay.replayed(), "저널에 있는 명령을 재전송으로 판정하지 않았다");
            assertEquals(2 * span + 1, replay.seq());
            assertEquals(3, queryLong(c, "SELECT next_value FROM market_journal_counters "
                    + "WHERE market_id = 2 AND name = 'command_seq'"));
        }
    }

    @Test
    void latchRefusesMissingMarketBandLeaf() throws Exception {
        assertLatchRefused(conn -> execute(conn, "ALTER TABLE command_journal DETACH PARTITION command_journal_b2"),
                "래치 전 확인 1 실패: 대역 잎이 없는 마켓 2");
    }

    @Test
    void latchRefusesMissingTailLeaf() throws Exception {
        assertLatchRefused(
                conn -> execute(conn, "ALTER TABLE command_journal DETACH PARTITION command_journal_p200000000"),
                "래치 전 확인 1 실패: 꼬리 잎 command_journal_p200000000 이 없다");
    }

    @Test
    void latchRefusesMissingMarketCommandCounter() throws Exception {
        assertLatchRefused(conn -> assertEquals(1, update(conn,
                        "DELETE FROM market_journal_counters WHERE market_id = 3 AND name = 'command_seq'")),
                "래치 전 확인 2 실패: command_seq 카운터가 없는 마켓 3");
    }

    @Test
    void latchRefusesRowsInDefaultPartition() throws Exception {
        assertLatchRefused(conn -> assertEquals("command_journal_default",
                        insertNew(conn, 9 * span(conn) + 1, 9, "default-row")),
                "래치 전 확인 3 실패: command_journal_default 에 1행이 있다");
    }

    @Test
    void secondLatchIsRefusedAndFirstRowStays() throws Exception {
        String url = createDatabase();
        try (Connection c = open(url)) {
            OffsetDateTime first = latch(c);
            SQLException again = assertThrows(SQLException.class, () -> latch(c));
            assertEquals("55000", again.getSQLState(), again.getMessage());
            assertTrue(again.getMessage().contains("래치가 이미 있다"), again.getMessage());
            assertEquals(1, queryLong(c, "SELECT count(*) FROM journal_cutover"));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT latched_at FROM journal_cutover WHERE name = 'command_seq'")) {
                assertTrue(rs.next());
                assertEquals(first, rs.getObject(1, OffsetDateTime.class));
            }
        }
    }

    @Test
    void cutoverRowRejectsUpdateDeleteAndTruncate() throws Exception {
        String url = createDatabase();
        try (Connection c = open(url)) {
            latch(c);
            for (String mutation : List.of(
                    "UPDATE journal_cutover SET latched_by = 'someone-else'",
                    "DELETE FROM journal_cutover",
                    "TRUNCATE journal_cutover")) {
                SQLException rejected = assertThrows(SQLException.class, () -> execute(c, mutation));
                assertEquals("P0001", rejected.getSQLState(), mutation + ": " + rejected.getMessage());
                assertTrue(rejected.getMessage().contains("append-only 위반"), rejected.getMessage());
            }
            assertEquals(1, queryLong(c, "SELECT count(*) FROM journal_cutover WHERE latched_by = '"
                    + POSTGRES.getUsername() + "'"));
        }
    }

    @Test
    void afterLatchFiveArgRefusalIsNotDemotionButStaleLeaderIsStillFenced() throws Exception {
        String url = createDatabase();
        long span;
        try (Connection c = open(url)) {
            span = span(c);
            latch(c);
        }
        JdbcClient jdbc = JdbcClient.create(
                new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));

        DataAccessException refused = assertThrows(DataAccessException.class,
                () -> jdbc.sql(PROLOGUE5).params(HOLDER, EPOCH, true, USER, "old-app").query(Long.class).single());
        assertEquals("55000", sqlState(refused), FencingErrors.rejectionDetail(refused));
        assertFalse(FencingErrors.isFencingRejection(refused),
                "래치 거부가 강등 사유로 분류됐다: " + FencingErrors.rejectionDetail(refused));

        // 리더 검증이 래치 거부보다 앞이라 구 리더는 두 판 모두에서 강등 사유를 받는다.
        DataAccessException stale5 = assertThrows(DataAccessException.class,
                () -> jdbc.sql(PROLOGUE5).params(HOLDER, EPOCH - 1, true, USER, "stale-5").query(Long.class).single());
        assertTrue(FencingErrors.isFencingRejection(stale5), FencingErrors.rejectionDetail(stale5));
        DataAccessException stale6 = assertThrows(DataAccessException.class,
                () -> jdbc.sql(PROLOGUE6).params(HOLDER, EPOCH - 1, true, USER, "stale-6", 1).query(Long.class).single());
        assertTrue(FencingErrors.isFencingRejection(stale6), FencingErrors.rejectionDetail(stale6));

        assertEquals(span + 1, jdbc.sql(PROLOGUE6).params(HOLDER, EPOCH, true, USER, "new-app", 1)
                .query(Long.class).single());
    }

    @Test
    void appRoleCannotLatchButNumbersBandsThroughSixArg() throws Exception {
        String url = createDatabase();
        try (Connection c = open(url)) {
            assertFalse(queryBool(c,
                            "SELECT has_function_privilege('app_role', 'latch_command_band_cutover()', 'EXECUTE')"),
                    "app_role 이나 PUBLIC 에 래치 EXECUTE 가 남았다");
            assertTrue(queryBool(c, "SELECT has_function_privilege('app_role', 'append_command_prologue("
                    + "text, bigint, boolean, bigint, character varying, smallint)', 'EXECUTE')"));
            assertTrue(queryBool(c, "SELECT has_table_privilege('app_role', 'journal_cutover', 'SELECT')"));
            assertFalse(queryBool(c, "SELECT has_table_privilege('app_role', 'journal_cutover', 'INSERT')"));
            long span = span(c);

            // 수퍼유저는 권한 검사를 건너뛰므로 롤을 바꿔 실제 거부를 본다.
            inTransaction(c, () -> {
                execute(c, "SET LOCAL ROLE app_role");
                SQLException denied = assertThrows(SQLException.class, () -> latch(c));
                assertEquals("42501", denied.getSQLState(), denied.getMessage());
            });
            latch(c);
            inTransaction(c, () -> {
                execute(c, "SET LOCAL ROLE app_role");
                assertEquals(span + 1, prologue6(c, 1, "app-role-band").seq());
            });
        }
    }

    /** 손상한 DB 는 래치를 거부하고 같은 DB 가 손상 없이는 래치를 받는다. 무조건 거부하는 검사를 가른다. */
    private static void assertLatchRefused(SqlWork damage, String expected) throws Exception {
        String url = createDatabase();
        inRollback(url, conn -> {
            damage.run(conn);
            SQLException refused = assertThrows(SQLException.class, () -> latch(conn));
            assertEquals("55000", refused.getSQLState(), refused.getMessage());
            assertTrue(refused.getMessage().contains(expected), refused.getMessage());
        });
        inRollback(url, conn -> assertNotNull(latch(conn)));
        try (Connection conn = open(url)) {
            assertEquals(0, queryLong(conn, "SELECT count(*) FROM journal_cutover"), "롤백한 래치 행이 남았다");
        }
    }

    private static OffsetDateTime latch(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT latch_command_band_cutover()")) {
            assertTrue(rs.next(), "래치 함수가 행을 돌려주지 않았다");
            return rs.getObject(1, OffsetDateTime.class);
        }
    }

    private static Prologue prologue6(Connection c, int marketId, String clientOrderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT out_seq, out_replayed FROM append_command_prologue(?, ?, TRUE, ?, ?, ?::smallint)")) {
            ps.setString(1, HOLDER);
            ps.setLong(2, EPOCH);
            ps.setLong(3, USER);
            ps.setString(4, clientOrderId);
            ps.setInt(5, marketId);
            return prologue(ps);
        }
    }

    private static Prologue prologue5(Connection c, String clientOrderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT out_seq, out_replayed FROM append_command_prologue(?, ?, TRUE, ?, ?)")) {
            ps.setString(1, HOLDER);
            ps.setLong(2, EPOCH);
            ps.setLong(3, USER);
            ps.setString(4, clientOrderId);
            return prologue(ps);
        }
    }

    private static Prologue prologue(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "머리말이 행을 돌려주지 않았다");
            return new Prologue(rs.getLong("out_seq"), rs.getBoolean("out_replayed"));
        }
    }

    /** NEW 명령 한 행을 넣고 담긴 잎 이름을 돌려준다. */
    private static String insertNew(Connection c, long seq, int marketId, String clientOrderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO command_journal
                  (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                   engine_ts, tie_break, payload, fencing_token, checksum)
                VALUES (?, ?, 'NEW', ?, ?, ?, 0, 0, '{}'::jsonb, ?, 0)
                RETURNING tableoid::regclass::text
                """)) {
            ps.setLong(1, seq);
            ps.setInt(2, marketId);
            ps.setLong(3, USER);
            ps.setString(4, clientOrderId);
            ps.setLong(5, seq);
            ps.setLong(6, EPOCH);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "command_journal 삽입이 행을 돌려주지 않았다");
                return rs.getString(1);
            }
        }
    }

    /** 전 마이그레이션, 자산, 마켓 1..3(마켓 트리거가 대역 잎과 카운터를 만든다), 이 시험의 리더 리스를 올린다. */
    private static String createDatabase() throws SQLException {
        String name = "cutover_latch_" + DATABASES.incrementAndGet();
        try (Connection c = open(POSTGRES.getJdbcUrl()); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
        assertTrue(Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate().success, "Flyway migration failed");
        try (Connection c = open(url); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                        (1, 'KRW', 'Korean Won', 'FIAT', 0),
                        (2, 'BTC', 'Bitcoin', 'CRYPTO', 8)
                    """);
            for (int id = 1; id <= MARKETS; id++) {
                st.execute("""
                        INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                             tick_size, lot_size, min_notional, max_notional, max_qty)
                        VALUES (%d, 'LATCH-%d', 2, 1, 1000, 0.0001, 5000, 100000000000, 10000)
                        """.formatted(id, id));
            }
            assertEquals(1, st.executeUpdate("UPDATE leader_lease SET holder = '" + HOLDER + "', epoch = " + EPOCH
                    + ", lease_until = clock_timestamp() + interval '10 minutes' WHERE resource = 'matching-engine'"));
        }
        return url;
    }

    @FunctionalInterface
    private interface TxWork {
        void run() throws Exception;
    }

    private static void inTransaction(Connection c, TxWork work) throws Exception {
        c.setAutoCommit(false);
        try {
            work.run();
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
    }

    private static void inRollback(String url, SqlWork work) throws Exception {
        try (Connection c = open(url)) {
            c.setAutoCommit(false);
            try {
                work.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    private static long span(Connection c) throws SQLException {
        return queryLong(c, "SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'");
    }

    private static long globalCounter(Connection c) throws SQLException {
        return queryLong(c, "SELECT next_value FROM journal_counters WHERE name = 'command_seq'");
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static int update(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            return st.executeUpdate(sql);
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getLong(1);
        }
    }

    private static boolean queryBool(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getBoolean(1);
        }
    }
}

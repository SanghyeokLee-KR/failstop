package kr.exchange;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V40 대역 채번 기반의 계약 고정(ADR 0022 1단계). 시험마다 다른 마켓 id 를 써서 실행 순서에
 * 기대지 않는다. 저널 행을 넣는 시험은 트랜잭션을 롤백한다. command_journal 은 append-only 라
 * 커밋한 행을 지울 수 없다. 레거시 DB 시험은 같은 컨테이너의 별도 데이터베이스를 V39 까지 올려
 * market_id 없는 이벤트를 합성한 뒤 V40 을 적용한다. 이벤트 축 명세와 상한은 V46 이 바꿨고
 * EventSeqBandWidenMigrationIntegrationTest 가 본다.
 */
@Testcontainers
class CommandSeqBandMigrationIntegrationTest {

    private static final long COMMAND_SPAN = 1_000_000_000_000L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    @BeforeAll
    static void migrate() throws SQLException {
        var result = flyway(POSTGRES.getJdbcUrl()).load().migrate();
        assertTrue(result.success, "Flyway migration failed");
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                        (1, 'KRW', 'Korean Won', 'FIAT', 0),
                        (2, 'BTC', 'Bitcoin', 'CRYPTO', 8)
                    ON CONFLICT (id) DO NOTHING
                    """);
        }
    }

    @Test
    void bandSpecHoldsCommandAxis() throws Exception {
        try (Connection conn = open()) {
            assertEquals(COMMAND_SPAN + "|63",
                    queryString(conn, "SELECT band_span || '|' || max_band_index FROM journal_band_spec WHERE name = 'command_seq'"));
        }
    }

    @Test
    void axisBoundaryIsZeroOnFreshDatabase() throws Exception {
        try (Connection conn = open()) {
            assertEquals("0", queryString(conn,
                    "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'"));
        }
    }

    @Test
    void legacyBoundaryIsFrozenWhenEveryCommandHasEvents() throws Exception {
        String url = createDatabaseAtV39("band_legacy_ok");
        try (Connection conn = open(url); Statement st = conn.createStatement()) {
            insertLegacyCommands(st);
            insertLegacyEvent(st, 1, 1);
            insertLegacyEvent(st, 2, 2);
        }
        flyway(url).load().migrate();
        try (Connection conn = open(url)) {
            assertEquals("2", queryString(conn,
                    "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'"));
        }
    }

    /** 경계 이하에 이벤트 없는 명령이 있으면 단일 라이터 전제가 깨진 것이라 V40 이 멈춰야 한다. */
    @Test
    void legacyCommandWithoutEventStopsMigration() throws Exception {
        String url = createDatabaseAtV39("band_legacy_hole");
        try (Connection conn = open(url); Statement st = conn.createStatement()) {
            insertLegacyCommands(st);
            insertLegacyEvent(st, 2, 2);
        }
        FlywayException e = assertThrows(FlywayException.class, () -> flyway(url).load().migrate());
        assertTrue(e.getMessage().contains("이벤트 없는 명령 1건"), e.getMessage());
        try (Connection conn = open(url)) {
            assertEquals("39", queryString(conn,
                    "SELECT max(version::int) FROM flyway_schema_history WHERE success"));
        }
    }

    @Test
    void tailPartitionTakesSeqBeyondLegacyLeaves() throws Exception {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                assertEquals("command_journal_p200000000", insertCommandAt(conn, 500_000_000L, 1));
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void tailUpperBoundMeetsFirstBandLeaf() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            insertMarket(st, 1);
            assertEquals("FOR VALUES FROM ('200000000') TO ('1000000000000')",
                    partitionBound(conn, "command_journal_p200000000"));
            assertEquals("FOR VALUES FROM ('1000000000000') TO ('2000000000000')",
                    partitionBound(conn, "command_journal_b1"));
        }
    }

    @Test
    void marketInsertCreatesBandLeafAndBothCounters() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            insertMarket(st, 3);
            assertEquals("FOR VALUES FROM ('3000000000000') TO ('4000000000000')",
                    partitionBound(conn, "command_journal_b3"));
            assertEquals("2", queryString(conn,
                    "SELECT count(*) FROM market_journal_counters WHERE market_id = 3 AND name IN ('event_seq', 'command_seq')"));
        }
    }

    @Test
    void marketIdOutsideCommandBandIsRejected() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            SQLException over = assertThrows(SQLException.class, () -> insertMarket(st, 64));
            assertTrue(over.getMessage().contains("명령 대역 밖 마켓 id"), over.getMessage());
            SQLException zero = assertThrows(SQLException.class, () -> insertMarket(st, 0));
            assertTrue(zero.getMessage().contains("명령 대역 밖 마켓 id"), zero.getMessage());
            assertEquals("0", queryString(conn, "SELECT count(*) FROM markets WHERE id IN (0, 64)"));
        }
    }

    @Test
    void commandSeqIsDerivedFromBandAndRoutesToItsLeaf() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            insertMarket(st, 5);
            assertEquals(String.valueOf(5 * COMMAND_SPAN + 1), queryString(conn, "SELECT next_market_command_seq(5::smallint)"));
            assertEquals(String.valueOf(5 * COMMAND_SPAN + 2), queryString(conn, "SELECT next_market_command_seq(5::smallint)"));

            conn.setAutoCommit(false);
            try {
                assertEquals("command_journal_b5", insertCommandAt(conn, 5 * COMMAND_SPAN + 3, 5));
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    @Test
    void commandSeqForMarketWithoutLeafIsRejected() throws Exception {
        try (Connection conn = open()) {
            SQLException e = assertThrows(SQLException.class,
                    () -> queryString(conn, "SELECT next_market_command_seq(9::smallint)"));
            assertTrue(e.getMessage().contains("명령 대역 잎 없음"), e.getMessage());
        }
    }

    @Test
    void counterWithoutBandSpecIsRejected() throws Exception {
        try (Connection conn = open()) {
            SQLException e = assertThrows(SQLException.class,
                    () -> queryString(conn, "SELECT next_market_journal_value(1::smallint, 'no_such_counter', 1)"));
            assertTrue(e.getMessage().contains("대역 명세 없는 카운터"), e.getMessage());
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    /** 이름으로 데이터베이스를 만들고 V39 까지 올린 뒤 그 JDBC URL 을 돌려준다. */
    private static String createDatabaseAtV39(String name) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
        var result = flyway(url).target("39").load().migrate();
        assertTrue(result.success, "V39 까지의 Flyway migration failed");
        return url;
    }

    private static void insertLegacyCommands(Statement st) throws SQLException {
        st.execute("""
                INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                             assigned_order_id, engine_ts, tie_break, payload,
                                             fencing_token, checksum)
                VALUES (1, 1, 'NEW', 1, 'legacy-1', 1, 0, 0, '{}', 1, 0),
                       (2, 1, 'NEW', 1, 'legacy-2', 2, 0, 1, '{}', 1, 0)
                """);
    }

    /** V36 이전 모양의 이벤트. market_id·market_seq 가 비어 있다. */
    private static void insertLegacyEvent(Statement st, long eventSeq, long sourceCmdSeq) throws SQLException {
        st.execute("""
                INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
                                           payload_schema_version, fencing_epoch, created_at)
                VALUES (%d, %d, 0, 'ACCEPTED', '{}', 1, 1, TIMESTAMPTZ '2026-07-01 00:00:00+00')
                """.formatted(eventSeq, sourceCmdSeq));
    }

    private static void insertMarket(Statement st, int id) throws SQLException {
        st.execute("""
                INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                     tick_size, lot_size, min_notional, max_notional, max_qty)
                VALUES (%d, 'BAND-%d', 2, 1, 1000, 0.0001, 5000, 100000000000, 10000)
                ON CONFLICT (id) DO NOTHING
                """.formatted(id, id));
    }

    /** 명령 행을 넣고 실제로 담긴 잎 이름을 돌려준다. 호출자가 트랜잭션을 롤백한다. */
    private static String insertCommandAt(Connection conn, long seq, int marketId) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
                INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                             assigned_order_id, engine_ts, tie_break, payload,
                                             fencing_token, checksum)
                VALUES (%d, %d, 'NEW', 1, 'band-probe', 1, 0, 0, '{}', 1, 0)
                RETURNING tableoid::regclass::text
                """.formatted(seq, marketId))) {
            assertTrue(rs.next(), "command_journal 삽입이 행을 반환하지 않음");
            String leaf = rs.getString(1);
            assertFalse("command_journal_default".equals(leaf), "대역 행이 DEFAULT 로 떨어짐");
            return leaf;
        }
    }

    private static String partitionBound(Connection conn, String relname) throws SQLException {
        return queryString(conn, """
                SELECT pg_get_expr(c.relpartbound, c.oid)
                FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'command_journal'::regclass AND c.relname = '%s'
                """.formatted(relname));
    }

    private static Connection open() throws SQLException {
        return open(POSTGRES.getJdbcUrl());
    }

    private static Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String queryString(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getString(1);
        }
    }
}

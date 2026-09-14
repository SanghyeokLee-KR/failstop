package kr.exchange;

import kr.exchange.api.transfer.TransferService;
import kr.exchange.harness.InvariantCheck;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V46 이벤트 대역 확대(ADR 0022 9단계). 시험마다 같은 컨테이너에 데이터베이스를 따로 만들어 V45 까지 올리고, V45 채번 함수로
 * 10^7 대역 이벤트를 넣은 뒤 V46 을 적용한다. event_journal 은 append-only 라 커밋한 행을 지울 수 없어 데이터베이스를 나눈다.
 */
@Testcontainers
class EventSeqBandWidenMigrationIntegrationTest {

    private static final long LEGACY_SPAN = 10_000_000L;
    private static final long SPAN = 1_000_000_000_000L;
    private static final List<Integer> MARKETS = List.of(1, 2, 3);
    private static final int LEGACY_EVENTS = 5;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    /** 기존 행은 그대로 유일하고, 새 행은 10^12 파생값으로 마켓 안 번호를 잇는다. */
    @Test
    void widensLegacyBandInPlaceAndContinuesEachMarket() throws Exception {
        String url = legacyDatabase("widen_ok");
        List<String> before;
        try (Connection conn = open(url); Statement st = conn.createStatement()) {
            // 마켓 3 은 지역 번호 5 까지 썼다. 그 이하의 옛 해제 id 는 앞으로 배정될 대역 값과 겹치지 않아 확인 3 을 통과한다.
            st.execute("INSERT INTO ledger_transactions (business_event_type, business_event_id) VALUES ('RELEASE', "
                    + (3 * SPAN + LEGACY_EVENTS) + ")");
            before = eventRows(conn);
        }
        assertTrue(flyway(url).load().migrate().success, "V46 적용 실패");

        try (Connection conn = open(url)) {
            assertEquals(SPAN + "|63", queryString(conn,
                    "SELECT band_span || '|' || max_band_index FROM journal_band_spec WHERE name = 'event_seq'"));
            assertEquals("FOR VALUES FROM ('200000000') TO ('1000000000000')",
                    partitionBound(conn, "event_journal_p200000000"));
            for (int m : MARKETS) {
                assertEquals("FOR VALUES FROM ('%d') TO ('%d')".formatted(m * SPAN, (m + 1) * SPAN),
                        partitionBound(conn, "event_journal_b" + m));
            }
            assertEquals(before, eventRows(conn), "V46 이 기존 이벤트 행을 바꿨다");

            for (int m : MARKETS) {
                long[] start = allocate(conn, m, 2);
                assertArrayEquals(new long[] {LEGACY_EVENTS + 1, m * SPAN + LEGACY_EVENTS + 1}, start,
                        "마켓 " + m + " 새 번호가 지역 번호를 잇는 10^12 파생값이 아니다");
                for (int i = 0; i < 2; i++) {
                    assertEquals("event_journal_b" + m, insertEvent(conn, m, start[0] + i, start[1] + i));
                }
            }

            long rows = queryLong(conn, "SELECT count(*) FROM event_journal");
            assertEquals(MARKETS.size() * (LEGACY_EVENTS + 2), rows);
            assertEquals(rows, queryLong(conn, "SELECT count(DISTINCT event_seq) FROM event_journal"));
            assertEquals(MARKETS.size() * LEGACY_EVENTS,
                    queryLong(conn, "SELECT count(*) FROM event_journal WHERE event_seq < " + SPAN));
            assertEquals(0, queryLong(conn, """
                    SELECT count(*) FROM (
                        SELECT event_seq, lag(event_seq) OVER (PARTITION BY market_id ORDER BY market_seq) AS prev
                        FROM event_journal
                    ) t
                    WHERE t.prev >= t.event_seq
                    """), "마켓 안 event_seq 가 경계에서 줄었다");
            for (InvariantCheck check : List.of(InvariantCheck.C06_EVT_GAPLESS,
                    InvariantCheck.C21_EVENT_EMISSION_REGISTRY, InvariantCheck.C23_COUNTER_SEQ_CONSISTENCY,
                    InvariantCheck.C15_DEFAULT_PARTITION)) {
                assertEquals(0, queryLong(conn, check.sql()), check.name());
            }
            assertEquals(SPAN - (LEGACY_EVENTS + 3), queryLong(conn,
                    "SELECT remaining FROM v_journal_band_headroom WHERE name = 'event_seq' AND market_id = 1"),
                    "V45 뷰의 이벤트 축 여유가 확대 폭을 읽지 않는다");
            assertTrue(TransferService.TRANSFER_UNLOCK_BASE >= queryLong(conn,
                            "SELECT (max_band_index + 1) * band_span FROM journal_band_spec WHERE name = 'event_seq'"),
                    "출금 거절 해제 예약이 event_seq 값 공간 안에 있다");
        }
    }

    /** 앱 파생 판의 채번 호출을 거부하고 확대 명세의 상한과 마켓 id 범위를 지킨다. 명령 채번과 마켓 생성 트리거는 그대로 돈다. */
    @Test
    void widenedAllocationRejectsLegacyCallAndGuardsLimitAndMarketRange() throws Exception {
        String url = legacyDatabase("widen_guard");
        assertTrue(flyway(url).load().migrate().success, "V46 적용 실패");
        try (Connection conn = open(url); Statement st = conn.createStatement()) {
            SQLException legacyCall = assertThrows(SQLException.class,
                    () -> queryString(conn, "SELECT next_market_journal_value(1::smallint, 'event_seq', 1)"));
            assertEquals("55000", legacyCall.getSQLState());
            assertTrue(legacyCall.getMessage().contains("next_market_event_seq"), legacyCall.getMessage());
            assertEquals(String.valueOf(SPAN + 1), queryString(conn, "SELECT next_market_command_seq(1::smallint)"));

            insertMarket(st, 4);
            assertEquals("FOR VALUES FROM ('4000000000000') TO ('5000000000000')",
                    partitionBound(conn, "event_journal_b4"));
            st.execute("UPDATE market_journal_counters SET next_value = " + (SPAN - 1)
                    + " WHERE market_id = 4 AND name = 'event_seq'");
            assertArrayEquals(new long[] {SPAN - 1, 5 * SPAN - 1}, allocate(conn, 4, 1));
            SQLException over = assertThrows(SQLException.class, () -> allocate(conn, 4, 1));
            assertTrue(over.getMessage().contains("마켓 순서 대역 초과"), over.getMessage());
            SQLException outside = assertThrows(SQLException.class, () -> allocate(conn, 64, 1));
            assertTrue(outside.getMessage().contains("이벤트 대역 밖 마켓 id"), outside.getMessage());
        }
    }

    /** 잎 없는 마켓의 채번은 거부하지 않는다. 그 행은 DEFAULT 로 가고 C15 가 센다(ADR 0022 7절 9단계). */
    @Test
    void allocationForMarketWithoutLeafRoutesToDefaultPartition() throws Exception {
        String url = legacyDatabase("widen_leafless");
        assertTrue(flyway(url).load().migrate().success, "V46 적용 실패");
        try (Connection conn = open(url)) {
            conn.setAutoCommit(false);
            try {
                long[] start = allocate(conn, 9, 1);
                assertArrayEquals(new long[] {1, 9 * SPAN + 1}, start);
                assertEquals("event_journal_default", insertEvent(conn, 9, start[0], start[1]));
                assertEquals(1, queryLong(conn, InvariantCheck.C15_DEFAULT_PARTITION.sql()));
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void unexpectedEventSpecStopsWiden() throws Exception {
        String url = legacyDatabase("widen_spec");
        try (Connection conn = open(url); Statement st = conn.createStatement()) {
            st.execute("UPDATE journal_band_spec SET band_span = " + SPAN + ", max_band_index = 63 WHERE name = 'event_seq'");
        }
        assertStopsBeforeWiden(url, "적용 전 확인 1");
    }

    @Test
    void defaultPartitionRowStopsWiden() throws Exception {
        String url = legacyDatabase("widen_default");
        try (Connection conn = open(url)) {
            // 10^7 대역에서 마켓 id 20 의 값은 p0 경계 2x10^8 밖이라 DEFAULT 로 간다.
            assertEquals("event_journal_default", insertEvent(conn, 20, 1, 20 * LEGACY_SPAN + 1));
        }
        assertStopsBeforeWiden(url, "적용 전 확인 2");
    }

    @Test
    void releaseIdInFutureEventBandStopsWiden() throws Exception {
        String url = legacyDatabase("widen_release");
        try (Connection conn = open(url); Statement st = conn.createStatement()) {
            // 마켓 3 이 다음에 배정할 지역 번호가 6 이라 3x10^12 + 6 은 V46 뒤 그 마켓 정산 RELEASE 와 겹친다.
            st.execute("INSERT INTO ledger_transactions (business_event_type, business_event_id) VALUES ('RELEASE', "
                    + (3 * SPAN + LEGACY_EVENTS + 1) + ")");
        }
        assertStopsBeforeWiden(url, "적용 전 확인 3");
    }

    /** V46 이 통째로 롤백돼 V45 에 남는다. */
    private static void assertStopsBeforeWiden(String url, String reason) throws SQLException {
        FlywayException e = assertThrows(FlywayException.class, () -> flyway(url).load().migrate());
        assertTrue(e.getMessage().contains(reason), e.getMessage());
        try (Connection conn = open(url)) {
            assertEquals("45", queryString(conn, "SELECT max(version::int) FROM flyway_schema_history WHERE success"));
            assertEquals("true|true", queryString(conn, """
                    SELECT (to_regclass('public.event_journal_p200000000') IS NULL) || '|'
                        || (to_regprocedure('next_market_event_seq(smallint,bigint)') IS NULL)
                    """));
        }
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    /** V45 데이터베이스에 자산과 마켓을 두고 마켓마다 V45 채번 함수로 10^7 대역 이벤트를 넣는다. */
    private static String legacyDatabase(String name) throws SQLException {
        try (Connection conn = open(POSTGRES.getJdbcUrl()); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
        assertTrue(flyway(url).target("45").load().migrate().success, "V45 까지의 Flyway migration failed");
        try (Connection conn = open(url); Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                        (1, 'KRW', 'Korean Won', 'FIAT', 0),
                        (2, 'BTC', 'Bitcoin', 'CRYPTO', 8)
                    """);
            for (int m : MARKETS) {
                insertMarket(st, m);
                for (int i = 0; i < LEGACY_EVENTS; i++) {
                    long local = queryLong(conn,
                            "SELECT next_market_journal_value(" + m + "::smallint, 'event_seq', 1)");
                    assertEquals("event_journal_p0", insertEvent(conn, m, local, m * LEGACY_SPAN + local));
                }
            }
        }
        return url;
    }

    private static void insertMarket(Statement st, int id) throws SQLException {
        st.execute("""
                INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                     tick_size, lot_size, min_notional, max_notional, max_qty)
                VALUES (%d, 'WIDEN-%d', 2, 1, 1000, 0.0001, 5000, 100000000000, 10000)
                """.formatted(id, id));
    }

    private static long[] allocate(Connection conn, int marketId, long count) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT out_market_seq, out_event_seq FROM next_market_event_seq(?::smallint, ?)")) {
            ps.setInt(1, marketId);
            ps.setLong(2, count);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "채번 결과 없음");
                return new long[] {rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    /** 이벤트 행을 넣고 실제로 담긴 잎 이름을 돌려준다. source_cmd_seq 를 event_seq 로 둬 방출 레지스트리 키가 겹치지 않는다. */
    private static String insertEvent(Connection conn, int marketId, long marketSeq, long eventSeq) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
                                           fencing_epoch, created_at, market_id, market_seq)
                VALUES (?, ?, 0, 'ACCEPTED', '{}', 1, now(), ?, ?)
                RETURNING tableoid::regclass::text
                """)) {
            ps.setLong(1, eventSeq);
            ps.setLong(2, eventSeq);
            ps.setInt(3, marketId);
            ps.setLong(4, marketSeq);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "event_journal 삽입이 행을 반환하지 않음");
                return rs.getString(1);
            }
        }
    }

    private static List<String> eventRows(Connection conn) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT event_seq || '|' || market_id || '|' || market_seq || '|' || tableoid::regclass
                FROM event_journal ORDER BY event_seq
                """)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        assertTrue(!rows.isEmpty(), "기존 이벤트 행이 없어 불변 판정이 공허하다");
        return rows;
    }

    private static String partitionBound(Connection conn, String relname) throws SQLException {
        return queryString(conn, """
                SELECT pg_get_expr(c.relpartbound, c.oid)
                FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'event_journal'::regclass AND c.relname = '%s'
                """.formatted(relname));
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

    private static long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getLong(1);
        }
    }
}

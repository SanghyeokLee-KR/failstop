package kr.exchange.journal;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V43 멱등 조회 정렬(ADR 0022 4-3). 같은 키의 NEW 행이 둘이면 먼저 접수된(created_at 이 이른) 행을 고른다.
 * 그 상태는 어드바이저리 락이 막으므로 append-only 저널에 INSERT 만으로 직접 만든다.
 */
@Testcontainers
class AppendPrologueCreatedAtOrderIntegrationTest {

    private static final long USER = 9_001L;
    private static final String CLIENT_ORDER_ID = "dup-new-1";
    // seq 가 작은 행을 늦게 접수된 것으로 둔다. 두 정렬이 서로 다른 행을 고르게 하려는 배치다.
    private static final long SMALL_SEQ_LATE = 100L;
    private static final long LARGE_SEQ_EARLY = 200L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    @BeforeAll
    static void migrate() {
        assertTrue(flyway(POSTGRES.getJdbcUrl()).load().migrate().success, "Flyway migration failed");
    }

    @Test
    void replayPicksEarliestCreatedAtAmongDuplicateNewRows() throws Exception {
        try (Connection c = open(POSTGRES.getJdbcUrl())) {
            assertEquals(LARGE_SEQ_EARLY, replayedSeq(c));
        }
    }

    /** 대조. V43 이전 정렬은 같은 상태에서 seq 가 작은 행을 고른다. 위 시험이 정렬 변경을 실제로 가른다는 증거다. */
    @Test
    void seqOrderedPrologueBeforeV43PicksSmallestSeq() throws Exception {
        String url = createDatabase("prologue_before_v43");
        flyway(url).target("42").load().migrate();
        try (Connection c = open(url)) {
            assertEquals(SMALL_SEQ_LATE, replayedSeq(c));
        }
    }

    /** 5인자 판(V43)과 6인자 판(V44) 모두 VOLATILE 이어야 어드바이저리 락을 기다린 멱등 조회가 앞선 커밋을 본다. */
    @Test
    void prologueIsDeclaredVolatile() throws Exception {
        try (Connection c = open(POSTGRES.getJdbcUrl()); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT pg_get_function_identity_arguments(oid), provolatile FROM pg_proc
                     WHERE proname = 'append_command_prologue' ORDER BY pronargs
                     """)) {
            List<String> overloads = new ArrayList<>();
            while (rs.next()) {
                assertEquals("v", rs.getString(2), rs.getString(1));
                overloads.add(rs.getString(1));
            }
            // 5인자(V39·V43·V44), 6인자(V44·V48), 10인자(V50 접수 예약) 세 판이다(ADR 0034 S3).
            assertEquals(3, overloads.size(), "append_command_prologue 가 5·6·10인자 세 판이 아니다: " + overloads);
            assertTrue(overloads.get(2).endsWith("p_check_asset smallint, p_check_amount numeric"),
                    "가장 긴 판이 V50 10인자 머리말이 아니다: " + overloads.get(2));
        }
    }

    private static long replayedSeq(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE leader_lease SET holder = 'node-a', epoch = 5, "
                    + "lease_until = clock_timestamp() + interval '60 seconds' WHERE resource = 'matching-engine'");
        }
        insertNew(c, SMALL_SEQ_LATE, 1, "2026-09-14 00:00:02+00");
        insertNew(c, LARGE_SEQ_EARLY, 2, "2026-09-14 00:00:01+00");
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT out_seq, out_replayed FROM append_command_prologue('node-a', 5, TRUE, ?, ?)")) {
            ps.setLong(1, USER);
            ps.setString(2, CLIENT_ORDER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "머리말이 행을 돌려주지 않았다");
                assertTrue(rs.getBoolean("out_replayed"), "같은 키 NEW 행이 있는데 재전송으로 판정하지 않았다");
                return rs.getLong("out_seq");
            }
        }
    }

    private static void insertNew(Connection c, long seq, int marketId, String createdAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO command_journal
                  (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                   engine_ts, tie_break, payload, fencing_token, checksum, created_at)
                VALUES (?, ?, 'NEW', ?, ?, ?, 0, 0, '{}'::jsonb, 5, 0, ?::timestamptz)
                """)) {
            ps.setLong(1, seq);
            ps.setInt(2, marketId);
            ps.setLong(3, USER);
            ps.setString(4, CLIENT_ORDER_ID);
            ps.setLong(5, seq);
            ps.setString(6, createdAt);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    private static String createDatabase(String name) throws SQLException {
        try (Connection c = open(POSTGRES.getJdbcUrl()); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
    }

    private static Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

package kr.exchange.admin.msp;

import org.flywaydb.core.Flyway;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V73 통지 보관함과 단일 실행 임대의 권한과 제약을 실제로 어겨 본다(DB 설계 §10.5·§10.6). 앱 계정은 app_role 을
 * 받은 로그인으로 시도한다.
 */
@Testcontainers
class NotificationSchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection owner = owner(); Statement st = owner.createStatement()) {
            st.execute("CREATE ROLE note_app LOGIN PASSWORD 'app' IN ROLE app_role");
            st.execute("CREATE ROLE note_harness LOGIN PASSWORD 'harness' IN ROLE harness_role");
        }
    }

    @Test
    void 앱_계정은_보관함에_쓰고_상태를_바꾸지만_지울_수_없다() throws SQLException {
        try (Connection app = app(); Statement st = app.createStatement()) {
            String event = UUID.randomUUID().toString();
            assertEquals(1, st.executeUpdate(insert(event, "AUTO_EXECUTED", "'PENDING'")),
                    "IDENTITY 열이 있어도 시퀀스 권한 없이 넣을 수 있다");
            assertEquals(1, st.executeUpdate("UPDATE ops_notifications SET state = 'ACCEPTED', accepted_at = now(), "
                    + "report_id = 'msp:" + event + "' WHERE event_id = '" + event + "'"));
            assertSqlState("42501", app, "DELETE FROM ops_notifications");
            assertSqlState("23505", app, insert(event, "AUTO_EXECUTED", "'PENDING'"));
        }
    }

    @Test
    void 종류_상태_본문_크기_접수_시각을_제약이_막는다() throws SQLException {
        try (Connection app = app()) {
            assertSqlState("23514", app, insert(UUID.randomUUID().toString(), "DAILY_CAP", "'PENDING'"));
            assertSqlState("23514", app, insert(UUID.randomUUID().toString(), "STOPPED", "'UNKNOWN'"));
            assertSqlState("23514", app, insert(UUID.randomUUID().toString(), "STOPPED", "'SENT'"));
            assertSqlState("23514", app, "INSERT INTO ops_notifications (event_id, kind, payload) VALUES ('"
                    + UUID.randomUUID() + "', 'STOPPED', jsonb_build_object('pad', repeat('x', 17000)))");
            assertSqlState("23514", app, "INSERT INTO ops_notifications (event_id, kind, payload, state) VALUES ('"
                    + UUID.randomUUID() + "', 'STOPPED', '{}', 'ACCEPTED')");
            assertSqlState("23514", app, "INSERT INTO ops_notifications (event_id, kind, payload, accepted_at) VALUES ('"
                    + UUID.randomUUID() + "', 'STOPPED', '{}', now())");
        }
    }

    @Test
    void 임대는_행_하나이고_앱은_임대_열만_바꾼다() throws SQLException {
        try (Connection app = app(); Statement st = app.createStatement()) {
            assertEquals(1, count(app, "SELECT count(*) FROM ops_runner_lease WHERE name = 'ai_loop' AND holder IS NULL"));
            assertEquals(1, st.executeUpdate("UPDATE ops_runner_lease SET holder = 'jvm-a', "
                    + "expires_at = now() + interval '90 seconds', renewed_at = now() WHERE name = 'ai_loop'"));
            assertSqlState("42501", app, "INSERT INTO ops_runner_lease (name) VALUES ('ai_loop')");
            assertSqlState("42501", app, "DELETE FROM ops_runner_lease");
            assertSqlState("42501", app, "UPDATE ops_runner_lease SET name = 'ai_loop'");
            assertSqlState("23514", app, "UPDATE ops_runner_lease SET holder = NULL WHERE name = 'ai_loop'");
            st.executeUpdate("UPDATE ops_runner_lease SET holder = NULL, expires_at = NULL WHERE name = 'ai_loop'");
        }
    }

    @Test
    void 하네스_계정은_읽기만_한다() throws SQLException {
        try (Connection harness = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "note_harness", "harness")) {
            count(harness, "SELECT count(*) FROM ops_notifications");
            count(harness, "SELECT count(*) FROM ops_runner_lease");
            assertSqlState("42501", harness, insert(UUID.randomUUID().toString(), "STOPPED", "'PENDING'"));
            assertSqlState("42501", harness, "UPDATE ops_runner_lease SET holder = NULL, expires_at = NULL");
        }
    }

    private static String insert(String eventId, String kind, String state) {
        return "INSERT INTO ops_notifications (event_id, kind, payload, state) VALUES ('" + eventId + "', '" + kind
                + "', '{\"schema_version\":1}', " + state + ")";
    }

    private static void assertSqlState(String expected, Connection conn, String sql) {
        SQLException e = assertThrows(SQLException.class, () -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
        }, sql);
        assertEquals(expected, e.getSQLState(), sql + " -> " + e.getMessage());
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection app() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "note_app", "app");
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

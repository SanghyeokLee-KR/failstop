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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V70·V71 관제센터 표의 권한과 제약을 실제로 어겨 본다(DB 설계 §10.6).
 * 앱 계정은 app_role 을 받은 로그인으로, 기록 표의 append-only 는 소유자 계정으로 시도한다.
 */
@Testcontainers
class OpsSchemaIntegrationTest {

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
            st.execute("CREATE ROLE msp_app LOGIN PASSWORD 'app' IN ROLE app_role");
            st.execute("CREATE ROLE msp_harness LOGIN PASSWORD 'harness' IN ROLE harness_role");
        }
    }

    @Test
    void 항목_아홉_개와_초기_이력이_있고_리터럴이_없는_항목은_미설정이다() throws SQLException {
        try (Connection owner = owner()) {
            assertEquals(9, count(owner, "SELECT count(*) FROM ops_thresholds"));
            assertEquals(9, count(owner, "SELECT count(*) FROM ops_threshold_changes WHERE via = 'SEED' AND version = 1"));
            assertEquals(5, count(owner, "SELECT count(*) FROM ops_thresholds WHERE value IS NULL"));
            assertEquals("0.1500", text(owner, "SELECT value::text FROM ops_thresholds WHERE key = 'host.disk_free_alert'"));
            assertEquals("93600.0000", text(owner, "SELECT value::text FROM ops_thresholds WHERE key = 'backup.delay_alert'"));
        }
    }

    @Test
    void 앱_계정은_범위_열을_바꾸거나_항목을_늘리거나_이력을_지울_수_없다() throws SQLException {
        try (Connection app = app()) {
            assertSqlState("42501", app, "UPDATE ops_thresholds SET min_value = 0.01 WHERE key = 'host.disk_free_alert'");
            assertSqlState("42501", app, "UPDATE ops_thresholds SET ai_max_step = 1 WHERE key = 'read.scale_out_cpu'");
            assertSqlState("42501", app, """
                    INSERT INTO ops_thresholds (key, unit, min_value, max_value, ai_max_step, updated_by)
                    VALUES ('host.new_alert', 'ratio', 0, 1, 1, 'app')
                    """);
            assertSqlState("42501", app, "DELETE FROM ops_threshold_changes WHERE key = 'host.cpu_alert'");
            assertSqlState("42501", app, "DELETE FROM ops_incident_events");
        }
    }

    @Test
    void 범위_밖_값과_초기값_항목의_미설정은_23514다() throws SQLException {
        try (Connection app = app()) {
            assertSqlState("23514", app, "UPDATE ops_thresholds SET value = 0.05 WHERE key = 'host.disk_free_alert'");
            assertSqlState("23514", app, "UPDATE ops_thresholds SET value = NULL WHERE key = 'backup.delay_alert'");
            assertSqlState("23514", app, "UPDATE ops_thresholds SET value = NULL WHERE key = 'read.scale_out_cpu'");
        }
    }

    @Test
    void 조회_임계_간격은_문장이_아니라_커밋에서_거부된다() throws SQLException {
        try (Connection app = app()) {
            app.setAutoCommit(false);
            try (Statement st = app.createStatement()) {
                // 증설 0.60 에 축소 0.50 이면 간격 0.10 이다. 지연 트리거라 문장은 통과한다.
                assertEquals(1, st.executeUpdate("UPDATE ops_thresholds SET value = 0.50 WHERE key = 'read.scale_in_cpu'"));
            }
            SQLException atCommit = assertThrows(SQLException.class, app::commit);
            assertEquals("23514", atCommit.getSQLState(), atCommit.getMessage());
            app.rollback();
        }
        try (Connection owner = owner()) {
            assertEquals("0.3000", text(owner, "SELECT value::text FROM ops_thresholds WHERE key = 'read.scale_in_cpu'"));
        }
    }

    @Test
    void 두_조회_임계를_한_트랜잭션에서_바꾸면_중간_상태는_거부되지_않는다() throws SQLException {
        try (Connection app = app()) {
            app.setAutoCommit(false);
            try (Statement st = app.createStatement()) {
                // 0.60·0.30 에서 0.25·0.10 으로. 첫 문장 뒤의 0.25·0.30 은 간격이 음수지만 커밋 때는 0.15 다.
                st.executeUpdate("UPDATE ops_thresholds SET value = 0.25 WHERE key = 'read.scale_out_cpu'");
                st.executeUpdate("UPDATE ops_thresholds SET value = 0.10 WHERE key = 'read.scale_in_cpu'");
            }
            app.commit();
            try (Statement st = app.createStatement()) {
                st.executeUpdate("UPDATE ops_thresholds SET value = 0.60 WHERE key = 'read.scale_out_cpu'");
                st.executeUpdate("UPDATE ops_thresholds SET value = 0.30 WHERE key = 'read.scale_in_cpu'");
            }
            app.commit();
        }
    }

    @Test
    void 기록_표는_소유자도_고치거나_지우거나_비울_수_없다() throws SQLException {
        try (Connection owner = owner()) {
            assertSqlState("P0001", owner, "UPDATE ops_threshold_changes SET reason = 'x' WHERE via = 'SEED'");
            assertSqlState("P0001", owner, "DELETE FROM ops_threshold_changes WHERE via = 'SEED'");
            assertSqlState("P0001", owner, "TRUNCATE ops_threshold_changes");
            try (Statement st = owner.createStatement()) {
                st.execute("""
                        INSERT INTO ops_incident_events (kind, actor_type, actor_id, text)
                        VALUES ('MODE', 'SYSTEM', 'test', '기록')
                        """);
            }
            assertSqlState("P0001", owner, "UPDATE ops_incident_events SET text = 'x'");
            assertSqlState("P0001", owner, "DELETE FROM ops_incident_events");
            assertSqlState("P0001", owner, "TRUNCATE ops_incident_events");
        }
    }

    @Test
    void 앱_계정은_시퀀스_권한_없이_식별자_열이_있는_표에_행을_넣는다() throws SQLException {
        try (Connection app = app(); Statement st = app.createStatement()) {
            st.execute("""
                    INSERT INTO ops_threshold_changes (key, from_value, to_value, version, via, changed_by)
                    VALUES ('host.cpu_credit_alert', NULL, NULL, 900, 'MANUAL', 'schema-test')
                    """);
            long incident = insertIncident(st, "aaaaaaaaaaaaaaa1", "warning", "2026-09-25T00:00:00Z");
            st.execute("INSERT INTO ops_incident_events (incident_id, kind, actor_type, actor_id, text) "
                    + "VALUES (" + incident + ", 'OPENED', 'SYSTEM', 'poller', '발생')");
            assertEquals(1, st.executeUpdate("UPDATE ops_incidents SET last_seen_at = now() WHERE id = " + incident));
        }
    }

    @Test
    void 사건은_정규화한_등급과_지문당_열린_사건_하나와_상태별_필수_열을_강제한다() throws SQLException {
        try (Connection app = app(); Statement st = app.createStatement()) {
            insertIncident(st, "bbbbbbbbbbbbbbb1", "critical", "2026-09-25T01:00:00Z");
            assertSqlState("23514", app, incidentSql("bbbbbbbbbbbbbbb2", "none", "2026-09-25T01:00:00Z"));
            assertSqlState("23514", app, incidentSql("NOT-A-FINGERPRT", "warning", "2026-09-25T01:00:00Z"));
            // 같은 지문의 열린 사건은 하나다.
            assertSqlState("23505", app, incidentSql("bbbbbbbbbbbbbbb1", "critical", "2026-09-25T02:00:00Z"));
            assertSqlState("23514", app,
                    "UPDATE ops_incidents SET status = 'RESOLVED' WHERE fingerprint = 'bbbbbbbbbbbbbbb1'");
            assertSqlState("23514", app,
                    "UPDATE ops_incidents SET silence_id = 's-1' WHERE fingerprint = 'bbbbbbbbbbbbbbb1'");
            st.executeUpdate("UPDATE ops_incidents SET status = 'RESOLVED', resolved_at = now() "
                    + "WHERE fingerprint = 'bbbbbbbbbbbbbbb1'");
            // 닫힌 사건과 같은 (지문, 시작 시각)은 새 행이 아니라 다시 열어야 한다.
            assertSqlState("23505", app, incidentSql("bbbbbbbbbbbbbbb1", "critical", "2026-09-25T01:00:00Z"));
            insertIncident(st, "bbbbbbbbbbbbbbb1", "critical", "2026-09-25T02:00:00Z");
        }
    }

    @Test
    void 하네스_계정은_네_표를_읽기만_한다() throws SQLException {
        try (Connection harness = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "msp_harness", "harness")) {
            for (String table : new String[]{"ops_thresholds", "ops_threshold_changes", "ops_incidents",
                    "ops_incident_events"}) {
                count(harness, "SELECT count(*) FROM " + table);
            }
            assertSqlState("42501", harness, "UPDATE ops_thresholds SET value = 0.2 WHERE key = 'host.disk_free_alert'");
            assertSqlState("42501", harness,
                    "INSERT INTO ops_incident_events (kind, actor_type, actor_id, text) VALUES ('MODE','SYSTEM','h','x')");
        }
        try (Connection owner = owner()) {
            assertFalse(bool(owner, "SELECT has_table_privilege('app_role', 'ops_thresholds', 'INSERT')"));
            assertFalse(bool(owner, "SELECT has_table_privilege('app_role', 'ops_incidents', 'DELETE')"));
            assertTrue(bool(owner, "SELECT has_column_privilege('app_role', 'ops_thresholds', 'value', 'UPDATE')"));
            assertFalse(bool(owner, "SELECT has_column_privilege('app_role', 'ops_thresholds', 'max_value', 'UPDATE')"));
        }
    }

    private static long insertIncident(Statement st, String fingerprint, String severity, String startedAt)
            throws SQLException {
        try (ResultSet rs = st.executeQuery(incidentSql(fingerprint, severity, startedAt) + " RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String incidentSql(String fingerprint, String severity, String startedAt) {
        return "INSERT INTO ops_incidents (fingerprint, alertname, severity, labels, started_at) VALUES ('"
                + fingerprint + "', 'SchemaProbe', '" + severity + "', '{\"alertname\":\"SchemaProbe\"}', '"
                + startedAt + "')";
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
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "msp_app", "app");
    }

    private static int count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String text(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static boolean bool(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}

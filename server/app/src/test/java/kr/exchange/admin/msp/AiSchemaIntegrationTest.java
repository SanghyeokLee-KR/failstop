package kr.exchange.admin.msp;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * V72 관제센터 AI 표의 권한과 제약을 실제로 어겨 본다(DB 설계 §10.4·§10.6). 앱 계정은 app_role 을 받은 로그인으로,
 * append-only 는 소유자 계정으로 시도한다.
 */
@Testcontainers
class AiSchemaIntegrationTest {

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
            st.execute("CREATE ROLE ai_app LOGIN PASSWORD 'app' IN ROLE app_role");
            st.execute("CREATE ROLE ai_harness LOGIN PASSWORD 'harness' IN ROLE harness_role");
            st.execute("INSERT INTO admin_operators (operator_id, display_name) VALUES ('op-01','운영자1'),('op-02','운영자2')");
        }
    }

    @BeforeEach
    void manual() throws SQLException {
        try (Connection owner = owner(); Statement st = owner.createStatement()) {
            st.execute("UPDATE ops_ai_mode SET mode = 'MANUAL', enabled_by = NULL, reason = NULL, approval_id = NULL, "
                    + "since = NULL, valid_until = NULL, activation_at = NULL, activation_limits = NULL, "
                    + "stopped = false, stopped_by = NULL, stopped_at = NULL, stop_reason = NULL, changed_by = 'test'");
        }
    }

    @Test
    void AI_신원과_한도_확정값과_수동_모드_행이_있다() throws SQLException {
        try (Connection owner = owner()) {
            assertEquals("AI", text(owner, "SELECT kind FROM admin_operators WHERE operator_id = 'ai-ops'"));
            assertEquals("HUMAN", text(owner, "SELECT kind FROM admin_operators WHERE operator_id = 'op-01'"));
            assertEquals(0, count(owner, "SELECT count(*) FROM operator_roles WHERE operator_id = 'ai-ops'"));
            assertEquals("80,5,2,10,30", text(owner, "SELECT string_agg(value::text, ',' ORDER BY CASE key "
                    + "WHEN 'confidence_pct' THEN 1 WHEN 'auto_cap' THEN 2 WHEN 'threshold_per_item' THEN 3 "
                    + "WHEN 'bot_cooldown_min' THEN 4 ELSE 5 END) FROM ops_ai_limits"));
            assertEquals(5, count(owner, "SELECT count(*) FROM ops_ai_limit_changes WHERE version = 1"));
            assertEquals(1, count(owner, "SELECT count(*) FROM ops_ai_mode WHERE mode = 'MANUAL' AND NOT stopped"));
        }
    }

    @Test
    void AI_신원에는_역할을_줄_수_없고_종류를_바꿀_수_없다() throws SQLException {
        try (Connection app = app()) {
            assertSqlState("P0001", app, "INSERT INTO operator_roles (operator_id, role) VALUES ('ai-ops', 'VIEWER')");
            assertSqlState("P0001", app, "UPDATE admin_operators SET kind = 'HUMAN' WHERE operator_id = 'ai-ops'");
            assertSqlState("P0001", app, "UPDATE admin_operators SET kind = 'AI' WHERE operator_id = 'op-02'");
        }
    }

    @Test
    void 앱_계정은_한도_항목을_늘리거나_지우거나_하드_범위를_넘길_수_없다() throws SQLException {
        try (Connection app = app()) {
            assertSqlState("42501", app, "INSERT INTO ops_ai_limits (key, value, updated_by) VALUES ('auto_cap', 3, 'x')");
            assertSqlState("42501", app, "DELETE FROM ops_ai_limits WHERE key = 'auto_cap'");
            assertSqlState("42501", app, "UPDATE ops_ai_limits SET key = 'auto_cap' WHERE key = 'auto_cap'");
            assertSqlState("42501", app, "DELETE FROM ops_ai_limit_changes");
            for (String bad : new String[]{"confidence_pct = 69", "confidence_pct = 96", "auto_cap = 11",
                    "threshold_per_item = 4", "bot_cooldown_min = 4", "backup_disk_free_pct = 51"}) {
                String[] kv = bad.split(" = ");
                assertSqlState("23514", app, "UPDATE ops_ai_limits SET value = " + kv[1] + " WHERE key = '" + kv[0] + "'");
            }
            try (Statement st = app.createStatement()) {
                assertEquals(1, st.executeUpdate("UPDATE ops_ai_limits SET value = 3, version = version + 1 "
                        + "WHERE key = 'auto_cap'"));
                st.executeUpdate("UPDATE ops_ai_limits SET value = 5 WHERE key = 'auto_cap'");
            }
        }
    }

    @Test
    void 기록_표는_소유자도_고치거나_지우거나_비울_수_없다() throws SQLException {
        try (Connection owner = owner(); Statement st = owner.createStatement()) {
            st.execute("INSERT INTO ops_ai_decisions (decided_at, mode, outcome, model, prompt_version, evidence, "
                    + "evidence_sha256) VALUES (now(), 'MANUAL', 'NO_ACTION', 'm', 'p', '{}', repeat('a', 64))");
            for (String table : new String[]{"ops_ai_decisions", "ops_ai_limit_changes"}) {
                assertSqlState("P0001", owner, "UPDATE " + table + " SET changed_at = changed_at"
                        .replace("changed_at", table.equals("ops_ai_decisions") ? "decided_at" : "changed_at"));
                assertSqlState("P0001", owner, "DELETE FROM " + table);
                assertSqlState("P0001", owner, "TRUNCATE " + table + " CASCADE");
            }
        }
    }

    @Test
    void 자동_모드는_같은_트랜잭션에서_소각한_ops_ai_auto_티켓이어야_한다() throws SQLException {
        // 사례마다 티켓 소각과 모드 갱신을 한 트랜잭션에서 하고 되돌린다. 그래야 거부가 소각 시점 조건 하나가 아니라
        // 사례가 어긴 조건 때문임을 가를 수 있다.
        try (Connection app = app()) {
            app.setAutoCommit(false);
            long ok = ticket(app, "ops.ai.auto", "AUTO:2", "op-01", true);
            try (Statement st = app.createStatement()) {
                assertEquals(1, st.executeUpdate(autoSql(ok, "op-01")), "대조: 올바른 티켓은 받는다");
            }
            app.rollback();

            rejectedInOneTransaction(app, "ops.threshold.set", "AUTO:2", "op-01", true, "op-01", "다른 액션");
            rejectedInOneTransaction(app, "ops.ai.auto", "AUTO:2", "op-01", false, "op-01", "소각하지 않은 티켓");
            rejectedInOneTransaction(app, "ops.ai.auto", "AUTO:3", "op-01", true, "op-01", "1·2·4·8시간 밖");
            rejectedInOneTransaction(app, "ops.ai.auto", "AUTO:2", "op-02", true, "op-01", "다른 사용자의 티켓");

            long earlier = ticket(app, "ops.ai.auto", "AUTO:2", "op-01", true);
            app.commit();
            assertSqlState("P0001", app, autoSql(earlier, "op-01"));
            app.rollback();
        }
    }

    /** 티켓 하나를 만들고 같은 트랜잭션에서 자동으로 바꾸려 하면 트리거가 거부하는지 본다. 끝나면 되돌린다. */
    private static void rejectedInOneTransaction(Connection app, String action, String target, String requester,
                                                 boolean consume, String enabledBy, String why) throws SQLException {
        long id = ticket(app, action, target, requester, consume);
        SQLException e = assertThrows(SQLException.class, () -> {
            try (Statement st = app.createStatement()) {
                st.executeUpdate(autoSql(id, enabledBy));
            }
        }, why);
        assertEquals("P0001", e.getSQLState(), why + " -> " + e.getMessage());
        app.rollback();
    }

    @Test
    void 자동_창은_DB가_정하고_수동에서_켤_때만_활성_번호가_오른다() throws SQLException {
        long before;
        try (Connection owner = owner()) {
            before = count(owner, "SELECT activation FROM ops_ai_mode");
        }
        try (Connection app = app()) {
            app.setAutoCommit(false);
            long t1 = ticket(app, "ops.ai.auto", "AUTO:2", "op-01", true);
            try (Statement st = app.createStatement()) {
                // 앱이 넣은 미래의 시작과 긴 끝은 트리거가 커밋 시각과 티켓 시간으로 덮어쓴다.
                st.executeUpdate("UPDATE ops_ai_mode SET mode = 'AUTO', enabled_by = 'op-01', reason = 'r', "
                        + "approval_id = " + t1 + ", since = now() + interval '1 day', "
                        + "valid_until = now() + interval '30 hours', activation_at = now(), activation_limits = '{}'");
            }
            app.commit();
            assertTrue(bool(app, "SELECT since = (SELECT consumed_at FROM admin_approvals WHERE id = " + t1 + ") "
                    + "AND valid_until = since + interval '2 hours' FROM ops_ai_mode"));
            assertEquals(before + 1, count(app, "SELECT activation FROM ops_ai_mode"));
            assertEquals("5", text(app, "SELECT activation_limits->>'auto_cap' FROM ops_ai_mode"));

            long t2 = ticket(app, "ops.ai.auto", "AUTO:8", "op-01", true);
            try (Statement st = app.createStatement()) {
                st.executeUpdate("UPDATE ops_ai_mode SET approval_id = " + t2 + ", since = now(), valid_until = now()");
            }
            app.commit();
            assertEquals(before + 1, count(app, "SELECT activation FROM ops_ai_mode"), "자동 중 다시 켜도 활성 번호는 그대로");
            assertTrue(bool(app, "SELECT valid_until = since + interval '8 hours' FROM ops_ai_mode"));

            assertSqlState("23514", app, "UPDATE ops_ai_mode SET stopped = true, stopped_by = 'op-02', stopped_at = now()");
            app.rollback();
        }
    }

    @Test
    void 계획_작업_창은_24시간_이하이고_대상이_있어야_한다() throws SQLException {
        try (Connection app = app()) {
            assertSqlState("23514", app, windowSql("interval '25 hours'", "'BotOrderLatencyHigh'", "'{}'", "'작업'"));
            assertSqlState("23514", app, windowSql("interval '1 hour'", "NULL", "'{}'", "'작업'"));
            assertSqlState("23514", app, windowSql("interval '1 hour'", "'BotOrderLatencyHigh'", "'{}'", "''"));
            try (Statement st = app.createStatement()) {
                st.execute(windowSql("interval '1 hour'", "NULL", "'{\"component\":\"bot\"}'", "'작업'"));
            }
            assertSqlState("42501", app, "UPDATE ops_maintenance_windows SET ends_at = now() + interval '2 hours'");
            try (Statement st = app.createStatement()) {
                st.executeUpdate("UPDATE ops_maintenance_windows SET ended_by = 'op-02', ended_at = now()");
            }
        }
    }

    @Test
    void 조치_요청은_실행_하나와_열린_요청_하나와_나중에_기한을_강제한다() throws SQLException {
        try (Connection app = app(); Statement st = app.createStatement()) {
            long decision;
            try (ResultSet rs = st.executeQuery("INSERT INTO ops_ai_decisions (decided_at, mode, outcome, model, "
                    + "prompt_version, evidence, evidence_sha256) VALUES (now(), 'MANUAL', 'PROPOSED', 'm', 'p', '{}', "
                    + "repeat('b', 64)) RETURNING id")) {
                rs.next();
                decision = rs.getLong(1);
            }
            st.execute(actionSql(decision, "READ_NODES:+1", "PROPOSED"));
            assertSqlState("23505", app, actionSql(decision, "READ_NODES:+1", "DEFERRED"));
            st.execute(actionSql(decision, "READ_NODES:-1", "EXECUTING"));
            assertSqlState("23505", app, actionSql(decision, "BOT_LOAD:0.5", "EXECUTING"));
            st.execute(actionSql(decision, "READ_NODES:+1", "DENIED"));
            assertSqlState("23514", app, "UPDATE ops_ai_actions SET deferred_until = expires_at + interval '1 second' "
                    + "WHERE identity_key = 'READ_NODES:+1' AND status = 'PROPOSED'");
            assertSqlState("42501", app, "DELETE FROM ops_ai_actions");
        }
    }

    @Test
    void 사건_타임라인은_넓힌_종류만_받는다() throws SQLException {
        try (Connection app = app(); Statement st = app.createStatement()) {
            for (String kind : new String[]{"STOPPED", "RESUMED", "LIMITS", "WINDOW", "DENIED", "OBSERVED", "MODE"}) {
                st.execute("INSERT INTO ops_incident_events (kind, actor_type, actor_id, text) VALUES ('" + kind
                        + "', 'SYSTEM', 'test', '기록')");
            }
            assertSqlState("23514", app, "INSERT INTO ops_incident_events (kind, actor_type, actor_id, text) "
                    + "VALUES ('BOGUS', 'SYSTEM', 'test', '기록')");
        }
    }

    @Test
    void 하네스_계정은_AI_표를_읽기만_한다() throws SQLException {
        try (Connection harness = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "ai_harness", "harness")) {
            for (String table : new String[]{"ops_ai_limits", "ops_ai_limit_changes", "ops_ai_mode",
                    "ops_maintenance_windows", "ops_ai_decisions", "ops_ai_actions"}) {
                count(harness, "SELECT count(*) FROM " + table);
            }
            assertSqlState("42501", harness, "UPDATE ops_ai_mode SET changed_by = 'h'");
        }
        try (Connection owner = owner()) {
            assertFalse(bool(owner, "SELECT has_table_privilege('app_role', 'ops_ai_decisions', 'UPDATE')"));
            assertFalse(bool(owner, "SELECT has_column_privilege('app_role', 'ops_ai_limits', 'key', 'UPDATE')"));
        }
    }

    /* ===== 도움 ===== */

    private static String autoSql(long approvalId, String by) {
        return "UPDATE ops_ai_mode SET mode = 'AUTO', enabled_by = '" + by + "', reason = 'r', approval_id = " + approvalId
                + ", since = now(), valid_until = now() + interval '1 hour', activation_at = now(), "
                + "activation_limits = '{}'";
    }

    /** 티켓 행. consume 이면 같은 트랜잭션에서 소각한 것처럼 consumed_at 을 now() 로 적는다. */
    private static long ticket(Connection conn, String action, String target, String requester, boolean consume)
            throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "INSERT INTO admin_approvals (approval_token, action, target, requester_operator_id, "
                        + "approver_operator_id, expires_at, state, consumed_at) VALUES ('T" + System.nanoTime() + "', '"
                        + action + "', '" + target + "', '" + requester + "', '" + requester + "', now() + interval '5 min', "
                        + (consume ? "'CONSUMED', now()" : "'ISSUED', NULL") + ") RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String windowSql(String span, String alertname, String labels, String reason) {
        return "INSERT INTO ops_maintenance_windows (starts_at, ends_at, alertname, labels, reason, created_by) "
                + "VALUES (now(), now() + " + span + ", " + alertname + ", " + labels + "::jsonb, " + reason + ", 'op-02')";
    }

    private static String actionSql(long decision, String identity, String status) {
        return "INSERT INTO ops_ai_actions (first_decision_id, last_decision_id, kind, identity_key, params, target, "
                + "confidence, status, created_at, expires_at) VALUES (" + decision + ", " + decision + ", 'READ_NODES', '"
                + identity + "', '{\"delta\":1}', 'app', 0.9, '" + status + "', now(), now() + interval '15 minutes')";
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
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "ai_app", "app");
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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

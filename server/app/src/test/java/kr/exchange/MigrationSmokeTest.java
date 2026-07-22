package kr.exchange;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway V0+V1이 실 PostgreSQL에서 끝까지 적용되고, 외부 검토 P0의 핵심 계약이
 * DB 계층에서 실제로 강제되는지 검증한다.
 * 파티션드 테이블 IDENTITY가 PG17 의존이라 이미지도 17로 고정한다(DB 설계 v2.5 전제).
 */
@Testcontainers
class MigrationSmokeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final List<String> CORE_TABLES = List.of(
            "assets", "markets", "users", "api_keys", "accounts", "balances",
            "orders", "trades", "trade_seq_registry",
            "ledger_transactions", "ledger_entries",
            "command_journal", "event_journal", "event_emission_registry",
            "snapshots", "journal_counters",
            "outbox", "processed_events", "idempotency_keys", "leader_lease",
            // V3 관측 테이블
            "invariant_checks", "recovery_runs",
            // V5 관리 평면 테이블
            "admin_operators", "operator_roles", "admin_sessions", "admin_approvals",
            "admin_audit_log", "system_config", "notices");

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        var result = flyway.migrate();
        assertTrue(result.success, "Flyway migration failed");

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                        (1, 'BTC', 'Bitcoin', 'CRYPTO', 8),
                        (2, 'KRW', 'Korean Won', 'FIAT', 0)
                    ON CONFLICT (id) DO NOTHING
                    """);
        }
    }

    // ------------------------------------------------------------------
    // 스키마 존재 + 권한 경계
    // ------------------------------------------------------------------

    @Test
    void coreTablesRolesAndSeedsExist() throws Exception {
        try (Connection conn = open()) {
            for (String table : CORE_TABLES) {
                assertTrue(tableExists(conn, table), "missing table: " + table);
            }
            assertEquals(1, countRows(conn, "leader_lease"), "leader_lease bootstrap seed missing");
            // V1 command_seq·event_seq + V2 order_id
            assertEquals(3, countRows(conn, "journal_counters"), "journal_counters seeds missing");
            assertTrue(tableExists(conn, "trades_default"), "trades DEFAULT partition missing");
            assertTrue(tableExists(conn, "command_journal_default"), "command_journal DEFAULT partition missing");

            assertTrue(queryBool(conn, "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_role')"),
                    "app_role missing (V0)");
            assertTrue(queryBool(conn, "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='harness_role')"),
                    "harness_role missing (V0)");

            // P0-2: 앱 롤은 원장/잔고 직접 DML 불가, 게시 함수 EXECUTE만 가능
            for (String t : List.of("ledger_transactions", "ledger_entries", "balances")) {
                assertTrue(!queryBool(conn,
                                "SELECT has_table_privilege('app_role', '" + t + "', 'INSERT')"),
                        "app_role must not INSERT on " + t);
                assertTrue(!queryBool(conn,
                                "SELECT has_table_privilege('app_role', '" + t + "', 'UPDATE')"),
                        "app_role must not UPDATE on " + t);
            }
            assertTrue(queryBool(conn, """
                    SELECT has_function_privilege('app_role',
                        'post_ledger_transaction(character varying, bigint, jsonb)', 'EXECUTE')
                    """), "app_role must EXECUTE post_ledger_transaction");
        }
    }

    // ------------------------------------------------------------------
    // V3/V4: 관측·하네스 스키마가 빈 PG17에 단독 적용된다
    // ------------------------------------------------------------------

    @Test
    void observabilityAndHarnessObjectsExist() throws Exception {
        try (Connection conn = open()) {
            // V3 관측 테이블 컬럼 계약(정본 스키마와 1:1)
            assertTrue(columnExists(conn, "public", "invariant_checks", "violated_rows"),
                    "invariant_checks.violated_rows missing");
            assertTrue(columnExists(conn, "public", "recovery_runs", "fp_replay"),
                    "recovery_runs.fp_replay missing");
            assertTrue(columnExists(conn, "public", "recovery_runs", "matched"),
                    "recovery_runs.matched missing");

            // V4 harness 스키마 + 테이블
            assertTrue(queryBool(conn,
                            "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='harness')"),
                    "harness schema missing (V4)");
            for (String t : List.of("loadtest_accounts", "loadtest_acks",
                    "invariant_partition_sums", "calibration_runs", "failover_runs")) {
                assertTrue(queryBool(conn,
                                "SELECT EXISTS (SELECT 1 FROM pg_tables "
                                + "WHERE schemaname='harness' AND tablename='" + t + "')"),
                        "missing harness table: " + t);
            }
            assertTrue(columnExists(conn, "harness", "failover_runs", "rto_seconds"),
                    "failover_runs.rto_seconds missing");
            assertTrue(columnExists(conn, "harness", "failover_runs", "rpo_records"),
                    "failover_runs.rpo_records missing");

            // 관측 테이블은 append 가능해야 검증기가 결과를 남긴다
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO invariant_checks (check_name, ok, violated_rows) "
                        + "VALUES ('C01_ASSET_CONSERVATION', true, 0)");
                st.execute("INSERT INTO harness.failover_runs (experiment_id, verdict) "
                        + "VALUES ('E1', 'PASS')");
            }
            assertEquals(1, queryInt(conn, "SELECT count(*) FROM invariant_checks"),
                    "invariant_checks append failed");
        }
    }

    // ------------------------------------------------------------------
    // V5: 관리 평면 스키마가 빈 PG17에 단독 적용되고 핵심 제약이 강제된다
    // ------------------------------------------------------------------

    @Test
    void adminSchemaEnforcesOperatorAndApprovalContracts() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            // 운영자 2명 + 역할
            st.execute("INSERT INTO admin_operators (operator_id, display_name) "
                    + "VALUES ('op-01','운영자1'),('op-02','운영자2') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO operator_roles (operator_id, role) "
                    + "VALUES ('op-01','SUPER') ON CONFLICT DO NOTHING");

            // api_keys.scope ADMIN 어휘 확장 + operator 1:1 바인딩
            long uid = queryLong(conn, "INSERT INTO users (email) "
                    + "VALUES ('op1@admin.test') RETURNING id");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO api_keys (user_id, access_key, enc_secret, scope, operator_id) "
                    + "VALUES (?, 'AKadmin1', 'enc', 'ADMIN', 'op-01')")) {
                ps.setLong(1, uid);
                ps.executeUpdate();
            }
            // ADMIN scope인데 operator_id 미바인딩은 CHECK 위반
            SQLException noBinding = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO api_keys (user_id, access_key, enc_secret, scope) "
                        + "VALUES (?, 'AKadmin2', 'enc', 'ADMIN')")) {
                    ps.setLong(1, uid);
                    ps.executeUpdate();
                }
            });
            assertEquals("23514", noBinding.getSQLState(),
                    "ADMIN 키는 operator 바인딩 필수(check_violation): " + noBinding.getMessage());

            // admin_approvals: requester = approver는 물리 차단(반결탁)
            SQLException selfApprove = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO admin_approvals
                        (approval_token, action, requester_operator_id, approver_operator_id, expires_at)
                    VALUES ('T1', 'chaos.kill_node', 'op-01', 'op-01', now() + interval '5 min')
                    """));
            assertEquals("23514", selfApprove.getSQLState(),
                    "자가승인은 CHECK 위반이어야 한다: " + selfApprove.getMessage());
            // 서로 다른 운영자는 통과
            st.execute("""
                    INSERT INTO admin_approvals
                        (approval_token, action, requester_operator_id, approver_operator_id, expires_at)
                    VALUES ('T2', 'chaos.kill_node', 'op-01', 'op-02', now() + interval '5 min')
                    """);

            // admin_audit_log append-only: UPDATE·DELETE는 트리거 예외
            st.execute("INSERT INTO admin_audit_log (operator_id, action) VALUES ('op-01','test')");
            SQLException noUpd = assertThrows(SQLException.class,
                    () -> st.execute("UPDATE admin_audit_log SET result = 'DENIED'"));
            assertTrue(noUpd.getMessage().contains("append-only"), noUpd.getMessage());
            SQLException noDel = assertThrows(SQLException.class,
                    () -> st.execute("DELETE FROM admin_audit_log"));
            assertTrue(noDel.getMessage().contains("append-only"), noDel.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // (a) 균형 분개 게시 성공 + balances 갱신 + SEALED
    // ------------------------------------------------------------------

    @Test
    void postLedgerTransactionUpdatesBalancesAndSeals() throws Exception {
        try (Connection conn = open()) {
            long genesis = createAccount(conn, "SYSTEM", 0, "GENESIS", 1, 1);
            long avail = createAccount(conn, "USER", 101, "AVAILABLE", 1, 0);

            long txn = postLedger(conn, "GENESIS", 1001, """
                    [{"account_id": %d, "asset_id": 1, "amount": -10.5, "entry_type": "GENESIS"},
                     {"account_id": %d, "asset_id": 1, "amount": 10.5, "entry_type": "GENESIS"}]
                    """.formatted(genesis, avail));
            assertTrue(txn > 0, "post_ledger_transaction must return txn id");

            assertEquals(0, new BigDecimal("10.5").compareTo(balanceOf(conn, avail)),
                    "AVAILABLE balance not updated");
            assertEquals(0, new BigDecimal("-10.5").compareTo(balanceOf(conn, genesis)),
                    "GENESIS balance not updated");

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, sealed_at FROM ledger_transactions WHERE id = ?")) {
                ps.setLong(1, txn);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "posted txn header missing");
                    assertEquals("SEALED", rs.getString(1));
                    assertNotNull(rs.getTimestamp(2), "sealed_at must be set");
                }
            }

            // 정합 뷰: 게시 후 mismatch 행이 없어야 한다
            assertEquals(0, queryInt(conn,
                            "SELECT count(*) FROM balance_ledger_check WHERE mismatch"),
                    "balance_ledger_check reports mismatch after posting");
        }
    }

    // ------------------------------------------------------------------
    // (b) 불균형 entries는 예외 + 전체 롤백
    // ------------------------------------------------------------------

    @Test
    void unbalancedEntriesRejected() throws Exception {
        try (Connection conn = open()) {
            long genesis = createAccount(conn, "SYSTEM", 0, "GENESIS", 1, 2);
            long avail = createAccount(conn, "USER", 102, "AVAILABLE", 1, 0);

            SQLException ex = assertThrows(SQLException.class, () ->
                    postLedger(conn, "GENESIS", 2001, """
                            [{"account_id": %d, "asset_id": 1, "amount": -10, "entry_type": "GENESIS"},
                             {"account_id": %d, "asset_id": 1, "amount": 9, "entry_type": "GENESIS"}]
                            """.formatted(genesis, avail)));
            assertTrue(ex.getMessage().contains("불균형"), "expected imbalance error, got: " + ex.getMessage());

            assertEquals(0, queryInt(conn,
                            "SELECT count(*) FROM ledger_transactions WHERE business_event_id = 2001"),
                    "failed post must roll back entirely");
            assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(conn, avail)),
                    "failed post must not touch balances");
        }
    }

    // ------------------------------------------------------------------
    // (c) SEALED 거래에 직접 INSERT는 트리거 예외
    // ------------------------------------------------------------------

    @Test
    void sealedTransactionRejectsDirectEntryInsert() throws Exception {
        try (Connection conn = open()) {
            long genesis = createAccount(conn, "SYSTEM", 0, "GENESIS", 1, 3);
            long avail = createAccount(conn, "USER", 103, "AVAILABLE", 1, 0);

            long txn = postLedger(conn, "GENESIS", 3001, """
                    [{"account_id": %d, "asset_id": 1, "amount": -1, "entry_type": "GENESIS"},
                     {"account_id": %d, "asset_id": 1, "amount": 1, "entry_type": "GENESIS"}]
                    """.formatted(genesis, avail));

            SQLException ex = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO ledger_entries (transaction_id, account_id, asset_id, amount, entry_type)
                        VALUES (?, ?, 1, 5, 'TRADE')
                        """)) {
                    ps.setLong(1, txn);
                    ps.setLong(2, avail);
                    ps.executeUpdate();
                }
            });
            assertTrue(ex.getMessage().contains("봉인"), "expected sealed-guard error, got: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // (d) fencing: 잘못된 epoch/holder/만료 리스는 예외
    // ------------------------------------------------------------------

    @Test
    void assertCurrentLeaderEnforcesFencing() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("""
                    UPDATE leader_lease
                    SET holder = 'proc-uuid-1', epoch = 7,
                        lease_until = clock_timestamp() + interval '60 seconds'
                    WHERE resource = 'matching-engine'
                    """);

            // 정상: 현재 holder+epoch
            st.execute("SELECT assert_current_leader('proc-uuid-1', 7)");

            // 구식 epoch
            SQLException stale = assertThrows(SQLException.class,
                    () -> st.execute("SELECT assert_current_leader('proc-uuid-1', 6)"));
            assertTrue(stale.getMessage().contains("fencing"), stale.getMessage());

            // 다른 holder
            assertThrows(SQLException.class,
                    () -> st.execute("SELECT assert_current_leader('proc-uuid-2', 7)"));

            // 만료 리스
            st.execute("""
                    UPDATE leader_lease
                    SET lease_until = clock_timestamp() - interval '1 second'
                    WHERE resource = 'matching-engine'
                    """);
            SQLException expired = assertThrows(SQLException.class,
                    () -> st.execute("SELECT assert_current_leader('proc-uuid-1', 7)"));
            assertTrue(expired.getMessage().contains("만료"), expired.getMessage());

            // 만료 후 재승격은 epoch 증가
            try (ResultSet rs = st.executeQuery(
                    "SELECT try_acquire_leader('proc-uuid-3', interval '60 seconds')")) {
                assertTrue(rs.next());
                assertEquals(8, rs.getLong(1), "takeover must bump epoch");
            }
        }
    }

    // ------------------------------------------------------------------
    // (e) journal_counters UPDATE RETURNING 채번
    // ------------------------------------------------------------------

    @Test
    void journalCountersAllocateAtomically() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            long first = queryLong(conn, """
                    UPDATE journal_counters SET next_value = next_value + 1
                    WHERE name = 'command_seq' RETURNING next_value - 1
                    """);
            long second = queryLong(conn, """
                    UPDATE journal_counters SET next_value = next_value + 1
                    WHERE name = 'command_seq' RETURNING next_value - 1
                    """);
            assertEquals(first + 1, second, "command_seq allocation must be contiguous");

            long blockStart = queryLong(conn, "SELECT next_journal_value('event_seq', 5)");
            long afterBlock = queryLong(conn, "SELECT next_journal_value('event_seq', 1)");
            assertEquals(blockStart + 5, afterBlock, "block allocation must advance by count");

            assertThrows(SQLException.class,
                    () -> st.execute("SELECT next_journal_value('no_such_counter', 1)"));
        }
    }

    // ------------------------------------------------------------------
    // (f) 동일 business_event 중복 게시는 유일 제약 위반(23505)
    // ------------------------------------------------------------------

    @Test
    void duplicateBusinessEventRejected() throws Exception {
        try (Connection conn = open()) {
            long genesis = createAccount(conn, "SYSTEM", 0, "GENESIS", 1, 4);
            long avail = createAccount(conn, "USER", 104, "AVAILABLE", 1, 0);
            String entries = """
                    [{"account_id": %d, "asset_id": 1, "amount": -1, "entry_type": "RESERVE"},
                     {"account_id": %d, "asset_id": 1, "amount": 1, "entry_type": "RESERVE"}]
                    """.formatted(genesis, avail);

            postLedger(conn, "RESERVE", 4001, entries);
            SQLException ex = assertThrows(SQLException.class,
                    () -> postLedger(conn, "RESERVE", 4001, entries));
            assertEquals("23505", ex.getSQLState(), "expected unique_violation, got: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // (g) 결정론 이벤트 모델: 레지스트리 트리거가 중복 방출/중복 trade_id를 물리 차단
    // ------------------------------------------------------------------

    @Test
    void duplicateEventOrdinalAndTradeIdRejectedByRegistryTriggers() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                         tick_size, lot_size, min_notional, max_notional, max_qty)
                    VALUES (1, 'BTC-KRW', 1, 2, 1, 0.0001, 0, 100000000000, 10000)
                    ON CONFLICT (id) DO NOTHING
                    """);

            // event_journal: 동일 (source_cmd_seq, event_ordinal) 재방출은 트리거 경유 레지스트리 PK 위반
            st.execute("""
                    INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal,
                                               event_type, payload, fencing_epoch, created_at)
                    VALUES (900001, 500001, 0, 'ACCEPTED', '{}', 1, now())
                    """);
            assertEquals(1, queryInt(conn,
                            "SELECT count(*) FROM event_emission_registry WHERE source_cmd_seq = 500001"),
                    "event registry row must be auto-inserted by trigger");
            SQLException dupOrdinal = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal,
                                               event_type, payload, fencing_epoch, created_at)
                    VALUES (900002, 500001, 0, 'ACCEPTED', '{}', 1, now())
                    """));
            assertEquals("23505", dupOrdinal.getSQLState(),
                    "duplicate (source_cmd_seq, event_ordinal) must hit registry PK: " + dupOrdinal.getMessage());

            // trades: created_at이 달라 파티션드 PK(id, created_at)는 통과하는 중복 id도
            // 트리거 경유 trade_seq_registry PK가 차단한다
            st.execute("""
                    INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                        maker_user_id, taker_user_id, taker_side, price, quantity,
                                        fencing_epoch, executed_at, created_at)
                    VALUES (910001, 1, 1, 2, 11, 12, 'BUY', 100, 1, 1, now(), now())
                    """);
            assertEquals(1, queryInt(conn,
                            "SELECT count(*) FROM trade_seq_registry WHERE seq = 910001"),
                    "trade registry row must be auto-inserted by trigger");
            SQLException dupTrade = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                        maker_user_id, taker_user_id, taker_side, price, quantity,
                                        fencing_epoch, executed_at, created_at)
                    VALUES (910001, 1, 3, 4, 13, 14, 'SELL', 100, 1, 1,
                            now() + interval '1 second', now() + interval '1 second')
                    """));
            assertEquals("23505", dupTrade.getSQLState(),
                    "duplicate trade id must hit trade_seq_registry PK: " + dupTrade.getMessage());

            // 레지스트리 직접 INSERT 권한 부재: 선점 삽입 우회 봉쇄
            for (String t : List.of("trade_seq_registry", "event_emission_registry")) {
                assertTrue(!queryBool(conn,
                                "SELECT has_table_privilege('app_role', '" + t + "', 'INSERT')"),
                        "app_role must not INSERT on " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long postLedger(Connection conn, String eventType, long eventId, String entriesJson)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT post_ledger_transaction(?, ?, ?::jsonb)")) {
            ps.setString(1, eventType);
            ps.setLong(2, eventId);
            ps.setString(3, entriesJson);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long createAccount(Connection conn, String ownerType, long ownerId,
                               String purpose, int assetId, int shard) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """)) {
            ps.setString(1, ownerType);
            ps.setLong(2, ownerId);
            ps.setString(3, purpose);
            ps.setInt(4, assetId);
            ps.setInt(5, shard);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private BigDecimal balanceOf(Connection conn, long accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM balances WHERE account_id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "balance row missing for account " + accountId);
                return rs.getBigDecimal(1);
            }
        }
    }

    private boolean columnExists(Connection conn, String schema, String table, String column)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND column_name = ?
                """)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean tableExists(Connection conn, String table) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pg_class WHERE relname = ? AND relkind IN ('r','p')")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int countRows(Connection conn, String table) throws Exception {
        return queryInt(conn, "SELECT count(*) FROM " + table);
    }

    private int queryInt(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            boolean hasResult = st.execute(sql);
            assertTrue(hasResult, "expected a result set from: " + sql);
            try (ResultSet rs = st.getResultSet()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private boolean queryBool(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}

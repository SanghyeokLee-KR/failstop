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
 * Flyway V0+V1이 실 PostgreSQL에서 끝까지 적용되고 핵심 계약이 DB 계층에서 강제되는지 검증한다.
 * 파티션드 테이블 IDENTITY가 PG17 의존이라 이미지도 17로 고정한다.
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
            "invariant_checks", "recovery_runs",
            "admin_operators", "operator_roles", "admin_sessions", "admin_approvals",
            "admin_audit_log", "system_config", "notices",
            "user_credentials", "user_profiles", "auth_sessions", "user_provisioning",
            "posts", "comments", "post_likes", "comment_likes", "post_rank_snapshot",
            "reports", "community_moderation_log");

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

    @Test
    void coreTablesRolesAndSeedsExist() throws Exception {
        try (Connection conn = open()) {
            for (String table : CORE_TABLES) {
                assertTrue(tableExists(conn, table), "missing table: " + table);
            }
            assertEquals(1, countRows(conn, "leader_lease"), "leader_lease bootstrap seed missing");
            assertEquals(3, countRows(conn, "journal_counters"), "journal_counters seeds missing");
            assertTrue(tableExists(conn, "trades_default"), "trades DEFAULT partition missing");
            assertTrue(tableExists(conn, "command_journal_default"), "command_journal DEFAULT partition missing");

            assertTrue(queryBool(conn, "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_role')"),
                    "app_role missing (V0)");
            assertTrue(queryBool(conn, "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='harness_role')"),
                    "harness_role missing (V0)");

            // 앱 롤은 원장·잔고 직접 DML 불가, 게시 함수 EXECUTE만 가능.
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

            // 롤을 분리하면 GRANT 누락이 권한 오류로만 드러나 원인 추적이 한 단계 멀어진다.
            for (String t : List.of("sim_commands", "chaos_commands", "sim_fleet_state")) {
                for (String priv : List.of("SELECT", "INSERT", "UPDATE")) {
                    assertTrue(queryBool(conn,
                                    "SELECT has_table_privilege('app_role', '" + t + "', '" + priv + "')"),
                            "app_role must " + priv + " on " + t);
                }
                // 제어 이력이라 지우는 경로가 없어야 감사가 성립한다.
                // sim_fleet_state 는 이력이 아니라 갱신형이지만 같은 규율을 쓴다.
                assertTrue(!queryBool(conn,
                                "SELECT has_table_privilege('app_role', '" + t + "', 'DELETE')"),
                        "app_role must not DELETE on " + t);
            }
        }
    }

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

    @Test
    void adminSchemaEnforcesOperatorAndApprovalContracts() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO admin_operators (operator_id, display_name) "
                    + "VALUES ('op-01','운영자1'),('op-02','운영자2') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO operator_roles (operator_id, role) "
                    + "VALUES ('op-01','SUPER') ON CONFLICT DO NOTHING");

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

            // admin_approvals: 1인 승인이므로 requester = approver 가 통과해야 한다.
            // V34 가 chk_approval_two_person 을 지웠다(ADR 0019). 종전 판은 이 자리에서
            // 23514 를 기대했는데, 그 제약이 막은 것은 결탁이 아니라 야간 단독 운영의
            // 정지 명령이었다. 제약이 되살아나면 이 단언이 실패해 알린다.
            st.execute("""
                    INSERT INTO admin_approvals
                        (approval_token, action, requester_operator_id, approver_operator_id, expires_at)
                    VALUES ('T1', 'chaos.kill_node', 'op-01', 'op-01', now() + interval '5 min')
                    """);
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

    @Test
    void memberSchemaEnforcesCredentialAndSessionContracts() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            long uid = queryLong(conn, "INSERT INTO users (email, kind) "
                    + "VALUES ('member1@demo.test','HUMAN') RETURNING id");
            st.execute("INSERT INTO user_credentials (user_id, password_hash) "
                    + "VALUES (" + uid + ", '$argon2id$v=19$m=16384,t=2,p=1$abc$def')");
            st.execute("INSERT INTO user_profiles (user_id, display_name) "
                    + "VALUES (" + uid + ", '회원1')");

            // algo CHECK: 정의 밖 알고리즘은 거부
            SQLException badAlgo = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO user_credentials (user_id, password_hash, algo) VALUES ("
                    + uid + ", 'h', 'PLAINTEXT')"));
            assertEquals("23514", badAlgo.getSQLState(), badAlgo.getMessage());

            // auth_sessions: expires_at > issued_at CHECK
            SQLException badExpiry = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO auth_sessions (user_id, family_id, token_hash, issued_at, expires_at)
                    VALUES (%d, gen_random_uuid(), repeat('a', 64), now(), now() - interval '1 hour')
                    """.formatted(uid)));
            assertEquals("23514", badExpiry.getSQLState(), badExpiry.getMessage());

            // token_hash UNIQUE
            st.execute("""
                    INSERT INTO auth_sessions (user_id, family_id, token_hash, expires_at)
                    VALUES (%d, gen_random_uuid(), repeat('b', 64), now() + interval '14 days')
                    """.formatted(uid));
            SQLException dupToken = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO auth_sessions (user_id, family_id, token_hash, expires_at)
                    VALUES (%d, gen_random_uuid(), repeat('b', 64), now() + interval '14 days')
                    """.formatted(uid)));
            assertEquals("23505", dupToken.getSQLState(), dupToken.getMessage());

            // user_provisioning: genesis_event_id UNIQUE
            st.execute("INSERT INTO user_provisioning (user_id, genesis_event_id) "
                    + "VALUES (" + uid + ", 1000000000001)");
            long uid2 = queryLong(conn, "INSERT INTO users (email, kind) "
                    + "VALUES ('member2@demo.test','HUMAN') RETURNING id");
            SQLException dupGenesis = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO user_provisioning (user_id, genesis_event_id) VALUES ("
                    + uid2 + ", 1000000000001)"));
            assertEquals("23505", dupGenesis.getSQLState(), dupGenesis.getMessage());
        }
    }

    @Test
    void communitySchemaEnforcesBoardContracts() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            long author = queryLong(conn, "INSERT INTO users (email, kind) "
                    + "VALUES ('poster@demo.test','HUMAN') RETURNING id");

            // author_id는 users(id) FK: 미존재 회원은 23503
            SQLException badAuthor = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO posts (market_symbol, author_id, author_handle, title, body) "
                    + "VALUES ('KRW-BTC', 999999999, 'x', 't', 'b')"));
            assertEquals("23503", badAuthor.getSQLState(), badAuthor.getMessage());

            // status CHECK: 정의 밖 상태는 23514
            SQLException badStatus = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO posts (market_symbol, author_id, author_handle, title, body, status) "
                    + "VALUES ('KRW-BTC', " + author + ", 'x', 't', 'b', 'PURGED')"));
            assertEquals("23514", badStatus.getSQLState(), badStatus.getMessage());

            long postId = queryLong(conn, "INSERT INTO posts "
                    + "(market_symbol, author_id, author_handle, title, body) "
                    + "VALUES ('KRW-BTC', " + author + ", '작성자', '제목', '본문') RETURNING id");

            // comments.post_id FK: 미존재 글은 23503
            SQLException badPost = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO comments (post_id, author_id, author_handle, body) "
                    + "VALUES (999999999, " + author + ", 'x', 'c')"));
            assertEquals("23503", badPost.getSQLState(), badPost.getMessage());

            // 신고 대상별 1인 1회: 동일 (target_type, target_id, reporter_id)은 23505
            st.execute("INSERT INTO reports (target_type, target_id, reporter_id, reason) "
                    + "VALUES ('POST', " + postId + ", " + author + ", 'SPAM')");
            SQLException dupReport = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO reports (target_type, target_id, reporter_id, reason) "
                    + "VALUES ('POST', " + postId + ", " + author + ", 'ABUSE')"));
            assertEquals("23505", dupReport.getSQLState(), dupReport.getMessage());

            // 좋아요 PK 유니크: 동일 (post_id, member_id) 중복은 23505
            st.execute("INSERT INTO post_likes (post_id, member_id) VALUES ("
                    + postId + ", " + author + ")");
            SQLException dupLike = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO post_likes (post_id, member_id) VALUES ("
                    + postId + ", " + author + ")"));
            assertEquals("23505", dupLike.getSQLState(), dupLike.getMessage());

            // like_count 음수 방지 CHECK
            SQLException negLike = assertThrows(SQLException.class, () -> st.execute(
                    "UPDATE posts SET like_count = -1 WHERE id = " + postId));
            assertEquals("23514", negLike.getSQLState(), negLike.getMessage());
        }
    }

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

    @Test
    void assertCurrentLeaderEnforcesFencing() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("""
                    UPDATE leader_lease
                    SET holder = 'proc-uuid-1', epoch = 7,
                        lease_until = clock_timestamp() + interval '60 seconds'
                    WHERE resource = 'matching-engine'
                    """);

            st.execute("SELECT assert_current_leader('proc-uuid-1', 7)");

            SQLException stale = assertThrows(SQLException.class,
                    () -> st.execute("SELECT assert_current_leader('proc-uuid-1', 6)"));
            assertTrue(stale.getMessage().contains("fencing"), stale.getMessage());

            assertThrows(SQLException.class,
                    () -> st.execute("SELECT assert_current_leader('proc-uuid-2', 7)"));

            /*
             * assert_and_lock_leader(V30, 잠금 모드 V41) 의 계약. 검증과 FOR KEY SHARE 잠금을 한 왕복으로 한다.
             *
             * 거부 메시지를 여기서 못 박는 이유. 자바의 강등 판정이 SQLSTATE P0001 과 메시지의
             * `fencing 위반` 문구 일치로 동작한다(FencingErrors.isFencingRejection). 정산과 이벤트
             * 적재는 이 함수만 부르므로, 문구가 어긋나면 stale 리더가 강등되지 않고 자기를 리더로
             * 믿는다. 그 어긋남이 런타임이 아니라 여기서 드러나야 한다.
             */
            st.execute("SELECT assert_and_lock_leader('proc-uuid-1', 7)");

            SQLException lockedStale = assertThrows(SQLException.class,
                    () -> st.execute("SELECT assert_and_lock_leader('proc-uuid-1', 6)"));
            assertEquals("P0001", lockedStale.getSQLState(),
                    "강등 판정이 P0001 을 요구한다: " + lockedStale.getMessage());
            assertTrue(lockedStale.getMessage().contains("fencing 위반"),
                    "강등 판정이 이 문구를 요구한다: " + lockedStale.getMessage());

            SQLException wrongHolder = assertThrows(SQLException.class,
                    () -> st.execute("SELECT assert_and_lock_leader('proc-uuid-2', 7)"));
            assertTrue(wrongHolder.getMessage().contains("fencing 위반"), wrongHolder.getMessage());

            // 정상 경로는 epoch 을 돌려준다. 호출자가 fencing_epoch 컬럼에 쓰는 값이다.
            try (ResultSet rs = st.executeQuery("SELECT assert_and_lock_leader('proc-uuid-1', 7)")) {
                assertTrue(rs.next());
                assertEquals(7L, rs.getLong(1), "잠금 함수가 현재 epoch 을 돌려주지 않는다");
            }

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

    /**
     * 소유자 권한 트리거 함수는 남의 임시 표에 걸 수 없고(V47.1), 제 트리거로는 그대로 발화한다.
     * EXECUTE 는 CREATE TRIGGER 때만 검사되므로 PUBLIC 에 열려 있으면 레지스트리 선점 봉쇄가 우회된다.
     */
    @Test
    void ownerRightsTriggerFunctionsRejectForeignTriggersButStillFire() throws Exception {
        List<String> functions = List.of("create_balance_for_account", "register_trade_seq",
                "register_event_emission", "seed_market_journal_counters");

        try (Connection conn = open(); Statement st = conn.createStatement()) {
            // deploy/provision-runtime-roles.sql 의 앱·봇 로그인과 조회 로그인을 흉내 낸다.
            st.execute("CREATE ROLE probe_app LOGIN PASSWORD 'probe' IN ROLE app_role");
            st.execute("CREATE ROLE probe_reader LOGIN PASSWORD 'probe'");
            st.execute("GRANT USAGE ON SCHEMA public TO probe_reader");
            st.execute("GRANT SELECT ON assets, markets, trades TO probe_reader");
            st.execute("ALTER ROLE probe_reader SET default_transaction_read_only = on");
            assertTrue(!queryBool(conn, "SELECT pg_has_role('probe_reader', 'app_role', 'MEMBER')"),
                    "probe_reader must hold only PUBLIC privileges");
            // TEMP 가 있어야 아래 42501 이 임시 표가 아니라 함수 권한에서 나온 것이 된다.
            assertTrue(queryBool(conn,
                            "SELECT has_database_privilege('probe_reader', current_database(), 'TEMP')"),
                    "PUBLIC default TEMP privilege is the premise of this path");
            for (String fn : functions) {
                for (String role : List.of("app_role", "probe_reader")) {
                    assertTrue(!queryBool(conn, "SELECT has_function_privilege('" + role + "', '"
                                    + fn + "()', 'EXECUTE')"),
                            role + " must not EXECUTE " + fn);
                }
            }
            // 앞으로 더할 소유자 권한 함수도 PUBLIC 회수를 빠뜨리면 여기서 드러난다.
            String secdef = "FROM pg_proc WHERE prosecdef AND pronamespace = 'public'::regnamespace";
            assertTrue(queryInt(conn, "SELECT count(*) " + secdef) > 0, "no SECURITY DEFINER function found");
            assertEquals(0, queryInt(conn, "SELECT count(*) " + secdef
                            + " AND has_function_privilege('public', oid, 'EXECUTE')"),
                    "SECURITY DEFINER functions must not be EXECUTE-able by PUBLIC");
        }

        for (String login : List.of("probe_app", "probe_reader")) {
            try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), login, "probe");
                 Statement st = conn.createStatement()) {
                // 조회 로그인의 기본 읽기 전용은 세션에서 끌 수 있어 경계가 되지 못한다.
                st.execute("SET default_transaction_read_only = off");
                st.execute("CREATE TEMP TABLE probe (id SMALLINT, source_cmd_seq BIGINT, "
                        + "event_ordinal INT, event_seq BIGINT)");
                for (String fn : functions) {
                    SQLException denied = assertThrows(SQLException.class, () -> st.execute(
                            "CREATE TRIGGER plant_" + fn + " AFTER INSERT ON probe "
                            + "FOR EACH ROW EXECUTE FUNCTION public." + fn + "()"));
                    assertEquals("42501", denied.getSQLState(), login + ": " + denied.getMessage());
                    assertTrue(denied.getMessage().contains(fn), denied.getMessage());
                }
            }
        }

        // 발화는 EXECUTE 를 보지 않는다. EXECUTE 없는 앱 로그인의 정상 쓰기가 네 트리거를 모두 거친다.
        try (Connection conn = open();
             Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "probe_app", "probe");
             Statement st = app.createStatement()) {
            assertTrue(queryBool(conn, "SELECT to_regclass('command_journal_b9') IS NULL "
                    + "AND to_regclass('event_journal_b9') IS NULL"), "market 9 leaves must not pre-exist");
            st.execute("""
                    INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                         tick_size, lot_size, min_notional, max_notional, max_qty)
                    VALUES (9, 'PROBE-KRW', 1, 2, 1, 0.0001, 0, 100000000000, 10000)
                    """);
            assertEquals(2, queryInt(conn,
                            "SELECT count(*) FROM market_journal_counters WHERE market_id = 9"),
                    "market counters must be seeded by trigger");
            assertEquals(2, queryInt(conn, """
                            SELECT count(*) FROM pg_inherits
                            WHERE (inhrelid::regclass::text, inhparent::regclass::text) IN
                                  (('command_journal_b9', 'command_journal'), ('event_journal_b9', 'event_journal'))
                            """),
                    "band leaves must be created by trigger");

            long account = queryLong(app, "INSERT INTO accounts (owner_type, owner_id, purpose, asset_id) "
                    + "VALUES ('USER', 991, 'AVAILABLE', 1) RETURNING id");
            assertEquals(1, queryInt(conn, "SELECT count(*) FROM balances WHERE account_id = " + account),
                    "balance row must be created by trigger");

            st.execute("""
                    INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                        maker_user_id, taker_user_id, taker_side, price, quantity,
                                        fencing_epoch, executed_at, created_at)
                    VALUES (910101, 9, 1, 2, 11, 12, 'BUY', 100, 1, 1, now(), now())
                    """);
            assertEquals(1, queryInt(conn, "SELECT count(*) FROM trade_seq_registry WHERE seq = 910101"),
                    "trade registry row must be auto-inserted by trigger");

            st.execute("""
                    INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal,
                                               event_type, payload, fencing_epoch, created_at)
                    VALUES (900101, 500101, 0, 'ACCEPTED', '{}', 1, now())
                    """);
            assertEquals(1, queryInt(conn,
                            "SELECT count(*) FROM event_emission_registry WHERE source_cmd_seq = 500101"),
                    "event registry row must be auto-inserted by trigger");
        }
    }

    /**
     * 소유자 권한 함수는 search_path 끝에 pg_temp 를 둔다(V47.2). 적혀 있지 않으면 PostgreSQL 이 임시 스키마를
     * 먼저 찾아 호출자의 같은 이름 임시 표가 함수 안의 실제 표를 가린다.
     */
    @Test
    void securityDefinerFunctionsPinSearchPathWithPgTempLast() throws Exception {
        try (Connection conn = open()) {
            String secdef = "FROM pg_proc WHERE prosecdef AND pronamespace = 'public'::regnamespace";
            assertTrue(queryInt(conn, "SELECT count(*) " + secdef) > 0, "no SECURITY DEFINER function found");
            assertEquals(0, queryInt(conn, "SELECT count(*) " + secdef
                            + " AND NOT coalesce('search_path=public, pg_temp' = ANY (proconfig), false)"),
                    "SECURITY DEFINER functions must set search_path = public, pg_temp");
        }
    }

    @Test
    void tempRegistryCannotShadowDuplicateTradeId() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE ROLE probe_sp_trade LOGIN PASSWORD 'probe' IN ROLE app_role");
            st.execute("""
                    INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                         tick_size, lot_size, min_notional, max_notional, max_qty)
                    VALUES (1, 'BTC-KRW', 1, 2, 1, 0.0001, 0, 100000000000, 10000)
                    ON CONFLICT (id) DO NOTHING
                    """);
        }

        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "probe_sp_trade", "probe");
             Statement st = app.createStatement()) {
            st.execute("CREATE TEMP TABLE trade_seq_registry "
                    + "(seq BIGINT, trade_created_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now())");
            st.execute("GRANT ALL ON trade_seq_registry TO PUBLIC");
            st.execute("""
                    INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                        maker_user_id, taker_user_id, taker_side, price, quantity,
                                        fencing_epoch, executed_at, created_at)
                    VALUES (920001, 1, 1, 2, 11, 12, 'BUY', 100, 1, 1, now(), now())
                    """);
            SQLException dup = assertThrows(SQLException.class, () -> st.execute("""
                    INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                        maker_user_id, taker_user_id, taker_side, price, quantity,
                                        fencing_epoch, executed_at, created_at)
                    VALUES (920001, 1, 3, 4, 13, 14, 'SELL', 100, 1, 1,
                            now() + interval '1 second', now() + interval '1 second')
                    """));
            assertEquals("23505", dup.getSQLState(), dup.getMessage());
            assertEquals(0, queryInt(app, "SELECT count(*) FROM pg_temp.trade_seq_registry"),
                    "temp registry must not receive trigger rows");
        }

        try (Connection conn = open()) {
            assertEquals(1, queryInt(conn, "SELECT count(*) FROM public.trade_seq_registry WHERE seq = 920001"),
                    "trade registry row must land in the real table");
        }
    }

    @Test
    void tempTablesCannotShadowLedgerPosting() throws Exception {
        long avail;
        long locked;
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE ROLE probe_sp_ledger LOGIN PASSWORD 'probe' IN ROLE app_role");
            long genesis = createAccount(conn, "SYSTEM", 0, "GENESIS", 1, 5);
            avail = createAccount(conn, "USER", 105, "AVAILABLE", 1, 0);
            locked = createAccount(conn, "USER", 105, "LOCKED", 1, 0);
            postLedger(conn, "GENESIS", 5001, """
                    [{"account_id": %d, "asset_id": 1, "amount": -10, "entry_type": "GENESIS"},
                     {"account_id": %d, "asset_id": 1, "amount": 10, "entry_type": "GENESIS"}]
                    """.formatted(genesis, avail));
        }

        List<String> shadows = List.of("accounts", "balances", "ledger_transactions", "ledger_entries");
        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "probe_sp_ledger", "probe");
             Statement st = app.createStatement()) {
            for (String t : shadows) {
                st.execute("CREATE TEMP TABLE " + t + " (LIKE public." + t + " INCLUDING ALL)");
                st.execute("GRANT ALL ON " + t + " TO PUBLIC");
            }
            queryLong(app, "SELECT reserve_funds(5002, %d, %d, 1::SMALLINT, 4)".formatted(avail, locked));
            queryLong(app, "SELECT release_funds(5003, %d, %d, 1::SMALLINT, 1)".formatted(locked, avail));
            for (String t : shadows) {
                assertEquals(0, queryInt(app, "SELECT count(*) FROM pg_temp." + t),
                        "posting must not write the temp " + t);
            }
        }

        try (Connection conn = open()) {
            assertEquals(2, queryInt(conn, """
                            SELECT count(*) FROM public.ledger_transactions
                            WHERE (business_event_type, business_event_id) IN (('RESERVE', 5002), ('RELEASE', 5003))
                              AND status = 'SEALED'
                            """),
                    "reserve and release must be sealed in the real ledger");
            assertEquals(0, new BigDecimal("7").compareTo(balanceOf(conn, avail)), "AVAILABLE balance");
            assertEquals(0, new BigDecimal("3").compareTo(balanceOf(conn, locked)), "LOCKED balance");
            assertEquals(0, queryInt(conn, "SELECT count(*) FROM balance_ledger_check WHERE mismatch "
                            + "AND account_id IN (" + avail + ", " + locked + ")"),
                    "balances must match the real ledger");
        }
    }

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

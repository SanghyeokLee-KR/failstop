package kr.exchange.engine.lease;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static kr.exchange.engine.lease.PartitionLeaseDb.queryLong;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V55 모드 전환 함수의 전이 규칙과 선행 조건(ADR 0034 S7, DB 설계 §3.10·§5.4-e, S7 설계 4-1·6-2). 운영과 같은 set_leader_protocol
 * 로 오르내린다. 받는 전이는 1->2, 2->1, 2->3, 3->2 뿐이고 내리는 전이는 확인 인자가 있어야 한다. 3 으로 올릴 때 선행 조건 아홉
 * (정산 전 NEW, 명령 대역 래치, 대역 잎, 마켓 대응, 마켓 카운터, 파티션 리스 행, FEE_INCOME 샤드 0·1, app_role 의 리스 UPDATE,
 * app_role 의 저널 INSERT)을 하나씩 어기면 leader_protocol_blockers 가 그 사유 한 줄을 내고 전환은 55000 으로 아무것도 바꾸지
 * 않는다. 모두 풀면 2->3 이 세 행을 V50 과 같은 순서로 퇴역시킨다. P3 에서는 최고 모드 2 의 획득과 matching-engine 획득이
 * 거부되고, 한 파티션의 리스로 다른 파티션 마켓에 쓸 수 없다. 3->2 는 진행 중 쓰기의 KEY SHARE 를 5초까지 기다린 뒤 55P03 이다.
 */
class PartitionProtocolTransitionTest {

    private static final String OBJECT_NOT_IN_PREREQUISITE_STATE = "55000";

    @Test
    void transitionRulesAndEachPreconditionOneByOne() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("s7_transition").withRuntimeRoles();
        db.seedMarkets(1, 2);
        seedFeeIncome(db);
        assertEquals(List.of("band_latch_missing"), blockers(db, 3), "P1 새 DB 에서 3 으로 올릴 때 막는 것은 래치뿐이다");

        // P1 에서 곧바로 3 으로 가는 전이와 같은 모드 전이는 받지 않는다.
        assertRefused(db, "SELECT * FROM set_leader_protocol(3::smallint)", "받지 않는다");
        assertRefused(db, "SELECT * FROM set_leader_protocol(1::smallint)", "받지 않는다");
        assertEquals(1, protocol(db));
        assertEquals(3, db.switchProtocol(2, false));

        // (1) 명령 대역 래치.
        assertEquals(List.of("band_latch_missing"), blockers(db, 3));
        assertSwitchBlocked(db, "band_latch_missing");
        db.exec("SELECT latch_command_band_cutover()");
        assertEquals(List.of(), blockers(db, 3));

        // (2) 정산되지 않은 주문 생성 명령. 모든 목표에 걸린다(3->2·2->1 도).
        long me = Long.parseLong(db.ownerString(
                "SELECT try_acquire_lease('matching-engine', 'N1', interval '60 seconds', 3::smallint)"));
        long newSeq = queryOwner(db, "SELECT next_market_command_seq(1::smallint)");
        db.exec("""
                INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                                             engine_ts, tie_break, payload, fencing_token, checksum)
                VALUES (%d, 1, 'NEW', 1, 's7-new', 1, 0, 0, '{}'::jsonb, %d, 0)
                """.formatted(newSeq, me));
        assertEquals(List.of("unsettled_new"), blockers(db, 3));
        assertEquals(List.of("unsettled_new"), blockers(db, 2), "정산 전 NEW 는 내리는 전이도 막는다");
        assertSwitchBlocked(db, "unsettled_new");
        db.exec("""
                INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch,
                                           created_at, market_id, market_seq)
                SELECT r.out_event_seq, %d, 0, 'ACCEPTED', '{}'::jsonb, %d, clock_timestamp(), 1, r.out_market_seq
                  FROM next_market_event_seq(1::smallint) r
                """.formatted(newSeq, me));
        db.exec("SELECT release_lease('matching-engine', 'N1', " + me + ")");
        assertEquals(List.of(), blockers(db, 3), "정산(방출 레지스트리)이 unsettled_new 를 풀지 않았다");

        // (3) FEE_INCOME 샤드 1 이 없는 자산. 트리거를 잠시 끄고 자산 5 와 그 샤드 0, 마켓 3(자산 5/1)을 넣는다.
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE accounts DISABLE TRIGGER trg_accounts_fee_income_shard");
            st.execute("INSERT INTO assets (id, symbol, name, kind, scale) VALUES (5, 'SOL', 'SOL', 'CRYPTO', 6)");
            st.execute("INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES ('SYSTEM', 0, 'FEE_INCOME', 5, 0)");
            st.execute("ALTER TABLE accounts ENABLE TRIGGER trg_accounts_fee_income_shard");
            st.execute(marketSql(3, 5));
        }
        assertEquals(List.of("fee_shard_missing"), blockers(db, 3));
        assertSwitchBlocked(db, "fee_shard_missing");
        db.exec("INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES ('SYSTEM', 0, 'FEE_INCOME', 5, 1)");
        assertEquals(List.of(), blockers(db, 3));

        // (4) 마켓 대응. 대응 시드 트리거만 끈 마켓 4.
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE markets DISABLE TRIGGER trg_markets_seed_lease_partition");
            st.execute(marketSql(4, 2));
            st.execute("ALTER TABLE markets ENABLE TRIGGER trg_markets_seed_lease_partition");
        }
        assertEquals(List.of("market_partition_missing"), blockers(db, 3));
        assertSwitchBlocked(db, "market_partition_missing");
        db.exec("INSERT INTO market_lease_partition (market_id, resource) VALUES (4, default_lease_partition(4::smallint))");
        assertEquals(List.of(), blockers(db, 3));

        // (5) 마켓 카운터. 카운터 트리거를 끈 마켓 5 에 대역 잎만 만든다. (6) 대역 잎. 같은 방식의 마켓 6 에 카운터만 넣는다.
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE markets DISABLE TRIGGER trg_markets_seed_counters");
            st.execute(marketSql(5, 2));
            st.execute("ALTER TABLE markets ENABLE TRIGGER trg_markets_seed_counters");
            st.execute("SELECT ensure_command_band_partition(5::smallint)");
            st.execute("SELECT ensure_event_band_partition(5::smallint)");
        }
        assertEquals(List.of("market_counter_missing"), blockers(db, 3));
        assertSwitchBlocked(db, "market_counter_missing");
        db.exec("INSERT INTO market_journal_counters (market_id, name, next_value) VALUES (5, 'command_seq', 1), (5, 'event_seq', 1)");
        assertEquals(List.of(), blockers(db, 3));
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE markets DISABLE TRIGGER trg_markets_seed_counters");
            st.execute(marketSql(6, 2));
            st.execute("ALTER TABLE markets ENABLE TRIGGER trg_markets_seed_counters");
            st.execute("INSERT INTO market_journal_counters (market_id, name, next_value) VALUES (6, 'command_seq', 1), (6, 'event_seq', 1)");
        }
        assertEquals(List.of("band_leaf_missing"), blockers(db, 3));
        assertSwitchBlocked(db, "band_leaf_missing");
        db.exec("SELECT ensure_command_band_partition(6::smallint)");
        db.exec("SELECT ensure_event_band_partition(6::smallint)");
        assertEquals(List.of(), blockers(db, 3));

        // (7) app_role 의 리스 UPDATE(표 전체, 열 하나)와 (8) 저널 INSERT. V49 회수가 되돌려진 상태다.
        for (String grant : List.of("UPDATE ON leader_lease", "UPDATE (holder) ON leader_lease")) {
            db.exec("GRANT " + grant + " TO app_role");
            assertEquals(List.of("app_role_lease_update"), blockers(db, 3), grant);
            assertSwitchBlocked(db, "app_role_lease_update");
            db.exec("REVOKE " + grant + " FROM app_role");
        }
        db.exec("GRANT INSERT ON command_journal TO app_role");
        assertEquals(List.of("app_role_journal_insert"), blockers(db, 3));
        assertSwitchBlocked(db, "app_role_journal_insert");
        db.exec("REVOKE INSERT ON command_journal FROM app_role");
        assertEquals(List.of(), blockers(db, 3));

        // (9) 파티션 리스 행. 보통 경로로는 지울 수 없다(획득 이력·대응의 외래 키). 트리거를 끈 세션(복원 도구 흉내)에서 지운
        // 트랜잭션 안에서만 그 사유 한 줄이 나고, 전환도 그 사유로 거부된다. 롤백한다(S7 설계 3절 R7-1).
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            SQLException fk = assertThrows(SQLException.class,
                    () -> st.execute("DELETE FROM leader_lease WHERE resource = 'matching-p1'"));
            assertEquals("23503", fk.getSQLState(), fk.getMessage());
            c.setAutoCommit(false);
            st.execute("SET LOCAL session_replication_role = replica");
            assertEquals(1, st.executeUpdate("DELETE FROM leader_lease WHERE resource = 'matching-p1'"));
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT blocker || '|' || detail FROM leader_protocol_blockers(3::smallint)")) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            assertEquals(List.of("lease_row_missing|matching-p1"), rows);
            SQLException refused = assertThrows(SQLException.class,
                    () -> st.executeQuery("SELECT * FROM set_leader_protocol(3::smallint)").close());
            assertEquals(OBJECT_NOT_IN_PREREQUISITE_STATE, refused.getSQLState());
            assertTrue(refused.getMessage().contains("lease_row_missing"), refused.getMessage());
            c.rollback();
        }
        assertEquals(3, queryOwner(db, "SELECT count(*) FROM leader_lease"), "롤백 뒤 리스 행이 셋이 아니다");
        assertEquals(List.of(), blockers(db, 3));

        // 거부된 전환은 모드·리스·이력을 바꾸지 않았다.
        assertEquals(2, protocol(db));
        assertEquals(2, queryOwner(db, "SELECT count(*) FROM leader_protocol_history"), "거부된 전환이 이력을 남겼다");
        assertEquals(3, queryOwner(db, "SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE'"));

        // 모두 푼 뒤 2->3. 퇴역 순서 p0, p1, matching-engine 이고 epoch 는 이력 행의 epoch_floor 보다 크다.
        List<String[]> retired = switchRows(db, 3, false);
        assertEquals(List.of("matching-p0", "matching-p1", "matching-engine"), retired.stream().map(r -> r[0]).toList());
        long floor = queryOwner(db, "SELECT epoch_floor FROM leader_protocol_history ORDER BY id DESC LIMIT 1");
        long previous = floor;
        for (String[] r : retired) {
            long epoch = Long.parseLong(r[1]);
            assertTrue(epoch > previous, "퇴역 epoch 가 단조가 아니거나 epoch_floor 보다 작다");
            previous = epoch;
            assertEquals("3", r[2], "퇴역 행의 모드");
        }
        assertEquals(3, protocol(db));
        assertEquals("3:2", db.ownerString("SELECT protocol || ':' || prev_protocol FROM leader_protocol_history ORDER BY id DESC LIMIT 1"));

        // P3: 최고 모드 2 의 획득과 matching-engine 획득은 55000, 최고 모드 3 은 두 파티션을 얻는다.
        assertRefused(db, "SELECT try_acquire_lease('matching-p1', 'N2', interval '30 seconds', 2::smallint)", "P2");
        assertRefused(db, "SELECT try_acquire_lease('matching-engine', 'N1', interval '30 seconds', 3::smallint)",
                "matching-engine");
        long p0 = Long.parseLong(db.ownerString("SELECT try_acquire_lease('matching-p0', 'N1', interval '60 seconds', 3::smallint)"));
        long p1 = Long.parseLong(db.ownerString("SELECT try_acquire_lease('matching-p1', 'N2', interval '60 seconds', 3::smallint)"));
        assertTrue(p0 > previous && p1 > p0, "P3 첫 획득이 마지막 퇴역 epoch 뒤가 아니다");

        // 시장 도출 fencing: p0 의 holder·epoch 로 p1 마켓(2) 행은 0행, p0 마켓(1) 행은 1행이다.
        assertEquals(0, appendRow(db, 2, "N1", p0), "p0 리스로 p1 마켓에 썼다");
        assertEquals(1, appendRow(db, 1, "N1", p0));
        assertEquals(0, appendRow(db, 1, "N2", p1), "p1 리스로 p0 마켓에 썼다");

        // 3->1 과 3->3 은 전이 거부, 확인 인자 없는 3->2 는 되돌리기 거부다.
        assertRefused(db, "SELECT * FROM set_leader_protocol(1::smallint, true)", "받지 않는다");
        assertRefused(db, "SELECT * FROM set_leader_protocol(3::smallint)", "받지 않는다");
        assertRefused(db, "SELECT * FROM set_leader_protocol(2::smallint)", "되돌리기");
        assertEquals(3, protocol(db));
        assertEquals(p0, queryOwner(db, "SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'"), "거부가 리스를 바꿨다");
    }

    /**
     * 3->2 는 진행 중 쓰기가 쥔 파티션 리스 행의 KEY SHARE 를 5초까지 기다린 뒤 55P03 으로 끝나고 아무것도 바꾸지 않는다(S7 설계
     * 3절 P7-4). 앱 로그인은 전환 함수와 선행 조건 함수를 부르지 못한다.
     */
    @Test
    void rollbackWaitsForInFlightWritesAndGivesUpAfterFiveSeconds() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("s7_transition_lock").withRuntimeRoles();
        db.seedMarkets(1, 2);
        seedFeeIncome(db);
        db.switchProtocol(2, false);
        db.exec("SELECT latch_command_band_cutover()");
        db.switchProtocol(3, false);
        long p1 = Long.parseLong(db.ownerString("SELECT try_acquire_lease('matching-p1', 'N2', interval '60 seconds', 3::smallint)"));
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            for (String sql : List.of("SELECT * FROM set_leader_protocol(2::smallint, true)",
                    "SELECT * FROM leader_protocol_blockers(3::smallint)")) {
                SQLException denied = assertThrows(SQLException.class, () -> st.executeQuery(sql).close(), sql);
                assertEquals("42501", denied.getSQLState(), sql + ": " + denied.getMessage());
            }
        }
        long retiredBefore = queryOwner(db, "SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE'");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            try (Connection c = db.app()) {
                c.setAutoCommit(false);
                assertEquals(p1, queryLong(c, "SELECT assert_and_lock_market_leader(2::smallint, 'N2', " + p1 + ")"));
                held.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
                c.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(held.await(30, TimeUnit.SECONDS), "쓰기 판정이 p1 행을 잡지 못했다");
        long t0 = System.nanoTime();
        SQLException timeout = assertThrows(SQLException.class, () -> switchRows(db, 2, true));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        release.countDown();
        writer.get(30, TimeUnit.SECONDS);
        assertEquals("55P03", timeout.getSQLState(), timeout.getMessage());
        assertTrue(waitedMs >= 4_500 && waitedMs < 15_000, "잠금 대기 상한이 5초가 아니다: " + waitedMs);
        assertEquals(3, protocol(db), "시간 초과한 전환이 모드를 바꿨다");
        assertEquals(retiredBefore, queryOwner(db, "SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE'"));
        assertEquals(p1, queryOwner(db, "SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'"));
        // 쓰기가 끝나면 같은 전환이 곧바로 된다.
        assertEquals(3, switchRows(db, 2, true).size());
        assertEquals(2, protocol(db));
        assertRefused(db, "SELECT try_acquire_lease('matching-p1', 'N2', interval '60 seconds', 3::smallint)",
                "획득할 수 없다");
    }

    /**
     * 내리는 전이(3->2, 2->1)도 정산되지 않은 주문 생성 명령이 있으면 확인 인자를 줘도 55000 으로 거부되고 모드·리스·이력을 바꾸지
     * 않는다(DB 설계 §3.10, ADR 0034 결정 8, S7 설계 4-8 모드 경계). V50 은 이 검사를 전환 함수 본문에 두었고 V55 는
     * leader_protocol_blockers 호출로 옮겼다. 그 호출을 오르는 전이에만 거는 회귀를 여기서 잡는다. 정산하면 같은 전이가 된다(양성 대조).
     */
    @Test
    void downgradesAreRefusedWhileANewCommandIsUnsettled() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("s7_transition_down").withRuntimeRoles();
        db.seedMarkets(1, 2);
        seedFeeIncome(db);
        assertEquals(3, db.switchProtocol(2, false));
        db.exec("SELECT latch_command_band_cutover()");

        // P2 -> P1. matching-engine 리더가 마켓 1 에 넣고 아직 정산하지 않은 NEW.
        long me = Long.parseLong(db.ownerString(
                "SELECT try_acquire_lease('matching-engine', 'N1', interval '60 seconds', 3::smallint)"));
        long p2New = insertNew(db, 1, "s7-down-p2", me);
        assertDowngradeBlocked(db, 1, 2);
        settle(db, 1, p2New, me);
        db.exec("SELECT release_lease('matching-engine', 'N1', " + me + ")");

        // P3 -> P2. p1 리더가 p1 마켓(2)에 넣고 아직 정산하지 않은 NEW.
        assertEquals(3, db.switchProtocol(3, false));
        long p1 = Long.parseLong(db.ownerString(
                "SELECT try_acquire_lease('matching-p1', 'N2', interval '60 seconds', 3::smallint)"));
        long p3New = insertNew(db, 2, "s7-down-p3", p1);
        assertDowngradeBlocked(db, 2, 3);

        // 정산하면 같은 내리는 전이가 된다. 3->2 다음 2->1 까지 간다.
        settle(db, 2, p3New, p1);
        db.exec("SELECT release_lease('matching-p1', 'N2', " + p1 + ")");
        assertEquals(3, switchRows(db, 2, true).size());
        assertEquals(2, protocol(db));
        assertEquals(3, switchRows(db, 1, true).size());
        assertEquals(1, protocol(db));
        assertEquals("1:2", db.ownerString(
                "SELECT protocol || ':' || prev_protocol FROM leader_protocol_history ORDER BY id DESC LIMIT 1"));
    }

    /** FEE_INCOME 샤드 0 을 모든 자산에 넣는다. 샤드 1 은 V55 트리거가 만든다. */
    static void seedFeeIncome(PartitionLeaseDb db) throws SQLException {
        db.exec("""
                INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                SELECT 'SYSTEM', 0, 'FEE_INCOME', a.id, 0 FROM assets a
                ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING
                """);
    }

    private static String marketSql(int id, int baseAsset) {
        return """
                INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size,
                                     min_notional, max_notional, max_qty)
                VALUES (%d, 'M%d', %d, 1, 1, 0.0001, 0, 1000000000, 1000000)
                """.formatted(id, id, baseAsset);
    }

    private static List<String> blockers(PartitionLeaseDb db, int target) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = db.owner(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT blocker FROM leader_protocol_blockers(" + target + "::smallint)")) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static List<String[]> switchRows(PartitionLeaseDb db, int target, boolean allowDowngrade) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection c = db.owner(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT out_resource, out_retired_epoch, out_protocol FROM set_leader_protocol("
                     + target + "::smallint, " + allowDowngrade + ")")) {
            while (rs.next()) {
                rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
            }
        }
        return rows;
    }

    /** 2->3 전환이 그 사유로 거부되고 아무것도 바꾸지 않는다. */
    private static void assertSwitchBlocked(PartitionLeaseDb db, String blocker) throws SQLException {
        long history = queryOwner(db, "SELECT count(*) FROM leader_protocol_history");
        long retired = queryOwner(db, "SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE'");
        SQLException refused = assertThrows(SQLException.class, () -> switchRows(db, 3, false));
        assertEquals(OBJECT_NOT_IN_PREREQUISITE_STATE, refused.getSQLState(), refused.getMessage());
        assertTrue(refused.getMessage().contains(blocker), blocker + " 가 메시지에 없다: " + refused.getMessage());
        assertEquals(2, protocol(db));
        assertEquals(history, queryOwner(db, "SELECT count(*) FROM leader_protocol_history"));
        assertEquals(retired, queryOwner(db, "SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE'"));
    }

    /**
     * 그 마켓에 정산 전 NEW 한 줄을 소유자로 넣는다. 전환 함수의 unsettled_new 는 방출 레지스트리에 짝이 없는 NEW 를 센다.
     * 돌려주는 값은 명령 seq 다.
     */
    private static long insertNew(PartitionLeaseDb db, int market, String clientOrderId, long epoch) throws SQLException {
        long seq = queryOwner(db, "SELECT next_market_command_seq(" + market + "::smallint)");
        db.exec("""
                INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                                             engine_ts, tie_break, payload, fencing_token, checksum)
                VALUES (%d, %d, 'NEW', 1, '%s', %d, 0, 0, '{}'::jsonb, %d, 0)
                """.formatted(seq, market, clientOrderId, seq, epoch));
        return seq;
    }

    /** 그 명령의 첫 이벤트를 넣는다. 이벤트 트리거가 방출 레지스트리 행을 만들어 정산된 것으로 친다. */
    private static void settle(PartitionLeaseDb db, int market, long commandSeq, long epoch) throws SQLException {
        db.exec("""
                INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch,
                                           created_at, market_id, market_seq)
                SELECT r.out_event_seq, %d, 0, 'ACCEPTED', '{}'::jsonb, %d, clock_timestamp(), %d, r.out_market_seq
                  FROM next_market_event_seq(%d::smallint) r
                """.formatted(commandSeq, epoch, market, market));
        assertEquals(List.of(), blockers(db, 1), "정산(방출 레지스트리)이 unsettled_new 를 풀지 않았다");
    }

    /** 확인 인자를 준 내리는 전이가 unsettled_new 로 거부되고 모드·리스·이력·퇴역 행을 바꾸지 않는다. */
    private static void assertDowngradeBlocked(PartitionLeaseDb db, int target, int current) throws SQLException {
        assertEquals(List.of("unsettled_new"), blockers(db, target));
        String leases = leaseRows(db);
        long history = queryOwner(db, "SELECT count(*) FROM leader_protocol_history");
        long retired = queryOwner(db, "SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE'");
        SQLException refused = assertThrows(SQLException.class, () -> switchRows(db, target, true),
                current + "->" + target + " 가 정산 전 NEW 를 두고 됐다");
        assertEquals(OBJECT_NOT_IN_PREREQUISITE_STATE, refused.getSQLState(), refused.getMessage());
        assertTrue(refused.getMessage().contains("unsettled_new"), refused.getMessage());
        assertEquals(current, protocol(db), "거부된 내리는 전이가 모드를 바꿨다");
        assertEquals(leases, leaseRows(db), "거부된 내리는 전이가 리스 행을 바꿨다");
        assertEquals(history, queryOwner(db, "SELECT count(*) FROM leader_protocol_history"), "거부된 전이가 이력을 남겼다");
        assertEquals(retired, queryOwner(db, "SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE'"));
    }

    private static String leaseRows(PartitionLeaseDb db) throws SQLException {
        return db.ownerString("""
                SELECT string_agg(resource || ':' || coalesce(holder, '') || ':' || epoch || ':' || lease_until::text, ','
                                  ORDER BY resource)
                  FROM leader_lease
                """);
    }

    private static void assertRefused(PartitionLeaseDb db, String sql, String messagePart) throws SQLException {
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            SQLException refused = assertThrows(SQLException.class, () -> st.executeQuery(sql).close(), sql);
            assertEquals(OBJECT_NOT_IN_PREREQUISITE_STATE, refused.getSQLState(), sql + ": " + refused.getMessage());
            assertTrue(refused.getMessage().contains(messagePart), sql + ": " + refused.getMessage());
        }
    }

    private static int appendRow(PartitionLeaseDb db, int market, String holder, long epoch) throws SQLException {
        return (int) queryOwner(db, """
                SELECT append_command_journal_row(next_market_command_seq(%d::smallint), %d::smallint, 'CANCEL', 1, NULL, 1,
                                                  0, 0, '{}'::jsonb, 0, '%s', %d)
                """.formatted(market, market, holder, epoch));
    }

    private static int protocol(PartitionLeaseDb db) throws SQLException {
        return (int) queryOwner(db, "SELECT protocol FROM leader_protocol");
    }

    private static long queryOwner(PartitionLeaseDb db, String sql) throws SQLException {
        try (Connection c = db.owner()) {
            return queryLong(c, sql);
        }
    }

    @SuppressWarnings("unused")
    private static String ownerString(PartitionLeaseDb db, String sql) throws SQLException {
        try (Connection c = db.owner()) {
            return queryString(c, sql);
        }
    }
}

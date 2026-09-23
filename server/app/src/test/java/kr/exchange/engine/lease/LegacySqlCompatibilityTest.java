package kr.exchange.engine.lease;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 이전 바이너리가 V48 DB 에서 오늘과 같이 도는지 고정한다(S2 설계 6-1, 4-1 되돌리기). 아래 상수는 S2 변경 직전 코드가 보내던
 * SQL 이다. 세 상태에서 앱 로그인으로 돌린다. (a) V48 DB 는 V47 DB 와 결과가 같다. (b) V49 권한 상태에서는 옛 append INSERT 와
 * 반납 UPDATE 가 42501 이고 나머지는 된다. (c) 되돌리기 GRANT 두 줄 뒤에는 (a) 와 같다.
 */
class LegacySqlCompatibilityTest {

    // PostgresLeaderLeaseStore 의 획득·갱신·반납·조회
    static final String ACQUIRE = "SELECT try_acquire_leader(?, ? * interval '1 millisecond')";
    static final String RENEW = "SELECT renew_leader_lease(?, ?, ? * interval '1 millisecond')";
    static final String RELEASE = """
            UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second'
            WHERE resource = 'matching-engine' AND holder = ? AND epoch = ?
            """;
    static final String CURRENT = """
            SELECT holder, epoch, lease_until FROM leader_lease
            WHERE resource = 'matching-engine'
            """;

    // CommandJournalAppender.PROLOGUE_SQL·INSERT_SQL
    static final String APPEND_PROLOGUE =
            "SELECT out_seq, out_replayed, out_fencing, out_order_id"
            + " FROM append_command_prologue(?, ?, ?, ?, ?, ?::smallint)";
    static final String APPEND_INSERT = """
            INSERT INTO command_journal
              (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
               engine_ts, tie_break, payload, fencing_token, checksum)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, l.epoch, ?
            FROM leader_lease l
            WHERE l.resource = 'matching-engine'
              AND l.holder = ? AND l.epoch = ? AND l.lease_until > clock_timestamp()
            FOR KEY SHARE OF l
            """;

    // SettlementService.PROLOGUE_SQL(CommandJournalReader.ENTRY_COLUMNS 를 펼친 문장)
    static final String SETTLE_PROLOGUE =
            "SELECT assert_and_lock_leader(?, ?) AS epoch,"
            + " (SELECT count(*) FROM event_emission_registry"
            + "   WHERE source_cmd_seq = ?) AS registered,"
            + " seq, market_id, command_type, user_id, client_order_id,"
            + " assigned_order_id, engine_ts, tie_break, payload::text AS payload,"
            + " fencing_token, checksum, created_at"
            + " FROM (VALUES (1)) AS one(x)"
            + " LEFT JOIN command_journal ON seq = ?";

    // SnapshotStore·EventJournalAppender.ASSERT_AND_LOCK_SQL
    static final String ASSERT_AND_LOCK = "SELECT assert_and_lock_leader(?, ?)";

    // PostgresFencingEpochPublisher 의 게시(DynamoDB 보관소 노드). V48 판은 프로토콜을 확인하고 획득 이력을 남긴다.
    static final String PUBLISH = "SELECT publish_leader_epoch(?, ?)";

    private static final String HOLDER = "legacy";
    private static final String PUBLISHER = "legacy-dynamo";
    private static final long SEED_EPOCH = 41;
    private static final long TTL_MILLIS = 60_000;

    @Test
    void legacySqlBehavesTheSameOnV48AsOnV47() throws Exception {
        List<String> v47 = run(legacyDatabase("legacy_v47", false));
        List<String> v48 = run(legacyDatabase("legacy_v48", true));
        assertEquals(v47, v48);
        assertEquals("acquire=42", v47.get(0), "S2 이전 획득이 V47 epoch 위에서 +1 이 아니다");
    }

    @Test
    void v49RefusesOnlyLegacyAppendInsertAndRelease() throws Exception {
        PartitionLeaseDb db = legacyDatabase("legacy_v49", true);
        db.applyV49Contract();
        db.expire("matching-engine");
        try (Connection app = db.app()) {
            assertEquals(SEED_EPOCH + 1, acquire(app));
            assertTrue(renew(app, SEED_EPOCH + 1));

            app.setAutoCommit(false);
            long seq = prologue(app, SEED_EPOCH + 1, "v49");
            SQLException insert = assertThrows(SQLException.class, () -> insert(app, seq, SEED_EPOCH + 1, "v49"));
            assertEquals("42501", insert.getSQLState(), insert.getMessage());
            app.rollback();

            // 정산 머리말과 스냅샷 잠금은 V48 의 소유자 권한 함수를 거쳐 V49 뒤에도 된다.
            assertEquals(String.valueOf(SEED_EPOCH + 1), settlePrologue(app, SEED_EPOCH + 1, seq).split("\\|")[0]);
            app.rollback();
            assertEquals(SEED_EPOCH + 1, lock(app, SEED_EPOCH + 1));
            app.rollback();
            app.setAutoCommit(true);

            SQLException release = assertThrows(SQLException.class, () -> release(app, SEED_EPOCH + 1));
            assertEquals("42501", release.getSQLState(), release.getMessage());

            // 게시는 소유자 권한 함수를 거쳐 V49 뒤에도 된다.
            assertEquals(String.valueOf(SEED_EPOCH + 11), publish(app, PUBLISHER, SEED_EPOCH + 11));
        }
    }

    @Test
    void grantingTheTwoPrivilegesBackRestoresV48Behaviour() throws Exception {
        List<String> v47 = run(legacyDatabase("legacy_base", false));
        PartitionLeaseDb db = legacyDatabase("legacy_grant", true);
        db.applyV49Contract();
        db.exec("GRANT UPDATE ON leader_lease TO app_role");
        db.exec("GRANT INSERT ON command_journal TO app_role");
        assertEquals(v47, run(db));
    }

    /** V47 까지 올려 기존 데이터(마켓 셋, 리스 epoch 41)를 넣고, v48 이면 그 위에 V48 을 올린다. */
    private static PartitionLeaseDb legacyDatabase(String prefix, boolean v48) throws SQLException {
        PartitionLeaseDb db = PartitionLeaseDb.createAt(prefix, "47");
        db.seedMarkets(1, 2, 3);
        db.seedLease("matching-engine", "old-node", SEED_EPOCH, 3600);
        if (v48) {
            db.migrate();
        }
        return db.withRuntimeRoles();
    }

    /** 앱 로그인으로 S2 이전 바이너리의 한 주기를 돌려 결과를 줄로 남긴다. 문장 순서는 앱과 같다. */
    private static List<String> run(PartitionLeaseDb db) throws SQLException {
        db.expire("matching-engine");
        List<String> out = new ArrayList<>();
        try (Connection app = db.app()) {
            long epoch = acquire(app);
            out.add("acquire=" + epoch);
            out.add("renew=" + renew(app, epoch));
            out.add("current=" + current(app));

            app.setAutoCommit(false);
            long seq = prologue(app, epoch, "L" + epoch);
            out.add("append=" + insert(app, seq, epoch, "L" + epoch));
            app.commit();
            out.add("stale-append=" + insert(app, seq + 100_000, epoch - 1, "S" + epoch));
            app.rollback();

            out.add("settle=" + settlePrologue(app, epoch, seq));
            app.rollback();
            out.add("lock=" + lock(app, epoch));
            app.rollback();
            SQLException stale = assertThrows(SQLException.class, () -> lock(app, epoch - 1));
            out.add("stale-lock=" + stale.getSQLState() + ":" + firstLine(stale.getMessage()));
            app.rollback();
            app.setAutoCommit(true);

            out.add("release=" + release(app, epoch));
            out.add("expired=" + PartitionLeaseDb.queryString(app, """
                    SELECT lease_until < clock_timestamp() FROM leader_lease WHERE resource = 'matching-engine'
                    """));

            // 외부 보관소 노드의 게시. 더 큰 epoch, 같은 보유자의 재게시, 더 작은 epoch 순이다.
            out.add("publish=" + publish(app, PUBLISHER, epoch + 10));
            out.add("republish=" + publish(app, PUBLISHER, epoch + 10));
            out.add("stale-publish=" + publish(app, "legacy-other", epoch + 9));
            out.add("published=" + current(app) + "|" + PartitionLeaseDb.queryString(app, """
                    SELECT lease_until > clock_timestamp() + interval '99 years' FROM leader_lease
                    WHERE resource = 'matching-engine'
                    """));
        }
        return out;
    }

    private static long acquire(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ACQUIRE)) {
            ps.setString(1, HOLDER);
            ps.setLong(2, TTL_MILLIS);
            return single(ps).getLong(1);
        }
    }

    private static boolean renew(Connection c, long epoch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RENEW)) {
            ps.setString(1, HOLDER);
            ps.setLong(2, epoch);
            ps.setLong(3, TTL_MILLIS);
            return single(ps).getBoolean(1);
        }
    }

    private static String current(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CURRENT)) {
            ResultSet rs = single(ps);
            return rs.getString("holder") + "|" + rs.getLong("epoch");
        }
    }

    private static long prologue(Connection c, long epoch, String clientOrderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(APPEND_PROLOGUE)) {
            ps.setString(1, HOLDER);
            ps.setLong(2, epoch);
            ps.setBoolean(3, true);
            ps.setLong(4, 1L);
            ps.setString(5, clientOrderId);
            ps.setInt(6, 1);
            return single(ps).getLong("out_seq");
        }
    }

    private static int insert(Connection c, long seq, long epoch, String clientOrderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(APPEND_INSERT)) {
            ps.setLong(1, seq);
            ps.setInt(2, 1);
            ps.setString(3, "NEW");
            ps.setLong(4, 1L);
            ps.setString(5, clientOrderId);
            ps.setLong(6, 5_000 + seq);
            ps.setLong(7, 0L);
            ps.setLong(8, 0L);
            ps.setString(9, "{}");
            ps.setLong(10, 0L);
            ps.setString(11, HOLDER);
            ps.setLong(12, epoch);
            return ps.executeUpdate();
        }
    }

    private static String settlePrologue(Connection c, long epoch, long seq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SETTLE_PROLOGUE)) {
            ps.setString(1, HOLDER);
            ps.setLong(2, epoch);
            ps.setLong(3, seq);
            ps.setLong(4, seq);
            ResultSet rs = single(ps);
            return rs.getLong("epoch") + "|" + rs.getLong("registered") + "|" + rs.getString("seq")
                    + "|" + rs.getString("market_id") + "|" + rs.getString("fencing_token");
        }
    }

    private static long lock(Connection c, long epoch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ASSERT_AND_LOCK)) {
            ps.setString(1, HOLDER);
            ps.setLong(2, epoch);
            return single(ps).getLong(1);
        }
    }

    /** 게시한 epoch. 더 큰 epoch 이 이미 있으면 SQL NULL 이라 "null" 이다. */
    private static String publish(Connection c, String holder, long epoch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(PUBLISH)) {
            ps.setString(1, holder);
            ps.setLong(2, epoch);
            return String.valueOf(single(ps).getString(1));
        }
    }

    private static int release(Connection c, long epoch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(RELEASE)) {
            ps.setString(1, HOLDER);
            ps.setLong(2, epoch);
            return ps.executeUpdate();
        }
    }

    private static ResultSet single(PreparedStatement ps) throws SQLException {
        ResultSet rs = ps.executeQuery();
        assertTrue(rs.next(), "결과 없음");
        return rs;
    }

    private static String firstLine(String message) {
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}

package kr.exchange.engine.lease;

import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.JournalChecksum;
import kr.exchange.journal.ReserveCheck;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 모드 전환 함수 set_leader_protocol 의 경합(ADR 0034 결정 6·8, S3 설계 4-7·6-2, 단계 계획 2절 검토 1 1b).
 * 전환은 모든 리스 행을 FOR UPDATE 로 잡은 뒤 선행 조건을 보고 모든 행을 새 epoch 로 퇴역시킨다. 시험마다 새 DB 다.
 */
class ProtocolLatchRaceTest {

    private static final String PROLOGUE_10 = "SELECT out_seq, out_reserved FROM append_command_prologue("
            + "?, ?, ?, ?, ?, ?::smallint, ?, ?::jsonb, ?::smallint, ?::numeric)";
    private static final String PROLOGUE_6 = "SELECT out_seq FROM append_command_prologue(?, ?, ?, ?, ?, ?::smallint)";
    private static final String PROLOGUE_5 = "SELECT out_seq FROM append_command_prologue(?, ?, ?, ?, ?)";
    private static final String ROW = "SELECT append_command_journal_row(?, ?::smallint, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)";

    /** (1) 전환 직전 커밋되는 미예약 NEW 는 전환을 55000 으로 롤백시킨다. 전환은 그 NEW 의 KEY SHARE 를 기다렸다. */
    @Test
    void unreservedNewCommittedWhileSwitchWaitsRollsTheSwitchBack() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("latch_race_1b", 1)) {
            long alice = f.user("alice", new BigDecimal("100000"));
            LeaderLeaseService lease = f.lease();
            OrderIntake buy = f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1");
            try (Connection writer = f.db.app(); Connection owner = f.db.owner()) {
                writer.setAutoCommit(false);
                long seq = prologue10(writer, lease, f.draft(buy), buy.reserveCheck());
                assertEquals(1, row(writer, lease, f.draft(buy), seq));
                int writerPid = pid(writer);

                CompletableFuture<SQLException> switching = CompletableFuture.supplyAsync(() -> {
                    try (Statement st = owner.createStatement()) {
                        st.executeQuery("SELECT * FROM set_leader_protocol(2::smallint)");
                        return null;
                    } catch (SQLException e) {
                        return e;
                    }
                });
                // 전환이 NEW 트랜잭션의 리스 행 잠금을 기다리는 표본을 본다(양성 축).
                assertTrue(waitsOn(f, "set_leader_protocol", writerPid), "전환이 KEY SHARE 를 기다리지 않았다");
                writer.commit();

                SQLException refused = switching.get(30, TimeUnit.SECONDS);
                assertTrue(refused != null, "미예약 NEW 가 남았는데 전환이 커밋됐다");
                assertEquals("55000", refused.getSQLState(), refused.getMessage());
                assertTrue(refused.getMessage().contains("정산되지 않은 주문 생성 명령"), refused.getMessage());
            }
            assertEquals(1, f.protocol(), "전환이 모드를 바꿨다");
            assertEquals(0, f.count("SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE'"));
        }
    }

    /**
     * (2) 전환이 열린 트랜잭션으로 리스 행을 쥔 동안 시작한 실제 append 는 전환 커밋 뒤 퇴역 epoch 로 fencing 거부되고 강등된다.
     * (3) 같은 상황의 V48 경로(6인자 머리말 + 저널 행 함수)는 머리말을 통과해도 행 함수가 0행이다.
     */
    @Test
    void appendsInFlightDuringTheSwitchAreFenced() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("latch_race_inflight", 1)) {
            long bob = f.user("bob", new BigDecimal("100000"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, false);
            OrderIntake viaAppender = f.limit(KRW_BTC, bob, OrderCommand.Side.BUY, "50000", "0.1");
            OrderIntake viaV48 = f.limit(KRW_BTC, bob, OrderCommand.Side.BUY, "50000", "0.2");
            long journalBefore = f.count("SELECT count(*) FROM command_journal");
            try (Connection owner = f.db.owner(); Connection legacy = f.db.app()) {
                owner.setAutoCommit(false);
                try (Statement st = owner.createStatement()) {
                    st.executeQuery("SELECT * FROM set_leader_protocol(2::smallint)");
                }
                int ownerPid = pid(owner);
                // V48 경로: 6인자 머리말은 잠그지 않고 판정하므로 커밋 전 전환을 보지 못하고 통과한다.
                legacy.setAutoCommit(false);
                long legacySeq = prologue6(legacy, lease, f.draft(viaV48));
                CompletableFuture<Integer> legacyRow = CompletableFuture.supplyAsync(() -> {
                    try {
                        return row(legacy, lease, f.draft(viaV48), legacySeq);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
                CompletableFuture<Throwable> append = CompletableFuture.supplyAsync(() -> {
                    try {
                        f.append(appender, viaAppender);
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                });
                assertTrue(waitsOn(f, "append_command_journal_row", ownerPid), "V48 행 함수가 전환을 기다리지 않았다");
                assertTrue(waitsOn(f, "append_command_prologue", ownerPid), "append 가 전환을 기다리지 않았다");
                owner.commit();

                assertEquals(0, legacyRow.get(30, TimeUnit.SECONDS), "전환 커밋 뒤 V48 경로가 행을 넣었다");
                legacy.rollback();
                Throwable fenced = append.get(30, TimeUnit.SECONDS);
                assertTrue(fenced instanceof DataAccessException, "append 가 거부되지 않았다: " + fenced);
                assertTrue(((DataAccessException) fenced).getMostSpecificCause().getMessage().contains("fencing 위반"),
                        fenced.toString());
            }
            assertFalse(lease.isLeader(), "fencing 거부 뒤 demoteUnlessStillLeader 가 강등하지 않았다");
            assertEquals(journalBefore, f.count("SELECT count(*) FROM command_journal"));
            assertEquals(0, f.count("SELECT count(*) FROM order_reservations"));
            assertEquals(2, f.protocol());
        }
    }

    /** (4) P2 에서 옛 진입점은 모두 55000 이고 리스 행과 이력을 바꾸지 않는다. 6인자 머리말의 취소는 된다. */
    @Test
    void legacyEntryPointsAreRefusedInP2() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("latch_legacy", 2)) {
            long carol = f.user("carol", new BigDecimal("100000"));
            try (Connection app = f.db.app()) {
                String before = leaseState(f);
                for (String sql : List.of(
                        "SELECT try_acquire_leader('old', interval '1 minute')",
                        "SELECT try_acquire_lease('matching-engine', 'old', interval '1 minute')",
                        "SELECT try_acquire_lease('matching-engine', 'old', interval '1 minute', 1::smallint)",
                        "SELECT publish_leader_epoch('dyn', 999)",
                        "SELECT publish_leader_epoch('dyn', 999, 1::smallint)")) {
                    SQLException e = assertThrows(SQLException.class, () -> exec(app, sql), sql);
                    assertEquals("55000", e.getSQLState(), sql + ": " + e.getMessage());
                }
                assertEquals(before, leaseState(f), "거부된 옛 진입점이 리스 행이나 이력을 바꿨다");

                LeaderLeaseService lease = f.lease();
                OrderIntake buy = f.limit(KRW_BTC, carol, OrderCommand.Side.BUY, "50000", "0.1");
                app.setAutoCommit(false);
                SQLException six = assertThrows(SQLException.class, () -> prologue6(app, lease, f.draft(buy)));
                assertEquals("55000", six.getSQLState(), six.getMessage());
                app.rollback();
                SQLException five = assertThrows(SQLException.class, () -> prologue5(app, lease, f.draft(buy), true));
                assertEquals("55000", five.getSQLState(), five.getMessage());
                app.rollback();
                // 취소 명령은 예약과 무관하므로 옛 머리말로도 채번된다.
                assertTrue(prologue5(app, lease, f.draft(buy), false) > 0);
                app.rollback();
                assertTrue(prologue6Cancel(app, lease) > 0);
                app.rollback();
            }
        }
    }

    /** (5) 쓰기가 멈추지 않으면 전환은 약 5초 뒤 55P03 으로 끝나고 아무것도 바꾸지 않는다. (6) 앱 로그인은 전환을 못 부른다. */
    @Test
    void switchGivesUpAfterFiveSecondsAndAppLoginCannotCallIt() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("latch_timeout", 1)) {
            try (Connection app = f.db.app()) {
                SQLException denied = assertThrows(SQLException.class,
                        () -> exec(app, "SELECT * FROM set_leader_protocol(2::smallint)"));
                assertEquals("42501", denied.getSQLState(), denied.getMessage());
            }
            LeaderLeaseService lease = f.lease();
            try (Connection holder = f.db.app()) {
                holder.setAutoCommit(false);
                exec(holder, "SELECT assert_and_lock_market_leader(1::smallint, '" + lease.requireLeader().holder()
                        + "', " + lease.requireLeader().epoch() + ")");
                long t0 = System.nanoTime();
                SQLException timeout = assertThrows(SQLException.class, () -> {
                    try (Connection owner = f.db.owner()) {
                        exec(owner, "SELECT * FROM set_leader_protocol(2::smallint)");
                    }
                });
                long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                assertEquals("55P03", timeout.getSQLState(), timeout.getMessage());
                assertTrue(waitedMs >= 4_500 && waitedMs < 15_000, "잠금 대기 상한이 5초가 아니다: " + waitedMs);
                holder.rollback();
            }
            assertEquals(1, f.protocol());
            assertTrue(lease.renew(), "실패한 전환이 리스를 건드렸다");
        }
    }

    /** (7) 퇴역 순서는 matching-p0 < matching-p1 < matching-engine 이고 다음 획득은 마지막 퇴역 + 1 이다. 2->1 도 같다. */
    @Test
    void retirementOrderAndNextAcquisitionForBothDirections() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("latch_retire", 1)) {
            LeaderLeaseService first = f.lease();
            long p1Epoch = first.requireLeader().epoch();
            first.close();
            f.db.expire("matching-engine");

            List<String[]> up = f.switchProtocol(2, false);
            assertRetired(f, up, 2, p1Epoch);
            long nextUp = f.lease().requireLeader().epoch();
            assertEquals(Long.parseLong(up.get(2)[1]) + 1, nextUp, "전환 뒤 첫 획득이 마지막 퇴역 + 1 이 아니다");

            SQLException needsConfirm = assertThrows(SQLException.class, () -> {
                try (Connection owner = f.db.owner()) {
                    exec(owner, "SELECT * FROM set_leader_protocol(1::smallint)");
                }
            });
            assertEquals("55000", needsConfirm.getSQLState());
            List<String[]> down = f.switchProtocol(1, true);
            assertRetired(f, down, 1, nextUp);
            f.db.expire("matching-engine");
            try (Connection app = f.db.app()) {
                // P1 로 내린 뒤에는 S2 바이너리의 3인자 획득이 다시 된다.
                assertEquals(String.valueOf(Long.parseLong(down.get(2)[1]) + 1), PartitionLeaseDb.queryString(app,
                        "SELECT try_acquire_lease('matching-engine', 's2-node', interval '1 minute')"));
            }
        }
    }

    private static void assertRetired(ReservationFixture f, List<String[]> retired, int protocol, long previous) {
        assertEquals(List.of("matching-p0", "matching-p1", "matching-engine"),
                retired.stream().map(r -> r[0]).toList(), "퇴역 순서");
        long p0 = Long.parseLong(retired.get(0)[1]);
        long p1 = Long.parseLong(retired.get(1)[1]);
        long engine = Long.parseLong(retired.get(2)[1]);
        assertTrue(previous < p0 && p0 < p1 && p1 < engine, "퇴역 epoch 가 단조가 아니다");
        long floor = f.count("SELECT epoch_floor FROM leader_protocol_history ORDER BY id DESC LIMIT 1");
        assertTrue(floor < p0, "epoch_floor 가 퇴역 epoch 보다 작지 않다");
        assertEquals(3, f.count("SELECT count(*) FROM lease_acquisitions WHERE origin = 'RETIRE' AND protocol = "
                + protocol + " AND epoch IN (" + p0 + "," + p1 + "," + engine + ")"));
        assertEquals(protocol, f.protocol());
    }

    private static String leaseState(ReservationFixture f) {
        return f.owner.sql("""
                SELECT (SELECT string_agg(resource || ':' || holder || ':' || epoch, ',' ORDER BY resource)
                          FROM leader_lease) || '|' || (SELECT count(*) FROM lease_acquisitions)
                """).query(String.class).single();
    }

    /** 다른 세션이 그 문장을 실행하며 blocker 가 쥔 잠금을 기다리는지 최대 10초 본다. */
    static boolean waitsOn(ReservationFixture f, String query, int blocker) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            long n = f.owner.sql("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock' AND query LIKE ? AND ? = ANY (pg_blocking_pids(pid))
                            """)
                    .params("%" + query + "%", blocker)
                    .query(Long.class).single();
            if (n > 0) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    static int pid(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet ignored = st.executeQuery(sql)) {
            // 결과는 보지 않는다
        }
    }

    static long prologue10(Connection c, LeaderLeaseService lease, CommandDraft d, ReserveCheck check)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(PROLOGUE_10)) {
            ps.setString(1, lease.requireLeader().holder());
            ps.setLong(2, lease.requireLeader().epoch());
            ps.setBoolean(3, true);
            ps.setLong(4, d.userId());
            ps.setString(5, d.clientOrderId());
            ps.setInt(6, d.marketId());
            ps.setLong(7, d.assignedOrderId());
            ps.setString(8, d.payloadJson());
            ps.setShort(9, check.assetId());
            ps.setBigDecimal(10, check.amount());
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    static long prologue6(Connection c, LeaderLeaseService lease, CommandDraft d) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(PROLOGUE_6)) {
            ps.setString(1, lease.requireLeader().holder());
            ps.setLong(2, lease.requireLeader().epoch());
            ps.setBoolean(3, true);
            ps.setLong(4, d.userId());
            ps.setString(5, d.clientOrderId());
            ps.setInt(6, d.marketId());
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static long prologue6Cancel(Connection c, LeaderLeaseService lease) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(PROLOGUE_6)) {
            ps.setString(1, lease.requireLeader().holder());
            ps.setLong(2, lease.requireLeader().epoch());
            ps.setBoolean(3, false);
            ps.setLong(4, 1L);
            ps.setString(5, null);
            ps.setInt(6, KRW_BTC);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static long prologue5(Connection c, LeaderLeaseService lease, CommandDraft d, boolean isNew)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(PROLOGUE_5)) {
            ps.setString(1, lease.requireLeader().holder());
            ps.setLong(2, lease.requireLeader().epoch());
            ps.setBoolean(3, isNew);
            ps.setLong(4, d.userId());
            ps.setString(5, isNew ? d.clientOrderId() : null);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    static int row(Connection c, LeaderLeaseService lease, CommandDraft d, long seq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ROW)) {
            ps.setLong(1, seq);
            ps.setInt(2, d.marketId());
            ps.setString(3, d.type().name());
            ps.setLong(4, d.userId());
            ps.setString(5, d.clientOrderId());
            ps.setLong(6, d.assignedOrderId());
            ps.setLong(7, d.engineTs());
            ps.setLong(8, d.tieBreak());
            ps.setString(9, d.payloadJson());
            ps.setLong(10, JournalChecksum.of(seq, d));
            ps.setString(11, lease.requireLeader().holder());
            ps.setLong(12, lease.requireLeader().epoch());
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}

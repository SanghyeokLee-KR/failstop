package kr.exchange.engine.lease;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static kr.exchange.engine.lease.PartitionLeaseDb.queryLong;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * epoch 배정(S2 설계 4-2, DB 설계 §3.4). 새 epoch 는 max(모든 리스 행, 획득 이력) + 1 이고 leader_protocol 행이 배정을 한 줄로
 * 세운다. P1 단언은 파티션 자원을 획득한 적이 없는 DB 에서만 하고, P3 흉내는 다른 DB 에서 한다(S2 설계 6-1 격리 규칙).
 */
class EpochAllocationTest {

    private static final String NOT_IN_PREREQUISITE_STATE = "55000";
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    /** V47 의 epoch 41 위에서 V48 을 올리면 첫 획득이 42 이고, 두 획득 함수를 번갈아도 1 씩 오른다. */
    @Test
    void p1AcquisitionsContinueFromExistingEpochByOne() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.createAt("epoch_p1", "47");
        db.seedLease("matching-engine", "old-node", 41, 3600);
        db.migrate();
        assertEquals("matching-engine:old-node:41,matching-p0::0,matching-p1::0", leaseRows(db));
        assertEquals(0, db.ownerLong("SELECT count(*) FROM lease_acquisitions"));
        assertEquals("1||41", db.ownerString("""
                SELECT protocol || '|' || COALESCE(prev_protocol::text, '') || '|' || epoch_floor
                FROM leader_protocol_history
                """));

        try (Connection c = db.owner()) {
            assertNull(queryString(c, "SELECT try_acquire_lease('matching-engine', 'node-x', interval '60 seconds')"),
                    "만료 전 획득이 성공했다");
            assertEquals(0, queryLong(c, "SELECT count(*) FROM lease_acquisitions"), "실패한 획득이 이력을 남겼다");

            db.expire("matching-engine");
            assertEquals(42, legacyAcquire(c, "node-a"));
            db.expire("matching-engine");
            assertEquals(43, acquire(c, "matching-engine", "node-b"));
            db.expire("matching-engine");
            assertEquals(44, legacyAcquire(c, "node-c"));
            db.expire("matching-engine");
            assertEquals(45, acquire(c, "matching-engine", "node-d"));
        }
        assertEquals("42:matching-engine:node-a:1:ACQUIRE,43:matching-engine:node-b:1:ACQUIRE,"
                + "44:matching-engine:node-c:1:ACQUIRE,45:matching-engine:node-d:1:ACQUIRE", acquisitions(db));
        assertEquals("matching-engine:node-d:45,matching-p0::0,matching-p1::0", leaseRows(db));
    }

    /** 경합에서 진 획득은 NULL 이고 이력을 남기지 않는다. 진 쪽은 이긴 쪽 커밋 뒤 만료 조건을 다시 보고 빠진다. */
    @Test
    void losingConcurrentAcquisitionReturnsNullWithoutRecord() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("epoch_race");
        db.seedLease("matching-engine", "old-node", 41, -1);
        try (Connection winner = db.owner(); Connection loser = db.owner(); Connection observer = db.owner()) {
            winner.setAutoCommit(false);
            assertEquals(42, acquire(winner, "matching-engine", "node-w"));
            int loserPid = pid(loser);
            Future<String> lost = POOL.submit(() ->
                    queryString(loser, "SELECT try_acquire_lease('matching-engine', 'node-l', interval '60 seconds')"));
            awaitBlockedBy(observer, loserPid, pid(winner));
            winner.commit();
            assertNull(lost.get(5, TimeUnit.SECONDS), "진 획득이 epoch 을 받았다");
        }
        assertEquals("42:matching-engine:node-w:1:ACQUIRE", acquisitions(db));
    }

    /** 획득이 롤백되면 배정도 사라져 다음 획득이 같은 번호를 받는다. 시퀀스와 달리 결번이 없다. */
    @Test
    void rolledBackAcquisitionLeavesNoGap() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("epoch_rollback");
        db.seedLease("matching-engine", "old-node", 41, -1);
        try (Connection c = db.owner()) {
            c.setAutoCommit(false);
            assertEquals(42, acquire(c, "matching-engine", "node-r"));
            c.rollback();
            c.setAutoCommit(true);

            // 이력 INSERT 를 한 번 실패시켜 획득 문장 전체를 되감는다.
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SEQUENCE test_fail_once");
                st.execute("""
                        CREATE FUNCTION test_fail_first_acquisition() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF nextval('test_fail_once') = 1 THEN
                                RAISE EXCEPTION '주입한 획득 이력 실패';
                            END IF;
                            RETURN NEW;
                        END $$
                        """);
                st.execute("CREATE TRIGGER test_fail_acquisition BEFORE INSERT ON lease_acquisitions "
                        + "FOR EACH ROW EXECUTE FUNCTION test_fail_first_acquisition()");
            }
            SQLException injected = assertThrows(SQLException.class, () -> acquire(c, "matching-engine", "node-f"));
            assertTrue(injected.getMessage().contains("주입한 획득 이력 실패"), injected.getMessage());
            assertEquals("matching-engine:old-node:41,matching-p0::0,matching-p1::0", leaseRows(db),
                    "실패한 획득이 리스 행을 바꿨다");

            assertEquals(42, acquire(c, "matching-engine", "node-g"));
        }
        assertEquals("42:matching-engine:node-g:1:ACQUIRE", acquisitions(db));
    }

    /** 게시 X 뒤 PostgreSQL 획득은 X + 1 이다. 같은 보유자의 재게시는 이력을 늘리지 않고 작은 epoch 게시는 거부된다. */
    @Test
    void publishRecordsOnceAndNextAcquisitionContinuesAboveIt() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("epoch_publish");
        db.seedLease("matching-engine", "old-node", 41, 3600);
        try (Connection c = db.owner()) {
            assertEquals("50", queryString(c, "SELECT publish_leader_epoch('node-d', 50)"));
            assertEquals("50", queryString(c, "SELECT publish_leader_epoch('node-d', 50)"));
            assertNull(queryString(c, "SELECT publish_leader_epoch('node-e', 49)"));
            assertNull(queryString(c, "SELECT publish_leader_epoch('node-e', 50)"));
            assertEquals("matching-engine:node-d:50,matching-p0::0,matching-p1::0", leaseRows(db));
            assertEquals("true", queryString(c, """
                    SELECT (lease_until > clock_timestamp() + interval '99 years')::text
                    FROM leader_lease WHERE resource = 'matching-engine'
                    """), "게시가 lease_until 을 100년 뒤로 밀지 않았다");

            db.exec("SELECT expire_leader_lease_now()");
            assertEquals(51, acquire(c, "matching-engine", "node-f"));
        }
        assertEquals("50:matching-engine:node-d:1:PUBLISH,51:matching-engine:node-f:1:ACQUIRE", acquisitions(db));
    }

    /** P1 에서는 파티션 자원을 획득하지 못한다. 행은 그대로다. */
    @Test
    void p1RefusesPartitionResources() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("epoch_p1_refuse");
        try (Connection c = db.owner()) {
            for (String resource : new String[] {"matching-p0", "matching-p1"}) {
                SQLException refused = assertThrows(SQLException.class, () -> acquire(c, resource, "node-x"));
                assertEquals(NOT_IN_PREREQUISITE_STATE, refused.getSQLState(), refused.getMessage());
            }
        }
        assertEquals("matching-engine::0,matching-p0::0,matching-p1::0", leaseRows(db));
        assertEquals(0, db.ownerLong("SELECT count(*) FROM lease_acquisitions"));
    }

    /**
     * P2 에서는 2인자 획득, S2 바이너리의 3인자 획득(ADR 0034 S3, V50 에서 P1 전용), 파티션 자원을 거부하고 P2 를 아는
     * 4인자 matching-engine 획득만 받는다. 모드는 전환 함수로 올린다. 전환이 리스 행 셋을 1·2·3 으로 퇴역시키므로 첫 획득은
     * 마지막 퇴역 + 1 인 4 다.
     */
    @Test
    void p2RefusesLegacyAcquisitionAndPartitionResources() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("epoch_p2");
        assertEquals(3, db.switchProtocol(2, false));
        try (Connection c = db.owner()) {
            SQLException legacy = assertThrows(SQLException.class, () -> legacyAcquire(c, "old-binary"));
            assertEquals(NOT_IN_PREREQUISITE_STATE, legacy.getSQLState(), legacy.getMessage());
            SQLException s2Binary = assertThrows(SQLException.class, () -> queryString(c,
                    "SELECT try_acquire_lease('matching-engine', 's2-binary', interval '60 seconds')"));
            assertEquals(NOT_IN_PREREQUISITE_STATE, s2Binary.getSQLState(), s2Binary.getMessage());
            SQLException partition = assertThrows(SQLException.class, () -> acquire(c, "matching-p0", "node-x"));
            assertEquals(NOT_IN_PREREQUISITE_STATE, partition.getSQLState(), partition.getMessage());
            assertEquals(4, acquire(c, "matching-engine", "node-a", 2));
        }
        assertEquals("1:matching-p0::2:RETIRE,2:matching-p1::2:RETIRE,3:matching-engine::2:RETIRE,"
                + "4:matching-engine:node-a:2:ACQUIRE", acquisitions(db));
    }

    /**
     * P3 흉내. 두 파티션의 획득을 섞어도 epoch 는 유일하고 배정 순서대로 1 씩 커진다. 2인자와 matching-engine 획득은 거부된다.
     * 이 DB 를 P1 로 되돌리면 다음 matching-engine epoch 는 파티션 epoch 위다(S2 설계 4-2 한계).
     */
    @Test
    void p3PartitionEpochsAreUniqueAcrossResources() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("epoch_p3");
        db.seedLease("matching-engine", "old-node", 41, 3600);
        db.setProtocol(3);
        try (Connection c = db.owner()) {
            assertEquals(42, acquire(c, "matching-p0", "node-p0"));
            assertEquals(43, acquire(c, "matching-p1", "node-p1"));
            db.expire("matching-p0");
            assertEquals(44, acquire(c, "matching-p0", "node-p0b"));
            db.expire("matching-p1");
            assertEquals(45, acquire(c, "matching-p1", "node-p1b"));

            db.expire("matching-engine");
            SQLException legacy = assertThrows(SQLException.class, () -> legacyAcquire(c, "old-binary"));
            assertEquals(NOT_IN_PREREQUISITE_STATE, legacy.getSQLState(), legacy.getMessage());
            SQLException engine = assertThrows(SQLException.class, () -> acquire(c, "matching-engine", "node-e"));
            assertEquals(NOT_IN_PREREQUISITE_STATE, engine.getSQLState(), engine.getMessage());

            db.setProtocol(1);
            assertEquals(46, legacyAcquire(c, "node-back"), "P1 로 되돌린 뒤의 epoch 가 파티션 epoch 아래로 내려갔다");
        }
        assertEquals("42:matching-p0:node-p0:3:ACQUIRE,43:matching-p1:node-p1:3:ACQUIRE,"
                + "44:matching-p0:node-p0b:3:ACQUIRE,45:matching-p1:node-p1b:3:ACQUIRE,"
                + "46:matching-engine:node-back:1:ACQUIRE", acquisitions(db));
    }

    /**
     * 동시 배정. 앞 획득이 트랜잭션을 연 동안 뒤 획득은 leader_protocol 행에서 기다린다. 앞이 커밋하면 뒤는 그 값 + 1 을,
     * 롤백하면 앞이 받았던 번호를 받는다(S2 설계 10절 R7).
     */
    @Test
    void concurrentAllocationsQueueOnProtocolRow() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("epoch_concurrent");
        db.seedLease("matching-engine", "old-node", 41, 3600);
        db.setProtocol(3);

        try (Connection a = db.owner(); Connection b = db.owner(); Connection observer = db.owner()) {
            a.setAutoCommit(false);
            long first = acquire(a, "matching-p0", "node-a");
            assertEquals(42, first);
            int bPid = pid(b);
            Future<Long> second = POOL.submit(() -> acquire(b, "matching-p1", "node-b"));
            awaitQueuedOnProtocolRow(observer, bPid, pid(a));
            a.commit();
            assertEquals(first + 1, second.get(5, TimeUnit.SECONDS));
        }

        db.expire("matching-p0");
        db.expire("matching-p1");
        try (Connection a = db.owner(); Connection b = db.owner(); Connection observer = db.owner()) {
            a.setAutoCommit(false);
            long held = acquire(a, "matching-p0", "node-c");
            assertEquals(44, held);
            int bPid = pid(b);
            Future<Long> second = POOL.submit(() -> acquire(b, "matching-p1", "node-d"));
            awaitQueuedOnProtocolRow(observer, bPid, pid(a));
            a.rollback();
            assertEquals(held, second.get(5, TimeUnit.SECONDS), "앞 획득이 롤백됐는데 번호를 건너뛰었다");
        }
        assertEquals("42:matching-p0:node-a:3:ACQUIRE,43:matching-p1:node-b:3:ACQUIRE,"
                + "44:matching-p1:node-d:3:ACQUIRE", acquisitions(db));
    }

    /**
     * 파티션 자원까지 아는 바이너리(최고 모드 3)의 획득. V50 뒤 3인자 판은 P1 전용이라(ADR 0034 S3) P3 흉내 시험은 최고 모드를
     * 넘기는 4인자 판을 쓴다. 자원·모드 판정은 V48 과 같다.
     */
    static long acquire(Connection c, String resource, String holder) throws SQLException {
        return acquire(c, resource, holder, 3);
    }

    static long acquire(Connection c, String resource, String holder, int maxProtocol) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT try_acquire_lease(?, ?, interval '60 seconds', ?::smallint)")) {
            ps.setString(1, resource);
            ps.setString(2, holder);
            ps.setInt(3, maxProtocol);
            return epochOrFail(ps);
        }
    }

    static long legacyAcquire(Connection c, String holder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT try_acquire_leader(?, interval '60 seconds')")) {
            ps.setString(1, holder);
            return epochOrFail(ps);
        }
    }

    private static long epochOrFail(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            long epoch = rs.getLong(1);
            assertFalse(rs.wasNull(), "획득이 NULL 을 돌려줬다");
            return epoch;
        }
    }

    static String leaseRows(PartitionLeaseDb db) throws SQLException {
        return db.ownerString("""
                SELECT string_agg(resource || ':' || holder || ':' || epoch, ',' ORDER BY resource) FROM leader_lease
                """);
    }

    static String acquisitions(PartitionLeaseDb db) throws SQLException {
        return db.ownerString("""
                SELECT COALESCE(string_agg(epoch || ':' || resource || ':' || holder || ':' || protocol || ':' || origin,
                                           ',' ORDER BY epoch), '')
                FROM lease_acquisitions
                """);
    }

    static int pid(Connection c) throws SQLException {
        return Integer.parseInt(queryString(c, "SELECT pg_backend_pid()"));
    }

    static void awaitBlockedBy(Connection observer, int waiterPid, int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (PreparedStatement ps = observer.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")) {
            ps.setInt(1, blockerPid);
            ps.setInt(2, waiterPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        fail("pid " + waiterPid + " 가 pid " + blockerPid + " 에 막히지 않았다");
    }

    /** 뒤 획득이 앞 트랜잭션 번호를 기다리고, 그 대기가 leader_protocol 행의 튜플 잠금에서 줄을 선 것인지 본다. */
    private static void awaitQueuedOnProtocolRow(Connection observer, int waiterPid, int blockerPid) throws Exception {
        awaitBlockedBy(observer, waiterPid, blockerPid);
        assertEquals("Lock|transactionid", queryString(observer,
                "SELECT wait_event_type || '|' || wait_event FROM pg_stat_activity WHERE pid = " + waiterPid));
        assertEquals(1, queryLong(observer, """
                SELECT count(*) FROM pg_locks
                WHERE pid = %d AND locktype = 'tuple' AND relation = 'leader_protocol'::regclass AND granted
                """.formatted(waiterPid)), "뒤 획득이 leader_protocol 행에서 줄을 서지 않았다");
    }
}

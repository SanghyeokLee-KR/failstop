package kr.exchange.engine.lease;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 획득이 옛 리더의 KEY SHARE 를 TTL 보다 오래 기다려도 살아 있는 리스를 받는다(ADR 0034 S3 관문 X3, V52).
 *
 * 멈춘 옛 리더(docker pause)가 리스 행 KEY SHARE 를 쥔 트랜잭션을 남기면 승격 획득은 그 트랜잭션이 끝날 때까지 기다린다.
 * V51 까지의 획득은 lease_until 을 기다리기 전 시각에서 계산해, 기다림이 TTL 보다 길면 이미 만료된 리스를 돌려줬다. 새 리더는
 * 첫 쓰기에서 '리스 만료' fencing 거부를 받고 종신 강등해 클러스터에 리더가 없어졌다. P2 에서는 접수가 잔고 행을 잡아 멈춘
 * 리더의 트랜잭션끼리 대기 사슬을 만들고 그 시간이 idle_in_transaction_session_timeout 의 배수로 늘어난다. 획득 경로 셋
 * (4인자, S2 바이너리의 3인자, V1 이래 2인자)이 모두 잠금을 얻은 뒤의 시각에서 TTL 을 센다.
 */
class LeaseAcquireAfterWaitTest {

    private static final int TTL_SECONDS = 2;
    private static final long HOLD_MILLIS = 3_000;

    @Test
    void acquisitionThatWaitedLongerThanTtlStillReturnsALiveLease() throws Exception {
        List<String> calls = List.of(
                "SELECT try_acquire_lease('matching-engine', 'new-node', interval '2 seconds', 2::smallint)",
                "SELECT try_acquire_lease('matching-engine', 'new-node', interval '2 seconds')",
                "SELECT try_acquire_leader('new-node', interval '2 seconds')");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            for (String call : calls) {
                PartitionLeaseDb db = PartitionLeaseDb.create("acq_wait");
                db.seedLease("matching-engine", "old-node", 5, 60);
                try (Connection zombie = db.owner(); Connection owner = db.owner(); Connection acquirer = db.owner()) {
                    // 멈춘 옛 리더의 열린 쓰기 트랜잭션. 판정 함수가 리스 행에 KEY SHARE 를 건 채 커밋하지 않는다.
                    zombie.setAutoCommit(false);
                    assertEquals("5", queryString(zombie, "SELECT assert_and_lock_leader('old-node', 5)"));
                    // TTL 이 지난 것처럼 만료를 과거로 민다. lease_until 만 바꾸는 UPDATE 는 KEY SHARE 와 충돌하지 않는다.
                    try (Statement st = owner.createStatement()) {
                        st.execute("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second'"
                                + " WHERE resource = 'matching-engine'");
                    }
                    long started = System.nanoTime();
                    Future<Long> acquired = pool.submit(() -> {
                        try (Statement st = acquirer.createStatement(); ResultSet rs = st.executeQuery(call)) {
                            assertTrue(rs.next());
                            return rs.getLong(1);
                        }
                    });
                    Thread.sleep(HOLD_MILLIS);
                    assertFalse(acquired.isDone(), "획득이 옛 리더의 KEY SHARE 를 기다리지 않았다: " + call);
                    zombie.rollback();
                    long epoch = acquired.get(10, TimeUnit.SECONDS);
                    long waitedMillis = (System.nanoTime() - started) / 1_000_000;
                    assertTrue(waitedMillis > TTL_SECONDS * 1000L, "기다림이 TTL 보다 짧아 시험이 공허하다: " + waitedMillis);

                    assertEquals(6, epoch, call);
                    assertEquals("true", queryString(owner, "SELECT (lease_until > clock_timestamp())::text FROM leader_lease"
                            + " WHERE resource = 'matching-engine'"), "기다린 획득이 이미 만료된 리스를 돌려줬다: " + call);
                    // 첫 쓰기와 첫 갱신이 그 리스로 통과한다.
                    assertEquals("6", queryString(owner, "SELECT assert_and_lock_leader('new-node', 6)"), call);
                    assertEquals("true", queryString(owner, "SELECT renew_lease('matching-engine', 'new-node', 6,"
                            + " interval '2 seconds')::text"), call);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void acquisitionStillRefusesALiveLeaseAndLeavesItUntouched() throws SQLException {
        PartitionLeaseDb db = PartitionLeaseDb.create("acq_live");
        db.seedLease("matching-engine", "old-node", 5, 60);
        try (Connection c = db.owner()) {
            assertEquals(null, queryString(c,
                    "SELECT try_acquire_lease('matching-engine', 'new-node', interval '2 seconds', 2::smallint)"));
            assertEquals("old-node|5", queryString(c,
                    "SELECT holder || '|' || epoch FROM leader_lease WHERE resource = 'matching-engine'"));
        }
    }
}

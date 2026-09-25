package kr.exchange.engine;

import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.TransientDbErrors;
import kr.exchange.snapshot.SnapshotStore;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.S7Cutover.ME;
import static kr.exchange.engine.S7Cutover.P0;
import static kr.exchange.engine.S7Cutover.P1;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryLong;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4-2 전환 판(ADR 0034 S7, DB 설계 §5.4-e 5, 하네스 검증설계 §4-4 ⑦, S7 설계 4-7·6-2). 모드 전환은 모든 리스 행을 퇴역시켜
 * 이전 모드의 쓰기를 막는다. 정지 창을 지키지 않아도 막히는지 옛 모드 노드를 멈추지 않은 채 본다.
 *
 * (1) P2 리더를 드레인만 하고 2->3. (2) 두 파티션 리더를 드레인만 하고 3->2. 두 경우 모두 그 노드의 접수(머리말과 저널 행 함수),
 * 전환 전에 저널에 들어가 정산을 기다리던 취소의 체결 정산(SettlementService), 스냅샷(SnapshotStore.persist)이 모두 fencing
 * 예외나 0행으로 끝나고, 그 노드는 옛 계획의 자원을 다시 얻지 못한다(55000). (3) 전환 전에 시작한 트랜잭션이 전환 뒤 옛
 * (holder, epoch)로 쓰기 판정을 부르면 0행이나 예외이고 커밋할 행이 없다.
 *
 * 매 경우 그 전환 이력 행의 구간 경계 증분 표본(harness/sql/segment-old-rows.sql)이 전환 직후와 같고, C22'·C30 이 0 이다. 사후
 * 감사가 세지 못하는 두 경우(전환을 가로지른 트랜잭션, 옛 구간 epoch 의 이벤트·체결)를 라이브 차단이 막는다는 증거다.
 */
class CutoverStaleWriteTest {

    @Test
    void p2LeaderLeftRunningAcrossTwoToThreeCannotWriteAnything() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("s7_stale_23", 2)) {
            S7Cutover.latch(f);
            long alice = f.user("alice", new BigDecimal("100000000"));
            PartitionNode a = new PartitionNode("app-1", f, new PartitionNode.Options().boot(2));
            try {
                a.start();
                awaitAccepting(a, ME);
                StuckCancel stuck = stuckCancel(f, a, KRW_BTC, alice);
                LeaderTerm old = a.lease(ME).currentTerm().orElseThrow();
                assertEquals(0, S7Cutover.unsettledNew(f), "드레인되지 않았다(시험 전제)");

                try (Connection straddling = f.db.app()) {
                    straddling.setAutoCommit(false);
                    queryLong(straddling, "SELECT 1");            // (3) 전환 전에 시작한 트랜잭션
                    S7Cutover.switchTo(f, 3);
                    long h23 = S7Cutover.lastHistoryId(f);
                    Map<Long, Long> atCutover = S7Cutover.oldRows(f);
                    long commands = commandRows(f);

                    // (3) 옛 (holder, epoch)로 저널 행 함수는 0행, 머리말은 fencing 예외다. 커밋할 행이 없다.
                    assertEquals(0, journalRow(straddling, KRW_BTC, old), "전환을 가로지른 트랜잭션이 옛 epoch 로 썼다");
                    assertFencing(assertThrows(SQLException.class, () -> prologue(straddling, KRW_BTC, old)));
                    straddling.rollback();

                    // (1) 체결 정산: 전환 전에 저널에 든 취소의 정산이 옛 기간으로 돌면 거부된다.
                    stuck.release();
                    stuck.assertNotSettled(f);

                    // (1) 접수: 옛 노드의 append 는 fencing 으로 거부되고, 그 사이 강등했으면 게이트가 저널 전에 거부한다.
                    RuntimeException refused = assertThrows(RuntimeException.class,
                            () -> a.submit(f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "1000", "1")));
                    assertTrue(isStaleWrite(refused), "옛 노드 접수가 fencing·게이트 거부가 아니다: " + refused);
                    assertEquals(commands, commandRows(f), "옛 노드 접수가 저널에 닿았다");

                    // (1) 스냅샷: 옛 기간 식별자로는 한 행도 쓰지 못한다.
                    assertSnapshotRefused(f, old, KRW_BTC);

                    // 옛 노드는 강등되고 옛 계획의 자원(matching-engine)을 P3 에서 얻지 못한다.
                    Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !a.lease(ME).isLeader());
                    assertAcquireRefused(f, ME, old.holder());
                    Thread.sleep(a.options.ttl.multipliedBy(2).toMillis());
                    assertFalse(a.lease(ME).isLeader(), "P2 계획 노드가 P3 에서 matching-engine 을 다시 얻었다");

                    assertEquals(atCutover.get(h23), S7Cutover.oldRows(f).get(h23),
                            "2->3 뒤 옛 구간 epoch 행이 늘었다(구간 경계 증분)");
                    assertEquals(0L, f.invariant(InvariantCheck.C22_FENCING_MONOTONIC));
                    assertEquals(0L, f.invariant(InvariantCheck.C30_FENCING_RESOURCE_MATCH));
                }
            } finally {
                a.close();
            }
        }
    }

    @Test
    void partitionLeadersLeftRunningAcrossThreeToTwoCannotWriteAnything() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("s7_stale_32", 2)) {
            S7Cutover.latch(f);
            S7Cutover.switchTo(f, 3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            PartitionNode a = new PartitionNode("app-1", f, new PartitionNode.Options().preferred(P0));
            PartitionNode b = new PartitionNode("app-2", f, new PartitionNode.Options().preferred(P1));
            try {
                a.start();
                b.start();
                awaitAccepting(a, P0);
                awaitAccepting(b, P1);
                StuckCancel stuck0 = stuckCancel(f, a, KRW_BTC, alice);
                StuckCancel stuck1 = stuckCancel(f, b, KRW_ETH, alice);
                LeaderTerm old0 = a.lease(P0).currentTerm().orElseThrow();
                LeaderTerm old1 = b.lease(P1).currentTerm().orElseThrow();
                assertEquals(0, S7Cutover.unsettledNew(f), "드레인되지 않았다(시험 전제)");

                try (Connection straddling = f.db.app()) {
                    straddling.setAutoCommit(false);
                    queryLong(straddling, "SELECT 1");
                    S7Cutover.switchTo(f, 2);
                    long h32 = S7Cutover.lastHistoryId(f);
                    Map<Long, Long> atCutover = S7Cutover.oldRows(f);
                    long commands = commandRows(f);

                    assertEquals(0, journalRow(straddling, KRW_BTC, old0), "전환을 가로지른 트랜잭션이 옛 p0 epoch 로 썼다");
                    assertEquals(0, journalRow(straddling, KRW_ETH, old1), "전환을 가로지른 트랜잭션이 옛 p1 epoch 로 썼다");
                    assertFencing(assertThrows(SQLException.class, () -> prologue(straddling, KRW_ETH, old1)));
                    straddling.rollback();

                    stuck0.release();
                    stuck1.release();
                    stuck0.assertNotSettled(f);
                    stuck1.assertNotSettled(f);

                    for (Object[] node : new Object[][] {{a, KRW_BTC}, {b, KRW_ETH}}) {
                        PartitionNode n = (PartitionNode) node[0];
                        int market = (Integer) node[1];
                        RuntimeException refused = assertThrows(RuntimeException.class,
                                () -> n.submit(f.limit(market, alice, OrderCommand.Side.BUY, "1000", "1")));
                        assertTrue(isStaleWrite(refused), n.name + " 옛 파티션 접수가 fencing·게이트 거부가 아니다: " + refused);
                    }
                    assertEquals(commands, commandRows(f), "옛 파티션 리더의 접수가 저널에 닿았다");

                    assertSnapshotRefused(f, old0, KRW_BTC);
                    assertSnapshotRefused(f, old1, KRW_ETH);

                    Awaitility.await().atMost(Duration.ofSeconds(20))
                            .until(() -> !a.lease(P0).isLeader() && !b.lease(P1).isLeader());
                    assertAcquireRefused(f, P0, old0.holder());
                    assertAcquireRefused(f, P1, old1.holder());

                    assertEquals(atCutover.get(h32), S7Cutover.oldRows(f).get(h32),
                            "3->2 뒤 옛 구간 epoch 행이 늘었다(구간 경계 증분)");
                    assertEquals(0L, f.invariant(InvariantCheck.C22_FENCING_MONOTONIC));
                    assertEquals(0L, f.invariant(InvariantCheck.C30_FENCING_RESOURCE_MATCH));
                }
            } finally {
                a.close();
                b.close();
            }
        }
    }

    /** 정산 sink 앞에서 멈춘 취소. 저널 append 는 커밋됐고 정산은 release 뒤에 옛 기간으로 돈다. */
    private record StuckCancel(long orderId, long cancelSeq, int market, CountDownLatch gate, AtomicBoolean fired) {

        void release() {
            gate.countDown();
        }

        /** 정산이 거부돼 취소 이벤트가 없고 주문이 열린 채다. */
        void assertNotSettled(ReservationFixture f) {
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(fired::get);
            // 거부된 정산은 트랜잭션째 롤백된다. 잠시 기다려도 이벤트가 생기지 않는다.
            Awaitility.await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> true);
            assertEquals(0, f.count("SELECT count(*) FROM event_journal WHERE source_cmd_seq = " + cancelSeq),
                    "옛 기간의 취소 정산이 이벤트를 남겼다");
            assertEquals("OPEN", f.owner.sql("SELECT status FROM orders WHERE id = ?").param(orderId)
                    .query(String.class).single(), "옛 기간의 취소 정산이 주문을 바꿨다");
        }
    }

    /**
     * 그 마켓에 열린 매수 하나를 두고, 그 주문의 취소를 저널에 넣되 정산 sink 앞에서 멈춘다. 주문 생성 명령은 정산됐으므로
     * 전환 선행 조건(unsettled_new)은 막지 않는다.
     */
    private static StuckCancel stuckCancel(ReservationFixture f, PartitionNode node, int market, long user) {
        OrderIntake order = f.limit(market, user, OrderCommand.Side.BUY, "1000", "1");
        node.submit(order);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> f.count(
                "SELECT count(*) FROM orders WHERE status = 'OPEN' AND id = " + order.assignedOrderId()) == 1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        PartitionNode.SinkFault previous = node.sinkFault;
        node.sinkFault = (marketId, term, events) -> {
            if (marketId == market && events.stream().anyMatch(e -> e.type() == EngineEvent.EventType.CANCELED)) {
                blocked.countDown();
                try {
                    // 강등이 라이터를 멈추며 인터럽트하면 여기서 끝난다. 어느 쪽이든 이 배치는 정산되지 않거나 옛 기간으로 돈다.
                    gate.await(60, TimeUnit.SECONDS);
                } finally {
                    fired.set(true);
                }
            }
            previous.before(marketId, term, events);
        };
        SubmitResult cancel = node.submit(f.cancel(market, user, order.assignedOrderId()));
        try {
            assertTrue(blocked.await(20, TimeUnit.SECONDS), "취소 정산이 sink 앞에 오지 않았다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return new StuckCancel(order.assignedOrderId(), cancel.seq(), market, gate, fired);
    }

    private static long commandRows(ReservationFixture f) {
        return f.count("SELECT count(*) FROM command_journal");
    }

    /** 저널 행 함수를 옛 기간으로 부른다(대역 seq 는 아무 값이나 된다. 0행이면 넣지 않는다). */
    private static long journalRow(Connection c, int market, LeaderTerm old) throws SQLException {
        return queryLong(c, """
                SELECT append_command_journal_row(%d, %d::smallint, 'CANCEL', 1, NULL, 1, 0, 0, '{}'::jsonb, 0, '%s', %d)
                """.formatted((long) market * 1_000_000_000_000L + 999_999L, market, old.holder(), old.epoch()));
    }

    /** 10인자 머리말을 옛 기간으로 부른다. 첫 문장이 리스 행 판정이라 fencing 예외다. */
    private static void prologue(Connection c, int market, LeaderTerm old) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeQuery("""
                    SELECT * FROM append_command_prologue('%s', %d, false, 1, NULL, %d::smallint, 1, '{}'::jsonb, NULL, NULL)
                    """.formatted(old.holder(), old.epoch(), market)).close();
        }
    }

    private static void assertFencing(SQLException e) {
        assertTrue(e.getMessage().contains("fencing 위반"), "fencing 거부가 아니다: " + e.getMessage());
    }

    /**
     * 옛 노드 접수의 거부 사유. DB 의 fencing 거부(머리말의 리스 행 판정, 저널 행 함수 0행)이거나, 그 거부나 옛 기간 정산의
     * 치명이 먼저 강등을 일으켜 저널 전에 닫힌 게이트·멈춘 라이터 루프의 거부다. 어느 쪽이든 저널 행이 남지 않는 것을 따로 본다.
     */
    private static boolean isStaleWrite(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NotReadyException || t instanceof kr.exchange.journal.StaleLeaderException) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("fencing") || message.contains("리더")) {
                return true;
            }
            if (t instanceof IllegalStateException && message.contains("루프 정지")) {
                return true;
            }
        }
        return false;
    }

    private static void assertSnapshotRefused(ReservationFixture f, LeaderTerm old, int market) {
        long before = f.count("SELECT count(*) FROM snapshots");
        SnapshotStore store = new SnapshotStore(f.app, f.appTx, "s7-test", "s7-test");
        DataAccessException refused = assertThrows(DataAccessException.class, () -> store.persist(
                new LeaderIdentity(old.holder(), old.epoch()),
                List.of(new SnapshotStore.MarketState(market, 1L, new byte[] {0}, 0L))));
        assertTrue(String.valueOf(refused.getMostSpecificCause().getMessage()).contains("fencing 위반"),
                "스냅샷 거부가 fencing 이 아니다: " + refused);
        assertEquals(before, f.count("SELECT count(*) FROM snapshots"), "옛 기간 스냅샷이 쓰였다");
    }

    /** 옛 계획의 자원은 새 모드에서 얻지 못한다(55000). 최고 모드 3 바이너리도 같다. */
    private static void assertAcquireRefused(ReservationFixture f, String resource, String holder) {
        PostgresLeaderLeaseStore store = new PostgresLeaderLeaseStore(f.appPool, resource, (short) 3,
                Duration.ofMillis(500));
        DataAccessException refused = assertThrows(DataAccessException.class,
                () -> store.acquire(holder, Duration.ofSeconds(3)));
        assertEquals("55000", TransientDbErrors.sqlState(refused), resource + ": " + refused);
    }
}

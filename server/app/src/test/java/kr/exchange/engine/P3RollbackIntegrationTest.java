package kr.exchange.engine;

import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.S7Audit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitTrades;
import static kr.exchange.engine.PartitionLeaseIsolationTest.trade;
import static kr.exchange.engine.S7Cutover.ME;
import static kr.exchange.engine.S7Cutover.P0;
import static kr.exchange.engine.S7Cutover.P1;
import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 에서 P2 로 되돌리고 다시 올린다(ADR 0034 S7, DB 설계 §5.4-e 2, S7 설계 4-5·6-2). P3 에서 두 파티션이 체결한 뒤 드레인하고
 * 두 노드를 멈춘 채 확인 인자를 준 3->2 를 부른다. app-1 을 먼저 시작해 matching-engine 을 마지막 퇴역 epoch 뒤로 얻는 것을 보고
 * app-2 를 시작한다(팔로워). 데이터 이관은 없다. 샤드 1 의 수수료 잔고는 그대로이고 새 수수료는 샤드 0 에만 쌓인다. 사후 감사
 * C22'(구간 경계 팔 0)·C30 이 0 이고 네 평가기가 같다. 이어 같은 DB 를 2->3 으로 재진입한다. 래치와 샤드 1 계정이 이미 있어
 * 선행 조건은 드레인뿐이다. 전환 셋의 구간 경계 증분 표본이 끝까지 그대로다.
 */
class P3RollbackIntegrationTest {

    @Test
    void rollbackToP2KeepsShardOneAndReentryWorks() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("s7_p3_rollback", 2)) {
            S7Cutover.latch(f);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            f.deposit(bob, ETH, new BigDecimal("10"));
            Map<Long, Long> samples = new TreeMap<>();

            S7Cutover.switchTo(f, 3);
            samples.put(S7Cutover.lastHistoryId(f), S7Cutover.oldRows(f).get(S7Cutover.lastHistoryId(f)));
            runP3(f, alice, bob, 1);
            BigDecimal krw1 = S7Cutover.feeBalance(f, KRW, 1);
            BigDecimal eth1 = S7Cutover.feeBalance(f, ETH, 1);
            BigDecimal krw0 = S7Cutover.feeBalance(f, KRW, 0);
            assertTrue(krw1.signum() > 0 && eth1.signum() > 0, "P3 의 p1 수수료가 샤드 1 에 없다(양성 축)");

            // 되돌리기. 두 노드를 멈춘 채(runP3 가 닫았다) 확인 인자를 준 3->2.
            assertEquals(List.of(), S7Cutover.blockers(f, 2));
            long lastRetired = S7Cutover.switchTo(f, 2);
            samples.put(S7Cutover.lastHistoryId(f), S7Cutover.oldRows(f).get(S7Cutover.lastHistoryId(f)));
            PartitionNode a = new PartitionNode("app-1", f, new PartitionNode.Options().boot(2).preferred(P0));
            PartitionNode b = new PartitionNode("app-2", f, new PartitionNode.Options().boot(2).preferred(P1));
            try {
                a.start();
                awaitAccepting(a, ME);
                assertTrue(a.lease(ME).currentTerm().orElseThrow().epoch() > lastRetired,
                        "되돌린 뒤 matching-engine 이 마지막 퇴역 epoch 뒤가 아니다");
                b.start();
                Thread.sleep(a.options.ttl.multipliedBy(2).toMillis());
                assertFalse(b.lease(ME).isLeader(), "되돌린 뒤 리더가 둘이다");
                assertEquals(List.of(ME), b.plan.partitions(), "P2 로 뜬 노드의 계획이 단일이 아니다");
                trade(f, a, KRW_BTC, alice, bob);
                trade(f, a, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_BTC, 2);
                awaitTrades(f, KRW_ETH, 2);
                S7Cutover.awaitDrained(f);
                assertEquals(0, a.settlement.lockPlanMisses());
            } finally {
                a.close();
                b.close();
            }
            assertEquals(0, krw1.compareTo(S7Cutover.feeBalance(f, KRW, 1)), "되돌린 뒤 샤드 1 KRW 수수료가 바뀌었다");
            assertEquals(0, eth1.compareTo(S7Cutover.feeBalance(f, ETH, 1)), "되돌린 뒤 샤드 1 ETH 수수료가 바뀌었다");
            assertTrue(S7Cutover.feeBalance(f, KRW, 0).compareTo(krw0) > 0, "되돌린 뒤 새 수수료가 샤드 0 에 쌓이지 않았다");
            S7Audit.Snapshot afterRollback = S7Audit.take(f.db).agreed("3->2 뒤 P2 행");
            assertEquals(0, afterRollback.arm("boundary", "command_journal") + afterRollback.arm("boundary", "snapshots"),
                    "구간 경계 팔이 0 이 아니다");
            assertEquals(0, afterRollback.c22Listing());
            assertEquals(0, afterRollback.c30Listing());
            S7Cutover.assertAudit(S7Cutover.authoritative(f, "rollback"), S7Cutover.GATE);

            // 재진입. 래치와 샤드 1 계정이 있어 선행 조건은 없다.
            assertEquals(List.of(), S7Cutover.blockers(f, 3));
            S7Cutover.switchTo(f, 3);
            samples.put(S7Cutover.lastHistoryId(f), S7Cutover.oldRows(f).get(S7Cutover.lastHistoryId(f)));
            runP3(f, alice, bob, 3);
            assertTrue(S7Cutover.feeBalance(f, KRW, 1).compareTo(krw1) > 0, "재진입 뒤 p1 수수료가 샤드 1 로 가지 않았다");
            S7Cutover.assertAudit(S7Cutover.authoritative(f, "reentry"), S7Cutover.GATE);
            S7Audit.Snapshot end = S7Audit.take(f.db).agreed("재진입 뒤");
            assertEquals(0, end.c22Listing());
            assertEquals(0, end.c30Listing());
            assertEquals("P2,P3,P2,P3", f.owner.sql("SELECT string_agg('P' || protocol, ',' ORDER BY id) FROM"
                    + " leader_protocol_history WHERE id > (SELECT min(id) FROM leader_protocol_history)")
                    .query(String.class).single());
            for (Map.Entry<Long, Long> sample : samples.entrySet()) {
                assertEquals(sample.getValue(), end.oldRows().get(sample.getKey()),
                        "이력 행 " + sample.getKey() + " 의 옛 구간 epoch 행이 늘었다(구간 경계 증분)");
            }
        }
    }

    /** P3 두 노드를 함께 띄워 두 파티션을 체결하고 드레인한 뒤 멈춘다. */
    private static void runP3(ReservationFixture f, long alice, long bob, long tradesBefore) throws Exception {
        PartitionNode a = new PartitionNode("app-1", f, new PartitionNode.Options().preferred(P0));
        PartitionNode b = new PartitionNode("app-2", f, new PartitionNode.Options().preferred(P1));
        try {
            a.start();
            b.start();
            awaitAccepting(a, P0);
            awaitAccepting(b, P1);
            trade(f, a, KRW_BTC, alice, bob);
            trade(f, b, KRW_ETH, alice, bob);
            awaitTrades(f, KRW_BTC, tradesBefore);
            awaitTrades(f, KRW_ETH, tradesBefore);
            S7Cutover.awaitDrained(f);
            assertEquals(0, a.settlement.lockPlanMisses());
            assertEquals(0, b.settlement.lockPlanMisses());
        } finally {
            a.close();
            b.close();
        }
    }
}

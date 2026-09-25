package kr.exchange.engine;

import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.AuthoritativeRun;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

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
 * P2 에서 P3 로 올리는 정지 창 전체(ADR 0034 S7, DB 설계 §5.4-e 1, S7 설계 4-4·6-2). S7 바이너리 두 노드(app-1 선호 p0, app-2 선호
 * p1)가 P2 로 떠 한 노드가 matching-engine 을 쥐고 두 마켓을 체결한다. 드레인하고 두 노드를 멈춘 뒤 운영과 같은 전환 함수로 2->3
 * 을 부르고, 새 노드 둘을 함께 시작한다. 각 노드가 선호 파티션을 쥐고, 두 파티션의 체결 수수료가 그 파티션 번호의 샤드(0·1)로
 * 가며, 정산 잠금 계획 이탈이 없고, 사후 권위 run 의 C01·C14'·C22'·C29·C30·C31 이 0 이다. 전환 직후 뜬 구간 경계 증분 표본이
 * 끝까지 그대로다.
 */
class P3TransitionIntegrationTest {

    @Test
    void drainSwitchAndPreferredNodesSplitTheMarketsWithShardedFees() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("s7_p3_transition", 2)) {
            S7Cutover.latch(f);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            f.deposit(bob, ETH, new BigDecimal("10"));

            // P2. 두 노드 모두 S7 바이너리(최고 모드 3)지만 P2 DB 에서 떴으므로 단일 계획이다. 선호 설정은 P2 에서 뜻이 없다.
            PartitionNode a = new PartitionNode("app-1", f, new PartitionNode.Options().boot(2).preferred(P0));
            PartitionNode b = new PartitionNode("app-2", f, new PartitionNode.Options().boot(2).preferred(P1));
            try {
                a.start();
                b.start();
                Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> a.accepting(ME) || b.accepting(ME));
                PartitionNode p2Leader = a.accepting(ME) ? a : b;
                trade(f, p2Leader, KRW_BTC, alice, bob);
                trade(f, p2Leader, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_BTC, 1);
                awaitTrades(f, KRW_ETH, 1);
                S7Cutover.awaitDrained(f);
            } finally {
                a.close();
                b.close();
            }
            assertEquals(0, BigDecimal.ZERO.compareTo(S7Cutover.feeBalance(f, KRW, 1)), "P2 수수료가 샤드 1 로 갔다");
            assertEquals(0, BigDecimal.ZERO.compareTo(S7Cutover.feeBalance(f, ETH, 1)));
            BigDecimal krw0 = S7Cutover.feeBalance(f, KRW, 0);
            BigDecimal eth0 = S7Cutover.feeBalance(f, ETH, 0);
            BigDecimal btc0 = S7Cutover.feeBalance(f, BTC, 0);
            assertTrue(krw0.signum() > 0, "P2 수수료가 샤드 0 에 게시되지 않았다(양성 축)");

            // 정지 창: 선행 조건은 드레인뿐이다. 2->3 은 세 행을 퇴역시킨다.
            assertEquals(java.util.List.of(), S7Cutover.blockers(f, 3));
            long lastRetired = S7Cutover.switchTo(f, 3);
            long h23 = S7Cutover.lastHistoryId(f);
            Map<Long, Long> atCutover = S7Cutover.oldRows(f);

            // 새 노드를 함께 시작한다. 선호 파티션을 곧바로 노리고 비선호는 기동 뒤 2×TTL 을 기다린다.
            PartitionNode a3 = new PartitionNode("app-1", f, new PartitionNode.Options().preferred(P0));
            PartitionNode b3 = new PartitionNode("app-2", f, new PartitionNode.Options().preferred(P1));
            try {
                a3.start();
                b3.start();
                awaitAccepting(a3, P0);
                awaitAccepting(b3, P1);
                long p0Epoch = a3.lease(P0).currentTerm().orElseThrow().epoch();
                long p1Epoch = b3.lease(P1).currentTerm().orElseThrow().epoch();
                assertTrue(p0Epoch > lastRetired && p1Epoch > lastRetired, "P3 첫 획득이 마지막 퇴역 epoch 뒤가 아니다");
                Thread.sleep(a3.options.ttl.multipliedBy(3).toMillis());
                assertEquals(P0, S7Cutover.owned(a3), "app-1 이 선호 p0 만 쥐지 않았다");
                assertEquals(P1, S7Cutover.owned(b3), "app-2 가 선호 p1 만 쥐지 않았다");

                trade(f, a3, KRW_BTC, alice, bob);
                trade(f, b3, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_BTC, 2);
                awaitTrades(f, KRW_ETH, 2);
                S7Cutover.awaitDrained(f);

                // p1 마켓(KRW-ETH) 수수료는 샤드 1, p0 마켓(KRW-BTC) 수수료는 샤드 0 이다.
                assertTrue(S7Cutover.feeBalance(f, KRW, 1).signum() > 0, "p1 마켓의 KRW 수수료가 샤드 1 에 게시되지 않았다");
                assertTrue(S7Cutover.feeBalance(f, ETH, 1).signum() > 0, "p1 마켓의 ETH 수수료가 샤드 1 에 게시되지 않았다");
                assertEquals(0, eth0.compareTo(S7Cutover.feeBalance(f, ETH, 0)), "p1 마켓 수수료가 샤드 0 을 건드렸다");
                assertTrue(S7Cutover.feeBalance(f, KRW, 0).compareTo(krw0) > 0, "p0 마켓의 KRW 수수료가 샤드 0 에 게시되지 않았다");
                assertTrue(S7Cutover.feeBalance(f, BTC, 0).compareTo(btc0) > 0);
                assertEquals(0, BigDecimal.ZERO.compareTo(S7Cutover.feeBalance(f, BTC, 1)), "p0 마켓 수수료가 샤드 1 로 갔다");
                assertEquals(0, a3.settlement.lockPlanMisses(), "p0 정산이 잠금 계획 밖 계정을 썼다");
                assertEquals(0, b3.settlement.lockPlanMisses(), "p1 정산이 잠금 계획 밖 계정을 썼다");
                assertTrue(a3.settlement.lockPlansTaken() > 0 && b3.settlement.lockPlansTaken() > 0);
                // 체결의 fencing epoch 는 그 마켓 파티션 리더의 epoch 다.
                assertEquals(0, f.count("SELECT count(*) FROM trades WHERE market_id = " + KRW_BTC
                        + " AND fencing_epoch > " + lastRetired + " AND fencing_epoch <> " + p0Epoch));
                assertEquals(0, f.count("SELECT count(*) FROM trades WHERE market_id = " + KRW_ETH
                        + " AND fencing_epoch > " + lastRetired + " AND fencing_epoch <> " + p1Epoch));
            } finally {
                a3.close();
                b3.close();
            }

            AuthoritativeRun run = S7Cutover.authoritative(f, "post");
            S7Cutover.assertAudit(run, S7Cutover.GATE);
            assertEquals(atCutover.get(h23), S7Cutover.oldRows(f).get(h23), "전환 뒤 옛 구간 epoch 행이 늘었다(구간 경계 증분)");
            assertEquals("3:2", f.owner.sql("SELECT protocol || ':' || prev_protocol FROM leader_protocol_history"
                    + " ORDER BY id DESC LIMIT 1").query(String.class).single());
            assertFalse(f.count("SELECT count(*) FROM lease_acquisitions WHERE protocol = 3 AND origin = 'ACQUIRE'"
                    + " AND resource = 'matching-engine'") > 0, "P3 에서 matching-engine 을 얻었다");
        }
    }
}

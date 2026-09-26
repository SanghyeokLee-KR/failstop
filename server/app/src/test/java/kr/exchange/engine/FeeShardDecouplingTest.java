package kr.exchange.engine;

import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitTrades;
import static kr.exchange.engine.PartitionLeaseIsolationTest.trade;
import static kr.exchange.engine.S7Cutover.P0;
import static kr.exchange.engine.S7Cutover.P1;
import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수수료 행 결합이 풀렸는지(ADR 0034 S7 결정 10, S7 설계 5-2 Q8 의 시험 판, 6-2). P3 에서 한 트랜잭션이 KRW FEE_INCOME 샤드 0 잔고
 * 행을 쥔 채 머문다(멈춘 p0 리더의 정산이 그 행을 쥔 장면의 흉내). 그동안 p1 마켓(KRW-ETH) 체결의 정산은 샤드 1 행에 게시하므로
 * 기다리지 않고 1초 안에 끝난다. 양성 대조로 p0 마켓(KRW-BTC) 체결의 정산은 그 행을 기다리고, 행을 놓으면 끝난다. 두 파티션 모두
 * 강등과 재시도 소진이 없다.
 */
class FeeShardDecouplingTest {

    @Test
    void p1SettlementDoesNotWaitForAHeldShardZeroFeeRow() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("s7_fee_decouple", 2)) {
            S7Cutover.latch(f);
            S7Cutover.switchTo(f, 3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            f.deposit(bob, ETH, new BigDecimal("10"));
            long krwFee0 = f.count("SELECT id FROM accounts WHERE owner_type = 'SYSTEM' AND purpose = 'FEE_INCOME'"
                    + " AND asset_id = " + KRW + " AND shard = 0");
            PartitionNode a = new PartitionNode("app-1", f, new PartitionNode.Options().preferred(P0));
            PartitionNode b = new PartitionNode("app-2", f, new PartitionNode.Options().preferred(P1));
            try {
                a.start();
                b.start();
                awaitAccepting(a, P0);
                awaitAccepting(b, P1);
                // 계정 조회 캐시와 잠금 계획을 데운다. 두 파티션에서 한 번씩 체결한다.
                trade(f, a, KRW_BTC, alice, bob);
                trade(f, b, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_BTC, 1);
                awaitTrades(f, KRW_ETH, 1);
                BigDecimal krw1Before = S7Cutover.feeBalance(f, KRW, 1);
                BigDecimal krw0Before = S7Cutover.feeBalance(f, KRW, 0);

                try (Connection locker = f.db.owner(); Statement st = locker.createStatement()) {
                    locker.setAutoCommit(false);
                    st.executeQuery("SELECT 1 FROM balances WHERE account_id = " + krwFee0 + " FOR NO KEY UPDATE").close();
                    long heldAt = System.nanoTime();

                    // p1 마켓 체결은 샤드 1 에 게시하므로 샤드 0 행을 기다리지 않는다.
                    long t0 = System.nanoTime();
                    trade(f, b, KRW_ETH, alice, bob);
                    Awaitility.await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10))
                            .until(() -> f.count("SELECT count(*) FROM trades WHERE market_id = " + KRW_ETH) >= 2);
                    long p1Millis = (System.nanoTime() - t0) / 1_000_000;
                    assertTrue(p1Millis <= 1_000, "샤드 0 수수료 행을 쥔 동안 p1 정산이 1초 안에 끝나지 않았다: " + p1Millis + "ms");
                    assertTrue(S7Cutover.feeBalance(f, KRW, 1).compareTo(krw1Before) > 0, "p1 수수료가 샤드 1 에 게시되지 않았다");

                    // 양성 대조: p0 마켓 체결의 정산은 그 행을 기다린다.
                    trade(f, a, KRW_BTC, alice, bob);
                    Thread.sleep(1_000);
                    assertEquals(1, f.count("SELECT count(*) FROM trades WHERE market_id = " + KRW_BTC),
                            "샤드 0 수수료 행을 쥐었는데 p0 정산이 끝났다(대조가 성립하지 않는다)");
                    assertTrue(lockWaiters(f) > 0, "p0 정산이 수수료 행 잠금을 기다리는 표본이 없다");
                    assertTrue((System.nanoTime() - heldAt) / 1_000_000 < 10_000, "잠금을 너무 오래 쥐었다(시험 전제)");
                    locker.rollback();
                }
                awaitTrades(f, KRW_BTC, 2);
                assertTrue(S7Cutover.feeBalance(f, KRW, 0).compareTo(krw0Before) > 0);
                S7Cutover.awaitDrained(f);
                for (PartitionNode node : List.of(a, b)) {
                    assertEquals(0, node.settlement.transientExhausted(), node.name + " 정산 재시도 소진");
                    assertEquals(0, node.settlement.lockPlanMisses(), node.name + " 잠금 계획 이탈");
                    for (String resource : node.plan.partitions()) {
                        assertTrue(node.runtime(resource).demotions().isEmpty(), node.name + " " + resource + " 강등");
                    }
                }
                assertTrue(a.accepting(P0) && b.accepting(P1), "리스 인계가 있었다");
            } finally {
                a.close();
                b.close();
            }
        }
    }

    /** 이 DB 에서 행 잠금을 기다리는 세션 수. 이 시험의 잠금 경합은 쥐고 있는 수수료 행 하나뿐이다. */
    private static long lockWaiters(ReservationFixture f) {
        return f.count("""
                SELECT count(*) FROM pg_stat_activity a
                 WHERE a.datname = current_database() AND a.wait_event_type = 'Lock'
                """);
    }
}

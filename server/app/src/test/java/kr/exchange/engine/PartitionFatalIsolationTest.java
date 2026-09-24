package kr.exchange.engine;

import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitTrades;
import static kr.exchange.engine.PartitionLeaseIsolationTest.trade;
import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파티션 치명의 격리와 차단기, 노드 치명(S4 설계 6-2, DB 설계 §5.4-c 5·10).
 *
 * p1 마켓 sink 에 재시도 대상이 아닌 예외를 넣으면 p1 만 멈추고 fresh 재구성 뒤 FOLLOWER 로 돌아와 다시 후보가 된다. p0 은
 * 그동안 체결을 이어 간다. 같은 주입을 창 안에서 세 번 겪으면 p1 은 BLOCKED 이고 리스를 노리지 않는다. VM 오류는 노드
 * 치명이라 두 파티션이 모두 DEMOTED 이고 rearm 과 차단기 해제가 효과가 없다.
 */
class PartitionFatalIsolationTest {

    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";

    @Test
    void writerFatalStaysInItsPartitionAndTheBreakerBlocksAfterThree() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_fatal", 2)) {
            f.db.setProtocol(3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            f.deposit(bob, ETH, new BigDecimal("10"));

            try (PartitionNode a = new PartitionNode("node-a", f, new PartitionNode.Options().preferred(P0, P1))) {
                AtomicBoolean poison = new AtomicBoolean();
                a.sinkFault = (marketId, term, events) -> {
                    if (marketId == KRW_ETH && poison.getAndSet(false)) {
                        throw new IllegalStateException("주입: 재시도 대상이 아닌 정산 실패");
                    }
                };
                a.start();
                awaitAccepting(a, P0);
                awaitAccepting(a, P1);
                LeaderTerm p0Term = a.lease(P0).currentTerm().orElseThrow();
                long p0Trades = 0;
                long previousP1Epoch = a.lease(P1).currentTerm().orElseThrow().epoch();

                for (int round = 1; round <= 3; round++) {
                    // 주문 하나만 넣는다. 루프가 죽은 뒤의 두 번째 접수는 게이트나 루프 정지로 거부되기 때문이다. 그 주문은
                    // 저널에만 남은 크래시 창이 되고, 다음 승격의 M(P) 재생이 그 기간으로 메운다.
                    poison.set(true);
                    a.submit(f.limit(KRW_ETH, bob, kr.exchange.engine.core.OrderCommand.Side.SELL, "50000", "0.1"));
                    int expectedDemotions = round;
                    Awaitility.await().atMost(Duration.ofSeconds(10))
                            .until(() -> a.runtime(P1).demotions().size() >= expectedDemotions);
                    assertEquals(DemotionReason.WRITER_FATAL, a.runtime(P1).demotions().get(0).reason());
                    assertEquals(round, a.runtime(P1).breaker().count());

                    // p0 은 같은 기간으로 체결을 이어 간다.
                    trade(f, a, KRW_BTC, alice, bob);
                    awaitTrades(f, KRW_BTC, ++p0Trades);
                    assertSame(p0Term, a.lease(P0).currentTerm().orElseThrow(), "p1 치명이 p0 기간을 바꿨다");
                    assertEquals(PromotionCoordinator.State.ACCEPTING, a.state(P0));

                    if (round < 3) {
                        // 재구성 뒤 FOLLOWER 로 돌아와 후보가 되고, 옛 리스가 만료되면 새 epoch 로 다시 얻는다.
                        awaitAccepting(a, P1);
                        long epoch = a.lease(P1).currentTerm().orElseThrow().epoch();
                        assertTrue(epoch > previousP1Epoch, "p1 재승격 epoch 가 늘지 않았다");
                        previousP1Epoch = epoch;
                    }
                }

                // 세 번째 강등은 차단기를 연다. 재구성 뒤 BLOCKED 이고 후보가 아니다.
                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> a.state(P1) == PromotionCoordinator.State.BLOCKED);
                assertTrue(a.runtime(P1).breaker().isOpen());
                assertFalse(a.lease(P1).isArmed(), "차단된 파티션이 후보 표식을 세웠다");
                // 옛 리스 만료(TTL 3초)를 넘겨 기다려도 다시 얻지 않는다.
                Thread.sleep(a.options.ttl.toMillis() * 2);
                assertFalse(a.lease(P1).isLeader(), "차단된 파티션이 리스를 다시 얻었다");
                assertEquals(PromotionCoordinator.State.BLOCKED, a.state(P1));
                assertEquals(3.0, a.counter("exchange.partition.demotions", "partition", P1, "reason", "WRITER_FATAL"));
                assertEquals(0.0, a.counter("exchange.partition.demotions", "partition", P0, "reason", "WRITER_FATAL"));
                assertSame(p0Term, a.lease(P0).currentTerm().orElseThrow());

                // 운영자가 차단기를 풀면 후보로 돌아와 다시 얻는다.
                assertTrue(a.runtime(P1).resetBreaker());
                awaitAccepting(a, P1);
                trade(f, a, KRW_ETH, alice, bob);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> f.count("SELECT count(*) FROM trades WHERE market_id = " + KRW_ETH) >= 1);
            }
        }
    }

    @Test
    void vmErrorIsNodeFatalForEveryPartition() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_node_fatal", 2)) {
            f.db.setProtocol(3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));

            try (PartitionNode c = new PartitionNode("node-c", f, new PartitionNode.Options().preferred(P0, P1))) {
                c.sinkFault = (marketId, term, events) -> {
                    if (marketId == KRW_BTC) {
                        throw new StackOverflowError("주입: VM 오류");
                    }
                };
                c.start();
                awaitAccepting(c, P0);
                awaitAccepting(c, P1);

                c.submit(f.limit(KRW_BTC, bob, kr.exchange.engine.core.OrderCommand.Side.SELL, "50000", "0.1"));
                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> c.state(P0) == PromotionCoordinator.State.DEMOTED
                                && c.state(P1) == PromotionCoordinator.State.DEMOTED);
                for (String resource : new String[] {P0, P1}) {
                    assertFalse(c.lease(resource).isLeader(), resource + " 가 리더로 남았다");
                    assertTrue(c.lease(resource).isNodeFatal(), resource + " 가 노드 치명이 아니다");
                    assertFalse(c.lease(resource).rearm(), resource + " 를 rearm 이 풀었다");
                    assertFalse(c.runtime(resource).resetBreaker(), resource + " 를 차단기 해제가 풀었다");
                }
                Thread.sleep(c.options.ttl.toMillis() * 2);
                assertFalse(c.lease(P0).isLeader() || c.lease(P1).isLeader(), "노드 치명 뒤 리스를 다시 얻었다");
                assertEquals(PromotionCoordinator.State.DEMOTED, c.state(P0));
                assertEquals(PromotionCoordinator.State.DEMOTED, c.state(P1));
                assertEquals(org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC, c.readiness());
            }
        }
    }
}

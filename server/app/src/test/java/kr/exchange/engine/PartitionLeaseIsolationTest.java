package kr.exchange.engine;

import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파티션 리스의 격리(S4 설계 6-2, DB 설계 §5.4-c 2·5). P3 흉내 DB 에서 노드 A 가 p0·p1 을 쥐고 두 파티션에 주문을 넣은 뒤
 * p0 을 계획 강등한다. p0 의 라이터는 멈추고, p1 의 게이트·기간·epoch·갱신·라이터는 강등 전후로 그대로이며 p1 주문이 계속
 * 체결된다. p0 은 만료 뒤 노드 B 가 새 epoch 로 얻고, p0 옛 epoch 로는 한 행도 쓰이지 않는다.
 */
class PartitionLeaseIsolationTest {

    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";

    @Test
    void plannedDemotionOfOnePartitionLeavesTheOtherUntouched() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_isolation", 2)) {
            f.db.setProtocol(3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            f.deposit(bob, ETH, new BigDecimal("10"));

            try (PartitionNode a = new PartitionNode("node-a", f, new PartitionNode.Options().preferred(P0, P1));
                 PartitionNode b = new PartitionNode("node-b", f, new PartitionNode.Options().preferred(P0))) {
                a.start();
                awaitAccepting(a, P0);
                awaitAccepting(a, P1);

                trade(f, a, KRW_BTC, alice, bob);
                trade(f, a, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_BTC, 1);
                awaitTrades(f, KRW_ETH, 1);

                LeaderTerm p1Term = a.leases.openTerm(KRW_ETH);
                long p1Epoch = p1Term.epoch();
                long p0Epoch = a.lease(P0).currentTerm().orElseThrow().epoch();
                double p1RenewBefore = a.counter("exchange.leader.lease.renew", "outcome", "ok", "partition", P1);
                SingleWriterLoop p0Loop = a.router.slotOf(KRW_BTC).shard().loop();
                SingleWriterLoop p1Loop = a.router.slotOf(KRW_ETH).shard().loop();

                // 계획 강등(참여 끄기). 리스는 반납하지 않고 만료시킨다.
                a.runtime(P0).setParticipate(false);
                assertFalse(a.lease(P0).isLeader(), "계획 강등이 p0 기간을 놓지 않았다");
                assertEquals(null, a.leases.openTerm(KRW_BTC), "계획 강등 뒤 p0 게이트가 열려 있다");
                // 강등은 그 파티션의 라이터를 멈춘다. 다른 파티션의 라이터는 같은 루프로 계속 돈다.
                assertFalse(p0Loop.isRunning(), "계획 강등이 p0 라이터를 멈추지 않았다");
                assertSame(p1Loop, a.router.slotOf(KRW_ETH).shard().loop(), "p0 강등이 p1 라이터를 바꿨다");
                assertTrue(p1Loop.isRunning(), "p0 강등이 p1 라이터를 멈췄다");
                long p0RowsAtDemotion = rowsWithEpoch(f, KRW_BTC, p0Epoch);
                long p0Commands = f.count("SELECT count(*) FROM command_journal WHERE market_id = " + KRW_BTC);
                // 강등은 게이트를 닫고 그 파티션 라이터를 멈춘다. 접수는 둘 가운데 먼저 닿는 쪽(루프 정지 또는 게이트)에서
                // 저널 전에 거부된다.
                RuntimeException refused = assertThrows(RuntimeException.class,
                        () -> a.submit(f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1")));
                assertTrue(refused instanceof NotReadyException || refused instanceof IllegalStateException,
                        "강등한 파티션의 접수가 저널 전 거부가 아니다: " + refused);
                assertEquals(p0Commands, f.count("SELECT count(*) FROM command_journal WHERE market_id = " + KRW_BTC),
                        "강등한 파티션의 접수가 저널에 닿았다");

                // p1 은 강등 전과 같은 기간·epoch 로 계속 체결한다.
                trade(f, a, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_ETH, 2);
                assertSame(p1Term, a.leases.openTerm(KRW_ETH), "p0 강등이 p1 게이트의 기간을 바꿨다");
                assertEquals(p1Epoch, a.lease(P1).currentTerm().orElseThrow().epoch());

                // 노드 B 가 p0 을 만료 뒤 새 epoch 로 얻는다. p1 은 A 가 쥔 유효 리스라 B 가 얻지 못한다.
                b.start();
                awaitAccepting(b, P0);
                long p0NewEpoch = b.lease(P0).currentTerm().orElseThrow().epoch();
                assertTrue(p0NewEpoch > p0Epoch, "p0 새 epoch 가 옛 epoch 보다 크지 않다");
                assertFalse(b.lease(P1).isLeader(), "유효한 p1 리스를 B 가 얻었다");
                trade(f, b, KRW_BTC, alice, bob);
                awaitTrades(f, KRW_BTC, 2);
                trade(f, a, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_ETH, 3);

                // p1 의 기간·epoch·갱신이 그대로다. 갱신 거부가 한 번도 없다.
                assertSame(p1Term, a.lease(P1).currentTerm().orElseThrow());
                assertTrue(a.counter("exchange.leader.lease.renew", "outcome", "ok", "partition", P1) > p1RenewBefore,
                        "p1 갱신이 이어지지 않았다");
                assertEquals(0.0, a.counter("exchange.leader.lease.renew", "outcome", "rejected", "partition", P1));
                assertEquals(PromotionCoordinator.State.ACCEPTING, a.state(P1));
                // A 의 p0 는 팔로워로 돌아와 후보가 아니다(참여 끔).
                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> a.state(P0) == PromotionCoordinator.State.FOLLOWER);
                assertFalse(a.lease(P0).isArmed());
                assertEquals(1, a.runtime(P0).demotions().size());
                assertEquals(DemotionReason.PLANNED, a.runtime(P0).demotions().get(0).reason());
                assertEquals(0, a.runtime(P0).breaker().count(), "계획 강등을 차단기가 셌다");

                // p0 옛 epoch 로는 강등 뒤 한 행도 쓰이지 않았다.
                assertEquals(p0RowsAtDemotion, rowsWithEpoch(f, KRW_BTC, p0Epoch), "p0 옛 epoch 로 쓴 행이 늘었다");
                assertEquals(0, f.count("SELECT count(*) FROM event_journal WHERE market_id = " + KRW_ETH
                        + " AND fencing_epoch <> " + p1Epoch), "p1 마켓에 다른 epoch 의 이벤트가 있다");
                assertEquals(0L, f.invariant(kr.exchange.harness.InvariantCheck.valueOf("C31_RESERVATION_LINK")));
            }
        }
    }

    static void awaitAccepting(PartitionNode node, String resource) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> node.accepting(resource));
    }

    /** 교차하는 매도·매수 한 쌍을 넣는다. */
    static void trade(ReservationFixture f, PartitionNode node, int market, long buyer, long seller) {
        node.submit(f.limit(market, seller, OrderCommand.Side.SELL, "50000", "0.1"));
        node.submit(f.limit(market, buyer, OrderCommand.Side.BUY, "50000", "0.1"));
    }

    static void awaitTrades(ReservationFixture f, int market, long count) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> f.count("SELECT count(*) FROM trades WHERE market_id = " + market) >= count);
    }

    /** 그 마켓에서 그 epoch 로 쓴 명령·이벤트 행 수. */
    static long rowsWithEpoch(ReservationFixture f, int market, long epoch) {
        return f.count("SELECT count(*) FROM command_journal WHERE market_id = " + market + " AND fencing_token = " + epoch)
                + f.count("SELECT count(*) FROM event_journal WHERE market_id = " + market + " AND fencing_epoch = " + epoch);
    }
}

package kr.exchange.engine;

import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 좀비 판정 두 층(S4 설계 6-2, DB 설계 §5.4-c 8). 파티션 층은 그 파티션 마켓의 수요와 완료로 판정해 그 파티션만 STALL 로
 * 강등하고, 노드 층은 노드 전체의 수요와 완료로 판정해 그 노드가 쥔 모든 파티션을 강등한다.
 *
 * 판정 시계는 주입한다. 수요를 넣고 한 번 갱신 틱을 기다려 붕괴 관측을 시작시킨 뒤 시계를 강등 기준보다 멀리 옮긴다.
 */
class PartitionStallDetectionTest {

    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";
    private static final Duration STANDDOWN_AFTER = Duration.ofSeconds(45);

    /** p1 마켓 append 만 연결 오류로 실패한다. p1 만 STALL 이고 p0 과 노드 층은 그대로다. */
    @Test
    void partitionLayerDemotesOnlyTheStalledPartition() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_stall_partition", 2)) {
            f.db.setProtocol(3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            f.deposit(bob, ETH, new BigDecimal("10"));
            AtomicLong clock = new AtomicLong(1_000_000_000L);

            try (PartitionNode a = new PartitionNode("node-a", f, new PartitionNode.Options().preferred(P0, P1)
                    .stallClock(clock::get, STANDDOWN_AFTER))) {
                a.appendFault = (term, draft) -> {
                    if (draft.marketId() == KRW_ETH) {
                        throw new DataAccessResourceFailureException("주입: append 연결 끊김");
                    }
                };
                a.start();
                awaitAccepting(a, P0);
                awaitAccepting(a, P1);
                LeaderTerm p0Term = a.lease(P0).currentTerm().orElseThrow();
                // p1 접수는 append 에서 끝나 라이터에 닿지 않으므로 루프를 먼저 띄워 둔다. 강등이 멈추는지 볼 대상이다.
                SingleWriterLoop p0Loop = a.router.loop(KRW_BTC);
                SingleWriterLoop p1Loop = a.router.loop(KRW_ETH);

                for (int i = 0; i < 25; i++) {
                    assertThrows(DataAccessResourceFailureException.class, () -> a.submit(
                            f.limit(KRW_ETH, alice, OrderCommand.Side.BUY, "50000", "0.1")));
                }
                for (int i = 0; i < 10; i++) {
                    trade(f, a, KRW_BTC, alice, bob);
                }
                awaitTrades(f, KRW_BTC, 10);
                // 갱신 틱이 두 층의 표본을 떠 붕괴 관측을 시작하게 한다.
                Thread.sleep(a.options.tick.toMillis() * 3);
                clock.addAndGet(STANDDOWN_AFTER.plusSeconds(5).toNanos());

                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> !a.runtime(P1).demotions().isEmpty());
                assertEquals(DemotionReason.STALL, a.runtime(P1).demotions().get(0).reason());
                // 강등 기록은 라이터를 멈춘 뒤에 남는다. 재구성 뒤 재획득은 옛 리스 만료(TTL 3초) 뒤라 이 단언보다 늦다.
                assertFalse(p1Loop.isRunning(), "STALL 강등이 p1 라이터를 멈추지 않았다");
                assertSame(p0Loop, a.router.slotOf(KRW_BTC).shard().loop(), "p1 강등이 p0 라이터를 바꿨다");
                assertTrue(p0Loop.isRunning(), "p1 강등이 p0 라이터를 멈췄다");
                assertEquals(1.0, a.counter("exchange.leader.standdown", "partition", P1));
                assertEquals(0.0, a.counter("exchange.leader.standdown", "partition", P0));
                assertEquals(0.0, a.registry.get("exchange.partition.serviceable").tag("partition", P1).gauge().value());
                assertEquals(1.0, a.registry.get("exchange.partition.serviceable").tag("partition", P0).gauge().value());
                assertEquals(1.0, a.registry.get("exchange.node.serviceable").gauge().value(),
                        "한 파티션의 붕괴로 노드 층 판정이 발동했다");

                // p0 은 같은 기간으로 갱신과 체결을 이어 간다.
                Thread.sleep(a.options.tick.toMillis() * 3);
                assertSame(p0Term, a.lease(P0).currentTerm().orElseThrow(), "p1 STALL 이 p0 기간을 바꿨다");
                assertTrue(a.runtime(P0).demotions().isEmpty());
                trade(f, a, KRW_BTC, alice, bob);
                awaitTrades(f, KRW_BTC, 11);
            }
        }
    }

    /**
     * 두 파티션이 모두 막힌다. 파티션마다 수요는 판정 하한(20) 밑이라 파티션 층은 판정하지 않고, 노드 층 수요는 하한을 넘어
     * 노드 층이 두 파티션을 모두 강등한다.
     */
    @Test
    void nodeLayerDemotesEveryPartitionWhenTheWholeNodeStalls() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_stall_node", 2)) {
            f.db.setProtocol(3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            AtomicLong clock = new AtomicLong(1_000_000_000L);

            try (PartitionNode a = new PartitionNode("node-a", f, new PartitionNode.Options().preferred(P0, P1)
                    .stallClock(clock::get, STANDDOWN_AFTER))) {
                a.appendFault = (term, draft) -> {
                    throw new DataAccessResourceFailureException("주입: append 연결 끊김");
                };
                a.start();
                awaitAccepting(a, P0);
                awaitAccepting(a, P1);
                // 접수가 모두 append 에서 끝나 라이터에 닿지 않으므로 루프를 먼저 띄워 둔다.
                SingleWriterLoop p0Loop = a.router.loop(KRW_BTC);
                SingleWriterLoop p1Loop = a.router.loop(KRW_ETH);

                for (int i = 0; i < 15; i++) {
                    assertThrows(DataAccessResourceFailureException.class, () -> a.submit(
                            f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1")));
                    assertThrows(DataAccessResourceFailureException.class, () -> a.submit(
                            f.limit(KRW_ETH, alice, OrderCommand.Side.BUY, "50000", "0.1")));
                }
                Thread.sleep(a.options.tick.toMillis() * 3);
                clock.addAndGet(STANDDOWN_AFTER.plusSeconds(5).toNanos());

                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> !a.runtime(P0).demotions().isEmpty() && !a.runtime(P1).demotions().isEmpty());
                assertEquals(DemotionReason.STALL, a.runtime(P0).demotions().get(0).reason());
                assertEquals(DemotionReason.STALL, a.runtime(P1).demotions().get(0).reason());
                assertFalse(p0Loop.isRunning(), "노드 층 STALL 이 p0 라이터를 멈추지 않았다");
                assertFalse(p1Loop.isRunning(), "노드 층 STALL 이 p1 라이터를 멈추지 않았다");
                assertEquals(0.0, a.registry.get("exchange.node.serviceable").gauge().value(),
                        "노드 층 판정이 발동하지 않았다");
                assertEquals(1.0, a.registry.get("exchange.partition.serviceable").tag("partition", P0).gauge().value(),
                        "수요 하한 밑의 파티션 층이 판정했다");
                assertEquals(1.0, a.registry.get("exchange.partition.serviceable").tag("partition", P1).gauge().value(),
                        "수요 하한 밑의 파티션 층이 판정했다");
            }
        }
    }
}

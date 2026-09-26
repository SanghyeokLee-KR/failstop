package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 라이터 부분 설치(DB 설계 §5.4-c 7, S4 설계 4-9). 한 파티션을 설치해도 다른 파티션 마켓의 루프·엔진·큐·커서·북이 그대로다.
 * 대조군인 모든 마켓 설치(installFrom)는 복구 결과에 없는 마켓의 북을 비운다. 파티션이 둘이면 그것이 다른 파티션의 살아 있는
 * 북을 지운다.
 */
class WriterRouterSubsetInstallTest {

    private static final IntPredicate P0 = marketId -> marketId == 1 || marketId == 2;
    private static final IntPredicate P1 = marketId -> marketId == 3 || marketId == 4;

    @Test
    void installingOnePartitionLeavesTheOtherPartitionUntouched() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        WriterRouter router = new WriterRouter((marketId, engine, term, slot) -> {
            SingleWriterLoop loop = new SingleWriterLoop(1024, engine, events -> {
                // 마켓 3 의 첫 배치에서 멈춰 그 뒤 명령이 큐에 남게 한다.
                if (marketId == 3) {
                    released.await();
                }
            }, (afterSeq, limit) -> List.of(), fatal -> {
            });
            return new WriterRouter.Shard(engine, loop);
        });
        try {
            LeaderTerm p0First = term("matching-p0", 11);
            LeaderTerm p1 = term("matching-p1", 12);
            router.install(p0First, P0, restingBooks(1, 2));
            router.install(p1, P1, restingBooks(3, 4));

            // 마켓 4 는 끝까지 적용하고, 마켓 3 은 첫 명령에서 sink 가 멈춰 두 명령이 큐에 남는다.
            router.offer(order(4, 101));
            router.offer(order(4, 102));
            router.offer(order(3, 101));
            router.offer(order(3, 102));
            router.offer(order(3, 103));
            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .until(() -> router.engine(4).appliedSeq(4) == 102 && router.loop(3).queueSize() == 2);

            Snapshot m3 = Snapshot.of(router, 3);
            Snapshot m4 = Snapshot.of(router, 4);
            SingleWriterLoop oldLoop1 = router.loop(1);
            assertEquals(2, m3.queue(), "마켓 3 큐에 명령이 남지 않아 판정이 공허하다");
            assertEquals(101, m3.cursor());

            LeaderTerm p0Second = term("matching-p0", 13);
            router.install(p0Second, P0, restingBooks(1, 2));

            assertEquals(m3, Snapshot.of(router, 3), "p0 설치가 p1 마켓 3 의 라이터를 건드렸다");
            assertEquals(m4, Snapshot.of(router, 4), "p0 설치가 p1 마켓 4 의 라이터를 건드렸다");
            assertSame(p1, router.slotOf(3).term(), "p1 마켓의 기간이 바뀌었다");
            assertNotSame(oldLoop1, router.loop(1), "p0 마켓에 새 루프가 설치되지 않았다");
            assertSame(p0Second, router.slotOf(1).term(), "p0 마켓의 루프가 새 기간에 묶이지 않았다");
            assertTrue(router.isRunning(P0) && router.isRunning(P1));
            assertTrue(!oldLoop1.isRunning(), "옛 기간의 루프가 멈추지 않았다");
        } finally {
            released.countDown();
            router.stopAll();
        }
    }

    @Test
    void allMarketInstallWipesBooksMissingFromTheRecoveredEngine() throws Exception {
        WriterRouter router = new WriterRouter(marketId -> {
            MatchingEngineImpl engine = new MatchingEngineImpl();
            return new WriterRouter.Shard(engine, new SingleWriterLoop(64, engine, events -> {
            }, (afterSeq, limit) -> List.of(), fatal -> {
            }));
        });
        try {
            router.installFrom(restingBooks(1, 2, 3, 4));
            assertNotNull(router.engine(3).orderBook(3), "대조군 전제: 마켓 3 에 북이 있어야 한다");

            router.installFrom(restingBooks(1, 2));

            // 이것이 부분 설치가 막는 결함이다. 파티션이 둘이면 p0 승격이 p1 의 살아 있는 북을 지운다.
            assertNull(router.engine(3).orderBook(3), "대조군이 결함을 드러내지 않았다");
            assertEquals(0, router.engine(3).appliedSeq(3));
        } finally {
            router.stopAll();
        }
    }

    /** 한 마켓 라이터의 관측값. 객체 동일성까지 담는다. */
    private record Snapshot(SingleWriterLoop loop, MatchingEngineImpl engine, int queue, long cursor, long bookHash) {
        static Snapshot of(WriterRouter router, int marketId) {
            MatchingEngineImpl engine = router.engine(marketId);
            return new Snapshot(router.loop(marketId), engine, router.loop(marketId).queueSize(),
                    engine.appliedSeq(marketId), engine.orderBook(marketId).stateChecksum());
        }
    }

    private static LeaderTerm term(String resource, long epoch) {
        LeaderLeaseService lease = new LeaderLeaseService(resource, "node-a", new TestPartitions.UnreachableStore(),
                kr.exchange.engine.lease.FencingEpochPublisher.NONE, Duration.ofSeconds(10), Duration.ofSeconds(3),
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress());
        return new LeaderTerm(lease, resource, "node-a", epoch, 1);
    }

    /** 마켓마다 매도 호가 하나(seq 100)를 올린 엔진. */
    private static MatchingEngineImpl restingBooks(int... markets) {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        for (int marketId : markets) {
            engine.apply(new OrderCommand(100, marketId, OrderCommand.CommandType.NEW, 9, "rest-" + marketId,
                    marketId * 1000L, 1_790_000_000_000L, 1, OrderCommand.Side.SELL, OrderCommand.OrdType.LIMIT,
                    OrderCommand.TimeInForce.GTC, new BigDecimal("1000"), new BigDecimal("1"), null, 1));
        }
        return engine;
    }

    private static OrderCommand order(int marketId, long seq) {
        return new OrderCommand(seq, marketId, OrderCommand.CommandType.NEW, 8, "buy-" + marketId + "-" + seq,
                marketId * 1000L + seq, 1_790_000_000_000L + seq, seq, OrderCommand.Side.BUY,
                OrderCommand.OrdType.LIMIT, OrderCommand.TimeInForce.GTC, new BigDecimal("900"),
                new BigDecimal("1"), null, 1);
    }
}

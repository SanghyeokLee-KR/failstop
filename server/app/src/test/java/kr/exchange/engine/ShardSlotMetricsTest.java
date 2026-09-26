package kr.exchange.engine;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 라이터 지표는 마켓 자리(ShardSlot)에 묶여 재구성 뒤에도 이어진다(S4 설계 4-9·4-16). 기간마다 루프를 새로 만들므로 지표를
 * 루프 객체에 묶으면 재구성 뒤 옛 루프에 묶인 채 멈춘다. 자리는 물러난 루프의 누계를 더해 단조로 잇고, 미터는 마켓마다 한
 * 번만 등록된다.
 */
class ShardSlotMetricsTest {

    private static final String RESOURCE = "matching-engine";

    @Test
    void countersContinueAcrossReinstallAndMetersRegisterOnce() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderLeaseService lease = TestPartitions.unstarted(PartitionPlan.single()).first();
        WriterRouter router = new WriterRouter((marketId, engine, term, slot) -> new WriterRouter.Shard(engine,
                new SingleWriterLoop(64, engine, events -> { }, (after, limit) -> List.of(), t -> { })));
        router.onSlotAdded(slot -> FunctionCounter.builder("exchange.writer.apply.count", slot, ShardSlot::applyCount)
                .tag("market", String.valueOf(slot.marketId()))
                .register(registry));
        try {
            LeaderTerm first = new LeaderTerm(lease, RESOURCE, "holder", 1, 1);
            router.install(first, marketId -> true, engineWithMarket(1));
            ShardSlot slot = router.slotOf(1);
            SingleWriterLoop firstLoop = slot.shard().loop();
            router.offer(sell(1, 1));
            router.offer(sell(1, 2));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> slot.applyCount() == 2);

            // 재구성 뒤의 새 기간. 자리는 그대로이고 루프는 새것이다.
            LeaderTerm second = new LeaderTerm(lease, RESOURCE, "holder", 2, 2);
            MatchingEngineImpl rebuilt = new MatchingEngineImpl();
            rebuilt.loadMarketSnapshot(1, router.engine(1).appliedSeq(1), router.engine(1).orderBook(1).copy());
            router.install(second, marketId -> true, rebuilt);
            assertSame(slot, router.slotOf(1), "재구성이 자리를 바꿨다");
            assertNotSame(firstLoop, slot.shard().loop(), "재구성이 루프를 새로 만들지 않았다");
            assertSame(second, slot.term());
            assertEquals(2, slot.applyCount(), "물러난 루프의 누계가 자리에 남지 않았다");
            router.offer(sell(1, 3));
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> slot.applyCount() == 3);

            assertEquals(1, registry.find("exchange.writer.apply.count").meters().size(), "미터가 마켓마다 한 번이 아니다");
            assertEquals(3.0, registry.get("exchange.writer.apply.count").tag("market", "1").functionCounter().count(),
                    "재구성 뒤 지표가 이어지지 않았다");
        } finally {
            router.stopAll();
        }
    }

    private static MatchingEngineImpl engineWithMarket(int marketId) {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        engine.loadMarketSnapshot(marketId, 0, new kr.exchange.engine.core.OrderBookImpl(marketId));
        return engine;
    }

    private static OrderCommand sell(int marketId, long seq) {
        return new OrderCommand(seq, marketId, OrderCommand.CommandType.NEW, 7, "sell-" + seq, 1_000 + seq, seq, seq,
                OrderCommand.Side.SELL, OrderCommand.OrdType.LIMIT, OrderCommand.TimeInForce.GTC,
                new BigDecimal("100").add(BigDecimal.valueOf(seq)), new BigDecimal("1"), null, 1);
    }
}

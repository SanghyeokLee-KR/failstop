package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.WriterRouter;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.CommandDraft;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 접수 락 지표가 마켓마다 따로 관측된다(ADR 0022 4-1). 태그 없는 같은 이름이 함께 등록되면 태그 키
 * 집합이 달라 Prometheus 레지스트리가 한쪽을 버리므로, 등록된 미터가 전부 market 태그를 가져야 한다.
 */
class SubmitLockMetersTest {

    private static final List<String> NAMES = List.of(
            "exchange.submit.lock.wait.nanos", "exchange.submit.lock.hold.nanos", "exchange.submit.count",
            "exchange.submit.lock.timeouts");

    @Test
    void engineBridgeRegistersSubmitMetersPerMarketOnFirstSubmit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 기동하지 않은 라우터라 접수는 첫 게이트에서 거부된다. 락과 계측은 게이트 앞뒤에서 이미 돈다.
        WriterRouter router = new WriterRouter(marketId -> {
            throw new AssertionError("거부될 접수가 라이터를 만들었다");
        });
        EngineBridge bridge = new EngineConfig().engineBridge(null, router,
                new CommandPayloadMapper(new ObjectMapper()),
                kr.exchange.engine.TestPartitions.unstarted(kr.exchange.engine.PartitionPlan.single()),
                new MarketDirectory(null), new EngineProperties(null, null, null, 16, null, null, null), registry);
        for (String name : NAMES) {
            assertTrue(registry.find(name).meters().isEmpty(), name + " 가 첫 접수 전에 등록됐다");
        }

        assertThrows(IllegalStateException.class, () -> bridge.submit(order(1, 1)));
        assertThrows(IllegalStateException.class, () -> bridge.submit(order(2, 2)));
        assertThrows(IllegalStateException.class, () -> bridge.submit(order(2, 3)));

        for (String name : NAMES) {
            Set<String> markets = new HashSet<>();
            for (Meter meter : registry.find(name).meters()) {
                markets.add(meter.getId().getTag("market"));
            }
            assertEquals(Set.of("1", "2"), markets, name + " 가 마켓마다 하나씩 태그와 함께 등록되지 않았다");
        }
        assertEquals(1.0, registry.get("exchange.submit.count").tag("market", "1").functionCounter().count());
        assertEquals(2.0, registry.get("exchange.submit.count").tag("market", "2").functionCounter().count());
        // 락 대기 상한은 기본으로 꺼져 있어 상한 초과가 한 번도 나지 않는다(ADR 0034 S1).
        assertEquals(0.0, registry.get("exchange.submit.lock.timeouts").tag("market", "1").functionCounter().count());
    }

    private static OrderIntake order(int marketId, long orderId) {
        String clientOrderId = "meter-" + orderId;
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, 1L, clientOrderId, orderId,
                1_753_000_000_000L + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("100"), new BigDecimal("1"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes(StandardCharsets.UTF_8)), null, null);
    }
}

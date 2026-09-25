package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.ReserveCheck;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 한 마켓 라이터의 정지는 그 마켓 접수만 저널 앞에서 거부한다(ADR 0022 4-2). */
class EngineBridgeMarketGateTest {

    private final List<Integer> journaledMarkets = new CopyOnWriteArrayList<>();

    // 저널 경계만 대신한다. 게이트가 거부한 접수는 여기까지 오지 않아야 한다.
    private final CommandJournalAppender appender = new CommandJournalAppender(null, null, null) {
        @Override
        public AppendResult append(CommandDraft draft, ReserveCheck check) {
            journaledMarkets.add(draft.marketId());
            return new AppendResult(journaledMarkets.size(), 1, false, draft.assignedOrderId());
        }
    };

    @Test
    void stoppedWriterRejectsOnlyItsMarket() throws InterruptedException {
        WriterRouter router = new WriterRouter(marketId -> {
            MatchingEngineImpl engine = new MatchingEngineImpl();
            return new WriterRouter.Shard(engine, new SingleWriterLoop(16, engine, events -> {
            }, (afterSeq, limit) -> List.of(), fatal -> {
            }));
        });
        EngineBridge bridge = new EngineBridge(appender, router, new CommandPayloadMapper(new ObjectMapper()),
                AcceptGate.ALWAYS_OPEN, EngineBridge.NEVER_HALTED);
        router.startAll();
        try {
            router.shard(1);
            router.shard(2);
            router.loop(1).stop();

            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> bridge.submit(order(1, 11)));
            assertTrue(rejected.getMessage().contains("market=1"), rejected.getMessage());

            SubmitResult accepted = bridge.submit(order(2, 21));
            assertFalse(accepted.replayed());
            assertTrue(accepted.queued(), "살아 있는 마켓의 접수가 라이터에 닿지 않았다");
            assertEquals(List.of(2), journaledMarkets,
                    "정지한 마켓의 접수가 저널에 닿았거나 살아 있는 마켓의 접수가 막혔다");
        } finally {
            router.stopAll();
        }
    }

    /**
     * 리더 자격 거부의 응답을 가르는 게이트 조회(API 설계 §2-5-c). 기간을 드는 게이트는 그 마켓 파티션에 열린 기간이 있을 때만
     * 참이고, 기간을 들지 않는 게이트는 개방 여부 그대로다.
     */
    @Test
    void acceptingReadsTheMarketPartitionGate() {
        LeaderTerm open = new LeaderTerm(null, "matching-p0", "node-a", 7, 1);
        AcceptGate partitioned = new AcceptGate() {
            @Override
            public boolean isAccepting() {
                return true;
            }

            @Override
            public boolean termBound() {
                return true;
            }

            @Override
            public LeaderTerm openTerm(int marketId) {
                return marketId == 1 ? open : null;
            }
        };
        CommandPayloadMapper mapper = new CommandPayloadMapper(new ObjectMapper());
        WriterRouter router = new WriterRouter(marketId -> {
            throw new IllegalStateException("이 시험은 설치하지 않는다");
        });
        EngineBridge bridge = new EngineBridge(appender, router, mapper, partitioned, EngineBridge.NEVER_HALTED);
        assertTrue(bridge.accepting(1), "열린 기간이 있는 마켓의 게이트를 닫힘으로 읽었다");
        assertFalse(bridge.accepting(2), "기간이 없는 마켓의 게이트를 열림으로 읽었다");

        assertTrue(new EngineBridge(appender, router, mapper, () -> true, EngineBridge.NEVER_HALTED).accepting(2));
        assertFalse(new EngineBridge(appender, router, mapper, () -> false, EngineBridge.NEVER_HALTED).accepting(1));
    }

    private static OrderIntake order(int marketId, long orderId) {
        String clientOrderId = "gate-" + orderId;
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, 1L, clientOrderId, orderId,
                1_753_000_000_000L + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("100"), new BigDecimal("1"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes(StandardCharsets.UTF_8)), null, null);
    }
}

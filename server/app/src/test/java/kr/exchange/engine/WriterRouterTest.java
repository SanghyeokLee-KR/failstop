package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 마켓별 접수 게이트 판정(ADR 0022 4-2). 한 샤드의 정지가 그 마켓의 접수만 닫는다. */
class WriterRouterTest {

    private final WriterRouter router = new WriterRouter(marketId -> {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        return new WriterRouter.Shard(engine, new SingleWriterLoop(16, engine, events -> {
        }, (afterSeq, limit) -> List.of(), fatal -> {
        }));
    });

    @AfterEach
    void stopWriters() throws InterruptedException {
        router.stopAll();
    }

    @Test
    void notStartedRouterClosesEveryMarket() {
        router.shard(1);
        assertEquals(List.of(1), router.markets(), "샤드가 만들어지지 않아 판정이 공허하다");

        assertFalse(router.isRunning(1), "기동 전 라우터가 샤드 있는 마켓 접수를 열었다");
        assertFalse(router.isRunning(2), "기동 전 라우터가 샤드 없는 마켓 접수를 열었다");
    }

    @Test
    void marketWithoutShardIsOpenAndJudgingCreatesNoShard() {
        router.startAll();

        assertTrue(router.isRunning(7), "샤드가 아직 없는 마켓의 접수를 닫았다");
        assertEquals(List.of(), router.markets(), "판정이 샤드를 만들었다");
    }

    @Test
    void stoppedShardClosesOnlyItsMarket() throws InterruptedException {
        router.startAll();
        router.shard(1);
        router.shard(2);
        assertTrue(router.isRunning(1) && router.isRunning(2), "기동한 샤드가 닫혀 있다");

        router.loop(1).stop();

        assertFalse(router.isRunning(1), "정지한 샤드의 마켓이 열려 있다");
        assertTrue(router.isRunning(2), "다른 샤드의 정지가 이 마켓을 닫았다");
        assertTrue(router.isRunning(3), "샤드 없는 마켓까지 닫혔다");
        assertFalse(router.isRunning(), "전체 기준은 한 샤드 정지로 닫혀야 한다");
    }
}

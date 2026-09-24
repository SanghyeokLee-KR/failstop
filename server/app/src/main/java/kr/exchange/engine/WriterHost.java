package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;

import java.util.function.IntPredicate;

/**
 * 승격이 라이터에 요구하는 것(ADR 0021 5단계, 파티션 단위는 DB 설계 §5.4-c 7).
 *
 * 승격 경로가 루프에서 쓰는 것은 셋이다. 받을 수 있는 상태인가, 복구한 엔진을 설치하는 것, 설치를 되돌리는 정지다.
 * 그것만 뽑아 두면 루프 하나든 마켓별 라우터든 같은 코드가 돈다.
 *
 * CommandSink 와 같은 이유로 인터페이스를 둔다. 통합 시험 여럿이 PromotionCoordinator 를
 * 루프와 함께 손으로 조립하므로, 생성자 타입을 갈아치우면 그 시험들이 한꺼번에 깨진다.
 */
public interface WriterHost {

    boolean isRunning();

    /**
     * 복구한 엔진을 설치한다.
     *
     * 단일 루프면 그 엔진을 그대로 꽂고, 마켓별 라우터면 그 안의 북을 마켓마다 나눠 담는다.
     * 승격 쪽 코드는 어느 쪽인지 몰라도 된다.
     */
    void install(MatchingEngineImpl recovered);

    /** scope 의 라이터가 모두 도는가. 파티션을 모르는 배선은 전체 판정이다. */
    default boolean isRunning(IntPredicate scope) {
        return isRunning();
    }

    /** 기간과 대상 마켓을 받아 설치한다. 파티션을 모르는 배선은 종전 설치다. */
    default void install(LeaderTerm term, IntPredicate scope, MatchingEngineImpl recovered) {
        install(recovered);
    }

    /** scope 의 라이터를 멈추고 큐를 버린다. 파티션을 모르는 배선은 아무것도 하지 않는다. */
    default void halt(IntPredicate scope) {
    }

    /** 그 마켓 라이터에 설치된 엔진. 생략 설치의 상태 해시 대조에 쓴다. 읽을 수 없는 배선은 null 이다. */
    default MatchingEngineImpl installedEngine(int marketId) {
        return null;
    }

    static WriterHost of(SingleWriterLoop loop) {
        return new WriterHost() {
            @Override
            public boolean isRunning() {
                return loop.isRunning();
            }

            @Override
            public void install(MatchingEngineImpl recovered) {
                loop.installEngine(recovered);
            }
        };
    }

    static WriterHost of(WriterRouter router) {
        return new WriterHost() {
            @Override
            public boolean isRunning() {
                return router.isRunning();
            }

            @Override
            public void install(MatchingEngineImpl recovered) {
                router.installFrom(recovered);
            }

            @Override
            public boolean isRunning(IntPredicate scope) {
                return router.isRunning(scope);
            }

            @Override
            public void install(LeaderTerm term, IntPredicate scope, MatchingEngineImpl recovered) {
                router.install(term, scope, recovered);
            }

            @Override
            public void halt(IntPredicate scope) {
                router.halt(scope);
            }

            @Override
            public MatchingEngineImpl installedEngine(int marketId) {
                ShardSlot slot = router.slotOf(marketId);
                return slot == null || slot.shard() == null ? null : slot.shard().engine();
            }
        };
    }
}

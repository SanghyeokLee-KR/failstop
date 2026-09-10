package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;

/**
 * 승격이 라이터에 요구하는 것(ADR 0021 5단계).
 *
 * 승격 경로가 루프에서 쓰는 것은 둘뿐이다. 받을 수 있는 상태인가와, 복구한 엔진을 설치하는
 * 것이다. 그 둘만 뽑아 두면 루프 하나든 마켓별 라우터든 같은 코드가 돈다.
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
        };
    }
}

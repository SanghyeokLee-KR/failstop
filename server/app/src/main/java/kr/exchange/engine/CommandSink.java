package kr.exchange.engine;

import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;

/**
 * 접수가 명령을 넘기는 곳(ADR 0021 5단계).
 *
 * 왜 이 인터페이스가 필요한가. 종전에는 EngineBridge 가 SingleWriterLoop 를 직접 들었다.
 * 마켓별 라이터로 가면 그 자리가 WriterRouter 로 바뀌는데, 통합 시험 열둘이 스택을 손으로
 * 조립하며 루프를 직접 넘긴다. 필드 타입을 갈아치우면 그 열둘이 한꺼번에 깨진다.
 *
 * 그래서 접수가 실제로 쓰는 두 가지만 인터페이스로 뽑는다. 명령을 넣는 것과 받을 수 있는지
 * 보는 것이다. 루프 하나든 마켓별 라우터든 이 둘을 답할 수 있으므로, 호출측은 어느 쪽인지
 * 몰라도 된다. 배선 전환이 한 줄로 끝나고 시험은 손대지 않는다.
 */
public interface CommandSink {

    boolean offer(OrderCommand command);

    /** 그 마켓의 명령을 받을 라이터가 살아 있는가. 한 마켓 라이터의 정지가 다른 마켓 접수를 닫지 않는다. */
    boolean isRunning(int marketId);

    /** 루프 하나가 전 마켓을 받던 종전 배선. 통합 시험과 단일 라이터 경로가 쓴다. */
    static CommandSink of(SingleWriterLoop loop) {
        return new CommandSink() {
            @Override
            public boolean offer(OrderCommand command) {
                return loop.offer(command);
            }

            @Override
            public boolean isRunning(int marketId) {
                return loop.isRunning();
            }
        };
    }

    /** 마켓별 라이터 배선. 운영이 쓴다. */
    static CommandSink of(WriterRouter router) {
        return new CommandSink() {
            @Override
            public boolean offer(OrderCommand command) {
                return router.offer(command);
            }

            @Override
            public boolean isRunning(int marketId) {
                return router.isRunning(marketId);
            }
        };
    }
}

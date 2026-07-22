package kr.exchange.engine.core;

import java.math.BigDecimal;
import java.util.List;

/**
 * 단일 마켓 오더북. 구현은 순수 인메모리 자료구조여야 하며 상태 변경은 단일 라이터 스레드에서만 일어난다.
 */
public interface OrderBook {

    int marketId();

    /**
     * 명령 하나를 적용하고 발생한 이벤트를 순서대로 반환한다.
     * 반환 순서까지 결정론의 일부다. 같은 명령열은 같은 이벤트열을 낳아야 한다.
     */
    List<EngineEvent> apply(OrderCommand command);

    /** 열린 최우선 매수 호가. 없으면 null. */
    BigDecimal bestBid();

    /** 열린 최우선 매도 호가. 없으면 null. */
    BigDecimal bestAsk();

    /** 스냅샷 직렬화 대상 상태의 체크섬. 재생 결정론 비교(fp_replay/fp_live)에 쓰인다. */
    long stateChecksum();
}

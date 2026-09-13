package kr.exchange.engine.core;

import java.util.List;

/**
 * 전 마켓 매칭엔진 파사드. 마켓별 OrderBook을 소유하고 명령을 해당 북에 라우팅한다.
 * 채번 권위가 전역 단일이므로 인스턴스도 전역 단일이다(DB 설계 v2.5 §3.1 6.6 리스 규칙).
 */
public interface MatchingEngine {

    /**
     * 명령 하나를 적용한다. 호출 스레드는 SingleWriterLoop의 라이터 스레드 하나뿐이어야 한다.
     */
    List<EngineEvent> apply(OrderCommand command);

    /** 그 마켓의 적용 커서. 커서가 없으면 스칼라 스냅샷 바닥. 이 값 이하인 그 마켓 명령은 적용하지 않는다. */
    long appliedSeq(int marketId);

    /** 전 마켓 적용 seq의 최댓값. 조밀 전역 축에서는 마지막으로 적용한 command seq와 같다. */
    long lastAppliedSeq();

    /** 전 마켓 정준 상태 해시. 스냅샷 무결 검증과 결정론 속성 테스트의 비교 축이다. */
    long stateChecksum();
}

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

    /** 마지막으로 적용한 command seq. 재생 시 seq > lastAppliedSeq만 적용해 이중 적용을 막는다. */
    long lastAppliedSeq();

    /** 전 마켓 정준 상태 해시. 재생 결정론 비교(fp_replay/fp_live)의 입력이다. */
    long stateChecksum();
}

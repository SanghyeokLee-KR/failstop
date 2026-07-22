package kr.exchange.journal;

import kr.exchange.engine.core.EngineEvent;

import java.util.List;

/**
 * 재생 결과 요약.
 *
 * @param lastAppliedSeq  재생 종점 command seq
 * @param replayedCommands 적용한 명령 수
 * @param verifiedEvents  event_journal 기존 행과 대조 일치한 이벤트 수
 * @param pendingEvents   저널에 없는 재방출 이벤트(크래시 창 분량). event_seq 미부여 상태이며
 *                        기동 복구(EngineRecovery)가 event_journal에 재적재한다(§5.5 ②).
 *                        유형별 재정산을 이 적재에 원자 결합하는 것은 M1-3 몫이다
 */
public record ReplayResult(
        long lastAppliedSeq,
        long replayedCommands,
        long verifiedEvents,
        List<EngineEvent> pendingEvents
) {
}

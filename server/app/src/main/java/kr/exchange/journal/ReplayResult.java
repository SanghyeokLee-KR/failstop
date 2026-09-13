package kr.exchange.journal;

import kr.exchange.engine.core.EngineEvent;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 재생 결과 요약.
 *
 * @param lastAppliedSeq   재생 종점 command seq. 마켓 진입점에서는 그 마켓의 커서다
 * @param replayedCommands 적용한 명령 수
 * @param verifiedEvents   event_journal 기존 행과 대조 일치한 이벤트 수
 * @param pendingBatches   저널에 없는 재방출 이벤트(크래시 창 분량)를 원인 명령 단위로 묶어 seq 오름차순으로
 *                         둔 것. event_seq 미부여 상태이며 기동 복구(EngineRecovery)가 event_journal에
 *                         재적재한다(§5.5 ②)
 */
public record ReplayResult(
        long lastAppliedSeq,
        long replayedCommands,
        long verifiedEvents,
        List<PendingBatch> pendingBatches
) {

    /**
     * 한 명령이 낸 재적재 대상 이벤트. 이벤트는 명령 단위로 원자 커밋되므로 재적재도 이 단위로 한다.
     *
     * @param createdAt 원인 명령의 command_journal.created_at. 마켓 큐 병합 키다(ADR 0022 5-3)
     */
    public record PendingBatch(long seq, OffsetDateTime createdAt, List<EngineEvent> events) {
    }

    /** pendingBatches 를 순서대로 편 것. */
    public List<EngineEvent> pendingEvents() {
        return pendingBatches.stream().flatMap(batch -> batch.events().stream()).toList();
    }
}

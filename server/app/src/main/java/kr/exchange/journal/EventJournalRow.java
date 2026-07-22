package kr.exchange.journal;

import java.time.OffsetDateTime;

/** event_journal 한 행. 재생 대조·검증 조회 결과. */
public record EventJournalRow(
        long eventSeq,
        long sourceCmdSeq,
        int eventOrdinal,
        String eventType,
        String payloadJson,
        short payloadSchemaVersion,
        long fencingEpoch,
        OffsetDateTime createdAt
) {
}

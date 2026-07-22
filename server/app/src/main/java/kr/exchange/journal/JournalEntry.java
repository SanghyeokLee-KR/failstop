package kr.exchange.journal;

import java.time.OffsetDateTime;

/** command_journal 한 행. catch-up·재생 조회 결과. */
public record JournalEntry(
        long seq,
        int marketId,
        CommandDraft.CommandType type,
        long userId,
        String clientOrderId,
        long assignedOrderId,
        long engineTs,
        long tieBreak,
        String payloadJson,
        long fencingToken,
        long checksum,
        OffsetDateTime createdAt
) {
}

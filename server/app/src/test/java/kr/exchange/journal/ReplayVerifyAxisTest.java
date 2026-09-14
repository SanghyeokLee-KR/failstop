package kr.exchange.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대조 전용 재생이 판정 대상 0 건으로 통과하지 않는지 본다. 마켓 진입점은 이 성질을 마켓마다 가져야 한다.
 *
 * 종전에는 호출자가 이벤트 커서 시작점을 long 으로 건넸다. 팔로워 경로가 그 자리에
 * max(event_seq) 를 넘겼고, V36 이 event_seq 를 마켓 대역 파생으로 바꾼 뒤 그 값이 명령 seq
 * 보다 몇 자릿수 커져 커서가 첫 조회에서 소진됐다. 재생이 만든 이벤트가 전부 pending 으로
 * 떨어지는데 아무도 보지 않아 대조 건수가 항상 0 이었다. 팔로워가 리더와 갈려도 알 수 없었다.
 *
 * DB 를 쓰지 않는다. 판정 대상이 미대조가 드러나는지이지 저장 내용이 아니다.
 */
class ReplayVerifyAxisTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 이벤트를 한 건도 돌려주지 않는다. 커서가 죽은 상태를 그대로 만든다. */
    private static final class BlindEventReader extends EventJournalReader {
        BlindEventReader() {
            super(null);
        }

        @Override
        public List<EventJournalRow> readAfter(long cmdSeq, int ordinal, int limit) {
            return List.of();
        }

        @Override
        public List<EventJournalRow> readAfterForMarket(long cmdSeq, int ordinal, int marketId,
                                                        long legacyBound, int limit) {
            return List.of();
        }
    }

    /** 마켓별 메모리 이벤트 저널. 실제 판독과 같은 (source_cmd_seq, event_ordinal) keyset 으로 자른다. */
    private static final class MemoryEventReader extends EventJournalReader {
        private final Map<Integer, List<EventJournalRow>> rowsByMarket;
        private final long legacyBound;
        // 재생이 판독에 넘긴 레거시 경계. 0 을 넘기면 V36 이전 행을 못 읽는데 메모리 판독은 그것을 드러내지 못한다.
        final List<Long> legacyBoundsSeen = new ArrayList<>();

        MemoryEventReader(Map<Integer, List<EventJournalRow>> rowsByMarket, long legacyBound) {
            super(null);
            this.rowsByMarket = rowsByMarket;
            this.legacyBound = legacyBound;
        }

        @Override
        public long legacyBound() {
            return legacyBound;
        }

        @Override
        public List<EventJournalRow> readAfterForMarket(long cmdSeq, int ordinal, int marketId,
                                                        long legacyBound, int limit) {
            legacyBoundsSeen.add(legacyBound);
            List<EventJournalRow> out = new ArrayList<>();
            for (EventJournalRow row : rowsByMarket.getOrDefault(marketId, List.of())) {
                boolean after = row.sourceCmdSeq() > cmdSeq
                        || (row.sourceCmdSeq() == cmdSeq && row.eventOrdinal() > ordinal);
                if (after && out.size() < limit) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 메모리 명령 저널. 상한과 시작점을 실제 질의와 같은 규약으로 자른다. */
    private static final class MemoryCommandReader extends CommandJournalReader {
        private final List<JournalEntry> entries;

        MemoryCommandReader(List<JournalEntry> entries) {
            super(null);
            this.entries = entries;
        }

        @Override
        public long maxSeq() {
            return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).seq();
        }

        @Override
        public List<JournalEntry> readRange(long lastAppliedSeq, long upToSeq, int limit) {
            return read(lastAppliedSeq, -1, upToSeq, limit);
        }

        @Override
        public List<JournalEntry> readAfterForMarket(long lastAppliedSeq, int marketId,
                                                     long upToSeq, int limit) {
            return read(lastAppliedSeq, marketId, upToSeq, limit);
        }

        private List<JournalEntry> read(long lastAppliedSeq, int marketId, long upToSeq, int limit) {
            List<JournalEntry> out = new ArrayList<>();
            for (JournalEntry e : entries) {
                if (e.seq() <= lastAppliedSeq || (marketId >= 0 && e.marketId() != marketId)) {
                    continue;
                }
                if (upToSeq >= 0 && e.seq() > upToSeq) {
                    break;
                }
                out.add(e);
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        }
    }

    private static ReplayService blind(int commands) {
        return new ReplayService(new MemoryCommandReader(entries(commands)),
                new BlindEventReader(), new CommandPayloadMapper(JSON), JSON, null);
    }

    private static List<JournalEntry> entries(int count) {
        List<JournalEntry> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            out.add(entry(i, 1));
        }
        return out;
    }

    private static JournalEntry entry(long seq, int marketId) {
        String payload = "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                + "\"price\":\"100\",\"quantity\":\"1\"}";
        CommandDraft draft = new CommandDraft(marketId, CommandDraft.CommandType.NEW, 1L,
                "c-" + seq, seq, 1_721_600_000_000L + seq, seq, payload);
        return new JournalEntry(seq, draft.marketId(), draft.type(), draft.userId(),
                draft.clientOrderId(), draft.assignedOrderId(), draft.engineTs(),
                draft.tieBreak(), draft.payloadJson(), 1L,
                JournalChecksum.of(seq, draft),
                // 접수 시각은 engine_ts 와 따로 흐른다. 같게 두면 배치가 어느 쪽을 싣는지 가르지 못한다.
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(1_700_000_000_000L - seq * 1_000), ZoneOffset.UTC));
    }

    /** 명령을 따로 적용해 저널에 적혀 있었을 이벤트 행을 만든다. 대조가 실제로 일치하는 짝이다. */
    private static List<EventJournalRow> journaled(List<JournalEntry> entries) {
        MatchingEngineImpl scratch = new MatchingEngineImpl();
        CommandPayloadMapper mapper = new CommandPayloadMapper(JSON);
        List<EventJournalRow> rows = new ArrayList<>();
        for (JournalEntry entry : entries) {
            for (EngineEvent event : scratch.apply(mapper.toCommand(entry))) {
                rows.add(new EventJournalRow(rows.size() + 1, event.sourceCmdSeq(), event.eventOrdinal(),
                        event.type().name(), EventPayloads.json(event, JSON), (short) 1, 1L,
                        OffsetDateTime.ofInstant(Instant.ofEpochMilli(event.engineTs()), ZoneOffset.UTC)));
            }
        }
        return rows;
    }

    @Test
    void 대조가_한_건도_안_되면_던진다() {
        ReplayService replay = blind(3);
        MatchingEngineImpl engine = new MatchingEngineImpl();

        ReplayMismatchException e = assertThrows(ReplayMismatchException.class,
                () -> replay.verifyTo(engine, new JournalCheckpoint(3)));

        assertTrue(e.getMessage().contains("pending"),
                "미대조가 사유로 드러나지 않는다: " + e.getMessage());
    }

    @Test
    void 상한이_시작점과_같으면_아무것도_읽지_않는다() {
        ReplayService replay = blind(3);
        MatchingEngineImpl engine = new MatchingEngineImpl();

        ReplayResult result = replay.verifyTo(engine, new JournalCheckpoint(0));

        assertEquals(0, result.replayedCommands(), "상한 0 인데 명령을 읽었다");
    }

    @Test
    void unverifiedMarketThrowsEvenIfOtherMarketVerified() {
        List<JournalEntry> entries = List.of(entry(1, 1), entry(2, 2), entry(3, 1), entry(4, 2), entry(5, 1));
        List<EventJournalRow> market1Rows =
                journaled(entries.stream().filter(e -> e.marketId() == 1).toList());
        MemoryEventReader events = new MemoryEventReader(Map.of(1, market1Rows), 3);
        ReplayService replay = new ReplayService(new MemoryCommandReader(entries),
                events, new CommandPayloadMapper(JSON), JSON, null);
        JournalCheckpoints checkpoints = new JournalCheckpoints(Map.of(1, 5L, 2, 4L), 3);
        MatchingEngineImpl engine = new MatchingEngineImpl();

        ReplayResult verified = replay.verifyToMarket(engine, 1, checkpoints);

        assertFalse(market1Rows.isEmpty(), "마켓 1 저널 행이 없어 대조가 판정되지 않는다");
        assertEquals(3, verified.replayedCommands());
        assertEquals(market1Rows.size(), verified.verifiedEvents(), "마켓 1 대조가 전량이 아니다");
        assertEquals(5, engine.appliedSeq(1));
        assertFalse(events.legacyBoundsSeen.isEmpty());
        assertTrue(events.legacyBoundsSeen.stream().allMatch(bound -> bound == 3),
                "대조 판독에 체크포인트의 레거시 경계가 넘어가지 않았다: " + events.legacyBoundsSeen);

        ReplayMismatchException e = assertThrows(ReplayMismatchException.class,
                () -> replay.verifyToMarket(engine, 2, checkpoints));

        assertTrue(e.getMessage().contains("마켓 2") && e.getMessage().contains("pending"),
                "마켓 2 미대조가 사유로 드러나지 않는다: " + e.getMessage());
    }

    @Test
    void rebuildMarketPassesLegacyBoundToEventReader() {
        List<JournalEntry> entries = List.of(entry(1, 1), entry(2, 2), entry(3, 1));
        List<EventJournalRow> market1Rows =
                journaled(entries.stream().filter(e -> e.marketId() == 1).toList());
        MemoryEventReader events = new MemoryEventReader(Map.of(1, market1Rows), 2);
        ReplayService replay = new ReplayService(new MemoryCommandReader(entries),
                events, new CommandPayloadMapper(JSON), JSON, null);

        ReplayResult rebuilt = replay.rebuildMarket(new MatchingEngineImpl(), 1);

        assertEquals(market1Rows.size(), rebuilt.verifiedEvents(), "마켓 1 대조가 전량이 아니다");
        assertFalse(events.legacyBoundsSeen.isEmpty());
        assertTrue(events.legacyBoundsSeen.stream().allMatch(bound -> bound == 2),
                "상한 없는 마켓 재생이 판독기의 레거시 경계를 넘기지 않았다: " + events.legacyBoundsSeen);
    }

    @Test
    void marketTailBatchesCarryCommandSeqAndCreatedAt() {
        List<JournalEntry> entries = List.of(entry(1, 1), entry(2, 2), entry(3, 1));
        ReplayService replay = new ReplayService(new MemoryCommandReader(entries),
                new BlindEventReader(), new CommandPayloadMapper(JSON), JSON, null);
        MatchingEngineImpl engine = new MatchingEngineImpl();

        ReplayResult tail = replay.replayTailMarket(engine, 1, 3);

        assertEquals(List.of(1L, 3L),
                tail.pendingBatches().stream().map(ReplayResult.PendingBatch::seq).toList());
        assertEquals(List.of(entries.get(0).createdAt(), entries.get(2).createdAt()),
                tail.pendingBatches().stream().map(ReplayResult.PendingBatch::createdAt).toList());
        assertTrue(tail.pendingBatches().stream().allMatch(batch -> !batch.events().isEmpty()
                        && batch.events().stream().allMatch(ev -> ev.sourceCmdSeq() == batch.seq())),
                "배치가 원인 명령 단위로 묶이지 않았다");
        assertEquals(0, engine.appliedSeq(2), "다른 마켓 명령을 적용했다");
        assertThrows(IllegalArgumentException.class, () -> replay.replayTailMarket(engine, 1, -1));
    }
}

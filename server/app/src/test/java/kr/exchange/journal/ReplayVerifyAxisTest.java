package kr.exchange.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.core.MatchingEngineImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대조 전용 재생이 판정 대상 0 건으로 통과하지 않는지 본다.
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
            List<JournalEntry> out = new ArrayList<>();
            for (JournalEntry e : entries) {
                if (e.seq() <= lastAppliedSeq) {
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
            out.add(entry(i));
        }
        return out;
    }

    private static JournalEntry entry(long seq) {
        String payload = "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                + "\"price\":\"100\",\"quantity\":\"1\"}";
        CommandDraft draft = new CommandDraft(1, CommandDraft.CommandType.NEW, 1L,
                "c-" + seq, seq, 1_721_600_000_000L + seq, seq, payload);
        return new JournalEntry(seq, draft.marketId(), draft.type(), draft.userId(),
                draft.clientOrderId(), draft.assignedOrderId(), draft.engineTs(),
                draft.tieBreak(), draft.payloadJson(), 1L,
                JournalChecksum.of(seq, draft),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(draft.engineTs()), ZoneOffset.UTC));
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
    void 크래시_창_재생은_전량_pending_이_정상이다() {
        // replayTail 은 대조할 상대가 없는 구간 전용이다. 같은 눈먼 리더에서도 던지지 않는다.
        ReplayService replay = blind(3);
        MatchingEngineImpl engine = new MatchingEngineImpl();

        ReplayResult result = replay.replayTail(engine, 3);

        assertEquals(3, result.replayedCommands(), "크래시 창 명령을 적용하지 않았다");
        assertEquals(0, result.verifiedEvents());
        assertTrue(result.pendingEvents().size() >= 3,
                "크래시 창 방출이 pending 으로 나오지 않았다");
    }

    @Test
    void 상한이_시작점과_같으면_아무것도_읽지_않는다() {
        ReplayService replay = blind(3);
        MatchingEngineImpl engine = new MatchingEngineImpl();

        ReplayResult result = replay.verifyTo(engine, new JournalCheckpoint(0));

        assertEquals(0, result.replayedCommands(), "상한 0 인데 명령을 읽었다");
    }
}

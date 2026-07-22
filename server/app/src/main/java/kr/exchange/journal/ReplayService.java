package kr.exchange.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngine;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.ledger.Fees;
import kr.exchange.ledger.MarketCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 기동 재생(DB 설계 §5.5 STEP2, M1-2 범위: 스냅샷 없이 seq 0부터 전역 단일 패스).
 *
 * command_journal을 seq 오름차순으로 재적용해 오더북을 재구성하면서, 재방출 이벤트를
 * event_journal 기존 행과 순서대로 대조한다(prefix 일치 검증). 단일 라이터는 명령 처리를
 * 순차 완결하므로 기존 이벤트는 재방출 스트림의 prefix여야 하며, 불일치는 비결정성
 * 또는 저널 손상이라 fail-fast한다. prefix 너머(크래시 창) 이벤트는 pending으로 반환하고,
 * 재적재는 호출측(EngineRecovery)이 기동 완료 전에 수행한다.
 *
 * 대조 축: event_seq 연속성, source_cmd_seq, event_ordinal, event_type,
 * payload(canonical JSON 바이트), created_at(engine_ts 파생). fencing_epoch는 리더 이력
 * 값이라 재생으로 재현되는 값이 아니므로 대조에서 제외한다.
 */
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    private static final int BATCH = 500;

    private final CommandJournalReader commandReader;
    private final EventJournalReader eventReader;
    private final CommandPayloadMapper mapper;
    private final ObjectMapper objectMapper;
    private final MarketCatalog catalog;

    public ReplayService(CommandJournalReader commandReader, EventJournalReader eventReader,
                         CommandPayloadMapper mapper, ObjectMapper objectMapper,
                         MarketCatalog catalog) {
        this.commandReader = commandReader;
        this.eventReader = eventReader;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.catalog = catalog;
    }

    public ReplayResult rebuild(MatchingEngine engine) {
        JournalEventCursor cursor = new JournalEventCursor();
        List<EngineEvent> pending = new ArrayList<>();
        long replayedCommands = 0;
        long verifiedEvents = 0;

        while (true) {
            List<JournalEntry> batch = commandReader.readAfter(engine.lastAppliedSeq(), BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (JournalEntry entry : batch) {
                verifyChecksum(entry);
                OrderCommand command = mapper.toCommand(entry);
                for (EngineEvent event : engine.apply(command)) {
                    EventJournalRow row = cursor.next();
                    if (row == null) {
                        pending.add(event);
                        continue;
                    }
                    if (!pending.isEmpty()) {
                        // prefix 성질 위반: 이벤트 없는 명령 뒤에 이벤트 있는 명령이 나왔다.
                        throw new ReplayMismatchException(
                                "event_journal prefix 위반: event_seq=" + row.eventSeq());
                    }
                    verifyMatch(row, event, entry);
                    verifiedEvents++;
                }
                replayedCommands++;
            }
        }
        if (cursor.next() != null) {
            throw new ReplayMismatchException(
                    "재생이 생성하지 않은 이벤트가 저널에 남아 있다: event_seq="
                            + cursor.current().eventSeq());
        }
        log.info("재생 완료: lastAppliedSeq={}, commands={}, verified={}, pending={}",
                engine.lastAppliedSeq(), replayedCommands, verifiedEvents, pending.size());
        return new ReplayResult(engine.lastAppliedSeq(), replayedCommands, verifiedEvents, pending);
    }

    private void verifyChecksum(JournalEntry entry) {
        CommandDraft draft = new CommandDraft(entry.marketId(), entry.type(), entry.userId(),
                entry.clientOrderId(), entry.assignedOrderId(), entry.engineTs(),
                entry.tieBreak(), entry.payloadJson());
        if (JournalChecksum.of(entry.seq(), draft) != entry.checksum()) {
            throw new ReplayMismatchException("command_journal 행 손상: seq=" + entry.seq());
        }
    }

    private void verifyMatch(EventJournalRow row, EngineEvent event, JournalEntry entry) {
        if (row.sourceCmdSeq() != event.sourceCmdSeq()
                || row.eventOrdinal() != event.eventOrdinal()
                || !row.eventType().equals(event.type().name())) {
            throw new ReplayMismatchException("이벤트 불일치: event_seq=" + row.eventSeq()
                    + ", 저널=(" + row.sourceCmdSeq() + "," + row.eventOrdinal() + "," + row.eventType()
                    + "), 재생=(" + event.sourceCmdSeq() + "," + event.eventOrdinal()
                    + "," + event.type() + ")");
        }
        if (row.createdAt().toInstant().toEpochMilli() != event.engineTs()) {
            throw new ReplayMismatchException("created_at이 engine_ts 파생값과 다르다: event_seq="
                    + row.eventSeq());
        }
        String expected = JournalChecksum.canonicalJson(rebuildPayload(event, entry));
        String actual = JournalChecksum.canonicalJson(row.payloadJson());
        if (!expected.equals(actual)) {
            throw new ReplayMismatchException("payload 불일치: event_seq=" + row.eventSeq()
                    + ", 저널=" + actual + ", 재생=" + expected);
        }
    }

    /**
     * 정산 append와 동일 빌더·동일 수수료 산식으로 payload를 재구성한다(EventPayloads·Fees
     * 단일 지점). 요율은 명령 payload 박제값만 쓴다(§5.2 수수료 박제 규칙). 요율 미박제
     * 명령(M1-2 이전 저널)은 자리표시 "0"과 동일 표기가 되어 하위 호환이다.
     */
    private String rebuildPayload(EngineEvent event, JournalEntry entry) {
        if (event.type() != EngineEvent.EventType.TRADE) {
            return EventPayloads.json(event, objectMapper);
        }
        CommandPayloadMapper.Extras extras = mapper.extras(entry);
        if (Fees.zero(extras.makerFeeRate()) && Fees.zero(extras.takerFeeRate())) {
            return EventPayloads.json(event, objectMapper);
        }
        Fees.TradeFees fees = Fees.of(event, extras.makerFeeRate(), extras.takerFeeRate(),
                catalog.meta(event.marketId()));
        return EventPayloads.json(event, objectMapper,
                Fees.text(fees.makerFee()), Fees.text(fees.takerFee()));
    }

    /** event_journal 순차 커서. gapless 검증을 겸한다. */
    private final class JournalEventCursor {
        private List<EventJournalRow> buffer = List.of();
        private int index;
        private long lastSeq;
        private boolean exhausted;
        private EventJournalRow current;

        EventJournalRow next() {
            if (exhausted) {
                return null;
            }
            if (index >= buffer.size()) {
                buffer = eventReader.readAfter(lastSeq, BATCH);
                index = 0;
                if (buffer.isEmpty()) {
                    exhausted = true;
                    return null;
                }
            }
            EventJournalRow row = buffer.get(index++);
            if (lastSeq != 0 && row.eventSeq() != lastSeq + 1) {
                throw new ReplayMismatchException("event_seq 결번: " + lastSeq + " 다음이 "
                        + row.eventSeq());
            }
            lastSeq = row.eventSeq();
            current = row;
            return row;
        }

        EventJournalRow current() {
            return current;
        }
    }
}

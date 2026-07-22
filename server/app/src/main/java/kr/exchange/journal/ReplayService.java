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
    // 재생 대조 기준. append 경로(EventJournalAppender·SettlementService)와 같은 값이어야 한다.
    private static final short PAYLOAD_SCHEMA_VERSION = 1;

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
        return rebuild(engine, 0);
    }

    /**
     * 스냅샷 경계 tail 재생(복구 RTO 설계 §3-b). eventStartSeq는 스냅샷 워터마크 W이며,
     * 명령 커서는 engine.lastAppliedSeq() 초과분(= 스냅샷 로드로 세운 snapshot seq 초과분),
     * 이벤트 커서는 W+1부터 대조한다. eventStartSeq=0이면 seq 0 전량 재생(기존 경로).
     *
     * W는 호출측이 event_journal에서 max(event_seq) WHERE source_cmd_seq <= snapshot seq로
     * 유도한다. event_seq는 source_cmd_seq 오름차순 gapless 배정이라 유도값이 캡처 시점 W와
     * 정확히 일치하고, 커서를 W로 두면 첫 tail 이벤트(W+1)를 기대해 prefix가 정합한다.
     */
    public ReplayResult rebuild(MatchingEngine engine, long eventStartSeq) {
        JournalEventCursor cursor = new JournalEventCursor(eventStartSeq);
        List<EngineEvent> pending = new ArrayList<>();
        long replayedCommands = 0;
        long verifiedEvents = 0;

        while (true) {
            List<JournalEntry> batch = commandReader.readAfter(engine.lastAppliedSeq(), BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (JournalEntry entry : batch) {
                JournalChecksum.verify(entry);
                OrderCommand command = mapper.toCommand(entry);
                for (EngineEvent event : engine.apply(command)) {
                    EventJournalRow row = cursor.next();
                    if (row == null) {
                        pending.add(event);
                        continue;
                    }
                    if (!pending.isEmpty()) {
                        // 방어적 불변식. 커서가 한 번 null을 반환하면 exhausted라 이후 next()는
                        // 계속 null(pending 경로)이므로 현재 커서 구현에서는 도달하지 않는다.
                        // 커서 변경 시 prefix 성질 위반을 조용히 넘기지 않도록 남긴다.
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

    private void verifyMatch(EventJournalRow row, EngineEvent event, JournalEntry entry) {
        if (row.payloadSchemaVersion() != PAYLOAD_SCHEMA_VERSION) {
            throw new ReplayMismatchException("payload_schema_version 불일치: event_seq="
                    + row.eventSeq() + ", 저널=" + row.payloadSchemaVersion()
                    + ", 기대=" + PAYLOAD_SCHEMA_VERSION);
        }
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

        // lastSeq를 스냅샷 워터마크 W로 초기화한다. readAfter(W)가 W+1부터 읽고 선두 결번
        // 검사(expected = lastSeq==0 ? 1 : lastSeq+1)도 W+1을 기대해 tail prefix가 정합한다.
        JournalEventCursor(long eventStartSeq) {
            this.lastSeq = eventStartSeq;
        }

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
            // 선두 결번도 검출한다. event_seq는 1부터 gapless라(journal_counters 시드=1)
            // 첫 행이 1이 아니면 앞쪽 유실이다. lastSeq==0을 검사에서 빼면 선두 결번을 놓친다.
            long expected = lastSeq == 0 ? 1 : lastSeq + 1;
            if (row.eventSeq() != expected) {
                throw new ReplayMismatchException("event_seq 결번: 기대 " + expected + ", 실제 "
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

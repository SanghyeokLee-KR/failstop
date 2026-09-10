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
 *
 * 상한(JournalCheckpoint)을 받는 변형이 있다. 상한 없이 재생하면 다른 노드가 리더로서 계속 쓰는
 * 동안 "명령은 있는데 이벤트가 아직 없는" 구간이 항상 남고, 재생하는 쪽이 그것을 자기 크래시
 * 창으로 읽어 리더 자격을 요구한다. 그러면 유효한 타 리더가 있는 노드는 기동을 거부한다.
 * 2026-09-08 두 노드 실측이 그 경로다. 상한을 걸면 pending 이 구조적으로 비어 리더 자격이
 * 필요 없고, 상한 초과분은 팔로워 catch-up 이 따라잡는다.
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
     * 지금 재생할 수 있는 상한. 이벤트가 전부 적재된 마지막 명령 지점이다.
     *
     * 여기 두는 이유는 호출측에 리더를 새로 주입하지 않기 위해서다. 이 클래스가 두 저널 리더를
     * 이미 들고 있고, 상한은 재생의 성질이지 복구 절차의 성질이 아니다.
     */
    public JournalCheckpoint checkpoint() {
        return eventReader.checkpoint();
    }

    /** 명령 저널 head. 상한 초과분이 있는지 판정에 쓴다. */
    public long journalHead() {
        return commandReader.maxSeq();
    }

    /** 주어진 명령까지의 이벤트 워터마크. 이어서 재생할 때 이벤트 커서 시작점이다. */
    public long watermarkAt(long commandSeq) {
        return eventReader.watermark(commandSeq);
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
        return rebuild(engine, eventStartSeq, null);
    }

    /**
     * 상한을 건 재생. bound 가 null 이면 상한 없이 읽는다(종전 경로).
     *
     * 명령과 이벤트를 같은 상한으로 묶는 것이 핵심이다. 명령만 상한하고 이벤트를 끝까지 읽으면
     * 재생이 만들지 않은 이벤트를 만나 불일치로 오판한다.
     */
    public ReplayResult rebuild(MatchingEngine engine, long eventStartSeq, JournalCheckpoint bound) {
        // 음수가 '상한 없음' 이다. 0 은 '안정 구간이 없다' 라 상한으로서 유효한 값이다.
        long commandBound = bound == null ? -1 : bound.commandSeq();
        // 이벤트 커서도 명령 축 상한을 쓴다. 종전에는 bound.eventSeq() 였으나 커서 축이
        // (source_cmd_seq, event_ordinal) 로 바뀌어 같은 축의 상한을 쓰는 것이 맞다.
        // 둘을 같은 상한으로 묶는다는 성질은 그대로다.
        JournalEventCursor cursor = new JournalEventCursor(eventStartSeq, commandBound);
        List<EngineEvent> pending = new ArrayList<>();
        long replayedCommands = 0;
        long verifiedEvents = 0;

        while (true) {
            List<JournalEntry> batch =
                    commandReader.readRange(engine.lastAppliedSeq(), commandBound, BATCH);
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
        // 상한 있는 재생은 팔로워 catch-up 이 초마다 부른다. 그 경로까지 INFO 로 남기면 로그가
        // 초당 한 줄씩 쌓인다. 기동 요약은 호출측(EngineRecovery)이 따로 남긴다.
        if (bound == null) {
            log.info("재생 완료: lastAppliedSeq={}, commands={}, verified={}, pending={}",
                    engine.lastAppliedSeq(), replayedCommands, verifiedEvents, pending.size());
        } else if (replayedCommands > 0) {
            log.debug("재생 완료(상한 {}): lastAppliedSeq={}, commands={}, verified={}, pending={}",
                    commandBound, engine.lastAppliedSeq(), replayedCommands, verifiedEvents,
                    pending.size());
        }
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

    /**
     * 한 마켓만 재생한다(ADR 0021 4단계 shadow 비교).
     *
     * 전역 재생과 같은 대조를 마켓 축에서 한다. 명령은 그 마켓 것만 seq 오름차순으로,
     * 이벤트도 그 마켓 것만 (source_cmd_seq, event_ordinal) 오름차순으로 읽어 1 대 1 로 짝짓는다.
     *
     * seq 연속성을 검사하지 않는다. seq 가 전역 채번이라 한 마켓의 스트림에는 구멍이
     * 뚫려 있고 그 구멍은 다른 마켓의 명령이지 결번이 아니다. 마켓 안의 순서만 있으면
     * 재생이 성립한다는 것이 이 설계의 전제이고, 이 메서드가 그 전제를 실증한다.
     *
     * 엔진은 비어 있어야 한다. 이 마켓의 북 하나만 채워져 나온다. 전역 재생이 만든
     * 같은 마켓의 북과 바이트가 같아야 한다. 그 대조가 shadow 비교다.
     */
    public ReplayResult rebuildMarket(MatchingEngine engine, int marketId) {
        MarketEventCursor cursor = new MarketEventCursor(marketId);
        List<EngineEvent> pending = new ArrayList<>();
        long replayedCommands = 0;
        long verifiedEvents = 0;

        while (true) {
            List<JournalEntry> batch = commandReader.readAfterForMarket(
                    engine.lastAppliedSeq(), marketId, -1, BATCH);
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
                        throw new ReplayMismatchException(
                                "마켓 " + marketId + " prefix 위반: event_seq=" + row.eventSeq());
                    }
                    verifyMatch(row, event, entry);
                    verifiedEvents++;
                }
                replayedCommands++;
            }
        }
        if (cursor.next() != null) {
            throw new ReplayMismatchException(
                    "마켓 " + marketId + " 재생이 생성하지 않은 이벤트가 저널에 남았다: event_seq="
                            + cursor.current().eventSeq());
        }
        log.info("마켓 재생 완료: market={}, commands={}, verified={}, pending={}",
                marketId, replayedCommands, verifiedEvents, pending.size());
        return new ReplayResult(engine.lastAppliedSeq(), replayedCommands, verifiedEvents, pending);
    }

    /** 한 마켓의 event_journal 커서. 축은 전역 커서와 같고 읽기에 마켓 조건만 더 붙는다. */
    private final class MarketEventCursor {
        private final int marketId;
        private List<EventJournalRow> buffer = List.of();
        private int index;
        private long lastCmdSeq;
        private int lastOrdinal = -1;
        private boolean exhausted;
        private EventJournalRow current;

        MarketEventCursor(int marketId) {
            this.marketId = marketId;
        }

        EventJournalRow next() {
            if (exhausted) {
                return null;
            }
            if (index >= buffer.size()) {
                buffer = eventReader.readAfterForMarket(lastCmdSeq, lastOrdinal, marketId, BATCH);
                index = 0;
                if (buffer.isEmpty()) {
                    exhausted = true;
                    return null;
                }
            }
            EventJournalRow row = buffer.get(index++);
            lastCmdSeq = row.sourceCmdSeq();
            lastOrdinal = row.eventOrdinal();
            current = row;
            return row;
        }

        EventJournalRow current() {
            return current;
        }
    }

    /**
     * event_journal 순차 커서. 축은 (source_cmd_seq, event_ordinal) 이다.
     *
     * V36 이전에는 event_seq 축이었고 그 값의 연속성으로 유실을 잡았다. event_seq 가
     * marketId 대역 파생이 되면서 그 축이 명령 순서와 갈렸으므로 축을 옮겼다.
     * 유실 검출은 사라지지 않는다. 재생이 만든 이벤트와 저널 행을 verifyMatch 가
     * source_cmd_seq 와 event_ordinal 로 대조하므로, 빠진 행은 그 자리에서 불일치로 잡힌다.
     * 명령 자체의 결번은 C05 가 전역 축에서 본다.
     */
    private final class JournalEventCursor {
        private List<EventJournalRow> buffer = List.of();
        private int index;
        private long lastCmdSeq;
        private int lastOrdinal;
        // 음수면 상한 없음. 0 이상이면 이 source_cmd_seq 까지만 읽는다. 명령 상한과 짝이다.
        private final long upperCmdSeq;
        private boolean exhausted;
        private EventJournalRow current;

        // 커서를 스냅샷 명령 워터마크로 초기화한다. 그 명령까지의 이벤트는 스냅샷에 이미
        // 반영돼 있으므로 그 다음 명령의 첫 이벤트부터 대조한다.
        JournalEventCursor(long startCmdSeq, long upperCmdSeq) {
            this.lastCmdSeq = startCmdSeq;
            this.lastOrdinal = Integer.MAX_VALUE;
            this.upperCmdSeq = upperCmdSeq;
        }

        EventJournalRow next() {
            if (exhausted) {
                return null;
            }
            if (index >= buffer.size()) {
                buffer = eventReader.readAfter(lastCmdSeq, lastOrdinal, BATCH);
                index = 0;
                if (buffer.isEmpty()) {
                    exhausted = true;
                    return null;
                }
            }
            EventJournalRow row = buffer.get(index++);
            if (upperCmdSeq >= 0 && row.sourceCmdSeq() > upperCmdSeq) {
                // 상한 너머다. 이번 재생의 대조 대상이 아니다.
                exhausted = true;
                return null;
            }
            lastCmdSeq = row.sourceCmdSeq();
            lastOrdinal = row.eventOrdinal();
            current = row;
            return row;
        }

        EventJournalRow current() {
            return current;
        }
    }
}

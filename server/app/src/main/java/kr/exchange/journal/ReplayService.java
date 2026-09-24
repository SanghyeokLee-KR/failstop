package kr.exchange.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngine;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.ReplayResult.PendingBatch;
import kr.exchange.ledger.Fees;
import kr.exchange.ledger.MarketCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** 상한 없는 전량 재생. 대조하며 읽고 pending 을 허용한다. */
    public ReplayResult rebuild(MatchingEngine engine) {
        return replay(engine, -1, new JournalEventCursor(engine.lastAppliedSeq(), -1));
    }

    /**
     * 연속 물질화 경계. 이 seq 이하 모든 명령의 이벤트가 저널에 전부 있다.
     *
     * 여기 두는 이유는 호출측에 리더를 새로 주입하지 않기 위해서다. 이 클래스가 두 저널 리더를
     * 이미 들고 있고, 상한은 재생의 성질이지 복구 절차의 성질이 아니다.
     */
    public JournalCheckpoint materializedThrough(long fromCmdSeq) {
        return eventReader.materializedThrough(fromCmdSeq);
    }

    /** 명령 저널 head. 상한 초과분이 있는지 판정에 쓴다. */
    public long journalHead() {
        return commandReader.maxSeq();
    }

    /** 마켓별 연속 물질화 경계. 호출자는 엔진의 마켓 커서를 넘긴다(ADR 0023 결정 2). */
    public JournalCheckpoints materializedThroughByMarket(Map<Integer, Long> cursors) {
        return eventReader.materializedThroughByMarket(cursors);
    }

    /** include 가 받는 마켓만 경계를 읽는다(팔로워가 따라잡는 파티션의 마켓). */
    public JournalCheckpoints materializedThroughByMarket(Map<Integer, Long> cursors,
                                                          java.util.function.IntPredicate include) {
        return eventReader.materializedThroughByMarket(cursors, include);
    }

    /**
     * 엔진의 마켓 커서로 구한 마켓별 경계(ADR 0023 결정 2). 엔진에 커서가 없는 마켓은 넘기지 않는다. 판독기가 저널에 명령이
     * 있는 마켓을 더하고 그 커서를 0 으로 연다. 마켓 사이에 통하는 바닥은 없다(ADR 0022 7단계).
     */
    public JournalCheckpoints materializedThroughByMarket(MatchingEngineImpl engine) {
        return materializedThroughByMarket(engine.appliedSeqs());
    }

    /** 마켓별 명령 저널 head. 2 단계 상한과 초과분 판정에 쓴다. */
    public Map<Integer, Long> journalHeads() {
        return commandReader.maxSeqByMarket();
    }

    /**
     * 상한까지 대조만 하는 재생. pending 이 하나라도 나오면 던진다.
     *
     * 상한이 연속 물질화 경계이므로 그 아래 모든 명령은 이벤트가 있어야 한다. pending 이
     * 나왔다는 것은 커서가 죽었거나 이벤트가 명령 단위로 원자 커밋된다는 전제가 깨진 것이다.
     * 조용히 넘기면 대조 건수가 0 인 채로 통과해 그 뒤 판정이 전부 잘못된 전제 위에 선다.
     *
     * 가드를 호출측이 아니라 여기 두는 이유가 있다. 호출측에 두면 다음 호출자가 그것을 안 부르고
     * 지나간다. 실제로 그렇게 됐다. 팔로워 경로에는 그 검사가 없었다.
     */
    public ReplayResult verifyTo(MatchingEngine engine, JournalCheckpoint bound) {
        long from = engine.lastAppliedSeq();
        ReplayResult result = replay(engine, bound.commandSeq(),
                new JournalEventCursor(from, bound.commandSeq()));
        if (!result.pendingBatches().isEmpty()) {
            // 대조 건수를 함께 싣는다. 커서가 죽은 경우와 일부만 대조된 경우를 로그에서 가른다.
            // 별도 하한을 두지 않는 이유는 그것이 발화할 수 없기 때문이다. 커서가 한 번 null 을
            // 내면 exhausted 가 서서 이후 방출이 전부 pending 으로 가므로, pending 이 비었다는
            // 것이 곧 방출 전량이 대조됐다는 뜻이다.
            throw new ReplayMismatchException("물질화 경계 재생에서 pending 이 나왔다: "
                    + result.pendingEvents().size() + "건, 커서 시작=" + from
                    + ", 상한 cmd=" + bound.commandSeq()
                    + ", 적용=" + result.replayedCommands()
                    + ", 대조=" + result.verifiedEvents()
                    + ", 첫 pending cmd=" + result.pendingBatches().get(0).seq());
        }
        return result;
    }

    /** 이벤트 커서. 대조할 상대가 없는 구간에서는 NONE 을 쓴다. */
    private interface EventCursor {
        EventCursor NONE = new EventCursor() {
            @Override
            public EventJournalRow next() {
                return null;
            }

            @Override
            public EventJournalRow current() {
                return null;
            }
        };

        EventJournalRow next();

        EventJournalRow current();
    }

    /**
     * 재생 본체. 명령 커서는 engine.lastAppliedSeq() 초과분이고 이벤트 커서 시작점도 같은
     * 값이다. 둘이 같은 값에서 출발한다는 것이 이 경로의 유일한 축 계약이고, 그것을 인자가
     * 아니라 이 코드가 소유한다. 종전에는 호출자가 long 으로 건넸고 그 자리에 이벤트 축 값이
     * 들어가 커서가 첫 조회에서 소진됐다.
     */
    private ReplayResult replay(MatchingEngine engine, long commandBound, EventCursor cursor) {
        List<PendingBatch> pending = new ArrayList<>();
        long replayedCommands = 0;
        long verifiedEvents = 0;

        while (true) {
            List<JournalEntry> batch =
                    commandReader.readRange(engine.lastAppliedSeq(), commandBound, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (JournalEntry entry : batch) {
                verifiedEvents += applyAndMatch(engine, entry, cursor, pending, "");
                replayedCommands++;
            }
        }
        if (cursor.next() != null) {
            throw new ReplayMismatchException(
                    "재생이 생성하지 않은 이벤트가 저널에 남아 있다: event_seq="
                            + cursor.current().eventSeq());
        }
        ReplayResult result =
                new ReplayResult(engine.lastAppliedSeq(), replayedCommands, verifiedEvents, pending);
        // 상한 있는 재생은 팔로워 catch-up 이 초마다 부른다. 그 경로까지 INFO 로 남기면 로그가
        // 초당 한 줄씩 쌓인다. 기동 요약은 호출측(EngineRecovery)이 따로 남긴다.
        if (commandBound < 0) {
            log.info("재생 완료: lastAppliedSeq={}, commands={}, verified={}, pending={}",
                    engine.lastAppliedSeq(), replayedCommands, verifiedEvents,
                    result.pendingEvents().size());
        } else if (replayedCommands > 0) {
            log.debug("재생 완료(상한 {}): lastAppliedSeq={}, commands={}, verified={}, pending={}",
                    commandBound, engine.lastAppliedSeq(), replayedCommands, verifiedEvents,
                    result.pendingEvents().size());
        }
        return result;
    }

    /**
     * 한 명령을 적용하고 방출을 이벤트 커서와 1 대 1 로 짝짓는다. 대조한 건수를 돌려준다.
     * 짝이 없는 방출은 그 명령의 배치로 pending 에 더한다.
     */
    private long applyAndMatch(MatchingEngine engine, JournalEntry entry, EventCursor cursor,
                               List<PendingBatch> pending, String scope) {
        JournalChecksum.verify(entry);
        OrderCommand command = mapper.toCommand(entry);
        List<EngineEvent> unmatched = new ArrayList<>();
        long verified = 0;
        for (EngineEvent event : engine.apply(command)) {
            EventJournalRow row = cursor.next();
            if (row == null) {
                unmatched.add(event);
                continue;
            }
            if (!pending.isEmpty() || !unmatched.isEmpty()) {
                // 방어적 불변식. 커서가 한 번 null을 반환하면 exhausted라 이후 next()는
                // 계속 null(pending 경로)이므로 현재 커서 구현에서는 도달하지 않는다.
                // 커서 변경 시 prefix 성질 위반을 조용히 넘기지 않도록 남긴다.
                throw new ReplayMismatchException(
                        scope + "event_journal prefix 위반: event_seq=" + row.eventSeq());
            }
            verifyMatch(row, event, entry);
            verified++;
        }
        if (!unmatched.isEmpty()) {
            pending.add(new PendingBatch(entry.seq(), entry.createdAt(), List.copyOf(unmatched)));
        }
        return verified;
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
     * 한 마켓만 상한 없이 재생한다(ADR 0021 4단계 shadow 비교).
     *
     * 전역 재생과 같은 대조를 마켓 축에서 한다. 명령은 그 마켓 것만 seq 오름차순으로,
     * 이벤트도 그 마켓 것만 (source_cmd_seq, event_ordinal) 오름차순으로 읽어 1 대 1 로 짝짓는다.
     * market_id 가 빈 V36 이전 이벤트는 레거시 경계 이하에서 명령 조인으로 짝짓는다(ADR 0022 9-14).
     *
     * seq 연속성을 검사하지 않는다. seq 가 전역 채번이라 한 마켓의 스트림에는 구멍이
     * 뚫려 있고 그 구멍은 다른 마켓의 명령이지 결번이 아니다. 마켓 안의 순서만 있으면
     * 재생이 성립한다는 것이 이 설계의 전제이고, 이 메서드가 그 전제를 실증한다.
     *
     * 빈 엔진에서 부르면 이 마켓의 북 하나만 채워져 나온다. 전역 재생이 만든 같은 마켓의 북과
     * 바이트가 같아야 한다. 그 대조가 shadow 비교다.
     */
    public ReplayResult rebuildMarket(MatchingEngine engine, int marketId) {
        return replayMarket(engine, marketId, -1, new JournalEventCursor(
                engine.appliedSeq(marketId), -1, marketId, eventReader.legacyBound()));
    }

    /**
     * 한 마켓을 bound(m) 까지 대조만 하는 재생. pending 이 하나라도 나오면 던진다. verifyTo 의 마켓판이다.
     *
     * 두 커서를 engine.appliedSeq(marketId) 에서 함께 열고 호출자는 상한만 넘긴다(ADR 0023 결정 2).
     * checkpoints 는 같은 엔진의 마켓 커서로 구한 materializedThroughByMarket 결과여야 한다.
     */
    public ReplayResult verifyToMarket(MatchingEngine engine, int marketId, JournalCheckpoints checkpoints) {
        long from = engine.appliedSeq(marketId);
        long bound = checkpoints.bound(marketId);
        ReplayResult result = replayMarket(engine, marketId, bound,
                new JournalEventCursor(from, bound, marketId, checkpoints.legacyBound()));
        if (!result.pendingBatches().isEmpty()) {
            throw new ReplayMismatchException("마켓 " + marketId + " 물질화 경계 재생에서 pending 이 나왔다: "
                    + result.pendingEvents().size() + "건, 커서 시작=" + from
                    + ", 상한 cmd=" + bound
                    + ", 적용=" + result.replayedCommands()
                    + ", 대조=" + result.verifiedEvents()
                    + ", 첫 pending cmd=" + result.pendingBatches().get(0).seq());
        }
        return result;
    }

    /**
     * 한 마켓의 크래시 창 산출 재생. headOfMarket 까지 적용하고 방출을 전부 명령 단위 배치로 낸다.
     *
     * 상한이 없으면 리더가 쓰는 동안 끝나지 않으므로 음수를 받지 않는다(ADR 0022 5-4).
     */
    public ReplayResult replayTailMarket(MatchingEngine engine, int marketId, long headOfMarket) {
        if (headOfMarket < 0) {
            throw new IllegalArgumentException("크래시 창 재생에는 상한이 있어야 한다: market=" + marketId);
        }
        return replayMarket(engine, marketId, headOfMarket, EventCursor.NONE);
    }

    /**
     * 마켓 재생 본체. 명령 커서는 engine.appliedSeq(marketId) 초과분이고 이벤트 커서는 호출측이 같은
     * 값에서 연다. commandBound 가 음수면 상한이 없다.
     */
    private ReplayResult replayMarket(MatchingEngine engine, int marketId, long commandBound,
                                      EventCursor cursor) {
        String scope = "마켓 " + marketId + " ";
        List<PendingBatch> pending = new ArrayList<>();
        long replayedCommands = 0;
        long verifiedEvents = 0;

        while (true) {
            List<JournalEntry> batch = commandReader.readAfterForMarket(
                    engine.appliedSeq(marketId), marketId, commandBound, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (JournalEntry entry : batch) {
                verifiedEvents += applyAndMatch(engine, entry, cursor, pending, scope);
                replayedCommands++;
            }
        }
        if (cursor.next() != null) {
            throw new ReplayMismatchException(
                    scope + "재생이 생성하지 않은 이벤트가 저널에 남았다: event_seq="
                            + cursor.current().eventSeq());
        }
        ReplayResult result = new ReplayResult(engine.appliedSeq(marketId), replayedCommands,
                verifiedEvents, pending);
        if (commandBound < 0) {
            log.info("마켓 재생 완료: market={}, commands={}, verified={}, pending={}",
                    marketId, replayedCommands, verifiedEvents, result.pendingEvents().size());
        } else if (replayedCommands > 0) {
            log.debug("마켓 재생 완료(상한 {}): market={}, commands={}, verified={}, pending={}",
                    commandBound, marketId, replayedCommands, verifiedEvents,
                    result.pendingEvents().size());
        }
        return result;
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
    private final class JournalEventCursor implements EventCursor {
        private List<EventJournalRow> buffer = List.of();
        private int index;
        private long lastCmdSeq;
        private int lastOrdinal;
        // 음수면 상한 없음. 0 이상이면 이 source_cmd_seq 까지만 읽는다. 명령 상한과 짝이다.
        private final long upperCmdSeq;
        // 음수면 전 마켓. 마켓을 정하면 legacyBound 이하의 market_id 없는 행도 명령 조인으로 읽는다.
        private final int marketId;
        private final long legacyBound;
        private boolean exhausted;
        private EventJournalRow current;

        JournalEventCursor(long startCmdSeq, long upperCmdSeq) {
            this(startCmdSeq, upperCmdSeq, -1, 0);
        }

        // 커서를 스냅샷 명령 워터마크로 초기화한다. 그 명령까지의 이벤트는 스냅샷에 이미
        // 반영돼 있으므로 그 다음 명령의 첫 이벤트부터 대조한다.
        JournalEventCursor(long startCmdSeq, long upperCmdSeq, int marketId, long legacyBound) {
            this.lastCmdSeq = startCmdSeq;
            this.lastOrdinal = Integer.MAX_VALUE;
            this.upperCmdSeq = upperCmdSeq;
            this.marketId = marketId;
            this.legacyBound = legacyBound;
        }

        @Override
        public EventJournalRow next() {
            if (exhausted) {
                return null;
            }
            if (index >= buffer.size()) {
                buffer = marketId < 0
                        ? eventReader.readAfter(lastCmdSeq, lastOrdinal, BATCH)
                        : eventReader.readAfterForMarket(lastCmdSeq, lastOrdinal, marketId, legacyBound, BATCH);
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

        @Override
        public EventJournalRow current() {
            return current;
        }
    }
}

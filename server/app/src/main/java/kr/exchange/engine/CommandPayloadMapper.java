package kr.exchange.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.JournalEntry;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * command_journal payload의 직렬화·복원 단일 지점. 접수(toPayloadJson)와 재생·정산
 * (toCommand, extras)이 같은 필드명을 쓰므로 여기서만 스키마를 바꾼다(재생 결정론, API 설계 §7-2).
 *
 * external_id·수수료율은 엔진 명령(OrderCommand)에는 없고 정산 계층만 읽는다(extras).
 * 엔진 결정론 코어에 정산 입력을 섞지 않기 위한 분리다.
 */
public class CommandPayloadMapper {

    /** 정산 계층 전용 박제값. NEW 외 명령 또는 미박제 저널(M1-2 이전)은 필드가 null이다. */
    public record Extras(UUID externalId, BigDecimal makerFeeRate, BigDecimal takerFeeRate) {
    }

    private final ObjectMapper objectMapper;

    public CommandPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toPayloadJson(OrderIntake intake) {
        // 수치는 문자열 박제. JSON number로 두면 파서에 따라 double 경유 손실이 생긴다.
        ObjectNode node = objectMapper.createObjectNode();
        node.put("side", intake.side() == null ? null : intake.side().name());
        node.put("ord_type", intake.ordType() == null ? null : intake.ordType().name());
        node.put("time_in_force", intake.timeInForce() == null ? null : intake.timeInForce().name());
        node.put("price", intake.price() == null ? null : intake.price().toPlainString());
        node.put("qty", intake.quantity() == null ? null : intake.quantity().toPlainString());
        node.put("quote_order_qty",
                intake.quoteOrderQty() == null ? null : intake.quoteOrderQty().toPlainString());
        node.put("external_id", intake.externalId() == null ? null : intake.externalId().toString());
        node.put("maker_fee_rate",
                intake.makerFeeRate() == null ? null : intake.makerFeeRate().toPlainString());
        node.put("taker_fee_rate",
                intake.takerFeeRate() == null ? null : intake.takerFeeRate().toPlainString());
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("payload 직렬화 실패", e);
        }
    }

    public OrderCommand toCommand(JournalEntry entry) {
        JsonNode payload = readPayload(entry);
        return new OrderCommand(
                entry.seq(),
                entry.marketId(),
                entry.type() == CommandDraft.CommandType.NEW
                        ? OrderCommand.CommandType.NEW : OrderCommand.CommandType.CANCEL,
                entry.userId(),
                entry.clientOrderId(),
                entry.assignedOrderId(),
                entry.engineTs(),
                entry.tieBreak(),
                enumOf(payload, "side", OrderCommand.Side.class),
                enumOf(payload, "ord_type", OrderCommand.OrdType.class),
                enumOf(payload, "time_in_force", OrderCommand.TimeInForce.class),
                decimalOf(payload, "price"),
                decimalOf(payload, "qty"),
                decimalOf(payload, "quote_order_qty"),
                entry.fencingToken());
    }

    public Extras extras(JournalEntry entry) {
        JsonNode payload = readPayload(entry);
        JsonNode ext = payload.get("external_id");
        return new Extras(
                ext == null || ext.isNull() ? null : UUID.fromString(ext.asText()),
                decimalOf(payload, "maker_fee_rate"),
                decimalOf(payload, "taker_fee_rate"));
    }

    private JsonNode readPayload(JournalEntry entry) {
        try {
            return objectMapper.readTree(entry.payloadJson());
        } catch (Exception e) {
            throw new IllegalStateException("command payload 손상: seq=" + entry.seq(), e);
        }
    }

    private static <E extends Enum<E>> E enumOf(JsonNode payload, String field, Class<E> type) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : Enum.valueOf(type, node.asText());
    }

    private static BigDecimal decimalOf(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : new BigDecimal(node.asText());
    }
}

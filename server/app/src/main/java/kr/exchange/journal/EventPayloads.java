package kr.exchange.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.exchange.engine.core.EngineEvent;

/**
 * event_journal.payload 직렬화 단일 지점. append와 재생 대조가 같은 빌더를 써야
 * 바이트 비교가 성립한다(DB 설계 §5.1 (a''), §5.2 (0), §5.3 (0) payload 계약).
 *
 * 수치는 문자열 박제. JSON number로 두면 파서에 따라 double 경유 손실이 생긴다.
 * makerFee/takerFee는 정산 계층이 명령 payload 박제 요율로 확정한 금액이다(Fees).
 * 호출측이 값을 주지 않으면 "0"이며, 이는 요율 미박제 명령(M1-2 이전 저널)과
 * 요율 0의 표기와 일치한다(payload_schema_version 1 유지).
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    public static String json(EngineEvent e, ObjectMapper mapper) {
        return json(e, mapper, null, null);
    }

    public static String json(EngineEvent e, ObjectMapper mapper, String makerFee, String takerFee) {
        ObjectNode node = mapper.createObjectNode();
        switch (e.type()) {
            case ACCEPTED -> node.put("orderId", e.orderId());
            case TRADE -> {
                node.put("price", e.price().toPlainString());
                node.put("qty", e.quantity().toPlainString());
                node.put("makerOid", e.counterOrderId());
                node.put("takerOid", e.orderId());
                node.put("makerFee", makerFee == null ? "0" : makerFee);
                node.put("takerFee", takerFee == null ? "0" : takerFee);
            }
            case CANCELED -> {
                node.put("orderId", e.orderId());
                node.put("qty", e.quantity().toPlainString());
                if (e.reason() != null) {
                    node.put("reason", e.reason());
                }
            }
            case REJECTED -> {
                node.put("orderId", e.orderId());
                node.put("reason", e.reason());
            }
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("이벤트 payload 직렬화 실패", ex);
        }
    }
}

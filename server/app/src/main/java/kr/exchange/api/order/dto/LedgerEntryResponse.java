package kr.exchange.api.order.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** §4-C-3 원장 행. ref의 둘째 키 이름은 business_event_type에 따라 갈린다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LedgerEntryResponse(
        String entryId,
        String currency,
        String amount,
        String entryType,
        Ref ref,
        String timestamp) {

    public sealed interface Ref permits TradeRef, ReserveRef, EventRef {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TradeRef(String type, String tradeId) implements Ref {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReserveRef(String type, String orderId) implements Ref {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventRef(String type, String id) implements Ref {
    }

    /**
     * 분개가 가리키는 업무 이벤트(§4-C-3 `ref`). business_event_id는 타입마다 축이 다르다
     * (TRADE=event_seq, RESERVE=주문 내부 id, RELEASE=해제 유발 event_seq, GENESIS=발행 일련).
     * 내부 주문 PK는 노출하지 않으므로 RESERVE만 외부 UUID로 치환한다.
     */
    public static Ref ref(String type, long eventId, String orderExternalId) {
        if ("TRADE".equals(type)) {
            return new TradeRef(type, String.valueOf(eventId));
        }
        if ("RESERVE".equals(type)) {
            return new ReserveRef(type, orderExternalId);
        }
        return new EventRef(type, String.valueOf(eventId));
    }
}

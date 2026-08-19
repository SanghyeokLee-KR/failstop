package kr.exchange.api.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * §4-B-1 단건 주문 응답. 컴포넌트 선언 순서가 직렬화 키 순서라 순서 변경은 계약 변경이다.
 * canceled_at은 CANCELED일 때만 존재한다(키 존재 여부 자체가 계약).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderResponse(
        String schemaVersion,
        String id,
        String clientOrderId,
        String market,
        String side,
        String ordType,
        String status,
        String price,
        String volume,
        String remainingVolume,
        String executedVolume,
        String executedFunds,
        String remainingNotional,
        String paidFee,
        long tradesCount,
        String rejectReason,
        String createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String canceledAt,
        long epoch,
        Long seq) {
}

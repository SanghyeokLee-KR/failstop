package kr.exchange.api.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * §4-B-6 일괄 주문 응답. per-item 분기 키(idempotent_replay·order·error)와 summary의
 * in_progress는 있을 때만 존재한다(키 존재 여부가 계약).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BatchResponse(String schemaVersion, List<Item> results, Summary summary) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Item(
            int index,
            String clientOrderId,
            String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean idempotentReplay,
            @JsonInclude(JsonInclude.Include.NON_NULL) OrderResponse order,
            @JsonInclude(JsonInclude.Include.NON_NULL) Object error) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RejectedError(
            String name,
            String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> detail) {
    }

    /** 비종결(in-flight·페일오버) 오류는 message 없이 name·retry_after만 싣는다(재시도 신호). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InProgressError(String name, Integer retryAfter) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Summary(
            int accepted,
            int rejected,
            int replayed,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer inProgress,
            int total) {
    }
}

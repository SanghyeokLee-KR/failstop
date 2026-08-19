package kr.exchange.api.order.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** §4-B-3 일괄 취소 응답. 개별 실패는 HTTP 오류가 아니라 failed 배열로 수렴한다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CancelAllResponse(
        String schemaVersion,
        int canceledCount,
        List<String> canceledIds,
        List<Failure> failed) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Failure(String id, String reason) {
    }
}

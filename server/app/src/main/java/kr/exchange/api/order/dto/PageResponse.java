package kr.exchange.api.order.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** 리스트 엔벌로프(§2-2). next_cursor는 미발급이어도 null 리터럴로 항상 존재한다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PageResponse<T>(String schemaVersion, List<T> data, Pagination pagination) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Pagination(String nextCursor, boolean hasMore) {
    }
}

package kr.exchange.api.order.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** §4-B-5 주문 가능 정보. 단건 객체라 리스트 엔벌로프 없이 schema_version만 싣는다(§2-2). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChanceResponse(
        String schemaVersion,
        MarketInfo market,
        String makerFee,
        String takerFee,
        AccountBalance bidAccount,
        AccountBalance askAccount) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MarketInfo(String id, String tickSize, String minOrderAmount,
                             String maxOrderAmount, String state) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccountBalance(String currency, String balance, String locked) {
    }
}

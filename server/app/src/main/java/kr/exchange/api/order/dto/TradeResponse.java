package kr.exchange.api.order.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** §4-C-2 체결 행. side·fee_currency는 taker_side·liquidity_flag에서 내 관점으로 파생한다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TradeResponse(
        String tradeId,
        String market,
        String side,
        String orderId,
        String price,
        String volume,
        String liquidityFlag,
        String fee,
        String feeCurrency,
        String timestamp) {
}

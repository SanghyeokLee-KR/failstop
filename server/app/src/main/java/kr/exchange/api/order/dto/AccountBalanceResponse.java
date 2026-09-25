package kr.exchange.api.order.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * §4-C-1 계좌 행. unit_currency는 v1 quote 단일(KRW) 전제의 상수 라벨이고 avg_buy_price의
 * 반올림 자리수는 행별 qa.scale에서 오므로, 다중 quote 확장 시 라벨을 행별로 실어야 갈라지지 않는다.
 * version 은 잔고 판이다. myAsset 항목의 같은 필드와 같은 값이라 클라이언트가 둘을 비교한다(§5-2).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AccountBalanceResponse(
        String currency,
        String balance,
        String locked,
        String avgBuyPrice,
        String unitCurrency,
        String costBasisQty,
        String costBasisAmount,
        long version) {
}

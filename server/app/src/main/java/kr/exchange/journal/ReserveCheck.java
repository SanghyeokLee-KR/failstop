package kr.exchange.journal;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 앱이 계산한 주문 생성 명령의 예약 자산과 예약액(ADR 0034 결정한 값 7, DB 설계 §3.5).
 *
 * P2 이상에서 10인자 머리말은 예약액을 payload 와 카탈로그 scale 로 직접 계산하고 이 값은 대조에만 쓴다. 다르면 트랜잭션을
 * 롤백한다. 앱 산식이 DB 와 어긋난 채 과소 예약하면 중간 체결이 LOCKED 잔여를 넘어 정산이 멈추고, 과대 예약하면 사용자
 * 자금을 묶는다. P1 에서는 머리말이 예약하지 않으므로 보지 않는다. 취소 명령에는 없다(null).
 *
 * @param assetId 예약 자산. 매수는 마켓의 quote, 매도는 base 다
 * @param amount  예약액. 지정가 매수는 price x qty 의 quote scale 올림, 시장가 매수는 quote_order_qty, 매도는 qty 다
 */
public record ReserveCheck(short assetId, BigDecimal amount) {

    public ReserveCheck {
        Objects.requireNonNull(amount, "amount");
    }
}

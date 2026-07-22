package kr.exchange.engine;

import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.CommandDraft;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 게이트웨이가 접수 시점에 박제를 끝낸 명령 입력. 저널 payload와 엔진 OrderCommand의
 * 공통 원천이라, 여기 담긴 값 외의 비결정 입력(현재 시각·난수)은 이후 단계에 존재하지 않는다.
 *
 * externalId·수수료율은 엔진이 아니라 정산 계층의 입력이다. 재생·catch-up 재정산이
 * 현재 DB 상태(markets 요율 변동)를 다시 읽지 않도록 명령에 박제한다(DB 설계 §5.2 수수료
 * 박제 규칙, orders.external_id 사전 발급 규약 P0-4). CANCEL 명령은 셋 다 null이다.
 */
public record OrderIntake(
        int marketId,
        CommandDraft.CommandType type,
        long userId,
        String clientOrderId,
        long assignedOrderId,
        long engineTs,
        long tieBreak,
        OrderCommand.Side side,
        OrderCommand.OrdType ordType,
        OrderCommand.TimeInForce timeInForce,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteOrderQty,
        UUID externalId,
        BigDecimal makerFeeRate,
        BigDecimal takerFeeRate
) {
}

package kr.exchange.engine.core;

import java.math.BigDecimal;

/**
 * 시퀀서가 채번을 마친 뒤 엔진에 투입하는 명령. command_journal 한 행과 1:1 대응한다(DB 설계 v2.9 §3.1 6.1).
 *
 * @param seq             전역 gapless 시퀀스. command_journal PK
 * @param marketId        대상 마켓
 * @param type            NEW 또는 CANCEL. AMEND는 1차 범위 밖(DB 설계 v2.4 결정)
 * @param userId          주문자
 * @param clientOrderId   멱등키. NEW에서 필수(API 설계 v2.6 §2-4)
 * @param assignedOrderId NEW는 시퀀서 사전채번 주문 id(재생 시 DB 채번 비결정성 제거), CANCEL은 취소 대상 주문 id
 * @param engineTs        박제된 시각(epoch ms). 엔진은 이 값 외의 시계를 보지 않는다
 * @param tieBreak        동시성 tie-break 박제 값
 * @param side            BUY 또는 SELL
 * @param ordType         LIMIT, MARKET, BEST
 * @param timeInForce     GTC/IOC/FOK/POST_ONLY. null이면 GTC로 해석(API 설계 §4-B-1 기본값)
 * @param price           지정가. MARKET이면 null
 * @param quantity        base 수량. MARKET BUY 금액기반이면 null
 * @param quoteOrderQty   시장가 매수 금액. 수량기반이면 null
 * @param fencingToken    기록 시점 리더 epoch
 */
public record OrderCommand(
        long seq,
        int marketId,
        CommandType type,
        long userId,
        String clientOrderId,
        long assignedOrderId,
        long engineTs,
        long tieBreak,
        Side side,
        OrdType ordType,
        TimeInForce timeInForce,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteOrderQty,
        long fencingToken
) {
    public enum CommandType { NEW, CANCEL }

    public enum Side { BUY, SELL }

    public enum OrdType { LIMIT, MARKET, BEST }

    public enum TimeInForce { GTC, IOC, FOK, POST_ONLY }
}

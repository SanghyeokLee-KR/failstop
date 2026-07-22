package kr.exchange.engine.core;

import java.math.BigDecimal;

/**
 * 엔진이 명령 적용 결과로 방출하는 이벤트. event_journal 한 행과 1:1 대응한다(DB 설계 v2.9 §3.1 6.2).
 * trades.id의 진실 원천은 TRADE 이벤트의 eventSeq다(trade_id = event_seq, API 설계 §7-2).
 *
 * eventSeq는 엔진이 모른다. 저널 채번(next_journal_value)이 원천이므로 방출 시점에는 0이고,
 * 운영 sink인 SettlementService(appendAndSettle)가 부여한다. EventJournalAppender는 정산
 * 없는 저널 계층 테스트 전용이다. 단일 라이터의 방출 순서가 곧 채번 순서라 재생 시에도 동일하다.
 *
 * @param eventSeq       전역 gapless 이벤트 시퀀스. 채번 전 0
 * @param sourceCmdSeq   이 이벤트를 낳은 command_journal.seq
 * @param eventOrdinal   원인 명령 내 방출 순번. 0부터 단조 증가(UNIQUE(source_cmd_seq, event_ordinal))
 * @param marketId       대상 마켓
 * @param type           TRADE, ACCEPTED, REJECTED, CANCELED. EXPIRED는 CANCELED로 일원화(DB 설계 v2.4)
 * @param engineTs       원인 명령의 engine_ts. created_at·executed_at 파생 원천(DB now() 금지)
 * @param orderId        대상 주문 id. TRADE에서는 taker 주문 id
 * @param counterOrderId TRADE에서 상대(maker) 주문 id. 그 외 null
 * @param makerUserId    TRADE에서 maker 사용자 id. 그 외 null
 * @param takerUserId    TRADE에서 taker 사용자 id. 그 외 null
 * @param takerSide      TRADE에서 taker 방향. 그 외 null
 * @param price          TRADE 체결가. 항상 maker 지정가와 일치해야 한다(하네스 C18)
 * @param quantity       TRADE 체결 수량. CANCELED에서는 주문에서 제거된 수량이며 단위는
 *                       주문의 잔여 추적 단위를 따른다(시장가 매수 주문은 잔여 취소·STP
 *                       감액 모두 quote 금액, 그 외는 base 수량. DB 설계 v2.8 §5.3 (0))
 * @param reason         REJECTED 거부 사유 또는 CANCELED 취소 사유. 사용자 취소는 null
 */
public record EngineEvent(
        long eventSeq,
        long sourceCmdSeq,
        int eventOrdinal,
        int marketId,
        EventType type,
        long engineTs,
        long orderId,
        Long counterOrderId,
        Long makerUserId,
        Long takerUserId,
        OrderCommand.Side takerSide,
        BigDecimal price,
        BigDecimal quantity,
        String reason
) {
    public enum EventType { TRADE, ACCEPTED, REJECTED, CANCELED }

    public EngineEvent withEventSeq(long assignedSeq) {
        return new EngineEvent(assignedSeq, sourceCmdSeq, eventOrdinal, marketId, type, engineTs,
                orderId, counterOrderId, makerUserId, takerUserId, takerSide, price, quantity, reason);
    }
}

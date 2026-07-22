package kr.exchange.journal;

/**
 * append 결과. COMMIT 성공이 곧 ACK다.
 *
 * @param seq          커밋된 command_journal.seq
 * @param fencingToken 기록 시점 리더 epoch
 * @param replayed     true면 신규 삽입 없이 기존 NEW 명령을 반환한 멱등 재전송
 * @param assignedOrderId 저널에 박제된 주문 id. 재전송 응답은 기존 값을 그대로 쓴다
 */
public record AppendResult(long seq, long fencingToken, boolean replayed, long assignedOrderId) {
}

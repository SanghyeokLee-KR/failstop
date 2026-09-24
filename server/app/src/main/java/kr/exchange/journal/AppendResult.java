package kr.exchange.journal;

/**
 * append 결과. COMMIT 성공이 곧 ACK다.
 *
 * @param seq          커밋된 command_journal.seq
 * @param fencingToken 기록 시점 리더 epoch
 * @param replayed     true면 신규 삽입 없이 기존 NEW 명령을 반환한 멱등 재전송
 * @param assignedOrderId 저널에 박제된 주문 id. 재전송 응답은 기존 값을 그대로 쓴다
 * @param reserved     같은 트랜잭션에서 DB 가 이 명령의 자금을 예약했는가(P2 이상의 주문 생성 명령, ADR 0034 S3).
 *                     재전송과 P1 접수, 취소 명령은 거짓이다
 */
public record AppendResult(long seq, long fencingToken, boolean replayed, long assignedOrderId, boolean reserved) {

    /** 예약하지 않은 append. 예약을 모르는 호출부와 시험이 쓴다. */
    public AppendResult(long seq, long fencingToken, boolean replayed, long assignedOrderId) {
        this(seq, fencingToken, replayed, assignedOrderId, false);
    }
}

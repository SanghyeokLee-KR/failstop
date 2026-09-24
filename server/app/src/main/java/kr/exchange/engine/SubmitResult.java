package kr.exchange.engine;

/**
 * 접수 결과. 저널 COMMIT이 ACK의 근거이고 큐 투입은 이후의 전달 시도일 뿐이다.
 *
 * @param seq          command_journal.seq
 * @param epoch        기록 시점 리더 epoch. API 응답의 epoch 필드 원천
 * @param replayed     멱등 재전송이라 신규 투입 없이 기존 명령을 반환했는지
 * @param queued       라이터 큐 투입 성공 여부. false면 저널에는 있으므로 catch-up이 회수한다
 * @param reserved     저널 append 와 같은 트랜잭션에서 DB 가 자금을 예약했는지(P2, ADR 0034 S3). 참이면 게이트웨이의
 *                     in-flight 누계가 쥘 몫이 없다
 */
public record SubmitResult(long seq, long epoch, boolean replayed, boolean queued, boolean reserved) {

    /** 예약하지 않은 접수. 예약을 모르는 호출부와 시험이 쓴다. */
    public SubmitResult(long seq, long epoch, boolean replayed, boolean queued) {
        this(seq, epoch, replayed, queued, false);
    }
}

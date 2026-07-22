package kr.exchange.journal;

/**
 * 재생 결과가 event_journal 기록과 불일치. 엔진 비결정성 또는 저널 손상이므로
 * 서비스를 열지 않고 fail-fast한다(DB 설계 §5.5 matched=false 규칙).
 */
public class ReplayMismatchException extends RuntimeException {

    public ReplayMismatchException(String message) {
        super(message);
    }
}

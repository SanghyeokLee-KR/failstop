package kr.exchange.admin.msp;

/**
 * 원천(Prometheus, Alertmanager) 호출 실패. 문구에는 분류 코드만 싣고 주소나 응답 본문을 싣지 않는다.
 * outcomeUnknown 은 요청이 원천에 닿았는지 모르는 실패(응답 시간 초과, 연결 중 끊김)다.
 */
public class SourceException extends RuntimeException {

    private final String reason;
    private final boolean outcomeUnknown;

    public SourceException(String reason, boolean outcomeUnknown) {
        super(reason, null, false, false);
        this.reason = reason;
        this.outcomeUnknown = outcomeUnknown;
    }

    public String reason() {
        return reason;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}

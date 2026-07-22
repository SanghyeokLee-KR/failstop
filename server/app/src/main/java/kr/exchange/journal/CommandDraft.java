package kr.exchange.journal;

/**
 * seq·fencing_token 채번 전의 명령. 나머지 필드는 시퀀서가 접수 시점에 박제를 끝낸 값이다
 * (engine_ts, tie_break, assigned_order_id, payload. DB 설계 v2.9 §3.1 6.1).
 */
public record CommandDraft(
        int marketId,
        CommandType type,
        long userId,
        String clientOrderId,
        long assignedOrderId,
        long engineTs,
        long tieBreak,
        String payloadJson
) {
    public enum CommandType { NEW, CANCEL }

    public CommandDraft {
        if (type == CommandType.NEW && (clientOrderId == null || clientOrderId.isBlank())) {
            // NEW 멱등키 필수(API 설계 v2.6 §2-4). 저널에 들어간 뒤에는 되돌릴 수 없다.
            throw new IllegalArgumentException("NEW 명령은 client_order_id 필수");
        }
    }
}

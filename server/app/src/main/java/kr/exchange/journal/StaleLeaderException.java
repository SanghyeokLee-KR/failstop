package kr.exchange.journal;

/**
 * fencing INSERT...SELECT가 0행으로 끝난 경우. 내 epoch가 밀렸거나 리스가 만료된
 * stale 리더라는 뜻이므로 호출측은 즉시 self-shutdown 경로를 밟아야 한다(DB 설계 §5.4).
 */
public class StaleLeaderException extends RuntimeException {

    public StaleLeaderException(String message) {
        super(message);
    }
}

package kr.exchange.engine;

/** 리더가 아닌 인스턴스가 엔진 기원 쓰기를 시도했을 때. DB 왕복 전에 차단한다. */
public class NotLeaderException extends RuntimeException {

    public NotLeaderException(String message) {
        super(message);
    }
}

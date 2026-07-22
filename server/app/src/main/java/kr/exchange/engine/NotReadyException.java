package kr.exchange.engine;

/**
 * 리더지만 접수 게이트가 닫힌 상태(PROMOTING: 승격 재생 진행 중)의 접수 거부. NotLeader와
 * 구분한다. 이 노드는 리더가 맞고 곧 개방하므로 클라이언트·봇·k6는 재시도로 흡수한다(503).
 */
public class NotReadyException extends RuntimeException {

    public NotReadyException(String message) {
        super(message);
    }
}

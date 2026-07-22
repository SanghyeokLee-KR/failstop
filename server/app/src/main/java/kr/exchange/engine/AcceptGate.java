package kr.exchange.engine;

/**
 * 접수 개방 게이트. 리더십과 분리한다. 리더여도 승격 재생(PROMOTING) 중에는 닫혀 있어
 * head까지 선복원되기 전 명령 유입을 막는다(복구 RTO §3-a). 게이트 미배선 경로(단위 테스트)는
 * 항상 개방을 쓴다.
 */
@FunctionalInterface
public interface AcceptGate {

    AcceptGate ALWAYS_OPEN = () -> true;

    boolean isAccepting();
}

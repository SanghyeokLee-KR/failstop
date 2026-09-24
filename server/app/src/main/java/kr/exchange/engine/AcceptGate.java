package kr.exchange.engine;

/**
 * 접수 개방 게이트. 리더십과 분리한다. 리더여도 승격 재생(PROMOTING) 중에는 닫혀 있어
 * head까지 선복원되기 전 명령 유입을 막는다(복구 RTO §3-a). 게이트 미배선 경로(단위 테스트)는
 * 항상 개방을 쓴다.
 *
 * 파티션 배선의 게이트는 마켓마다 그 파티션이 연 기간을 든다(DB 설계 §5.4-c 6). 접수는 그 기간의 epoch 로 append 한다.
 */
@FunctionalInterface
public interface AcceptGate {

    AcceptGate ALWAYS_OPEN = () -> true;

    boolean isAccepting();

    /** 기간을 드는 게이트인가. 거짓이면 append 가 리스 서비스의 현재 epoch 를 쓴다(종전 배선). */
    default boolean termBound() {
        return false;
    }

    /** 그 마켓 파티션 게이트가 연 기간. 닫혀 있으면 null 이다. 기간을 드는 게이트만 쓴다. */
    default LeaderTerm openTerm(int marketId) {
        return null;
    }
}

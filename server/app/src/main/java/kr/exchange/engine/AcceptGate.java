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

    /**
     * 메모리만 보고 그 마켓 파티션의 게이트가 열렸는지 답한다(API 설계 §2-5-d, S5 설계 4-1). 마켓의 파티션 대응을 메모리에 갖고
     * 있지 않으면 null 이다. 인증 전의 소유권 선검사가 쓰므로 DB 를 보면 안 된다. 기간을 들지 않는 게이트는 노드 전체의 개방
     * 여부다. 파티션 게이트(PartitionLeases)는 대응을 다시 읽지 않는 판으로 덮어 쓴다.
     */
    default Boolean acceptingIfKnown(int marketId) {
        return termBound() ? Boolean.valueOf(openTerm(marketId) != null) : Boolean.valueOf(isAccepting());
    }
}

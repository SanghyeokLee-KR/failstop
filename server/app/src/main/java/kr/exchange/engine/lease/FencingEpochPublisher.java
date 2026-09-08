package kr.exchange.engine.lease;

/**
 * 획득한 epoch 을 쓰기 경로가 보는 곳에 게시한다.
 *
 * 리스를 PostgreSQL 밖으로 옮기면 트랜잭션 안의 fencing 검증(assert_current_leader)이 볼 근거가
 * 사라진다. 그 검증은 (holder, epoch) 일치로 stale 리더의 쓰기를 막는 장치이므로, 새 리더는
 * 자기 epoch 을 그 검증이 읽는 자리에 먼저 올려야 한다.
 *
 * 순서가 안전성의 전부다. 게시는 반드시 접수 개방 전에 끝나야 한다. 게시 전에 쓰기를 열면 구
 * 리더의 epoch 이 그대로 남아 있는 동안 두 노드의 쓰기가 모두 통과한다.
 *
 * 게시 실패는 승격 실패다. 게시하지 못한 노드는 쓸 수 없으므로 리더가 되어서는 안 된다.
 */
@FunctionalInterface
public interface FencingEpochPublisher {

    /** 게시가 성립하면 참. 거짓이면 이 노드는 리더가 되면 안 된다. */
    boolean publish(String holder, long epoch);

    /**
     * 게시가 필요 없는 배선. 리스 보관소가 곧 fencing 검증의 원천일 때 쓴다.
     * PostgreSQL 보관소가 그 경우다. 승격 UPDATE 가 이미 같은 행의 epoch 을 올린다.
     */
    FencingEpochPublisher NONE = (holder, epoch) -> true;
}

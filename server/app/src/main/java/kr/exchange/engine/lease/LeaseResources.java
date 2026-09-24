package kr.exchange.engine.lease;

/**
 * leader_lease 의 자원 이름(DB 설계 §3.4). 획득·갱신·조회처럼 자원을 직접 가리키는 곳만 쓴다. 쓰기 경로는 이름을 넘기지 않고
 * DB 가 기록할 행의 마켓에서 도출한다(§5.4-b). P1·P2 의 단일 리더는 이 행 하나를 쓴다.
 */
public final class LeaseResources {

    public static final String MATCHING_ENGINE = "matching-engine";

    /**
     * 이 바이너리가 구현한 최고 리더 모드(ADR 0034 S3, DB 설계 §3.5·§5.4-b 바이너리 능력). 획득과 게시가 이 값을 넘기고,
     * DB 는 현재 모드가 이보다 크면 55000 으로 거부한다. S3 바이너리는 P2(접수 시점 예약)의 정산을 알고 P3(파티션 리더)는
     * 모른다. 그래서 P3 전환 뒤에는 이 바이너리가 리더가 되지 못한다. P3 을 구현하는 단계(S4 이후)가 이 값을 올린다.
     */
    public static final short MAX_PROTOCOL = 2;

    private LeaseResources() {
    }
}

package kr.exchange.engine.lease;

/**
 * leader_lease 의 자원 이름(DB 설계 §3.4). 획득·갱신·조회처럼 자원을 직접 가리키는 곳만 쓴다. 쓰기 경로는 이름을 넘기지 않고
 * DB 가 기록할 행의 마켓에서 도출한다(§5.4-b). P1·P2 의 단일 리더는 matching-engine 행 하나를 쓰고, 파티션 리더 모드(P3)는
 * matching-p0·matching-p1 을 쓴다.
 */
public final class LeaseResources {

    public static final String MATCHING_ENGINE = "matching-engine";
    public static final String PARTITION_P0 = "matching-p0";
    public static final String PARTITION_P1 = "matching-p1";

    /** 파티션 리더 모드(P3). */
    public static final int PARTITION_PROTOCOL = 3;

    /**
     * 이 바이너리가 구현한 최고 리더 모드(ADR 0034 S3, DB 설계 §3.5·§5.4-b 바이너리 능력). 획득과 게시가 이 값을 넘기고,
     * DB 는 현재 모드가 이보다 크면 55000 으로 거부한다. S4~S6 바이너리는 파티션 코드 경로를 가졌지만 이 값이 2 라 P3 DB 에서
     * 어떤 파티션도 얻지 못했다(DB 설계 §5.4-c 13). S7 이 3 으로 올려 파티션 리스 획득을 연다.
     *
     * 설정 스위치는 두지 않는다. 모드의 권위는 DB 의 leader_protocol 이고 이 값은 획득 상한일 뿐이다. 획득 함수는 현재 모드가
     * 이 값보다 클 때만 거부하므로 P1·P2 DB 에서는 어떤 호출의 결과도 바뀌지 않는다. 게시 함수는 P3 에서 늘 거부하므로 게시자가
     * 이 값을 넘겨도 P3 게시는 열리지 않는다(V50). 값은 게이지 exchange.leader.protocol.max 로 내고, 전환 절차가 2->3 전에 두
     * 노드의 값을 본다(DB 설계 §5.4-e 1, S7 설계 4-2).
     */
    public static final short MAX_PROTOCOL = 3;

    private LeaseResources() {
    }
}

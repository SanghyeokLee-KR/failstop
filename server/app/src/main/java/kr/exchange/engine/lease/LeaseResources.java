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
     * DB 는 현재 모드가 이보다 크면 55000 으로 거부한다. S4 바이너리는 파티션 코드 경로를 갖지만 이 값은 2 그대로라 P3 DB 에서
     * 어떤 파티션도 얻지 못한다(DB 설계 §5.4-c 13). P3 을 켜는 단계(S7)가 이 값을 올린다. 시험은 보관소에 3을 주입해 흉내 낸다.
     */
    public static final short MAX_PROTOCOL = 2;

    private LeaseResources() {
    }
}

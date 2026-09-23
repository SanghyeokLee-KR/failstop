package kr.exchange.engine.lease;

/**
 * leader_lease 의 자원 이름(DB 설계 §3.4). 획득·갱신·조회처럼 자원을 직접 가리키는 곳만 쓴다. 쓰기 경로는 이름을 넘기지 않고
 * DB 가 기록할 행의 마켓에서 도출한다(§5.4-b). P1·P2 의 단일 리더는 이 행 하나를 쓴다.
 */
public final class LeaseResources {

    public static final String MATCHING_ENGINE = "matching-engine";

    private LeaseResources() {
    }
}

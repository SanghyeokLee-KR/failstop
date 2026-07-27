package kr.exchange.engine;

/**
 * 이 노드가 리더 일을 수행할 수 있는 상태인지의 판정(DB 설계 §5.4 자발적 강등).
 *
 * 리스 하트비트가 전용 풀로 격리된 뒤로 하트비트 생존성과 서비스 생존성이 분리됐다. 요청 경로가
 * 완전히 멈춘 노드도 리스만은 계속 갱신해 스탠바이 승격을 영구히 막는 좀비 리더가 될 수 있다.
 * 리스 갱신 직전에 이 판정을 통과하지 못하면 갱신을 포기해 만료 구동 승격을 열어준다.
 *
 * 구현은 표본 채취까지 담당한다. 호출 주기가 곧 표본 주기이며, 호출자는 리스 틱이다.
 * 판정은 DB를 건드리지 않는다. DB 왕복을 넣으면 판정 자체가 굶어 하트비트를 막는다.
 */
@FunctionalInterface
public interface NodeServiceHealth {

    /** 계측 미배선 기본값. 판정을 하지 않으므로 종전 거동(무조건 갱신)과 동일하다. */
    NodeServiceHealth ALWAYS_SERVICEABLE = () -> true;

    /** false면 이 노드는 지속적으로 일을 못 하는 상태다. 리스 갱신을 포기해야 한다. */
    boolean serviceable();
}

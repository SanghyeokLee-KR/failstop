package kr.exchange.engine;

/**
 * 리더십 스냅샷. 트랜잭션 시작 시점에 한 번 캡처해 트랜잭션 내내 같은 값을 쓴다.
 * 중간에 다시 읽으면 하트비트/탈취와 겹쳐 fencing 비교 대상이 흔들린다.
 */
public record LeaderIdentity(String holder, long epoch) {
}

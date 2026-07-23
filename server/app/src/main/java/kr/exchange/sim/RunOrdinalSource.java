package kr.exchange.sim;

/**
 * run 시작마다 유일·단조 ordinal을 발번한다. client_order_id nonce 접두의 유일성 성분이다.
 *
 * 유일성 기준은 프로세스가 아니라 idempotency_keys의 보존 범위다. 인메모리 카운터는 JVM
 * 재시작마다 리셋되므로 DB를 보존한 채 재기동하면 직전 run의 coid 열을 그대로 재생성해 멱등키와
 * 전면 충돌한다(409 duplicate_client_order_id 폭주). 따라서 구현체는 멱등키와 수명을 같이하는
 * 채번원이어야 하고, 그래야 백업 복원에서도 둘이 같은 스냅샷으로 함께 되감겨 정합이 유지된다.
 */
@FunctionalInterface
public interface RunOrdinalSource {
    long next();
}

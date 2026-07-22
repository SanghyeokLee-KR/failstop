package kr.exchange.api.auth;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * nonce 재사용 차단 저장소. TTL은 요청별 유효 recvWindow + clock skew로 허용창 전체를
 * 덮는다(API 설계 §3-4 4단계).
 *
 * 단일 노드 전제의 인메모리 구현이다. 정본 §3-4는 Redis SET을 계약으로 두지만 M1-4는
 * 단일 게이트웨이 노드라 프로세스 로컬 저장으로 동일 보장을 얻는다. 다중 노드 승격(M2)
 * 시에는 노드 간 공유가 없으면 교차 노드 재전송이 뚫리므로 Redis로 교체해야 한다.
 */
public class NonceStore {

    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    /** 최초 등록이면 true. 이미 존재(미만료)하면 false = 재전송. */
    public boolean register(String key, long ttlMs) {
        long now = System.currentTimeMillis();
        purge(now);
        Long prev = seen.putIfAbsent(key, now + ttlMs);
        if (prev == null) {
            return true;
        }
        if (prev < now) {
            // 만료분 교체. replace 실패는 경쟁 등록이 이겼다는 뜻이므로 재사용으로 판정한다.
            return seen.replace(key, prev, now + ttlMs);
        }
        return false;
    }

    private void purge(long now) {
        // 호출 경로에서 상시 청소한다. 수만 nonce 수준의 데모 부하에서 전용 스레드는 과설계다.
        if (seen.size() < 10_000) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
    }
}

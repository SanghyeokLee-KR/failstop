package kr.exchange.api.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 주문 쓰기 수용 제한(API 설계 §2-5 node 버킷). 동시에 처리 중인 주문 쓰기 요청 수를 유계화하고
 * 초과분을 무대기로 거절한다.
 *
 * 왜 필요한가(라이브 실측). 리더 pg 경로에 지연 200ms/지터 100을 주입하면 리더가 스스로 죽었다.
 * 요청이 무한 수용되고, 가상 스레드가 요청마다 생겨 전부 느린 DB에 블록되며, HikariCP 커넥션
 * 획득이 기본 30초를 기다려 대기 스레드가 계속 쌓였다. cpu 463%, `Failed to obtain JDBC
 * Connection` 폭주, status·readiness 전량 타임아웃, 지연을 걷어내도 자력 회복 없음(30분 이상).
 * 병목은 커넥션이 아니라 커넥션을 기다리는 스레드였고, 그 수를 제한하는 장치가 없었다.
 *
 * 대기열을 만들지 않는다. 초과분 즉시 거절이 백프레셔의 전부다. 큐를 두면 스레드가 다시 쌓여
 * 죽는 방식만 옮겨진다. 그래서 무대기 tryAcquire이고 세마포어는 비공정(barging)이다. 공정 큐는
 * 곧 대기열이다.
 *
 * 상한과 요청 풀의 관계. 상한이 풀보다 크면 초과분이 커넥션 대기로 옮겨갈 뿐이라 유계화가 무의미하고,
 * 풀과 같으면 쓰기가 풀을 전량 점유해 조회(status·readiness·시장 데이터)가 커넥션을 얻지 못한다.
 * 노드가 죽지 않는다는 것은 곧 조회가 계속 응답한다는 뜻이라, 쓰기에 풀의 75%만 배정하고 나머지를
 * 조회 몫으로 남긴다(풀 32 -> 쓰기 24·조회 8, 풀 10 -> 쓰기 7·조회 3).
 *
 * 하한 4는 통합테스트·로컬 dev의 초소형 풀(2~4)에서 상한이 0이나 1로 내려앉아 정상 경로까지
 * 막는 것을 방지한다. 풀이 6 이상이면 75% 규칙이 지배하고 상한은 항상 풀보다 작다.
 *
 * 배치 주문 1건도 permit 1개다. 보호 대상이 요청이 점유하는 스레드·커넥션이라 원소 수와 무관하다.
 * 원소 증폭은 계정 토큰버킷이 weight 5 x 원소 수로 이미 상쇄한다(§2-5).
 */
public final class OrderAdmissionControl {

    // 요청 풀 중 쓰기에 배정하는 몫. 나머지는 조회 경로가 쓴다.
    private static final double WRITE_POOL_SHARE = 0.75;
    private static final int MIN_LIMIT = 4;
    // permit 회전 시간의 상한은 커넥션 획득 타임아웃(3s)이라 1초면 재시도가 헛돌지 않는다.
    // 기존 503 경로(NotLeader·NotReady·일시 DB 실패)와 같은 값이라 클라이언트 백오프가 단일하다.
    private static final int RETRY_AFTER_SECONDS = 1;

    private final int limit;
    private final Semaphore permits;
    private final Counter rejected;

    public OrderAdmissionControl(int configuredLimit, int requestPoolSize, MeterRegistry registry) {
        this.limit = resolveLimit(configuredLimit, requestPoolSize);
        this.permits = new Semaphore(this.limit);
        this.rejected = Counter.builder("exchange.admission.rejected")
                .description("수용 제한으로 접수 이전에 거절한 주문 쓰기 요청 수")
                .register(registry);
        Gauge.builder("exchange.admission.in_flight", this, OrderAdmissionControl::inFlight)
                .description("처리 중인 주문 쓰기 요청 수")
                .register(registry);
        Gauge.builder("exchange.admission.limit", this, c -> c.limit)
                .description("주문 쓰기 동시 처리 상한")
                .register(registry);
    }

    /** 0 이하 설정은 요청 풀 크기에서 유도한다. 카오스 스택은 풀을 환경변수로 덮으므로 유도가 곧 추종이다. */
    static int resolveLimit(int configuredLimit, int requestPoolSize) {
        if (configuredLimit > 0) {
            return configuredLimit;
        }
        return Math.max(MIN_LIMIT, (int) Math.floor(requestPoolSize * WRITE_POOL_SHARE));
    }

    /**
     * 무대기 획득. false면 호출측은 접수 이전에 거절해야 한다. 거절 계량을 여기서 하는 이유는
     * 이 지점이 '수용하지 않기로 판정한' 유일한 순간이기 때문이다. 응답 렌더링에서 세면 렌더링이
     * 실패한 거절이 통계에서 사라진다.
     */
    public boolean tryAcquire() {
        boolean acquired = permits.tryAcquire();
        if (!acquired) {
            rejected.increment();
        }
        return acquired;
    }

    /** tryAcquire가 true를 준 경우에만, 요청 수명이 끝날 때 정확히 한 번 호출한다. */
    public void release() {
        permits.release();
    }

    /**
     * 429다. 503이 아닌 이유는 셋이다.
     *  (1) 거절은 접수 이전에 일어나 주문이 저널·원장에 닿지 않는다. 상태가 UNKNOWN이 아니라
     *      확정 미접수라, 503이 발동시키는 재조회 계약(§2-3·§7-6)은 불필요한 DB 읽기를 노드가
     *      굶고 있는 순간에 더 얹는다.
     *  (2) 인그레스가 접수 경로의 503을 노드 장애로 계상한다(harness/nginx: proxy_next_upstream
     *      http_503, max_fails=1). 백프레셔를 503으로 내면 정상 리더가 업스트림에서 빠지고,
     *      수요가 끊겨 좀비 판정(DB§5.4)의 분모까지 사라진다. 429는 그 경로를 타지 않는다.
     *  (3) 클라이언트가 이미 다루는 코드다. k6는 429를 실패로 세지 않고(order_flow.js
     *      expectedStatuses), 봇은 같은 client_order_id로 재시도해 멱등 수렴한다.
     * detail은 §2-5 다중버킷 표기를 그대로 쓴다. 계정 버킷과 구분되는 노드 용량 버킷이다.
     */
    public ApiException rejection() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bucket", "node");
        detail.put("scope", "order");
        return new ApiException(429, "too_many_requests",
                "노드 처리 용량을 초과했습니다. 잠시 후 동일 client_order_id로 재시도하십시오.",
                detail, RETRY_AFTER_SECONDS);
    }

    public int limit() {
        return limit;
    }

    public int inFlight() {
        return limit - permits.availablePermits();
    }
}

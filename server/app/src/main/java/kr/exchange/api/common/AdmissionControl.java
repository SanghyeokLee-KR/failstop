package kr.exchange.api.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 수용 제한 버킷 하나(API 설계 §2-5-a). 동시에 처리 중인 요청 수를 유계화하고 초과분을 무대기로
 * 거절한다. 스코프별로 인스턴스를 따로 둔다(order·query). 상한 배분 근거는 AdmissionLimits.
 *
 * 왜 필요한가(라이브 실측). 리더 pg 경로에 지연 200ms/지터 100을 주입하면 리더가 스스로 죽었다.
 * 요청이 무한 수용되고, 가상 스레드가 요청마다 생겨 전부 느린 DB에 블록되며, 대기 스레드가 계속
 * 쌓여 cpu 463%·전 경로 타임아웃·자력 회복 불가로 이어졌다. 병목은 커넥션이 아니라 커넥션을
 * 기다리는 스레드였고, 그 수를 제한하는 장치가 없었다.
 *
 * 대기열을 만들지 않는다. 초과분 즉시 거절이 백프레셔의 전부다. 큐를 두면 스레드가 다시 쌓여
 * 죽는 방식만 옮겨진다. 그래서 무대기 tryAcquire이고 세마포어는 비공정(barging)이다. 공정 큐는
 * 곧 대기열이다.
 */
public final class AdmissionControl {

    // 재시도 간격. HTTP Retry-After의 최소 유효값이고, 기존 503 경로(NotLeader·NotReady·일시 DB
    // 실패)와 같은 값이라 클라이언트 백오프가 단일하다. permit 회전 시간의 상한은 아니다. dev
    // 프로파일에는 statement_timeout이 없어(prod만 있음) 슬로 윈도에서 한 요청이 이 값보다 오래
    // 슬롯을 쥘 수 있다. 그때 재시도는 다시 429로 튕기고, 그것이 백프레셔의 정상 동작이다.
    private static final int RETRY_AFTER_SECONDS = 1;

    private final String scope;
    private final int limit;
    private final Semaphore permits;
    private final Counter rejected;

    public AdmissionControl(String scope, int limit, MeterRegistry registry) {
        if (scope == null || scope.isBlank() || limit <= 0 || registry == null) {
            throw new IllegalArgumentException("scope·limit·registry는 필수");
        }
        this.scope = scope;
        this.limit = limit;
        this.permits = new Semaphore(limit);
        this.rejected = Counter.builder("exchange.admission.rejected")
                .description("수용 제한으로 접수·처리 이전에 거절한 요청 수")
                .tag("scope", scope)
                .register(registry);
        Gauge.builder("exchange.admission.in_flight", this, AdmissionControl::inFlight)
                .description("처리 중인 요청 수")
                .tag("scope", scope)
                .register(registry);
        Gauge.builder("exchange.admission.limit", this, c -> c.limit)
                .description("동시 처리 상한")
                .tag("scope", scope)
                .register(registry);
    }

    /**
     * 무대기 획득. false면 호출측은 처리 이전에 거절해야 한다. 거절 계량을 여기서 하는 이유는
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
     *  (1) 거절은 처리 이전에 일어나 요청이 저널·원장에 닿지 않는다. 쓰기의 경우 상태가 UNKNOWN이
     *      아니라 확정 미접수라, 503이 발동시키는 재조회 계약(§2-3·§7-6)은 불필요한 DB 읽기를
     *      노드가 굶고 있는 순간에 더 얹는다.
     *  (2) 인그레스가 접수 경로의 503을 노드 장애로 계상한다(harness/nginx: proxy_next_upstream
     *      http_503, max_fails=1). 백프레셔를 503으로 내면 정상 리더가 업스트림에서 빠지고,
     *      수요가 끊겨 좀비 판정(DB§5.4)의 분모까지 사라진다. 429는 그 경로를 타지 않는다.
     *  (3) 클라이언트가 이미 다루는 코드다. k6는 429를 실패로 세지 않고(order_flow.js
     *      expectedStatuses, readMarket의 ob_ok가 200|429), 봇의 조회는 비200을 캐시값·앵커
     *      무효로 흡수하고 다음 사이클(requote 주기 1초, MM은 지수 백오프)까지 쉰다. 어느 쪽도
     *      429에 즉시 재시도로 반응하지 않아 거절이 폭주를 만들지 않는다. 주문 거절은 같은
     *      client_order_id로 재시도해 멱등 수렴한다.
     * detail은 §2-5 다중버킷 표기를 그대로 쓴다. 계정 버킷과 구분되는 노드 용량 버킷이다.
     */
    public ApiException rejection() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bucket", "node");
        detail.put("scope", scope);
        return new ApiException(429, "too_many_requests",
                "노드 처리 용량을 초과했습니다. 잠시 후 재시도하십시오.", detail, RETRY_AFTER_SECONDS);
    }

    public String scope() {
        return scope;
    }

    public int limit() {
        return limit;
    }

    public int inFlight() {
        return limit - permits.availablePermits();
    }
}

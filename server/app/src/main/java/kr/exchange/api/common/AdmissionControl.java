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
 * 병목은 커넥션이 아니라 커넥션을 기다리는 스레드다. 무한 수용에서는 느린 DB에 블록된 스레드가
 * 쌓여 노드가 자력 회복하지 못한다(라이브 실측).
 *
 * 대기열을 만들지 않는 이유도 같다. 큐를 두면 스레드가 다시 쌓이므로 무대기 tryAcquire이고
 * 세마포어는 비공정이다. 공정 큐는 곧 대기열이다.
 */
public final class AdmissionControl {

    // 재시도 간격. HTTP Retry-After의 최소 유효값이고, 기존 503 경로(NotLeader·NotReady·일시 DB
    // 실패)와 같은 값이라 클라이언트 백오프가 단일하다. permit 회전 시간의 상한은 아니다. dev
    // 프로파일에는 statement_timeout이 없어(prod만 있음) 슬로 윈도에서 한 요청이 이 값보다 오래
    // 슬롯을 쥘 수 있다. 그때 재시도는 다시 거절로 튕기고, 그것이 백프레셔의 정상 동작이다.
    private static final int RETRY_AFTER_SECONDS = 1;

    /**
     * 상한 초과 시 내보낼 응답. 버킷마다 다르다. 용량 초과의 기본형은 429지만, 재조회 수렴 경로는
     * 429가 곧 계약 위반이라 다른 코드를 쓴다.
     */
    public record Shed(int status, String name, String message) {

        /**
         * 용량 초과 기본형. 429인 이유 셋. (1) 거절은 처리 이전이라 상태가 확정 미접수이므로 503의
         * 재조회 계약(§2-3·§7-6)이 불필요한 DB 읽기를 더한다. (2) 인그레스가 접수 경로의 503을 노드
         * 장애로 계상해(proxy_next_upstream http_503, max_fails=1) 정상 리더가 업스트림에서 빠지고
         * 좀비 판정(DB§5.4)의 분모까지 사라진다. (3) k6·봇이 429를 이미 정상 흡수한다.
         */
        /* 문구는 원인을 동시성으로 적는다(API 설계 2-5-a v2.40). 이 게이트가 재는 것은
           동시 in-flight 수이지 노드의 처리 능력이 아니다. 상한이 6 이면 일곱 번째 요청은
           노드가 한가해도 거절된다. 종전 "노드 처리 용량 초과"는 그것을 서버 장애로 읽히게
           해, 설계된 백프레셔를 고장으로 번역했다. 그 오역 제거가 2-3 의 목적이다. */
        public static final Shed CAPACITY = new Shed(429, "too_many_requests",
                "동시 처리 한도 도달. 잠시 후 재시도 가능");

        /**
         * §7-6 재조회 수렴 경로 전용. 호출자가 이미 UNKNOWN이고 k6 규약 판정이 200·404·409만
         * `idempotent_safe`로 보므로(order_flow.js) 429·503은 백프레셔가 곧 멱등 규약 위반이 된다.
         *
         * 이름이 `order_in_progress`가 아닌 이유는 그 이름이 '저널에는 있으나 프로젝션 미반영'이라는
         * 확정 사실을 주장하는데(§4-B-4(a)), 슬롯이 없어 저널을 보지도 못한 상태는 그 주장을 할 수
         * 없어서다. 이중 접수를 부르는 오답은 잘못된 404뿐이고 판정 보류는 그 반대 방향이라 안전하다.
         */
        public static final Shed REQUERY_DEFERRED = new Shed(409, "requery_deferred",
                "접수 여부 확정 불가. Retry-After 후 동일 client_order_id로 재조회 필요");
    }

    private final String scope;
    private final int limit;
    private final Shed shed;
    private final Semaphore permits;
    private final Counter rejected;

    public AdmissionControl(String scope, int limit, Shed shed, MeterRegistry registry) {
        if (scope == null || scope.isBlank() || limit <= 0 || shed == null || registry == null) {
            throw new IllegalArgumentException("scope·limit·shed·registry는 필수");
        }
        this.scope = scope;
        this.limit = limit;
        this.shed = shed;
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

    /** detail은 §2-5 다중버킷 표기를 그대로 쓴다. 계정 버킷과 구분되는 노드 용량 버킷이다. */
    public ApiException rejection() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bucket", "node");
        detail.put("scope", scope);
        return new ApiException(shed.status(), shed.name(), shed.message(), detail,
                RETRY_AFTER_SECONDS);
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

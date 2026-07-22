package kr.exchange.api.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.common.ApiException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주문 접수·취소 거부 계량(하네스 CICD §5-2 exchange.orders.rejected). 라이터 진입 전 거부만
 * 세므로 매칭 핫패스를 블로킹하지 않는다. reason 태그는 유한 에러코드 집합이라 고카디널리티가 아니다.
 *
 * 계량 대상은 클라이언트가 요청을 고쳐야 하는 종결 거부(4xx 중 429·422·400·403·413)로 한정한다.
 * 409 order_in_progress·503 service_unavailable은 재조회로 수렴하는 가용성 신호라 거부율을 오염시키지
 * 않도록 제외한다. 401·404는 인증·부재라 주문 접수 거부가 아니다.
 *
 * 429는 §5-2 (2)·대시보드 PromQL(reason="rate_limited")과 정렬하기 위해 에러코드 too_many_requests
 * 대신 rate_limited로 정규화한다. 나머지 사유는 ApiException 코드를 그대로 쓴다.
 */
public class OrderRejectionMetrics {

    private static final Set<Integer> REJECT_STATUSES = Set.of(400, 403, 413, 422, 429);
    private static final Map<Integer, String> STATUS_REASON_OVERRIDE = Map.of(429, "rate_limited");

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public OrderRejectionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(ApiException e) {
        if (!REJECT_STATUSES.contains(e.status())) {
            return;
        }
        String reason = STATUS_REASON_OVERRIDE.getOrDefault(e.status(), e.name());
        counter(reason).increment();
    }

    private Counter counter(String reason) {
        return counters.computeIfAbsent(reason, r ->
                Counter.builder("exchange.orders.rejected")
                        .description("주문 접수·취소 종결 거부 수")
                        .tag("reason", r)
                        .register(registry));
    }
}

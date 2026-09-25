package kr.exchange.api.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 마켓 파티션 라우팅의 계수(API 설계 §2-5-d 관측, 하네스 CI/CD §5-2).
 *
 * precheck 는 소유권 선검사 필터의 결과만 센다. 게이트웨이가 힌트 없는 단건 취소에 낸 소유 아님 503 은 cancel_not_owner 로
 * 따로 센다. 그래야 선검사 계수를 인그레스 접근 로그의 첫 시도 503 줄 수와 대조할 수 있다(S5 설계 7-2 Z1). hint.rejected 는
 * 힌트와 배치 규칙의 400 수다. 태그 조합을 기동 때 모두 등록해 프로메테우스 increase() 가 첫 증가를 센다.
 */
public final class RoutingCounters {

    public static final String ACCEPTED = "accepted";
    public static final String NOT_OWNER = "not_owner";
    public static final String UNRESOLVED = "unresolved";

    public static final String HINT_MISMATCH = "market_hint_mismatch";
    public static final String HINT_DUPLICATE = "market_hint_duplicate";
    public static final String BATCH_SINGLE_MARKET = "batch_single_market";

    private static final List<String> RESULTS = List.of(ACCEPTED, NOT_OWNER, UNRESOLVED);
    private static final List<String> REASONS = List.of(HINT_MISMATCH, HINT_DUPLICATE, BATCH_SINGLE_MARKET);

    private final Map<String, Counter> precheck = new HashMap<>();
    private final Map<String, Counter> hintRejected = new HashMap<>();
    private final Counter cancelNotOwner;

    public RoutingCounters(MeterRegistry registry) {
        for (String result : RESULTS) {
            precheck.put(result, Counter.builder("exchange.routing.precheck")
                    .tag("result", result)
                    .description("소유권 선검사 필터의 결과 수(accepted 접수함, not_owner 503, unresolved 뒤 단계로 넘김)")
                    .register(registry));
        }
        for (String reason : REASONS) {
            hintRejected.put(reason, Counter.builder("exchange.routing.hint.rejected")
                    .tag("reason", reason)
                    .description("마켓 힌트와 한 배치 한 마켓 규칙으로 400 을 낸 수")
                    .register(registry));
        }
        cancelNotOwner = Counter.builder("exchange.routing.cancel_not_owner")
                .description("게이트웨이가 힌트 없는 단건 취소에 채번 전 소유 아님 503 을 낸 수")
                .register(registry);
    }

    /** 계수를 내보내지 않는 판. 배선하지 않은 시험이 쓴다. */
    public static RoutingCounters untracked() {
        return new RoutingCounters(new SimpleMeterRegistry());
    }

    public void precheck(String result) {
        precheck.get(result).increment();
    }

    public double precheckCount(String result) {
        return precheck.get(result).count();
    }

    public void hintRejected(String reason) {
        hintRejected.get(reason).increment();
    }

    public double hintRejectedCount(String reason) {
        return hintRejected.get(reason).count();
    }

    public void cancelNotOwner() {
        cancelNotOwner.increment();
    }

    public double cancelNotOwnerCount() {
        return cancelNotOwner.count();
    }
}

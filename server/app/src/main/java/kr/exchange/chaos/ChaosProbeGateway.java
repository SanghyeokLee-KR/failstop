package kr.exchange.chaos;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이 평면 경계. downstream.fetch()를 Resilience4j CB로 감싸 assault(지연·예외)를 흡수한다.
 * CB 애스펙트는 call()에, chaos-monkey 애스펙트는 downstream.fetch()에 각각 걸려 조인포인트가 달라
 * 애스펙트 순서 충돌이 없다. 지연 assault는 slow-call로, 예외 assault는 failure로 집계돼 임계 초과 시
 * CB가 open되고 이후 호출은 CallNotPermittedException으로 fail-fast된다.
 */
@Component
@Profile("chaos-monkey")
public class ChaosProbeGateway {

    private final ChaosProbeDownstream downstream;

    public ChaosProbeGateway(ChaosProbeDownstream downstream) {
        this.downstream = downstream;
    }

    @CircuitBreaker(name = "chaosProbe")
    public String call() {
        return downstream.fetch();
    }
}

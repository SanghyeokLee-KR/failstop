package kr.exchange.chaos;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * E8 assault의 유일 주입 대상(chaos.monkey.watcher.beans=chaosProbeDownstream). 게이트웨이 평면의
 * 외부 의존을 대리하는 표식 빈이다. 매칭 코어(engine 모듈, 순수 Java)는 스프링 빈이 아니라 chaos-monkey
 * AOP 위빙 대상이 될 수 없고, 주문·원장 write 경로 빈은 watcher.beans에서 제외돼 assault가 닿지 않는다.
 * 이 단일 빈에 국한함으로써 지연·예외 폭풍이 매칭 무결성에 비전파됨을 구조적으로 보장한다.
 */
@Component
@Profile("chaos-monkey")
public class ChaosProbeDownstream {

    /**
     * assault 활성 시 chaos-monkey가 이 호출에 지연을 삽입하거나 예외를 던진다. 평시에는 즉시 반환한다.
     */
    public String fetch() {
        return "ok";
    }
}

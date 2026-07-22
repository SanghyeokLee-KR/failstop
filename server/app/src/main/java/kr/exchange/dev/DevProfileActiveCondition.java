package kr.exchange.dev;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * dev가 활성 프로파일로 명시된 기동에서만 참. 기본 프로파일(spring.profiles.default=dev)로
 * 도는 통합테스트는 활성 프로파일이 비어 있어 거짓이 된다.
 *
 * @Profile("dev")를 쓰지 않는 이유: @Profile은 기본·활성 프로파일을 구분하지 못해
 * 테스트(활성 미지정, 기본 dev)에서도 켜진다. 그러면 dev 시더가 테스트의 자체 시드보다
 * 먼저 마켓을 만들어(ON CONFLICT DO NOTHING) 마켓 규칙을 덮어써 주문 검증 테스트를 깬다.
 */
public class DevProfileActiveCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        for (String profile : context.getEnvironment().getActiveProfiles()) {
            if ("dev".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}

package kr.exchange.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 운영 시크릿 fail-fast 게이트. 참이면 StartupSecretValidator가 활성화된다.
 *
 * 판정: exchange.require-secrets 프로퍼티가 명시되면 그 값(운영자 강제 on·off). 미명시면
 * prod 프로파일이 활성일 때만 참. DevProfileActiveCondition과 같은 getActiveProfiles() 근거라
 * 활성 프로파일이 비어 있는 통합테스트·기본 dev 기동에는 걸리지 않는다(기존 테스트·데모 보존).
 * "비-dev면 무조건"이 아니라 prod 명시를 요구하는 이유는 bots·demo 등 비 dev 보조 프로파일이
 * 시크릿 없이 기동하는 정상 경로를 깨지 않기 위함이다.
 */
public class RequireSecretsCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String flag = env.getProperty("exchange.require-secrets");
        if (flag != null) {
            return Boolean.parseBoolean(flag);
        }
        for (String profile : env.getActiveProfiles()) {
            if ("prod".equals(profile)) {
                return true;
            }
        }
        return false;
    }
}

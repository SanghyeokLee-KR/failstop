package kr.exchange.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;
import java.util.List;

/**
 * 운영 시크릿·PKI fail-fast 게이트. 참이면 StartupSecretValidator가 활성화된다.
 *
 * 판정 순서:
 *   1. exchange.require-secrets 프로퍼티 명시 시 그 값(운영자 강제 on·off).
 *   2. 유효 프로파일(active 우선, 비면 default)에 prod가 있으면 참.
 *   3. 관리 평면 활성(exchange.admin.enabled=true)이고 유효 프로파일에 dev가 없으면 참.
 *      관리 평면은 최고권한이라 하드닝을 prod 프로파일이 아니라 평면 자체에 건다. 데모·통합테스트는
 *      dev(기본 또는 명시 dev,admin)가 유효 프로파일에 들어 게이트가 꺼지므로 데모 PKI로 기동한다.
 *      SPRING_PROFILES_ACTIVE=admin(dev 없음)이나 prod,admin 같은 실운영 의도 기동만 게이트가 켜져
 *      데모 keystore·blank 시크릿·미핀 trusted-forwarding을 부팅 시점에 차단한다.
 *   4. 그 외 거짓. bots·demo 등 비 dev 보조 프로파일의 시크릿 없는 정상 기동을 깨지 않는다.
 */
public class RequireSecretsCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String flag = env.getProperty("exchange.require-secrets");
        if (flag != null) {
            return Boolean.parseBoolean(flag);
        }
        List<String> effective = effectiveProfiles(env);
        if (effective.contains("prod")) {
            return true;
        }
        boolean adminEnabled = env.getProperty("exchange.admin.enabled", Boolean.class, false);
        return adminEnabled && !effective.contains("dev");
    }

    // 유효 프로파일: active가 비면 default(spring.profiles.default=dev)가 적용된다. active가
    // 비었다고 dev를 놓치면 통합테스트(active 없음, default dev)가 게이트에 걸린다.
    private static List<String> effectiveProfiles(Environment env) {
        String[] active = env.getActiveProfiles();
        return Arrays.asList(active.length > 0 ? active : env.getDefaultProfiles());
    }
}

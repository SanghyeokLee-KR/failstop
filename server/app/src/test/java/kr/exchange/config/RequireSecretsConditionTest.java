package kr.exchange.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시크릿 게이트 판정 분기 고정. admin 게이트(adminEnabled && !dev)와 default 프로파일
 * 폴백은 배선 실수의 결과가 양방향으로 치명적이다: 위양성이면 데모·통합테스트가 시크릿
 * 없이 못 뜨고, 위음성이면 실운영 의도의 admin 평면이 데모 PKI로 기동한다.
 */
class RequireSecretsConditionTest {

    // ── 1순위: 명시 플래그(운영자 강제) ─────────────────────────────────

    @Test
    void explicitFlagForcesGateOnEvenInDev() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("exchange.require-secrets", "true");
        assertTrue(matches(env));
    }

    @Test
    void explicitFlagForcesGateOffEvenInProd() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("exchange.require-secrets", "false");
        assertFalse(matches(env));
    }

    // ── 2순위: prod 프로파일 ────────────────────────────────────────────

    @Test
    void prodProfileTurnsGateOn() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertTrue(matches(env));
    }

    @Test
    void prodWithAdminProfileTurnsGateOn() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "admin");
        env.setProperty("exchange.admin.enabled", "true");
        assertTrue(matches(env));
    }

    // ── 3순위: admin 게이트(adminEnabled && !dev) ───────────────────────

    @Test
    void adminEnabledWithoutDevTurnsGateOn() {
        // SPRING_PROFILES_ACTIVE=admin(dev 없음) = 실운영 의도 기동.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("admin");
        env.setProperty("exchange.admin.enabled", "true");
        assertTrue(matches(env));
    }

    @Test
    void adminEnabledWithDevProfileStaysOff() {
        // 데모·통합테스트의 명시 dev,admin 기동은 데모 PKI로 떠야 한다.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev", "admin");
        env.setProperty("exchange.admin.enabled", "true");
        assertFalse(matches(env));
    }

    @Test
    void adminProfileWithoutEnabledFlagStaysOff() {
        // 게이트 판정 축은 프로파일명이 아니라 관리 평면 활성 프로퍼티다.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("admin");
        assertFalse(matches(env));
    }

    // ── default 프로파일 폴백(active 비었을 때) ─────────────────────────

    @Test
    void adminEnabledFallsBackToDefaultDevAndStaysOff() {
        // active 없음 + spring.profiles.default=dev(통합테스트 기동 형태). dev로 판정해야 한다.
        MockEnvironment env = new MockEnvironment();
        env.setDefaultProfiles("dev");
        env.setProperty("exchange.admin.enabled", "true");
        assertFalse(matches(env));
    }

    @Test
    void adminEnabledWithNonDevDefaultTurnsGateOn() {
        // default 폴백에도 dev가 없으면 admin 게이트가 켜져야 한다.
        MockEnvironment env = new MockEnvironment();
        env.setDefaultProfiles("standalone");
        env.setProperty("exchange.admin.enabled", "true");
        assertTrue(matches(env));
    }

    // ── 4순위: 그 외 거짓 ───────────────────────────────────────────────

    @Test
    void nonDevAuxiliaryProfileWithoutAdminStaysOff() {
        // bots·demo 등 보조 프로파일의 시크릿 없는 정상 기동을 깨지 않는다.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("bots");
        assertFalse(matches(env));
    }

    @Test
    void defaultDevWithoutAdminStaysOff() {
        MockEnvironment env = new MockEnvironment();
        env.setDefaultProfiles("dev");
        assertFalse(matches(env));
    }

    private static boolean matches(Environment env) {
        return new RequireSecretsCondition().matches(context(env), null);
    }

    // Condition.matches는 환경만 읽는다. 나머지 접근자는 도달하지 않아야 하므로 예외로 막는다.
    private static ConditionContext context(Environment env) {
        return new ConditionContext() {
            @Override
            public BeanDefinitionRegistry getRegistry() {
                throw new UnsupportedOperationException("환경 외 접근 금지");
            }

            @Override
            public ConfigurableListableBeanFactory getBeanFactory() {
                throw new UnsupportedOperationException("환경 외 접근 금지");
            }

            @Override
            public Environment getEnvironment() {
                return env;
            }

            @Override
            public ResourceLoader getResourceLoader() {
                throw new UnsupportedOperationException("환경 외 접근 금지");
            }

            @Override
            public ClassLoader getClassLoader() {
                return RequireSecretsConditionTest.class.getClassLoader();
            }
        };
    }
}

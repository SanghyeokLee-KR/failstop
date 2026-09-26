package kr.exchange.config;

import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.ReadRoleFilter;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@Conditional(ReadRoleConfig.ReadRoleCondition.class)
public class ReadRoleConfig {

    @Bean
    static BeanFactoryPostProcessor readRoleStartupGuard(Environment environment) {
        // 빈 생성보다 먼저 검사한다. 잘못 켠 봇·리스 제공자가 시작한 뒤 거부해서는 안 된다.
        validate(environment);
        return beanFactory -> { };
    }

    static void validate(Environment environment) {
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (!profiles.contains("prod") || !profiles.contains("read")
                || !Set.of("prod", "read").containsAll(profiles)) {
            throw new IllegalStateException("조회 역할은 prod,read 프로파일로만 실행합니다.");
        }
        require(environment, "exchange.engine.enabled", "false");
        require(environment, "exchange.node.role", "read");
        require(environment, "spring.flyway.enabled", "false");
        require(environment, "exchange.harness.enabled", "false");
        require(environment, "exchange.admin.enabled", "false");
        require(environment, "exchange.leader.lease.provider", "postgres");
        require(environment, "spring.datasource.hikari.read-only", "true");
        require(environment, "exchange.engine.append-datasource.hikari.read-only", "true");
        require(environment, "exchange.engine.lease-datasource.hikari.read-only", "true");
    }

    static final class ReadRoleCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return isReadRole(context.getEnvironment());
        }
    }

    /**
     * 조회 역할이 아닌 노드. 조회 로그인이 읽지 못하는 표를 주기적으로 읽는 빈에 건다(EngineConfig.reservationBacklogMonitor).
     * 조회 로그인은 assets·markets·trades 만 읽으므로(deploy/provision-runtime-roles.sql) 그런 빈이 조회 노드에서 돌면 매번 권한
     * 오류로 끝난다.
     */
    static final class NotReadRoleCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !isReadRole(context.getEnvironment());
        }
    }

    /** 조회 역할 노드인가. read 프로파일이거나 exchange.node.role 이 read 다. 기동 검사(validate)는 그 뒤에 따로 한다. */
    static boolean isReadRole(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("read"))
                || "read".equals(environment.getProperty("exchange.node.role"));
    }

    private static void require(Environment environment, String key, String expected) {
        if (!expected.equalsIgnoreCase(environment.getProperty(key, ""))) {
            throw new IllegalStateException("조회 역할의 필수 설정이 다릅니다: " + key);
        }
    }

    @Bean
    FilterRegistrationBean<ReadRoleFilter> readRoleFilter(ErrorRenderer errors) {
        FilterRegistrationBean<ReadRoleFilter> registration =
                new FilterRegistrationBean<>(new ReadRoleFilter(errors));
        registration.addUrlPatterns("/*");
        // CORS·인증·관리 필터보다 먼저 실행해 금지된 경로가 DB나 WS에 닿지 않게 한다.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}

package kr.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ErrorRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 관리 평면 배선. exchange.admin.enabled=true인 배포단위(exchange-admin)에서만 활성화되어
 * 고객 평면(/api)과 물리 분리된다. 도메인 서비스는 스프링 어노테이션 없이 여기서 결선한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfig {

    @Bean
    public AdminMtlsResolver adminMtlsResolver(AdminProperties props) {
        return new AdminMtlsResolver(props);
    }

    @Bean
    public AdminOperatorDirectory adminOperatorDirectory(JdbcClient jdbc) {
        return new AdminOperatorDirectory(jdbc);
    }

    @Bean
    public AdminSessionService adminSessionService(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new AdminSessionService(jdbc, objectMapper);
    }

    @Bean
    public AdminAuditService adminAuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new AdminAuditService(jdbc, objectMapper);
    }

    @Bean
    public ApprovalService approvalService(JdbcClient jdbc, AdminProperties props) {
        return new ApprovalService(jdbc, props.getApprovalTtl());
    }

    @Bean
    public AdminOperatorSeeder adminOperatorSeeder(JdbcClient jdbc) {
        return new AdminOperatorSeeder(jdbc);
    }

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(
            AdminMtlsResolver mtlsResolver, AdminSessionService sessions,
            AdminOperatorDirectory operators, AdminAuditService audit, ErrorRenderer errorRenderer,
            @Value("${exchange.admin.rate-limit.capacity:60}") int rateCapacity,
            @Value("${exchange.admin.rate-limit.window-ms:60000}") long rateWindowMs) {
        // 기본은 계약값(§2-5 60토큰/60s, weight 10 = 실효 6req/60s). 통합테스트는 다건 관리 호출을
        // 위해 capacity를 상향 주입한다.
        FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>(
                new AdminAuthFilter(mtlsResolver, sessions, operators, audit, errorRenderer,
                        rateCapacity, rateWindowMs));
        registration.addUrlPatterns("/admin/v1/*");
        registration.setOrder(20);
        return registration;
    }
}

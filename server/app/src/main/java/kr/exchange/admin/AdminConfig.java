package kr.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ErrorRenderer;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
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
@EnableConfigurationProperties({AdminProperties.class, CapacityProperties.class})
public class AdminConfig {

    /**
     * trusted-forwarding 기동은 루프백 바인딩을 강제한다.
     *
     * 이 스위치가 켜지면 관리 신원이 검증된 인증서가 아니라 자가신고 X-Operator-Cn 헤더가 된다.
     * 게이트웨이가 mTLS 를 종단하고 그 헤더를 실어 주는 토폴로지 전용이고, 그 게이트웨이가
     * 없는 곳에서 켜지면 서버에 닿을 수 있는 누구나 관리 평면에 닿는다.
     *
     * 판정을 프로파일이 아니라 바인딩 주소에 건다. 프로파일 조건은 조합을 타서 꺼질 수 있지만
     * 주소는 배포가 감출 수 없는 사실이다(관리자 설계 1-3-a).
     *
     * 주소는 ServerProperties 에서 읽는다. 문자열 프로퍼티를 직접 읽으면 게이트가 검사하는 값과
     * 톰캣이 실제로 바인딩하는 값이 갈릴 수 있다. ServerProperties#getAddress 는 커넥터에
     * 그대로 넘어가는 바로 그 객체이고, InetAddress#isLoopbackAddress 가 127.0.0.0/8 전역과
     * ::1, ::ffff:127.0.0.1 을 한 번에 판정한다. 문자열 비교로는 127.0.0.2 를 놓친다.
     */
    @Bean
    public AdminForwardingBindingGuard adminForwardingBindingGuard(
            AdminProperties props, ServerProperties server) {
        if (props.getMtls().isTrustedForwarding() && !isLoopback(server.getAddress())) {
            throw new IllegalStateException(bindingRefusal(server.getAddress()));
        }
        return new AdminForwardingBindingGuard();
    }

    /** 미지정(null)은 전 인터페이스 바인딩이다. 루프백이 아니다 */
    static boolean isLoopback(InetAddress address) {
        return address != null && address.isLoopbackAddress();
    }

    static String bindingRefusal(InetAddress address) {
        return "exchange.admin.mtls.trusted-forwarding=true 인데 server.address 가 루프백이 아니다"
                + "(현재 " + (address == null ? "미지정. 전 인터페이스 바인딩" : address.getHostAddress())
                + "). 이 조합은 관리 평면 인증을 자가신고 헤더로 내리므로 외부에 노출될 수 있는"
                + " 바인딩에서 금지한다. server.address 를 127.0.0.1 로 고정하거나"
                + " trusted-forwarding 을 끄고 mTLS 클라이언트 인증서를 쓰라(관리자 설계 1-3-a).";
    }

    /** 배선 순서를 잡기 위한 표식 빈. 검증은 생성 시점에 끝난다 */
    public static final class AdminForwardingBindingGuard {
    }

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

    /**
     * 용량 콘솔의 관측면(ADR 0015). 노드마다 액추에이터를 직접 물어본다.
     * 타임아웃을 설정에서 받는 이유는 한 노드가 죽어 있을 때 그 대기가 화면 응답에
     * 그대로 더해지기 때문이다. 병렬로 던지되 각자 상한을 건다.
     */
    @Bean
    public NodeProbe nodeProbe(CapacityProperties props) {
        return new NodeProbe(props.getProbeTimeout());
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

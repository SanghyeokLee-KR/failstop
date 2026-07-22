package kr.exchange.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 관리 평면 구성. 운영 프로파일은 mTLS 클라이언트 인증서 검증을 강제하고
 * trusted-forwarding을 끈다. dev/demo는 mTLS 종단 게이트웨이가 전달하는 검증된 신원
 * 헤더를 신뢰할 수 있다(관리자 설계 §1-3 게이트웨이 토폴로지).
 */
@ConfigurationProperties(prefix = "exchange.admin")
public class AdminProperties {

    /** 관리 평면 활성화. 고객 평면(/api)과 물리 분리된 배포단위에서만 true. */
    private boolean enabled = false;

    /** 세션 TTL. 짧게 유지한다(§1-3 단기 세션). */
    private Duration sessionTtl = Duration.ofMinutes(30);

    /** 승인 토큰 TTL(§3-6 5분 고정). */
    private Duration approvalTtl = Duration.ofMinutes(5);

    private final Mtls mtls = new Mtls();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getApprovalTtl() {
        return approvalTtl;
    }

    public void setApprovalTtl(Duration approvalTtl) {
        this.approvalTtl = approvalTtl;
    }

    public Mtls getMtls() {
        return mtls;
    }

    public static class Mtls {

        /**
         * mTLS 종단을 게이트웨이가 하고 검증된 인증서 신원을 헤더로 전달하는 토폴로지를
         * 허용한다. 운영은 false(Spring SSL client-auth로 X509 직접 검증). true일 때만
         * forward-header를 신뢰하며, 인그레스가 외부 요청의 이 헤더를 제거하는 것이 전제다.
         */
        private boolean trustedForwarding = false;

        /** 게이트웨이가 검증된 클라이언트 인증서 CN을 싣는 헤더. trusted-forwarding에서만 유효. */
        private String forwardHeader = "X-Operator-Cn";

        public boolean isTrustedForwarding() {
            return trustedForwarding;
        }

        public void setTrustedForwarding(boolean trustedForwarding) {
            this.trustedForwarding = trustedForwarding;
        }

        public String getForwardHeader() {
            return forwardHeader;
        }

        public void setForwardHeader(String forwardHeader) {
            this.forwardHeader = forwardHeader;
        }
    }
}

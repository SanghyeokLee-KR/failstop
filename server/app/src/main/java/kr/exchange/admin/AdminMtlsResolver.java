package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.Optional;

/**
 * mTLS 클라이언트 인증서에서 operator_id(=CN)를 도출한다. operator_id의 권위 소스는
 * 인증 주체이며 바디 자가신고 값이 아니다(API §3-6 v2.4).
 *
 * 도출 우선순위:
 *   1. jakarta.servlet.request.X509Certificate 속성(Spring SSL client-auth 직접 검증, 운영 기본).
 *   2. trusted-forwarding 시 게이트웨이가 실은 검증된 CN 헤더(mTLS 종단 게이트웨이 토폴로지).
 * 둘 다 없으면 신원 없음(빈 Optional).
 */
public class AdminMtlsResolver {

    private static final String X509_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final AdminProperties props;

    public AdminMtlsResolver(AdminProperties props) {
        this.props = props;
    }

    public Optional<String> resolveOperatorId(HttpServletRequest request) {
        Object attr = request.getAttribute(X509_ATTRIBUTE);
        if (attr instanceof X509Certificate[] chain && chain.length > 0) {
            return commonName(chain[0].getSubjectX500Principal());
        }
        if (props.getMtls().isTrustedForwarding()) {
            String forwarded = request.getHeader(props.getMtls().getForwardHeader());
            if (forwarded != null && !forwarded.isBlank()) {
                return Optional.of(forwarded.trim());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> commonName(X500Principal principal) {
        try {
            LdapName name = new LdapName(principal.getName());
            for (Rdn rdn : name.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return Optional.of(String.valueOf(rdn.getValue()));
                }
            }
        } catch (Exception e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}

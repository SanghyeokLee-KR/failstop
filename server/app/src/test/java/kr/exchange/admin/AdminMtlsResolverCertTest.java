package kr.exchange.admin;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 게이트웨이 헤더가 아니라 Spring SSL client-auth가 채우는 X509 속성 경로를 실 인증서(op-01.p12)로
 * 태운다. 데모 자산이 CN=op-01로 발급됐고 리졸버가 그 CN을 뽑는지 확인한다(§1-6 반결탁 전제).
 */
class AdminMtlsResolverCertTest {

    @Test
    void resolvesOperatorIdFromDemoClientCertificateCn() throws Exception {
        X509Certificate cert = loadCert("/admin-demo/op-01.p12", "op-01", "changeit");
        AdminProperties props = new AdminProperties();
        // 게이트웨이 전달을 끈 상태에서도 X509 속성 경로로 신원이 나와야 한다.
        props.getMtls().setTrustedForwarding(false);
        AdminMtlsResolver resolver = new AdminMtlsResolver(props);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{cert});

        Optional<String> operatorId = resolver.resolveOperatorId(request);
        assertTrue(operatorId.isPresent(), "인증서에서 operator_id를 도출해야 한다");
        assertEquals("op-01", operatorId.get());
    }

    @Test
    void missingCertificateYieldsNoIdentityWhenForwardingOff() {
        AdminProperties props = new AdminProperties();
        props.getMtls().setTrustedForwarding(false);
        AdminMtlsResolver resolver = new AdminMtlsResolver(props);
        assertTrue(resolver.resolveOperatorId(new MockHttpServletRequest()).isEmpty(),
                "인증서·전달 헤더가 없으면 신원 없음");
    }

    private static X509Certificate loadCert(String resource, String alias, String pass)
            throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = AdminMtlsResolverCertTest.class.getResourceAsStream(resource)) {
            ks.load(in, pass.toCharArray());
        }
        return (X509Certificate) ks.getCertificate(alias);
    }
}

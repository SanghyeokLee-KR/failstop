package kr.exchange.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 운영 기동 시크릿 fail-closed 검증(실서비스_준비도_감사 §시크릿·설정). require-secrets 게이트에서만
 * 활성화된다(RequireSecretsCondition: prod 프로파일 또는 dev 없는 admin 평면 활성). 마스터키·페퍼·
 * 세션 시드 중 하나라도 blank면 기동을 중단한다.
 *
 * 근거: 미주입 시 세 클래스가 프로세스 한정 랜덤으로 폴백해 부팅은 성공하나 재기동 후 (1) 페퍼
 * 재생성으로 전 회원 해시 검증 불가, (2) 마스터키 재생성으로 api_keys 복호화·HMAC 인증 붕괴,
 * (3) 세션 시드 재생성으로 전 세션 무효가 무성 진행된다. 헬스체크는 통과해 장애가 은폐되므로
 * 배포 시점에 차단한다.
 *
 * 관리 평면(SSL) 기동이면 데모 PKI·기본 keystore 비밀번호도 함께 가드한다(운영 mTLS를 데모
 * 자산으로 열지 못하게 한다).
 */
@Configuration
@Conditional(RequireSecretsCondition.class)
public class StartupSecretValidator {

    public StartupSecretValidator(
            @Value("${exchange.auth.master-key-base64:}") String masterKey,
            @Value("${exchange.member.password-pepper-base64:}") String pepper,
            @Value("${exchange.member.session-seed-base64:}") String sessionSeed,
            @Value("${server.ssl.key-store:}") String keyStore,
            @Value("${server.ssl.key-store-password:}") String keyStorePassword,
            @Value("${exchange.admin.mtls.trusted-forwarding:false}") boolean trustedForwarding) {
        requireSecret("exchange.auth.master-key-base64", masterKey);
        requireSecret("exchange.member.password-pepper-base64", pepper);
        requireSecret("exchange.member.session-seed-base64", sessionSeed);
        if (keyStore != null && !keyStore.isBlank()) {
            if ("changeit".equals(keyStorePassword)) {
                throw new IllegalStateException(
                        "운영 기동에서 keystore 비밀번호가 changeit이다. 운영 PKI로 교체하라.");
            }
            if (keyStore.contains("admin-demo")) {
                throw new IllegalStateException(
                        "운영 기동에서 데모 keystore(config/admin-demo)를 사용한다. 운영 PKI로 교체하라.");
            }
        }
        // 미핀 trusted-forwarding 차단(RequireSecretsCondition javadoc 계약). 이 게이트가 켜진
        // 기동에서 trusted-forwarding=true면 앱이 소스 검증 없이 X-Operator-Cn 헤더를 관리 신원으로
        // 신뢰하므로(AdminMtlsResolver), 게이트웨이 우회·인그레스 미strip 시 mTLS 우회 창이 열린다.
        // 소스 핀(IP 허용목록·게이트웨이 상호인증) 배선이 없는 현재는 fail-closed로 기동을 막는다.
        if (trustedForwarding) {
            throw new IllegalStateException(
                    "운영 기동에서 exchange.admin.mtls.trusted-forwarding=true다. 소스 핀 없는 헤더 신뢰는 "
                            + "mTLS 우회 창이므로 false로 두고 Spring SSL client-auth로 X509를 직접 검증하라.");
        }
    }

    private static void requireSecret(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("운영 기동 필수 시크릿 미주입: " + key
                    + ". 주입 없이는 재기동 후 인증이 무성 붕괴한다.");
        }
    }
}

package kr.exchange.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기동 fail-closed 게이트 회귀. require-secrets 게이트가 켜진 기동(이 빈이 생성되는 조건)에서
 * trusted-forwarding=true는 소스 검증 없는 X-Operator-Cn 헤더 신뢰(mTLS 우회 창)이므로 기동을
 * 막아야 한다(RequireSecretsCondition javadoc 계약). 기본값 false는 통과한다.
 */
class StartupSecretValidatorTest {

    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void trustedForwardingTrueHaltsStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new StartupSecretValidator(SECRET, SECRET, SECRET, "", "", true));
        assertTrue(e.getMessage().contains("trusted-forwarding"));
    }

    @Test
    void trustedForwardingFalsePassesWithSecretsPresent() {
        assertDoesNotThrow(() ->
                new StartupSecretValidator(SECRET, SECRET, SECRET, "", "", false));
    }

    @Test
    void blankSecretStillHaltsRegardlessOfForwarding() {
        assertThrows(IllegalStateException.class, () ->
                new StartupSecretValidator("", SECRET, SECRET, "", "", false));
    }
}

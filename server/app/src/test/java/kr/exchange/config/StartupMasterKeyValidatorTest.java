package kr.exchange.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 마스터키 fail-closed 게이트 회귀.
 *
 * 이 게이트가 없으면 blank 마스터키로 기동이 성공하고, SecretCipher가 프로세스 한정 랜덤
 * 키로 폴백해 기존 enc_secret 복호화가 전건 실패한다. 헬스체크는 인증 경로를 지나지 않아
 * 통과하므로 죽은 채로 healthy가 된다. 실측 사고(2026-08-30)에서 3시간 49분간 그 상태였다.
 *
 * 메시지에 복구 절차가 들어 있는지도 건다. 게이트가 기동을 막는 것 자체가 사고이므로,
 * 그 로그를 보는 사람이 다음 수를 알 수 있어야 한다. 막기만 하고 방법을 안 알리면
 * 발표 중에 스택이 안 뜨는 상태로 시간을 쓴다.
 */
class StartupMasterKeyValidatorTest {

    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void blankKeyHaltsStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StartupMasterKeyValidator(""));
        assertTrue(e.getMessage().contains("마스터키가 비어 있다"), e.getMessage());
        assertTrue(e.getMessage().contains("--env-file"),
                "복구 절차가 메시지에 있어야 한다. 막기만 하고 방법을 안 알리면 안 된다");
    }

    @Test
    void nullKeyHaltsStartup() {
        // 프로퍼티 미정의는 @Value 기본값으로 빈 문자열이 오지만, 직접 주입 경로도 막는다.
        assertThrows(IllegalStateException.class, () -> new StartupMasterKeyValidator(null));
    }

    @Test
    void whitespaceOnlyKeyHaltsStartup() {
        // 공백만 있는 값은 SecretCipher의 isBlank 분기에서 폴백을 타므로 같이 막아야 한다.
        assertThrows(IllegalStateException.class, () -> new StartupMasterKeyValidator("   "));
    }

    @Test
    void presentKeyPasses() {
        assertDoesNotThrow(() -> new StartupMasterKeyValidator(KEY));
    }
}

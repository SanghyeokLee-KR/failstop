package kr.exchange.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 마스터키 blank 기동을 막는다. 켜져 있으면 blank일 때 기동을 중단한다.
 *
 * 왜 StartupSecretValidator를 재사용하지 않는가. 그쪽은 pepper·세션 시드·keystore 비밀번호까지
 * 함께 요구하고 조건이 RequireSecretsCondition이다. 카오스 스택은 SPRING_PROFILES_ACTIVE에
 * dev가 있어 그 게이트가 꺼져 있고, 켜면 데모에 없는 값 셋을 더 요구해 전 노드가 기동 불능이
 * 된다. 이 클래스는 축을 마스터키 하나로 좁혀 데모에서도 켤 수 있게 한다.
 *
 * 왜 필요한가. blank면 SecretCipher가 프로세스 한정 랜덤 키로 폴백한다. 부팅은 성공하고
 * 헬스체크도 통과하지만 기존 enc_secret 복호화가 전건 실패해 인증이 500으로 끝난다.
 * 실측 사고(2026-08-30): --env-file 없이 부분 재기동해 CHAOS_MASTER_KEY_BASE64가 빈 값이
 * 됐고 POST /api/v1/orders가 3시간 49분간 100% 500이었다. 그동안 진입 조건 12행이 전부
 * green이었다. 헬스체크는 인증 경로를 지나지 않고, 판정 API는 recovery_runs와 failover_runs만
 * 보며, 봇은 거부를 무음으로 넘긴다. 어느 축도 이 상태를 말하지 않는다.
 *
 * 그래서 기동 시점에 끊는다. 죽은 채로 뜨는 것보다 안 뜨는 편이 원인 분리가 빠르다.
 * 기본값은 꺼짐이다. 단위 테스트와 로컬 dev 기동은 blank로 돈다.
 */
@Component
@ConditionalOnProperty(name = "exchange.auth.require-master-key", havingValue = "true")
public class StartupMasterKeyValidator {

    public StartupMasterKeyValidator(
            @Value("${exchange.auth.master-key-base64:}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                    "마스터키가 비어 있다(exchange.auth.master-key-base64). "
                            + "이 상태로 뜨면 프로세스 한정 임시 키로 동작해 기존 API 키 복호화가 "
                            + "전건 실패하고 인증이 500으로 끝난다. 헬스체크는 통과하므로 겉으로는 "
                            + "정상이다. "
                            + "복구: compose 실행에 --env-file harness/.env.chaos를 붙인다. "
                            + "정본 기동은 harness/scripts/up.ps1이고 그것이 이 플래그를 붙인다.");
        }
    }
}

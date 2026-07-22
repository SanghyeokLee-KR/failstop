package kr.exchange.api.auth;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 데모 계정 API 키 시드 헬퍼(§3-2). 완전 시뮬레이션 전용 키만 발급한다.
 * secret은 발급 시점에만 평문으로 반환하고 저장은 AES-256-GCM 암호문이다.
 */
public class DemoKeySeeder {

    public record IssuedKey(String accessKey, String secretKey) {
    }

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public DemoKeySeeder(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public IssuedKey issue(long userId, String scope, String label) {
        String accessKey = "AK" + randomToken(30);
        String secretKey = randomToken(43);
        jdbc.sql("""
                        INSERT INTO api_keys (user_id, access_key, enc_secret, label, scope)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(userId, accessKey, cipher.encrypt(secretKey), label, scope)
                .update();
        return new IssuedKey(accessKey, secretKey);
    }

    private String randomToken(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                .replace("-", "A").replace("_", "B").substring(0, length);
    }
}

package kr.exchange.api.auth;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.security.SecureRandom;

/**
 * 데모 계정 API 키 시드 헬퍼(§3-2). 완전 시뮬레이션 전용 키만 발급한다.
 * secret은 발급 시점에만 평문으로 반환하고 저장은 AES-256-GCM 암호문이다.
 */
public class DemoKeySeeder {

    // base64url 인코딩 후 '-','_'를 특정 문자로 치환하면 그 문자만 과대 표현돼 엔트로피가 준다.
    // 균일 알파벳에서 문자별로 직접 뽑아 편향을 없앤다.
    private static final char[] TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

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
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]);
        }
        return token.toString();
    }
}

package kr.exchange.api.member;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 회원 비밀번호 해싱(회원_설계.md §1-2). Argon2id(파라미터 인코딩 문자열 내장) + 서버 페퍼.
 *
 * 페퍼는 해시 입력에 결합한다. 단순 접합은 password 끝과 pepper 시작 경계가 모호해
 * 서로 다른 (password, pepper) 쌍이 같은 입력을 만들 수 있으므로, HMAC-SHA256(pepper, password)로
 * 고정 길이 파생값을 만든 뒤 Argon2id에 넣는다. 검증도 같은 파생을 거친다.
 *
 * 페퍼 미주입 시 프로세스 한정 랜덤 페퍼로 동작한다. 재기동 후 기존 해시는 검증 불가이며
 * 이는 데모 허용 범위다(SecretCipher·DemoSessionService와 동일한 dev 폴백 규약).
 */
public class PasswordHasher {

    public static final String ALGO = "ARGON2ID";
    private static final Logger log = LoggerFactory.getLogger(PasswordHasher.class);

    // Spring Security 5.8 기본값(saltLen16, hashLen32, parallelism1, memory16MiB, iterations2).
    private final Argon2PasswordEncoder encoder =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final byte[] pepper;

    public PasswordHasher(String pepperBase64) {
        if (pepperBase64 == null || pepperBase64.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.pepper = generated;
            log.warn("비밀번호 페퍼 미주입: 프로세스 한정 임시 페퍼로 동작한다. 재기동 시 기존 회원 해시는 검증 불가.");
        } else {
            byte[] decoded = Base64.getDecoder().decode(pepperBase64);
            // SecretCipher·MemberSessionService와 대칭. 오설정 페퍼를 기동 시점에 검출한다.
            if (decoded.length != 32) {
                throw new IllegalArgumentException("비밀번호 페퍼는 base64 인코딩된 32바이트여야 한다");
            }
            this.pepper = decoded;
        }
    }

    public String hash(String rawPassword) {
        return encoder.encode(peppered(rawPassword));
    }

    public boolean matches(String rawPassword, String encoded) {
        return encoder.matches(peppered(rawPassword), encoded);
    }

    private String peppered(String rawPassword) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            byte[] out = mac.doFinal(rawPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("페퍼 결합 실패", e);
        }
    }
}

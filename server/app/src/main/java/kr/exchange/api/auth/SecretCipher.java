package kr.exchange.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * api_keys.enc_secret 봉투암호화(AES-256-GCM, API 설계 §3-2). HMAC 재계산에 복호화가
 * 필요하므로 비가역 해시 저장은 금지다. 마스터키는 환경변수 주입이 정본이고 저장소 평문 금지.
 *
 * 저장 형식: base64( IV 12바이트 || ciphertext+tag ). IV는 키 저장마다 새로 뽑는다.
 */
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec masterKey;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(String masterKeyBase64) {
        byte[] key;
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            // dev 편의 경로. 프로세스 생존 동안만 유효한 임시 키라 재기동 후 기존 enc_secret은
            // 복호화 불가다. 운영·데모 구동은 EXCHANGE_MASTER_KEY_BASE64 주입이 전제다.
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            log.warn("마스터키 미주입: 프로세스 한정 임시 키로 동작한다. 재기동 시 기존 API 키는 무효.");
        } else {
            key = Base64.getDecoder().decode(masterKeyBase64);
            if (key.length != 32) {
                throw new IllegalArgumentException("마스터키는 base64 인코딩된 32바이트여야 한다");
            }
        }
        this.masterKey = new SecretKeySpec(key, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, out, IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("secret 암호화 실패", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_BITS, in, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(in, IV_LENGTH, in.length - IV_LENGTH);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("secret 복호화 실패", e);
        }
    }
}

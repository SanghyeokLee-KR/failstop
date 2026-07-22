package kr.exchange.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 데모 세션 토큰(§3-7) 발급·검증. 브라우저 데모 전용 경로다.
 *
 * 서명 키는 프로세스 한정 랜덤 키다. 재기동 시 전 세션이 무효가 되지만 만료 15분과
 * 결합하면 데모 허용 범위이고, 서버 서명 비밀을 브라우저에 내리지 않는 계약
 * (웹 화면명세서 §6.1)이 우선이다.
 */
public class DemoSessionService {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final String TYP = "demo_session";
    private static final String HEADER_B64 = base64Url(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] key;

    public DemoSessionService() {
        this.key = new byte[32];
        new SecureRandom().nextBytes(this.key);
    }

    public String issue(long userId, String externalId, Duration ttl) {
        long iat = System.currentTimeMillis() / 1000;
        long exp = iat + ttl.toSeconds();
        String payload = "{\"typ\":\"" + TYP + "\",\"sub\":\"" + externalId
                + "\",\"user_id\":" + userId + ",\"scope\":\"TRADE\",\"iat\":" + iat
                + ",\"exp\":" + exp + "}";
        String signingInput = HEADER_B64 + "."
                + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(signingInput));
    }

    /** ApiAuthService가 typ=demo_session 판별 후 위임한다. HMAC canonical·nonce는 미적용(§3-7). */
    static boolean isDemoSession(JsonNode payload) {
        return TYP.equals(payload.path("typ").asText());
    }

    AuthPrincipal verify(String[] tokenParts, JsonNode payload) {
        String signingInput = tokenParts[0] + "." + tokenParts[1];
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(tokenParts[2]);
        } catch (IllegalArgumentException e) {
            throw unauthorized("세션 토큰 인코딩이 올바르지 않습니다.");
        }
        if (!MessageDigest.isEqual(hmac(signingInput), provided)) {
            throw unauthorized("세션 서명 검증에 실패했습니다.");
        }
        long exp = payload.path("exp").asLong(0);
        if (exp <= System.currentTimeMillis() / 1000) {
            throw unauthorized("세션이 만료되었습니다. 다시 발급받으십시오.");
        }
        if (!payload.hasNonNull("user_id") || !"TRADE".equals(payload.path("scope").asText())) {
            throw unauthorized("세션 클레임이 올바르지 않습니다.");
        }
        return new AuthPrincipal(payload.get("user_id").asLong(), "demo-session", "TRADE");
    }

    private byte[] hmac(String data) {
        return ApiAuthService.hmacSha256(new String(
                Base64.getEncoder().encode(key), StandardCharsets.UTF_8), data);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(401, "jwt_verification", message);
    }
}

package kr.exchange.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * 거래 평면 인증 검증기. 검증 순서는 §3-4 계약 그대로다:
 * JWT 파싱 → HMAC 대조 → timestamp 창 → nonce → canonical(query_hash) → scope.
 * 순서를 바꾸면 실패 코드가 계약과 달라진다(예: 서명이 깨진 요청이 nonce_used로 보이는 오류).
 */
public class ApiAuthService {

    private static final long DEFAULT_RECV_WINDOW_MS = 5_000;
    private static final long MAX_RECV_WINDOW_MS = 60_000;
    private static final long CLOCK_SKEW_MS = 1_000;

    private final ApiKeyDirectory keys;
    private final SecretCipher cipher;
    private final NonceStore nonces;
    private final ObjectMapper objectMapper;
    private final DemoSessionService demoSessions;

    public ApiAuthService(ApiKeyDirectory keys, SecretCipher cipher, NonceStore nonces,
                          ObjectMapper objectMapper, DemoSessionService demoSessions) {
        this.keys = keys;
        this.cipher = cipher;
        this.nonces = nonces;
        this.objectMapper = objectMapper;
        this.demoSessions = demoSessions;
    }

    /** canonical은 호출측이 요청 형태(a: 쿼리, b: JCS 바디)에 맞춰 만들어 전달한다. */
    public AuthPrincipal verify(String authorizationHeader, String canonical, boolean needsTrade) {
        // 1. JWT 파싱
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized("jwt_verification", "Authorization Bearer JWT가 필요합니다.");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized("jwt_verification", "JWT 형식이 올바르지 않습니다.");
        }
        JsonNode header = decodeJson(parts[0]);
        JsonNode payload = decodeJson(parts[1]);
        if (!"HS256".equals(header.path("alg").asText())) {
            throw unauthorized("jwt_verification", "지원하지 않는 서명 알고리즘입니다.");
        }
        if (DemoSessionService.isDemoSession(payload)) {
            // 브라우저 데모 세션(§3-7): Bearer 단독 인증. canonical·nonce·timestamp 미적용.
            // scope는 발급 시 TRADE 고정이라 needsTrade 검사가 항상 성립한다.
            return demoSessions.verify(parts, payload);
        }
        String accessKey = payload.path("access_key").asText(null);
        String nonce = payload.path("nonce").asText(null);
        String queryHash = payload.path("query_hash").asText(null);
        if (accessKey == null || nonce == null || queryHash == null
                || !payload.hasNonNull("timestamp")) {
            throw unauthorized("jwt_verification", "필수 클레임이 누락되었습니다.");
        }
        if (payload.has("query_hash_alg")
                && !"SHA512".equals(payload.path("query_hash_alg").asText())) {
            throw unauthorized("jwt_verification", "지원하지 않는 query_hash_alg입니다.");
        }

        // 2. access_key로 secret 복호화 → HMAC-SHA256 재계산
        ApiKeyDirectory.ApiKey key = keys.findActive(accessKey)
                .orElseThrow(() -> unauthorized("jwt_verification", "유효하지 않은 access_key입니다."));
        String secret = cipher.decrypt(key.encSecret());
        byte[] expected = hmacSha256(secret, parts[0] + "." + parts[1]);
        byte[] provided = base64UrlDecode(parts[2]);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw unauthorized("jwt_verification", "서명 검증에 실패했습니다.");
        }

        // 3. timestamp 단방향 창: now - recvWindow <= ts <= now + skew
        long recvWindow = Math.min(
                payload.hasNonNull("recvWindow") ? payload.get("recvWindow").asLong()
                        : DEFAULT_RECV_WINDOW_MS,
                MAX_RECV_WINDOW_MS);
        long ts = payload.get("timestamp").asLong();
        long now = System.currentTimeMillis();
        if (ts < now - recvWindow || ts > now + CLOCK_SKEW_MS) {
            throw unauthorized("timestamp_out_of_window", "timestamp가 허용창 밖입니다.");
        }

        // 4. nonce 중복 차단. TTL은 허용창 전체(recvWindow + skew)를 덮는다.
        if (!nonces.register(accessKey + ":" + nonce, recvWindow + CLOCK_SKEW_MS)) {
            throw unauthorized("nonce_used", "재사용된 nonce입니다.");
        }

        // 5. canonical(query_hash) 대조: method + path 바인딩 포함
        String recomputed = CanonicalRequests.sha512Hex(canonical);
        if (!recomputed.equalsIgnoreCase(queryHash.toLowerCase(Locale.ROOT))) {
            throw unauthorized("invalid_query_payload", "canonical 서명이 일치하지 않습니다.");
        }

        // 6. scope
        if (needsTrade && !"TRADE".equals(key.scope())) {
            throw new ApiException(403, "out_of_scope", "이 API 키에는 주문 권한이 없습니다.");
        }
        return new AuthPrincipal(key.userId(), key.accessKey(), key.scope());
    }

    private JsonNode decodeJson(String base64Url) {
        try {
            return objectMapper.readTree(base64UrlDecode(base64Url));
        } catch (Exception e) {
            throw unauthorized("jwt_verification", "JWT 디코딩에 실패했습니다.");
        }
    }

    private static byte[] base64UrlDecode(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(401, "jwt_verification", "JWT 인코딩이 올바르지 않습니다.");
        }
    }

    static byte[] hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    private static ApiException unauthorized(String name, String message) {
        return new ApiException(401, name, message);
    }
}

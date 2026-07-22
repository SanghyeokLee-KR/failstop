package kr.exchange.api;

import kr.exchange.api.auth.CanonicalRequests;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 테스트용 클라이언트 서명기(§3-3, §3-5 의사코드와 동일 규약).
 * canonical 산출은 서버 구현(CanonicalRequests)을 공유해 왕복 계약을 검증한다.
 */
final class TestSigner {

    private TestSigner() {
    }

    static String bearerForBody(String accessKey, String secret, String method, String path,
                                byte[] body) {
        String canonical = CanonicalRequests.forBody(method, path, body);
        return bearer(accessKey, secret, CanonicalRequests.sha512Hex(canonical),
                System.currentTimeMillis(), UUID.randomUUID().toString());
    }

    static String bearerForQuery(String accessKey, String secret, String method, String path,
                                 Map<String, String> params) {
        return bearer(accessKey, secret, queryHash(method, path, params),
                System.currentTimeMillis(), UUID.randomUUID().toString());
    }

    static String bearerWith(String accessKey, String secret, String queryHash,
                             long timestamp, String nonce) {
        return bearer(accessKey, secret, queryHash, timestamp, nonce);
    }

    static String queryHash(String method, String path, Map<String, String> params) {
        Map<String, String[]> multi = new TreeMap<>();
        params.forEach((k, v) -> multi.put(k, new String[]{v}));
        return CanonicalRequests.sha512Hex(CanonicalRequests.forQuery(method, path, multi));
    }

    static String wsQueryHash() {
        return CanonicalRequests.sha512Hex("GET\n/ws/v1\n");
    }

    private static String bearer(String accessKey, String secret, String queryHash,
                                 long timestamp, String nonce) {
        String header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64url("{\"access_key\":\"" + accessKey + "\",\"nonce\":\"" + nonce
                + "\",\"timestamp\":" + timestamp + ",\"recvWindow\":5000,\"query_hash\":\""
                + queryHash + "\",\"query_hash_alg\":\"SHA512\"}");
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(secret, header + "." + payload));
        return "Bearer " + header + "." + payload + "." + signature;
    }

    private static String b64url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

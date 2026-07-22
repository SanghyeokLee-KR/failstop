package kr.exchange.sim;

import kr.exchange.api.auth.CanonicalRequests;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 봇 전용 외부 HTTP 클라이언트. 게이트웨이 내부 서비스를 참조하지 않고 §3 서명 규약의
 * HTTP 왕복만 사용한다. canonical 산출은 서버와 동일 규약(CanonicalRequests)을 공유해
 * 클라이언트 서명 왕복 자체가 상시 회귀 검증이 되게 한다.
 */
public class BotApiClient {

    public record Response(int status, String body) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final BotCredentials credentials;
    // nonce는 유일성만 요구된다(§3-4). 결정 가능한 열로 만들어 요청 재현성을 유지한다.
    private final AtomicLong nonceSeq = new AtomicLong();
    private final String noncePrefix;

    public BotApiClient(String baseUrl, BotCredentials credentials, String noncePrefix) {
        this.baseUrl = baseUrl;
        this.credentials = credentials;
        this.noncePrefix = noncePrefix;
    }

    public Response placeOrder(String jsonBody) {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        String bearer = bearer("POST", "/api/v1/orders",
                CanonicalRequests.sha512Hex(
                        CanonicalRequests.forBody("POST", "/api/v1/orders", body)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/orders"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request);
    }

    public Response cancelOrder(String externalOrderId) {
        String path = "/api/v1/orders/" + externalOrderId;
        String bearer = bearer("DELETE", path, queryHash("DELETE", path, Map.of()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", bearer)
                .DELETE()
                .build();
        return send(request);
    }

    public Response getPublic(String pathWithQuery) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathWithQuery))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return send(request);
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            return new Response(-1, e.getMessage() == null ? "io_error" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(-1, "interrupted");
        }
    }

    private static String queryHash(String method, String path, Map<String, String> params) {
        Map<String, String[]> multi = new TreeMap<>();
        params.forEach((k, v) -> multi.put(k, new String[]{v}));
        return CanonicalRequests.sha512Hex(CanonicalRequests.forQuery(method, path, multi));
    }

    private String bearer(String method, String path, String queryHash) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String nonce = noncePrefix + "-" + nonceSeq.incrementAndGet();
        String payload = base64Url("{\"access_key\":\"" + credentials.accessKey()
                + "\",\"nonce\":\"" + nonce + "\",\"timestamp\":" + System.currentTimeMillis()
                + ",\"recvWindow\":5000,\"query_hash\":\"" + queryHash
                + "\",\"query_hash_alg\":\"SHA512\"}");
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(credentials.secretKey(), header + "." + payload));
        return "Bearer " + header + "." + payload + "." + signature;
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }
}

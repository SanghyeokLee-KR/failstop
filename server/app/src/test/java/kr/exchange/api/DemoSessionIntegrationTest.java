package kr.exchange.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.auth.DemoSessionService;
import kr.exchange.ledger.AccountBootstrap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-5 데모 세션(§3-7) 검증:
 * (a) 무인증 발급 → Bearer 단독 조회 + 쓰기 차단(v2.12 읽기 전용 계약),
 * (b) 만료 토큰·위조 서명 거부, 비데모(§3 서명) 경로 무영향,
 * (c) 게이트웨이 가드 보강 회귀: chunked 바디 canonical 우회 차단(W5),
 *     MARKET BUY notional scale 초과 400(W4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoSessionIntegrationTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int KRW_BTC = 1;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountBootstrap bootstrap;
    @Autowired
    DemoKeySeeder keySeeder;
    @Autowired
    DemoSessionService sessionService;
    @Autowired
    ObjectMapper objectMapper;
    @LocalServerPort
    int port;

    long maker;
    DemoKeySeeder.IssuedKey makerKey;
    String demoBearer;
    long demoUserId;

    @BeforeAll
    void seed() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        maker = bootstrap.user("maker@demo.test");
        bootstrap.userAccounts(maker, KRW);
        bootstrap.userAccounts(maker, BTC);
        bootstrap.deposit(maker, KRW, new BigDecimal("1000000000"));
        bootstrap.deposit(maker, BTC, new BigDecimal("100"));
        makerKey = keySeeder.issue(maker, "TRADE", "maker-trade");
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                jdbc.sql("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                                + "AND lease_until > now()")
                        .query(Long.class).single() > 0);
    }

    @Test
    @Order(1)
    void sessionIssueAndBearerOnlyAccounts() throws Exception {
        ResponseEntity<String> issued = rest.exchange("/api/v1/demo/session", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(200, issued.getStatusCode().value());
        JsonNode body = objectMapper.readTree(issued.getBody());
        assertEquals("demo@exchange.local", body.path("user").path("email").asText());
        assertNotNull(body.path("user").path("external_id").asText());
        assertEquals(900, body.path("expires_in").asInt());
        demoBearer = "Bearer " + body.path("access_token").asText();
        demoUserId = jdbc.sql("SELECT id FROM users WHERE email = 'demo@exchange.local'")
                .query(Long.class).single();

        // HMAC·query_hash·nonce 없이 Bearer 단독으로 보호 경로 접근(§3-7)
        ResponseEntity<String> accounts = get("/api/v1/accounts", demoBearer);
        assertEquals(200, accounts.getStatusCode().value());
        JsonNode data = objectMapper.readTree(accounts.getBody()).path("data");
        assertEquals("100000000", field(data, "KRW", "balance"));
        assertEquals("10", field(data, "BTC", "balance"));

        // 같은 토큰 재사용 가능(세션 토큰은 nonce 소비가 없다)
        assertEquals(200, get("/api/v1/accounts", demoBearer).getStatusCode().value());
    }

    /**
     * 데모 세션 읽기 전용 계약(§3-7 v2.12)과 봇 무영향을 한 테스트에서 본다.
     * 둘을 나누면 "데모를 막았다"만 남고 "막으면서 봇을 안 건드렸다"가 증명되지 않는다.
     * 봇 경로가 같이 막히면 하네스 부하와 라이브 데모가 통째로 죽으므로 회귀 대상이다.
     */
    @Test
    @Order(2)
    void demoSessionIsReadOnlyWhileApiKeyStillTrades() throws Exception {
        JsonNode before = objectMapper.readTree(get("/api/v1/accounts", demoBearer).getBody())
                .path("data");

        // 데모 세션 주문 생성 → 403. 봇과 같은 바디·같은 마켓이라 차이는 인증 주체뿐이다
        byte[] buy = orderBody("bid", "limit", "50000000", "0.01", "demo-buy-1");
        ResponseEntity<String> blocked = post("/api/v1/orders", buy, demoBearer);
        assertEquals(403, blocked.getStatusCode().value());
        assertEquals("member_session_required", errorName(blocked));

        // 취소도 막힌다. 필터가 핸들러 앞에서 끊으므로 없는 주문 번호여도 404가 아니라 403이다.
        // 이것이 경로별 등재가 아니라 메서드 기반 판정이라는 증거다
        ResponseEntity<String> cancel = rest.exchange("/api/v1/orders/00000000-0000-0000-0000-000000000000",
                HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(demoBearer)), String.class);
        assertEquals(403, cancel.getStatusCode().value());
        assertEquals("member_session_required", errorName(cancel));

        // 차단이 부작용을 남기지 않았다
        JsonNode after = objectMapper.readTree(get("/api/v1/accounts", demoBearer).getBody())
                .path("data");
        assertEquals(field(before, "KRW", "balance"), field(after, "KRW", "balance"));
        assertEquals(field(before, "BTC", "balance"), field(after, "BTC", "balance"));

        // 봇(§3 서명)은 같은 필터를 지나면서 그대로 거래한다
        byte[] sell = orderBody("ask", "limit", "50000000", "0.01", "demo-maker-1");
        ResponseEntity<String> posted = post("/api/v1/orders", sell,
                TestSigner.bearerForBody(makerKey.accessKey(), makerKey.secretKey(),
                        "POST", "/api/v1/orders", sell));
        assertEquals(201, posted.getStatusCode().value());
        assertEquals("OPEN", objectMapper.readTree(posted.getBody()).path("status").asText());
    }

    @Test
    @Order(3)
    void expiredAndForgedSessionRejected() throws Exception {
        String externalId = jdbc.sql("SELECT external_id::text FROM users WHERE id = ?")
                .params(demoUserId).query(String.class).single();

        // 만료 토큰 → 401
        String expired = sessionService.issue(demoUserId, externalId, Duration.ofSeconds(-1));
        ResponseEntity<String> expiredResponse = get("/api/v1/accounts", "Bearer " + expired);
        assertEquals(401, expiredResponse.getStatusCode().value());
        assertEquals("jwt_verification", errorName(expiredResponse));

        // 서버 세션 키 없이 위조한 demo_session 토큰 → 서명 불일치 401
        String header = b64url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long now = System.currentTimeMillis() / 1000;
        String payload = b64url("{\"typ\":\"demo_session\",\"sub\":\"" + externalId
                + "\",\"user_id\":" + demoUserId + ",\"scope\":\"TRADE\",\"iat\":" + now
                + ",\"exp\":" + (now + 900) + "}");
        String forged = header + "." + payload + "." + b64url("forged-signature");
        ResponseEntity<String> forgedResponse = get("/api/v1/accounts", "Bearer " + forged);
        assertEquals(401, forgedResponse.getStatusCode().value());
        assertEquals("jwt_verification", errorName(forgedResponse));

        // 비데모 경로(§3 서명)는 무영향: 세션 클레임 없는 JWT는 기존 검증 순서 그대로
        ResponseEntity<String> signed = get("/api/v1/accounts",
                TestSigner.bearerForQuery(makerKey.accessKey(), makerKey.secretKey(),
                        "GET", "/api/v1/accounts", Map.of()));
        assertEquals(200, signed.getStatusCode().value());
    }

    @Test
    @Order(4)
    void chunkedBodyCannotBypassBodyCanonical() throws Exception {
        // 우회 시도: 쿼리 canonical(빈 페이로드)로 서명하고 바디는 chunked로 전송 → 401
        byte[] body = orderBody("bid", "limit", "50000000", "0.01", "chunk-bypass-1");
        String querySigned = TestSigner.bearerForQuery(makerKey.accessKey(), makerKey.secretKey(),
                "POST", "/api/v1/orders", Map.of());
        HttpURLConnection bypass = openChunkedPost("/api/v1/orders", querySigned, body);
        assertEquals(401, bypass.getResponseCode());

        // 정상 경로: 바디 canonical 서명 + chunked 전송 → 접수(201)
        byte[] ok = orderBody("ask", "limit", "60000000", "0.01", "chunk-ok-1");
        String bodySigned = TestSigner.bearerForBody(makerKey.accessKey(), makerKey.secretKey(),
                "POST", "/api/v1/orders", ok);
        HttpURLConnection accepted = openChunkedPost("/api/v1/orders", bodySigned, ok);
        assertEquals(201, accepted.getResponseCode());
    }

    @Test
    @Order(5)
    void marketBuyNotionalScaleGuard() throws Exception {
        // 소수 19자리 notional은 scale 18 표현 불가 → 400 invalid_parameter(§1.4 v2.10)
        byte[] body = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"market\","
                + "\"price\":\"10000.0000000000000000001\",\"client_order_id\":\"scale-1\"}")
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> response = post("/api/v1/orders", body,
                TestSigner.bearerForBody(makerKey.accessKey(), makerKey.secretKey(),
                        "POST", "/api/v1/orders", body));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("invalid_parameter", errorName(response));
        assertTrue(response.getBody().contains("notional_scale_exceeded"));
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private HttpURLConnection openChunkedPost(String path, String bearer, byte[] body)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(16);
        connection.setRequestProperty("Authorization", bearer);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        return connection;
    }

    private static HttpHeaders bearerHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", bearer);
        return headers;
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(bearer)), String.class);
    }

    private ResponseEntity<String> post(String path, byte[] body, String bearer) {
        HttpHeaders headers = bearerHeaders(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static byte[] orderBody(String side, String ordType, String price, String volume,
                                    String clientOrderId) {
        return ("{\"market\":\"KRW-BTC\",\"side\":\"" + side + "\",\"ord_type\":\"" + ordType
                + "\",\"price\":\"" + price + "\",\"volume\":\"" + volume
                + "\",\"client_order_id\":\"" + clientOrderId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String errorName(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("error").path("name").asText();
    }

    private static String field(JsonNode accounts, String currency, String fieldName) {
        for (JsonNode account : accounts) {
            if (currency.equals(account.path("currency").asText())) {
                return account.path(fieldName).asText();
            }
        }
        throw new AssertionError(currency + " 계좌 없음: " + accounts);
    }

    private static String b64url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

package kr.exchange.api.member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.ledger.AccountBootstrap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 회원 인증(회원_설계.md §1·§3) 검증:
 * (a) 가입·로그인·me·로그아웃 왕복, 가입 시 GENESIS 초기 자금(KRW 1억) 발급,
 * (b) Argon2id 저장 확인, Ed25519 세션 위·변조 거부,
 * (c) 리프레시 회전·재사용 탐지(family 폐기), 로그인 실패 백오프 잠금,
 * (d) 중복 가입·잘못된 비번 거부, 회원 스코프 격리(타 회원 자산 불가시).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberAuthIntegrationTest {

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
        registry.add("exchange.member.session-seed-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("exchange.member.password-pepper-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountBootstrap bootstrap;
    @Autowired
    ObjectMapper objectMapper;

    private final AtomicInteger emailSeq = new AtomicInteger();

    @BeforeAll
    void seed() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                                + "AND lease_until > now()")
                        .query(Long.class).single() > 0);
    }

    @Test
    void signupProvisionsFundsAndArgon2idStored() throws Exception {
        String email = uniqueEmail();
        Signup s = signup(email, "demopass1", "데모트레이더");
        assertEquals(201, s.status);
        assertNotNull(s.accessToken);
        assertNotNull(s.rtCookie);
        assertEquals(900, s.body.path("expires_in").asInt());
        assertEquals(email, s.body.path("user").path("email").asText());
        assertEquals("데모트레이더", s.body.path("user").path("display_name").asText());
        assertEquals("HUMAN", s.body.path("user").path("kind").asText());

        // me: 회원 세션 Bearer 단독으로 본인 정보
        ResponseEntity<String> me = get("/api/v1/me", "Bearer " + s.accessToken);
        assertEquals(200, me.getStatusCode().value());
        JsonNode meUser = objectMapper.readTree(me.getBody()).path("user");
        assertEquals(email, meUser.path("email").asText());

        // accounts: 가입 GENESIS로 KRW 1억, 암호화폐는 0(계정만 선생성)
        ResponseEntity<String> accounts = get("/api/v1/accounts", "Bearer " + s.accessToken);
        assertEquals(200, accounts.getStatusCode().value());
        JsonNode data = objectMapper.readTree(accounts.getBody()).path("data");
        assertEquals("100000000", field(data, "KRW", "balance"));
        assertEquals("0", field(data, "BTC", "balance"));

        // Argon2id 인코딩 문자열만 저장(평문·가역 저장 금지)
        String hash = jdbc.sql("""
                        SELECT c.password_hash FROM user_credentials c
                        JOIN users u ON u.id = c.user_id WHERE u.email = ?
                        """)
                .params(email).query(String.class).single();
        assertTrue(hash.startsWith("$argon2id$"), "Argon2id 인코딩이 아님: " + hash);
        assertTrue(!hash.contains("demopass1"), "평문 노출");
    }

    @Test
    void duplicateSignupRejected() throws Exception {
        String email = uniqueEmail();
        assertEquals(201, signup(email, "demopass1", "중복테스트").status);
        Signup dup = signup(email, "demopass2", "중복테스트2");
        assertEquals(409, dup.status);
        assertEquals("email_taken", dup.body.path("error").path("name").asText());
    }

    @Test
    void weakPasswordAndInvalidEmailRejected() throws Exception {
        Signup weak = signup(uniqueEmail(), "short", "약한비번");
        assertEquals(400, weak.status);
        assertEquals("weak_password", weak.body.path("error").path("name").asText());

        Signup badEmail = signup("not-an-email", "demopass1", "잘못이메일");
        assertEquals(400, badEmail.status);
        assertEquals("invalid_email", badEmail.body.path("error").path("name").asText());
    }

    @Test
    void loginWrongPasswordThenBackoffLock() throws Exception {
        String email = uniqueEmail();
        signup(email, "correctpass1", "로그인테스트");

        // 미가입 이메일도 동일 코드로 존재 여부 비노출
        assertEquals("invalid_credentials",
                login(uniqueEmail(), "whatever1").body.path("error").path("name").asText());

        // 임계치 직전까지 invalid_credentials
        for (int i = 0; i < 4; i++) {
            Login attempt = login(email, "wrongpass" + i);
            assertEquals(401, attempt.status);
            assertEquals("invalid_credentials", attempt.body.path("error").path("name").asText());
        }
        // 5회째 누적 실패로 계정 잠금
        Login locked = login(email, "wrongpass-final");
        assertEquals(423, locked.status);
        assertEquals("account_locked", locked.body.path("error").path("name").asText());

        // 잠금 중에는 올바른 비번도 423
        Login correctButLocked = login(email, "correctpass1");
        assertEquals(423, correctButLocked.status);
    }

    @Test
    void loginRefreshRotationAndReuseDetection() throws Exception {
        String email = uniqueEmail();
        signup(email, "correctpass1", "회전테스트");

        Login ok = login(email, "correctpass1");
        assertEquals(200, ok.status);
        assertNotNull(ok.rtCookie);

        // 회전: 새 access + 새 rt
        Refresh first = refresh(ok.rtCookie);
        assertEquals(200, first.status);
        assertNotNull(first.rtCookie);
        assertTrue(!first.rtCookie.equals(ok.rtCookie), "rt는 회전되어야 한다");

        // 소각된 이전 rt 재사용 → family 폐기 + refresh_reuse
        Refresh reused = refresh(ok.rtCookie);
        assertEquals(401, reused.status);
        assertEquals("refresh_reuse", reused.body.path("error").path("name").asText());

        // family 폐기로 회전된 rt도 무효
        Refresh afterRevoke = refresh(first.rtCookie);
        assertEquals(401, afterRevoke.status);
    }

    @Test
    void logoutRevokesFamily() throws Exception {
        String email = uniqueEmail();
        signup(email, "correctpass1", "로그아웃테스트");
        Login ok = login(email, "correctpass1");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Cookie", "rt=" + ok.rtCookie);
        headers.set("X-Forwarded-For", uniqueIp());
        ResponseEntity<String> out = rest.exchange("/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertEquals(204, out.getStatusCode().value());

        Refresh afterLogout = refresh(ok.rtCookie);
        assertEquals(401, afterLogout.status);
        // 폐기된 family 토큰 재제출은 재사용으로 판정한다(§1-3).
        assertEquals("refresh_reuse", afterLogout.body.path("error").path("name").asText());
    }

    @Test
    void revokedFamilyCannotBeResurrectedByRotation() throws Exception {
        // 회전 소각 UPDATE는 revoked_at을 검사해 폐기 family 부활을 물리 차단한다.
        // 초기 SELECT와 소각 사이에 revokeFamily가 커밋된 상태(revoked_at 세팅, used_at NULL)를 직접 구성해
        // 가드가 적용된 소각이 0행임을 확인하고, 서비스 경로가 재사용으로 판정하는지 본다.
        String email = uniqueEmail();
        signup(email, "correctpass1", "소각가드");
        Login ok = login(email, "correctpass1");

        // 로그인 세션이 가장 최근 tip이다(가입 세션과 별개 family). 그 행만 폐기 상태로 만든다.
        Long sessionId = jdbc.sql("""
                        SELECT s.id FROM auth_sessions s JOIN users u ON u.id = s.user_id
                        WHERE u.email = ? ORDER BY s.id DESC LIMIT 1
                        """)
                .param(email).query(Long.class).single();
        String familyId = jdbc.sql("SELECT family_id::text FROM auth_sessions WHERE id = ?")
                .param(sessionId).query(String.class).single();
        jdbc.sql("UPDATE auth_sessions SET revoked_at = now() WHERE id = ?").param(sessionId).update();

        int burned = jdbc.sql("""
                        UPDATE auth_sessions SET used_at = now()
                        WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL
                        """)
                .param(sessionId).update();
        assertEquals(0, burned, "폐기된 세션이 소각되어 되살아남");

        Refresh reused = refresh(ok.rtCookie);
        assertEquals(401, reused.status);
        assertEquals("refresh_reuse", reused.body.path("error").path("name").asText());
        // 폐기 family에 살아있는 tip(revoked_at NULL)이 재생성되지 않았다.
        long live = jdbc.sql(
                        "SELECT count(*) FROM auth_sessions WHERE family_id = ?::uuid AND revoked_at IS NULL")
                .param(familyId).query(Long.class).single();
        assertEquals(0, live, "폐기된 family에 살아있는 세션이 남음");
    }

    @Test
    void forgedAndTamperedMemberTokenRejected() throws Exception {
        String email = uniqueEmail();
        Signup s = signup(email, "demopass1", "위조테스트");

        // 서명부 변조 → 401
        String[] parts = s.accessToken.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + flipSignature(parts[2]);
        ResponseEntity<String> res = get("/api/v1/me", "Bearer " + tampered);
        assertEquals(401, res.getStatusCode().value());

        // payload 변조(user_id 상향) 후 원 서명 유지 → 서명 불일치 401
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"typ\":\"access\",\"sub\":\"x\",\"user_id\":999999,\"scope\":\"TRADE\","
                        + "\"kind\":\"HUMAN\",\"aud\":[\"exchange\"],\"iat\":1,\"exp\":9999999999}")
                        .getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        assertEquals(401, get("/api/v1/me", "Bearer " + forged).getStatusCode().value());
    }

    @Test
    void memberScopeIsolation() throws Exception {
        Signup a = signup(uniqueEmail(), "demopass1", "회원A");
        Signup b = signup(uniqueEmail(), "demopass1", "회원B");

        // A가 LIMIT 매수 예약(체결 상대 없어 잔존) → A의 KRW available 감소·locked 증가
        byte[] buy = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\","
                + "\"price\":\"1000000\",\"volume\":\"0.001\",\"client_order_id\":\"iso-a-1\"}")
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> placed = post("/api/v1/orders", buy, "Bearer " + a.accessToken);
        assertEquals(201, placed.getStatusCode().value());

        JsonNode aData = objectMapper.readTree(
                get("/api/v1/accounts", "Bearer " + a.accessToken).getBody()).path("data");
        assertEquals("99999000", field(aData, "KRW", "balance"));
        assertEquals("1000", field(aData, "KRW", "locked"));

        // B는 A의 예약과 무관하게 자기 초기 자금만 본다(스코프 격리)
        JsonNode bData = objectMapper.readTree(
                get("/api/v1/accounts", "Bearer " + b.accessToken).getBody()).path("data");
        assertEquals("100000000", field(bData, "KRW", "balance"));
        assertEquals("0", field(bData, "KRW", "locked"));
    }

    @Test
    void signupThrottledPerIp() throws Exception {
        // 동일 IP 5회/60s 초과 시 429(§3-1). 무인증 signup의 Argon2id·원장 게시 남용 방어.
        String ip = "203.0.113.7";
        for (int i = 0; i < 5; i++) {
            assertEquals(201, signupFromIp(uniqueEmail(), ip).getStatusCode().value(),
                    "5회까지는 통과해야 한다");
        }
        ResponseEntity<String> sixth = signupFromIp(uniqueEmail(), ip);
        assertEquals(429, sixth.getStatusCode().value());
        assertEquals("too_many_requests",
                objectMapper.readTree(sixth.getBody()).path("error").path("name").asText());
    }

    @Test
    void signupIpBucketUsesRightmostUntrusted() throws Exception {
        // 스푸핑 방어(§2-5 rightmost-untrusted). 다중값 XFF에서 프리픽스는 원 클라이언트가 임의 주입
        // 가능하므로 무시되어야 한다. 소켓피어(루프백)만 신뢰 프록시라, 우측 비프록시 IP가 채택된다.
        // 프리픽스만 회전시키고 우측을 고정하면 전 요청이 같은 버킷에 계상되어 임계 초과 시 429가 나야
        // 한다. 최좌측을 채택하는 잘못된 구현이면 매 요청 버킷이 갈려 영원히 통과한다(회귀 감지).
        String pinnedClient = "198.51.100.9";
        for (int i = 0; i < 5; i++) {
            String spoofedXff = "203.0.113." + (i + 1) + ", " + pinnedClient;
            assertEquals(201, signupWithXff(uniqueEmail(), spoofedXff).getStatusCode().value(),
                    "5회까지는 통과해야 한다(우측 고정 버킷)");
        }
        ResponseEntity<String> sixth = signupWithXff(uniqueEmail(), "203.0.113.6, " + pinnedClient);
        assertEquals(429, sixth.getStatusCode().value(),
                "프리픽스 스푸핑이 무시되고 우측 IP 버킷이 소진되어야 한다");
        assertEquals("too_many_requests",
                objectMapper.readTree(sixth.getBody()).path("error").path("name").asText());
    }

    @Test
    void meRejectsNonMemberSession() throws Exception {
        // 데모 세션(비회원 유효 토큰)으로 /me 접근 → 500이 아니라 403(§3-1 회원 세션 전용).
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", uniqueIp());
        ResponseEntity<String> demo = rest.exchange("/api/v1/demo/session", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        assertEquals(200, demo.getStatusCode().value());
        String demoToken = objectMapper.readTree(demo.getBody()).path("access_token").asText();

        ResponseEntity<String> me = get("/api/v1/me", "Bearer " + demoToken);
        assertEquals(403, me.getStatusCode().value());
        assertEquals("out_of_scope",
                objectMapper.readTree(me.getBody()).path("error").path("name").asText());
    }

    private ResponseEntity<String> signupFromIp(String email, String ip) {
        return signupWithXff(email, ip);
    }

    // xff는 단일 IP 또는 "prefix, client" 다중값. RemoteIpValve의 우측 파생을 그대로 테스트한다.
    private ResponseEntity<String> signupWithXff(String email, String xff) {
        String json = "{\"email\":\"" + email + "\",\"password\":\"demopass1\","
                + "\"display_name\":\"스로틀\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", xff);
        return rest.exchange("/api/v1/auth/signup", HttpMethod.POST,
                new HttpEntity<>(json, headers), String.class);
    }

    private record Signup(int status, String accessToken, String rtCookie, JsonNode body) {
    }

    private record Login(int status, String accessToken, String rtCookie, JsonNode body) {
    }

    private record Refresh(int status, String accessToken, String rtCookie, JsonNode body) {
    }

    private Signup signup(String email, String password, String displayName) throws Exception {
        String json = "{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"display_name\":\"" + displayName + "\"}";
        ResponseEntity<String> res = postJson("/api/v1/auth/signup", json, null);
        JsonNode body = objectMapper.readTree(res.getBody());
        return new Signup(res.getStatusCode().value(), body.path("access_token").asText(null),
                extractRt(res), body);
    }

    private Login login(String email, String password) throws Exception {
        String json = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<String> res = postJson("/api/v1/auth/login", json, null);
        JsonNode body = objectMapper.readTree(res.getBody());
        return new Login(res.getStatusCode().value(), body.path("access_token").asText(null),
                extractRt(res), body);
    }

    private Refresh refresh(String rtCookie) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Cookie", "rt=" + rtCookie);
        headers.set("X-Forwarded-For", uniqueIp());
        ResponseEntity<String> res = rest.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        JsonNode body = res.getBody() == null ? objectMapper.createObjectNode()
                : objectMapper.readTree(res.getBody());
        return new Refresh(res.getStatusCode().value(), body.path("access_token").asText(null),
                extractRt(res), body);
    }

    // 진입 rate limit(§2-5)은 getRemoteAddr()가 파생한 클라이언트 IP로 버킷을 나눈다.
    // forward-headers-strategy=native의 RemoteIpValve가 XFF를 우측부터 신뢰 프록시(internal-proxies)로
    // 벗겨 남는 첫 IP(rightmost-untrusted)를 채택한다. in-test 소켓피어는 루프백이라 신뢰 프록시로
    // 처리되고, 단일 10.x XFF는 루프백이 아니므로 그대로 클라이언트로 채택된다. 그래서 요청마다 고유
    // XFF를 실으면 서로 다른 클라이언트가 되고, TestRestTemplate 공유 루프백 소켓이 버킷을 합치지 않는다.
    // 버킷 소진 자체는 signupThrottledPerIp로, 우측 기준·프리픽스 스푸핑 무시는 signupIpBucketUsesRightmostUntrusted로 검증한다.
    private final AtomicInteger ipSeq = new AtomicInteger();

    private String uniqueIp() {
        int n = ipSeq.incrementAndGet();
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    private ResponseEntity<String> postJson(String path, String json, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());
        if (bearer != null) {
            headers.set("Authorization", bearer);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", bearer);
        headers.set("X-Forwarded-For", uniqueIp());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, byte[] body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String extractRt(ResponseEntity<String> res) {
        List<String> cookies = res.getHeaders().get("Set-Cookie");
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies) {
            if (cookie.startsWith("rt=")) {
                String value = cookie.substring(3, cookie.indexOf(';'));
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private String uniqueEmail() {
        return "m" + emailSeq.incrementAndGet() + "@demo.test";
    }

    // 서명의 첫 base64url 문자를 바꿔 디코딩 바이트가 반드시 달라지게 한다. 마지막 문자는 유의 비트가
    // 2비트뿐이라 관대한 url 디코더가 원본과 같은 64바이트로 복원할 수 있어(무변조) 검증 결과가 흔들린다.
    private static String flipSignature(String value) {
        char first = value.charAt(0);
        char replacement = first == 'A' ? 'B' : 'A';
        return replacement + value.substring(1);
    }

    private static String field(JsonNode accounts, String currency, String fieldName) {
        for (JsonNode account : accounts) {
            if (currency.equals(account.path("currency").asText())) {
                return account.path(fieldName).asText();
            }
        }
        throw new AssertionError(currency + " 계좌 없음: " + accounts);
    }
}

package kr.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 회원 운영 API 검증(§6-E). 정지는 가역이라 미체결 주문을 남기고 세션만 끊고, 폐쇄는 비가역이라
 * 주문까지 정리하고 승인 토큰을 요구하며 되돌아오는 전이가 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberAdminIntegrationTest {

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
        registry.add("exchange.admin.enabled", () -> "true");
        registry.add("exchange.admin.mtls.trusted-forwarding", () -> "true");
        /* 이 토폴로지는 게이트웨이가 mTLS 를 종단하고 CN 을 헤더로 실어 주는 전제다.
           그 전제에서 앱은 루프백에만 뜬다. 주소를 비워 두면 전 인터페이스 바인딩이라
           자가신고 헤더 인증이 그대로 외부에 열리고, AdminConfig 가드가 기동을 막는다 */
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("exchange.admin.rate-limit.capacity", () -> "1000000");
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AdminOperatorSeeder operatorSeeder;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeAll
    void seed() {
        operatorSeeder.ensureOperator("op-mod", "회원 운영자", Set.of(AdminRole.MEMBER_MOD));
        operatorSeeder.ensureOperator("op-two", "승인자", Set.of(AdminRole.MEMBER_MOD));
        operatorSeeder.ensureOperator("op-view", "뷰어", Set.of(AdminRole.VIEWER));
    }

    @Test
    void listExcludesBotsAndCarriesEnvelope() throws Exception {
        String human = newMember("list-human@demo.local");
        newBot("list-bot@demo.local");
        String s = session("op-view");

        JsonNode body = json(get("/admin/v1/members?limit=200", "op-view", s));
        assertEquals("1.0", body.path("schema_version").asText());
        assertTrue(body.path("pagination").isObject(), "§2-2 엔벌로프");

        JsonNode rows = body.path("data");
        assertTrue(contains(rows, human), "사람 계정이 목록에 없음");
        // 봇 시더가 만든 계정은 운영 대상이 아니다(V20 부분 인덱스 술어와 같은 규율)
        for (JsonNode r : rows) {
            String email = r.path("email").asText();
            assertNotEquals("list-bot@demo.local", email, "봇 계정이 목록에 샜다");
        }
    }

    @Test
    void listFiltersByStatusAndOrdersNewestFirst() throws Exception {
        String older = newMember("order-older@demo.local");
        Thread.sleep(20);
        String newer = newMember("order-newer@demo.local");
        String s = session("op-mod");
        assertEquals(200, post("/admin/v1/members/" + older + "/suspend", null, "op-mod", s)
                .getStatusCode().value());

        JsonNode active = json(get("/admin/v1/members?status=ACTIVE&limit=200", "op-mod", s))
                .path("data");
        assertTrue(contains(active, newer));
        assertTrue(!contains(active, older), "정지 계정이 ACTIVE 필터에 남았다");

        JsonNode all = json(get("/admin/v1/members?limit=200", "op-mod", s)).path("data");
        assertTrue(indexOf(all, newer) < indexOf(all, older), "가입 역순이어야 한다");
    }

    @Test
    void detailCarriesActivitySummary() throws Exception {
        String ext = newMember("detail@demo.local");
        ResponseEntity<String> r = get("/admin/v1/members/" + ext, "op-view", session("op-view"));
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        JsonNode body = json(r);
        assertEquals(ext, body.path("external_id").asText());
        assertEquals("ACTIVE", body.path("status").asText());
        // 폐쇄 전에 남은 주문이 있는지 운영자가 이 값으로 판단한다
        assertEquals(0, body.path("open_order_count").asInt());
        assertTrue(body.has("asset_kinds"));
        assertTrue(body.has("updated_at"));
    }

    @Test
    void unknownMemberIsNotFound() throws Exception {
        String s = session("op-view");
        assertEquals(404, get("/admin/v1/members/" + UUID.randomUUID(), "op-view", s)
                .getStatusCode().value());
        // UUID 형식이 아니면 캐스팅 전에 걸러야 500이 되지 않는다
        assertEquals(404, get("/admin/v1/members/not-a-uuid", "op-view", s)
                .getStatusCode().value());
    }

    @Test
    void suspendRevokesSessionsButKeepsOrders() throws Exception {
        String ext = newMember("suspend@demo.local");
        long userId = userId(ext);
        seedSession(userId);
        String s = session("op-mod");

        JsonNode body = json(post("/admin/v1/members/" + ext + "/suspend", null, "op-mod", s));
        assertEquals("SUSPENDED", body.path("status").asText());
        assertEquals(1, body.path("revoked_sessions").asInt(), "세션은 끊는다");
        // 정지는 가역이라 주문을 남긴다. 취소하면 복구해도 원상태가 아니다
        assertEquals(0, body.path("canceled_orders").asInt());
        assertEquals("SUSPENDED", status(ext));
        assertEquals(0L, activeSessions(userId));

        // 같은 상태 재전이는 멱등 성공(상태 기계가 아니라 set-point)
        assertEquals(200, post("/admin/v1/members/" + ext + "/suspend", null, "op-mod", s)
                .getStatusCode().value());

        JsonNode back = json(post("/admin/v1/members/" + ext + "/reinstate", null, "op-mod", s));
        assertEquals("ACTIVE", back.path("status").asText());
        // 복구는 세션을 되살리지 않는다. 회원이 다시 로그인한다
        assertEquals(0, back.path("revoked_sessions").asInt());
        assertEquals("ACTIVE", status(ext));
    }

    @Test
    void closeRequiresApprovalAndIsTerminal() throws Exception {
        String ext = newMember("close@demo.local");
        seedSession(userId(ext));
        String modSession = session("op-mod");

        // 토큰 없이는 403. 폐쇄는 §3-6 완결 열거의 필수 액션이다
        assertEquals(403, post("/admin/v1/members/" + ext + "/close", "{}", "op-mod", modSession)
                .getStatusCode().value());

        String token = issueToken("op-mod", "members.close", ext, "op-two", session("op-two"));
        JsonNode body = json(post("/admin/v1/members/" + ext + "/close",
                "{\"approval_token\":\"" + token + "\"}", "op-mod", modSession));
        assertEquals("CLOSED", body.path("status").asText());
        assertEquals(1, body.path("revoked_sessions").asInt());
        assertTrue(body.path("approval_id").asLong() > 0);
        assertEquals("CLOSED", status(ext));

        // CLOSED에서 나가는 전이는 없다
        assertEquals(409, post("/admin/v1/members/" + ext + "/reinstate", null, "op-mod", modSession)
                .getStatusCode().value());
        assertEquals(409, post("/admin/v1/members/" + ext + "/suspend", null, "op-mod", modSession)
                .getStatusCode().value());

        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE operator_id='op-mod' "
                        + "AND action='members.close' AND target = ? AND approval_id IS NOT NULL")
                .param(ext).query(Long.class).single() >= 1);
    }

    @Test
    void approvalTokenIsBoundToTheTargetMember() throws Exception {
        String a = newMember("bind-a@demo.local");
        String b = newMember("bind-b@demo.local");
        String token = issueToken("op-mod", "members.close", a, "op-two", session("op-two"));
        // 다른 회원에 재사용하면 바인딩 불일치로 반려된다
        assertEquals(403, post("/admin/v1/members/" + b + "/close",
                "{\"approval_token\":\"" + token + "\"}", "op-mod", session("op-mod"))
                .getStatusCode().value());
        assertEquals("ACTIVE", status(b));
    }

    @Test
    void viewerCannotTransition() throws Exception {
        String ext = newMember("viewer-guard@demo.local");
        String s = session("op-view");
        ResponseEntity<String> r = post("/admin/v1/members/" + ext + "/suspend", null, "op-view", s);
        assertEquals(403, r.getStatusCode().value());
        assertEquals("role_required", json(r).path("error").path("detail").path("reason").asText());
        assertEquals("ACTIVE", status(ext));
    }

    @Test
    void reversibleTransitionsAreAudited() throws Exception {
        String ext = newMember("audit@demo.local");
        String s = session("op-mod");
        post("/admin/v1/members/" + ext + "/suspend", null, "op-mod", s);
        // 가역 액션은 approval_id NULL로 남는다
        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log WHERE action='members.suspend' "
                        + "AND target = ? AND approval_id IS NULL")
                .param(ext).query(Long.class).single() >= 1);
    }

    private String newMember(String email) {
        return jdbc.sql("INSERT INTO users (email, kind) VALUES (?, 'HUMAN') "
                        + "RETURNING external_id::text")
                .param(email).query(String.class).single();
    }

    private void newBot(String email) {
        jdbc.sql("INSERT INTO users (email, kind) VALUES (?, 'BOT')").param(email).update();
    }

    private long userId(String externalId) {
        return jdbc.sql("SELECT id FROM users WHERE external_id = ?::uuid")
                .param(externalId).query(Long.class).single();
    }

    private String status(String externalId) {
        return jdbc.sql("SELECT status FROM users WHERE external_id = ?::uuid")
                .param(externalId).query(String.class).single();
    }

    /** 폐기 대상 세션 한 줄. 로그인 경로를 태우지 않고 전이 부수효과만 본다 */
    private void seedSession(long userId) {
        jdbc.sql("""
                        INSERT INTO auth_sessions (user_id, family_id, token_hash, expires_at)
                        VALUES (?, gen_random_uuid(), ?, now() + interval '7 days')
                        """)
                .params(userId, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32))
                .update();
    }

    private long activeSessions(long userId) {
        return jdbc.sql("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL")
                .param(userId).query(Long.class).single();
    }

    private static boolean contains(JsonNode rows, String externalId) {
        for (JsonNode r : rows) {
            if (externalId.equals(r.path("external_id").asText())) return true;
        }
        return false;
    }

    private static int indexOf(JsonNode rows, String externalId) {
        for (int i = 0; i < rows.size(); i++) {
            if (externalId.equals(rows.get(i).path("external_id").asText())) return i;
        }
        return Integer.MAX_VALUE;
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        return objectMapper.readTree(r.getBody());
    }

    private String session(String cn) throws Exception {
        ResponseEntity<String> issued = exchange("/admin/v1/session", HttpMethod.POST, null, cn, null);
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        return objectMapper.readTree(issued.getBody()).path("token").asText();
    }

    private String issueToken(String requester, String action, String target,
                              String approverCn, String approverSession) throws Exception {
        String body = "{\"requester_operator_id\":\"" + requester + "\",\"action\":\"" + action
                + "\",\"target\":\"" + target + "\"}";
        ResponseEntity<String> issued = post("/admin/v1/approvals", body, approverCn, approverSession);
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        return objectMapper.readTree(issued.getBody()).path("approval_token").asText();
    }

    private ResponseEntity<String> get(String path, String cn, String bearer) {
        return exchange(path, HttpMethod.GET, null, cn, bearer);
    }

    private ResponseEntity<String> post(String path, String body, String cn, String bearer) {
        return exchange(path, HttpMethod.POST, body, cn, bearer);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String body,
                                            String cn, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cn != null) {
            headers.set("X-Operator-Cn", cn);
        }
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }
}

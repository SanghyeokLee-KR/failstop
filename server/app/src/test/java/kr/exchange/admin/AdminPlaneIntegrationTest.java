package kr.exchange.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.auth.DemoSessionService;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.sim.OpsGovernor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관리 평면 통합 검증(관리자 설계 v1.1 §1-3·§3-6):
 * (a) mTLS 신원 → 세션 발급, 인증서 없는 접근 거부,
 * (b) 평면 격리(고객 토큰의 /admin 거부, 관리 세션의 /api 거부),
 * (c) 파괴 액션 단독 시도 거부 · 2인 승인 시 성공 · 요청=승인 동일 운영자 거부(반결탁),
 * (d) 감사 적재, 모니터링 조회, 봇 OPS·마켓 halt 반영.
 *
 * mTLS는 게이트웨이 종단 토폴로지로 검증한다(trusted-forwarding). 게이트웨이가 실은 검증된
 * 인증서 CN을 X-Operator-Cn 헤더로 전달한다. 실 X509 인증서 파싱은 AdminMtlsResolverCertTest가
 * 데모 op-01.p12로 별도 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminPlaneIntegrationTest {

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
    }

    @TestConfiguration
    static class AdminTestBeans {
        // 봇 플릿(bots 프로파일) 없이 OPS 반영을 검증하기 위한 governor 주입.
        @Bean
        OpsGovernor opsGovernor() {
            return new OpsGovernor(10.0);
        }
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountBootstrap bootstrap;
    @Autowired
    DemoSessionService demoSessions;
    @Autowired
    AdminOperatorSeeder operatorSeeder;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    OpsGovernor opsGovernor;

    long customer;
    String customerSession;

    @BeforeAll
    void seed() {
        // 마켓 halt 반영 검증용. dev 자동 시드에 의존하지 않고 자체 시드한다(멱등).
        bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", 2, 1, new java.math.BigDecimal("0.0005"),
                new java.math.BigDecimal("0.0005"));
        operatorSeeder.ensureOperator("op-01", "데모 운영자 1", java.util.Set.of(AdminRole.SUPER));
        operatorSeeder.ensureOperator("op-02", "데모 운영자 2", java.util.Set.of(AdminRole.SUPER));
        operatorSeeder.ensureOperator("op-03", "데모 뷰어",
                java.util.Set.of(AdminRole.VIEWER));
        // CHAOS_OPS 단독. §1-2 포함관계(CHAOS_OPS ⊇ BOT_OPS) 회귀 검증용.
        operatorSeeder.ensureOperator("op-04", "데모 카오스",
                java.util.Set.of(AdminRole.CHAOS_OPS));
        customer = bootstrap.user("customer@admin.test");
        // 고객 평면 자격(데모 세션, scope=TRADE). 관리 평면에서 거부되어야 한다.
        customerSession = demoSessions.issue(customer, "customer", DemoSessionService.DEFAULT_TTL);
        // 모니터링 조회 소스: 불변식 결과 1건 적재.
        jdbc.sql("INSERT INTO invariant_checks (check_name, ok, violated_rows) "
                + "VALUES ('C01_ASSET_CONSERVATION', true, 0)").update();
    }

    // ------------------------------------------------------------------
    // (a) mTLS 신원 → 세션, 인증서 없는 접근 거부
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void mtlsIdentityIssuesSessionAndMissingCertIsRejected() throws Exception {
        // 인증서(mTLS 신원) 없이 세션 발급 시도 → 401
        ResponseEntity<String> noCert = adminPost("/admin/v1/session", null, null, null);
        assertEquals(401, noCert.getStatusCode().value());
        assertEquals("jwt_verification", errorName(noCert));

        // op-01 mTLS 신원으로 세션 발급 → 200 + operator_id 바인딩
        ResponseEntity<String> issued = adminPost("/admin/v1/session", null, "op-01", null);
        assertEquals(200, issued.getStatusCode().value());
        JsonNode session = objectMapper.readTree(issued.getBody());
        assertEquals("op-01", session.path("operator_id").asText());
        assertTrue(session.path("token").isTextual());
        assertNotNull(session.path("token").asText());

        // 발급된 세션으로 모니터링 조회(단독 운영자 가능)
        String token = session.path("token").asText();
        ResponseEntity<String> inv = adminGet("/admin/v1/ha/invariants", "op-01", token);
        assertEquals(200, inv.getStatusCode().value());
        assertTrue(objectMapper.readTree(inv.getBody()).path("checks").size() >= 1);

        // 등록되지 않은 신원 → 403 unknown_operator
        ResponseEntity<String> unknown = adminPost("/admin/v1/session", null, "op-99", null);
        assertEquals(403, unknown.getStatusCode().value());
        assertEquals("admin_forbidden", errorName(unknown));
    }

    // ------------------------------------------------------------------
    // (a') 세션 신원과 mTLS 신원 불일치 거부(자가신고 금지)
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void sessionBoundToMtlsIdentityIsRebound() throws Exception {
        String op01Token = issueSession("op-01");
        // op-01 세션을 op-02 mTLS 신원으로 사용 → 401(세션-신원 불일치)
        ResponseEntity<String> mismatch = adminGet("/admin/v1/ha/invariants", "op-02", op01Token);
        assertEquals(401, mismatch.getStatusCode().value());
        assertEquals("jwt_verification", errorName(mismatch));
    }

    // ------------------------------------------------------------------
    // (b) 평면 격리
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    void planeIsolation() throws Exception {
        // 고객 세션 토큰을 /admin에 제시(op-01 신원 동반) → 401(관리 세션 아님)
        ResponseEntity<String> customerOnAdmin =
                adminGet("/admin/v1/audit", "op-01", customerSession);
        assertEquals(401, customerOnAdmin.getStatusCode().value());
        assertEquals("jwt_verification", errorName(customerOnAdmin));

        // 관리 세션 토큰을 /api에 제시 → 401(고객 인증 클레임 부재)
        String adminToken = issueSession("op-01");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + adminToken);
        ResponseEntity<String> adminOnApi = rest.exchange("/api/v1/accounts", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(401, adminOnApi.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // (c) 파괴 액션 2인 승인(마켓 halt) · 반결탁
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    void destructiveActionRequiresTwoPersonApproval() throws Exception {
        String op01 = issueSession("op-01");
        String op02 = issueSession("op-02");

        // 승인 없이 halt → 403 approval_required
        ResponseEntity<String> noApproval =
                adminPost("/admin/v1/config/markets/KRW-BTC/halt", "{}", "op-01", op01);
        assertEquals(403, noApproval.getStatusCode().value());
        assertEquals("approval_required", reason(noApproval));

        // op-01이 자기 자신에게 승인 발급 시도 → 400/403(반결탁, requester=approver)
        ResponseEntity<String> selfApproval = adminPost("/admin/v1/approvals",
                approvalBody("op-01", "config.market.halt", "KRW-BTC"), "op-01", op01);
        assertEquals(403, selfApproval.getStatusCode().value());
        assertEquals("same_operator", reason(selfApproval));

        // op-02가 op-01의 halt를 승인 → approval_token 발급
        ResponseEntity<String> approval = adminPost("/admin/v1/approvals",
                approvalBody("op-01", "config.market.halt", "KRW-BTC"), "op-02", op02);
        assertEquals(200, approval.getStatusCode().value());
        String approvalToken = objectMapper.readTree(approval.getBody())
                .path("approval_token").asText();

        // op-01이 승인 토큰으로 halt → 성공, markets.status HALTED 반영
        ResponseEntity<String> halted = adminPost("/admin/v1/config/markets/KRW-BTC/halt",
                "{\"approval_token\":\"" + approvalToken + "\"}", "op-01", op01);
        assertEquals(200, halted.getStatusCode().value(), "halt 응답: " + halted.getBody());
        assertEquals("HALTED", objectMapper.readTree(halted.getBody()).path("status").asText());
        assertEquals("HALTED", jdbc.sql("SELECT status FROM markets WHERE symbol = 'KRW-BTC'")
                .query(String.class).single());

        // 동일 토큰 재사용 → 403 not_open(단회 소각)
        ResponseEntity<String> reused = adminPost("/admin/v1/config/markets/KRW-BTC/halt",
                "{\"approval_token\":\"" + approvalToken + "\"}", "op-01", op01);
        assertEquals(403, reused.getStatusCode().value());
        assertEquals("not_open", reason(reused));

        // 마켓 복구(정리): op-02 승인 → op-01 resume
        String resumeToken = objectMapper.readTree(adminPost("/admin/v1/approvals",
                approvalBody("op-01", "config.market.resume", "KRW-BTC"), "op-02", op02)
                .getBody()).path("approval_token").asText();
        ResponseEntity<String> resumed = adminPost("/admin/v1/config/markets/KRW-BTC/resume",
                "{\"approval_token\":\"" + resumeToken + "\"}", "op-01", op01);
        assertEquals(200, resumed.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // (c') approval_token 사용 주체가 승인 지정 요청자와 다르면 거부
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    void approvalBoundToRequester() throws Exception {
        String op01 = issueSession("op-01");
        String op02 = issueSession("op-02");
        // op-02가 op-01을 요청자로 지정해 승인 발급
        String token = objectMapper.readTree(adminPost("/admin/v1/approvals",
                approvalBody("op-01", "config.market.halt", "KRW-BTC"), "op-02", op02)
                .getBody()).path("approval_token").asText();
        // 발급 주체(op-02)가 그 토큰을 스스로 사용 → 403 requester_mismatch
        ResponseEntity<String> wrongUser = adminPost("/admin/v1/config/markets/KRW-BTC/halt",
                "{\"approval_token\":\"" + token + "\"}", "op-02", op02);
        assertEquals(403, wrongUser.getStatusCode().value());
        assertEquals("requester_mismatch", reason(wrongUser));
    }

    // ------------------------------------------------------------------
    // (d) RBAC: VIEWER는 파괴 액션 거부
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    void viewerCannotPerformDestructiveAction() throws Exception {
        String op03 = issueSession("op-03");
        ResponseEntity<String> denied =
                adminPost("/admin/v1/config/markets/KRW-BTC/halt", "{}", "op-03", op03);
        assertEquals(403, denied.getStatusCode().value());
        assertEquals("role_required", reason(denied));

        // 거부된 파괴 액션은 감사에 result='DENIED'로 남아야 한다(OK 오라벨 금지).
        assertEquals("DENIED", jdbc.sql("""
                        SELECT result FROM admin_audit_log
                        WHERE operator_id = 'op-03'
                          AND action = 'POST /admin/v1/config/markets/KRW-BTC/halt'
                        ORDER BY id DESC LIMIT 1
                        """).query(String.class).single());
    }

    // ------------------------------------------------------------------
    // (d) 봇 OPS 반영 + 감사 적재 + 모니터링 조회
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    void botOpsReflectedAndAuditAccrues() throws Exception {
        String op01 = issueSession("op-01");

        // PUT /admin/v1/sim/rate → OpsGovernor 런타임 반영
        ResponseEntity<String> rate = adminPut("/admin/v1/sim/rate",
                "{\"target_ops\":\"55\"}", "op-01", op01);
        assertEquals(200, rate.getStatusCode().value());
        assertTrue(objectMapper.readTree(rate.getBody()).path("applied").asBoolean());
        assertEquals(0, Double.compare(55.0, opsGovernor.targetOps()), "OPS governor 반영");

        // HA status 조회(읽기 전용)
        ResponseEntity<String> status = adminGet("/admin/v1/ha/status", "op-01", op01);
        assertEquals(200, status.getStatusCode().value());
        assertTrue(objectMapper.readTree(status.getBody()).has("lease"));

        // 감사 적재: sim.rate·config.market.halt 액션이 admin_audit_log에 존재
        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log "
                        + "WHERE operator_id = 'op-01' AND action = 'sim.rate'")
                .query(Long.class).single() >= 1);
        assertTrue(jdbc.sql("SELECT count(*) FROM admin_audit_log "
                        + "WHERE operator_id = 'op-01' AND action = 'config.market.halt' "
                        + "AND approval_id IS NOT NULL")
                .query(Long.class).single() >= 1);

        // GET /admin/v1/audit 조회
        ResponseEntity<String> audit = adminGet("/admin/v1/audit?operator=op-01", "op-01", op01);
        assertEquals(200, audit.getStatusCode().value());
        assertTrue(objectMapper.readTree(audit.getBody()).path("data").size() >= 1);
    }

    // ------------------------------------------------------------------
    // (d) RBAC 포함관계: CHAOS_OPS ⊇ BOT_OPS, 운영 역할은 조회 가능(§1-2)
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    void chaosOpsIncludesBotControl() throws Exception {
        String op04 = issueSession("op-04");

        // CHAOS_OPS 단독 운영자도 조회 가능(VIEWER 이상)
        ResponseEntity<String> simStatus = adminGet("/admin/v1/sim/status", "op-04", op04);
        assertEquals(200, simStatus.getStatusCode().value());
        ResponseEntity<String> experiments = adminGet("/admin/v1/chaos/experiments", "op-04", op04);
        assertEquals(200, experiments.getStatusCode().value());

        // CHAOS_OPS는 봇 제어(BOT_OPS)를 포함하므로 sim/rate 허용
        ResponseEntity<String> rate = adminPut("/admin/v1/sim/rate",
                "{\"target_ops\":\"33\"}", "op-04", op04);
        assertEquals(200, rate.getStatusCode().value(), "rate 응답: " + rate.getBody());
    }

    @Test
    @Order(9)
    void viewerCannotControlBots() throws Exception {
        String op03 = issueSession("op-03");
        // VIEWER는 봇 제어 불가 → 403 role_required
        ResponseEntity<String> rate = adminPut("/admin/v1/sim/rate",
                "{\"target_ops\":\"33\"}", "op-03", op03);
        assertEquals(403, rate.getStatusCode().value());
        assertEquals("role_required", reason(rate));
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private String issueSession(String operatorCn) throws Exception {
        ResponseEntity<String> issued = adminPost("/admin/v1/session", null, operatorCn, null);
        assertEquals(200, issued.getStatusCode().value(), "세션 발급 실패: " + issued.getBody());
        return objectMapper.readTree(issued.getBody()).path("token").asText();
    }

    private String approvalBody(String requester, String action, String target) {
        return "{\"requester_operator_id\":\"" + requester + "\",\"action\":\"" + action
                + "\",\"target\":\"" + target + "\"}";
    }

    private ResponseEntity<String> adminGet(String path, String operatorCn, String bearer) {
        return exchange(path, HttpMethod.GET, null, operatorCn, bearer);
    }

    private ResponseEntity<String> adminPost(String path, String body, String operatorCn,
                                             String bearer) {
        return exchange(path, HttpMethod.POST, body, operatorCn, bearer);
    }

    private ResponseEntity<String> adminPut(String path, String body, String operatorCn,
                                            String bearer) {
        return exchange(path, HttpMethod.PUT, body, operatorCn, bearer);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String body,
                                            String operatorCn, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (operatorCn != null) {
            headers.set("X-Operator-Cn", operatorCn);
        }
        if (bearer != null) {
            headers.set("Authorization", bearer.startsWith("Bearer ") ? bearer : "Bearer " + bearer);
        }
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    private String errorName(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("error").path("name").asText();
    }

    private String reason(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody())
                .path("error").path("detail").path("reason").asText();
    }
}

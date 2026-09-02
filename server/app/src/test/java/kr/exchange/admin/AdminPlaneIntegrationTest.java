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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 관리 평면 통합 검증(관리자 설계 §1-3·§3-6).
 *
 * mTLS는 게이트웨이 종단 토폴로지로 검증한다(trusted-forwarding). 게이트웨이가 검증된 인증서 CN을
 * X-Operator-Cn 헤더로 전달한다. 실 X509 파싱은 AdminMtlsResolverCertTest가 맡는다.
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
        /* 이 토폴로지는 게이트웨이가 mTLS 를 종단하고 CN 을 헤더로 실어 주는 전제다.
           그 전제에서 앱은 루프백에만 뜬다. 주소를 비워 두면 전 인터페이스 바인딩이라
           자가신고 헤더 인증이 그대로 외부에 열리고, AdminConfig 가드가 기동을 막는다 */
        registry.add("server.address", () -> "127.0.0.1");
        // 다건 관리 호출을 위해 레이트리밋 capacity 상향(운영 기본은 계약값 60, weight 10 = 6req/60s).
        registry.add("exchange.admin.rate-limit.capacity", () -> "1000000");
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
        // 기본 SimpleClientHttpRequestFactory(HttpURLConnection)는 PATCH를 거부한다. PATCH 계약
        // 검증을 위해 java.net.http 기반 팩토리로 교체한다(루트 URI 핸들러는 유지, 추가 의존성 없음).
        rest.getRestTemplate().setRequestFactory(
                new org.springframework.http.client.JdkClientHttpRequestFactory());
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
        // 역할 GRANT/REVOKE 왕복 대상. 초기 역할 없음(부여·회수 효과를 고립 검증).
        operatorSeeder.ensureOperator("op-05", "데모 역할대상", java.util.Set.of());
        customer = bootstrap.user("customer@admin.test");
        // 고객 평면 자격(데모 세션, scope=TRADE). 관리 평면에서 거부되어야 한다.
        customerSession = demoSessions.issue(customer, "customer", DemoSessionService.DEFAULT_TTL);
        // 모니터링 조회 소스: 불변식 결과 1건 적재.
        jdbc.sql("INSERT INTO invariant_checks (check_name, ok, violated_rows) "
                + "VALUES ('C01_ASSET_CONSERVATION', true, 0)").update();
    }

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

    @Test
    @Order(2)
    void sessionBoundToMtlsIdentityIsRebound() throws Exception {
        String op01Token = issueSession("op-01");
        // op-01 세션을 op-02 mTLS 신원으로 사용 → 401(세션-신원 불일치)
        ResponseEntity<String> mismatch = adminGet("/admin/v1/ha/invariants", "op-02", op01Token);
        assertEquals(401, mismatch.getStatusCode().value());
        assertEquals("jwt_verification", errorName(mismatch));
    }

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

    @Test
    @Order(10)
    void approvalRejectGuardsParties() throws Exception {
        String op01 = issueSession("op-01");
        String op02 = issueSession("op-02");
        String op03 = issueSession("op-03");
        ResponseEntity<String> issued = adminPost("/admin/v1/approvals",
                approvalBody("op-01", "config.market.halt", "KRW-BTC"), "op-02", op02);
        assertEquals(200, issued.getStatusCode().value());
        long id = objectMapper.readTree(issued.getBody()).path("approval_id").asLong();

        // 비당사자(op-03)의 거부 -> 403 not_party. 상태는 ISSUED 유지.
        ResponseEntity<String> notParty =
                adminPost("/admin/v1/approvals/" + id + "/reject", "{}", "op-03", op03);
        assertEquals(403, notParty.getStatusCode().value());
        assertEquals("not_party", reason(notParty));
        assertEquals("ISSUED", jdbc.sql("SELECT state FROM admin_approvals WHERE id = ?")
                .param(id).query(String.class).single());

        // 당사자(requester op-01)의 거부 -> 200, ISSUED->REJECTED.
        ResponseEntity<String> rejected =
                adminPost("/admin/v1/approvals/" + id + "/reject", "{}", "op-01", op01);
        assertEquals(200, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("REJECTED", objectMapper.readTree(rejected.getBody()).path("state").asText());
        assertEquals("REJECTED", jdbc.sql("SELECT state FROM admin_approvals WHERE id = ?")
                .param(id).query(String.class).single());

        // 재거부 -> 403 not_open(단회 전이).
        ResponseEntity<String> again =
                adminPost("/admin/v1/approvals/" + id + "/reject", "{}", "op-01", op01);
        assertEquals(403, again.getStatusCode().value());
        assertEquals("not_open", reason(again));
    }

    @Test
    @Order(11)
    void feesValidationAndApprovalRollback() throws Exception {
        String op01 = issueSession("op-01");
        String op02 = issueSession("op-02");

        // 스케일 초과(소수 9자리)는 승인 소비 이전 단계에서 400.
        ResponseEntity<String> scale = adminPatch("/admin/v1/config/markets/KRW-BTC/fees",
                "{\"maker_fee\":\"0.000000001\"}", "op-01", op01);
        assertEquals(400, scale.getStatusCode().value(), scale.getBody());

        // 범위 밖(>0.1)도 400.
        ResponseEntity<String> range = adminPatch("/admin/v1/config/markets/KRW-BTC/fees",
                "{\"maker_fee\":\"0.5\"}", "op-01", op01);
        assertEquals(400, range.getStatusCode().value());

        // 없는 마켓 -> 404. 승인 토큰은 액션과 함께 롤백돼 ISSUED 유지(재사용 가능).
        String absentToken = issueToken("op-01", "config.market.fees", "KRW-XXX", "op-02", op02);
        ResponseEntity<String> absent = adminPatch("/admin/v1/config/markets/KRW-XXX/fees",
                "{\"maker_fee\":\"0.001\",\"approval_token\":\"" + absentToken + "\"}", "op-01", op01);
        assertEquals(404, absent.getStatusCode().value(), absent.getBody());
        assertEquals("market_not_found", errorName(absent));
        assertEquals("ISSUED", jdbc.sql("SELECT state FROM admin_approvals WHERE approval_token = ?")
                .param(absentToken).query(String.class).single());

        // 정상 부분 갱신: maker만 교체, taker 보존.
        String okToken = issueToken("op-01", "config.market.fees", "KRW-BTC", "op-02", op02);
        ResponseEntity<String> ok = adminPatch("/admin/v1/config/markets/KRW-BTC/fees",
                "{\"maker_fee\":\"0.002\",\"approval_token\":\"" + okToken + "\"}", "op-01", op01);
        assertEquals(200, ok.getStatusCode().value(), ok.getBody());
        JsonNode body = objectMapper.readTree(ok.getBody());
        assertEquals(0, new java.math.BigDecimal(body.path("maker_fee").asText())
                .compareTo(new java.math.BigDecimal("0.002")));
        assertEquals(0, new java.math.BigDecimal(body.path("taker_fee").asText())
                .compareTo(new java.math.BigDecimal("0.0005")), "부분 갱신에서 taker 보존");
        assertEquals(0, jdbc.sql("SELECT maker_fee_rate FROM markets WHERE symbol = 'KRW-BTC'")
                .query(java.math.BigDecimal.class).single().compareTo(new java.math.BigDecimal("0.002")));
    }

    @Test
    @Order(12)
    void operatorsCreateConflictAndStatusNotFound() throws Exception {
        String op01 = issueSession("op-01");
        String op02 = issueSession("op-02");

        // 이미 존재하는 op-01 재생성 -> 409. 승인은 같은 트랜잭션이라 롤백돼 ISSUED 유지.
        String createToken = issueToken("op-01", "operators.create", "op-01", "op-02", op02);
        ResponseEntity<String> conflict = adminPost("/admin/v1/operators",
                "{\"operator_id\":\"op-01\",\"new_operator_id\":\"op-01\",\"display_name\":\"중복\","
                        + "\"approval_token\":\"" + createToken + "\"}", "op-01", op01);
        assertEquals(409, conflict.getStatusCode().value(), conflict.getBody());
        assertEquals("ISSUED", jdbc.sql("SELECT state FROM admin_approvals WHERE approval_token = ?")
                .param(createToken).query(String.class).single());

        // 없는 운영자 상태 전이 -> 404.
        String statusToken = issueToken("op-01", "operators.status", "op-nope:DISABLED",
                "op-02", op02);
        ResponseEntity<String> notFound = adminPatch("/admin/v1/operators/op-nope/status",
                "{\"status\":\"DISABLED\",\"approval_token\":\"" + statusToken + "\"}", "op-01", op01);
        assertEquals(404, notFound.getStatusCode().value(), notFound.getBody());
    }

    @Test
    @Order(13)
    void approvalsLedgerAndChaosRunsProject() throws Exception {
        String op01 = issueSession("op-01");

        ResponseEntity<String> list = adminGet("/admin/v1/approvals?state=REJECTED", "op-01", op01);
        assertEquals(200, list.getStatusCode().value());
        JsonNode data = objectMapper.readTree(list.getBody()).path("data");
        assertTrue(data.isArray() && data.size() >= 1, "order 10에서 REJECTED 1건 이상");
        for (JsonNode row : data) {
            assertFalse(row.has("approval_token"), "원장은 approval_token을 노출하지 않는다");
            assertEquals("REJECTED", row.path("state").asText());
        }

        // 카오스 이력: 주입 이력이 없어도 200·빈 data 배열.
        ResponseEntity<String> runs = adminGet("/admin/v1/chaos/runs", "op-01", op01);
        assertEquals(200, runs.getStatusCode().value());
        assertTrue(objectMapper.readTree(runs.getBody()).path("data").isArray());
    }

    @Test
    @Order(14)
    void noticePublishUpdateDelete() throws Exception {
        String op01 = issueSession("op-01");

        ResponseEntity<String> created = adminPost("/admin/v1/config/notices",
                "{\"title\":\"점검 안내\",\"body\":\"03:00 정기 점검\",\"severity\":\"INFO\"}",
                "op-01", op01);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        long id = objectMapper.readTree(created.getBody()).path("notice_id").asLong();

        // 잘못된 severity -> 400. 상태 불변.
        ResponseEntity<String> badSeverity = adminPatch("/admin/v1/config/notices/" + id,
                "{\"severity\":\"URGENT\"}", "op-01", op01);
        assertEquals(400, badSeverity.getStatusCode().value());

        // 부분 갱신: severity만 교체, title 보존. active는 삭제 멱등 검증을 위해 true 유지.
        ResponseEntity<String> patched = adminPatch("/admin/v1/config/notices/" + id,
                "{\"severity\":\"WARNING\"}", "op-01", op01);
        assertEquals(200, patched.getStatusCode().value(), patched.getBody());
        assertEquals("점검 안내", jdbc.sql("SELECT title FROM notices WHERE id = ?")
                .param(id).query(String.class).single());
        assertEquals("WARNING", jdbc.sql("SELECT severity FROM notices WHERE id = ?")
                .param(id).query(String.class).single());

        // 없는 공지 갱신 -> 404.
        ResponseEntity<String> absent = adminPatch("/admin/v1/config/notices/999999",
                "{\"active\":true}", "op-01", op01);
        assertEquals(404, absent.getStatusCode().value());

        // 소프트 삭제 -> 200. 행은 보존되고 active=false로 내려간다(하드 DELETE 금지, V5 GRANT 정합).
        ResponseEntity<String> deleted = adminDelete("/admin/v1/config/notices/" + id, "op-01", op01);
        assertEquals(200, deleted.getStatusCode().value(), deleted.getBody());
        assertTrue(objectMapper.readTree(deleted.getBody()).path("deleted").asBoolean());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM notices WHERE id = ?")
                .param(id).query(Long.class).single(), "소프트 삭제는 행을 보존한다");
        assertFalse(jdbc.sql("SELECT active FROM notices WHERE id = ?")
                .param(id).query(Boolean.class).single());

        // 이미 비활성인 공지 재삭제 -> 멱등 200(행 존재).
        ResponseEntity<String> again = adminDelete("/admin/v1/config/notices/" + id, "op-01", op01);
        assertEquals(200, again.getStatusCode().value(), again.getBody());
        assertTrue(objectMapper.readTree(again.getBody()).path("deleted").asBoolean());

        // 없는 공지 삭제 -> 404.
        ResponseEntity<String> missing = adminDelete("/admin/v1/config/notices/999999", "op-01", op01);
        assertEquals(404, missing.getStatusCode().value());
    }

    /**
     * 공지 길이 게이트. 컬럼 폭 초과를 DB 예외로 흘리면 입력 문제가 500으로 나가 운영자가
     * 서버 고장으로 읽는다. 본문 상한이 없으면 공지 한 건이 목록과 배너를 덮는다.
     */
    @Test
    @Order(15)
    void noticeLengthGate() throws Exception {
        String op01 = issueSession("op-01");

        String longTitle = "가".repeat(201);
        ResponseEntity<String> badTitle = adminPost("/admin/v1/config/notices",
                "{\"title\":\"" + longTitle + "\",\"body\":\"본문\"}", "op-01", op01);
        assertEquals(400, badTitle.getStatusCode().value(), badTitle.getBody());
        assertEquals("invalid_parameter",
                objectMapper.readTree(badTitle.getBody()).path("error").path("name").asText());

        String longBody = "나".repeat(2001);
        ResponseEntity<String> badBody = adminPost("/admin/v1/config/notices",
                "{\"title\":\"정상 제목\",\"body\":\"" + longBody + "\"}", "op-01", op01);
        assertEquals(400, badBody.getStatusCode().value(), badBody.getBody());

        // 경계값은 통과한다. 200자·2000자는 유효 입력이다
        ResponseEntity<String> ok = adminPost("/admin/v1/config/notices",
                "{\"title\":\"" + "다".repeat(200) + "\",\"body\":\"" + "라".repeat(2000) + "\"}",
                "op-01", op01);
        assertEquals(200, ok.getStatusCode().value(), ok.getBody());
        long id = objectMapper.readTree(ok.getBody()).path("notice_id").asLong();

        // PATCH도 같은 게이트를 지난다. 발행만 막고 수정을 열어두면 우회로가 된다
        ResponseEntity<String> patchLong = adminPatch("/admin/v1/config/notices/" + id,
                "{\"body\":\"" + "마".repeat(2001) + "\"}", "op-01", op01);
        assertEquals(400, patchLong.getStatusCode().value(), patchLong.getBody());
        assertEquals(2000, jdbc.sql("SELECT length(body) FROM notices WHERE id = ?")
                .param(id).query(Integer.class).single(), "거절된 수정이 행을 건드렸다");
    }

    @Test
    @Order(15)
    void viewerCannotUpdateFees() throws Exception {
        String op03 = issueSession("op-03");
        // VIEWER는 승인 토큰·본문과 무관하게 역할 게이트에서 403 role_required로 차단된다.
        ResponseEntity<String> denied = adminPatch("/admin/v1/config/markets/KRW-BTC/fees",
                "{\"maker_fee\":\"0.001\"}", "op-03", op03);
        assertEquals(403, denied.getStatusCode().value());
        assertEquals("role_required", reason(denied));
    }

    // REVOKE는 operator_roles에 하드 DELETE를 실행하므로 app_role의 DELETE 권한 회귀를 잡는다.

    @Test
    @Order(16)
    void roleGrantRevokeRoundTrip() throws Exception {
        String op01 = issueSession("op-01");
        String op02 = issueSession("op-02");

        // GRANT BOT_OPS: 효과 행 INSERT.
        String grantToken = issueToken("op-01", "operators.roles", "op-05:GRANT:BOT_OPS",
                "op-02", op02);
        ResponseEntity<String> granted = adminPost("/admin/v1/operators/op-05/roles",
                "{\"action\":\"GRANT\",\"role\":\"BOT_OPS\",\"approval_token\":\""
                        + grantToken + "\"}", "op-01", op01);
        assertEquals(200, granted.getStatusCode().value(), granted.getBody());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM operator_roles "
                        + "WHERE operator_id = 'op-05' AND role = 'BOT_OPS'")
                .query(Long.class).single(), "GRANT는 효과 행을 남긴다");

        // REVOKE BOT_OPS: 효과 행 하드 DELETE. app_role DELETE 누락 시 42501로 실패한다.
        String revokeToken = issueToken("op-01", "operators.roles", "op-05:REVOKE:BOT_OPS",
                "op-02", op02);
        ResponseEntity<String> revoked = adminPost("/admin/v1/operators/op-05/roles",
                "{\"action\":\"REVOKE\",\"role\":\"BOT_OPS\",\"approval_token\":\""
                        + revokeToken + "\"}", "op-01", op01);
        assertEquals(200, revoked.getStatusCode().value(), revoked.getBody());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM operator_roles "
                        + "WHERE operator_id = 'op-05' AND role = 'BOT_OPS'")
                .query(Long.class).single(), "REVOKE는 효과 행을 삭제한다");

        // 부여 권한 존재를 직접 단언한다. 컨테이너 슈퍼유저 접속이라 DELETE 실행이 42501을
        // 내지 않으므로, 권한 회귀는 카탈로그 조회로 잡는다(V9 GRANT 유실 방지).
        assertTrue(jdbc.sql(
                        "SELECT has_table_privilege('app_role', 'operator_roles', 'DELETE')")
                .query(Boolean.class).single(), "app_role은 operator_roles DELETE 권한을 가진다");
        assertTrue(jdbc.sql(
                        "SELECT has_table_privilege('app_role', 'idempotency_keys', 'DELETE')")
                .query(Boolean.class).single(), "app_role은 idempotency_keys DELETE 권한을 가진다");
    }

    // 엔드포인트별 의미 필드(node/name/assault)가 승인 target으로 소각되고, 다른 값은
    // binding_mismatch로 차단된다(§3-6).

    @Test
    @Order(17)
    void chaosInjectionBindsSemanticFieldToApprovalTarget() throws Exception {
        String op01 = issueSession("op-01");
        String op02 = issueSession("op-02");

        // kill-node: 토큰을 실으면 node 필드가 target으로 소각된다. 승인은 불요지만(§6-B v2.34)
        // 제시하면 종전 계약(바인딩·TTL·단회성)이 그대로 걸린다.
        String killToken = issueToken("op-01", "chaos.kill_node", "exchange-core-1", "op-02", op02);
        ResponseEntity<String> killed = adminPost("/admin/v1/chaos/kill-node",
                "{\"node\":\"exchange-core-1\",\"approval_token\":\"" + killToken + "\"}",
                "op-01", op01);
        assertEquals(200, killed.getStatusCode().value(), killed.getBody());
        JsonNode killBody = objectMapper.readTree(killed.getBody());
        assertEquals("chaos.kill_node", killBody.path("action").asText());
        assertEquals("exchange-core-1", killBody.path("target").asText());
        assertFalse(killBody.path("experiment_id").asText().isBlank(), "experiment_id 발급");
        assertTrue(killBody.path("approval_id").asLong() > 0);
        assertEquals("PENDING", killBody.path("state").asText(), "소비기가 집기 전 상태");

        // 대상 이름은 소비기에서 셸 문자열로 보간돼 docker 명령이 된다. 메타문자가 통과하면
        // 운영자 워크스테이션에서 임의 명령이 돈다. 승인 앞에서 400으로 끊는다.
        String evilTarget = "chaos-api-1 & echo pwned";
        String evilToken = issueToken("op-01", "chaos.kill_node", evilTarget, "op-02", op02);
        ResponseEntity<String> evil = adminPost("/admin/v1/chaos/kill-node",
                "{\"node\":\"" + evilTarget + "\",\"approval_token\":\"" + evilToken + "\"}",
                "op-01", op01);
        assertEquals(400, evil.getStatusCode().value(), evil.getBody());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM chaos_commands WHERE target LIKE '%&%'")
                .query(Long.class).single(), "메타문자 대상이 큐에 적재되면 안 된다");

        // 지시가 큐에 적재된다(V21). 이 행이 없으면 승인은 탔는데 아무도 주입하지 않는다.
        String expId = killBody.path("experiment_id").asText();
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM chaos_commands "
                        + "WHERE experiment_id = ?::uuid AND action = 'chaos.kill_node' "
                        + "AND target = 'exchange-core-1' AND state = 'PENDING' "
                        + "AND requested_by = 'op-01' AND approval_id IS NOT NULL")
                .param(expId).query(Long.class).single(), "명령 큐 적재");

        // 이력 조회가 집행 여부를 함께 낸다. 소비기 미가동이면 PENDING에 머문다.
        JsonNode runs = objectMapper.readTree(
                adminGet("/admin/v1/chaos/runs?limit=50", "op-01", op01).getBody()).path("data");
        boolean seen = false;
        for (JsonNode r : runs) {
            if (expId.equals(r.path("experiment_id").asText())) {
                assertEquals("PENDING", r.path("exec_state").asText());
                seen = true;
            }
        }
        assertTrue(seen, "이력에 방금 지시가 없다");

        // 감사에 target·approval_id가 소각 경로 그대로 남는다(OK 결과).
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM admin_audit_log "
                        + "WHERE operator_id = 'op-01' AND action = 'chaos.kill_node' "
                        + "AND target = 'exchange-core-1' AND approval_id IS NOT NULL "
                        + "AND result = 'OK'")
                .query(Long.class).single());

        // network: name 필드가 target으로 소각(동형).
        String netToken = issueToken("op-01", "chaos.network", "pg-latency", "op-02", op02);
        ResponseEntity<String> net = adminPost("/admin/v1/chaos/network",
                "{\"name\":\"pg-latency\",\"type\":\"latency\",\"approval_token\":\""
                        + netToken + "\"}", "op-01", op01);
        assertEquals(200, net.getStatusCode().value(), net.getBody());
        assertEquals("pg-latency", objectMapper.readTree(net.getBody()).path("target").asText());

        // app: assault 필드가 target으로 소각(동형).
        String appToken = issueToken("op-01", "chaos.app", "latency-assault", "op-02", op02);
        ResponseEntity<String> app = adminPost("/admin/v1/chaos/app",
                "{\"assault\":\"latency-assault\",\"approval_token\":\"" + appToken + "\"}",
                "op-01", op01);
        assertEquals(200, app.getStatusCode().value(), app.getBody());
        assertEquals("latency-assault", objectMapper.readTree(app.getBody()).path("target").asText());
    }

    @Test
    @Order(18)
    void chaosFieldMismatchWithApprovalTargetIsRejected() throws Exception {
        String op01 = issueSession("op-01");
        String op02 = issueSession("op-02");

        // 승인은 exchange-core-1로 지정했는데 본문 node는 exchange-core-2 → target 불일치.
        String token = issueToken("op-01", "chaos.kill_node", "exchange-core-1", "op-02", op02);
        ResponseEntity<String> mismatch = adminPost("/admin/v1/chaos/kill-node",
                "{\"node\":\"exchange-core-2\",\"approval_token\":\"" + token + "\"}",
                "op-01", op01);
        assertEquals(403, mismatch.getStatusCode().value(), mismatch.getBody());
        assertEquals("binding_mismatch", reason(mismatch));

        // 소각 실패이므로 승인은 ISSUED로 남아 재사용 가능하다(액션과 함께 롤백).
        assertEquals("ISSUED", jdbc.sql("SELECT state FROM admin_approvals WHERE approval_token = ?")
                .param(token).query(String.class).single());
    }

    // 카오스 4종은 approval_token 불요다(§6-B v2.34). 이 경로는 approval_id에 NULL을 싣는데,
    // 승인 필수였던 동안 한 번도 실행되지 않았다. 역할 게이트와 감사 적재가 완화 뒤에도
    // 남아 있는지를 함께 본다.

    @Test
    @Order(20)
    void chaosKillNodeWithoutApprovalTokenIsQueuedAndAudited() throws Exception {
        String op04 = issueSession("op-04");

        ResponseEntity<String> killed = adminPost("/admin/v1/chaos/kill-node",
                "{\"node\":\"exchange-core-9\"}", "op-04", op04);
        assertEquals(200, killed.getStatusCode().value(), killed.getBody());
        JsonNode body = objectMapper.readTree(killed.getBody());
        assertEquals("chaos.kill_node", body.path("action").asText());
        assertEquals("exchange-core-9", body.path("target").asText());
        assertEquals("PENDING", body.path("state").asText());

        String expId = body.path("experiment_id").asText();
        assertFalse(expId.isBlank(), "승인 없이도 experiment_id는 발급된다");

        // 부재와 미승인은 다른 사실이다. 키를 빼면 소비자가 둘을 구분하지 못한다.
        assertTrue(body.has("approval_id"), "approval_id 키가 응답에서 빠졌다");
        assertTrue(body.get("approval_id").isNull(), "승인 없는 주입의 approval_id는 null이다");

        assertEquals(1L, jdbc.sql("SELECT count(*) FROM chaos_commands "
                        + "WHERE experiment_id = ?::uuid AND action = 'chaos.kill_node' "
                        + "AND target = 'exchange-core-9' AND state = 'PENDING' "
                        + "AND requested_by = 'op-04' AND approval_id IS NULL")
                .param(expId).query(Long.class).single(), "무승인 명령 큐 적재");

        assertEquals(1L, jdbc.sql("SELECT count(*) FROM admin_audit_log "
                        + "WHERE operator_id = 'op-04' AND action = 'chaos.kill_node' "
                        + "AND target = 'exchange-core-9' AND result = 'OK' "
                        + "AND approval_id IS NULL")
                .query(Long.class).single(), "감사 적재는 승인 유무와 무관하다");
    }

    @Test
    @Order(21)
    void chaosNetworkAppAndClearWithoutApprovalTokenAreAccepted() throws Exception {
        String op04 = issueSession("op-04");

        ResponseEntity<String> net = adminPost("/admin/v1/chaos/network",
                "{\"name\":\"e1-toxic\",\"type\":\"latency\"}", "op-04", op04);
        assertEquals(200, net.getStatusCode().value(), net.getBody());
        JsonNode netBody = objectMapper.readTree(net.getBody());
        assertEquals("e1-toxic", netBody.path("target").asText());
        assertTrue(netBody.has("approval_id") && netBody.get("approval_id").isNull());

        ResponseEntity<String> app = adminPost("/admin/v1/chaos/app",
                "{\"assault\":\"e8-assault\"}", "op-04", op04);
        assertEquals(200, app.getStatusCode().value(), app.getBody());
        JsonNode appBody = objectMapper.readTree(app.getBody());
        assertEquals("e8-assault", appBody.path("target").asText());
        assertTrue(appBody.has("approval_id") && appBody.get("approval_id").isNull());

        // 해제는 본문 자체가 없는 호출이다. 바디 없는 경로도 같은 계약을 지나야 한다.
        ResponseEntity<String> cleared =
                adminDelete("/admin/v1/chaos/network/e1-toxic", "op-04", op04);
        assertEquals(200, cleared.getStatusCode().value(), cleared.getBody());
        JsonNode clearBody = objectMapper.readTree(cleared.getBody());
        assertEquals("e1-toxic", clearBody.path("toxic").asText());
        assertTrue(clearBody.has("approval_id") && clearBody.get("approval_id").isNull());

        List<String> ids = List.of(netBody.path("experiment_id").asText(),
                appBody.path("experiment_id").asText(),
                clearBody.path("experiment_id").asText());
        for (String id : ids) {
            assertFalse(id.isBlank(), "experiment_id 발급");
            assertEquals(1L, jdbc.sql("SELECT count(*) FROM chaos_commands "
                            + "WHERE experiment_id = ?::uuid AND requested_by = 'op-04' "
                            + "AND approval_id IS NULL")
                    .param(id).query(Long.class).single(), "무승인 명령 큐 적재: " + id);
        }

        assertEquals(3L, jdbc.sql("SELECT count(*) FROM admin_audit_log "
                        + "WHERE operator_id = 'op-04' AND result = 'OK' "
                        + "AND approval_id IS NULL AND action IN "
                        + "('chaos.network', 'chaos.app', 'chaos.network.clear')")
                .query(Long.class).single(), "해제를 포함한 3종 감사 적재");
    }

    // 시각 필드는 UTC 밀리초 3자리 고정이다(§2-1). OffsetDateTime.toString의 가변 소수부·오프셋
    // 표기가 회귀 대상이다.

    private static final String TS_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Test
    @Order(19)
    void projectionTimestampsFollowMillisUtcContract() throws Exception {
        String op01 = issueSession("op-01");

        JsonNode audit = objectMapper.readTree(
                adminGet("/admin/v1/audit?operator=op-01", "op-01", op01).getBody()).path("data");
        assertTrue(audit.size() >= 1, "선행 테스트에서 op-01 감사 1건 이상");
        for (JsonNode row : audit) {
            assertTrue(row.path("created_at").asText().matches(TS_PATTERN),
                    "audit.created_at 형식: " + row.path("created_at").asText());
        }

        JsonNode checks = objectMapper.readTree(
                adminGet("/admin/v1/ha/invariants", "op-01", op01).getBody()).path("checks");
        assertTrue(checks.size() >= 1, "seed에서 불변식 1건");
        for (JsonNode row : checks) {
            assertTrue(row.path("checked_at").asText().matches(TS_PATTERN),
                    "invariants.checked_at 형식: " + row.path("checked_at").asText());
        }

        // ha/status lease_until은 V1 부트스트랩 시드('matching-engine' 행)로 상시 존재한다.
        JsonNode lease = objectMapper.readTree(
                adminGet("/admin/v1/ha/status", "op-01", op01).getBody()).path("lease");
        assertTrue(lease.path("lease_until").asText().matches(TS_PATTERN),
                "status.lease_until 형식: " + lease.path("lease_until").asText());
    }

    private String issueToken(String requester, String action, String target,
                             String approverCn, String approverSession) throws Exception {
        ResponseEntity<String> issued = adminPost("/admin/v1/approvals",
                approvalBody(requester, action, target), approverCn, approverSession);
        assertEquals(200, issued.getStatusCode().value(), "승인 발급 실패: " + issued.getBody());
        return objectMapper.readTree(issued.getBody()).path("approval_token").asText();
    }

    private String issueSession(String operatorCn) throws Exception {
        ResponseEntity<String> issued = adminPost("/admin/v1/session", null, operatorCn, null);
        assertEquals(200, issued.getStatusCode().value(), "세션 발급 실패: " + issued.getBody());
        return objectMapper.readTree(issued.getBody()).path("token").asText();
    }

    private String approvalBody(String requester, String action, String target) {
        return "{\"requester_operator_id\":\"" + requester + "\",\"action\":\"" + action
                + "\",\"target\":\"" + target + "\"}";
    }

    /**
     * 관리 평면 목록 전수 점검. 모든 목록이 §2-2 엔벌로프를 리터럴로 싣는지, 상한을 넘는 목록이
     * 커서로 끝까지 읽히는지 본다. 감사 로그는 이 클래스가 남긴 행이 여럿이라 재료로 쓴다.
     */
    @Test
    @Order(16)
    void adminListsCarryPaginationAndFollowCursor() throws Exception {
        String op01 = issueSession("op-01");

        // (a) 목록마다 pagination이 리터럴로 존재한다. 유계 목록도 예외가 아니다
        for (String path : List.of("/admin/v1/audit", "/admin/v1/approvals",
                "/admin/v1/chaos/runs", "/admin/v1/operators", "/admin/v1/config/markets",
                "/admin/v1/config/notices", "/admin/v1/transfers?status=REQUESTED",
                "/admin/v1/support?status=OPEN")) {
            ResponseEntity<String> res = adminGet(path, "op-01", op01);
            assertEquals(200, res.getStatusCode().value(), path + " -> " + res.getBody());
            JsonNode pagination = objectMapper.readTree(res.getBody()).path("pagination");
            assertFalse(pagination.isMissingNode(), path + "에 pagination이 없다");
            assertTrue(pagination.has("next_cursor"), path + "에 next_cursor가 없다");
            assertTrue(pagination.has("has_more"), path + "에 has_more가 없다");
        }

        // (b) 감사 로그를 쪽 크기 2로 훑어 중복·누락 없이 끝까지 도달한다
        long total = jdbc.sql("SELECT count(*) FROM admin_audit_log").query(Long.class).single();
        assertTrue(total > 4, "감사 행이 부족해 페이징을 검증할 수 없다: " + total);

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            String path = "/admin/v1/audit?limit=2"
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode body = objectMapper.readTree(adminGet(path, "op-01", op01).getBody());
            pages++;
            for (JsonNode row : body.path("data")) {
                assertTrue(seen.add(row.path("id").asText()), "쪽 경계에서 감사 행이 중복됐다");
            }
            if (!body.path("pagination").path("has_more").asBoolean()) {
                assertTrue(body.path("pagination").path("next_cursor").isNull(),
                        "마지막 쪽인데 next_cursor가 남아 있다");
                break;
            }
            assertEquals(2, body.path("data").size(), "다음 쪽이 있는데 쪽이 덜 찼다");
            cursor = body.path("pagination").path("next_cursor").asText();
            assertTrue(pages <= total, "커서가 끝나지 않는다");
        }
        // 고정 LIMIT이면 그 경계를 넘는 순간부터 조용히 잘린다. 여기서는 전량 도달만 확인한다
        assertEquals(total, seen.size(), "누락 또는 중복으로 전체 건수가 어긋난다");

        // 위조 커서는 조용히 첫 쪽으로 되돌리지 않고 거절한다
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"ts\":\"2026-08-28T00:00:00Z\",\"id\":\"999999999\"}".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<String> bad = adminGet("/admin/v1/audit?limit=2&cursor="
                + URLEncoder.encode(forged, StandardCharsets.UTF_8), "op-01", op01);
        assertEquals(400, bad.getStatusCode().value(), bad.getBody());
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

    private ResponseEntity<String> adminPatch(String path, String body, String operatorCn,
                                              String bearer) {
        return exchange(path, HttpMethod.PATCH, body, operatorCn, bearer);
    }

    private ResponseEntity<String> adminDelete(String path, String operatorCn, String bearer) {
        return exchange(path, HttpMethod.DELETE, null, operatorCn, bearer);
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

package kr.exchange.api.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.admin.AdminOperatorSeeder;
import kr.exchange.admin.AdminRole;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 게시판(게시판_설계.md §4) 통합 검증:
 * (a) 목록·작성·상세·수정·삭제 왕복과 작성자 본인 강제, 비회원 쓰기 거부,
 * (b) 댓글 CRUD·1뎁스 가드, 좋아요 토글, 신고·중복 신고 차단,
 * (c) XSS 본문 출력 이스케이프, 커서 페이지네이션,
 * (d) 관리 평면 MEMBER_MOD 숨김·신고 처리(거래 평면 분리).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityIntegrationTest {

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
        registry.add("exchange.admin.enabled", () -> "true");
        registry.add("exchange.admin.mtls.trusted-forwarding", () -> "true");
        // 신고 임계 자동 숨김을 낮춰 단일 신고로 검증 가능하게 한다.
        registry.add("exchange.community.auto-hide-threshold", () -> "2");
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountBootstrap bootstrap;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    AdminOperatorSeeder operatorSeeder;
    @LocalServerPort
    int port;

    // PATCH는 JDK HttpURLConnection이 막는다. httpclient5를 전역 채택하면 그 기본 재시도 전략이
    // 429/503을 Retry-After만큼 대기 후 자동 재시도해 다른 스로틀 테스트를 깬다. PATCH만 JDK
    // java.net.http 클라이언트로 보내 전역 TestRestTemplate 의미를 건드리지 않는다(자동 재시도 없음).
    private final HttpClient patchClient = HttpClient.newHttpClient();

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeAll
    void seed() {
        bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.asset(3, "ETH", "Ethereum", "CRYPTO", 18);
        bootstrap.market(1, "KRW-BTC", 2, 1, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        bootstrap.market(2, "KRW-ETH", 3, 1, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        operatorSeeder.ensureOperator("op-mod", "모더레이터", Set.of(AdminRole.MEMBER_MOD));
        operatorSeeder.ensureOperator("op-view", "뷰어", Set.of(AdminRole.VIEWER));
    }

    // ------------------------------------------------------------------
    // (a) 목록·작성·상세·수정·삭제, 본인 강제, 비회원 거부
    // ------------------------------------------------------------------

    @Test
    void postLifecycleAndOwnershipEnforced() throws Exception {
        Member alice = signup();
        Member bob = signup();

        // 작성
        ResponseEntity<String> created = post("/api/v1/community/boards/KRW-BTC/posts",
                "{\"title\":\"첫 글\",\"body\":\"안녕하세요\"}", alice.token);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String postId = json(created).path("post_id").asText();
        assertNotNull(postId);

        // 상세
        ResponseEntity<String> detail = get("/api/v1/community/posts/" + postId, null);
        assertEquals(200, detail.getStatusCode().value());
        assertEquals("첫 글", json(detail).path("title").asText());
        assertEquals("안녕하세요", json(detail).path("body").asText());

        // 목록에 노출
        ResponseEntity<String> list = get("/api/v1/community/boards/KRW-BTC/posts", null);
        assertEquals(200, list.getStatusCode().value());
        assertTrue(containsPost(json(list).path("data"), postId), "새 글이 목록에 없음");

        // 타인(bob) 수정·삭제 거부
        ResponseEntity<String> foreignEdit = patch("/api/v1/community/posts/" + postId,
                "{\"title\":\"탈취\",\"body\":\"탈취\"}", bob.token);
        assertEquals(403, foreignEdit.getStatusCode().value());
        assertEquals("out_of_scope", errorName(foreignEdit));

        ResponseEntity<String> foreignDelete = delete("/api/v1/community/posts/" + postId, bob.token);
        assertEquals(403, foreignDelete.getStatusCode().value());

        // 본인 수정
        ResponseEntity<String> edit = patch("/api/v1/community/posts/" + postId,
                "{\"title\":\"수정됨\",\"body\":\"고쳤습니다\"}", alice.token);
        assertEquals(200, edit.getStatusCode().value(), edit.getBody());
        assertEquals("수정됨", json(edit).path("title").asText());

        // 본인 삭제(soft) 후 상세는 마스킹
        assertEquals(200, delete("/api/v1/community/posts/" + postId, alice.token)
                .getStatusCode().value());
        ResponseEntity<String> afterDelete = get("/api/v1/community/posts/" + postId, null);
        assertEquals(200, afterDelete.getStatusCode().value());
        assertEquals("DELETED", json(afterDelete).path("status").asText());
        assertFalse(json(afterDelete).path("body").asText().contains("고쳤습니다"), "삭제 본문 노출");

        // 삭제 글은 목록에서 제외
        ResponseEntity<String> listAfter = get("/api/v1/community/boards/KRW-BTC/posts", null);
        assertFalse(containsPost(json(listAfter).path("data"), postId), "삭제 글이 목록에 남음");
    }

    @Test
    void nonMemberWriteRejected() throws Exception {
        // 인증 없이 작성 → 401
        ResponseEntity<String> noAuth = post("/api/v1/community/boards/KRW-BTC/posts",
                "{\"title\":\"t\",\"body\":\"b\"}", null);
        assertEquals(401, noAuth.getStatusCode().value());
        assertEquals("jwt_verification", errorName(noAuth));

        // 데모 세션(비회원 HS256 토큰)으로 작성 → 401(회원 세션 전용)
        HttpHeaders h = new HttpHeaders();
        h.set("X-Forwarded-For", uniqueIp());
        ResponseEntity<String> demo = rest.exchange("/api/v1/demo/session", HttpMethod.POST,
                new HttpEntity<>(h), String.class);
        String demoToken = json(demo).path("access_token").asText();
        ResponseEntity<String> demoWrite = post("/api/v1/community/boards/KRW-BTC/posts",
                "{\"title\":\"t\",\"body\":\"b\"}", demoToken);
        assertEquals(401, demoWrite.getStatusCode().value());
        assertEquals("jwt_verification", errorName(demoWrite));
    }

    @Test
    void invalidMarketAndLengthGuards() throws Exception {
        Member m = signup();
        // 미존재 마켓 → 404
        assertEquals(404, post("/api/v1/community/boards/KRW-DOGE/posts",
                "{\"title\":\"t\",\"body\":\"b\"}", m.token).getStatusCode().value());
        // 빈 제목 → 400
        assertEquals(400, post("/api/v1/community/boards/KRW-BTC/posts",
                "{\"title\":\"\",\"body\":\"b\"}", m.token).getStatusCode().value());
        // 본문 길이 초과 → 400
        String tooLong = "x".repeat(4001);
        assertEquals(400, post("/api/v1/community/boards/KRW-BTC/posts",
                "{\"title\":\"t\",\"body\":\"" + tooLong + "\"}", m.token).getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // (b) 댓글, 좋아요, 신고
    // ------------------------------------------------------------------

    @Test
    void commentsRepliesAndDepthGuard() throws Exception {
        Member m = signup();
        String postId = createPost(m, "KRW-BTC", "댓글 글", "본문");

        // 댓글
        ResponseEntity<String> c1 = post("/api/v1/community/posts/" + postId + "/comments",
                "{\"body\":\"첫 댓글\"}", m.token);
        assertEquals(201, c1.getStatusCode().value(), c1.getBody());
        String commentId = json(c1).path("comment_id").asText();

        // 대댓글(1뎁스)
        ResponseEntity<String> reply = post("/api/v1/community/posts/" + postId + "/comments",
                "{\"body\":\"대댓글\",\"parent_comment_id\":\"" + commentId + "\"}", m.token);
        assertEquals(201, reply.getStatusCode().value());
        String replyId = json(reply).path("comment_id").asText();

        // 2뎁스 시도 → 400
        ResponseEntity<String> deep = post("/api/v1/community/posts/" + postId + "/comments",
                "{\"body\":\"불가\",\"parent_comment_id\":\"" + replyId + "\"}", m.token);
        assertEquals(400, deep.getStatusCode().value());
        assertEquals("invalid_parameter", errorName(deep));

        // 댓글 목록·comment_count 반영
        ResponseEntity<String> list = get("/api/v1/community/posts/" + postId + "/comments", null);
        assertEquals(2, json(list).path("data").size());
        assertEquals(2, json(get("/api/v1/community/posts/" + postId, null))
                .path("comment_count").asInt());

        // 본인 댓글 삭제(soft) → count 감소, 목록에서도 제외(목록 행수 == comment_count)
        assertEquals(200, delete("/api/v1/community/comments/" + replyId, m.token)
                .getStatusCode().value());
        assertEquals(1, json(get("/api/v1/community/posts/" + postId, null))
                .path("comment_count").asInt());
        assertEquals(1, json(get("/api/v1/community/posts/" + postId + "/comments", null))
                .path("data").size(), "삭제 댓글이 목록에 잔존");
    }

    @Test
    void commentCountMatchesVisibleRowsAcrossModeration() throws Exception {
        Member m = signup();
        Member reporter1 = signup();
        Member reporter2 = signup();
        String postId = createPost(m, "KRW-BTC", "집계 정합 글", "본문");
        String c1 = createComment(m, postId, "댓글1");
        createComment(m, postId, "댓글2");
        String c3 = createComment(m, postId, "댓글3");
        assertCommentConsistency(postId, 3);

        // 본인 soft delete
        assertEquals(200, delete("/api/v1/community/comments/" + c1, m.token).getStatusCode().value());
        assertCommentConsistency(postId, 2);

        // 모더레이터 숨김
        String modToken = adminSession("op-mod");
        assertEquals(200, adminPost("/admin/v1/community/comments/" + c3 + "/moderate",
                "{\"action\":\"HIDE\"}", "op-mod", modToken).getStatusCode().value());
        assertCommentConsistency(postId, 1);

        // 숨김 복구
        assertEquals(200, adminPost("/admin/v1/community/comments/" + c3 + "/moderate",
                "{\"action\":\"UNHIDE\"}", "op-mod", modToken).getStatusCode().value());
        assertCommentConsistency(postId, 2);

        // 신고 임계 자동 숨김(threshold=2)도 집계에서 빠진다
        post("/api/v1/community/reports",
                "{\"target_type\":\"COMMENT\",\"target_id\":\"" + c3 + "\",\"reason\":\"SPAM\"}",
                reporter1.token);
        post("/api/v1/community/reports",
                "{\"target_type\":\"COMMENT\",\"target_id\":\"" + c3 + "\",\"reason\":\"ABUSE\"}",
                reporter2.token);
        assertCommentConsistency(postId, 1);

        // DELETED 항목 UNHIDE 부활 금지
        assertEquals(400, adminPost("/admin/v1/community/comments/" + c1 + "/moderate",
                "{\"action\":\"UNHIDE\"}", "op-mod", modToken).getStatusCode().value());
    }

    @Test
    void moderationRejectsSelfDeletedTargets() throws Exception {
        Member author = signup();
        String modToken = adminSession("op-mod");

        // 회원 자가삭제 글(DELETED)에 HIDE→UNHIDE 부활 시도 차단
        String postId = createPost(author, "KRW-BTC", "자가삭제 글", "본문");
        assertEquals(200, delete("/api/v1/community/posts/" + postId, author.token)
                .getStatusCode().value());
        ResponseEntity<String> hideDeleted = adminPost(
                "/admin/v1/community/posts/" + postId + "/moderate",
                "{\"action\":\"HIDE\"}", "op-mod", modToken);
        assertEquals(400, hideDeleted.getStatusCode().value(), hideDeleted.getBody());
        assertEquals("invalid_parameter", errorName(hideDeleted));
        // 여전히 DELETED, 본문 마스킹 유지
        assertEquals("DELETED", json(get("/api/v1/community/posts/" + postId, null))
                .path("status").asText());
        // UNHIDE도 거부(부활 경로 봉쇄)
        assertEquals(400, adminPost("/admin/v1/community/posts/" + postId + "/moderate",
                "{\"action\":\"UNHIDE\"}", "op-mod", modToken).getStatusCode().value());

        // 자가삭제 댓글도 동일하게 모더레이션 거부, comment_count 불변
        String postId2 = createPost(author, "KRW-BTC", "댓글 자가삭제", "본문");
        String commentId = createComment(author, postId2, "지울 댓글");
        assertCommentConsistency(postId2, 1);
        assertEquals(200, delete("/api/v1/community/comments/" + commentId, author.token)
                .getStatusCode().value());
        assertCommentConsistency(postId2, 0);
        assertEquals(400, adminPost("/admin/v1/community/comments/" + commentId + "/moderate",
                "{\"action\":\"HIDE\"}", "op-mod", modToken).getStatusCode().value());
        // 거부되었으므로 이중 감산 없이 0 유지
        assertCommentConsistency(postId2, 0);
    }

    private void assertCommentConsistency(String postId, int expected) throws Exception {
        int listed = json(get("/api/v1/community/posts/" + postId + "/comments", null))
                .path("data").size();
        int count = json(get("/api/v1/community/posts/" + postId, null))
                .path("comment_count").asInt();
        assertEquals(expected, listed, "가시 댓글 목록 행수 불일치");
        assertEquals(expected, count, "comment_count 불일치");
    }

    private String createComment(Member m, String postId, String body) throws Exception {
        ResponseEntity<String> res = post("/api/v1/community/posts/" + postId + "/comments",
                "{\"body\":\"" + body + "\"}", m.token);
        assertEquals(201, res.getStatusCode().value(), res.getBody());
        return json(res).path("comment_id").asText();
    }

    @Test
    void likeToggleIdempotent() throws Exception {
        Member m = signup();
        String postId = createPost(m, "KRW-BTC", "좋아요 글", "본문");

        ResponseEntity<String> like1 = post("/api/v1/community/posts/" + postId + "/like", null, m.token);
        assertEquals(200, like1.getStatusCode().value());
        assertEquals(1, json(like1).path("like_count").asInt());

        // 재요청은 멱등(카운트 불변)
        ResponseEntity<String> like2 = post("/api/v1/community/posts/" + postId + "/like", null, m.token);
        assertEquals(1, json(like2).path("like_count").asInt());

        // 취소
        ResponseEntity<String> unlike = delete("/api/v1/community/posts/" + postId + "/like", m.token);
        assertEquals(0, json(unlike).path("like_count").asInt());
    }

    @Test
    void reportDuplicateRejected() throws Exception {
        Member author = signup();
        Member reporter = signup();
        String postId = createPost(author, "KRW-BTC", "신고 글", "본문");

        ResponseEntity<String> first = post("/api/v1/community/reports",
                "{\"target_type\":\"POST\",\"target_id\":\"" + postId + "\",\"reason\":\"SPAM\"}",
                reporter.token);
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        assertTrue(json(first).path("reported").asBoolean());

        // 동일 신고자 재신고 → 409
        ResponseEntity<String> dup = post("/api/v1/community/reports",
                "{\"target_type\":\"POST\",\"target_id\":\"" + postId + "\",\"reason\":\"ABUSE\"}",
                reporter.token);
        assertEquals(409, dup.getStatusCode().value());
        assertEquals("duplicate_report", errorName(dup));
    }

    // ------------------------------------------------------------------
    // (c) XSS 이스케이프, 페이지네이션
    // ------------------------------------------------------------------

    @Test
    void bodyEscapedOnRender() throws Exception {
        Member m = signup();
        String payload = "<script>alert('x')</script>";
        String postId = createPost(m, "KRW-BTC", "<b>제목</b>", payload);

        JsonNode detail = json(get("/api/v1/community/posts/" + postId, null));
        assertFalse(detail.path("body").asText().contains("<script>"), "원본 스크립트 태그 노출");
        assertTrue(detail.path("body").asText().contains("&lt;script&gt;"), "이스케이프 안 됨");
        assertTrue(detail.path("title").asText().contains("&lt;b&gt;"), "제목 미이스케이프");
    }

    @Test
    void cursorPaginationCoversAllPosts() throws Exception {
        Member m = signup();
        long authorId = jdbc.sql("SELECT id FROM users WHERE email = ?")
                .param(m.email).query(Long.class).single();
        // 결정적 순서를 위해 created_at을 벌려 직접 시드한다(작성 rate limit·시각 충돌 회피).
        int total = 25;
        for (int i = 0; i < total; i++) {
            jdbc.sql("""
                            INSERT INTO posts (market_symbol, author_id, author_handle, title, body, created_at)
                            VALUES ('KRW-ETH', ?, '작성자', ?, '본문', now() + make_interval(secs => ?))
                            """)
                    .params(authorId, "페이지 글 " + i, (double) i)
                    .update();
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/api/v1/community/boards/KRW-ETH/posts?count=10"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode body = json(get(path, null));
            for (JsonNode row : body.path("data")) {
                assertTrue(seen.add(row.path("post_id").asText()), "커서 페이지 간 중복");
            }
            cursor = body.path("pagination").path("next_cursor").isNull() ? null
                    : body.path("pagination").path("next_cursor").asText();
            pages++;
            assertTrue(pages <= 10, "페이지 루프 과다");
        } while (cursor != null);

        assertEquals(total, seen.size(), "전 글이 페이지네이션으로 커버되지 않음");
    }

    // ------------------------------------------------------------------
    // (d) 관리 평면 MEMBER_MOD 모더레이션
    // ------------------------------------------------------------------

    @Test
    void memberModCanHideAndResolveReports() throws Exception {
        Member author = signup();
        Member reporter = signup();
        String postId = createPost(author, "KRW-BTC", "모더레이션 글", "부적절");

        // 회원(비운영자)은 모더레이션 불가: 관리 세션이 없어 관리 평면에서 거부
        String modToken = adminSession("op-mod");
        String viewToken = adminSession("op-view");

        // VIEWER는 MEMBER_MOD 부재 → 403
        ResponseEntity<String> viewerHide = adminPost(
                "/admin/v1/community/posts/" + postId + "/moderate",
                "{\"action\":\"HIDE\"}", "op-view", viewToken);
        assertEquals(403, viewerHide.getStatusCode().value());

        // MEMBER_MOD 숨김
        ResponseEntity<String> hide = adminPost(
                "/admin/v1/community/posts/" + postId + "/moderate",
                "{\"action\":\"HIDE\",\"note\":\"부적절\"}", "op-mod", modToken);
        assertEquals(200, hide.getStatusCode().value(), hide.getBody());

        // 숨김 글은 목록 제외, 상세는 마스킹
        assertFalse(containsPost(json(get("/api/v1/community/boards/KRW-BTC/posts", null))
                .path("data"), postId), "숨김 글이 목록에 남음");
        assertEquals("HIDDEN", json(get("/api/v1/community/posts/" + postId, null))
                .path("status").asText());

        // 복구
        ResponseEntity<String> unhide = adminPost(
                "/admin/v1/community/posts/" + postId + "/moderate",
                "{\"action\":\"UNHIDE\"}", "op-mod", modToken);
        assertEquals(200, unhide.getStatusCode().value());
        assertTrue(containsPost(json(get("/api/v1/community/boards/KRW-BTC/posts", null))
                .path("data"), postId), "복구 글이 목록에 없음");

        // 신고 접수 후 MEMBER_MOD가 조회·처리
        post("/api/v1/community/reports",
                "{\"target_type\":\"POST\",\"target_id\":\"" + postId + "\",\"reason\":\"ABUSE\"}",
                reporter.token);
        ResponseEntity<String> reports = adminGet("/admin/v1/community/reports?status=PENDING",
                "op-mod", modToken);
        assertEquals(200, reports.getStatusCode().value());
        JsonNode pending = json(reports).path("data");
        assertTrue(pending.size() >= 1, "대기 신고 없음");
        long reportId = pending.get(0).path("report_id").asLong();

        ResponseEntity<String> resolve = adminPost(
                "/admin/v1/community/reports/" + reportId + "/resolve",
                "{\"resolution\":\"DISMISSED\"}", "op-mod", modToken);
        assertEquals(200, resolve.getStatusCode().value(), resolve.getBody());

        // 모더레이션 로그 적재 확인
        assertTrue(jdbc.sql("SELECT count(*) FROM community_moderation_log "
                        + "WHERE operator_id = 'op-mod' AND action = 'HIDE'")
                .query(Long.class).single() >= 1);
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private record Member(String token, String email) {
    }

    private Member signup() throws Exception {
        String email = "board" + seq.incrementAndGet() + "@demo.test";
        String json = "{\"email\":\"" + email + "\",\"password\":\"demopass1\","
                + "\"display_name\":\"글쓴이" + seq.get() + "\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());
        ResponseEntity<String> res = rest.exchange("/api/v1/auth/signup", HttpMethod.POST,
                new HttpEntity<>(json, headers), String.class);
        assertEquals(201, res.getStatusCode().value(), "signup 실패: " + res.getBody());
        return new Member(json(res).path("access_token").asText(), email);
    }

    private String createPost(Member m, String market, String title, String body) throws Exception {
        ResponseEntity<String> res = post("/api/v1/community/boards/" + market + "/posts",
                "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}", m.token);
        assertEquals(201, res.getStatusCode().value(), res.getBody());
        return json(res).path("post_id").asText();
    }

    private String adminSession(String operatorCn) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Operator-Cn", operatorCn);
        ResponseEntity<String> res = rest.exchange("/admin/v1/session", HttpMethod.POST,
                new HttpEntity<>((byte[]) null, headers), String.class);
        assertEquals(200, res.getStatusCode().value(), "관리 세션 실패: " + res.getBody());
        return json(res).path("token").asText();
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return exchange(path, HttpMethod.GET, null, bearer, null);
    }

    private ResponseEntity<String> post(String path, String body, String bearer) {
        return exchange(path, HttpMethod.POST, body, bearer, null);
    }

    private ResponseEntity<String> patch(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", uniqueIp())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : body, StandardCharsets.UTF_8));
        if (bearer != null) {
            req.header("Authorization", bearer.startsWith("Bearer ") ? bearer : "Bearer " + bearer);
        }
        HttpResponse<String> res = patchClient.send(req.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return ResponseEntity.status(res.statusCode()).body(res.body());
    }

    private ResponseEntity<String> delete(String path, String bearer) {
        return exchange(path, HttpMethod.DELETE, null, bearer, null);
    }

    private ResponseEntity<String> adminGet(String path, String cn, String bearer) {
        return exchange(path, HttpMethod.GET, null, bearer, cn);
    }

    private ResponseEntity<String> adminPost(String path, String body, String cn, String bearer) {
        return exchange(path, HttpMethod.POST, body, bearer, cn);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String body,
                                            String bearer, String operatorCn) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());
        if (bearer != null) {
            headers.set("Authorization", bearer.startsWith("Bearer ") ? bearer : "Bearer " + bearer);
        }
        if (operatorCn != null) {
            headers.set("X-Operator-Cn", operatorCn);
        }
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        // PATCH는 SimpleClientHttpRequestFactory가 막으므로 X-HTTP-Method-Override 없이 직접 지원되는
        // TestRestTemplate 경로를 쓴다. 실패 시 명시적으로 드러난다.
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> res) throws Exception {
        return objectMapper.readTree(res.getBody() == null ? "{}" : res.getBody());
    }

    private String errorName(ResponseEntity<String> res) throws Exception {
        return json(res).path("error").path("name").asText();
    }

    private boolean containsPost(JsonNode data, String postId) {
        for (JsonNode row : data) {
            if (postId.equals(row.path("post_id").asText())) {
                return true;
            }
        }
        return false;
    }

    private final AtomicInteger ipSeq = new AtomicInteger();

    private String uniqueIp() {
        int n = ipSeq.incrementAndGet();
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }
}

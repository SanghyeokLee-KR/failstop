package kr.exchange.api.notice;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공지 목록 커서 페이지네이션(API 설계 §2-6, §4-F) 실 DB 검증.
 *
 * 종전 구현은 LIMIT 50 고정에 pagination 엔벌로프도 없었다. 공지는 삭제되지 않고 철회만
 * 되므로(V5 §7 DELETE 미부여) 활성 공지가 상한을 넘는 상태가 실제로 온다. 그때 51번째
 * 공지는 존재하는데도 어떤 클라이언트도 도달할 수 없었고 잘렸다는 표시조차 없었다.
 *
 * 여기서 보는 것: (a) 상한을 넘는 공지를 커서로 전부 따라가는가, (b) 같은 시각에 발행된
 * 공지가 쪽 경계에서 중복·누락되지 않는가(published_at 단독 축의 실패 모드), (c) 철회분이
 * 공개 목록에서 빠지는가, (d) 위조 커서가 400인가.
 *
 * 공지는 무인증 조회다. 회원 세션 없이 직접 부른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoticePaginationIntegrationTest {

    /** 종전 고정 상한(50)을 넘겨 심는다. 그 경계 밖이 실제로 읽히는지가 이 테스트의 요점이다 */
    private static final int TOTAL = 57;
    private static final int PAGE = 10;

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
    ObjectMapper objectMapper;

    private final AtomicInteger ipSeq = new AtomicInteger();

    /** 활성 공지 id를 최신순으로. 기대 순서의 정본이다 */
    private final List<String> activeNewestFirst = new ArrayList<>();

    @BeforeAll
    void seed() {
        jdbc.sql("DELETE FROM notices").update();

        /* 절반은 같은 published_at을 공유한다. published_at 단독 축이면 이 구간에서
           쪽 경계가 무너진다. id 보조 축이 실제로 필요한지 여기서 갈린다 */
        for (int i = 0; i < TOTAL; i++) {
            String at = "now() - interval '" + (i / 2) + " hours'";
            jdbc.sql("INSERT INTO notices (title, body, severity, active, published_at) "
                            + "VALUES (?, ?, 'INFO', true, " + at + ")")
                    .params("공지 " + i, "본문 " + i)
                    .update();
        }
        // 철회분. 공개 목록에서 빠져야 한다
        for (int i = 0; i < 5; i++) {
            jdbc.sql("INSERT INTO notices (title, body, severity, active) VALUES (?, '', 'INFO', false)")
                    .param("철회 " + i)
                    .update();
        }

        activeNewestFirst.addAll(jdbc.sql(
                        "SELECT id FROM notices WHERE active = true ORDER BY published_at DESC, id DESC")
                .query(String.class).list());
        assertEquals(TOTAL, activeNewestFirst.size());
    }

    @Test
    void cursorReachesEveryActiveNoticePastTheOldFixedCap() throws Exception {
        List<String> walked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            JsonNode body = list(cursor, PAGE);
            JsonNode data = body.path("data");
            pages++;
            for (JsonNode row : data) {
                String id = row.path("id").asText();
                assertTrue(seen.add(id), "쪽 경계에서 공지가 중복됐다: " + id);
                walked.add(id);
                assertEquals("INFO", row.path("severity").asText());
            }
            boolean hasMore = body.path("pagination").path("has_more").asBoolean();
            String next = body.path("pagination").path("next_cursor").asText(null);
            if (!hasMore) {
                assertNull(next, "마지막 쪽인데 next_cursor가 남아 있다");
                break;
            }
            assertEquals(PAGE, data.size(), "다음 쪽이 있는데 쪽이 덜 찼다");
            assertNotNull(next, "has_more인데 next_cursor가 없다");
            cursor = next;
            assertTrue(pages <= TOTAL, "커서가 끝나지 않는다");
        }

        // 종전 LIMIT 50이면 여기서 50에서 멈췄다
        assertEquals(TOTAL, walked.size(), "고정 상한 밖 공지에 도달하지 못했다");
        assertEquals(activeNewestFirst, walked, "정렬이 (published_at DESC, id DESC)가 아니다");
        assertEquals((TOTAL + PAGE - 1) / PAGE, pages);
    }

    @Test
    void retractedNoticesNeverAppear() throws Exception {
        Set<String> publicIds = new HashSet<>();
        String cursor = null;
        while (true) {
            JsonNode body = list(cursor, PAGE);
            body.path("data").forEach(n -> publicIds.add(n.path("id").asText()));
            if (!body.path("pagination").path("has_more").asBoolean()) {
                break;
            }
            cursor = body.path("pagination").path("next_cursor").asText();
        }
        List<String> retracted = jdbc.sql("SELECT id FROM notices WHERE active = false")
                .query(String.class).list();
        assertEquals(5, retracted.size());
        for (String id : retracted) {
            assertFalse(publicIds.contains(id), "철회된 공지가 공개 목록에 있다: " + id);
        }
    }

    @Test
    void firstPageAlwaysCarriesPaginationEnvelope() throws Exception {
        // §2-2. 한 쪽으로 끝나도 필드는 리터럴로 존재한다. 종전 응답에는 이 필드가 없었다
        JsonNode body = list(null, 500);
        assertTrue(body.path("pagination").has("next_cursor"));
        assertTrue(body.path("pagination").has("has_more"));
        // limit은 200으로 잘린다. 활성 57건이라 한 쪽에 다 담긴다
        assertEquals(TOTAL, body.path("data").size());
        assertFalse(body.path("pagination").path("has_more").asBoolean());
        assertTrue(body.path("pagination").path("next_cursor").isNull());
    }

    @Test
    void malformedCursorIsRejectedNotSilentlyReset() throws Exception {
        for (String bad : List.of("not-base64!!", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"ts\":\"nope\",\"id\":\"nope\"}".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"ts\":\"2026-08-28T00:00:00Z\",\"id\":\"999999\"}"
                                .getBytes(StandardCharsets.UTF_8)))) {
            ResponseEntity<String> res = get("/api/v1/notices?limit=10&cursor="
                    + URLEncoder.encode(bad, StandardCharsets.UTF_8));
            assertEquals(400, res.getStatusCode().value(), "커서 '" + bad + "'가 400이 아니다");
            assertEquals("invalid_parameter",
                    objectMapper.readTree(res.getBody()).path("error").path("name").asText());
        }
    }

    @Test
    void retractedNoticeAsCursorStillWalksTheRest() throws Exception {
        /* 읽는 도중 운영자가 한 건을 내려도 그 지점부터 이어 읽힌다. 앵커를 active로
           제한하면 여기서 400이 나고 목록의 나머지가 통째로 막힌다 */
        String retracted = jdbc.sql(
                        "SELECT id FROM notices WHERE active = false ORDER BY id LIMIT 1")
                .query(String.class).single();
        String at = jdbc.sql("SELECT published_at FROM notices WHERE id = ?::bigint")
                .param(retracted).query(String.class).single();
        assertNotNull(at);
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"ts\":\"2026-08-28T00:00:00Z\",\"id\":\"" + retracted + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        ResponseEntity<String> res = get("/api/v1/notices?limit=10&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        assertEquals(200, res.getStatusCode().value(), res.getBody());
    }

    /* ===== 보조 ===== */

    private JsonNode list(String cursor, int limit) throws Exception {
        String path = "/api/v1/notices?limit=" + limit
                + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        ResponseEntity<String> res = get(path);
        assertEquals(200, res.getStatusCode().value(), res.getBody());
        return objectMapper.readTree(res.getBody());
    }

    private ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        // 공지는 IP 60회/60s 자체 스로틀이다. 요청마다 고유 IP를 실어 버킷을 나눈다
        headers.set("X-Forwarded-For", uniqueIp());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String uniqueIp() {
        int n = ipSeq.incrementAndGet();
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }
}

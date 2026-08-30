package kr.exchange.api.transfer;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * 내 입출금 내역 커서 페이지네이션(API 설계 §2-6, §4-E) 실 DB 검증.
 *
 * 검증 대상은 (a) 커서가 실제로 발급되고 추종되는지, (b) 쪽 경계에서 행이 겹치거나 빠지지
 * 않는지, (c) 커서가 opaque라 내부 PK를 노출하지 않는지, (d) 타인의 external_id를 커서로
 * 넘겨 남의 정렬 위치를 캘 수 없는지다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferPaginationIntegrationTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int KRW_BTC = 1;

    /** 한 쪽 크기. 전체 건수의 약수가 아닌 값이라 마지막 쪽이 부분으로 남는다 */
    private static final int PAGE = 4;
    private static final int TOTAL = 11;

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

    private final AtomicInteger seq = new AtomicInteger();

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
    void cursorWalksEveryRowExactlyOnceInDescendingOrder() throws Exception {
        String token = signup();
        List<String> created = deposit(token, TOTAL);

        List<String> walked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            JsonNode body = list(token, cursor, PAGE);
            JsonNode data = body.path("data");
            pages++;
            for (JsonNode row : data) {
                String id = row.path("external_id").asText();
                assertTrue(seen.add(id), "쪽 경계에서 행이 중복됐다: " + id);
                walked.add(id);
            }
            boolean hasMore = body.path("pagination").path("has_more").asBoolean();
            String next = body.path("pagination").path("next_cursor").asText(null);
            if (!hasMore) {
                // 마지막 쪽은 커서를 발급하지 않는다. 남기면 클라이언트가 빈 쪽을 한 번 더 읽는다
                assertNull(next, "마지막 쪽인데 next_cursor가 남아 있다");
                assertTrue(data.size() <= PAGE);
                break;
            }
            assertEquals(PAGE, data.size(), "다음 쪽이 있는데 쪽이 덜 찼다");
            assertNotNull(next, "has_more인데 next_cursor가 없다");
            cursor = next;
            assertTrue(pages <= TOTAL, "커서가 끝나지 않는다");
        }

        assertEquals(TOTAL, walked.size(), "누락 또는 중복으로 전체 건수가 어긋난다");
        // 최신순. 생성 역순과 정확히 같아야 한다
        List<String> expected = new ArrayList<>(created);
        java.util.Collections.reverse(expected);
        assertEquals(expected, walked, "정렬이 (created_at DESC, id DESC)가 아니다");
        assertEquals((TOTAL + PAGE - 1) / PAGE, pages);
    }

    @Test
    void cursorIsOpaqueAndCarriesNoInternalPk() throws Exception {
        String token = signup();
        deposit(token, PAGE + 1);

        String cursor = list(token, null, PAGE).path("pagination").path("next_cursor").asText(null);
        assertNotNull(cursor);
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("\"ts\"") && decoded.contains("\"id\""),
                "§2-6 (ts,id) 형식이 아니다: " + decoded);

        // id 축은 external_id(UUID)다. 내부 BIGINT PK가 실리면 행 개수와 채번을 유추당한다
        String id = decoded.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertEquals(36, id.length(), "id 축이 UUID가 아니다: " + id);
        assertTrue(jdbc.sql("SELECT count(*) FROM transfer_requests WHERE external_id = ?::uuid")
                .params(id).query(Long.class).single() == 1, "커서 id가 실제 행을 가리키지 않는다");
    }

    @Test
    void malformedCursorIsRejectedNotSilentlyReset() throws Exception {
        String token = signup();
        deposit(token, 2);

        for (String bad : List.of("1", "not-base64!!", Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"ts\":\"nope\",\"id\":\"nope\"}".getBytes(StandardCharsets.UTF_8)))) {
            ResponseEntity<String> res = rawList(token, bad);
            assertEquals(400, res.getStatusCode().value(), "커서 '" + bad + "'가 400이 아니다");
            assertEquals("invalid_parameter",
                    objectMapper.readTree(res.getBody()).path("error").path("name").asText());
        }
    }

    @Test
    void otherMembersRowAsCursorIsRejected() throws Exception {
        String mine = signup();
        String theirs = signup();
        deposit(mine, 2);
        List<String> theirRows = deposit(theirs, 2);

        // 남의 external_id로 커서를 위조해도 내 목록의 정렬 위치를 얻지 못한다.
        // 앵커 조회가 user_id 스코프라 행을 못 찾고 형식 오류와 같은 400이 된다
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"ts\":\"2026-08-28T00:00:00Z\",\"id\":\"" + theirRows.get(0) + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        ResponseEntity<String> res = rawList(mine, forged);
        assertEquals(400, res.getStatusCode().value());
        assertEquals("invalid_parameter",
                objectMapper.readTree(res.getBody()).path("error").path("name").asText());
    }

    @Test
    void emptyHistoryStillCarriesPaginationEnvelope() throws Exception {
        String token = signup();
        JsonNode body = list(token, null, PAGE);
        assertEquals(0, body.path("data").size());
        // §2-2: 페이지네이션이 없는 리스트도 필드는 리터럴로 존재한다
        assertTrue(body.path("pagination").has("next_cursor"));
        assertTrue(body.path("pagination").path("next_cursor").isNull());
        assertFalse(body.path("pagination").path("has_more").asBoolean());
    }

    private String signup() throws Exception {
        int n = seq.incrementAndGet();
        String json = "{\"email\":\"pager" + n + "@demo.local\",\"password\":\"demopass1\","
                + "\"display_name\":\"페이저\"}";
        ResponseEntity<String> res = post("/api/v1/auth/signup", json, null, null);
        assertEquals(201, res.getStatusCode().value(), res.getBody());
        return objectMapper.readTree(res.getBody()).path("access_token").asText();
    }

    /** 생성 순서대로 external_id를 돌려준다. 목록의 기대 역순을 만드는 재료다 */
    private List<String> deposit(String token, int count) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String json = "{\"direction\":\"DEPOSIT\",\"asset\":\"KRW\",\"amount\":\"100000\"}";
            ResponseEntity<String> res = post("/api/v1/transfers", json, "Bearer " + token,
                    "pager-" + seq.incrementAndGet());
            assertEquals(201, res.getStatusCode().value(), res.getBody());
            ids.add(objectMapper.readTree(res.getBody()).path("external_id").asText());
        }
        return ids;
    }

    private JsonNode list(String token, String cursor, int limit) throws Exception {
        String path = "/api/v1/transfers?limit=" + limit
                + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        ResponseEntity<String> res = get(path, "Bearer " + token);
        assertEquals(200, res.getStatusCode().value(), res.getBody());
        return objectMapper.readTree(res.getBody());
    }

    private ResponseEntity<String> rawList(String token, String cursor) {
        return get("/api/v1/transfers?limit=4&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8), "Bearer " + token);
    }

    private ResponseEntity<String> post(String path, String json, String bearer, String idem) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());
        if (bearer != null) {
            headers.set("Authorization", bearer);
        }
        if (idem != null) {
            headers.set("Idempotency-Key", idem);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", bearer);
        headers.set("X-Forwarded-For", uniqueIp());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    // 진입 rate limit이 요청마다 다른 버킷을 쓰도록 고유 IP를 실는다(MemberAuthIntegrationTest 동형)
    private String uniqueIp() {
        int n = seq.incrementAndGet();
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }
}

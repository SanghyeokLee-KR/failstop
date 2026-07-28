package kr.exchange.api;

import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.common.OrderAdmissionControl;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.ledger.AccountBootstrap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주문 쓰기 수용 제한의 계약 고정(API 설계 §2-5 node 버킷).
 *
 * 증명 대상은 셋이다.
 *  (1) 상한 초과는 접수 이전에 429 + Retry-After로 거절되고 저널·원장·수요 축에 흔적이 없다.
 *      흔적이 남으면 RPO0 계약(ACK된 명령의 저널 누락 0)의 반대편, 즉 '거절했는데 접수됨'이 된다.
 *  (2) 상한 이하 부하에서는 거절이 0이고 전량 접수된다. 백프레셔가 정상 처리량을 깎으면 안 된다.
 *  (3) 실패 경로를 반복해도 허용량이 전량 회복된다. permit 누수는 서서히 전면 정지로 수렴한다.
 *
 * 상한을 2로 고정해 고갈을 결정적으로 만든다. 실 배포는 요청 풀에서 유도한다(풀 32 -> 24).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// 테스트 컨텍스트는 기본적으로 메트릭 익스포트를 끈다(management.defaults.metrics.export.enabled).
// 그러면 레지스트리가 SimpleMeterRegistry로 대체돼 Prometheus 노출명을 검증할 수 없고, 이름 불일치가
// 그대로 배포까지 흘러 게이트가 없는 시계열을 조회하게 된다. 여기서만 배포와 같은 익스포트를 켠다.
@AutoConfigureObservability(tracing = false)
class OrderAdmissionIntegrationTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int KRW_BTC = 1;
    private static final int LIMIT = 2;

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
        registry.add("exchange.admission.order-in-flight", () -> String.valueOf(LIMIT));
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
    OrderAdmissionControl admission;
    @Autowired
    LeaderWriteProgress writeProgress;
    @Autowired
    MeterRegistry meterRegistry;

    DemoKeySeeder.IssuedKey tradeKey;
    long traderId;

    @BeforeAll
    void seed() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        traderId = bootstrap.user("admission@api.test");
        bootstrap.userAccounts(traderId, KRW);
        bootstrap.userAccounts(traderId, BTC);
        bootstrap.deposit(traderId, KRW, new BigDecimal("1000000000"));
        tradeKey = keySeeder.issue(traderId, "TRADE", "admission-trade");
        // 접수는 리더 확정 후에만 열린다. 승격 전에는 503이라 수용 판정 대조가 무의미하다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                                + "AND lease_until > now()")
                        .query(Long.class).single() > 0);
    }

    @Test
    @Order(1)
    void beyondLimitRejectsBeforeAcceptanceWithoutJournalOrLedgerTrace() {
        assertEquals(LIMIT, admission.limit(), "상한 고정이 적용되지 않았다");
        String clientOrderId = "admission-shed-1";

        // 상한만큼 permit을 선점해 다음 요청이 반드시 초과가 되게 한다. 실 카오스에서는
        // 느린 DB에 붙들린 요청들이 이 자리를 차지한다.
        List<Boolean> taken = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            taken.add(admission.tryAcquire());
        }
        try {
            assertTrue(taken.stream().allMatch(Boolean::booleanValue), "선점에 실패했다");

            long journalBefore = count("SELECT count(*) FROM command_journal");
            long ledgerBefore = count("SELECT count(*) FROM ledger_entries");
            long attemptsBefore = writeProgress.attempts();
            double rejectedBefore = rejectedCount();

            long startNanos = System.nanoTime();
            ResponseEntity<String> response = placeOrder(clientOrderId);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            assertEquals(429, response.getStatusCode().value(),
                    "수용 초과가 429로 거절되지 않았다: " + response.getBody());
            assertNotNull(response.getHeaders().getFirst("Retry-After"),
                    "Retry-After가 없다. 클라이언트 백오프 계약(§2-3·§2-5) 위반");
            assertTrue(Integer.parseInt(response.getHeaders().getFirst("Retry-After")) >= 1);
            String body = response.getBody();
            assertNotNull(body);
            assertTrue(body.contains("\"too_many_requests\""), "에러코드가 계약과 다르다: " + body);
            assertTrue(body.contains("\"bucket\":\"node\""), "위반 버킷 표기가 없다: " + body);
            assertTrue(body.contains("\"scope\":\"order\""), "버킷 scope 표기가 없다: " + body);
            // 대기열 없음. 거절이 지연되면 그 자체로 스레드가 쌓인다.
            assertTrue(elapsedMs < 1000, "거절이 즉시 반환되지 않았다: " + elapsedMs + "ms");

            // 접수 이전 거절의 정의: 저널·원장 어디에도 흔적이 없다.
            assertEquals(0, count(
                            "SELECT count(*) FROM command_journal WHERE client_order_id = '"
                                    + clientOrderId + "'"),
                    "거절된 주문이 저널에 남았다");
            assertEquals(0, count("SELECT count(*) FROM orders WHERE client_order_id = '"
                            + clientOrderId + "'"),
                    "거절된 주문이 프로젝션에 남았다");
            assertEquals(journalBefore, count("SELECT count(*) FROM command_journal"),
                    "거절 요청이 저널을 늘렸다");
            assertEquals(ledgerBefore, count("SELECT count(*) FROM ledger_entries"),
                    "거절 요청이 원장을 늘렸다");

            // 거절은 쓰기 수요가 아니다. 수요로 세면 부하를 흘리는 건강한 노드의 완료율이
            // 임계 아래로 내려가 헛강등한다(DB 설계 §5.4).
            assertEquals(attemptsBefore, writeProgress.attempts(),
                    "거절이 좀비 판정 분모를 오염시켰다");
            assertEquals(rejectedBefore + 1, rejectedCount(), "거절 카운터가 오르지 않았다");
        } finally {
            for (int i = 0; i < LIMIT; i++) {
                admission.release();
            }
        }
        assertEquals(0, admission.inFlight());
    }

    @Test
    @Order(2)
    void withinLimitAcceptsEverythingWithZeroRejections() throws Exception {
        double rejectedBefore = rejectedCount();
        long journalBefore = count("SELECT count(*) FROM command_journal");
        int orders = 12;

        // 동시성을 상한과 같게 둔다. permit이 요청 수명과 정확히 짝을 이루면 초과가 발생할 수 없다.
        ExecutorService pool = Executors.newFixedThreadPool(LIMIT);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < orders; i++) {
                String clientOrderId = "admission-pass-" + i;
                Callable<ResponseEntity<String>> task = () -> placeOrder(clientOrderId);
                futures.add(pool.submit(task));
            }
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertTrue(response.getStatusCode().is2xxSuccessful(),
                        "상한 이하 부하에서 주문이 거절됐다: " + response.getStatusCode()
                                + " " + response.getBody());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(rejectedBefore, rejectedCount(), "상한 이하인데 수용 거절이 발생했다");
        assertEquals(journalBefore + orders, count("SELECT count(*) FROM command_journal"),
                "접수된 주문이 저널에 전량 남지 않았다");
        assertEquals(0, admission.inFlight(), "요청 종료 후 permit이 남아 있다");
    }

    /**
     * 누수 검증. permit 반납은 서로 다른 반환 지점 넷을 모두 지나야 한다. 인증 거부는 필터가 직접
     * 렌더링하고 조기 반환하며, 바디 거부·부재 취소는 필터를 통과해 컨트롤러에서 던져진다. 어느
     * 하나라도 새면 허용량이 단조 감소해 결국 전 주문이 429가 된다. 그것은 백프레셔가 아니라 정지다.
     */
    @Test
    @Order(3)
    void permitsRecoverAfterRepeatedFailurePaths() {
        // 각 반환 지점의 계약 코드를 한 번씩 확정한다.
        assertEquals(401, badSignature().getStatusCode().value());
        assertEquals(401, noAuthHeader().getStatusCode().value());
        assertEquals(400, incompleteBody().getStatusCode().value());
        assertEquals(404, cancelMissing().getStatusCode().value());

        // 반복 구간은 코드를 고정하지 않는다. 계정 토큰버킷(§2-5)이 도중에 429로 전환되는데,
        // 그 429도 필터를 통과한 뒤 컨트롤러에서 나오므로 반납 경로 검증에는 오히려 유효하다.
        for (int i = 0; i < 20; i++) {
            assertTrue(badSignature().getStatusCode().is4xxClientError());
            assertTrue(noAuthHeader().getStatusCode().is4xxClientError());
            assertTrue(incompleteBody().getStatusCode().is4xxClientError());
            assertTrue(cancelMissing().getStatusCode().is4xxClientError());
        }

        assertEquals(0, admission.inFlight(), "실패 경로에서 permit이 샜다");
        List<Boolean> taken = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            taken.add(admission.tryAcquire());
        }
        for (int i = 0; i < LIMIT; i++) {
            admission.release();
        }
        assertTrue(taken.stream().allMatch(Boolean::booleanValue), "허용량이 전량 회복되지 않았다");
    }

    /** 조회 경로 비영향. 수용 제한이 조회까지 막으면 백프레셔가 아니라 서비스 정지다. */
    @Test
    @Order(4)
    void queryPathsAreNotAdmissionLimited() {
        List<Boolean> taken = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            taken.add(admission.tryAcquire());
        }
        try {
            assertTrue(taken.stream().allMatch(Boolean::booleanValue));
            assertEquals(200, rest.getForEntity("/api/v1/markets", String.class)
                    .getStatusCode().value(), "공개 시장 조회가 수용 제한에 걸렸다");
            assertEquals(200, rest.getForEntity("/actuator/health", String.class)
                    .getStatusCode().value(), "헬스 조회가 수용 제한에 걸렸다");

            String path = "/api/v1/orders/open";
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", TestSigner.bearerForQuery(
                    tradeKey.accessKey(), tradeKey.secretKey(), "GET", path, java.util.Map.of()));
            assertEquals(200, rest.exchange(path, HttpMethod.GET,
                            new HttpEntity<>(null, headers), String.class).getStatusCode().value(),
                    "주문 조회(GET)가 수용 제한에 걸렸다");
        } finally {
            for (int i = 0; i < LIMIT; i++) {
                admission.release();
            }
        }
    }

    /**
     * Prometheus 노출명 고정(하네스 CICD §5-2). Micrometer 점 표기와 스크레이프명이 어긋나면
     * 게이트가 없는 시계열을 조회해 공허 통과한다.
     */
    @Test
    @Order(5)
    void prometheusExposesContractedMeterNames() {
        ResponseEntity<String> scrape = rest.getForEntity("/actuator/prometheus", String.class);
        assertEquals(200, scrape.getStatusCode().value());
        String body = scrape.getBody();
        assertNotNull(body);
        assertTrue(body.contains("exchange_admission_rejected_total"),
                "거절 카운터 노출명이 §5-2와 다르다");
        assertTrue(body.contains("exchange_admission_in_flight"),
                "인플라이트 게이지 노출명이 §5-2와 다르다");
        assertTrue(body.contains("exchange_admission_limit"),
                "상한 게이지 노출명이 §5-2와 다르다");
    }

    /** 서명 불일치. ApiAuthFilter가 인증 단계에서 렌더링 후 조기 반환한다. */
    private ResponseEntity<String> badSignature() {
        String path = "/api/v1/orders";
        byte[] body = orderBody("leak-probe").getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = jsonHeaders();
        headers.add("Authorization", TestSigner.bearerForBody(
                tradeKey.accessKey(), "wrong-secret", "POST", path, body));
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** 인증 헤더 부재. 같은 반환 지점의 다른 사유. */
    private ResponseEntity<String> noAuthHeader() {
        byte[] body = orderBody("leak-probe").getBytes(StandardCharsets.UTF_8);
        return rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    /** 필수 필드 누락(client_order_id). 필터를 통과해 게이트웨이에서 던져진다. */
    private ResponseEntity<String> incompleteBody() {
        String path = "/api/v1/orders";
        byte[] body = "{\"market\":\"KRW-BTC\"}".getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = jsonHeaders();
        headers.add("Authorization", TestSigner.bearerForBody(
                tradeKey.accessKey(), tradeKey.secretKey(), "POST", path, body));
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** 존재하지 않는 주문 취소. DELETE도 수용 제한 대상이다. */
    private ResponseEntity<String> cancelMissing() {
        String path = "/api/v1/orders/" + java.util.UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", TestSigner.bearerForQuery(
                tradeKey.accessKey(), tradeKey.secretKey(), "DELETE", path, java.util.Map.of()));
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(null, headers), String.class);
    }

    private ResponseEntity<String> placeOrder(String clientOrderId) {
        String path = "/api/v1/orders";
        byte[] body = orderBody(clientOrderId).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = jsonHeaders();
        headers.add("Authorization", TestSigner.bearerForBody(
                tradeKey.accessKey(), tradeKey.secretKey(), "POST", path, body));
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String orderBody(String clientOrderId) {
        return "{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\","
                + "\"price\":\"84000000\",\"volume\":\"0.001\","
                + "\"client_order_id\":\"" + clientOrderId + "\"}";
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        return headers;
    }

    private double rejectedCount() {
        return meterRegistry.get("exchange.admission.rejected").counter().count();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

package kr.exchange.api;

import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionLimits;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수용 제한의 계약 고정(API 설계 §2-5-a). 쓰기·조회 두 버킷과 면제 경로를 함께 본다.
 *
 * 증명 대상.
 *  (1) 두 버킷 각각 상한 초과 시 429 + Retry-After + scope 구분. 쓰기 거절은 저널·원장·수요 축에
 *      흔적이 없다. 흔적이 남으면 '거절했는데 접수됨'이라 RPO0 계약의 반대편이 된다.
 *  (2) 두 버킷이 모두 포화여도 생존 신호(actuator readiness·health·prometheus)와 하네스 status,
 *      그리고 §7-6 재조회(by-client)는 200을 유지한다. 이것이 설계의 핵심 제약이다. 라이브 1차
 *      실패가 정확히 여기였다. 쓰기만 유계화하니 남은 커넥션을 조회가 전부 먹고 노드가 죽었다.
 *  (3) 두 버킷은 서로를 굶기지 않는다. 한 버킷으로 합치면 쓰기가 조회에 밀리거나 그 반대가 된다.
 *  (4) 실패 경로를 반복해도 허용량이 전량 회복된다. permit 누수는 서서히 전면 정지로 수렴한다.
 *
 * 상한을 2/2로 고정해 고갈을 결정적으로 만든다. 실 배포는 요청 풀에서 유도한다(풀 32 -> 12/12).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// 테스트 컨텍스트는 기본적으로 메트릭 익스포트를 끈다(management.defaults.metrics.export.enabled).
// 그러면 레지스트리가 SimpleMeterRegistry로 대체돼 Prometheus 노출명을 검증할 수 없고, 이름 불일치가
// 그대로 배포까지 흘러 게이트가 없는 시계열을 조회하게 된다. 여기서만 배포와 같은 익스포트를 켠다.
@AutoConfigureObservability(tracing = false)
class AdmissionIntegrationTest {

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
        registry.add("exchange.admission.query-in-flight", () -> String.valueOf(LIMIT));
        registry.add("exchange.admission.requery-in-flight", () -> String.valueOf(LIMIT));
        // 하네스 status 면제를 실제 엔드포인트로 검증하려면 하네스 평면이 떠 있어야 한다.
        registry.add("exchange.harness.enabled", () -> "true");
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
    @Qualifier("orderAdmissionControl")
    AdmissionControl orderAdmission;
    @Autowired
    @Qualifier("queryAdmissionControl")
    AdmissionControl queryAdmission;
    @Autowired
    @Qualifier("requeryAdmissionControl")
    AdmissionControl requeryAdmission;
    @Autowired
    AdmissionLimits limits;
    @Autowired
    LeaderWriteProgress writeProgress;
    @Autowired
    MeterRegistry meterRegistry;

    DemoKeySeeder.IssuedKey tradeKey;

    @BeforeAll
    void seed() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        long trader = bootstrap.user("admission@api.test");
        bootstrap.userAccounts(trader, KRW);
        bootstrap.userAccounts(trader, BTC);
        bootstrap.deposit(trader, KRW, new BigDecimal("1000000000"));
        tradeKey = keySeeder.issue(trader, "TRADE", "admission-trade");
        // 접수는 리더 확정 후에만 열린다. 승격 전에는 503이라 수용 판정 대조가 무의미하다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.sql("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                                + "AND lease_until > now()")
                        .query(Long.class).single() > 0);
        // readiness는 승격 완료 뒤에 ACCEPTING_TRAFFIC으로 올라간다. 면제 검증의 전제다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() ->
                rest.getForEntity("/actuator/health/readiness", String.class)
                        .getStatusCode().value() == 200);
    }

    @Test
    @Order(1)
    void orderBucketRejectsBeforeAcceptanceWithoutJournalOrLedgerTrace() {
        assertEquals(LIMIT, orderAdmission.limit(), "쓰기 상한 고정이 적용되지 않았다");
        String clientOrderId = "admission-shed-1";

        // 상한만큼 permit을 선점해 다음 요청이 반드시 초과가 되게 한다. 실 카오스에서는
        // 느린 DB에 붙들린 요청들이 이 자리를 차지한다.
        saturate(orderAdmission);
        try {
            long journalBefore = count("SELECT count(*) FROM command_journal");
            long ledgerBefore = count("SELECT count(*) FROM ledger_entries");
            long attemptsBefore = writeProgress.attempts();
            double rejectedBefore = rejectedCount("order");

            long startNanos = System.nanoTime();
            ResponseEntity<String> response = placeOrder(clientOrderId);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            assertRejected(response, "order");
            // 대기열 없음. 거절이 지연되면 그 자체로 스레드가 쌓인다.
            assertTrue(elapsedMs < 1000, "거절이 즉시 반환되지 않았다: " + elapsedMs + "ms");

            // 접수 이전 거절의 정의: 저널·원장 어디에도 흔적이 없다.
            assertEquals(0, count("SELECT count(*) FROM command_journal WHERE client_order_id = '"
                    + clientOrderId + "'"), "거절된 주문이 저널에 남았다");
            assertEquals(0, count("SELECT count(*) FROM orders WHERE client_order_id = '"
                    + clientOrderId + "'"), "거절된 주문이 프로젝션에 남았다");
            assertEquals(journalBefore, count("SELECT count(*) FROM command_journal"),
                    "거절 요청이 저널을 늘렸다");
            assertEquals(ledgerBefore, count("SELECT count(*) FROM ledger_entries"),
                    "거절 요청이 원장을 늘렸다");

            // 거절은 쓰기 수요가 아니다. 수요로 세면 부하를 흘리는 건강한 노드의 완료율이
            // 임계 아래로 내려가 헛강등한다(DB 설계 §5.4).
            assertEquals(attemptsBefore, writeProgress.attempts(),
                    "거절이 좀비 판정 분모를 오염시켰다");
            assertEquals(rejectedBefore + 1, rejectedCount("order"), "거절 카운터가 오르지 않았다");
        } finally {
            drain(orderAdmission);
        }
        assertEquals(0, orderAdmission.inFlight());
    }

    /** 라이브 1차 실패의 교정 지점. 조회가 무제한이면 쓰기를 아무리 잘라도 노드가 죽는다. */
    @Test
    @Order(2)
    void queryBucketRejectsPublicMarketReadsBeyondLimit() {
        assertEquals(LIMIT, queryAdmission.limit(), "조회 상한 고정이 적용되지 않았다");
        saturate(queryAdmission);
        try {
            double rejectedBefore = rejectedCount("query");
            // 공개 시장 데이터는 인증 필터의 제외 목록이라, 인증 필터 안에 게이트를 두면
            // 정작 노드를 죽인 이 요청이 게이트 밖에 있게 된다.
            assertRejected(rest.getForEntity("/api/v1/orderbook?market=KRW-BTC", String.class),
                    "query");
            assertRejected(rest.getForEntity("/api/v1/ticker?markets=KRW-BTC", String.class),
                    "query");
            assertEquals(rejectedBefore + 2, rejectedCount("query"));

            // 인증 조회도 같은 버킷이다.
            String path = "/api/v1/orders/open";
            assertRejected(rest.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(null, signedQueryHeaders("GET", path)), String.class), "query");
        } finally {
            drain(queryAdmission);
        }
        assertEquals(0, queryAdmission.inFlight());
    }

    /**
     * 설계의 핵심 제약. 두 버킷이 동시에 포화여도 노드의 생존 신호와 §7-6 수렴 경로는 살아야 한다.
     * 노드가 살아 있다는 것은 곧 이 질문들에 답할 수 있다는 뜻이고, LB·docker healthcheck·하네스가
     * 전부 그 응답으로 판단한다.
     */
    @Test
    @Order(3)
    void survivalSignalsAndRequeryStayOpenWhenBothBucketsAreFull() {
        String clientOrderId = "admission-requery-1";
        assertTrue(placeOrder(clientOrderId).getStatusCode().is2xxSuccessful(),
                "재조회 대상 주문 접수에 실패했다");

        saturate(orderAdmission);
        saturate(queryAdmission);
        try {
            for (String path : new String[]{
                    "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness",
                    "/actuator/prometheus", "/harness/v1/status"}) {
                assertEquals(200, rest.getForEntity(path, String.class).getStatusCode().value(),
                        path + "가 수용 제한에 막혔다. 노드 생존 판정이 무너진다");
            }

            // §7-6 재조회. 쓰기·조회가 전부 포화여도 전용 버킷이라 정상 판정을 낸다. 여기가
            // 조회 버킷에 묶여 있었다면 429가 나가고 클라이언트는 접수 여부를 확정하지 못한 채
            // 안전 재전송과 이중 접수 중 하나를 골라야 한다. 멱등 규약이 깨지는 자리다.
            assertEquals(200, requery(clientOrderId).getStatusCode().value(),
                    "재조회 수렴 경로가 다른 버킷의 포화에 막혔다");
        } finally {
            drain(orderAdmission);
            drain(queryAdmission);
        }
    }

    @Test
    @Order(4)
    void bucketsDoNotStarveEachOther() {
        saturate(orderAdmission);
        try {
            assertEquals(200, rest.getForEntity("/api/v1/orderbook?market=KRW-BTC", String.class)
                    .getStatusCode().value(), "쓰기 포화가 조회를 굶겼다");
        } finally {
            drain(orderAdmission);
        }
        saturate(queryAdmission);
        try {
            assertTrue(placeOrder("admission-cross-1").getStatusCode().is2xxSuccessful(),
                    "조회 포화가 쓰기를 굶겼다");
        } finally {
            drain(queryAdmission);
        }
    }

    /**
     * 성질 (a): UNKNOWN 클라이언트는 접수 여부를 확정할 수 있어야 한다.
     *
     * 재조회 버킷이 포화면 429가 아니라 409 `requery_deferred` + Retry-After가 나간다. 429·503은
     * k6의 안전 집합(200·404·409) 밖이라 백프레셔가 곧 멱등 규약 위반으로 계상되고, 클라이언트는
     * 안전 재전송과 이중 접수 중 하나를 골라야 한다. 409는 비종결이라 재조회 반복으로 수렴하고,
     * 슬롯이 풀리면 확정 판정(200 접수됨 / 404 미접수)이 나온다. 그 수렴을 여기서 고정한다.
     */
    @Test
    @Order(5)
    void requeryDefersJudgementUnderPressureThenConverges() {
        String accepted = "admission-converge-1";
        assertTrue(placeOrder(accepted).getStatusCode().is2xxSuccessful());
        String neverSent = "admission-converge-absent";

        saturate(requeryAdmission);
        ResponseEntity<String> deferred;
        ResponseEntity<String> deferredAbsent;
        try {
            deferred = requery(accepted);
            deferredAbsent = requery(neverSent);
        } finally {
            drain(requeryAdmission);
        }

        for (ResponseEntity<String> response : List.of(deferred, deferredAbsent)) {
            assertEquals(409, response.getStatusCode().value(),
                    "재조회 보류가 k6 안전 집합(200·404·409) 밖으로 나갔다: " + response.getBody());
            assertNotNull(response.getHeaders().getFirst("Retry-After"),
                    "보류에 재조회 주기가 없다. 클라이언트가 언제 다시 물을지 알 수 없다");
            String body = response.getBody();
            assertNotNull(body);
            assertTrue(body.contains("\"requery_deferred\""),
                    "보류를 order_in_progress로 내보내면 저널 존재를 거짓 주장하게 된다: " + body);
            assertTrue(body.contains("\"scope\":\"requery\""), body);
        }

        // 슬롯이 풀린 뒤에는 확정 판정이 나온다. 접수된 키는 200, 보낸 적 없는 키는 404다.
        // 404가 곧 '안전 재전송 가능'이므로 이 수렴이 성립해야 멱등 규약이 닫힌다(§7-6).
        assertEquals(200, requery(accepted).getStatusCode().value(),
                "보류 후에도 접수 확정을 받지 못했다");
        assertEquals(404, requery(neverSent).getStatusCode().value(),
                "보류 후에도 미접수 확정을 받지 못했다");
    }

    /**
     * 성질 (b): by-client만으로 노드를 고갈시킬 수 없다.
     *
     * 이 경로는 하필 노드가 굶는 순간에 몰린다. 커넥션 획득 3초 초과가 503을 만들고 클라이언트는
     * 5xx에 재조회로 반응한다(k6 order_flow.js). 완전 면제였다면 그 되먹임이 동시 스레드 수에
     * 상한 없이 쌓인다. 전용 버킷이 그 고리를 끊는지, 그리고 그 압력이 다른 버킷으로 새지 않는지를 본다.
     */
    @Test
    @Order(6)
    void requeryFloodStaysBoundedAndDoesNotStarveOtherBuckets() throws Exception {
        int flood = 60;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < flood; i++) {
                futures.add(pool.submit(() -> requery("admission-converge-1")));
            }
            for (Future<ResponseEntity<String>> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS).getStatusCode().value();
                // 200(확정) 또는 409(보류)뿐이어야 한다. 429·5xx는 수렴 계약 위반이다.
                assertTrue(status == 200 || status == 409,
                        "재조회 폭주가 수렴 불가 응답을 냈다: " + status);
            }
        } finally {
            pool.shutdownNow();
        }

        // 동시성이 전용 버킷 상한을 넘지 않았다. 넘었다면 슬롯 회계가 깨진 것이다.
        assertEquals(0, requeryAdmission.inFlight(), "재조회 폭주 후 permit이 남아 있다");
        assertTrue(requeryAdmission.limit() <= LIMIT);
        // 압력이 다른 버킷으로 새지 않았다. 쓰기·조회는 그대로 열려 있어야 한다.
        assertEquals(0, orderAdmission.inFlight());
        assertEquals(0, queryAdmission.inFlight());
        assertTrue(placeOrder("admission-after-flood").getStatusCode().is2xxSuccessful(),
                "재조회 폭주가 쓰기를 굶겼다");
        assertEquals(200, rest.getForEntity("/api/v1/orderbook?market=KRW-BTC", String.class)
                .getStatusCode().value(), "재조회 폭주가 조회를 굶겼다");
    }

    @Test
    @Order(7)
    void withinLimitAcceptsEverythingWithZeroRejections() throws Exception {
        double rejectedBefore = rejectedCount("order");
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

        assertEquals(rejectedBefore, rejectedCount("order"), "상한 이하인데 수용 거절이 발생했다");
        assertEquals(journalBefore + orders, count("SELECT count(*) FROM command_journal"),
                "접수된 주문이 저널에 전량 남지 않았다");
        assertEquals(0, orderAdmission.inFlight(), "요청 종료 후 permit이 남아 있다");
    }

    /**
     * 누수 검증. permit 반납은 서로 다른 반환 지점 넷을 모두 지나야 한다. 인증 거부는 인증 필터가
     * 직접 렌더링하고 조기 반환하며, 바디 거부·부재 취소는 컨트롤러에서 던져진다. 어느 하나라도
     * 새면 허용량이 단조 감소해 결국 전 요청이 429가 된다. 그것은 백프레셔가 아니라 정지다.
     */
    @Test
    @Order(8)
    void permitsRecoverAfterRepeatedFailurePaths() {
        // 각 반환 지점의 계약 코드를 한 번씩 확정한다.
        assertEquals(401, badSignature().getStatusCode().value());
        assertEquals(401, noAuthHeader().getStatusCode().value());
        assertEquals(400, incompleteBody().getStatusCode().value());
        assertEquals(404, cancelMissing().getStatusCode().value());
        assertEquals(400, badQueryParameter().getStatusCode().value());
        assertEquals(404, requery("admission-never-sent").getStatusCode().value());

        // 반복 구간은 코드를 고정하지 않는다. 계정 토큰버킷(§2-5)이 도중에 429로 전환되는데,
        // 그 429도 수용 게이트를 통과한 뒤 컨트롤러에서 나오므로 반납 경로 검증에는 오히려 유효하다.
        for (int i = 0; i < 20; i++) {
            assertTrue(badSignature().getStatusCode().is4xxClientError());
            assertTrue(noAuthHeader().getStatusCode().is4xxClientError());
            assertTrue(incompleteBody().getStatusCode().is4xxClientError());
            assertTrue(cancelMissing().getStatusCode().is4xxClientError());
            assertTrue(badQueryParameter().getStatusCode().is4xxClientError());
            assertTrue(requery("admission-never-sent").getStatusCode().is4xxClientError());
        }

        assertEquals(0, orderAdmission.inFlight(), "쓰기 실패 경로에서 permit이 샜다");
        assertEquals(0, queryAdmission.inFlight(), "조회 실패 경로에서 permit이 샜다");
        assertEquals(0, requeryAdmission.inFlight(), "재조회 실패 경로에서 permit이 샜다");
        saturate(orderAdmission);
        saturate(queryAdmission);
        saturate(requeryAdmission);
        drain(orderAdmission);
        drain(queryAdmission);
        drain(requeryAdmission);
    }

    /**
     * Prometheus 노출명 고정(하네스 CICD §5-2). Micrometer 점 표기와 스크레이프명이 어긋나면
     * 게이트가 없는 시계열을 조회해 공허 통과한다. scope 태그까지 함께 고정한다.
     *
     * 수용 제한 축만이 아니라 §5-2 노출명 계약 전반의 단일 고정점이다. 익스포트를 켠 컨텍스트가
     * 여기뿐이라 검증을 다른 클래스로 나누면 컨텍스트와 Testcontainers 컨테이너가 하나 더 뜨고
     * (테스트 클래스마다 postgres 1대) 전체 스위트가 자원 한계에서 흔들린다.
     */
    @Test
    @Order(9)
    void prometheusExposesContractedMeterNames() {
        ResponseEntity<String> scrape = rest.getForEntity("/actuator/prometheus", String.class);
        assertEquals(200, scrape.getStatusCode().value());
        String body = scrape.getBody();
        assertNotNull(body);
        for (String scope : new String[]{"order", "query", "requery"}) {
            assertTrue(body.contains("exchange_admission_rejected_total{scope=\"" + scope + "\"}"),
                    "거절 카운터 노출명·태그가 §5-2와 다르다: " + scope);
            assertTrue(body.contains("exchange_admission_in_flight{scope=\"" + scope + "\"}"),
                    "인플라이트 게이지 노출명·태그가 §5-2와 다르다: " + scope);
            assertTrue(body.contains("exchange_admission_limit{scope=\"" + scope + "\"}"),
                    "상한 게이지 노출명·태그가 §5-2와 다르다: " + scope);
        }
        // 리더십 축. 강등 두 등급은 별도 시계열이어야 한다. 합치면 [재생 실패 -> 반납 ->
        // 재승격] 반복이 자발적 강등 1회와 구분되지 않는다(DB 설계 §5.4).
        for (String exposed : new String[]{
                "exchange_leader_recoverable_standdown_total",
                "exchange_leader_standdown_total",
                "exchange_node_serviceable",
                "exchange_node_write_completion_ratio",
                "exchange_leader_epoch",
                "exchange_leader_is_leader",
                "exchange_failover_downtime_seconds"}) {
            assertTrue(body.contains(exposed),
                    "§5-2 명세표의 노출명이 스크레이프 본문에 없다: " + exposed);
        }
    }

    /** 유도 배선이 실제 컨텍스트에서 성립하는지. 상한을 명시한 컨텍스트라 배분 규칙 자체는 단위테스트가 본다. */
    @Test
    @Order(10)
    void limitsBeanBacksEveryBucket() {
        assertEquals(limits.write(), orderAdmission.limit());
        assertEquals(limits.query(), queryAdmission.limit());
        assertEquals(limits.requery(), requeryAdmission.limit());
    }

    private void saturate(AdmissionControl admission) {
        for (int i = 0; i < admission.limit(); i++) {
            assertTrue(admission.tryAcquire(), "선점에 실패했다: " + admission.scope());
        }
    }

    private void drain(AdmissionControl admission) {
        for (int i = 0; i < admission.limit(); i++) {
            admission.release();
        }
    }

    private static void assertRejected(ResponseEntity<String> response, String scope) {
        assertEquals(429, response.getStatusCode().value(),
                "수용 초과가 429로 거절되지 않았다: " + response.getBody());
        String retryAfter = response.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "Retry-After가 없다. 클라이언트 백오프 계약(§2-3·§2-5) 위반");
        assertTrue(Integer.parseInt(retryAfter) >= 1);
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"too_many_requests\""), "에러코드가 계약과 다르다: " + body);
        assertTrue(body.contains("\"bucket\":\"node\""), "위반 버킷 표기가 없다: " + body);
        assertTrue(body.contains("\"scope\":\"" + scope + "\""), "버킷 scope 표기가 다르다: " + body);
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

    /** 필수 필드 누락(client_order_id). 인증을 통과해 게이트웨이에서 던져진다. */
    private ResponseEntity<String> incompleteBody() {
        String path = "/api/v1/orders";
        byte[] body = "{\"market\":\"KRW-BTC\"}".getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = jsonHeaders();
        headers.add("Authorization", TestSigner.bearerForBody(
                tradeKey.accessKey(), tradeKey.secretKey(), "POST", path, body));
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** 존재하지 않는 주문 취소. DELETE도 쓰기 버킷 대상이다. */
    private ResponseEntity<String> cancelMissing() {
        String path = "/api/v1/orders/" + UUID.randomUUID();
        return rest.exchange(path, HttpMethod.DELETE,
                new HttpEntity<>(null, signedQueryHeaders("DELETE", path)), String.class);
    }

    /** 조회 버킷의 실패 경로. 파라미터 타입 오류는 컨트롤러 어드바이스가 400으로 정규화한다. */
    private ResponseEntity<String> badQueryParameter() {
        return rest.getForEntity("/api/v1/candles/1m?market=KRW-BTC&count=notanumber",
                String.class);
    }

    /** §7-6 재조회. 전용 버킷을 지난다. */
    private ResponseEntity<String> requery(String clientOrderId) {
        String path = "/api/v1/orders/by-client/" + clientOrderId;
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(null, signedQueryHeaders("GET", path)), String.class);
    }

    private ResponseEntity<String> placeOrder(String clientOrderId) {
        String path = "/api/v1/orders";
        byte[] body = orderBody(clientOrderId).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = jsonHeaders();
        headers.add("Authorization", TestSigner.bearerForBody(
                tradeKey.accessKey(), tradeKey.secretKey(), "POST", path, body));
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders signedQueryHeaders(String method, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", TestSigner.bearerForQuery(
                tradeKey.accessKey(), tradeKey.secretKey(), method, path, Map.of()));
        return headers;
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

    private double rejectedCount(String scope) {
        return meterRegistry.get("exchange.admission.rejected")
                .tag("scope", scope).counter().count();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

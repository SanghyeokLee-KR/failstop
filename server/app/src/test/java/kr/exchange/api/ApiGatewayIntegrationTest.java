package kr.exchange.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.common.Cursors;
import kr.exchange.ledger.AccountBootstrap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-4 통합 검증(웹 화면명세 §6.2 수용 기준 축):
 * (a) 서명 왕복과 변조·재전송·시계창 401, (b) 주문 E2E(체결·조회·비공개 채널 수신),
 * (c) client_order_id 멱등 replay와 내용 상이 409, (d) WS 구독 즉시 스냅샷·seq 단조,
 * (e) 게이트웨이 사전 가드(잔고·최소 주문, 저널 미기록).
 * 저널·시퀀스가 전역 공유 자원이라 순서를 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayIntegrationTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int ETH = 3;
    private static final int KRW_BTC = 1;
    private static final int KRW_ETH = 2;

    // alice의 bid 0.01@50,000,000 체결 결과. 지출 500,000 KRW, 수취 순액 0.009995 BTC
    // (taker 수수료 0.000005 BTC 차감). 500000 / 0.009995 = 50025012.5062... 을 KRW scale 0으로
    // HALF_UP 반올림한 값이다. 부분 매도 뒤에도 이 값이 유지되는 것이 원가 산식의 요지다.
    private static final String AVG_AFTER_BUY = "50025013";

    // PER_CLASS 수명주기에서는 테스트 인스턴스 생성(=스프링 컨텍스트 기동)이 @Testcontainers
    // 확장 beforeAll보다 앞서므로 static 초기화로 컨테이너를 먼저 띄운다. 정리는 ryuk 몫이다.
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
    ObjectMapper objectMapper;
    @Autowired
    org.springframework.context.ApplicationContext context;
    @LocalServerPort
    int port;

    long alice;
    long bob;
    long carol;
    DemoKeySeeder.IssuedKey aliceKey;
    DemoKeySeeder.IssuedKey bobKey;
    DemoKeySeeder.IssuedKey carolKey;
    DemoKeySeeder.IssuedKey aliceReadKey;

    StompSession wsSession;
    final ConcurrentLinkedQueue<JsonNode> orderbookMessages = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<JsonNode> tradeMessages = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<JsonNode> myOrderMessages = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<JsonNode> myAssetMessages = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<JsonNode> candleMessages = new ConcurrentLinkedQueue<>();

    String buyOrderId;

    @BeforeAll
    void seed() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        // 최소 주문 미달 가드 검증용 하한. AccountBootstrap 기본값은 0이라 케이스가 성립하지 않는다.
        jdbc.sql("UPDATE markets SET min_notional = 5000 WHERE id = ?").params(KRW_BTC).update();
        alice = bootstrap.user("alice@api.test");
        bob = bootstrap.user("bob@api.test");
        carol = bootstrap.user("carol@api.test");
        for (long uid : new long[]{alice, bob, carol}) {
            bootstrap.userAccounts(uid, KRW);
            bootstrap.userAccounts(uid, BTC);
        }
        bootstrap.deposit(alice, KRW, new BigDecimal("1000000000"));
        bootstrap.deposit(alice, BTC, new BigDecimal("100"));
        bootstrap.deposit(bob, KRW, new BigDecimal("1000000000"));
        bootstrap.deposit(bob, BTC, new BigDecimal("100"));
        // carol은 무잔고. 잔고 사전 가드 케이스 전용.
        aliceKey = keySeeder.issue(alice, "TRADE", "alice-trade");
        bobKey = keySeeder.issue(bob, "TRADE", "bob-trade");
        carolKey = keySeeder.issue(carol, "TRADE", "carol-trade");
        aliceReadKey = keySeeder.issue(alice, "READ", "alice-read");
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                jdbc.sql("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                                + "AND lease_until > now()")
                        .query(Long.class).single() > 0);
    }

    @AfterAll
    void closeWs() {
        if (wsSession != null && wsSession.isConnected()) {
            wsSession.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // (a) 서명 왕복
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void signatureRoundtripAndTamperRejection() {
        // 정상 서명 통과
        ResponseEntity<String> ok = get("/api/v1/accounts",
                TestSigner.bearerForQuery(aliceKey.accessKey(), aliceKey.secretKey(),
                        "GET", "/api/v1/accounts", Map.of()));
        assertEquals(200, ok.getStatusCode().value());
        assertTrue(ok.getBody().contains("\"schema_version\":\"1.0\""));
        // §2-5 계좌 조회 버킷 배선(item 3): /accounts도 계정 조회 토큰버킷 헤더를 방출한다.
        assertNotNull(ok.getHeaders().getFirst("X-RateLimit-Limit-account"),
                "/accounts는 계정 조회 레이트리밋 헤더를 방출해야 한다");

        // 다른 path로 서명한 토큰(엔드포인트 치환 리플레이) → invalid_query_payload
        ResponseEntity<String> tampered = get("/api/v1/accounts",
                TestSigner.bearerForQuery(aliceKey.accessKey(), aliceKey.secretKey(),
                        "GET", "/api/v1/orders/open", Map.of()));
        assertEquals(401, tampered.getStatusCode().value());
        assertEquals("invalid_query_payload", errorName(tampered));

        // 바디 변조: 서명한 바디와 다른 바디 전송 → invalid_query_payload
        byte[] signedBody = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.01", "tamper-1");
        byte[] sentBody = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.02", "tamper-1");
        ResponseEntity<String> bodyTampered = post("/api/v1/orders", sentBody,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", signedBody));
        assertEquals(401, bodyTampered.getStatusCode().value());
        assertEquals("invalid_query_payload", errorName(bodyTampered));

        // 잘못된 secret → jwt_verification
        ResponseEntity<String> wrongSecret = get("/api/v1/accounts",
                TestSigner.bearerForQuery(aliceKey.accessKey(), "wrong-secret",
                        "GET", "/api/v1/accounts", Map.of()));
        assertEquals(401, wrongSecret.getStatusCode().value());
        assertEquals("jwt_verification", errorName(wrongSecret));

        // nonce 재사용 → 첫 요청만 통과
        String reused = TestSigner.bearerForQuery(aliceKey.accessKey(), aliceKey.secretKey(),
                "GET", "/api/v1/accounts", Map.of());
        assertEquals(200, get("/api/v1/accounts", reused).getStatusCode().value());
        ResponseEntity<String> replayed = get("/api/v1/accounts", reused);
        assertEquals(401, replayed.getStatusCode().value());
        assertEquals("nonce_used", errorName(replayed));

        // timestamp 허용창 밖(recvWindow 5000ms) → timestamp_out_of_window
        ResponseEntity<String> stale = get("/api/v1/accounts",
                TestSigner.bearerWith(aliceKey.accessKey(), aliceKey.secretKey(),
                        TestSigner.queryHash("GET", "/api/v1/accounts", Map.of()),
                        System.currentTimeMillis() - 10_000, UUID.randomUUID().toString()));
        assertEquals(401, stale.getStatusCode().value());
        assertEquals("timestamp_out_of_window", errorName(stale));

        // 조회 스코프 키로 주문 → out_of_scope
        byte[] body = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.01", "scope-1");
        ResponseEntity<String> outOfScope = post("/api/v1/orders", body,
                TestSigner.bearerForBody(aliceReadKey.accessKey(), aliceReadKey.secretKey(),
                        "POST", "/api/v1/orders", body));
        assertEquals(403, outOfScope.getStatusCode().value());
        assertEquals("out_of_scope", errorName(outOfScope));
    }

    // ------------------------------------------------------------------
    // (d) WS 구독 즉시 스냅샷 (구독을 주문보다 먼저 열어 realtime 순서를 관찰한다)
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void wsSubscribeDeliversSnapshotFirst() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter());
        client.setDefaultHeartbeat(new long[]{0, 0});
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", TestSigner.bearerWith(
                aliceKey.accessKey(), aliceKey.secretKey(), TestSigner.wsQueryHash(),
                System.currentTimeMillis(), UUID.randomUUID().toString()));
        wsSession = client.connectAsync("ws://localhost:" + port + "/ws/v1",
                        new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(15, TimeUnit.SECONDS);

        subscribe(wsSession, "/topic/orderbook.KRW-BTC", orderbookMessages);
        subscribe(wsSession, "/topic/trade.KRW-BTC", tradeMessages);
        subscribe(wsSession, "/topic/candle.1m.KRW-BTC", candleMessages);
        subscribe(wsSession, "/user/queue/myOrder", myOrderMessages);
        subscribe(wsSession, "/user/queue/myAsset", myAssetMessages);

        // 구독 즉시 스냅샷 1건(orderbook 채널 한정, §5-2 v2.4)
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> !orderbookMessages.isEmpty());
        JsonNode first = orderbookMessages.peek();
        assertEquals("snapshot", first.path("stream").asText());
        assertEquals("orderbook", first.path("type").asText());
        assertEquals("1.0", first.path("schema_version").asText());
        assertTrue(first.has("epoch"));
        assertTrue(first.has("seq"));
        assertEquals(1, orderbookMessages.size(), "스냅샷은 정확히 1건이어야 한다");
    }

    // ------------------------------------------------------------------
    // (b) 주문 E2E
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    void orderEndToEndWithPrivateChannels() throws Exception {
        // bob 매도 호가 게시
        byte[] sell = orderBody("KRW-BTC", "ask", "limit", "50000000", "0.01", "e2e-sell-1");
        ResponseEntity<String> sellResponse = post("/api/v1/orders", sell,
                TestSigner.bearerForBody(bobKey.accessKey(), bobKey.secretKey(),
                        "POST", "/api/v1/orders", sell));
        assertEquals(201, sellResponse.getStatusCode().value());
        JsonNode sellOrder = objectMapper.readTree(sellResponse.getBody());
        assertEquals("OPEN", sellOrder.path("status").asText());

        // 호가 갱신 realtime 수신(스냅샷 이후, seq 단조)
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> orderbookMessages.stream()
                        .anyMatch(m -> "realtime".equals(m.path("stream").asText())));

        // alice 매수 → 즉시 체결
        byte[] buy = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.01", "e2e-buy-1");
        ResponseEntity<String> buyResponse = post("/api/v1/orders", buy,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", buy));
        assertEquals(201, buyResponse.getStatusCode().value());
        JsonNode buyOrder = objectMapper.readTree(buyResponse.getBody());
        buyOrderId = buyOrder.path("id").asText();
        assertEquals("FILLED", buyOrder.path("status").asText());
        assertEquals("0.01", buyOrder.path("executed_volume").asText());
        assertEquals("500000", buyOrder.path("executed_funds").asText());
        assertEquals("0.000005", buyOrder.path("paid_fee").asText());
        assertEquals(1, buyOrder.path("trades_count").asInt());

        // 공개 trade 채널 수신
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !tradeMessages.isEmpty());
        JsonNode trade = tradeMessages.peek();
        assertEquals("50000000", trade.path("price").asText());
        assertEquals("0.01", trade.path("volume").asText());
        assertEquals("bid", trade.path("taker_side").asText());

        // 공개 candle 채널: 체결로 형성 중 1m 캔들 방출(§5-3, §5-4). 단일 체결이라 OHLC 동일가.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !candleMessages.isEmpty());
        JsonNode candle = candleMessages.peek();
        assertEquals("candle", candle.path("type").asText());
        assertEquals("1m", candle.path("unit").asText());
        assertEquals("KRW-BTC", candle.path("market").asText());
        assertEquals("50000000", candle.path("open").asText());
        assertEquals("50000000", candle.path("high").asText());
        assertEquals("50000000", candle.path("low").asText());
        assertEquals("50000000", candle.path("close").asText());
        assertEquals("0.01", candle.path("volume").asText());
        assertTrue(candle.path("candle_time").isTextual());
        assertTrue(candle.has("seq") && candle.has("epoch"));
        // §5-4 candle 스키마 계약: stream·trade_count 필드 부재가 계약이다.
        assertTrue(candle.path("stream").isMissingNode(), "candle에는 stream 필드 없음(§5-4)");
        assertTrue(candle.path("trade_count").isMissingNode(), "WS candle에는 trade_count 없음(§5-4)");

        // 비공개 myOrder: FILLED + taker 체결 통지
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> myOrderMessages.stream()
                        .anyMatch(m -> m.has("trade") && "FILLED".equals(m.path("status").asText())));
        JsonNode myOrder = myOrderMessages.stream()
                .filter(m -> m.has("trade")).findFirst().orElseThrow();
        assertEquals("TAKER", myOrder.path("trade").path("liquidity_flag").asText());
        assertEquals("0.000005", myOrder.path("trade").path("fee").asText());

        // 비공개 myAsset: 체결 반영 잔고
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !myAssetMessages.isEmpty());
        JsonNode myAsset = myAssetMessages.stream()
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("100.009995", assetField(myAsset, "BTC", "balance"));

        // 원가 필드는 델타에 실려야 한다(§5-5 v2.21 K5). 없으면 클라이언트가 직전 평단가를
        // 보존해 매수마다 낡은 값이 남는다. 취득 원가 500,000 / 순수취 0.009995의 KRW scale
        // HALF_UP 반올림이 50025013이다.
        assertEquals(AVG_AFTER_BUY, assetField(myAsset, "BTC", "avg_buy_price"));
        assertEquals("0.009995", assetField(myAsset, "BTC", "cost_basis_qty"));
        assertEquals("500000", assetField(myAsset, "BTC", "cost_basis_amount"));
        assertEquals("KRW", assetField(myAsset, "BTC", "unit_currency"));
        // quote 자산은 원가 개념이 없다(C25 quote_asset_position).
        assertEquals("0", assetField(myAsset, "KRW", "avg_buy_price"));
        assertEquals("0", assetField(myAsset, "KRW", "cost_basis_qty"));

        // REST 반영: closed 목록·계좌
        ResponseEntity<String> closed = get("/api/v1/orders/closed",
                TestSigner.bearerForQuery(aliceKey.accessKey(), aliceKey.secretKey(),
                        "GET", "/api/v1/orders/closed", Map.of()));
        assertEquals(200, closed.getStatusCode().value());
        JsonNode closedData = objectMapper.readTree(closed.getBody()).path("data");
        assertTrue(streamOf(closedData).anyMatch(o ->
                "e2e-buy-1".equals(o.path("client_order_id").asText())
                        && "FILLED".equals(o.path("status").asText())));

        ResponseEntity<String> accounts = get("/api/v1/accounts",
                TestSigner.bearerForQuery(aliceKey.accessKey(), aliceKey.secretKey(),
                        "GET", "/api/v1/accounts", Map.of()));
        JsonNode accountsData = objectMapper.readTree(accounts.getBody()).path("data");
        assertEquals("999500000", accountField(accountsData, "KRW", "balance"));
        assertEquals("0", accountField(accountsData, "KRW", "locked"));
        assertEquals("100.009995", accountField(accountsData, "BTC", "balance"));

        // §4-C-1 원가 산출. 종전 상수 "0" 스텁이 실측값으로 대체됐다는 회귀 고정이다.
        assertEquals(AVG_AFTER_BUY, accountField(accountsData, "BTC", "avg_buy_price"));
        assertEquals("0.009995", accountField(accountsData, "BTC", "cost_basis_qty"));
        assertEquals("500000", accountField(accountsData, "BTC", "cost_basis_amount"));
        // 원가 미상 수량은 별도 필드가 아니라 유도값이다(§4-C-1 K3). 시드 100 BTC가 전부 미상.
        assertEquals(0, unknownQty(accountsData, "BTC").compareTo(new BigDecimal("100")),
                "원가 미상 유도식이 시드 물량과 어긋난다");
        assertEquals("0", accountField(accountsData, "KRW", "avg_buy_price"));
        assertEquals("0", accountField(accountsData, "KRW", "cost_basis_qty"));
        assertEquals("0", accountField(accountsData, "KRW", "cost_basis_amount"));

        // (d) 채널 seq 단조 검증: orderbook 채널 수신 순서대로 strictly increasing
        List<Long> seqs = new ArrayList<>();
        orderbookMessages.forEach(m -> seqs.add(m.path("seq").asLong()));
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1),
                    "orderbook seq 역전: " + seqs.get(i - 1) + " -> " + seqs.get(i));
        }
    }

    // ------------------------------------------------------------------
    // (c) 멱등
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    void idempotentReplayAndContentConflict() throws Exception {
        // 동일 키·동일 내용 재전송 → 200 + X-Idempotent-Replay + 기존 주문
        byte[] buy = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.01", "e2e-buy-1");
        ResponseEntity<String> replayed = post("/api/v1/orders", buy,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", buy));
        assertEquals(200, replayed.getStatusCode().value());
        assertEquals("true", replayed.getHeaders().getFirst("X-Idempotent-Replay"));
        JsonNode replayedOrder = objectMapper.readTree(replayed.getBody());
        assertEquals(buyOrderId, replayedOrder.path("id").asText());

        // 동일 키·내용 상이 → 409 duplicate_client_order_id
        byte[] altered = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.02", "e2e-buy-1");
        ResponseEntity<String> conflict = post("/api/v1/orders", altered,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", altered));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("duplicate_client_order_id", errorName(conflict));

        // 저널에는 최초 1건만 존재한다(중복 접수 0, 불변식 ③)
        assertEquals(1, jdbc.sql("SELECT count(*) FROM command_journal "
                        + "WHERE command_type = 'NEW' AND user_id = ? AND client_order_id = 'e2e-buy-1'")
                .params(alice).query(Long.class).single());
    }

    /**
     * 보존 기간이 지나 idempotency_keys 행이 삭제된 뒤의 키 재사용.
     *
     * DB 설계는 pg_cron이 7일 지난 idempotency_keys 행을 지우도록 규정하고, API 설계는
     * client_order_id 재사용을 무기한 금지하며 UNIQUE 권위를 command_journal에 둔다. 두 규정이
     * 겹치는 구간에서 대조할 해시가 사라진다. 이때 저널 replay를 그대로 200으로 흡수하면 요청과
     * 내용이 다른 원 주문을 멱등 응답으로 위장하게 된다. 대조 불가는 재사용으로 보고 거절해야 한다.
     *
     * 데모 마이그레이션에는 아직 퍼지가 배선돼 있지 않아 삭제를 직접 수행해 그 상태를 만든다.
     *
     * 순서: e2e-buy-1 주문이 저널에 있어야 하므로 그 주문을 만드는 앞 테스트 뒤에 와야 한다.
     * 동일 @Order 값은 상대 순서가 보장되지 않아 멱등 절(4)이 아니라 빈 값 12를 쓴다.
     * 이 테스트는 e2e-buy-1의 키 행을 지운 상태로 끝내므로 그 키를 다시 쓰는 테스트를 두지 않는다.
     */
    @Test
    @Order(12)
    void purgedIdempotencyKeyRejectsReuseInsteadOfReplaying() throws Exception {
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = ? AND idem_key = 'e2e-buy-1'")
                .params(alice).update();

        byte[] altered = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.03", "e2e-buy-1");
        ResponseEntity<String> reused = post("/api/v1/orders", altered,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", altered));

        assertEquals(409, reused.getStatusCode().value(),
                "대조 불가 재사용을 200 replay로 흡수하면 요청과 무관한 주문을 돌려준다");
        assertEquals("duplicate_client_order_id", errorName(reused));

        // 거절하면서 넣은 키 행이 남으면 다음 재시도가 그 해시로 대조에 성공해 거절이 무력화된다
        assertEquals(0, jdbc.sql("SELECT count(*) FROM idempotency_keys "
                        + "WHERE user_id = ? AND idem_key = 'e2e-buy-1'")
                .params(alice).query(Long.class).single(),
                "거절 경로가 자기 해시를 남기면 같은 결함이 한 번의 재시도 뒤 재현된다");

        // 저널은 여전히 최초 1건. 거절이 새 명령을 만들지 않는다
        assertEquals(1, jdbc.sql("SELECT count(*) FROM command_journal "
                        + "WHERE command_type = 'NEW' AND user_id = ? AND client_order_id = 'e2e-buy-1'")
                .params(alice).query(Long.class).single());
    }

    // ------------------------------------------------------------------
    // 취소 멱등
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    void cancelIsIdempotent() throws Exception {
        byte[] sell = orderBody("KRW-BTC", "ask", "limit", "60000000", "0.01", "e2e-sell-2");
        ResponseEntity<String> created = post("/api/v1/orders", sell,
                TestSigner.bearerForBody(bobKey.accessKey(), bobKey.secretKey(),
                        "POST", "/api/v1/orders", sell));
        assertEquals(201, created.getStatusCode().value());
        String orderId = objectMapper.readTree(created.getBody()).path("id").asText();

        String path = "/api/v1/orders/" + orderId;
        ResponseEntity<String> canceled = delete(path,
                TestSigner.bearerForQuery(bobKey.accessKey(), bobKey.secretKey(),
                        "DELETE", path, Map.of()));
        assertEquals(200, canceled.getStatusCode().value());
        assertEquals("CANCELED", objectMapper.readTree(canceled.getBody()).path("status").asText());

        // 재취소 → 200 + 현재 상태(멱등, §2-4)
        ResponseEntity<String> again = delete(path,
                TestSigner.bearerForQuery(bobKey.accessKey(), bobKey.secretKey(),
                        "DELETE", path, Map.of()));
        assertEquals(200, again.getStatusCode().value());
        assertEquals("CANCELED", objectMapper.readTree(again.getBody()).path("status").asText());

        // 없는 주문 → 404
        String missing = "/api/v1/orders/" + UUID.randomUUID();
        ResponseEntity<String> notFound = delete(missing,
                TestSigner.bearerForQuery(bobKey.accessKey(), bobKey.secretKey(),
                        "DELETE", missing, Map.of()));
        assertEquals(404, notFound.getStatusCode().value());
        assertEquals("order_not_found", errorName(notFound));
    }

    // ------------------------------------------------------------------
    // (e) 사전 가드
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    void gatewayGuardsRejectBeforeJournal() throws Exception {
        // 잔고 부족 → 400 insufficient_funds, 저널·멱등 키 미기록
        byte[] broke = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.01", "guard-broke-1");
        ResponseEntity<String> insufficient = post("/api/v1/orders", broke,
                TestSigner.bearerForBody(carolKey.accessKey(), carolKey.secretKey(),
                        "POST", "/api/v1/orders", broke));
        assertEquals(400, insufficient.getStatusCode().value());
        assertEquals("insufficient_funds", errorName(insufficient));
        assertEquals(0, jdbc.sql(
                        "SELECT count(*) FROM command_journal WHERE client_order_id = 'guard-broke-1'")
                .query(Long.class).single());
        assertEquals(0, jdbc.sql(
                        "SELECT count(*) FROM idempotency_keys WHERE idem_key = 'guard-broke-1'")
                .query(Long.class).single());

        // 최소 주문 금액 미달 → 400 under_min_order
        byte[] tiny = orderBody("KRW-BTC", "bid", "limit", "1000", "0.0001", "guard-tiny-1");
        ResponseEntity<String> underMin = post("/api/v1/orders", tiny,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", tiny));
        assertEquals(400, underMin.getStatusCode().value());
        assertEquals("under_min_order", errorName(underMin));

        // 멱등키 누락 → 400 client_order_id_required
        byte[] noKey = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\","
                + "\"price\":\"50000000\",\"volume\":\"0.01\"}").getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> missingKey = post("/api/v1/orders", noKey,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", noKey));
        assertEquals(400, missingKey.getStatusCode().value());
        assertEquals("client_order_id_required", errorName(missingKey));

        // 상호배타 위반(시장가 매수에 volume 동시 지정) → 400 invalid_parameter
        byte[] combo = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"market\","
                + "\"price\":\"10000\",\"volume\":\"0.01\",\"client_order_id\":\"guard-combo-1\"}")
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> comboViolation = post("/api/v1/orders", combo,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", combo));
        assertEquals(400, comboViolation.getStatusCode().value());
        assertEquals("invalid_parameter", errorName(comboViolation));
    }

    // ------------------------------------------------------------------
    // 공개 시장 데이터 스모크(엔벌로프·문자열 표기)
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    void publicMarketDataContract() throws Exception {
        ResponseEntity<String> markets = rest.getForEntity("/api/v1/markets", String.class);
        assertEquals(200, markets.getStatusCode().value());
        JsonNode marketsBody = objectMapper.readTree(markets.getBody());
        assertEquals("1.0", marketsBody.path("schema_version").asText());
        assertTrue(marketsBody.path("pagination").has("next_cursor"));
        JsonNode krwBtc = marketsBody.path("data").get(0);
        assertEquals("KRW-BTC", krwBtc.path("market").asText());
        assertTrue(krwBtc.path("maker_fee").isTextual(), "수수료는 문자열이어야 한다");

        ResponseEntity<String> orderbook =
                rest.getForEntity("/api/v1/orderbook?market=KRW-BTC", String.class);
        assertEquals(200, orderbook.getStatusCode().value());
        JsonNode orderbookBody = objectMapper.readTree(orderbook.getBody());
        assertEquals("1.0", orderbookBody.path("schema_version").asText());
        assertTrue(orderbookBody.has("epoch"));
        assertTrue(orderbookBody.has("seq"));

        ResponseEntity<String> trades =
                rest.getForEntity("/api/v1/trades?market=KRW-BTC&count=10", String.class);
        JsonNode tradesBody = objectMapper.readTree(trades.getBody());
        assertTrue(tradesBody.path("data").size() >= 1);
        JsonNode firstTrade = tradesBody.path("data").get(0);
        assertTrue(firstTrade.path("price").isTextual(), "체결가는 문자열이어야 한다");
        assertNotNull(firstTrade.path("trade_id").asText());

        ResponseEntity<String> ticker =
                rest.getForEntity("/api/v1/ticker?markets=KRW-BTC", String.class);
        JsonNode tickerBody = objectMapper.readTree(ticker.getBody());
        JsonNode t = tickerBody.path("data").get(0);
        assertEquals("50000000", t.path("trade_price").asText());
        // 24h 집계·부호분리 필드 계약(§4-A-4 v2.9). 체결이 있으므로 대금·수량은 0 초과다.
        assertTrue(t.path("acc_trade_price_24h").isTextual(), "거래대금은 문자열");
        assertTrue(new java.math.BigDecimal(t.path("acc_trade_price_24h").asText()).signum() > 0);
        assertTrue(new java.math.BigDecimal(t.path("acc_trade_volume_24h").asText()).signum() > 0);
        assertTrue(t.has("change_price"));
        assertTrue(t.path("high_24h").isTextual(), "체결이 있으면 high_24h는 값이 있다");

        // 캔들: trades 실시간 집계(§4-A-5). 200 + OHLCV 문자열 + trade_count 정수.
        ResponseEntity<String> candles =
                rest.getForEntity("/api/v1/candles/1m?market=KRW-BTC&count=10", String.class);
        assertEquals(200, candles.getStatusCode().value());
        JsonNode candlesBody = objectMapper.readTree(candles.getBody());
        assertEquals("1.0", candlesBody.path("schema_version").asText());
        assertTrue(candlesBody.path("pagination").has("next_cursor"));
        assertTrue(candlesBody.path("data").size() >= 1, "체결이 있으면 최소 1개 캔들");
        JsonNode c0 = candlesBody.path("data").get(0);
        assertTrue(c0.path("open").isTextual() && c0.path("close").isTextual(),
                "OHLC는 문자열이어야 한다");
        assertTrue(c0.path("high").isTextual() && c0.path("low").isTextual());
        assertTrue(c0.path("volume").isTextual());
        assertTrue(c0.path("trade_count").asInt() >= 1);
        assertEquals("KRW-BTC", c0.path("market").asText());
        assertTrue(c0.path("candle_time").isTextual());

        // 미지원 unit → 400 invalid_parameter
        ResponseEntity<String> badUnit =
                rest.getForEntity("/api/v1/candles/2m?market=KRW-BTC", String.class);
        assertEquals(400, badUnit.getStatusCode().value());
    }

    // ------------------------------------------------------------------
    // 캔들 집계 정확성(§4-A-5): 구간 경계·OHLC·volume 합·sparse·결정론·커서
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    void candleAggregationBoundaryOhlcSparseDeterminismCursor() throws Exception {
        // 합성 체결을 controlled created_at으로 직접 적재한다. 엔진 경로 밖 id 대역(9_000_000+)이라
        // trade_seq_registry 충돌이 없고, 07-20은 24h 밖이라 ticker 24h 집계와 간섭하지 않는다.
        seedTrade(9_000_001, "2026-07-20T09:00:10Z", "100", "1");
        seedTrade(9_000_002, "2026-07-20T09:00:30Z", "120", "2");
        seedTrade(9_000_003, "2026-07-20T09:00:50Z", "90", "1");
        seedTrade(9_000_004, "2026-07-20T09:01:05Z", "110", "5");
        // 09:02 구간 공백(sparse)
        seedTrade(9_000_005, "2026-07-20T09:03:00Z", "130", "1");
        // 동일 created_at 3건: (created_at,id) 정렬로 open=최소 id, close=최대 id 검증
        seedTrade(9_000_012, "2026-07-20T09:05:00Z", "210", "1");
        seedTrade(9_000_010, "2026-07-20T09:05:00Z", "195", "1");
        seedTrade(9_000_011, "2026-07-20T09:05:00Z", "205", "1");

        JsonNode data = candlesData("/api/v1/candles/1m?market=KRW-BTC&count=200");
        Map<String, JsonNode> byTime = new java.util.HashMap<>();
        data.forEach(c -> byTime.put(c.path("candle_time").asText(), c));

        // 구간 경계 정렬 + sparse
        assertTrue(byTime.containsKey("2026-07-20T09:00:00.000Z"));
        assertTrue(byTime.containsKey("2026-07-20T09:01:00.000Z"));
        assertTrue(byTime.containsKey("2026-07-20T09:03:00.000Z"));
        assertTrue(byTime.containsKey("2026-07-20T09:05:00.000Z"));
        assertTrue(!byTime.containsKey("2026-07-20T09:02:00.000Z"), "체결 없는 구간은 sparse");

        // OHLC + volume 합 + trade_count
        JsonNode b0 = byTime.get("2026-07-20T09:00:00.000Z");
        assertEquals("100", b0.path("open").asText());
        assertEquals("120", b0.path("high").asText());
        assertEquals("90", b0.path("low").asText());
        assertEquals("90", b0.path("close").asText());
        assertEquals("4", b0.path("volume").asText());
        assertEquals(3, b0.path("trade_count").asInt());

        // 동일 시각 tie-break: open=id 9_000_010(195), close=id 9_000_012(210)
        JsonNode b5 = byTime.get("2026-07-20T09:05:00.000Z");
        assertEquals("195", b5.path("open").asText());
        assertEquals("210", b5.path("close").asText());
        assertEquals("210", b5.path("high").asText());
        assertEquals("195", b5.path("low").asText());
        assertEquals("3", b5.path("volume").asText());

        // 결정론: 같은 trades에서 같은 캔들이 재현된다(DB now() 미사용)
        JsonNode data2 = candlesData("/api/v1/candles/1m?market=KRW-BTC&count=200");
        assertEquals(data.toString(), data2.toString(), "같은 trades → 같은 캔들");

        // 커서 페이지네이션: (candle_time, market) 자연키로 다음 페이지가 더 과거를 반환
        JsonNode page1 = objectMapper.readTree(
                rest.getForEntity("/api/v1/candles/1m?market=KRW-BTC&count=2", String.class).getBody());
        JsonNode d1 = page1.path("data");
        assertEquals(2, d1.size());
        assertTrue(page1.path("pagination").path("has_more").asBoolean());
        String cursor = page1.path("pagination").path("next_cursor").asText();
        JsonNode d2 = candlesData("/api/v1/candles/1m?market=KRW-BTC&count=2&cursor=" + cursor);
        String lastP1 = d1.get(d1.size() - 1).path("candle_time").asText();
        String firstP2 = d2.get(0).path("candle_time").asText();
        assertTrue(firstP2.compareTo(lastP1) < 0, "다음 페이지는 더 과거 캔들(candle_time DESC)");
    }

    // ------------------------------------------------------------------
    // WS 형성 캔들 값 진화 + 미구독 unit 미방출(§5-3, §5-4)
    // ------------------------------------------------------------------

    @Test
    @Order(9)
    void wsFormingCandleEvolvesAndUnsubscribedUnitsStaySilent() throws Exception {
        // 1d 구독 추가. 오늘 실체결이 같은 1d 버킷에 모여 형성 캔들 high/low/close 진화를 한 버킷에서
        // 관찰한다(07-20 합성 체결은 다른 날 버킷이라 오늘 집계와 분리). 단건 케이스(@Order 3)는
        // OHLC가 전부 동일가라 값 진화를 증명하지 못한다.
        subscribe(wsSession, "/topic/candle.1d.KRW-BTC", candleMessages);

        // 직전 종가 50,000,000 대비 상승 후 하락. 최종 close는 마지막 체결가여야 한다.
        tradeAt("ws-hi-sell", "ws-hi-buy", "51000000", "0.01");
        tradeAt("ws-lo-sell", "ws-lo-buy", "49000000", "0.01");

        // 오늘 1d 버킷: open=50,000,000(최초 @Order 3), high=51,000,000, low=49,000,000, close=49,000,000
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> candleMessages.stream()
                .anyMatch(m -> "1d".equals(m.path("unit").asText())
                        && "49000000".equals(m.path("close").asText())));
        JsonNode day = candleMessages.stream()
                .filter(m -> "1d".equals(m.path("unit").asText()))
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("50000000", day.path("open").asText(), "형성 캔들 open은 최초 체결가 고정");
        assertEquals("51000000", day.path("high").asText(), "상승 체결로 high 갱신");
        assertEquals("49000000", day.path("low").asText(), "하락 체결로 low 갱신");
        assertEquals("49000000", day.path("close").asText(), "close는 최종 체결가로 진화");

        // 미구독 unit 미방출: 구독은 1m·1d뿐이라 다른 unit candle은 오지 않는다(방출 비용 실구독 제한).
        assertTrue(candleMessages.stream().allMatch(m -> {
            String u = m.path("unit").asText();
            return "1m".equals(u) || "1d".equals(u);
        }), "구독하지 않은 unit의 candle이 방출됐다");
    }

    // ------------------------------------------------------------------
    // trade·candle 재동기화 seq 도메인 고정(감사 High, §5-2 (2)). ticker와 별도.
    // WS 라이브 seq와 REST 스냅샷 seq가 같은 event_seq 도메인이어야 재연결 재동기화 후
    // 라이브가 도메인 불일치로 드롭되지 않는다. 도메인이 다시 갈라지면 이 테스트가 깨진다.
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    void tradeAndCandleWsSeqShareEventSeqDomainWithRest() throws Exception {
        tradeAt("seq-dom-sell", "seq-dom-buy", "52000000", "0.01");

        // WS trade seq == trade_id == event_seq. 채널 인메모리 카운터(부팅 0 기점)면 등식이 깨진다.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> tradeMessages.stream()
                .anyMatch(m -> "52000000".equals(m.path("price").asText())));
        JsonNode wsTrade = tradeMessages.stream()
                .filter(m -> "52000000".equals(m.path("price").asText()))
                .reduce((a, b) -> b).orElseThrow();
        long wsTradeSeq = wsTrade.path("seq").asLong();
        String tradeId = wsTrade.path("trade_id").asText();
        assertEquals(Long.parseLong(tradeId), wsTradeSeq,
                "WS trade seq는 event_seq 도메인(= trade_id)이어야 한다");

        // REST 스냅샷 seq == WS 라이브 seq(동일 도메인). 재동기화 후 ch.lastSeq 기준점 정합.
        JsonNode tradesData = objectMapper.readTree(
                        rest.getForEntity("/api/v1/trades?market=KRW-BTC&count=50", String.class).getBody())
                .path("data");
        JsonNode restTrade = streamOf(tradesData)
                .filter(t -> tradeId.equals(t.path("trade_id").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("REST에 해당 체결 없음: " + tradeId));
        assertEquals(wsTradeSeq, restTrade.path("seq").asLong(),
                "REST trade seq와 WS trade seq는 동일 도메인이어야 한다");

        // WS candle(1m) seq도 event_seq 도메인(버킷 최대 event_seq). 같은 배치 방출이라 이 버킷의
        // 최대 id == 방금 체결의 event_seq다.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> candleMessages.stream()
                .anyMatch(m -> "1m".equals(m.path("unit").asText())
                        && m.path("seq").asLong() == wsTradeSeq));
        JsonNode wsCandle = candleMessages.stream()
                .filter(m -> "1m".equals(m.path("unit").asText())
                        && m.path("seq").asLong() == wsTradeSeq)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("52000000", wsCandle.path("close").asText(), "형성 캔들 close는 방금 체결가");
        String candleTime = wsCandle.path("candle_time").asText();

        // REST candle 같은 버킷 seq도 event_seq 도메인(WS 이후 체결이 없으면 동일, 있으면 증가).
        JsonNode candlesData = candlesData("/api/v1/candles/1m?market=KRW-BTC&count=200");
        JsonNode restCandle = streamOf(candlesData)
                .filter(c -> candleTime.equals(c.path("candle_time").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("REST에 해당 캔들 없음: " + candleTime));
        assertTrue(restCandle.path("seq").asLong() >= wsTradeSeq,
                "REST candle seq는 WS candle seq와 동일 event_seq 도메인(단조)이어야 한다");
    }

    // ------------------------------------------------------------------
    // 커서 조작 방어(§2-6) + 정상 next_cursor 연속성(감사·reviewer 지적)
    // 조작 커서는 조용한 빈 페이지가 아니라 400 invalid_parameter로 정규화돼야 한다.
    // ------------------------------------------------------------------

    @Test
    @Order(11)
    void cursorValidationRejectsTamperedCursorsAndPreservesContinuity() throws Exception {
        // (1) 비UUID id 커서: 디코드는 되지만 id가 UUID가 아니다 → 400 invalid_parameter
        String nonUuid = Cursors.encode(java.time.OffsetDateTime.now(), "not-a-uuid");
        assertCursorRejected(aliceKey, nonUuid);

        // (2) 형식은 UUID지만 미존재 주문 → 400(anchor 부재를 조용한 빈 페이지로 넘기지 않는다)
        String ghost = Cursors.encode(java.time.OffsetDateTime.now(), UUID.randomUUID().toString());
        assertCursorRejected(aliceKey, ghost);

        // (3) 타 사용자 소유 주문 UUID를 커서로: bob 스코프에서 alice 주문 external_id는 anchor
        //     조회(user_id 스코프)로 걸러져 400. 무경계 서브쿼리로 정렬 위치를 캐던 경로 제거 확인.
        assertNotNull(buyOrderId, "alice 주문 선행 필요");
        String foreign = Cursors.encode(java.time.OffsetDateTime.now(), buyOrderId);
        assertCursorRejected(bobKey, foreign);

        // (4) 정상 커서 연속성: count=2 두 페이지의 합이 전체 목록 앞 4건과 정확히 일치(누락·중복·역전 0)
        List<String> full = closedIds(aliceKey, Map.of("count", "100"));
        assertTrue(full.size() >= 4, "연속성 검증에 alice 종결주문 4건 이상 필요");

        ResponseEntity<String> page1 = getSigned("/api/v1/orders/closed", Map.of("count", "2"), aliceKey);
        assertEquals(200, page1.getStatusCode().value());
        JsonNode p1body = objectMapper.readTree(page1.getBody());
        assertTrue(p1body.path("pagination").path("has_more").asBoolean(), "다음 페이지 있음");
        String next = p1body.path("pagination").path("next_cursor").asText();
        assertTrue(next != null && !next.isBlank(), "next_cursor 발급");
        List<String> p1ids = idList(p1body.path("data"));
        assertEquals(2, p1ids.size());

        List<String> p2ids = closedIds(aliceKey, Map.of("count", "2", "cursor", next));
        assertEquals(2, p2ids.size());

        List<String> joined = new ArrayList<>(p1ids);
        joined.addAll(p2ids);
        assertEquals(full.subList(0, 4), joined, "커서 경계에서 누락·중복·역전 없음");
        assertEquals(4, new java.util.HashSet<>(joined).size(), "커서 페이지 간 중복 발생");
    }

    private void assertCursorRejected(DemoKeySeeder.IssuedKey key, String cursor) {
        ResponseEntity<String> r = getSigned("/api/v1/orders/closed", Map.of("cursor", cursor), key);
        assertEquals(400, r.getStatusCode().value(), "조작 커서는 400이어야 한다: " + r.getBody());
        assertEquals("invalid_parameter", errorName(r));
    }

    private JsonNode signedJson(String path, Map<String, String> params,
                                DemoKeySeeder.IssuedKey key) throws Exception {
        ResponseEntity<String> response = getSigned(path, params, key);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode signedData(String path, Map<String, String> params,
                                DemoKeySeeder.IssuedKey key) throws Exception {
        return signedJson(path, params, key).path("data");
    }

    private List<String> closedIds(DemoKeySeeder.IssuedKey key, Map<String, String> params)
            throws Exception {
        ResponseEntity<String> r = getSigned("/api/v1/orders/closed", params, key);
        assertEquals(200, r.getStatusCode().value(), r.getBody());
        return idList(objectMapper.readTree(r.getBody()).path("data"));
    }

    private static List<String> idList(JsonNode data) {
        List<String> ids = new ArrayList<>();
        data.forEach(o -> ids.add(o.path("id").asText()));
        return ids;
    }

    // 서명 query_hash는 서버가 파라미터를 정렬해 산출하므로 URL 순서는 무관하다. base64url 커서는
    // url-safe 알파벳이라 추가 인코딩 없이 그대로 붙인다.
    private ResponseEntity<String> getSigned(String path, Map<String, String> params,
                                             DemoKeySeeder.IssuedKey key) {
        StringBuilder url = new StringBuilder(path);
        boolean first = true;
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(params).entrySet()) {
            url.append(first ? '?' : '&').append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        String bearer = TestSigner.bearerForQuery(key.accessKey(), key.secretKey(),
                "GET", path, params);
        return get(url.toString(), bearer);
    }

    // 지정가 maker(bob 매도) 후 동일가 taker(alice 매수)로 정확히 그 가격에 체결시킨다.
    private void tradeAt(String sellKey, String buyKey, String price, String volume) {
        byte[] sell = orderBody("KRW-BTC", "ask", "limit", price, volume, sellKey);
        assertEquals(201, post("/api/v1/orders", sell,
                TestSigner.bearerForBody(bobKey.accessKey(), bobKey.secretKey(),
                        "POST", "/api/v1/orders", sell)).getStatusCode().value());
        byte[] buy = orderBody("KRW-BTC", "bid", "limit", price, volume, buyKey);
        assertEquals(201, post("/api/v1/orders", buy,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", buy)).getStatusCode().value());
    }

    /**
     * 다른 마켓의 내 체결 1건. 마켓 필터가 실제로 좁히는지 양성 판별하기 위한 표본이라
     * 엔진을 거치지 않고 직접 적재한다. 엔진 경로 밖 id 대역이라 trade_seq_registry 충돌이 없다.
     */
    private void seedForeignMarketTrade(long makerUserId, long takerUserId) {
        bootstrap.asset(ETH, "ETH", "Ethereum", "CRYPTO", 8);
        bootstrap.market(KRW_ETH, "KRW-ETH", ETH, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        java.time.OffsetDateTime at = java.time.OffsetDateTime.now();
        jdbc.sql("""
                        INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                            maker_user_id, taker_user_id, taker_side, price, quantity,
                            maker_fee, taker_fee, fencing_epoch, executed_at, created_at)
                        VALUES (?, ?, 0, 0, ?, ?, 'BUY', 3000000, 0.1, 150, 0, 1, ?, ?)
                        """)
                .params(9_100_001L, KRW_ETH, makerUserId, takerUserId, at, at)
                .update();
    }

    private void seedTrade(long id, String isoTime, String price, String qty) {
        java.time.OffsetDateTime at = java.time.OffsetDateTime.parse(isoTime);
        jdbc.sql("""
                        INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                            maker_user_id, taker_user_id, taker_side, price, quantity,
                            maker_fee, taker_fee, fencing_epoch, executed_at, created_at)
                        VALUES (?, ?, 0, 0, 0, 0, 'BUY', ?, ?, 0, 0, 1, ?, ?)
                        """)
                .params(id, KRW_BTC, new BigDecimal(price), new BigDecimal(qty), at, at)
                .update();
    }

    private JsonNode candlesData(String url) throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url, String.class);
        assertEquals(200, response.getStatusCode().value());
        return objectMapper.readTree(response.getBody()).path("data");
    }

    // ------------------------------------------------------------------
    // 주문 가능 정보(§4-B-5)
    // ------------------------------------------------------------------

    /**
     * chance는 마켓 메타·수수료·양측 계좌를 단건 객체로 답한다. 수수료가 /markets와 갈라지면
     * 화면이 주문 전에 보여주는 예상 수수료와 실제 정산이 어긋난다(§4-B-5 수수료 권위 소스).
     */
    @Test
    @Order(20)
    void orderChanceMirrorsMarketMetaFeesAndBothAccounts() throws Exception {
        ResponseEntity<String> response =
                getSigned("/api/v1/orders/chance", Map.of("market", "KRW-BTC"), aliceKey);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("1.0", body.path("schema_version").asText());
        // 페이지네이션 없는 단건이라 리스트 엔벌로프를 쓰지 않는다(§2-2).
        assertTrue(body.path("data").isMissingNode());
        assertTrue(body.path("pagination").isMissingNode());
        // 계좌/주문 조회 버킷(weight 2) 배선.
        assertNotNull(response.getHeaders().getFirst("X-RateLimit-Limit-account"));

        JsonNode meta = body.path("market");
        assertEquals("KRW-BTC", meta.path("id").asText());
        assertEquals("ACTIVE", meta.path("state").asText());
        assertEquals("5000", meta.path("min_order_amount").asText());
        assertTrue(meta.path("tick_size").isTextual());
        assertTrue(meta.path("max_order_amount").isTextual());

        JsonNode marketsData = objectMapper.readTree(
                rest.getForEntity("/api/v1/markets", String.class).getBody()).path("data");
        JsonNode krwBtc = streamOf(marketsData)
                .filter(m -> "KRW-BTC".equals(m.path("market").asText())).findFirst().orElseThrow();
        assertEquals(krwBtc.path("maker_fee").asText(), body.path("maker_fee").asText());
        assertEquals(krwBtc.path("taker_fee").asText(), body.path("taker_fee").asText());

        // bid는 quote(KRW), ask는 base(BTC)다. 뒤집히면 화면이 반대 잔고로 주문 가능 수량을 낸다.
        assertEquals("KRW", body.path("bid_account").path("currency").asText());
        assertEquals("BTC", body.path("ask_account").path("currency").asText());
        assertTrue(body.path("bid_account").path("balance").isTextual(), "잔고는 문자열");
        assertTrue(body.path("ask_account").path("locked").isTextual());

        // 계좌 미개설 사용자도 0으로 답한다. 여기서 실패하면 신규 사용자의 첫 주문 화면이 뜨지 않는다.
        long dave = bootstrap.user("dave@api.test");
        DemoKeySeeder.IssuedKey daveKey = keySeeder.issue(dave, "READ", "dave-read");
        JsonNode fresh = objectMapper.readTree(
                getSigned("/api/v1/orders/chance", Map.of("market", "KRW-BTC"), daveKey).getBody());
        assertEquals("0", fresh.path("bid_account").path("balance").asText());
        assertEquals("0", fresh.path("ask_account").path("locked").asText());

        // market 누락 → 400. /{id} 라우트로 새면 404 order_not_found가 나온다.
        ResponseEntity<String> missing = getSigned("/api/v1/orders/chance", Map.of(), aliceKey);
        assertEquals(400, missing.getStatusCode().value(), missing.getBody());
        assertEquals("invalid_parameter", errorName(missing));

        ResponseEntity<String> unknown =
                getSigned("/api/v1/orders/chance", Map.of("market", "KRW-NOPE"), aliceKey);
        assertEquals(400, unknown.getStatusCode().value());
        assertEquals("invalid_parameter", errorName(unknown));

        assertEquals(401, rest.getForEntity("/api/v1/orders/chance?market=KRW-BTC", String.class)
                .getStatusCode().value(), "무인증 접근이 열려 있다");
    }

    // ------------------------------------------------------------------
    // 내 체결·원장 내역(§4-C-2, §4-C-3)
    // ------------------------------------------------------------------

    /**
     * 같은 체결을 양쪽 사용자가 각자 관점으로 본다. taker는 TAKER·매수·base 수수료, maker는
     * MAKER·매도·quote 수수료다. 이 투영이 뒤집히면 사용자가 자기 수수료를 반대 자산으로 읽는다.
     */
    @Test
    @Order(21)
    void myTradesProjectsBothSidesOfTheSameSettlement() throws Exception {
        assertNotNull(buyOrderId, "alice 체결 선행 필요");
        JsonNode mine = streamOf(signedData("/api/v1/my/trades", Map.of("count", "50"), aliceKey))
                .filter(t -> buyOrderId.equals(t.path("order_id").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("내 체결에 e2e 매수가 없다"));
        assertEquals("TAKER", mine.path("liquidity_flag").asText());
        assertEquals("bid", mine.path("side").asText());
        assertEquals("KRW-BTC", mine.path("market").asText());
        assertEquals("50000000", mine.path("price").asText());
        assertEquals("0.01", mine.path("volume").asText());
        assertEquals("0.000005", mine.path("fee").asText());
        assertEquals("BTC", mine.path("fee_currency").asText(), "매수 수수료는 수취 자산(base)");
        assertTrue(mine.path("timestamp").asText().endsWith("Z"));

        String tradeId = mine.path("trade_id").asText();
        JsonNode counterparty = streamOf(signedData("/api/v1/my/trades", Map.of("count", "50"), bobKey))
                .filter(t -> tradeId.equals(t.path("trade_id").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("상대편 체결이 없다"));
        assertEquals("MAKER", counterparty.path("liquidity_flag").asText());
        assertEquals("ask", counterparty.path("side").asText());
        // maker 수수료 = 체결대금 500,000 x 0.0005, quote scale(KRW=0) HALF_UP.
        assertEquals("250", counterparty.path("fee").asText());
        assertEquals("KRW", counterparty.path("fee_currency").asText(), "매도 수수료는 수취 자산(quote)");

        // 남의 체결은 보이지 않는다. carol은 체결이 없다.
        assertEquals(0, signedData("/api/v1/my/trades", Map.of("count", "50"), carolKey).size());
    }

    /** 목록 계약(§2-2 엔벌로프·§2-6 커서·count 상한)과 필터·권한 경계. */
    @Test
    @Order(22)
    void myTradesFollowsListEnvelopeCursorAndGuards() throws Exception {
        JsonNode full = signedData("/api/v1/my/trades", Map.of("count", "50"), aliceKey);
        assertTrue(full.size() >= 3, "커서 검증에 체결 3건 이상 필요: " + full.size());

        JsonNode page1 = signedJson("/api/v1/my/trades", Map.of("count", "2"), aliceKey);
        assertEquals("1.0", page1.path("schema_version").asText());
        assertTrue(page1.path("pagination").path("has_more").asBoolean());
        String cursor = page1.path("pagination").path("next_cursor").asText();
        JsonNode page2 = signedData("/api/v1/my/trades", Map.of("count", "2", "cursor", cursor), aliceKey);
        List<String> joined = new ArrayList<>();
        page1.path("data").forEach(t -> joined.add(t.path("trade_id").asText()));
        page2.forEach(t -> joined.add(t.path("trade_id").asText()));
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 4 && i < full.size(); i++) {
            expected.add(full.get(i).path("trade_id").asText());
        }
        assertEquals(expected, joined, "커서 경계에서 누락·중복·역전 없음");

        // 마켓 필터 양성 판별. 같은 사용자에게 다른 마켓 체결을 만들어 두 응답이 갈리게 한다.
        // 건수 동일만 보면 필터를 통째로 무시하는 구현도 통과한다.
        seedForeignMarketTrade(alice, bob);
        JsonNode allMarkets = signedData("/api/v1/my/trades", Map.of("count", "100"), aliceKey);
        JsonNode krwBtcOnly =
                signedData("/api/v1/my/trades", Map.of("market", "KRW-BTC", "count", "100"), aliceKey);
        assertTrue(krwBtcOnly.size() < allMarkets.size(), "마켓 필터가 걸리지 않았다");
        krwBtcOnly.forEach(t -> assertEquals("KRW-BTC", t.path("market").asText()));
        assertEquals(400, getSigned("/api/v1/my/trades", Map.of("market", "KRW-NOPE"), aliceKey)
                .getStatusCode().value());

        // 유효 base64 봉투 + 비수치 id. Cursors.decode는 통과하고 커서 id 파싱 분기에 도달한다.
        // 여기가 500으로 새면 조작 커서가 서버 오류로 나간다(§2-6).
        String nonNumericId = Cursors.encode(java.time.OffsetDateTime.now(), "01J8ZT-not-a-number");
        ResponseEntity<String> tampered =
                getSigned("/api/v1/my/trades", Map.of("cursor", nonNumericId), aliceKey);
        assertEquals(400, tampered.getStatusCode().value(), tampered.getBody());
        assertEquals("invalid_parameter", errorName(tampered));
        ResponseEntity<String> tamperedLedger =
                getSigned("/api/v1/my/ledger", Map.of("cursor", nonNumericId), aliceKey);
        assertEquals(400, tamperedLedger.getStatusCode().value(), tamperedLedger.getBody());
        assertEquals(400, getSigned("/api/v1/my/trades", Map.of("count", "201"), aliceKey)
                .getStatusCode().value(), "count 상한 200(§2-6)");
        assertEquals(400, getSigned("/api/v1/my/trades", Map.of("count", "0"), aliceKey)
                .getStatusCode().value());
        assertEquals(400, getSigned("/api/v1/my/trades", Map.of("cursor", "not-a-cursor"), aliceKey)
                .getStatusCode().value());
        assertEquals(401, rest.getForEntity("/api/v1/my/trades", String.class)
                .getStatusCode().value(), "무인증 접근이 열려 있다");
        // 조회 스코프 키로 읽을 수 있어야 한다(§4-C read).
        assertEquals(200, getSigned("/api/v1/my/trades", Map.of(), aliceReadKey).getStatusCode().value());
        assertNotNull(getSigned("/api/v1/my/trades", Map.of(), aliceKey)
                .getHeaders().getFirst("X-RateLimit-Limit-account"));
    }

    /** 원장 투영(§4-C-3). 어휘는 DB 정본과 같고, 시스템 계정 분개는 사용자 관점에 나오지 않는다. */
    @Test
    @Order(23)
    void myLedgerProjectsUserSideOfDoubleEntry() throws Exception {
        JsonNode ledger = signedData("/api/v1/my/ledger", Map.of("count", "200"), aliceKey);
        assertTrue(ledger.size() > 0, "체결·예약이 있었으므로 원장 분개가 있어야 한다");
        Set<String> vocabulary = Set.of("DEPOSIT", "WITHDRAW", "TRADE", "FEE",
                "RESERVE", "RELEASE", "GENESIS", "REVERSAL");
        for (JsonNode entry : ledger) {
            assertTrue(vocabulary.contains(entry.path("entry_type").asText()),
                    "DB 정본 밖 entry_type: " + entry);
            assertTrue(entry.path("entry_id").isTextual());
            assertTrue(entry.path("amount").isTextual(), "금액은 문자열(§2-1)");
            assertTrue(entry.path("currency").isTextual());
            assertTrue(entry.path("ref").path("type").isTextual());
            assertTrue(entry.path("timestamp").asText().endsWith("Z"));
        }

        // 수수료 leg는 FEE_INCOME 시스템 계정 귀속이라 사용자 원장에 나오지 않는다(§4-C-3).
        assertTrue(streamOf(ledger).noneMatch(e -> "FEE".equals(e.path("entry_type").asText())),
                "시스템 계정 분개가 사용자 투영에 샜다");

        JsonNode trade = streamOf(ledger)
                .filter(e -> "TRADE".equals(e.path("entry_type").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("체결 분개가 없다"));
        assertEquals("TRADE", trade.path("ref").path("type").asText());
        assertTrue(trade.path("ref").path("trade_id").isTextual());

        // 예약 분개는 내부 주문 PK가 아니라 외부 UUID를 가리킨다(§2-6 내부 PK 미노출).
        JsonNode reserve = streamOf(ledger)
                .filter(e -> "RESERVE".equals(e.path("entry_type").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("예약 분개가 없다"));
        assertEquals(36, reserve.path("ref").path("order_id").asText().length(),
                "ref.order_id는 외부 UUID여야 한다: " + reserve);

        JsonNode krwOnly = signedData("/api/v1/my/ledger",
                Map.of("currency", "KRW", "count", "200"), aliceKey);
        assertTrue(krwOnly.size() > 0);
        assertTrue(krwOnly.size() < ledger.size(), "통화 필터가 걸리지 않았다");
        krwOnly.forEach(e -> assertEquals("KRW", e.path("currency").asText()));

        assertEquals(400, getSigned("/api/v1/my/ledger", Map.of("currency", "NOPE"), aliceKey)
                .getStatusCode().value());
        assertEquals(400, getSigned("/api/v1/my/ledger", Map.of("count", "201"), aliceKey)
                .getStatusCode().value());
        assertEquals(401, rest.getForEntity("/api/v1/my/ledger", String.class)
                .getStatusCode().value());

        // 커서 연속성: 페이지 경계에서 시각·id가 단조 감소한다.
        JsonNode page1 = signedJson("/api/v1/my/ledger", Map.of("count", "2"), aliceKey);
        assertTrue(page1.path("pagination").path("has_more").asBoolean());
        JsonNode page2 = signedData("/api/v1/my/ledger",
                Map.of("count", "2", "cursor", page1.path("pagination").path("next_cursor").asText()),
                aliceKey);
        long lastOfPage1 = Long.parseLong(
                page1.path("data").get(1).path("entry_id").asText());
        long firstOfPage2 = Long.parseLong(page2.get(0).path("entry_id").asText());
        assertTrue(firstOfPage2 < lastOfPage1, "다음 페이지가 더 과거가 아니다");
    }

    /**
     * §4-C-1 원가 규약의 두 경계. (1) 시드 취득만 있는 계정은 원가 0이 아니라 원가 미상이라
     * cost_basis_qty가 0이고 avg_buy_price가 "0"이다. (2) 부분 매도는 원가 확보분을 비례로
     * 덜어내므로 평단가가 유지된다. 평단가를 저장하는 구현이면 (2)에서 반올림이 누적돼 값이
     * 흔들린다. cost_basis_amount는 avg_buy_price의 반올림 이전 원본이므로, 나눗셈을 다시 해도
     * 서버가 낸 avg_buy_price와 같은 값으로 떨어져야 한다(K2 총매수 재계산 근거).
     */
    @Test
    @Order(24)
    void costBasisSeparatesUnknownAcquisitionAndSurvivesPartialSell() throws Exception {
        // (1) 시드 물량만 보유한 계정.
        long seedOnly = bootstrap.user("seedonly@api.test");
        bootstrap.userAccounts(seedOnly, KRW);
        bootstrap.userAccounts(seedOnly, BTC);
        bootstrap.deposit(seedOnly, BTC, new BigDecimal("5"));
        DemoKeySeeder.IssuedKey seedKey = keySeeder.issue(seedOnly, "TRADE", "seedonly-trade");

        JsonNode seedAccounts = signedData("/api/v1/accounts", Map.of(), seedKey);
        assertEquals("5", accountField(seedAccounts, "BTC", "balance"));
        assertEquals("0", accountField(seedAccounts, "BTC", "avg_buy_price"),
                "원가 미상은 평단가 산출 불가라 \"0\"이다");
        assertEquals("0", accountField(seedAccounts, "BTC", "cost_basis_qty"),
                "시드 취득이 원가 확보분으로 계상되면 전액 수익으로 오표시된다");
        assertEquals("0", accountField(seedAccounts, "BTC", "cost_basis_amount"));
        assertEquals(0, unknownQty(seedAccounts, "BTC").compareTo(new BigDecimal("5")),
                "보유 전량이 원가 미상으로 유도돼야 한다");

        // (2) alice 부분 매도. 기준값은 상수로 박지 않고 매도 직전 응답에서 읽는다. 선행
        // 캔들 테스트가 alice 매수를 더 쌓아 누적 원가가 이 시점에 달라지기 때문이다.
        JsonNode before = signedData("/api/v1/accounts", Map.of(), aliceKey);
        String avgBefore = accountField(before, "BTC", "avg_buy_price");
        BigDecimal qtyBefore = new BigDecimal(accountField(before, "BTC", "cost_basis_qty"));
        BigDecimal amountBefore = new BigDecimal(accountField(before, "BTC", "cost_basis_amount"));
        BigDecimal sold = new BigDecimal("0.005");
        assertTrue(qtyBefore.compareTo(sold) > 0, "부분 처분이 되려면 원가 확보분이 더 많아야 한다");

        // bob이 maker 매수 호가를 깔고 alice가 taker로 판다.
        byte[] bid = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.005", "cost-bid-1");
        assertEquals(201, post("/api/v1/orders", bid,
                TestSigner.bearerForBody(bobKey.accessKey(), bobKey.secretKey(),
                        "POST", "/api/v1/orders", bid)).getStatusCode().value());
        byte[] ask = orderBody("KRW-BTC", "ask", "limit", "50000000", "0.005", "cost-ask-1");
        ResponseEntity<String> askResponse = post("/api/v1/orders", ask,
                TestSigner.bearerForBody(aliceKey.accessKey(), aliceKey.secretKey(),
                        "POST", "/api/v1/orders", ask));
        assertEquals(201, askResponse.getStatusCode().value());
        assertEquals("FILLED", objectMapper.readTree(askResponse.getBody())
                .path("status").asText());

        JsonNode after = signedData("/api/v1/accounts", Map.of(), aliceKey);
        // 산식이 보장하는 것은 원값 동일이 아니라 quote scale 반올림 후 동일이다. V13의 비례
        // 차감은 relief를 18자리 DOWN 절사하므로 잔여 원가가 정비례보다 미세하게 크게 남는다.
        assertEquals(avgBefore, accountField(after, "BTC", "avg_buy_price"),
                "부분 매도가 평단가를 흔들었다(비례 차감 위반)");
        // 처분은 원가 확보분을 우선 소진한다(known-first). 원가 미상분은 그대로 남는다.
        BigDecimal qtyAfter = new BigDecimal(accountField(after, "BTC", "cost_basis_qty"));
        assertEquals(0, qtyBefore.subtract(sold).compareTo(qtyAfter),
                "처분 수량이 원가 확보분에서 정확히 빠지지 않았다");

        BigDecimal amountAfter = new BigDecimal(accountField(after, "BTC", "cost_basis_amount"));
        assertTrue(amountAfter.compareTo(amountBefore) < 0,
                "매도했는데 원가 확보 금액이 줄지 않았다");
        assertEquals(avgBefore, amountAfter.divide(qtyAfter, 0, RoundingMode.HALF_UP)
                        .toPlainString(),
                "cost_basis_amount 재나눗셈이 서버 avg_buy_price와 어긋난다");
    }

    // ------------------------------------------------------------------
    // 레이트리밋 weight(§2-5, item 2)
    // ------------------------------------------------------------------

    /**
     * 주문 버킷 weight 5 회귀. 격리 사용자로 주문 버킷을 급송 소진한다(다른 테스트 버킷과 무간섭).
     * 무잔고라 각 주문은 잔고 가드에서 400으로 끊겨 정산·저널이 발생하지 않고, 레이트리밋은 진입부에서
     * 선차감된다. weight 5면 capacity 100에 20여건만 통과하고 나머지는 429다. weight가 1로 회귀하면
     * 60건이 capacity 100 미만이라 전부 통과해 429가 0이 되므로 denied>=1이 이를 잡는다.
     */
    @Test
    @Order(50)
    void orderBucketAppliesWeightFiveNotOne() {
        long user = bootstrap.user("ratelimit-order@api.test");
        DemoKeySeeder.IssuedKey key = keySeeder.issue(user, "TRADE", "rl-order");
        int denied = 0;
        int passed = 0;
        for (int i = 0; i < 60; i++) {
            byte[] body = orderBody("KRW-BTC", "bid", "limit", "50000000", "0.0001", "rl-" + i);
            ResponseEntity<String> res = post("/api/v1/orders", body,
                    TestSigner.bearerForBody(key.accessKey(), key.secretKey(),
                            "POST", "/api/v1/orders", body));
            if (res.getStatusCode().value() == 429) {
                denied++;
            } else {
                passed++;
            }
        }
        assertTrue(denied >= 1, "weight 5면 60건 급송 중 429가 나야 한다(weight 1 회귀 시 0)");
        assertTrue(passed <= 40, "통과 수가 weight 5(약 20여건)와 정합해야 한다: " + passed);
    }

    /**
     * 게이트웨이 필터 순서 고정(§2-5, §2-5-a). 수용 게이트 -> IP 버킷 -> 인증이다.
     *
     * 이 순서가 곧 계약이라 값으로 못박는다. IP 버킷이 수용 게이트보다 앞이면 백프레셔로 거절한
     * 요청이 클라이언트 토큰을 갉고, 인증보다 뒤면 미인증 플러드가 이미 api_keys를 조회한 뒤다.
     * 배선만 보는 테스트로는 순서를 바꿔도 전부 통과한다.
     */
    @Test
    @Order(59)
    void gatewayFiltersRunAdmissionThenIpBucketThenAuth() {
        int admission = filterOrder("admissionFilter");
        int ipBucket = filterOrder("publicRateLimitFilter");
        int auth = filterOrder("apiAuthFilter");
        assertTrue(admission < ipBucket,
                "IP 버킷이 수용 게이트보다 앞이다: " + admission + " vs " + ipBucket);
        assertTrue(ipBucket < auth,
                "IP 버킷이 인증보다 뒤다: " + ipBucket + " vs " + auth);
    }

    private int filterOrder(String beanName) {
        return context.getBean(beanName, org.springframework.boot.web.servlet.FilterRegistrationBean.class)
                .getOrder();
    }

    /**
     * 공개 조회 IP 버킷 배선(§2-5, rank11). 무인증 §4-A 평면에는 계정 버킷이 붙지 않아 이 필터가
     * 유일한 유량 방벽이다. 배선이 빠지면 헤더가 없고 폭주가 무제한 통과한다.
     *
     * 마지막 순서인 이유: 테스트 클라이언트가 전부 127.0.0.1이라 버킷이 클래스 전역 공유다.
     * 앞 순서에서 소진하면 뒤 테스트의 공개 조회가 429로 흔들린다. weight·429 본문 계약은
     * PublicRateLimitFilterTest가 시계 고정으로 별도 검증한다.
     */
    @Test
    @Order(60)
    void publicMarketDataCarriesIpBucketAndShedsBurst() {
        ResponseEntity<String> first =
                rest.getForEntity("/api/v1/trades?market=KRW-BTC&count=1", String.class);
        assertEquals(200, first.getStatusCode().value());
        assertEquals("300", first.getHeaders().getFirst("X-RateLimit-Limit-ip"),
                "공개 조회 IP 한도는 계약값 300이어야 한다");
        assertNotNull(first.getHeaders().getFirst("X-RateLimit-Remaining-ip"));
        assertNotNull(first.getHeaders().getFirst("X-RateLimit-Reset"));

        // 다중행 조회 weight 2라 capacity 300은 150여건에서 마른다. weight 1로 회귀하면
        // 250건 급송으로도 마르지 않아 denied가 0이 된다(리필 30토큰/s는 급송 속도에 못 미친다).
        ResponseEntity<String> denied = null;
        for (int i = 0; i < 250 && denied == null; i++) {
            ResponseEntity<String> response =
                    rest.getForEntity("/api/v1/trades?market=KRW-BTC&count=1", String.class);
            if (response.getStatusCode().value() == 429) {
                denied = response;
            }
        }
        assertNotNull(denied, "공개 조회 급송이 IP 버킷에 전혀 걸리지 않았다");
        assertNotNull(denied.getHeaders().getFirst("Retry-After"));
        String body = denied.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"bucket\":\"ip\""), body);
        assertTrue(body.contains("\"scope\":\"public\""), body);
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private void subscribe(StompSession session, String destination,
                           ConcurrentLinkedQueue<JsonNode> sink) {
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    sink.add(objectMapper.readTree((byte[]) payload));
                } catch (Exception e) {
                    throw new IllegalStateException("WS payload 파싱 실패", e);
                }
            }
        });
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", bearer);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> delete(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", bearer);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, byte[] body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static byte[] orderBody(String market, String side, String ordType,
                                    String price, String volume, String clientOrderId) {
        return ("{\"market\":\"" + market + "\",\"side\":\"" + side + "\",\"ord_type\":\""
                + ordType + "\",\"price\":\"" + price + "\",\"volume\":\"" + volume
                + "\",\"client_order_id\":\"" + clientOrderId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String errorName(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).path("error").path("name").asText();
        } catch (Exception e) {
            throw new IllegalStateException("에러 바디 파싱 실패: " + response.getBody(), e);
        }
    }

    private static java.util.stream.Stream<JsonNode> streamOf(JsonNode array) {
        List<JsonNode> nodes = new ArrayList<>();
        array.forEach(nodes::add);
        return nodes.stream();
    }

    private static String assetField(JsonNode myAsset, String currency, String field) {
        for (JsonNode asset : myAsset.path("assets")) {
            if (currency.equals(asset.path("currency").asText())) {
                return asset.path(field).asText();
            }
        }
        throw new AssertionError(currency + " 자산 없음: " + myAsset);
    }

    /** 원가 미상 수량. 서버가 필드로 내지 않고 클라이언트가 유도하는 값이다(§4-C-1 K3). */
    private static BigDecimal unknownQty(JsonNode accounts, String currency) {
        return new BigDecimal(accountField(accounts, currency, "balance"))
                .add(new BigDecimal(accountField(accounts, currency, "locked")))
                .subtract(new BigDecimal(accountField(accounts, currency, "cost_basis_qty")));
    }

    private static String accountField(JsonNode accounts, String currency, String field) {
        for (JsonNode account : accounts) {
            if (currency.equals(account.path("currency").asText())) {
                return account.path(field).asText();
            }
        }
        throw new AssertionError(currency + " 계좌 없음: " + accounts);
    }
}

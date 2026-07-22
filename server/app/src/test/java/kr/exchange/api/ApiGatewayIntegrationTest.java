package kr.exchange.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.auth.DemoKeySeeder;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
    private static final int KRW_BTC = 1;

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

    private static String accountField(JsonNode accounts, String currency, String field) {
        for (JsonNode account : accounts) {
            if (currency.equals(account.path("currency").asText())) {
                return account.path(field).asText();
            }
        }
        throw new AssertionError(currency + " 계좌 없음: " + accounts);
    }
}

package kr.exchange.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.ws.relay.BusClient;
import kr.exchange.api.ws.relay.RelayBroker;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.ledger.AccountBootstrap;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 노드 단위 중계의 전달과 발행(S6 설계 4-1~4-6, 6-2). 실제 RabbitMQ(하네스 중계 설정)와 전체 Spring 문맥을 쓴다.
 *
 * 판 A: 이 노드가 팔로워(리스를 다른 holder 가 쥠)이고, 시험이 원격 소유 노드를 흉내 내 봉투와 하트비트를 보낸다.
 * 판 B: 리스를 풀어 이 노드가 리더가 되고 주문 두 건으로 체결을 만든다. 버스 관측자가 이 노드의 봉투와 하트비트를 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrokerRelayIntegrationTest {

    private static final String REMOTE = "remote-node";
    private static final long REMOTE_EPOCH = 500;
    private static final String PARTITION = "matching-engine";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange").withUsername("exchange").withPassword("exchange");
    static final RelayBroker BROKER;

    static {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "exchange", "exchange")
                .locations("classpath:db/migration").load().migrate();
        // 문맥이 뜨기 전에 다른 노드가 리스를 쥐게 둔다. 이 노드는 팔로워로 기동하고 획득하지 못한다.
        owner().sql("UPDATE leader_lease SET holder = ?, epoch = ?, lease_until = clock_timestamp() + interval '1 hour'"
                + " WHERE resource = ?").params(REMOTE, REMOTE_EPOCH, PARTITION).update();
        BROKER = RelayBroker.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("exchange.engine.lease-ttl", () -> "6s");
        registry.add("exchange.engine.lease-tick-interval", () -> "1s");
        registry.add("exchange.engine.lease-acquire-lock-timeout", () -> "500ms");
        BROKER.springProperties().forEach((k, v) -> registry.add(k, () -> v));
    }

    @Autowired TestRestTemplate rest;
    @Autowired AccountBootstrap bootstrap;
    @Autowired DemoKeySeeder keySeeder;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;
    @Autowired PartitionLeases leases;
    @LocalServerPort int port;

    long alice;
    long bob;
    DemoKeySeeder.IssuedKey aliceKey;
    DemoKeySeeder.IssuedKey bobKey;
    final List<StompSession> sessions = new ArrayList<>();
    final ScheduledExecutorService beats = Executors.newSingleThreadScheduledExecutor();

    @BeforeAll
    void seed() {
        bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", 2, 1, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        alice = bootstrap.user("alice@relay.test");
        bob = bootstrap.user("bob@relay.test");
        for (long uid : new long[] {alice, bob}) {
            bootstrap.userAccounts(uid, 1);
            bootstrap.userAccounts(uid, 2);
            bootstrap.deposit(uid, 1, new BigDecimal("1000000000"));
            bootstrap.deposit(uid, 2, new BigDecimal("100"));
        }
        aliceKey = keySeeder.issue(alice, "TRADE", "alice-relay");
        bobKey = keySeeder.issue(bob, "TRADE", "bob-relay");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> gauge("exchange.ws.relay.connected", "link", "sub") == 1
                && gauge("exchange.ws.relay.connected", "link", "pub") == 1);
    }

    @AfterAll
    void close() {
        beats.shutdownNow();
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
    }

    @Test
    @Order(1)
    void followerDeliversRemoteEnvelopesAndServesTheMirrorWhileTheOwnerBeats() throws Exception {
        try (BusClient remote = BusClient.publisher(BROKER)) {
            AtomicLong announced = new AtomicLong(-1);
            AtomicLong hbSeq = new AtomicLong();
            var beating = beats.scheduleAtFixedRate(() -> remote.send(RelayEnvelope.heartbeat(REMOTE, PARTITION,
                    REMOTE_EPOCH, hbSeq.incrementAndGet(), new RelayEnvelope.Heartbeat(announced.get() < 0 ? Map.of()
                            : Map.of("KRW-BTC", new long[] {REMOTE_EPOCH, announced.get()}), Map.of()).encode(json))),
                    0, 200, TimeUnit.MILLISECONDS);

            // 원격 소유 노드의 하트비트가 셋 오기 전에는 소유 노드가 닿지 않는 상태가 이어져 게이트가 닫혀 있을 수 있다.
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> gauge("exchange.ws.gate.open", null, null) == 1);
            ConcurrentLinkedQueue<JsonNode> book = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<JsonNode> aliceOrders = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<JsonNode> bobOrders = new ConcurrentLinkedQueue<>();
            StompSession open = connect(null);
            subscribe(open, "/topic/orderbook.KRW-BTC", book);
            subscribe(connect(aliceKey), "/user/queue/myOrder", aliceOrders);
            subscribe(connect(bobKey), "/user/queue/myOrder", bobOrders);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !book.isEmpty());
            // 스냅샷은 SUBSCRIBE 사건에서 나가고, 내장 브로커의 구독 등록은 수신 채널 스레드에서 끝난다. 그 틈을 기다린다.
            Thread.sleep(500);
            assertEquals(0, book.peek().path("seq").asLong(), "비소유 노드의 구독 스냅샷이 미러 없이 기준점을 냈다");

            remote.send(RelayEnvelope.topic(REMOTE, "/topic/orderbook.KRW-BTC", "KRW-BTC", REMOTE_EPOCH, 7,
                    orderbookBody(7)));
            announced.set(7);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> seqs(book).contains(7L));

            String selfHolder = leases.holder();
            remote.send(RelayEnvelope.topic(selfHolder, "/topic/orderbook.KRW-BTC", "KRW-BTC", REMOTE_EPOCH, 8,
                    orderbookBody(8)));
            remote.send(RelayEnvelope.user(REMOTE, alice, "/queue/myOrder", "KRW-BTC", REMOTE_EPOCH, 1,
                    "{\"type\":\"myOrder\",\"marker\":\"for-alice\"}".getBytes(StandardCharsets.UTF_8)));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !aliceOrders.isEmpty());
            Thread.sleep(1_000);
            assertEquals(1, seqs(book).stream().filter(s -> s == 7L).count(), "원격 봉투를 한 번이 아니게 전달했다");
            assertTrue(!seqs(book).contains(8L), "자기 출처 봉투를 전달했다");
            assertEquals("for-alice", aliceOrders.peek().path("marker").asText());
            assertTrue(bobOrders.isEmpty(), "다른 사용자의 세션에 사용자 메시지가 갔다");

            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> orderbookSeq() == 7);
            JsonNode rest = json.readTree(this.rest.getForEntity("/api/v1/orderbook?market=KRW-BTC", String.class)
                    .getBody());
            assertEquals(REMOTE_EPOCH, rest.path("epoch").asLong());

            double malformedBefore = counter("exchange.ws.relay.dropped", "reason", "malformed");
            remote.send(RelayEnvelope.user(REMOTE, alice, "/queue/other", "KRW-BTC", REMOTE_EPOCH, 2, new byte[0]));
            remote.send(RelayEnvelope.heartbeat(REMOTE, "matching-p9", REMOTE_EPOCH, 99,
                    "{\"orderbook\":{},\"ticker\":{}}".getBytes(StandardCharsets.UTF_8)));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                    counter("exchange.ws.relay.dropped", "reason", "malformed") >= malformedBefore + 2);

            beating.cancel(false);
            long stoppedAt = System.nanoTime();
            Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> orderbookSeq() == 0);
            long tookMs = (System.nanoTime() - stoppedAt) / 1_000_000;
            assertTrue(tookMs <= 5_000, "하트비트가 멈춘 뒤 5초 안에 seq 0 이 되지 않았다: " + tookMs + "ms");
        }
    }

    @Test
    @Order(2)
    void leaderPublishesTermEpochEnvelopesAndHeartbeatsAtTheDefaultInterval() throws Exception {
        try (BusClient bus = BusClient.observer(BROKER)) {
            owner().sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = ?")
                    .param(PARTITION).update();
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> leases.openTerm(1) != null);
            long epoch = leases.openTerm(1).epoch();
            assertTrue(epoch > REMOTE_EPOCH);
            String holder = leases.holder();

            ConcurrentLinkedQueue<JsonNode> book = new ConcurrentLinkedQueue<>();
            subscribe(connect(null), "/topic/orderbook.KRW-BTC", book);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !book.isEmpty());

            Thread.sleep(500);
            assertEquals(201, order(bobKey, "ask", "50000000", "0.01", "relay-sell-1").getStatusCode().value());
            assertEquals(201, order(aliceKey, "bid", "50000000", "0.01", "relay-buy-1").getStatusCode().value());
            assertEquals(201, order(aliceKey, "bid", "49000000", "0.01", "relay-rest-1").getStatusCode().value());
            long placed = System.currentTimeMillis();

            // 마지막 게시 뒤에 나간 하트비트를 기다린다. 그 하트비트는 마지막 게시 번호를 알려야 한다.
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> bus.matching(r -> "hb".equals(r.h("x-kind"))
                    && holder.equals(r.h("x-origin")) && r.receivedAtMillis() > placed + 1_500).size() >= 1);
            List<BusClient.Received> obEnvelopes = bus.matching(r -> holder.equals(r.h("x-origin"))
                    && "/topic/orderbook.KRW-BTC".equals(r.h("x-dest")));
            assertTrue(obEnvelopes.size() >= 3, "리더의 호가 봉투가 모자라다: " + obEnvelopes.size());
            for (BusClient.Received r : obEnvelopes) {
                assertEquals(String.valueOf(epoch), r.h("x-epoch"), "봉투 epoch 가 기간 epoch 가 아니다");
                JsonNode local = book.stream().filter(m -> m.path("seq").asText().equals(r.h("x-seq"))
                        && "realtime".equals(m.path("stream").asText())).findFirst().orElseThrow();
                assertArrayEquals(json.writeValueAsBytes(local), json.writeValueAsBytes(json.readTree(r.body())),
                        "로컬 구독자와 버스 관측자의 본문이 다르다");
            }

            List<BusClient.Received> hbs = bus.matching(r -> "hb".equals(r.h("x-kind")) && holder.equals(r.h("x-origin")));
            assertTrue(hbs.size() >= 3);
            for (BusClient.Received hb : hbs) {
                assertEquals(PARTITION, hb.h("x-partition"));
                assertEquals(String.valueOf(epoch), hb.h("x-epoch"));
            }
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < hbs.size(); i++) {
                gaps.add(hbs.get(i).receivedAtMillis() - hbs.get(i - 1).receivedAtMillis());
            }
            gaps.sort(Long::compare);
            long median = gaps.get(gaps.size() / 2);
            // 기본 간격은 200ms 다(2026-09-25 S6 관문 W3·W4 뒤 1초에서 줄였다). 수신 시각 간격이라 지연 흔들림을 넉넉히 둔다.
            assertTrue(median >= 100 && median <= 400, "하트비트 간격이 200ms 가 아니다: " + gaps);
            BusClient.Received lastHb = hbs.get(hbs.size() - 1);
            RelayEnvelope.Heartbeat body = RelayEnvelope.Heartbeat.decode(json, lastHb.body());
            long lastObSeq = obEnvelopes.stream().mapToLong(r -> Long.parseLong(r.h("x-seq"))).max().orElseThrow();
            assertEquals(lastObSeq, body.orderbook().get("KRW-BTC")[1], "하트비트가 알린 호가 seq 가 마지막 게시가 아니다");
            long lastTicker = bus.matching(r -> "/topic/ticker.KRW-BTC".equals(r.h("x-dest"))).stream()
                    .mapToLong(r -> Long.parseLong(r.h("x-seq"))).max().orElseThrow();
            assertEquals(lastTicker, body.ticker().get("KRW-BTC")[1], "하트비트가 알린 티커 seq 가 마지막 게시가 아니다");

            Map<String, List<Long>> userSeqs = new HashMap<>();
            for (BusClient.Received r : bus.matching(r -> "user".equals(r.h("x-kind")) && holder.equals(r.h("x-origin")))) {
                JsonNode m = json.readTree(r.body());
                assertEquals("user_market", m.path("seq_domain").asText());
                userSeqs.computeIfAbsent(r.h("x-user") + r.h("x-dest") + r.h("x-market"), k -> new ArrayList<>())
                        .add(m.path("seq").asLong());
            }
            assertTrue(userSeqs.size() >= 4, "두 사용자·두 채널의 사용자 봉투가 없다: " + userSeqs.keySet());
            for (Map.Entry<String, List<Long>> e : userSeqs.entrySet()) {
                for (int i = 0; i < e.getValue().size(); i++) {
                    assertEquals(i + 1L, e.getValue().get(i), e.getKey() + " 가 1부터 조밀하지 않다: " + e.getValue());
                }
            }

            JsonNode accounts = json.readTree(get("/api/v1/accounts", aliceKey).getBody()).path("data");
            BusClient.Received lastAsset = bus.matching(r -> "/queue/myAsset".equals(r.h("x-dest"))
                    && String.valueOf(alice).equals(r.h("x-user"))).stream().reduce((a, b) -> b).orElseThrow();
            for (JsonNode asset : json.readTree(lastAsset.body()).path("assets")) {
                long restVersion = -1;
                for (JsonNode a : accounts) {
                    if (a.path("currency").asText().equals(asset.path("currency").asText())) {
                        restVersion = a.path("version").asLong();
                    }
                }
                assertEquals(restVersion, asset.path("version").asLong(), "myAsset version 이 계좌 조회와 다르다");
            }
        }
    }

    private long orderbookSeq() throws Exception {
        return json.readTree(rest.getForEntity("/api/v1/orderbook?market=KRW-BTC", String.class).getBody())
                .path("seq").asLong();
    }

    private byte[] orderbookBody(long seq) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("schema_version", "1.0");
        body.put("market", "KRW-BTC");
        body.put("timestamp", "2026-09-25T00:00:00.000Z");
        body.put("epoch", REMOTE_EPOCH);
        body.put("seq", seq);
        body.put("total_ask_size", "1");
        body.put("total_bid_size", "1");
        body.put("orderbook_units", List.of(Map.of("ask_price", "101", "ask_size", "1", "bid_price", "99",
                "bid_size", "1")));
        body.put("type", "orderbook");
        body.put("stream", "realtime");
        return json.writeValueAsBytes(body);
    }

    private StompSession connect(DemoKeySeeder.IssuedKey key) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter());
        client.setDefaultHeartbeat(new long[] {0, 0});
        StompHeaders connect = new StompHeaders();
        if (key != null) {
            connect.add("Authorization", TestSigner.bearerWith(key.accessKey(), key.secretKey(), TestSigner.wsQueryHash(),
                    System.currentTimeMillis(), UUID.randomUUID().toString()));
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws/v1", new WebSocketHttpHeaders(),
                connect, new StompSessionHandlerAdapter() { }).get(15, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private void subscribe(StompSession session, String destination, ConcurrentLinkedQueue<JsonNode> sink) {
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    sink.add(json.readTree((byte[]) payload));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private static List<Long> seqs(ConcurrentLinkedQueue<JsonNode> sink) {
        return sink.stream().map(m -> m.path("seq").asLong()).toList();
    }

    private ResponseEntity<String> order(DemoKeySeeder.IssuedKey key, String side, String price, String volume,
                                         String coid) {
        byte[] body = ("{\"market\":\"KRW-BTC\",\"side\":\"" + side + "\",\"ord_type\":\"limit\",\"price\":\"" + price
                + "\",\"volume\":\"" + volume + "\",\"client_order_id\":\"" + coid + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", TestSigner.bearerForBody(key.accessKey(), key.secretKey(), "POST", "/api/v1/orders",
                body));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, DemoKeySeeder.IssuedKey key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", TestSigner.bearerForQuery(key.accessKey(), key.secretKey(), "GET", path, Map.of()));
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private double gauge(String name, String tag, String value) {
        var search = meters.find(name);
        var g = (tag == null ? search : search.tag(tag, value)).gauge();
        return g == null ? -1 : g.value();
    }

    private double counter(String name, String tag, String value) {
        return meters.find(name).tag(tag, value).functionCounter().count();
    }

    static JdbcClient owner() {
        return JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "exchange", "exchange"));
    }
}

package kr.exchange.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.ws.relay.BusClient;
import kr.exchange.api.ws.relay.RelayBroker;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.api.ws.relay.RelayPublisher;
import kr.exchange.engine.PartitionLeases;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 브로커 장애에서 REST 는 영향을 받지 않는다(API 설계 §5-1, S6 설계 4-11, 6-2). 모든 마켓을 쥔 리더 노드 하나와 실제 RabbitMQ 를
 * 쓴다. (a) 브로커를 멈춘다. (b) 다시 띄운다. (c) 메모리 경보로 발행만 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrokerOutageRestIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange").withUsername("exchange").withPassword("exchange");
    static final RelayBroker BROKER;

    static {
        POSTGRES.start();
        BROKER = RelayBroker.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        // 주문 부하를 싣는 시험이다. 계정 주문 버킷(20건/10초)을 하네스 스택처럼 연다.
        registry.add("exchange.order.rate-limit.capacity", () -> "100000");
        BROKER.springProperties().forEach((k, v) -> registry.add(k, () -> v));
    }

    @Autowired TestRestTemplate rest;
    @Autowired AccountBootstrap bootstrap;
    @Autowired DemoKeySeeder keySeeder;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;
    @Autowired PartitionLeases leases;
    @Autowired RelayPublisher relayPublisher;
    @LocalServerPort int port;

    long alice;
    long bob;
    DemoKeySeeder.IssuedKey aliceKey;
    DemoKeySeeder.IssuedKey bobKey;
    StompSession ws;
    final ConcurrentLinkedQueue<JsonNode> trades = new ConcurrentLinkedQueue<>();
    final AtomicInteger coids = new AtomicInteger();
    long baselineP99Ms;

    @BeforeAll
    void seed() throws Exception {
        bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", 2, 1, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        alice = bootstrap.user("alice@outage.test");
        bob = bootstrap.user("bob@outage.test");
        for (long uid : new long[] {alice, bob}) {
            bootstrap.userAccounts(uid, 1);
            bootstrap.userAccounts(uid, 2);
            bootstrap.deposit(uid, 1, new BigDecimal("100000000000"));
            bootstrap.deposit(uid, 2, new BigDecimal("1000"));
        }
        aliceKey = keySeeder.issue(alice, "TRADE", "alice-outage");
        bobKey = keySeeder.issue(bob, "TRADE", "bob-outage");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> leases.openTerm(1) != null
                && gauge("exchange.ws.relay.connected", "link", "pub") == 1
                && gauge("exchange.ws.relay.connected", "link", "sub") == 1);
        ws = connect();
        ws.subscribe("/topic/trade.KRW-BTC", sink(trades));
        Thread.sleep(500);
        restingOrders(50);
        baselineP99Ms = p99(restingOrders(100));
    }

    @AfterAll
    void close() {
        if (ws != null && ws.isConnected()) {
            ws.disconnect();
        }
    }

    @Test
    @Order(1)
    void brokerStopLeavesRestAndLocalSessionsUntouched() throws Exception {
        double disconnectedBefore = counter("exchange.ws.relay.dropped", "reason", "disconnected");
        BROKER.stopApp();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> gauge("exchange.ws.relay.connected", "link", "pub") == 0
                    && gauge("exchange.ws.relay.connected", "link", "sub") == 0);
            long outageP99 = p99(restingOrders(100));
            assertTrue(outageP99 <= 2 * baselineP99Ms, "브로커 정지 중 주문 p99 " + outageP99 + "ms 가 평소 "
                    + baselineP99Ms + "ms 의 2배를 넘었다");
            assertEquals(200, rest.getForEntity("/api/v1/orderbook?market=KRW-BTC", String.class).getStatusCode().value());
            assertEquals(200, rest.getForEntity("/api/v1/ticker?markets=KRW-BTC", String.class).getStatusCode().value());
            assertEquals(200, get("/api/v1/accounts", aliceKey).getStatusCode().value());

            int tradesBefore = trades.size();
            assertEquals(201, order(bobKey, "ask", "10000000", "0.001").getStatusCode().value());
            assertEquals(201, order(aliceKey, "bid", "10000000", "0.001").getStatusCode().value());
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> trades.size() > tradesBefore);
            assertTrue(ws.isConnected(), "리더의 로컬 WS 세션이 브로커 정지로 끊겼다");
            assertTrue(counter("exchange.ws.relay.dropped", "reason", "disconnected") > disconnectedBefore,
                    "끊긴 동안의 게시가 disconnected 로 세지지 않았다");
            assertEquals(0.0, demotions(), "브로커 정지가 강등으로 번졌다");
            Thread.sleep(25_000);
        } finally {
            BROKER.startApp();
        }
        long started = System.nanoTime();
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> gauge("exchange.ws.relay.connected", "link", "pub") == 1
                && gauge("exchange.ws.relay.connected", "link", "sub") == 1);
        System.out.printf("브로커 재시작 뒤 두 연결 복구 %dms%n", (System.nanoTime() - started) / 1_000_000);
        try (BusClient remote = BusClient.publisher(BROKER)) {
            int before = trades.size();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
                remote.send(RelayEnvelope.topic("remote-x", "/topic/trade.KRW-BTC", "KRW-BTC", 1, 90_000_000_000L,
                        "{\"type\":\"trade\",\"market\":\"KRW-BTC\",\"seq\":90000000000}".getBytes(StandardCharsets.UTF_8)));
                Thread.sleep(300);
                return trades.stream().skip(before).anyMatch(t -> t.path("seq").asLong() == 90_000_000_000L);
            });
        }
    }

    @Test
    @Order(2)
    void memoryAlarmBlocksOnlyPublishingAndOldFramesNeverArriveLate() throws Exception {
        try (BusClient bus = BusClient.observer(BROKER)) {
            double timeoutsBefore = counter("exchange.ws.relay.dropped", "reason", "write_timeout")
                    + counter("exchange.ws.relay.dropped", "reason", "queue_full");
            long alarmStartedAt = System.currentTimeMillis();
            BROKER.alarm(true);
            AtomicBoolean subscriptionDropped = new AtomicBoolean();
            AtomicInteger maxPending = new AtomicInteger();
            AtomicBoolean flooding = new AtomicBoolean(true);
            // 체결 게시만으로는 송신 버퍼가 늦게 찬다. 같은 발행 큐에 큰 봉투를 넣어 쓰기 대기를 빨리 드러낸다.
            Thread flood = new Thread(() -> {
                byte[] big = new byte[32 * 1024];
                java.util.Arrays.fill(big, (byte) ' ');
                while (flooding.get()) {
                    relayPublisher.offer(RelayEnvelope.topic(leases.holder(), "/topic/trade.KRW-BTC", "KRW-BTC", 1, 1, big));
                    maxPending.accumulateAndGet(relayPublisher.pendingWrites(), Math::max);
                    if (gauge("exchange.ws.relay.connected", "link", "sub") != 1) {
                        subscriptionDropped.set(true);
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "alarm-flood");
            flood.start();
            try {
                // 주문은 모두 201 이어야 한다(restingOrders 가 본다). 지연은 이 시험의 큰 봉투 넣기와 CPU 를 나눠 기록만 한다.
                System.out.printf("메모리 경보 중 주문 p99 %dms(평소 %dms)%n", p99(restingOrders(50)), baselineP99Ms);
                Awaitility.await().atMost(Duration.ofSeconds(25)).until(() ->
                        counter("exchange.ws.relay.dropped", "reason", "write_timeout")
                                + counter("exchange.ws.relay.dropped", "reason", "queue_full") > timeoutsBefore);
                long remaining = 30_000 - (System.currentTimeMillis() - alarmStartedAt);
                if (remaining > 0) {
                    Thread.sleep(remaining);
                }
            } finally {
                flooding.set(false);
                flood.join(5_000);
                BROKER.alarm(false);
            }
            long clearedAt = System.currentTimeMillis();
            assertTrue(!subscriptionDropped.get(), "경보 동안 구독 연결이 끊겼다");
            assertTrue(maxPending.get() <= 1, "대기 쓰기가 1 을 넘었다: " + maxPending.get());
            assertEquals(0.0, demotions(), "메모리 경보가 강등으로 번졌다");
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> gauge("exchange.ws.relay.connected", "link", "pub") == 1);
            restingOrders(20);
            Thread.sleep(5_000);
            // 경보를 푼 뒤 새 발행 연결로 게시가 다시 흐른다.
            List<BusClient.Received> after = bus.matching(r -> r.receivedAtMillis() >= clearedAt
                    && leases.holder().equals(r.h("x-origin"))
                    && r.receivedAtMillis() - Long.parseLong(r.h("x-sent-at")) <= 5_000);
            assertTrue(!after.isEmpty(), "경보를 푼 뒤 발행이 다시 흐르지 않았다");
            // 늦은 옛 프레임은 기록만 한다. 이 시험의 앱은 Docker 포트 전달로 브로커에 붙어 앱이 보낸 RST 가 전달 프록시에서 끝나고
            // 브로커에는 보통 닫기로 보인다. RST 자체는 BrokerLinkAbortTest 가, 끝에서 끝까지의 늦은 전달은 같은 Docker 망의 W4 가 본다.
            long stale = bus.matching(r -> r.receivedAtMillis() >= clearedAt
                    && r.receivedAtMillis() - Long.parseLong(r.h("x-sent-at")) > 5_000).size();
            System.out.printf("경보를 푼 뒤 5초 넘게 묵은 봉투 %d(포트 전달 경로, 판정 아님)%n", stale);
        }
    }

    private List<Long> restingOrders(int n) {
        List<Long> took = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 겹치지 않는 가격에 쉬는 매수다. 체결 없이 호가·myOrder·myAsset 게시가 난다.
            String price = String.valueOf(1_000_000 + (coids.incrementAndGet() % 500) * 1_000);
            long t0 = System.nanoTime();
            ResponseEntity<String> r = order(aliceKey, "bid", price, "0.001");
            took.add((System.nanoTime() - t0) / 1_000_000);
            assertEquals(201, r.getStatusCode().value(), r.getBody());
        }
        return took;
    }

    private static long p99(List<Long> took) {
        List<Long> sorted = new ArrayList<>(took);
        sorted.sort(Long::compare);
        return Math.max(1, sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1));
    }

    private ResponseEntity<String> order(DemoKeySeeder.IssuedKey key, String side, String price, String volume) {
        String coid = "outage-" + coids.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
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

    private StompSession connect() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter());
        client.setDefaultHeartbeat(new long[] {0, 0});
        return client.connectAsync("ws://localhost:" + port + "/ws/v1", new WebSocketHttpHeaders(), new StompHeaders(),
                new StompSessionHandlerAdapter() { }).get(15, TimeUnit.SECONDS);
    }

    private StompFrameHandler sink(ConcurrentLinkedQueue<JsonNode> out) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    out.add(json.readTree((byte[]) payload));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private double demotions() {
        return meters.find("exchange.partition.demotions").counters().stream().mapToDouble(Counter::count).sum();
    }

    private double gauge(String name, String tag, String value) {
        var g = meters.find(name).tag(tag, value).gauge();
        return g == null ? -1 : g.value();
    }

    private double counter(String name, String tag, String value) {
        return meters.find(name).tag(tag, value).functionCounter().count();
    }
}

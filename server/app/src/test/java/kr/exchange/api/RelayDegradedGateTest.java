package kr.exchange.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.auth.SecretCipher;
import kr.exchange.api.ws.WsGate;
import kr.exchange.api.ws.relay.RelayBroker;
import kr.exchange.ledger.AccountBootstrap;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.socket.CloseStatus;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 두 노드의 WS 수용 판정(API 설계 §5-6, S6 설계 4-11, 6-2 RelayDegradedGateTest). 한 JVM 에 앱 문맥 둘을 띄우고 PostgreSQL 과
 * RabbitMQ 를 나눠 쓴다. 리스 TTL 2초라 소유 노드가 닿지 않는 상태의 유예(리스 TTL + 3초)는 5초다.
 *
 * 1부: 리더 simple, 팔로워 broker(모드를 바꾸는 롤링 재기동 중 혼재, (c)). 리더를 kill 하면 팔로워가 승격한다.
 * 2부: 그 노드가 리더(broker)이고 새 팔로워(broker)를 띄워 (a) 브로커 정지, (e) 재시작 뒤 새 구독 세대, (b) 메모리 경보,
 * (d) 리더 kill 을 차례로 본다.
 */
class RelayDegradedGateTest {

    private static final Duration TTL = Duration.ofSeconds(2);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange").withUsername("exchange").withPassword("exchange");
    static final RelayBroker BROKER;
    static final DemoKeySeeder.IssuedKey TRADER;

    static {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "exchange", "exchange")
                .locations("classpath:db/migration").load().migrate();
        JdbcClient owner = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "exchange", "exchange"));
        AccountBootstrap bootstrap = new AccountBootstrap(owner);
        bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", 2, 1, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        long trader = bootstrap.user("trader@gate.test");
        bootstrap.userAccounts(trader, 1);
        bootstrap.userAccounts(trader, 2);
        bootstrap.deposit(trader, 1, new BigDecimal("100000000000"));
        TRADER = new DemoKeySeeder(owner, new SecretCipher(RelayNode.MASTER_KEY)).issue(trader, "TRADE", "gate-trader");
        BROKER = RelayBroker.start();
    }

    private final List<RelayNode> nodes = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private int orders;

    @AfterAll
    static void stopBroker() {
        BROKER.close();
    }

    @Test
    void followerRefusesExactlyWhenTheRelayCannotCarryTheOwnersPublications() throws Exception {
        try {
            // 1부 (c): simple 리더는 하트비트를 보내지 않는다. broker 팔로워는 유예 뒤 owner_silent 로 거절하고 리더는 받는다.
            RelayNode simpleLeader = node("L-simple", null);
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(simpleLeader::leader);
            RelayNode f = node("F", BROKER);
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> f.gauge("exchange.ws.relay.connected", "link", "sub") == 1);
            long seen = System.nanoTime();
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> f.gate().degraded(WsGate.Cause.OWNER_SILENT));
            System.out.printf("(c) 모드 혼재에서 팔로워 owner_silent 까지 %dms%n", (System.nanoTime() - seen) / 1_000_000);
            assertRefused(f, "relay_unavailable");
            assertEquals(101, WsProbe.handshake(simpleLeader.port(), null).status(), "모든 파티션을 쥔 simple 리더가 거절했다");

            // simple 리더 kill. 팔로워가 TTL 뒤 승격해 모든 파티션을 쥐고 받는다.
            simpleLeader.close();
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(f::leader);
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> f.gate().open());

            // 2부: f 가 broker 리더다. 새 팔로워를 띄우고 미러를 채운다.
            RelayNode g = node("G", BROKER);
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> g.gate().open()
                    && g.gauge("exchange.ws.relay.connected", "link", "sub") == 1);
            placeOrder(f);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> orderbookSeq(g) > 0);

            // (a) 브로커 정지. 팔로워는 5초 뒤 link_down 으로 세션을 1013 으로 닫고 거절한다. 리더는 닫지 않는다.
            WsProbe.Watched atFollower = WsProbe.open(g.port());
            WsProbe.Watched atLeader = WsProbe.open(f.port());
            long stoppedAt = System.nanoTime();
            BROKER.stopApp();
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> g.gate().degraded(WsGate.Cause.LINK_DOWN));
            long downMs = (System.nanoTime() - stoppedAt) / 1_000_000;
            assertTrue(downMs >= 4_500, "5초 유예 전에 link_down 이 됐다: " + downMs + "ms");
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> atFollower.closed.get() != null);
            assertEquals(CloseStatus.SERVICE_OVERLOAD.getCode(), atFollower.closed.get().getCode());
            assertEquals("relay_unavailable", atFollower.closed.get().getReason());
            assertRefused(g, "relay_unavailable");
            assertNull(atLeader.closed.get(), "리더가 세션을 닫았다");
            assertEquals(0.0, f.gauge("exchange.ws.relay.degraded", "cause", "link_down"), "리더의 degraded 가 1 이다");

            // (e) 재시작. 새 구독 세대의 하트비트가 셋 오기 전에는 끊기기 전 미러를 쓰지 않는다.
            BROKER.startApp();
            Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                    .until(() -> g.gauge("exchange.ws.relay.connected", "link", "sub") == 1);
            assertEquals(0, orderbookSeq(g), "새 구독 세대의 하트비트 전에 끊기기 전 미러를 썼다");
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> orderbookSeq(g) > 0 && g.gate().open());

            // (b) 메모리 경보. 구독 연결은 멀쩡하고 리더의 발행만 막힌다. 팔로워는 owner_silent 로 거절하고 그 동안 seq 0 이다.
            WsProbe.Watched beforeAlarm = WsProbe.open(g.port());
            WsProbe.Watched leaderSession = WsProbe.open(f.port());
            long alarmAt = System.nanoTime();
            BROKER.alarm(true);
            try {
                Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> g.gate().degraded(WsGate.Cause.OWNER_SILENT));
                System.out.printf("(b) 경보 뒤 팔로워 owner_silent 까지 %dms%n", (System.nanoTime() - alarmAt) / 1_000_000);
                assertEquals(1.0, g.gauge("exchange.ws.relay.connected", "link", "sub"), "경보 동안 구독 연결이 끊겼다");
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> beforeAlarm.closed.get() != null);
                assertEquals(1013, beforeAlarm.closed.get().getCode());
                assertRefused(g, "relay_unavailable");
                assertEquals(0, orderbookSeq(g), "발행이 막힌 동안 팔로워가 멈춘 미러를 기준점으로 냈다");
                assertEquals(0.0, f.gauge("exchange.ws.relay.degraded", "cause", "owner_silent"));
                assertNull(leaderSession.closed.get(), "경보 중 리더가 세션을 닫았다");
            } finally {
                BROKER.alarm(false);
            }
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> WsProbe.handshake(g.port(), null).status() == 101);

            // (d) 리더 kill. 리스가 TTL 안에 만료되므로 팔로워는 한 번도 중계 불능이 되지 않은 채 승격한다.
            f.close();
            long killedAt = System.nanoTime();
            boolean everDegraded = false;
            while (!g.leader() && System.nanoTime() - killedAt < Duration.ofSeconds(30).toNanos()) {
                everDegraded |= g.gate().degraded(WsGate.Cause.OWNER_SILENT) || g.gate().degraded(WsGate.Cause.LINK_DOWN);
                Thread.sleep(200);
            }
            assertTrue(g.leader(), "팔로워가 승격하지 않았다");
            assertTrue(!everDegraded, "리더 사망을 중계 불능으로 오판했다");
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> g.gauge("exchange.ws.gate.open", null, null) == 1);
        } finally {
            for (RelayNode n : nodes) {
                n.close();
            }
        }
    }

    private RelayNode node(String name, RelayBroker broker) {
        RelayNode n = RelayNode.start(name, POSTGRES.getJdbcUrl(), "exchange", "exchange", broker, TTL);
        nodes.add(n);
        return n;
    }

    private static void assertRefused(RelayNode node, String reason) throws Exception {
        WsProbe.Handshake h = WsProbe.handshake(node.port(), null);
        assertEquals(503, h.status(), node.name + " 가 거절하지 않았다");
        assertEquals("5", h.header("Retry-After"));
        assertTrue(node.meters().find("exchange.ws.relay.handshake_refused").tag("reason", reason).functionCounter()
                .count() >= 1);
        assertEquals(503, node.status("/actuator/health/ws"), "헬스 그룹 ws 가 거절과 다르다");
    }

    private long orderbookSeq(RelayNode node) throws Exception {
        return json.readTree(node.get("/api/v1/orderbook?market=KRW-BTC")).path("seq").asLong();
    }

    private void placeOrder(RelayNode leader) throws Exception {
        byte[] body = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"" + (1_000_000 + ++orders)
                + "\",\"volume\":\"0.001\",\"client_order_id\":\"gate-" + orders + "\"}").getBytes(StandardCharsets.UTF_8);
        var r = leader.post("/api/v1/orders", body, TestSigner.bearerForBody(TRADER.accessKey(), TRADER.secretKey(), "POST",
                "/api/v1/orders", body));
        assertEquals(201, r.statusCode(), r.body());
    }
}

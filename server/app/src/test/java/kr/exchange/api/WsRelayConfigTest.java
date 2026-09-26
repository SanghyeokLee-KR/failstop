package kr.exchange.api;

import kr.exchange.api.ws.LeaseWatch;
import kr.exchange.api.ws.MarketMirror;
import kr.exchange.api.ws.WsGate;
import kr.exchange.api.ws.relay.BrokerLink;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayHeartbeat;
import kr.exchange.api.ws.relay.RelayPublisher;
import kr.exchange.api.ws.relay.RelaySubscriber;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 중계 설정 전환과 WS 수용 판정의 배선(S6 설계 4-12, 4-11, 6-2). 기본값 simple 은 중계 구성요소도 브로커 연결도 만들지 않고 게이트와
 * 리스 조회기만 만든다. simple 모드에서도 다른 노드가 유효한 리스를 쥐면 WS 를 거절한다.
 */
class WsRelayConfigTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange").withUsername("exchange").withPassword("exchange");

    static {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "exchange", "exchange")
                .locations("classpath:db/migration").load().migrate();
    }

    private static final List<Class<?>> RELAY_BEANS = List.of(BrokerLink.class, RelayPublisher.class,
            RelaySubscriber.class, RelayHeartbeat.class, OwnerBeats.class, MarketMirror.class);

    @Test
    void simpleDefaultBuildsGateAndLeaseWatchButNoRelayOrBrokerLink() {
        try (RelayNode node = start("cfg-default")) {
            assertNoRelayBeans(node.context);
            assertEquals(1, node.context.getBeanNamesForType(WsGate.class).length);
            assertEquals(1, node.context.getBeanNamesForType(LeaseWatch.class).length);
        }
    }

    @Test
    void brokerModeWithoutAddressOrPasscodeRefusesToStart() {
        Exception noHost = assertThrows(Exception.class, () -> start("cfg-no-host", "--exchange.ws.relay.mode=broker"));
        assertTrue(rootMessage(noHost).contains("broker.host"), rootMessage(noHost));
        Exception noPass = assertThrows(Exception.class, () -> start("cfg-no-pass", "--exchange.ws.relay.mode=broker",
                "--exchange.ws.relay.broker.host=localhost"));
        assertTrue(rootMessage(noPass).contains("EXCHANGE_WS_RELAY_BROKER_PASSCODE"), rootMessage(noPass));
    }

    /** 하트비트 간격이 닿음 판정 창에 맞지 않으면 살아 있는 소유 노드도 닿지 않는 때가 생긴다. 브로커에 붙기 전에 멈춘다. */
    @Test
    void brokerModeWithHeartbeatTooSlowForReachWindowRefusesToStart() {
        Exception slow = assertThrows(Exception.class, () -> start("cfg-slow-hb", "--exchange.ws.relay.mode=broker",
                "--exchange.ws.relay.broker.host=localhost", "--exchange.ws.relay.broker.passcode=unused",
                "--exchange.ws.relay.owner-heartbeat=2s"));
        assertTrue(rootMessage(slow).contains("owner-heartbeat"), rootMessage(slow));
    }

    @Test
    void engineDisabledNodeBuildsNoRelayEvenInBrokerModeAndRefusesWs() throws Exception {
        try (RelayNode node = start("cfg-engine-off", "--exchange.engine.enabled=false", "--exchange.ws.relay.mode=broker")) {
            assertNoRelayBeans(node.context);
            assertEquals(0, node.context.getBeanNamesForType(LeaseWatch.class).length, "엔진을 끈 노드에 리스 조회기가 떴다");
            WsProbe.Handshake h = WsProbe.handshake(node.port(), null);
            assertEquals(503, h.status());
            assertEquals("5", h.header("Retry-After"));
            assertEquals(0.0, node.gauge("exchange.ws.gate.open", null, null));
            assertEquals(503, node.status("/actuator/health/ws"));
        }
    }

    /**
     * P2 simple 팔로워는 다른 holder 가 유효한 리스를 쥐는 동안 거절하고, 리스가 풀려 승격하면 받는다. 승격 뒤 강등되면 열린 세션을
     * 1013 으로 닫는다.
     */
    @Test
    void simpleFollowerRefusesUntilPromotedAndDemotionClosesSessions() throws Exception {
        JdbcClient owner = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "exchange", "exchange"));
        owner.sql("UPDATE leader_lease SET holder = 'other-node', epoch = epoch + 1000,"
                + " lease_until = clock_timestamp() + interval '1 hour' WHERE resource = 'matching-engine'").update();
        try (RelayNode node = start("cfg-simple")) {
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> node.gauge("exchange.ws.gate.open", null, null) == 0);
            WsProbe.Handshake refused = WsProbe.handshake(node.port(), null);
            assertEquals(503, refused.status(), "simple 팔로워가 받았다");
            assertEquals("5", refused.header("Retry-After"));
            assertEquals(503, node.status("/actuator/health/ws"));
            assertTrue(node.meters().find("exchange.ws.relay.handshake_refused").tag("reason", "relay_required")
                    .functionCounter().count() >= 1);

            owner.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second'"
                    + " WHERE resource = 'matching-engine'").update();
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(node::leader);
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> WsProbe.handshake(node.port(), null).status() == 101);
            assertEquals(200, node.status("/actuator/health/ws"));

            WsProbe.Watched session = WsProbe.open(node.port());
            // 강등 흉내. 다른 holder 가 새 epoch 로 리스를 쥐면 이 노드의 다음 갱신이 거부된다.
            owner.sql("UPDATE leader_lease SET holder = 'other-node-2', epoch = epoch + 1000,"
                    + " lease_until = clock_timestamp() + interval '1 hour' WHERE resource = 'matching-engine'").update();
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> session.closed.get() != null);
            assertEquals(1013, session.closed.get().getCode());
            assertEquals("relay_required", session.closed.get().getReason());
        }
    }

    private static RelayNode start(String name, String... extra) {
        return RelayNode.start(name, POSTGRES.getJdbcUrl(), "exchange", "exchange", null, Duration.ofSeconds(2), extra);
    }

    private static void assertNoRelayBeans(ApplicationContext context) {
        for (Class<?> type : RELAY_BEANS) {
            assertEquals(0, context.getBeanNamesForType(type).length, type.getSimpleName() + " 빈이 떴다");
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }
}

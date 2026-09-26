package kr.exchange.api;

import kr.exchange.api.ws.relay.RelayBroker;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WebSocket 오리진 규칙은 두 모드에서 종전과 같다(API 설계 §2-7, S6 설계 4-16). 허용 밖 오리진은 403, 허용 오리진은 101 이다. 오리진
 * 검사는 WS 수용 게이트보다 먼저 돈다.
 */
class WsOriginRelayTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange").withUsername("exchange").withPassword("exchange");
    static final RelayBroker BROKER;

    static {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "exchange", "exchange")
                .locations("classpath:db/migration").load().migrate();
        BROKER = RelayBroker.start();
    }

    @AfterAll
    static void stop() {
        BROKER.close();
    }

    @Test
    void originRulesAreUnchangedInBothModes() throws Exception {
        for (RelayBroker broker : new RelayBroker[] {null, BROKER}) {
            try (RelayNode node = RelayNode.start(broker == null ? "origin-simple" : "origin-broker",
                    POSTGRES.getJdbcUrl(), "exchange", "exchange", broker, Duration.ofSeconds(2))) {
                Awaitility.await().atMost(Duration.ofSeconds(30)).until(node::leader);
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> node.gate().open());
                String mode = broker == null ? "simple" : "broker";
                assertEquals(403, WsProbe.handshake(node.port(), "https://evil.example").status(), mode + " 허용 밖 오리진");
                assertEquals(101, WsProbe.handshake(node.port(), "http://localhost:3000").status(), mode + " 허용 오리진");
                assertEquals(101, WsProbe.handshake(node.port(), null).status(), mode + " 오리진 없음(같은 출처)");
            }
        }
    }
}

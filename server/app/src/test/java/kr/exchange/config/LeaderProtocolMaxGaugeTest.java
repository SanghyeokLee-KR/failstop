package kr.exchange.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.lease.LeaseResources;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 바이너리 능력 게이지(ADR 0034 S7, S7 설계 4-2, DB 설계 §5.4-c 13). S7 바이너리는 최고 모드 3 을 넘기고 그 값을
 * exchange.leader.protocol.max 로 낸다. 모드 전환 절차가 2->3 전에 두 노드의 값이 3 인지 본다(§5.4-e 1). 값은 DB 를 읽지 않는
 * 상수라 DB 를 읽지 못한 노드도 같은 값을 낸다. 조회 역할 노드는 리스를 얻지 않으므로 기동 모드 게이지처럼 내지 않는다.
 */
class LeaderProtocolMaxGaugeTest {

    @Test
    void engineNodePublishesThePartitionCapableMaximum() {
        assertEquals(3, LeaseResources.MAX_PROTOCOL, "S7 바이너리의 최고 모드가 3 이 아니다");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // DB 를 읽지 못하는 노드. 기동 모드는 1 로 두지만 바이너리 능력은 그대로 3 이다.
        new EngineConfig().leaderProtocolBoot(JdbcClient.create(new UnreachableDataSource()), registry,
                new MockEnvironment());
        Gauge max = registry.find("exchange.leader.protocol.max").gauge();
        assertNotNull(max, "엔진 노드가 바이너리 능력 게이지를 내지 않았다");
        assertEquals(3.0, max.value());
        assertEquals(1.0, registry.find("exchange.leader.protocol.boot").gauge().value(),
                "DB 를 읽지 못한 노드의 기동 모드는 1 이다(LeaderProtocolBoot)");
    }

    @Test
    void readRoleNodePublishesNeitherProtocolGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockEnvironment read = new MockEnvironment();
        read.setActiveProfiles("read");
        new EngineConfig().leaderProtocolBoot(JdbcClient.create(new UnreachableDataSource()), registry, read);
        assertNull(registry.find("exchange.leader.protocol.max").gauge(), "조회 노드가 바이너리 능력 게이지를 냈다");
        assertNull(registry.find("exchange.leader.protocol.boot").gauge());

        SimpleMeterRegistry byProperty = new SimpleMeterRegistry();
        new EngineConfig().leaderProtocolBoot(JdbcClient.create(new UnreachableDataSource()), byProperty,
                new MockEnvironment().withProperty("exchange.node.role", "read"));
        assertNull(byProperty.find("exchange.leader.protocol.max").gauge(), "exchange.node.role=read 노드가 게이지를 냈다");
    }

    /** 연결을 주지 않는 데이터 소스. 기동 모드 조회가 곧바로 실패한다. */
    private static final class UnreachableDataSource extends AbstractDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("unreachable", "08001");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("unreachable", "08001");
        }
    }
}

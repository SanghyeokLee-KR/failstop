package kr.exchange.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 리스 하트비트 전용 커넥션 격리 회귀(하네스 검증 §3 E1 하드닝). E1 실측 기전(요청 풀 고갈이
 * 리스 renew를 굶겨 정당 단독 리더가 페일오버)을 풀 계층에서 결정적으로 재현한다.
 *
 * 요청 풀을 실제로 포화시킨 상태에서, 같은 renew SQL을 요청 풀로 돌리면 커넥션 기아로 실패하고
 * 전용 풀로 돌리면 성공함을 대조해, 전용 풀 격리가 하트비트 liveness를 보전함을 고정한다.
 */
@Testcontainers
class LeaseHeartbeatIsolationIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    static HikariDataSource requestPool;
    static HikariDataSource leasePool;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        requestPool = pool("request-pool", 2, 1000);
        leasePool = pool("exchange-lease", 2, 2000);
    }

    @AfterAll
    static void tearDown() {
        if (requestPool != null) {
            requestPool.close();
        }
        if (leasePool != null) {
            leasePool.close();
        }
    }

    @Test
    void heartbeatRenewsOnDedicatedPoolWhileRequestPoolIsSaturated() throws Exception {
        long ttlMs = 5_000;
        LeaderLeaseService lease = new LeaderLeaseService(JdbcClient.create(leasePool),
                Duration.ofMillis(ttlMs), Duration.ofMillis(1_000));
        assertTrue(lease.tryAcquire(), "전용 풀 승격 실패");
        long epoch = lease.requireLeader().epoch();

        List<Connection> held = new ArrayList<>();
        try {
            // 요청 풀 전량 점유(active == max). 실 카오스의 다중행 쿼리 폭주 대신 커넥션을 직접
            // 붙들어 포화를 결정적으로 만든다.
            for (int i = 0; i < requestPool.getMaximumPoolSize(); i++) {
                held.add(requestPool.getConnection());
            }
            assertEquals(requestPool.getMaximumPoolSize(),
                    requestPool.getHikariPoolMXBean().getActiveConnections(),
                    "요청 풀이 포화되지 않았다");

            // 대조군: 같은 renew를 요청 풀로 돌리면 커넥션 기아로 실패한다(하트비트가 요청 풀을
            // 공유하면 페일오버가 나는 원인).
            JdbcClient onRequestPool = JdbcClient.create(requestPool);
            assertThrows(RuntimeException.class, () -> onRequestPool
                    .sql("SELECT renew_leader_lease(?, ?, ? * interval '1 millisecond')")
                    .params(lease.holder(), epoch, ttlMs)
                    .query(Boolean.class)
                    .single(), "포화된 요청 풀에서 renew가 성공하면 안 된다");

            // 본 검증: 전용 풀 하트비트는 요청 풀 포화와 무관하게 성립한다.
            assertTrue(lease.renew(), "전용 풀 하트비트가 갱신에 실패했다");
            assertTrue(lease.isLeader(), "갱신 후에도 리더여야 한다");
            assertEquals(epoch, lease.requireLeader().epoch(), "갱신은 epoch 보존이 계약이다");
        } finally {
            for (Connection c : held) {
                c.close();
            }
            lease.close();
        }
    }

    private static HikariDataSource pool(String name, int maxSize, int connectionTimeoutMs) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setPoolName(name);
        config.setMaximumPoolSize(maxSize);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new HikariDataSource(config);
    }
}

package kr.exchange.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 좀비 리더 자발적 강등(DB 설계 §5.4). 992e59b의 전용 풀 격리로 하트비트 생존성이 서비스
 * 생존성과 분리된 뒤 생긴 결함(요청 경로가 멈춘 노드가 리스만 갱신해 스탠바이 승격을 영구히
 * 막음)을 풀 계층에서 결정적으로 재현하고, 그 반대편(일시 지연·순간 포화에서의 헛페일오버)이
 * 재발하지 않음을 함께 고정한다.
 *
 * 시간 축은 주입 클럭으로 구동한다. 실제 임계(45s)를 벽시계로 기다리지 않고도 동일한 판정을
 * 재현하려면 표본 사이의 경과 시간이 테스트 통제 아래 있어야 한다.
 */
@Testcontainers
class LeaderStanddownIntegrationTest {

    private static final Duration STANDDOWN_AFTER = Duration.ofSeconds(45);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    static HikariDataSource requestPool;
    static HikariDataSource leasePool;
    static JdbcClient leaseJdbc;
    static JdbcClient probeJdbc;

    private final List<Connection> held = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        requestPool = pool("request-pool", 2, 1000);
        leasePool = pool("exchange-lease", 2, 2000);
        // 리스 조작·검증은 요청 풀 포화와 무관해야 하므로 전용 풀로만 한다.
        leaseJdbc = JdbcClient.create(leasePool);
        probeJdbc = JdbcClient.create(leasePool);
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

    @BeforeEach
    void expireLeaseBeforeEach() {
        // 리스 행은 테스트 간 공유 자원이다. 각 테스트가 자기 리더를 잡고 시작하게 만든다.
        expireLease();
    }

    @Test
    void stalledNodeStopsRenewingAndStandbyTakesOverWithNextEpoch() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderLeaseService zombie = new LeaderLeaseService(leaseJdbc,
                Duration.ofSeconds(5), Duration.ofMillis(200),
                new RequestPoolStallDetector(requestPool, progress, STANDDOWN_AFTER,
                        registry, clock::get));
        AtomicInteger lost = new AtomicInteger();
        zombie.onLeadershipLost(lost::incrementAndGet);

        assertTrue(zombie.tryAcquire(), "만료 리스 승격 실패");
        long epoch = zombie.requireLeader().epoch();
        assertTrue(zombie.renew(), "포화 전 하트비트가 실패했다");

        try {
            saturateRequestPool();
            // 진행 축은 여기서부터 고정한다. 커밋이 한 건도 나지 않는 상태의 재현이다.

            assertTrue(zombie.renew(), "정지 첫 표본에서 곧바로 강등하면 안 된다");
            clock.addAndGet(Duration.ofSeconds(20).toNanos());
            assertTrue(zombie.renew(), "임계 미만 정지에서 강등하면 안 된다");

            long leaseEpochBefore = leaseEpoch();
            String holderBefore = leaseHolder();
            OffsetDateTime leaseUntilBefore = leaseUntil();

            clock.addAndGet(Duration.ofSeconds(30).toNanos());
            assertFalse(zombie.renew(), "서비스 불능이 임계를 넘겼는데 갱신을 계속했다");

            assertFalse(zombie.isLeader(), "자발적 강등 후에도 리더로 남았다");
            assertEquals(1, lost.get(), "상실 콜백이 정확히 한 번 발화해야 한다");
            // 자발적 중단은 리스 행에 쓰지 않는다. 승격은 종전대로 만료가 구동한다.
            assertEquals(leaseEpochBefore, leaseEpoch(), "강등이 리스 epoch를 건드렸다");
            assertEquals(holderBefore, leaseHolder(), "강등이 리스 holder를 건드렸다");
            assertEquals(leaseUntilBefore, leaseUntil(), "강등이 lease_until을 건드렸다");
            assertFalse(zombie.tryAcquire(), "강등된 노드가 재승격되면 안 된다");
            assertEquals(1.0, registry.get("exchange_leader_standdown").counter().count());
            assertEquals(0.0, registry.get("exchange_node_serviceable").gauge().value());

            LeaderLeaseService standby = new LeaderLeaseService(leaseJdbc,
                    Duration.ofSeconds(30), Duration.ofSeconds(1));
            assertFalse(standby.tryAcquire(), "만료 전 탈취가 허용되면 fencing이 무너진다");
            Awaitility.await().atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(200))
                    .until(standby::tryAcquire);
            assertEquals(epoch + 1, standby.requireLeader().epoch(), "승격은 정확히 epoch +1");
            assertEquals(standby.holder(), leaseHolder());
        } finally {
            releaseRequestPool();
            zombie.close();
        }
    }

    @Test
    void transientSaturationAndBriefStallKeepLeadership() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderLeaseService leader = new LeaderLeaseService(leaseJdbc,
                Duration.ofSeconds(30), Duration.ofSeconds(1),
                new RequestPoolStallDetector(requestPool, progress, STANDDOWN_AFTER,
                        registry, clock::get));
        AtomicInteger lost = new AtomicInteger();
        leader.onLeadershipLost(lost::incrementAndGet);

        assertTrue(leader.tryAcquire(), "만료 리스 승격 실패");
        long epoch = leader.requireLeader().epoch();

        try {
            saturateRequestPool();

            // ① 포화가 아무리 오래 가도 커밋이 나면 강등하지 않는다. E1(pg 지연)에서 리더가
            //    느려질 뿐 일은 계속하는 상황이 이 분기다. 임계의 6배를 흘려도 유지돼야 한다.
            for (int i = 0; i < 6; i++) {
                progress.recordCommit();
                clock.addAndGet(Duration.ofSeconds(45).toNanos());
                assertTrue(leader.renew(), "진행이 있는 포화에서 강등하면 안 된다");
            }

            // ② 커밋이 멈춰도 임계 미만이면 유지한다.
            clock.addAndGet(Duration.ofSeconds(10).toNanos());
            assertTrue(leader.renew(), "정지 첫 표본에서 강등하면 안 된다");
            clock.addAndGet(Duration.ofSeconds(40).toNanos());
            assertTrue(leader.renew(), "임계 미만 정지에서 강등하면 안 된다");

            // ③ 포화가 풀리면 누적이 초기화된다. 지속이 아닌 반복 포화로는 강등되지 않는다.
            releaseRequestPool();
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            assertTrue(leader.renew(), "포화 해소 표본에서 강등하면 안 된다");

            saturateRequestPool();
            assertTrue(leader.renew(), "재포화 첫 표본에서 강등하면 안 된다");
            clock.addAndGet(Duration.ofSeconds(40).toNanos());
            assertTrue(leader.renew(), "누적이 초기화되지 않아 임계 미만에서 강등했다");

            assertTrue(leader.isLeader(), "헛페일오버가 발생했다");
            assertEquals(epoch, leader.requireLeader().epoch(), "유지 중 epoch가 변하면 안 된다");
            assertEquals(epoch, leaseEpoch(), "유지 중 리스 epoch가 변하면 안 된다");
            assertEquals(0, lost.get(), "상실 콜백이 발화하면 안 된다");
            assertEquals(0.0, registry.get("exchange_leader_standdown").counter().count());
            assertEquals(1.0, registry.get("exchange_node_serviceable").gauge().value());
        } finally {
            releaseRequestPool();
            leader.close();
        }
    }

    private void saturateRequestPool() throws Exception {
        // 실 카오스의 다중행 쿼리 폭주 대신 커넥션을 직접 붙들어 전량 점유를 결정적으로 만든다.
        for (int i = held.size(); i < requestPool.getMaximumPoolSize(); i++) {
            held.add(requestPool.getConnection());
        }
        assertEquals(requestPool.getMaximumPoolSize(),
                requestPool.getHikariPoolMXBean().getActiveConnections(),
                "요청 풀이 포화되지 않았다");
    }

    private void releaseRequestPool() throws Exception {
        for (Connection c : held) {
            c.close();
        }
        held.clear();
    }

    private static String leaseHolder() {
        return probeJdbc.sql("SELECT holder FROM leader_lease WHERE resource = 'matching-engine'")
                .query(String.class).single();
    }

    private static long leaseEpoch() {
        return probeJdbc.sql("SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
                .query(Long.class).single();
    }

    private static OffsetDateTime leaseUntil() {
        return probeJdbc.sql("SELECT lease_until FROM leader_lease WHERE resource = 'matching-engine'")
                .query(OffsetDateTime.class).single();
    }

    private static void expireLease() {
        probeJdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
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

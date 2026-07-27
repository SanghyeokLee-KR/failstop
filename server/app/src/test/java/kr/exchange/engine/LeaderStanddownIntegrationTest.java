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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 좀비 리더 자발적 강등의 리스 계층 계약(DB 설계 §5.4).
 *
 * 992e59b의 전용 풀 격리로 하트비트 생존성이 서비스 생존성과 분리된 뒤, 요청 경로가 멈춘 노드가
 * 리스만 갱신해 스탠바이 승격을 영구히 막는 좀비가 생겼다. 여기서 고정하는 것은 그 노드가 갱신을
 * 포기하는 것, 포기가 리스 행을 건드리지 않는 것, 그리고 승격이 종전대로 만료 구동 epoch+1로만
 * 성립하는 것이다.
 *
 * 정지 재현에 커넥션 풀 포화를 쓰지 않는다. 카오스 라이브의 실제 좀비는 커넥션 대기가 아니라 CPU
 * 폭주(463%)로 죽어 있었고, 풀 점유를 판정 조건에 넣었던 직전 설계는 그래서 한 번도 발동하지
 * 않았다. 판정은 원인이 아니라 증상(수요는 오는데 완료가 없다)만 본다.
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

    @BeforeAll
    static void setUp() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        requestPool = pool("request-pool", 8, 1000);
        // 풀을 실제로 초기화해야 MXBean이 생긴다. 점유 0 단언이 관측에 근거하도록 예열한다.
        try (Connection warmUp = requestPool.getConnection()) {
            assertTrue(warmUp.isValid(2));
        }
        leasePool = pool("exchange-lease", 2, 2000);
        // 리스 조작·검증은 요청 경로 상태와 무관해야 하므로 전용 풀로만 한다.
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
    void stalledNodeStopsRenewingAndStandbyTakesOverWithNextEpoch() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderLeaseService zombie = new LeaderLeaseService(leaseJdbc,
                Duration.ofSeconds(5), Duration.ofMillis(200),
                new LeaderStallDetector(requestPool, progress, STANDDOWN_AFTER,
                        registry, clock::get));
        AtomicInteger lost = new AtomicInteger();
        zombie.onLeadershipLost(lost::incrementAndGet);

        assertTrue(zombie.tryAcquire(), "만료 리스 승격 실패");
        long epoch = zombie.requireLeader().epoch();
        assertTrue(zombie.renew(), "정지 전 하트비트가 실패했다");

        try {
            // 커넥션 풀은 멀쩡하다(점유 0/8). 쓰기 요청은 도착하는데 아무것도 끝나지 않는 상태다.
            // 수요 계측이 실제로 도달 가능한지는 별도로 고정한다
            // (api.LeaderWriteDemandReachabilityIntegrationTest).
            assertEquals(0, requestPool.getHikariPoolMXBean().getActiveConnections(),
                    "풀 포화가 강등의 전제로 남아 있으면 CPU 기아형 좀비를 놓친다");

            // 완료가 0이 아니라 희박한 상태다. 라이브 좀비는 5분에 체결 1건을 내고 있었고,
            // 완료 1건이 누적을 초기화하던 규칙은 그래서 무력화됐다.
            demand(progress, 50);
            progress.recordCompletion();
            assertTrue(zombie.renew(), "붕괴 첫 표본에서 곧바로 강등하면 안 된다");
            demand(progress, 50);
            progress.recordCompletion();
            clock.addAndGet(Duration.ofSeconds(20).toNanos());
            assertTrue(zombie.renew(), "임계 미만 붕괴에서 강등하면 안 된다");

            long leaseEpochBefore = leaseEpoch();
            String holderBefore = leaseHolder();
            OffsetDateTime leaseUntilBefore = leaseUntil();

            // 수요가 끊겨도 누적은 유지된다(hold). LB가 실패한 리더를 업스트림에서 빼면 실제로
            // 이렇게 되고, 수요 없음을 초기화로 두면 그 순간 판정이 무력화된다.
            clock.addAndGet(Duration.ofSeconds(30).toNanos());
            assertFalse(zombie.renew(), "처리율 붕괴가 임계를 넘겼는데 갱신을 계속했다");

            assertFalse(zombie.isLeader(), "자발적 강등 후에도 리더로 남았다");
            assertEquals(1, lost.get(), "상실 콜백이 정확히 한 번 발화해야 한다");
            // 자발적 중단은 리스 행에 쓰지 않는다. 승격은 종전대로 만료가 구동한다.
            assertEquals(leaseEpochBefore, leaseEpoch(), "강등이 리스 epoch를 건드렸다");
            assertEquals(holderBefore, leaseHolder(), "강등이 리스 holder를 건드렸다");
            assertEquals(leaseUntilBefore, leaseUntil(), "강등이 lease_until을 건드렸다");
            assertFalse(zombie.tryAcquire(), "강등된 노드가 재승격되면 안 된다");
            assertEquals(1.0, registry.get("exchange.leader.standdown").counter().count());
            assertEquals(0.0, registry.get("exchange.node.serviceable").gauge().value());

            LeaderLeaseService standby = new LeaderLeaseService(leaseJdbc,
                    Duration.ofSeconds(30), Duration.ofSeconds(1));
            assertFalse(standby.tryAcquire(), "만료 전 탈취가 허용되면 fencing이 무너진다");
            Awaitility.await().atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(200))
                    .until(standby::tryAcquire);
            assertEquals(epoch + 1, standby.requireLeader().epoch(), "승격은 정확히 epoch +1");
            assertEquals(standby.holder(), leaseHolder());
        } finally {
            zombie.close();
        }
    }

    @Test
    void idleAndCompletingNodesKeepLeadership() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LeaderWriteProgress progress = new LeaderWriteProgress();
        AtomicLong clock = new AtomicLong();
        LeaderLeaseService leader = new LeaderLeaseService(leaseJdbc,
                Duration.ofSeconds(30), Duration.ofSeconds(1),
                new LeaderStallDetector(requestPool, progress, STANDDOWN_AFTER,
                        registry, clock::get));
        AtomicInteger lost = new AtomicInteger();
        leader.onLeadershipLost(lost::incrementAndGet);

        assertTrue(leader.tryAcquire(), "만료 리스 승격 실패");
        long epoch = leader.requireLeader().epoch();

        try {
            // ① 느려도 완료율이 유지되는 노드는 임계의 6배를 흘려도 유지된다. E1(pg 지연) 전제다.
            for (int i = 0; i < 6; i++) {
                demand(progress, 50);
                for (int c = 0; c < 45; c++) {
                    progress.recordCompletion();
                }
                clock.addAndGet(Duration.ofSeconds(45).toNanos());
                assertTrue(leader.renew(), "완료율이 건강한 노드에서 강등하면 안 된다");
            }

            // ② 주문이 오지 않는 유휴 구간. 수요 하한이 없으면 여기서 정상 리더가 영구 강등된다.
            for (int i = 0; i < 6; i++) {
                clock.addAndGet(Duration.ofSeconds(45).toNanos());
                assertTrue(leader.renew(), "쓰기 수요 0(무주문) 구간에서 강등하면 안 된다");
            }

            // ③ 완료율이 잠시 무너져도 임계 미만이면 유지한다.
            demand(progress, 50);
            clock.addAndGet(Duration.ofSeconds(10).toNanos());
            assertTrue(leader.renew(), "붕괴 첫 표본에서 강등하면 안 된다");
            clock.addAndGet(Duration.ofSeconds(30).toNanos());
            assertTrue(leader.renew(), "임계 미만 붕괴에서 강등하면 안 된다");

            // ④ 완료율이 임계를 회복하면 누적이 초기화된다. 일시 하락으로는 강등되지 않는다.
            for (int c = 0; c < 20; c++) {
                progress.recordCompletion();
            }
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            assertTrue(leader.renew(), "완료율 회복 표본에서 강등하면 안 된다");
            demand(progress, 50);
            clock.addAndGet(Duration.ofSeconds(40).toNanos());
            assertTrue(leader.renew(), "누적이 초기화되지 않아 임계 미만에서 강등했다");

            assertTrue(leader.isLeader(), "헛페일오버가 발생했다");
            assertEquals(epoch, leader.requireLeader().epoch(), "유지 중 epoch가 변하면 안 된다");
            assertEquals(epoch, leaseEpoch(), "유지 중 리스 epoch가 변하면 안 된다");
            assertEquals(0, lost.get(), "상실 콜백이 발화하면 안 된다");
            assertEquals(0.0, registry.get("exchange.leader.standdown").counter().count());
            assertEquals(1.0, registry.get("exchange.node.serviceable").gauge().value());
        } finally {
            leader.close();
        }
    }

    private static void demand(LeaderWriteProgress progress, int count) {
        for (int i = 0; i < count; i++) {
            progress.recordAttempt();
        }
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

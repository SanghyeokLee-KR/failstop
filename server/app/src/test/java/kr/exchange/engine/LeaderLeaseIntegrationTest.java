package kr.exchange.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 리스 생명주기(M1-1 (a)): 만료 리스 획득, epoch 증가, 하트비트 갱신, 유효 리스 탈취 불가,
 * 상실 감지 콜백. 테스트 간 리스 행이 공유 자원이라 순서를 고정한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LeaderLeaseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static LeaderLeaseService first;
    // 1에서 얻은 기간. 6에서 재획득 뒤 옛 기간의 쓰기가 거부되는지 본다.
    static LeaderTerm firstTerm;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        first = new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void acquiresExpiredBootstrapLeaseAndBumpsEpoch() {
        // V1 시드는 epoch 0, 만료 상태. 첫 승격은 즉시 성립하고 epoch는 1이 된다.
        assertFalse(first.isLeader());
        assertThrows(NotLeaderException.class, first::requireLeader);

        assertTrue(first.tryAcquire(), "만료 시드 리스 승격 실패");
        assertTrue(first.isLeader());
        assertEquals(1, first.requireLeader().epoch());
        firstTerm = first.currentTerm().orElseThrow();
        assertEquals(first.holder(), leaseHolder());
    }

    @Test
    @Order(2)
    void validLeaseCannotBeStolen() {
        LeaderLeaseService intruder =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        assertFalse(intruder.tryAcquire(), "유효 리스가 탈취되면 안 된다");
        assertFalse(intruder.isLeader());
        assertEquals(first.holder(), leaseHolder());
        assertEquals(1, leaseEpoch());
    }

    @Test
    @Order(3)
    void heartbeatExtendsLeaseAndPreservesEpoch() {
        OffsetDateTime before = leaseUntil();
        assertTrue(first.renew(), "유효 리스 하트비트 실패");
        assertFalse(leaseUntil().isBefore(before), "갱신 후 lease_until이 되돌아갔다");
        // 갱신은 epoch 보존이 계약이다. 올라가면 진행 중 append가 자기-fencing된다(§5.4).
        assertEquals(1, leaseEpoch());
        assertEquals(1, first.requireLeader().epoch());
    }

    @Test
    @Order(4)
    void takeoverAfterExpiryBumpsEpochAndOldLeaderDetectsLoss() {
        AtomicInteger lossCount = new AtomicInteger();
        first.onLeadershipLost(lossCount::incrementAndGet);

        expireLease();
        LeaderLeaseService successor =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        assertTrue(successor.tryAcquire(), "만료 리스 탈취 실패");
        assertEquals(2, successor.requireLeader().epoch(), "탈취는 epoch +1");

        // 구 리더의 갱신은 거부되고 상실 콜백이 발화한다.
        assertFalse(first.renew());
        assertFalse(first.isLeader());
        assertEquals(1, lossCount.get());
        assertThrows(NotLeaderException.class, first::requireLeader);
    }

    @Test
    @Order(5)
    void scheduledLoopAcquiresAndKeepsLeadership() {
        expireLease();
        LeaderLeaseService scheduled =
                new LeaderLeaseService(jdbc, Duration.ofMillis(700), Duration.ofMillis(150));
        try {
            scheduled.start();
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(scheduled::isLeader);
            long epoch = scheduled.requireLeader().epoch();
            // TTL(700ms)을 넘겨도 하트비트가 리스를 유지해야 한다.
            Awaitility.await().pollDelay(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3))
                    .until(() -> true);
            assertTrue(scheduled.isLeader(), "하트비트가 리스를 유지하지 못했다");
            assertEquals(epoch, scheduled.requireLeader().epoch(), "유지 중 epoch가 변하면 안 된다");
        } finally {
            scheduled.stop();
        }
    }

    @Test
    @Order(6)
    void demotedServiceDoesNotReacquireUntilRearmed() {
        // 4에서 상실 확정된 first는 리스가 만료돼도 다시 얻지 않는다(DB 설계 §5.4-c 5). 강등 시점의 엔진 상태는
        // stale이고 라이터는 멈췄다. 파티션 수명주기가 fresh 재구성을 마치고 rearm 해야 후보로 돌아온다.
        expireLease();
        assertFalse(first.tryAcquire(), "재구성 전 재획득 없음: 강등한 서비스가 rearm 전에 리스를 다시 얻었다");
        assertFalse(first.isLeader());
        assertFalse(first.isArmed(), "강등이 후보 표식을 내리지 않았다");
        assertThrows(NotLeaderException.class, first::requireLeader);

        // 다른 인스턴스의 승격은 열려 있어야 한다.
        LeaderLeaseService fresh =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        assertTrue(fresh.tryAcquire(), "신규 인스턴스 승격이 막히면 안 된다");
        long freshEpoch = fresh.requireLeader().epoch();

        // 재구성을 마쳐 rearm 하면 후보로 돌아온다. 유효한 리스는 여전히 빼앗지 못하고 만료 뒤에만 더 큰 epoch로 얻는다.
        assertTrue(first.rearm(), "노드 치명이 아닌 강등은 rearm 으로 후보에 돌아와야 한다");
        assertFalse(first.tryAcquire(), "rearm 한 서비스가 유효한 리스를 빼앗았다");
        expireLease();
        assertTrue(first.tryAcquire(), "rearm 한 서비스가 만료 리스를 얻지 못했다");
        assertTrue(first.requireLeader().epoch() > freshEpoch, "재획득 epoch가 앞 리더보다 커야 한다");
        assertEquals(first.holder(), leaseHolder());
        // 같은 서비스가 다시 얻어도 강등 전 기간은 끝났다. 그 기간을 쥔 쓰기는 앱에서 거부되고, 옛 epoch 는 DB 판정도 통과하지 못한다.
        assertFalse(firstTerm.isCurrent());
        assertThrows(NotLeaderException.class, firstTerm::requireCurrent);
        assertThrows(RuntimeException.class, () -> jdbc.sql("SELECT assert_current_leader(?, ?)")
                .params(first.holder(), firstTerm.epoch()).query(Object.class).optional());
    }

    @Test
    @Order(7)
    void nodeFatalIsNotRearmable() {
        // VM 오류 뒤의 프로세스는 불건전하다(§5.4-c 5 NODE_FATAL). rearm 이 풀지 않고 재기동으로만 복귀한다.
        assertTrue(first.isLeader());
        first.markNodeFatal("시험: VM 오류");
        assertFalse(first.isLeader());
        assertTrue(first.isNodeFatal());
        assertFalse(first.rearm(), "노드 치명을 rearm 이 풀었다");
        expireLease();
        assertFalse(first.tryAcquire(), "노드 치명 서비스가 리스를 다시 얻었다");
    }

    @Test
    @Order(8)
    void 오래_만료된_리스를_주워도_페일오버_다운타임으로_세지_않는다() {
        // 리스 행은 볼륨에 남아 스택 종료를 넘긴다. 하한이 없으면 꺼 둔 시간이 통째로
        // 다운타임이 되어 RTO 알람이 기동마다 오발화한다.
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '36 hours' "
                        + "WHERE resource = 'matching-engine'")
                .update();

        LeaderLeaseService coldStart =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        AtomicReference<Double> reported = new AtomicReference<>();
        coldStart.onLeadershipAcquired((identity, downtime) -> reported.set(downtime));

        assertTrue(coldStart.tryAcquire(), "만료 리스는 획득할 수 있어야 한다");
        assertNotNull(reported.get(), "승격 통지가 오지 않았다");
        assertTrue(reported.get() < 120,
                "감시 시작 이전 구간을 다운타임으로 셌다: " + reported.get() + "s");
    }

    @Test
    @Order(9)
    void 감시_중이던_노드의_실제_페일오버는_그대로_잰다() {
        LeaderLeaseService standby =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        AtomicReference<Double> reported = new AtomicReference<>();
        standby.onLeadershipAcquired((identity, downtime) -> reported.set(downtime));

        // 유효 리스를 세워 감시만 하게 만든다. 이 실패한 시도가 감시 시작 시각을 잡는다.
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() + interval '60 seconds' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertFalse(standby.tryAcquire(), "유효 리스는 탈취할 수 없다");

        // 그 뒤 리더가 죽어 리스가 20초 전에 만료됐다.
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '20 seconds' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(standby.tryAcquire(), "만료 후에는 승격해야 한다");
        assertNotNull(reported.get(), "승격 통지가 오지 않았다");
        assertTrue(reported.get() >= 20,
                "실제 페일오버 구간이 하한에 깎였다: " + reported.get() + "s");
    }

    private static String leaseHolder() {
        return jdbc.sql("SELECT holder FROM leader_lease WHERE resource = 'matching-engine'")
                .query(String.class).single();
    }

    private static long leaseEpoch() {
        return jdbc.sql("SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
                .query(Long.class).single();
    }

    private static OffsetDateTime leaseUntil() {
        return jdbc.sql("SELECT lease_until FROM leader_lease WHERE resource = 'matching-engine'")
                .query(OffsetDateTime.class).single();
    }

    private static void expireLease() {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
    }
}

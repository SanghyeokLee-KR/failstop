package kr.exchange.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.lease.LeaseResources;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 쓰기 가능 탐지와 커밋 대기(DB 설계 §5.4-f, Patroni 설계 10절 시험). 대기 서버가 없는 동기 설정을 한 노드에 걸어 커밋 대기를
 * 만든다. synchronous_standby_names 에 붙지 않은 이름을 걸면 모든 동기 커밋이 SyncRep 에서 기다리고, '*' 를 걸면 Patroni 가
 * 동기 후보를 잃었을 때와 같은 설정이 된다. 두 노드에서 대기 서버를 멈추는 장면은 SyncStandbyLossIntegrationTest 가 본다.
 */
@Testcontainers
class WriteAvailabilityIntegrationTest {

    // 붙은 대기 서버가 없는 동기 대상. 모든 동기 커밋이 기다린다.
    private static final String GHOST = "ghost";
    private static final WriteAvailability.Settings SETTINGS = WriteAvailability.Settings.DEFAULTS;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private WriteProbe probe;

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
        config.setMaximumPoolSize(6);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE TABLE IF NOT EXISTS wa_probe_rows (id bigint PRIMARY KEY)").update();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @AfterEach
    void restore() throws Exception {
        setSyncStandbys("");
        if (probe != null) {
            probe.close();
        }
        pool.shutdownNow();
    }

    private static void setSyncStandbys(String value) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement st = c.createStatement()) {
            st.execute("ALTER SYSTEM SET synchronous_standby_names = '" + value + "'");
            st.execute("SELECT pg_reload_conf()");
        }
    }

    private WriteAvailability startProbe() {
        WriteAvailability availability = WriteAvailability.enabled(SETTINGS);
        probe = new WriteProbe(availability,
                WriteProbe.driverManager(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                        Duration.ofSeconds(2)),
                Duration.ofMillis(500), Duration.ofMillis(300), Duration.ofSeconds(60), Duration.ofSeconds(2), null);
        probe.start();
        Awaitility.await("첫 탐지 두 번과 설정 읽기").atMost(Duration.ofSeconds(15)).until(availability::isActive);
        return availability;
    }

    private static long syncRepWaiters(String applicationName) {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'SyncRep' AND application_name = ?")
                .param(applicationName)
                .query(Long.class)
                .single();
    }

    @Test
    void missingStandbySuspendsOnSyncRepAndResumesWhenCommitsReturn() throws Exception {
        WriteAvailability availability = startProbe();
        AtomicInteger resumed = new AtomicInteger();
        availability.onResume(resumed::incrementAndGet);
        long gate = availability.generation();

        setSyncStandbys(GHOST);
        Awaitility.await("탐지 백엔드의 SyncRep 대기 두 번").atMost(Duration.ofSeconds(10))
                .until(availability::isSuspended);
        assertEquals(WriteAvailability.REASON_REPLICATION, availability.view().reason());
        assertEquals(1, syncRepWaiters(WriteProbe.PROBE_APPLICATION), "커밋을 기다리는 탐지 백엔드는 하나다");
        assertFalse(availability.stillActive(gate));

        // 문장 시간 제한은 커밋 대기를 끊지 않는다(설계 2-6 A). pgjdbc 확장 질의로도 같은지 본다.
        Future<Integer> blocked = pool.submit(() -> {
            try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()); Statement st = c.createStatement()) {
                st.execute("SET statement_timeout = '1s'");
                try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO wa_probe_rows VALUES (?)")) {
                    ps.setLong(1, 1);
                    return ps.executeUpdate();
                }
            }
        });
        assertThrows(TimeoutException.class, () -> blocked.get(3, TimeUnit.SECONDS),
                "statement_timeout 이 커밋 대기를 끊었다");

        setSyncStandbys("");
        assertEquals(1, blocked.get(10, TimeUnit.SECONDS), "대기 서버 조건이 풀리면 기다리던 커밋이 끝난다");
        Awaitility.await("밀린 탐지 뒤 빠른 탐지 두 번").atMost(Duration.ofSeconds(10)).until(availability::isActive);
        assertEquals(1, resumed.get());
        assertEquals(gate + 1, availability.generation());
        assertTrue(availability.requeryGuarded(), "재개 직후는 재조회 유예 기간이다");
    }

    @Test
    void anyStandbySettingSuspendsEvenBeforeTheProbeIsOverdue() throws Exception {
        WriteAvailability availability = startProbe();

        setSyncStandbys("*");
        Awaitility.await("확인 연결이 '*' 를 읽음").atMost(Duration.ofSeconds(5)).until(availability::isSuspended);
        assertEquals(WriteAvailability.REASON_REPLICATION, availability.view().reason());

        setSyncStandbys("");
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(availability::isActive);
    }

    @Test
    void closeEndsTheProbeBackendThatIsStillWaiting() throws Exception {
        WriteAvailability availability = startProbe();
        setSyncStandbys(GHOST);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(availability::isSuspended);
        assertEquals(1, syncRepWaiters(WriteProbe.PROBE_APPLICATION));

        probe.close();
        probe = null;

        Awaitility.await("종료 때 서버에 남은 탐지 백엔드를 끝낸다").atMost(Duration.ofSeconds(5))
                .until(() -> syncRepWaiters(WriteProbe.PROBE_APPLICATION) == 0);
    }

    @Test
    void crashRestartRaisesUnconfirmedUntilAConfirmedProbe() throws Exception {
        WriteAvailability availability = startProbe();
        assertTrue(availability.definitiveAllowed());
        setSyncStandbys(GHOST);
        Future<Integer> waiting = pool.submit(() -> {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                return st.executeUpdate("INSERT INTO wa_probe_rows VALUES (2)");
            }
        });
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(availability::isSuspended);
        assertEquals(0L, visibleRows(2), "커밋을 기다리는 행은 보이지 않는다");

        // 메모리 부족 종료를 흉내 낸다. 다른 백엔드 하나가 SIGKILL 로 죽으면 postmaster 가 크래시 재시작을 한다(설계 2-6 Q1).
        int victim;
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            victim = rs.getInt(1);
            POSTGRES.execInContainer("kill", "-9", String.valueOf(victim));
        } catch (SQLException expected) {
            // 크래시 재시작이 이 연결도 끊는다.
        }
        assertThrows(Exception.class, () -> waiting.get(20, TimeUnit.SECONDS), "기다리던 클라이언트는 연결을 잃는다");
        Awaitility.await("크래시 복구 뒤 기다리던 행이 복제 없이 보인다").atMost(Duration.ofSeconds(30))
                .ignoreExceptions().until(() -> visibleRows(2) == 1L);

        Awaitility.await("다시 붙은 탐지 연결이 재확인 전 표시를 세운다").atMost(Duration.ofSeconds(30))
                .until(() -> availability.view().unconfirmed());
        assertFalse(availability.definitiveAllowed(), "다시 붙은 뒤 확인된 탐지 전에는 확정 응답을 내지 않는다");

        setSyncStandbys("");
        Awaitility.await("다시 붙은 뒤의 탐지가 끝나고 설정이 '*' 가 아님").atMost(Duration.ofSeconds(15))
                .until(availability::definitiveAllowed);
    }

    @Test
    void leaseRenewCommitsLocallyWhileSynchronousCommitsWait() throws Exception {
        PostgresLeaderLeaseStore store = new PostgresLeaderLeaseStore(dataSource, LeaseResources.MATCHING_ENGINE,
                LeaseResources.MAX_PROTOCOL, Duration.ofSeconds(1));
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        long epoch = store.acquire("renewer", Duration.ofSeconds(30)).orElseThrow();
        PostgresLeaderLeaseStore single = new PostgresLeaderLeaseStore(jdbc);

        setSyncStandbys(GHOST);
        Future<Boolean> renewed = pool.submit(() -> store.renew("renewer", epoch, Duration.ofSeconds(30)));
        Future<Boolean> renewedSingle = pool.submit(() -> single.renew("renewer", epoch, Duration.ofSeconds(30)));
        assertTrue(renewed.get(5, TimeUnit.SECONDS), "갱신이 동기 대기에 걸리면 리스가 만료된다(2026-09-08 연쇄)");
        assertTrue(renewedSingle.get(5, TimeUnit.SECONDS), "자동 커밋 한 문장 갱신도 로컬 커밋이다");
        assertTrue(store.holdsUnexpired("renewer", epoch));
    }

    @Test
    void acquisitionReleasedAfterItsTtlIsDiscardedAndTheNextOneBumpsTheEpoch() throws Exception {
        PostgresLeaderLeaseStore store = new PostgresLeaderLeaseStore(dataSource, LeaseResources.MATCHING_ENGINE,
                LeaseResources.MAX_PROTOCOL, Duration.ofSeconds(1));
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        long before = jdbc.sql("SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
                .query(Long.class).single();
        // 게이트가 닫히기 직전에 시작한 획득을 흉내 낸다. 탐지기는 아직 ACTIVE 다.
        WriteAvailability gate = WriteAvailability.enabled(SETTINGS);
        gate.probeConnected();
        for (int i = 1; i <= 2; i++) {
            gate.probeSent(i);
            gate.probeCompleted(i);
        }
        Thread.sleep(2);
        gate.settingRead("", false);
        assertTrue(gate.isActive());
        LeaderLeaseService lease = new LeaderLeaseService(LeaseResources.MATCHING_ENGINE, "acquirer", store,
                kr.exchange.engine.lease.FencingEpochPublisher.NONE, Duration.ofSeconds(2), Duration.ofMillis(500),
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress());
        lease.gateAcquisition(gate);

        setSyncStandbys(GHOST);
        Future<Boolean> acquired = pool.submit(lease::tryAcquire);
        Thread.sleep(3_000);
        assertFalse(acquired.isDone(), "획득 커밋이 동기 대기에 걸려야 시험이 공허하지 않다");
        setSyncStandbys("");

        assertFalse(acquired.get(10, TimeUnit.SECONDS), "커밋 대기 뒤 풀린 획득은 이미 만료라 승격하지 않는다");
        assertFalse(lease.isLeader());
        assertEquals(1, lease.expiredAcquisitions());
        assertEquals(before + 1, jdbc.sql("SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
                .query(Long.class).single());

        assertTrue(lease.tryAcquire(), "다음 획득은 살아 있는 리스를 받는다");
        assertEquals(before + 2, lease.currentTerm().orElseThrow().epoch(), "버린 획득 다음은 epoch 이 한 번 더 오른다");
    }

    @Test
    void acquisitionIsAttemptedOnlyWhileActive() {
        PostgresLeaderLeaseStore store = new PostgresLeaderLeaseStore(dataSource, LeaseResources.MATCHING_ENGINE,
                LeaseResources.MAX_PROTOCOL, Duration.ofSeconds(1));
        // 다른 노드가 쥐었다 만료된 리스. 획득 게이트가 없으면 첫 시도에서 곧바로 성립한다.
        jdbc.sql("UPDATE leader_lease SET holder = 'other-node', lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        long before = matchingEngineEpoch();
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        WriteAvailability gate = WriteAvailability.enabled(SETTINGS, nanos::get, System::currentTimeMillis);
        LeaderLeaseService lease = new LeaderLeaseService(LeaseResources.MATCHING_ENGINE, "gated", store,
                kr.exchange.engine.lease.FencingEpochPublisher.NONE, Duration.ofSeconds(2), Duration.ofMillis(500),
                NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress());
        lease.gateAcquisition(gate);

        assertEquals(WriteAvailability.State.UNREACHABLE, gate.view().state(), "첫 확인 탐지 전");
        assertFalse(lease.tryAcquire());
        gate.settingRead("*", false);
        assertEquals(WriteAvailability.State.SUSPENDED, gate.view().state());
        assertFalse(lease.tryAcquire());
        assertEquals(before, matchingEngineEpoch(), "ACTIVE 가 아닌 동안 보관소 획득을 부르지 않는다");
        assertEquals("other-node", jdbc.sql("SELECT holder FROM leader_lease WHERE resource = 'matching-engine'")
                .query(String.class).single());

        gate.probeConnected();
        for (long id = 1; id <= 2; id++) {
            gate.probeSent(id);
            gate.probeCompleted(id);
        }
        nanos.addAndGet(1);
        gate.settingRead("\"patroni-db-2\"", false);
        assertTrue(gate.isActive());
        assertTrue(lease.tryAcquire(), "ACTIVE 면 같은 만료 리스를 얻는다");
        assertEquals(before + 1, matchingEngineEpoch());
    }

    private static long matchingEngineEpoch() {
        return jdbc.sql("SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'").query(Long.class).single();
    }

    private static long visibleRows(long id) {
        return JdbcClient.create(dataSource).sql("SELECT count(*) FROM wa_probe_rows WHERE id = ?")
                .param(id).query(Long.class).single();
    }
}

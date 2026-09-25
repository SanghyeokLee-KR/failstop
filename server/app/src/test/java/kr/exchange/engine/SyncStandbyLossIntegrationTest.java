package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.WriteGateFilter;
import kr.exchange.engine.lease.LeaseResources;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 두 노드 동기 복제에서 대기 서버를 멈췄다 되살린다(Patroni 설계 10절 시험, E-P2a 의 단위판). 대기 서버 이름을 설정에 둔 채
 * 멈추므로 '*' 가 아니라 탐지 백엔드의 SyncRep 대기로 중단을 알아야 한다. 그동안 쓰기 게이트는 423 이고 리스 갱신은 로컬 커밋으로
 * 끝난다. 대기 서버가 돌아오면 기다리던 커밋이 풀리고 탐지기가 사람 조작 없이 ACTIVE 로 돌아간다.
 */
class SyncStandbyLossIntegrationTest {

    private static final String IMAGE = "postgres:17-alpine";
    private static final String STANDBY_NAME = "s1";

    static Network network;
    static PostgreSQLContainer<?> primary;
    static GenericContainer<?> standby;
    static JdbcClient jdbc;

    @BeforeAll
    static void startCluster() {
        network = Network.newNetwork();
        primary = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("exchange")
                .withUsername("exchange")
                .withPassword("exchange")
                .withNetwork(network)
                .withNetworkAliases("primary")
                // 이미지 기본 pg_hba 는 원격 복제 연결을 받지 않는다. 초기화 단계에서 한 줄 더한다.
                .withCopyToContainer(Transferable.of(
                        "echo 'host replication all all scram-sha-256' >> \"$PGDATA/pg_hba.conf\"\n", 0755),
                        "/docker-entrypoint-initdb.d/00-replication.sh");
        primary.start();
        // 데이터 디렉터리는 컨테이너 안에 둔다. docker stop 과 start 사이에는 남아 다시 시작하면 곧바로 대기 서버로 뜬다.
        String boot = """
                if [ ! -s /tmp/pgdata/PG_VERSION ]; then
                  until pg_basebackup -h primary -U exchange -D /tmp/pgdata -X stream; do sleep 1; done
                  touch /tmp/pgdata/standby.signal
                  echo "primary_conninfo = 'host=primary user=exchange password=exchange application_name=%s'" \
                    >> /tmp/pgdata/postgresql.auto.conf
                  chmod 700 /tmp/pgdata
                fi
                exec postgres -D /tmp/pgdata
                """.formatted(STANDBY_NAME);
        standby = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withEnv("PGPASSWORD", "exchange")
                .withCreateContainerCmdModifier(cmd -> cmd.withUser("postgres").withEntrypoint("sh", "-c"))
                // 문자열 하나짜리 withCommand 는 공백으로 쪼갠다. 스크립트를 인자 하나로 넘긴다.
                .withCommand(new String[] {boot})
                .waitingFor(Wait.forLogMessage(".*started streaming WAL from primary.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        standby.start();
        PGSimpleDataSource ds = primaryDataSource();
        jdbc = JdbcClient.create(ds);
        jdbc.sql("ALTER SYSTEM SET synchronous_standby_names = '" + STANDBY_NAME + "'").update();
        jdbc.sql("SELECT pg_reload_conf()").query(Boolean.class).single();
        Awaitility.await("대기 서버가 동기로 잡힘").atMost(Duration.ofSeconds(20)).until(() -> "sync".equals(
                jdbc.sql("SELECT sync_state FROM pg_stat_replication WHERE application_name = ?")
                        .param(STANDBY_NAME).query(String.class).optional().orElse(null)));
        // 리스 함수와 표만 있으면 된다. 마이그레이션 전체를 대기 서버까지 복제한다.
        org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc.sql("CREATE TABLE sync_rows (id bigint PRIMARY KEY)").update();
    }

    @AfterAll
    static void stopCluster() {
        if (standby != null) {
            standby.stop();
        }
        if (primary != null) {
            primary.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void losingTheSyncStandbySuspendsWritesAndItsReturnResumesThem() throws Exception {
        WriteAvailability availability = WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS);
        WriteProbe probe = new WriteProbe(availability,
                WriteProbe.driverManager(primary.getJdbcUrl(), "exchange", "exchange", Duration.ofSeconds(2)),
                Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(60), Duration.ofSeconds(2), null);
        WriteGateFilter gate = new WriteGateFilter(availability, new ErrorRenderer(new ObjectMapper()),
                new LeaderWriteProgress());
        PostgresLeaderLeaseStore lease = new PostgresLeaderLeaseStore(primaryDataSource(),
                LeaseResources.MATCHING_ENGINE, LeaseResources.MAX_PROTOCOL, Duration.ofSeconds(1));
        ExecutorService pool = Executors.newCachedThreadPool();
        DockerClient docker = DockerClientFactory.instance().client();
        probe.start();
        try {
            Awaitility.await("첫 탐지").atMost(Duration.ofSeconds(15)).until(availability::isActive);
            jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                    + "WHERE resource = 'matching-engine'").update();
            long epoch = lease.acquire("sync-node", Duration.ofSeconds(30)).orElseThrow();

            docker.stopContainerCmd(standby.getContainerId()).withTimeout(10).exec();
            long stoppedAt = System.nanoTime();
            Awaitility.await("대기 서버 이름이 설정에 남아 있어도 SyncRep 대기로 알아챈다").atMost(Duration.ofSeconds(10))
                    .until(availability::isSuspended);
            long suspendMillis = (System.nanoTime() - stoppedAt) / 1_000_000;
            assertTrue(suspendMillis <= 5_000, "T_suspend - T0 <= 5초(E-P2a): " + suspendMillis + "ms");
            assertEquals(STANDBY_NAME, jdbc.sql("SELECT current_setting('synchronous_standby_names')")
                    .query(String.class).single());

            MockHttpServletResponse rejected = new MockHttpServletResponse();
            MockFilterChain untouched = new MockFilterChain();
            gate.doFilter(new MockHttpServletRequest("POST", "/api/v1/orders"), rejected, untouched);
            assertEquals(423, rejected.getStatus());

            assertTrue(pool.submit(() -> lease.renew("sync-node", epoch, Duration.ofSeconds(30)))
                    .get(5, TimeUnit.SECONDS), "갱신은 대기 서버 없이 로컬 커밋으로 끝난다");

            Future<Integer> pending = pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(primary.getJdbcUrl(), "exchange", "exchange");
                     Statement st = c.createStatement()) {
                    return st.executeUpdate("INSERT INTO sync_rows VALUES (1)");
                }
            });
            assertThrows(TimeoutException.class, () -> pending.get(2, TimeUnit.SECONDS));

            docker.startContainerCmd(standby.getContainerId()).exec();
            assertEquals(1, pending.get(60, TimeUnit.SECONDS), "대기 서버가 돌아오면 기다리던 커밋이 끝난다");
            Awaitility.await("사람 조작 없이 재개").atMost(Duration.ofSeconds(30)).until(availability::isActive);
            MockFilterChain passed = new MockFilterChain();
            gate.doFilter(new MockHttpServletRequest("POST", "/api/v1/orders"), new MockHttpServletResponse(), passed);
            assertTrue(passed.getRequest() != null, "재개 뒤 게이트가 열린다");
            assertEquals(1L, availability.generation());
            // 대기 서버는 호스트 포트를 열지 않았다. 컨테이너 안의 psql 로 읽는다.
            Awaitility.await("대기 서버에도 같은 행").atMost(Duration.ofSeconds(10)).until(() -> standby.execInContainer(
                    "psql", "-At", "-U", "exchange", "-d", "exchange", "-c", "SELECT count(*) FROM sync_rows")
                    .getStdout().trim().equals("1"));
        } finally {
            probe.close();
            pool.shutdownNow();
        }
    }

    private static PGSimpleDataSource primaryDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(primary.getJdbcUrl());
        ds.setUser("exchange");
        ds.setPassword("exchange");
        return ds;
    }
}

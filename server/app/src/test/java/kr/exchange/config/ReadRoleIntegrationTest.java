package kr.exchange.config;

import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.WsProbe;
import kr.exchange.api.ws.CandleFanout;
import kr.exchange.api.ws.LeaseWatch;
import kr.exchange.api.ws.MarketMirror;
import kr.exchange.api.ws.relay.BrokerLink;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayHeartbeat;
import kr.exchange.api.ws.relay.RelayPublisher;
import kr.exchange.api.ws.relay.RelaySubscriber;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.deploy.SeedMarketCatalog;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.ledger.ReservationBacklogMonitor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles({"prod", "read"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "exchange.cors.allowed-origins=https://exchange.example",
                "app.ws.allowed-origins=https://exchange.example", "exchange.ws.relay.mode=broker"})
class ReadRoleIntegrationTest {

    private static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PASSWORD = "read'password";
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange").withUsername("owner").withPassword("owner")
            .withEnv("DB_USER", "runtime_app").withEnv("DB_PASSWORD", "app'password")
            .withEnv("READ_DB_USER", "runtime_read").withEnv("READ_DB_PASSWORD", PASSWORD)
            .withEnv("BOT_DB_USER", "runtime_bot").withEnv("BOT_DB_PASSWORD", "bot'password");

    static {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "owner", "owner")
                .locations("classpath:db/migration").load().migrate();
        try {
            SeedMarketCatalog.seed(JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "owner", "owner")));
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(root.resolve("deploy/provision-runtime-roles.sql"))) {
                root = root.getParent();
            }
            if (root == null) {
                throw new IllegalStateException("deploy/provision-runtime-roles.sql not found");
            }
            POSTGRES.copyFileToContainer(MountableFile.forHostPath(
                    root.resolve("deploy/provision-runtime-roles.sql")), "/tmp/provision.sql");
            var result = POSTGRES.execInContainer("psql", "-X", "-U", "owner", "-d", "exchange",
                    "-f", "/tmp/provision.sql");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("runtime role provisioning failed");
            }
        } catch (Exception error) {
            throw new IllegalStateException("read role fixture failed", error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "runtime_read");
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("exchange.auth.master-key-base64", () -> SECRET);
        registry.add("exchange.member.password-pepper-base64", () -> SECRET);
        registry.add("exchange.member.session-seed-base64", () -> SECRET);
    }

    @Autowired TestRestTemplate rest;
    @Autowired ApplicationContext context;
    @Autowired PartitionLeases leases;
    @Autowired MeterRegistry meters;
    @LocalManagementPort int managementPort;

    /**
     * 조회 로그인이 읽지 못하는 표를 주기적으로 읽는 빈이 이 노드에 없다(ADR 0034 S3 구현 검토). 정산 전 예약 감시는 저널과
     * 예약 표를 15초마다 읽으므로 여기서 돌면 매번 권한 오류 경고와 NaN 게이지가 남는다. 기동 모드도 읽지 않는다.
     */
    @Test
    void readNodeDoesNotPollTablesItsLoginCannotRead() {
        assertFalse(context.containsBean("reservationBacklogMonitor"), "조회 노드에 정산 전 예약 감시가 떴다");
        // 조회 역할 필터가 쓰기를 먼저 막으므로 소유권 선검사를 걸지 않는다. 인계 작업자는 엔진을 켠 노드의 일이다(S5 설계 4-10).
        assertFalse(context.containsBean("marketRoutingFilter"), "조회 노드에 소유권 선검사가 걸렸다");
        assertFalse(context.containsBean("cancelHandoffWorker"), "조회 노드에 인계 작업자가 떴다");
        assertNull(meters.find("exchange.reservation.backlog.query.failed").counter());
        assertNull(meters.find("exchange.reservation.unsettled.count").gauge());
        LeaderProtocolBoot boot = context.getBean(LeaderProtocolBoot.class);
        assertEquals(1, boot.protocol());
        assertFalse(boot.fromDatabase(), "조회 노드가 leader_protocol 을 읽으려 했다");
        assertNull(meters.find("exchange.leader.protocol.boot").gauge(), "조회 노드가 기동 모드 게이지를 냈다");
        // 바이너리 능력 게이지도 리스를 얻는 노드만 낸다(S7 설계 4-2). 조회 노드는 모드 전환 절차의 확인 대상이 아니다.
        assertNull(meters.find("exchange.leader.protocol.max").gauge(), "조회 노드가 바이너리 능력 게이지를 냈다");

        // 양성 대조: 이 로그인으로 실제 감시 질의와 모드 조회를 돌리면 권한 오류다. 빈을 빼는 이유가 실제로 성립한다.
        DriverManagerDataSource reader = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "runtime_read", PASSWORD);
        ReservationBacklogMonitor probe = new ReservationBacklogMonitor(JdbcClient.create(reader),
                new TransactionTemplate(new JdbcTransactionManager(reader)), new SimpleMeterRegistry());
        probe.refresh();
        assertEquals(1.0, probe.queryFailures(), "조회 로그인이 감시 질의를 읽었다. 빈을 뺀 근거가 없어졌다");
        assertTrue(Double.isNaN(probe.pending()));
        LeaderProtocolBoot direct = LeaderProtocolBoot.read(JdbcClient.create(reader));
        assertFalse(direct.fromDatabase(), "조회 로그인이 leader_protocol 을 읽었다");
    }

    @Test
    void servesDatabaseReadsWithoutJoiningLeadership() {
        var markets = rest.getForEntity("/api/v1/markets", String.class);
        assertEquals(200, markets.getStatusCode().value());
        assertNotNull(markets.getBody());
        assertTrue(markets.getBody().contains("KRW-BTC"));
        assertEquals(200, rest.getForEntity("/api/v1/trades?market=KRW-BTC", String.class).getStatusCode().value());
        assertEquals(200, rest.getForEntity("/api/v1/candles/1m?market=KRW-BTC", String.class).getStatusCode().value());
        assertFalse(context.containsBean("engineRuntimeLifecycle"));
        assertFalse(context.containsBean("followerCatchUp"));
        assertFalse(leases.first().isLeader());
        // 조회 역할의 계획은 DB 를 읽지 않는 matching-engine 하나다(DB 설계 §5.4-c 1). 리스 틱을 시작하지 않는다(§5.4-c 2).
        PartitionPlan plan = context.getBean(PartitionPlan.class);
        assertEquals(java.util.List.of("matching-engine"), plan.partitions());
        assertFalse(plan.partitioned());
        assertFalse(leases.first().started(), "조회 역할 노드가 리스 틱을 시작했다");
        // 틱 스레드 이름(leader-lease-*)으로는 보지 않는다. 같은 JVM 에 캐시된 다른 시험 컨텍스트의 틱 스레드가 섞인다.
        assertTrue(leases.runtimes().isEmpty(), "조회 역할 노드에 파티션 수명주기가 있다");
        assertEquals(200, rest.getForEntity("http://localhost:" + managementPort
                + "/actuator/health/read", String.class).getStatusCode().value());
    }

    @Test
    void rejectsUnexpectedRoutesBeforeAuthenticationOrDatabaseWrites() {
        for (String path : new String[]{"/api/v1/ticker", "/api/v1/orderbook", "/ws/v1", "/admin/v1/sim",
                "/api/v1/member/login", "/api/v1/orders"}) {
            assertEquals(404, rest.getForEntity(path, String.class).getStatusCode().value(), path);
            assertEquals(404, rest.postForEntity(path, "{}", String.class).getStatusCode().value(), path);
        }
    }

    /**
     * 조회 노드는 WS 게시가 없으므로 중계 모드를 broker 로 주어도 중계 빈과 리스 조회기를 만들지 않는다(S6 설계 4-12). 리스 조회기가
     * 돌면 조회 로그인이 읽지 못하는 leader_lease 를 1초마다 읽는다. /ws/v1 은 WS 업그레이드 요청이어도 계속 404 다.
     */
    @Test
    void readNodeBuildsNoRelayAndKeepsWebSocketNotFound() throws Exception {
        for (Class<?> relay : java.util.List.of(RelayPublisher.class, BrokerLink.class, RelaySubscriber.class,
                RelayHeartbeat.class, OwnerBeats.class, MarketMirror.class, CandleFanout.class, LeaseWatch.class)) {
            assertEquals(0, context.getBeanNamesForType(relay).length,
                    "조회 노드에 " + relay.getSimpleName() + " 이(가) 떴다");
        }
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        assertEquals(404, WsProbe.handshake(port, "https://exchange.example").status(),
                "조회 노드가 /ws/v1 업그레이드 요청에 404 가 아니다");
        assertEquals(404, WsProbe.handshake(port, null).status());
    }

    @Test
    void catalogSeedIsRepeatableAndCreatesNoUsersOrFunds() {
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "owner", "owner"));
        SeedMarketCatalog.seed(jdbc);
        assertEquals(13, jdbc.sql("SELECT count(*) FROM assets").query(Integer.class).single());
        assertEquals(12, jdbc.sql("SELECT count(*) FROM markets").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM accounts WHERE owner_type = 'USER'").query(Integer.class).single());
        // 자산마다 샤드 0 셋(FEE_INCOME·GENESIS·GATEWAY)과 V55 트리거가 더한 FEE_INCOME 샤드 1 하나다(ADR 0034 S7, DB 설계 §3.10).
        assertEquals(39, jdbc.sql("SELECT count(*) FROM accounts WHERE owner_type = 'SYSTEM' AND shard = 0")
                .query(Integer.class).single());
        assertEquals(13, jdbc.sql("SELECT count(*) FROM accounts WHERE owner_type = 'SYSTEM' AND shard = 1"
                + " AND purpose = 'FEE_INCOME'").query(Integer.class).single());
        assertEquals(52, jdbc.sql("SELECT count(*) FROM accounts WHERE owner_type = 'SYSTEM'").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM balances WHERE balance <> 0").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM ledger_transactions").query(Integer.class).single());
    }

    @Test
    void readerLoginHasNoTableWritesOrDdlEvenIfSessionReadOnlyIsDisabled() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "runtime_read", PASSWORD);
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SHOW default_transaction_read_only")) {
                assertTrue(result.next());
                assertEquals("on", result.getString(1));
            }
            statement.execute("SET default_transaction_read_only = off");
            SQLException ddl = assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE forbidden_reader_table(id int)"));
            assertEquals("42501", ddl.getSQLState());
            SQLException write = assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE markets SET status = 'HALTED'"));
            assertEquals("42501", write.getSQLState());
            SQLException members = assertThrows(SQLException.class,
                    () -> statement.execute("SELECT * FROM user_credentials"));
            assertEquals("42501", members.getSQLState());
        }
    }
}

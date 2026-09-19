package kr.exchange.config;

import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.deploy.SeedMarketCatalog;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
                "app.ws.allowed-origins=https://exchange.example"})
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
    @Autowired LeaderLeaseService lease;
    @LocalManagementPort int managementPort;

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
        assertFalse(lease.isLeader());
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

    @Test
    void catalogSeedIsRepeatableAndCreatesNoUsersOrFunds() {
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "owner", "owner"));
        SeedMarketCatalog.seed(jdbc);
        assertEquals(13, jdbc.sql("SELECT count(*) FROM assets").query(Integer.class).single());
        assertEquals(12, jdbc.sql("SELECT count(*) FROM markets").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM accounts WHERE owner_type = 'USER'").query(Integer.class).single());
        assertEquals(39, jdbc.sql("SELECT count(*) FROM accounts WHERE owner_type = 'SYSTEM'").query(Integer.class).single());
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

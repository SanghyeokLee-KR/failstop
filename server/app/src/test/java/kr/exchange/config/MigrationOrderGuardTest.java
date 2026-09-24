package kr.exchange.config;

import kr.exchange.deploy.MigrateSchema;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 적용 순서 점검(DB 설계 §10.6). 계속 쓰는 DB 에 V48~V69 없이 V70 이후를 넣으려 하면 멈추고, 빈 DB 와
 * 병합 뒤의 실행 파일은 통과한다. 점검을 부르는 두 진입점(스프링 기동의 FlywayMigrationStrategy, 배포 창의
 * MigrateSchema)이 migrate() 전에 멈추는지도 본다.
 */
@Testcontainers
class MigrationOrderGuardTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static String priorUrl;

    @BeforeAll
    static void priorDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE prior");
            st.execute("CREATE DATABASE fresh_spring");
            st.execute("CREATE DATABASE fresh_cli");
        }
        priorUrl = url("prior");
        // 지금 운영 중인 DB 처럼 V47.1 까지 적용해 둔다.
        Flyway.configure().dataSource(priorUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("47.1").load().migrate();
    }

    @Test
    void V47_1_까지_적용한_DB에_V70_이후만_가진_실행_파일로는_적용하지_않고_멈춘다() {
        Flyway flyway = Flyway.configure().dataSource(priorUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();

        assertThrows(IllegalStateException.class, () -> MigrationOrderGuard.check(flyway));
    }

    @Test
    void 스프링의_적용_전략은_점검이_멈추면_migrate를_부르지_않는다() throws SQLException {
        Flyway flyway = Flyway.configure().dataSource(priorUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();

        assertThrows(IllegalStateException.class, () -> new MigrationOrderConfig().migrationOrderGuard().migrate(flyway));

        assertFalse(tableExists(priorUrl, "ops_thresholds"), "점검이 멈췄으면 V70 표가 생기지 않아야 한다");
        assertEquals("47.1", latestApplied(priorUrl));
    }

    @Test
    void 스프링_기동은_Flyway_자동_구성이_적용_전략을_거쳐_멈추고_빈_DB는_끝까지_적용한다() throws SQLException {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withUserConfiguration(MigrationOrderConfig.class)
                .withPropertyValues("spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword());

        runner.withPropertyValues("spring.datasource.url=" + priorUrl).run(context -> {
            assertNotNull(context.getStartupFailure(), "적용 순서를 어기는 DB 에서는 기동하지 않는다");
            assertTrue(causedByGuard(context.getStartupFailure()), String.valueOf(context.getStartupFailure()));
        });
        assertFalse(tableExists(priorUrl, "ops_thresholds"));

        // 대조: 같은 구성으로 빈 DB 는 기동하고 끝까지 적용한다. 위 실패가 구성 오류가 아니라 점검 때문임을 보인다.
        runner.withPropertyValues("spring.datasource.url=" + url("fresh_spring")).run(context ->
                assertNull(context.getStartupFailure()));
        assertTrue(tableExists(url("fresh_spring"), "ops_thresholds"));
    }

    @Test
    void 배포_창의_MigrateSchema도_점검이_멈추면_적용하지_않고_빈_DB는_끝까지_적용한다() throws SQLException {
        assertThrows(IllegalStateException.class,
                () -> MigrateSchema.migrate(priorUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertFalse(tableExists(priorUrl, "ops_thresholds"));
        assertEquals("47.1", latestApplied(priorUrl));

        MigrateSchema.migrate(url("fresh_cli"), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertTrue(tableExists(url("fresh_cli"), "ops_thresholds"));
    }

    @Test
    void 빈_DB는_통과하고_끝까지_적용된다() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();

        MigrationOrderGuard.check(flyway);
        flyway.migrate();

        assertTrue(tableExists(POSTGRES.getJdbcUrl(), "ops_thresholds"));
    }

    @Test
    void 병합해_V48부터_V69가_실행_파일에_들어오면_풀린다() {
        List<MigrationVersion> applied = versions("0", "47", "47.1");
        assertNotNull(MigrationOrderGuard.refusal(applied, versions("70", "71"), versions("0", "47", "47.1", "70", "71")));
        assertNull(MigrationOrderGuard.refusal(applied, versions("48", "69", "70", "71"),
                versions("0", "47", "47.1", "48", "69", "70", "71")));
        // V70 이 이미 들어간 DB(새로 만든 시연 볼륨)는 더 적용할 V70 이후가 없어 막지 않는다.
        assertNull(MigrationOrderGuard.refusal(versions("0", "47.1", "70", "71"), versions(), versions("70", "71")));
        assertNull(MigrationOrderGuard.refusal(versions(), versions("0", "70"), versions("0", "70")));
    }

    private static String url(String database) {
        return POSTGRES.getJdbcUrl().replace("/exchange", "/" + database);
    }

    private static boolean causedByGuard(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException && t.getMessage() != null && t.getMessage().contains("V48~V69")) {
                return true;
            }
        }
        return false;
    }

    private static List<MigrationVersion> versions(String... values) {
        return java.util.Arrays.stream(values).map(MigrationVersion::fromVersion).toList();
    }

    private static String latestApplied(String url) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM flyway_schema_history WHERE success "
                     + "ORDER BY installed_rank DESC LIMIT 1")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static boolean tableExists(String url, String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('public." + table + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}

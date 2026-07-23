package kr.exchange.sim;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V11 시퀀스가 실제 PG17에 적용되고 세션·인스턴스와 무관하게 단조임을 검증한다. 재기동은 새
 * DataSource로 재접속해 모사한다. 같은 커넥션을 재사용하면 nextval 성질만 재확인하는 셈이라
 * 채번원이 세션 밖에 존속한다는 요건을 못 짚는다.
 *
 * DB만 검증하고 봇 플릿·웹 컨텍스트는 띄우지 않는다(경량).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunOrdinalSourceIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private JdbcClient jdbc;

    @BeforeAll
    void migrate() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(freshDataSource());
    }

    @AfterAll
    void stop() {
        POSTGRES.stop();
    }

    private DataSource freshDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void sequenceStaysMonotoneAcrossReconnects() {
        RunOrdinalSource first = new DbRunOrdinalSource(jdbc);
        long o1 = first.next();
        long o2 = first.next();
        assertTrue(o2 > o1, "동일 소스 내 단조: " + o2 + " > " + o1);

        // 재기동 모사: 새 커넥션 위의 새 소스. 시퀀스는 세션 밖에 존속해 계속 전진한다.
        RunOrdinalSource afterRestart = new DbRunOrdinalSource(JdbcClient.create(freshDataSource()));
        long o3 = afterRestart.next();
        assertTrue(o3 > o2, "재접속을 넘어 단조: " + o3 + " > " + o2);

        // 같은 seed라도 ordinal이 갈려 재기동 후 nonce가 직전 run과 겹치지 않는다.
        assertNotEquals(BotFleet.nextRunNonce(42L, o1), BotFleet.nextRunNonce(42L, o3),
                "재기동 nonce가 직전 run과 갈린다");
        // run 내 재현: 같은 ordinal이면 nonce 동일.
        assertEquals(BotFleet.nextRunNonce(42L, o3), BotFleet.nextRunNonce(42L, o3));
    }

    @Test
    void appRoleHoldsSequenceUsage() {
        // V9는 GRANT 누락이 런타임 42501로 터진 뒤에야 교정됐다. 컨테이너는 소유자로 접속해
        // 권한 경로를 안 타므로 운영 롤 권한은 별도 단언으로 고정한다.
        Boolean granted = jdbc.sql(
                "SELECT has_sequence_privilege('app_role', 'sim_run_ordinal', 'USAGE')")
                .query(Boolean.class).single();
        assertTrue(granted, "app_role이 sim_run_ordinal USAGE 보유");
    }
}

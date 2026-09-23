package kr.exchange.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.ledger.AccountBootstrap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 접수 append 의 DB lock_timeout 틀(ADR 0034 S1). 넘으면 트랜잭션 전체가 롤백돼 저널 행과 채번이 남지 않고, 강등하지
 * 않으며, 호출자에게 일시 DB 오류(ApiExceptionHandler 가 503)로 올라간다. 상한은 트랜잭션 한정이라 풀에 남지 않는다.
 */
@Testcontainers
class AcceptLockTimeoutIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static LeaderLeaseService lease;
    static CommandJournalAppender appender;
    static CommandPayloadMapper payloadMapper = new CommandPayloadMapper(new ObjectMapper());
    static long alice;

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
        // 커넥션 하나로 돌려 트랜잭션 한정 설정이 다음 사용에 남지 않는지 같은 세션에서 본다.
        config.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", 2, 1, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        alice = bootstrap.user("alice@accept-timeout.local");

        lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(30), Duration.ofSeconds(1));
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(lease.tryAcquire(), "테스트 리더 승격 실패");
        appender = new CommandJournalAppender(jdbc, tx, lease);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void lockWaitPastTimeoutRollsBackWithoutDemotionOrLeftovers() throws Exception {
        appender.setLockTimeout(Duration.ofMillis(200));
        CommandDraft draft = draft("accept-timeout-1", 81_001L);
        long counterBefore = commandCounter();

        DataAccessException timedOut;
        long waitedMs;
        try (Connection outside = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            outside.setAutoCommit(false);
            try (PreparedStatement ps = outside.prepareStatement(
                    "SELECT next_value FROM journal_counters WHERE name = 'command_seq' FOR UPDATE")) {
                ps.executeQuery().close();
            }
            long t0 = System.nanoTime();
            timedOut = assertThrows(DataAccessException.class, () -> appender.append(draft));
            waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            outside.rollback();
        }

        assertEquals("55P03", TransientDbErrors.sqlState(timedOut), timedOut.toString());
        // Spring 6 기본 번역기는 55P03 을 UncategorizedSQLException 으로 올린다. ApiExceptionHandler 가 SQLSTATE 로 503 을 낸다.
        assertInstanceOf(UncategorizedSQLException.class, timedOut);
        assertTrue(TransientDbErrors.isRetryable(timedOut), "503 재시도 신호로 매핑되지 않는 예외다: " + timedOut);
        assertTrue(waitedMs >= 150 && waitedMs < 3_000, "상한 200ms 대기가 아니다: " + waitedMs + "ms");
        assertTrue(lease.isLeader(), "잠금 대기 초과가 강등으로 번졌다");
        assertEquals(counterBefore, commandCounter(), "롤백된 append 가 채번을 남겼다");
        assertEquals(0, journaledNew("accept-timeout-1"), "롤백된 append 가 저널 행을 남겼다");
        assertEquals("0", jdbc.sql("SHOW lock_timeout").query(String.class).single(),
                "트랜잭션 한정 lock_timeout 이 풀 커넥션에 남았다");

        AppendResult ok = appender.append(draft);
        assertFalse(ok.replayed());
        assertEquals(1, journaledNew("accept-timeout-1"));
    }

    private static CommandDraft draft(String clientOrderId, long orderId) {
        OrderIntake intake = new OrderIntake(1, CommandDraft.CommandType.NEW, alice, clientOrderId,
                orderId, 1_757_000_000_000L + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("50000000"), new BigDecimal("0.01"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes(StandardCharsets.UTF_8)),
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        return new CommandDraft(intake.marketId(), intake.type(), intake.userId(), intake.clientOrderId(),
                intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(), payloadMapper.toPayloadJson(intake));
    }

    private static long commandCounter() {
        return jdbc.sql("SELECT next_value FROM journal_counters WHERE name = 'command_seq'")
                .query(Long.class)
                .single();
    }

    private static long journaledNew(String clientOrderId) {
        return jdbc.sql("""
                        SELECT count(*) FROM command_journal
                        WHERE command_type = 'NEW' AND user_id = ? AND client_order_id = ?
                        """)
                .params(alice, clientOrderId)
                .query(Long.class)
                .single();
    }
}

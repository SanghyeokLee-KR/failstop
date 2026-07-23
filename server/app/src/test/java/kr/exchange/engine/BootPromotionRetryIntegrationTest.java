package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalAppender;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 부팅 승격 재시도 회귀(라이브 실측 결함). 강제 종료 직후 재기동은 직전 프로세스의 리스가
 * TTL 내 유효해 첫 승격이 실패하는데, 크래시 창 pending이 있으면 부팅이 즉시 죽었다.
 * recoverAtBoot는 만료를 기다렸다가 재시도해 승격·재적재에 성공해야 하고, 무대기 경로
 * (recover)는 런타임 승격 계약대로 즉시 실패를 유지해야 한다. 펜싱 자체는 불변이다:
 * 만료 전 탈취는 try_acquire_leader가 차단한다.
 */
@Testcontainers
class BootPromotionRetryIntegrationTest {

    private static final long BASE_TS = 1_753_200_000_000L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static ObjectMapper objectMapper;
    static ReplayService replayService;
    // 테스트 3(fencing 정지)와 같은 크래시 창 재현: 명령 커밋·이벤트 미적재.
    static long crashSeq;

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
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        objectMapper = new ObjectMapper();
        CommandJournalReader commandReader = new CommandJournalReader(jdbc);
        EventJournalReader eventReader = new EventJournalReader(jdbc);
        CommandPayloadMapper mapper = new CommandPayloadMapper(objectMapper);
        replayService = new ReplayService(commandReader, eventReader, mapper, objectMapper,
                new MarketCatalog(jdbc));

        // 크래시 창 생성: 직전 리더가 명령만 커밋하고 이벤트 적재 전에 강제 종료된 상황.
        LeaderLeaseService crashedLeader =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        takeOver(crashedLeader);
        String payload = "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                + "\"price\":\"100\",\"qty\":\"1\",\"quote_order_qty\":null}";
        crashSeq = new CommandJournalAppender(jdbc, tx, crashedLeader)
                .append(new CommandDraft(1, CommandDraft.CommandType.NEW, 11L, "boot-retry-1",
                        70L, BASE_TS + 70, 70L, payload))
                .seq();
        crashedLeader.close();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void bootRecoveryRetriesPromotionUntilLeaseExpiry() {
        // 직전 프로세스의 잔존 리스 재현: 타 holder가 유효 리스를 쥔 채로 재기동이 시작된다.
        expireLease();
        LeaderLeaseService priorProcess =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        assertTrue(priorProcess.tryAcquire(), "잔존 리스 선점 실패");

        LeaderLeaseService bootLease =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(4), Duration.ofSeconds(1));
        EventJournalAppender bootAppender = new EventJournalAppender(jdbc, tx, bootLease, objectMapper);
        // 저널 계층만 검증하므로 정산 없는 append sink를 쓴다(EngineRuntimeIntegrationTest와 동일).
        EngineRecovery recovery = new EngineRecovery(replayService, bootAppender::append, bootLease);

        // 무대기 경로 회귀 고정: 잔존 리스가 유효하면 즉시 실패한다(런타임 승격 계약 유지).
        assertThrows(IllegalStateException.class, () -> recovery.recover(new MatchingEngineImpl()));
        assertFalse(bootLease.isLeader(), "무대기 실패 후 리더가 되면 안 된다");
        assertEquals(0, countEvents(crashSeq), "실패 경로에서 이벤트가 적재되면 안 된다");

        // 잔존 리스를 2초 뒤 만료로 재설정. 부팅 대기 예산(TTL 4s x 1.5 = 6s) 안에 만료된다.
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() + interval '2 seconds' "
                        + "WHERE resource = 'matching-engine'")
                .update();

        // 부팅 경로: 만료 대기 후 재시도 승격이 성공하고 크래시 창을 메운다.
        ReplayResult result = recovery.recoverAtBoot(new MatchingEngineImpl());
        assertFalse(result.pendingEvents().isEmpty(), "크래시 창이 pending으로 잡혀야 한다");
        assertTrue(bootLease.isLeader(), "만료 대기 후 승격돼야 한다");
        assertEquals(1, countEvents(crashSeq), "크래시 창 ACCEPTED가 재적재돼야 한다");

        // 재기동 2회째: pending 없이 전량 대조가 성공한다(치유 확인).
        assertTrue(recovery.recover(new MatchingEngineImpl()).pendingEvents().isEmpty(),
                "복구 후 재기동에 pending이 남았다");

        priorProcess.close();
        bootLease.close();
    }

    private static long countEvents(long sourceCmdSeq) {
        return jdbc.sql("SELECT count(*) FROM event_journal "
                        + "WHERE source_cmd_seq = ? AND event_type = 'ACCEPTED'")
                .params(sourceCmdSeq).query(Long.class).single();
    }

    private static void expireLease() {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
    }

    /** 만료 유도 후 즉시 승격. 테스트 전용 리더 교체 경로. */
    private static void takeOver(LeaderLeaseService svc) {
        expireLease();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
    }
}

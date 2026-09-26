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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 기동은 늘 팔로워다(DB 설계 §5.4-c 4).
 *
 * 종전 기동 복구는 크래시 창을 보면 직전 프로세스의 리스가 만료되기를 기다렸다가 승격해 재적재했다(부팅 승격 재시도, 라이브
 * 실측 결함의 수정). 파티션 단위 리더에서는 기동이 리스를 얻지 않고 크래시 창을 재적재하지 않는다. 모든 마켓을 물질화
 * 경계까지 대조만 하고, 창은 다음 리더의 승격 재생(기간을 쥔 recover)이 메운다. 기동이 리스를 쥐면 파티션마다 승격 조율을
 * 거치지 않은 리더가 생기고, 창 재적재가 인계·M(P) 재생과 다른 경로로 한 번 더 생긴다.
 *
 * 이 시험은 그 경계를 고정한다. 리스가 유효하든 만료됐든 기동은 리스 행을 바꾸지 않고 이벤트를 적재하지 않으며, 창은
 * 기간을 쥔 승격 재생이 그 기간의 epoch 로 메운다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BootAsFollowerIntegrationTest {

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
    // 크래시 창 재현: 명령 커밋·이벤트 미적재.
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

        // 직전 리더가 명령만 커밋하고 이벤트 적재 전에 강제 종료된 상황.
        LeaderLeaseService crashedLeader =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        expireLease();
        assertTrue(crashedLeader.tryAcquire(), "테스트 리더 승격 실패");
        String payload = "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                + "\"price\":\"100\",\"qty\":\"1\",\"quote_order_qty\":null}";
        crashSeq = new CommandJournalAppender(jdbc, tx, crashedLeader)
                .append(new CommandDraft(1, CommandDraft.CommandType.NEW, 11L, "boot-follower-1",
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
    @Order(1)
    void bootReconcilesAsFollowerAndPromotionReplayFillsTheCrashWindow() {
        // 직전 프로세스의 잔존 리스: 타 holder가 유효 리스를 쥔 채로 기동이 시작된다.
        expireLease();
        LeaderLeaseService priorProcess =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        assertTrue(priorProcess.tryAcquire(), "잔존 리스 선점 실패");

        LeaderLeaseService bootLease =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(4), Duration.ofSeconds(1));
        EventJournalAppender events = new EventJournalAppender(jdbc, tx, null, objectMapper);
        EngineRecovery recovery = new EngineRecovery(replayService, events::append, null, n -> {
        });

        // 기동 복구: 리스를 얻지 않고 창을 재적재하지 않는다. 창의 명령은 물질화 경계 밖이라 엔진에 적용하지 않는다.
        Map<String, Object> leaseBefore = lease();
        MatchingEngineImpl booted = new MatchingEngineImpl();
        ReplayResult atBoot = recovery.recoverAtBoot(booted);
        assertTrue(atBoot.pendingEvents().isEmpty(), "팔로워 기동이 크래시 창을 재적재 대상으로 냈다");
        assertFalse(bootLease.isLeader(), "기동이 리스를 얻었다");
        assertEquals(leaseBefore, lease(), "기동이 리스 행을 바꿨다");
        assertEquals(0, countEvents(crashSeq), "기동이 크래시 창 이벤트를 적재했다");
        assertTrue(booted.appliedSeq(1) < crashSeq, "기동이 물질화 경계 밖 명령을 적용했다");

        // 리스가 만료돼 있어도 기동은 리스를 얻지 않는다. 첫 획득은 리스 틱과 런타임 승격 경로뿐이다.
        expireLease();
        Map<String, Object> expired = lease();
        assertTrue(recovery.recoverAtBoot(new MatchingEngineImpl()).pendingEvents().isEmpty());
        assertEquals(expired, lease(), "만료 리스를 본 기동이 리스 행을 바꿨다");
        assertEquals(0, countEvents(crashSeq));

        // 기간 없는 종전 승격은 이 배선에서 쓰지 못한다(기간 재적재 sink 만 있다).
        assertThrows(IllegalStateException.class, () -> recovery.recover(new MatchingEngineImpl()));

        // 런타임 승격: 리스를 얻어 기간을 세우고, 그 기간으로 크래시 창을 메운다.
        assertTrue(bootLease.tryAcquire(), "만료 리스 획득 실패");
        LeaderTerm term = bootLease.currentTerm().orElseThrow();
        ReplayResult promoted = recovery.recover(new MatchingEngineImpl(), marketId -> true, term);
        assertFalse(promoted.pendingEvents().isEmpty(), "승격 재생이 크래시 창을 잡지 못했다");
        assertEquals(1, countEvents(crashSeq), "승격 재생이 크래시 창 ACCEPTED 를 재적재하지 않았다");
        assertEquals(term.epoch(), jdbc.sql("SELECT fencing_epoch FROM event_journal WHERE source_cmd_seq = ?")
                .params(crashSeq).query(Long.class).single(), "재적재가 승격 기간의 epoch 로 쓰지 않았다");

        // 창이 메워진 뒤의 기동은 그 명령까지 대조한다(치유 확인).
        MatchingEngineImpl healed = new MatchingEngineImpl();
        assertTrue(recovery.recoverAtBoot(healed).pendingEvents().isEmpty());
        assertEquals(crashSeq, healed.appliedSeq(1), "메워진 창을 기동 대조가 적용하지 않았다");

        priorProcess.close();
        bootLease.close();
    }

    /** 끝난 기간은 크래시 창을 재적재하지 못한다. 늦게 남은 승격 재생이 다음 리더의 창을 건드리지 않는다. */
    @Test
    @Order(2)
    void endedTermCannotReinject() {
        String payload = "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                + "\"price\":\"101\",\"qty\":\"1\",\"quote_order_qty\":null}";
        LeaderLeaseService writer = new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        expireLease();
        assertTrue(writer.tryAcquire());
        long seq = new CommandJournalAppender(jdbc, tx, writer)
                .append(new CommandDraft(1, CommandDraft.CommandType.NEW, 12L, "boot-follower-2",
                        71L, BASE_TS + 71, 71L, payload))
                .seq();
        writer.close();

        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(60), Duration.ofSeconds(1));
        expireLease();
        assertTrue(lease.tryAcquire());
        LeaderTerm term = lease.currentTerm().orElseThrow();
        lease.standDown("시험: 기간 종료");
        assertFalse(term.isCurrent());

        EventJournalAppender events = new EventJournalAppender(jdbc, tx, null, objectMapper);
        EngineRecovery recovery = new EngineRecovery(replayService, events::append, null, n -> {
        });
        // 리더 자격 상실은 판정 없이 끝난 재생이라 예외 타입으로 드러낸다(DB 설계 §5.5 v2.56).
        assertThrows(NotLeaderException.class,
                () -> recovery.recover(new MatchingEngineImpl(), marketId -> true, term));
        assertEquals(0, countEvents(seq), "끝난 기간이 크래시 창을 재적재했다");
        lease.close();
    }

    private static long countEvents(long sourceCmdSeq) {
        return jdbc.sql("SELECT count(*) FROM event_journal "
                        + "WHERE source_cmd_seq = ? AND event_type = 'ACCEPTED'")
                .params(sourceCmdSeq).query(Long.class).single();
    }

    private static Map<String, Object> lease() {
        return jdbc.sql("SELECT holder, epoch, lease_until FROM leader_lease WHERE resource = 'matching-engine'")
                .query().singleRow();
    }

    private static void expireLease() {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
    }
}

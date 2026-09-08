package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalAppender;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.JournalCheckpoint;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 장애 노드 재합류 회귀(2026-09-08 두 노드 실측 결함).
 *
 * 종전에는 기동 재생이 command_journal 을 상한 없이 읽었다. 그래서 다른 노드가 리더로서 계속
 * 쓰는 동안 부팅 노드가 "명령은 있는데 이벤트가 아직 없는" 구간을 항상 보고, 그것을 자기 크래시
 * 창으로 읽어 리더 자격을 요구했다. 유효한 타 리더가 있으므로 15초 대기 뒤 기동을 거부했고
 * 프로세스가 종료했다. 곧 부하 중에는 장애 노드를 되돌릴 수 없었다.
 *
 * 이 검증이 고정하는 것은 셋이다.
 *   1. 살아서 갱신 중인 리더가 있어도 기동이 성공한다.
 *   2. 그 기동은 리더가 되지 않는다. 체크포인트까지만 복원한 팔로워다.
 *   3. 리더가 뒤를 채우면 따라잡기가 그 뒤를 잇고 격차가 0 이 된다.
 */
@Testcontainers
class FollowerRejoinIntegrationTest {

    private static final long BASE_TS = 1_753_300_000_000L;
    private static final String PAYLOAD =
            "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                    + "\"price\":\"100\",\"qty\":\"1\",\"quote_order_qty\":null}";

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
    static EventJournalAppender leaderAppender;
    static CommandJournalAppender leaderCommands;
    // 살아서 리스를 갱신하는 리더. 이 검증의 조건 그 자체다.
    static LeaderLeaseService liveLeader;

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
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        objectMapper = new ObjectMapper();
        replayService = new ReplayService(new CommandJournalReader(jdbc),
                new EventJournalReader(jdbc), new CommandPayloadMapper(objectMapper),
                objectMapper, new MarketCatalog(jdbc));

        // TTL 을 길게, 틱을 짧게 잡아 '살아서 갱신 중' 을 만든다. 갱신이 돌아야 부팅 경로가
        // 만료 대기를 건너뛰는 판별을 실제로 탄다.
        liveLeader = new LeaderLeaseService(jdbc, Duration.ofSeconds(30), Duration.ofSeconds(1));
        assertTrue(liveLeader.tryAcquire(), "리더 리스 선점 실패");
        liveLeader.start();
        leaderAppender = new EventJournalAppender(jdbc, tx, liveLeader, objectMapper);
        leaderCommands = new CommandJournalAppender(jdbc, tx, liveLeader);
    }

    @AfterAll
    static void tearDown() {
        if (liveLeader != null) {
            liveLeader.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void 리더가_쓰는_중에도_팔로워로_기동하고_따라잡는다() {
        // 명령 A. 리더가 이벤트까지 마쳐 안정 구간이 된다.
        long seqA = appendCommand("rejoin-a", 101L);
        materializeAsLeader();
        JournalCheckpoint afterA = replayService.checkpoint();
        assertEquals(seqA, afterA.commandSeq(), "A 가 안정 구간에 들어오지 않았다");

        // 명령 B. 리더가 방금 커밋했고 이벤트는 아직 없다. 라이브 부하에서 항상 존재하는 구간이다.
        long seqB = appendCommand("rejoin-b", 102L);
        assertEquals(afterA.commandSeq(), replayService.checkpoint().commandSeq(),
                "이벤트 없는 명령이 체크포인트를 밀면 안 된다");

        // 기동. 종전에는 여기서 IllegalStateException 으로 프로세스가 죽었다.
        LeaderLeaseService bootLease =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(4), Duration.ofSeconds(1));
        EngineRecovery recovery = new EngineRecovery(replayService,
                new EventJournalAppender(jdbc, tx, bootLease, objectMapper)::append, bootLease);
        MatchingEngineImpl engine = new MatchingEngineImpl();

        ReplayResult booted = recovery.recoverAtBoot(engine);

        assertTrue(booted.pendingEvents().isEmpty(), "체크포인트 상한 기동에 pending 이 남았다");
        assertEquals(seqA, engine.lastAppliedSeq(), "기동이 체크포인트까지만 복원해야 한다");
        assertFalse(bootLease.isLeader(), "유효한 타 리더가 있는데 부팅 노드가 리더가 됐다");
        assertEquals(0, countEvents(seqB), "팔로워 기동이 리더의 진행 중 명령을 재적재했다");

        // 따라잡기. 리더가 B 를 마치면 팔로워가 그 뒤를 잇는다.
        FollowerCatchUp catchUp = new FollowerCatchUp(replayService, engine, bootLease,
                Duration.ofSeconds(1), new SimpleMeterRegistry());
        catchUp.tick();
        assertEquals(seqA, engine.lastAppliedSeq(), "B 가 아직 없는데 따라잡기가 넘어갔다");
        assertEquals(1, catchUp.lagCommands(), "격차가 1 이어야 한다");

        materializeAsLeader();
        catchUp.tick();

        assertEquals(seqB, engine.lastAppliedSeq(), "따라잡기가 리더 head 에 도달하지 못했다");
        assertEquals(0, catchUp.lagCommands(), "따라잡은 뒤 격차가 0 이어야 한다");
        assertEquals(1, catchUp.state(), "따라잡는 중 상태가 아니다");

        catchUp.close();
        bootLease.close();
    }

    /**
     * 승격 정지 계약. 정지 뒤에는 틱이 엔진을 만지지 않는다.
     *
     * 이 계약이 필요한 이유는 승격 재생 생략 경로다. 그 경로는 공유 엔진 위에서 접수를 열므로,
     * 따라잡기가 그 엔진에서 손을 뗀 뒤여야 한다. 정지 자체는 유계여야 한다. 리스 스케줄러
     * 스레드에서 도는 콜백이라 막히면 리스 갱신이 함께 막힌다.
     */
    @Test
    void 승격_정지_뒤에는_따라잡기가_엔진을_만지지_않는다() {
        LeaderLeaseService idleLease =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(4), Duration.ofSeconds(1));
        MatchingEngineImpl engine = new MatchingEngineImpl();
        FollowerCatchUp catchUp = new FollowerCatchUp(replayService, engine, idleLease,
                Duration.ofSeconds(1), new SimpleMeterRegistry());

        catchUp.tick();
        long advanced = engine.lastAppliedSeq();
        assertTrue(advanced > 0, "따라잡기가 한 번도 전진하지 않았다");

        long startedNanos = System.nanoTime();
        catchUp.stopForPromotion();
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;

        assertTrue(elapsedMillis < 1_000, "승격 정지가 유계가 아니다: " + elapsedMillis + "ms");
        assertEquals(2, catchUp.state(), "정지 뒤 상태가 리더 대기여야 한다");

        catchUp.tick();
        assertEquals(advanced, engine.lastAppliedSeq(), "정지 뒤에도 따라잡기가 엔진을 전진시켰다");

        catchUp.close();
        idleLease.close();
    }

    /**
     * 리더 자격으로 크래시 창을 메운다. 실제 리더의 이벤트 적재를 대신한다.
     * 상한 없는 경로(recover)라 이 호출이 곧 '리더가 뒤를 채웠다' 이다.
     */
    private static void materializeAsLeader() {
        EngineRecovery leaderRecovery =
                new EngineRecovery(replayService, leaderAppender::append, liveLeader);
        leaderRecovery.recover(new MatchingEngineImpl());
    }

    private static long appendCommand(String clientOrderId, long orderId) {
        return leaderCommands.append(new CommandDraft(1, CommandDraft.CommandType.NEW, 11L,
                clientOrderId, orderId, BASE_TS + orderId, orderId, PAYLOAD)).seq();
    }

    private static long countEvents(long sourceCmdSeq) {
        return jdbc.sql("SELECT count(*) FROM event_journal WHERE source_cmd_seq = ?")
                .params(sourceCmdSeq).query(Long.class).single();
    }
}

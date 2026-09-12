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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 재생 상한이 물질화된 명령 아래의 구멍 앞에서 멈추는지 본다(ADR 0023).
 *
 * 종전 상한은 `max(source_cmd_seq)` 였다. 명령 저널은 접수 시점에 커밋되고 이벤트는 마켓별
 * 라이터가 각자 정산 트랜잭션에서 적재하므로 그 값 아래에 구멍이 남는다. 상한이 구멍 위에
 * 서면 대조가 구멍 명령의 재생 이벤트와 그 위 명령의 저널 행을 짝지어 거짓 불일치를 낸다.
 * 팔로워는 복귀 경로 없이 굳는다. 실측은 `harness/evidence/materialization-holes-20260912/` 다.
 *
 * 클래스를 따로 두는 이유가 있다. 이 검증은 저널에 영원한 구멍을 남긴다. 합성 이벤트의
 * payload 가 재생 결과와 맞지 않으므로 그 뒤의 어떤 대조도 통과하지 못하고,
 * `event_journal` 은 append-only 라 되돌릴 수 없다. 같은 컨테이너를 쓰는 다른 검증과 섞으면
 * 실행 순서에 따라 결과가 갈린다.
 */
@Testcontainers
class MaterializationBoundIntegrationTest {

    private static final long BASE_TS = 1_753_400_000_000L;
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
    static ReplayService replayService;
    static EventJournalAppender leaderAppender;
    static CommandJournalAppender leaderCommands;
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
        TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        ObjectMapper objectMapper = new ObjectMapper();
        replayService = new ReplayService(new CommandJournalReader(jdbc),
                new EventJournalReader(jdbc), new CommandPayloadMapper(objectMapper),
                objectMapper, new MarketCatalog(jdbc));

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
    void 상한은_물질화된_명령_아래_구멍_앞에서_멈춘다() {
        long base = appendCommand("hole-base", 201L);
        materializeAsLeader();
        assertEquals(base, replayService.materializedThrough(0).commandSeq(),
                "기준선이 서지 않았다");

        long hole = appendCommand("hole-gap", 202L);
        long above = appendCommand("hole-above", 203L);

        // 구멍 위 명령만 물질화한다. 마켓별 라이터가 서로 다른 트랜잭션으로 커밋하는 상황과
        // 같은 상태다. 저널은 append-only 라 INSERT 로만 만든다.
        insertSyntheticEvent(above);

        // 양성 증거 축. 구멍과 그 위 물질화가 실제로 존재하는지 먼저 증명한다.
        assertEquals(0, countEvents(hole), "구멍이 만들어지지 않았다");
        assertEquals(1, countEvents(above), "구멍 위 물질화가 만들어지지 않았다");
        // 종전 술어가 구멍 위를 가리키는 것을 같은 시험에서 대조로 남긴다. 이것이 없으면
        // 아래 단언이 교정 전 구현에서도 통과하는지 알 수 없다.
        assertEquals(above, jdbc.sql("SELECT max(source_cmd_seq) FROM event_journal")
                        .query(Long.class).single(),
                "종전 술어가 구멍 위를 가리키지 않는다. 이 검증은 아무것도 판정하지 않는다");

        assertEquals(base, replayService.materializedThrough(0).commandSeq(),
                "상한이 구멍을 넘어섰다");
    }

    /** 구멍 위 물질화를 만든다. 재생 대조를 통과할 필요가 없으므로 최소 행이면 된다. */
    private static void insertSyntheticEvent(long sourceCmdSeq) {
        jdbc.sql("INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type,"
                        + " payload, payload_schema_version, fencing_epoch, created_at, market_id,"
                        + " market_seq) VALUES (?, ?, 0, 'ACCEPTED', '{}'::jsonb, 1, 1, now(), 1, ?)")
                .params(10_000_000L + sourceCmdSeq, sourceCmdSeq, 9_000_000L + sourceCmdSeq)
                .update();
    }

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
                .param(sourceCmdSeq)
                .query(Long.class)
                .single();
    }
}

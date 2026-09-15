package kr.exchange.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.EngineRecovery;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.core.SnapshotCodec;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalAppender;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스냅샷 로드 후 tail 재생 회귀 검증(복구 RTO 설계 §3-b, §4-1 스냅샷 케이스).
 *
 * 계약 단언:
 *  (1) 채택: seq K 스냅샷 존재 시 복구가 그것을 로드하고 seq > K만 tail 재생한다. 재생 후
 *      엔진이 head + 초기 리더 체크섬과 일치하고, replayedCommands == K 뒤 명령 수로 tail 상한을 증명.
 *      K 는 접수 응답의 seq 다. seq 가 1 부터 조밀하다고 가정하지 않는다.
 *  (2) 폴백: engine_version 불일치 스냅샷은 채택 게이트가 폐기하고 seq 0 전량 재생으로 복구한다.
 *      결과 상태는 채택 경로와 바이트 동일(체크섬 일치)해야 한다.
 */
@Testcontainers
class SnapshotRecoveryIntegrationTest {

    private static final long BASE_TS = 1_753_200_000_000L;
    private static final int JOURNAL_SIZE = 400;
    private static final int SNAPSHOT_AT = 250;
    private static final String ENGINE_VERSION = "engine-m1";
    private static final String CONFIG_HASH = "default";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static ObjectMapper objectMapper;
    static CommandJournalReader commandReader;
    static EventJournalReader eventReader;
    static CommandPayloadMapper mapper;
    static ReplayService replayService;

    static long leaderHead;
    static long leaderChecksum;
    static long snapshotSeq;

    @BeforeAll
    static void buildJournalAndSnapshot() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        objectMapper = new ObjectMapper();
        commandReader = new CommandJournalReader(jdbc);
        eventReader = new EventJournalReader(jdbc);
        mapper = new CommandPayloadMapper(objectMapper);
        replayService = new ReplayService(commandReader, eventReader, mapper, objectMapper,
                new MarketCatalog(jdbc));

        LeaderLeaseService leader = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        takeOver(leader);
        EventJournalAppender eventAppender = new EventJournalAppender(jdbc, tx, leader, objectMapper);
        MatchingEngineImpl live = new MatchingEngineImpl();
        SingleWriterLoop loop = new SingleWriterLoop(4096, live, eventAppender::append,
                new JournalCatchUpSource(commandReader, mapper), new AtomicReference<Throwable>()::set);
        EngineBridge bridge = new EngineBridge(new CommandJournalAppender(jdbc, tx, leader), loop, mapper);
        SnapshotStore store = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        loop.start();
        try {
            for (int i = 1; i <= SNAPSHOT_AT; i++) {
                snapshotSeq = bridge.submit(sellIntake(i, String.valueOf(1000 + i))).seq();
            }
            Awaitility.await().atMost(Duration.ofSeconds(60))
                    .until(() -> live.lastAppliedSeq() == snapshotSeq);
            // 라이터가 유휴(큐 비고 스냅샷 seq 까지 적용 완료)인 경계에서 마켓 상태를 동결한다.
            List<SnapshotStore.MarketState> markets = new ArrayList<>();
            for (int marketId : live.markets()) {
                OrderBookImpl book = live.orderBook(marketId);
                markets.add(new SnapshotStore.MarketState(
                        marketId, live.appliedSeq(marketId), SnapshotCodec.encode(book), book.stateChecksum()));
            }
            store.persist(leader.requireLeader(), markets);

            long lastSeq = 0;
            for (int i = SNAPSHOT_AT + 1; i <= JOURNAL_SIZE; i++) {
                lastSeq = bridge.submit(sellIntake(i, String.valueOf(1000 + i))).seq();
            }
            long target = lastSeq;
            Awaitility.await().atMost(Duration.ofSeconds(60))
                    .until(() -> live.lastAppliedSeq() == target);
        } finally {
            try {
                loop.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        leaderHead = live.lastAppliedSeq();
        leaderChecksum = live.stateChecksum();
        assertEquals(leaderHead, commandReader.maxSeq());
        assertEquals(JOURNAL_SIZE, jdbc.sql("SELECT count(*) FROM command_journal").query(Long.class).single());
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void adoptsSnapshotAndReplaysOnlyTail() {
        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        takeOver(lease);
        SnapshotStore store = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        EngineRecovery recovery = new EngineRecovery(replayService,
                new EventJournalAppender(jdbc, tx, lease, objectMapper)::append, lease, store);

        MatchingEngineImpl engine = new MatchingEngineImpl();
        ReplayResult result = recovery.recover(engine);

        assertEquals(leaderHead, engine.lastAppliedSeq(), "재생 후 head 미도달");
        assertEquals(leaderChecksum, engine.stateChecksum(), "스냅샷+tail 상태가 원본과 다르다");
        assertEquals(JOURNAL_SIZE - SNAPSHOT_AT, result.replayedCommands(),
                "tail 재생이 스냅샷 경계로 상한되지 않았다");
        assertTrue(result.pendingEvents().isEmpty(), "클린 저널에 pending이 남았다");
        lease.close();
    }

    @Test
    void discardsVersionMismatchedSnapshotAndFullReplays() {
        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        takeOver(lease);
        // 엔진 업그레이드 시뮬레이션: 기록된 engine-m1 스냅샷을 현재 engine-m2가 폐기해야 한다.
        SnapshotStore upgraded = new SnapshotStore(jdbc, tx, "engine-m2", CONFIG_HASH);
        EngineRecovery recovery = new EngineRecovery(replayService,
                new EventJournalAppender(jdbc, tx, lease, objectMapper)::append, lease, upgraded);

        MatchingEngineImpl engine = new MatchingEngineImpl();
        ReplayResult result = recovery.recover(engine);

        assertEquals(leaderHead, engine.lastAppliedSeq());
        assertEquals(leaderChecksum, engine.stateChecksum(),
                "폴백 전량 재생 상태가 채택 경로와 다르다");
        assertEquals(JOURNAL_SIZE, result.replayedCommands(), "폴백인데 tail만 재생했다");
        lease.close();
    }

    private static OrderIntake sellIntake(long orderId, String price) {
        return new OrderIntake(1, CommandDraft.CommandType.NEW, 1, "sell-" + orderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.SELL, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal("1"), null,
                externalId("sell-" + orderId), null, null);
    }

    private static UUID externalId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes());
    }

    private static void takeOver(LeaderLeaseService svc) {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
    }
}

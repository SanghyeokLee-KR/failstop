package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalAppender;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.EventJournalRow;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import org.awaitility.Awaitility;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-2 통합 검증: (b) 재생 결정론(event_seq·ordinal·시각·payload 동일, 체크섬 일치, T3-C 축),
 * (d) 루프 실소비(append → 소비 → 이벤트 저널), seq 갭 catch-up 회복, fencing 거부 시
 * demote + 루프 정지(self-shutdown), 크래시 창 pending 재적재(§5.5 ②), 비 fencing sink
 * 실패 시 리더십 반납·접수 차단. 저널이 전역 공유 자원이라 순서를 고정한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineRuntimeIntegrationTest {

    private static final long BASE_TS = 1_753_000_000_000L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static ObjectMapper objectMapper;
    static LeaderLeaseService lease;
    static CommandJournalAppender commandAppender;
    static CommandJournalReader commandReader;
    static EventJournalAppender eventAppender;
    static EventJournalReader eventReader;
    static CommandPayloadMapper mapper;
    static ReplayService replayService;

    // 테스트 2에서 띄운 스택을 테스트 3(fencing 정지)이 이어 쓴다.
    static MatchingEngineImpl runningEngine;
    static SingleWriterLoop runningLoop;
    static AtomicReference<Throwable> loopFatal;

    static long liveChecksum;
    static long liveLastAppliedSeq;
    static int scenarioEventCount;
    // 테스트 3이 남기는 크래시 창(명령 커밋·이벤트 미적재) 명령. 테스트 4가 복구를 검증한다.
    static long fenceSeq;

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
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        objectMapper = new ObjectMapper();
        lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        commandAppender = new CommandJournalAppender(jdbc, tx, lease);
        commandReader = new CommandJournalReader(jdbc);
        eventAppender = new EventJournalAppender(jdbc, tx, lease, objectMapper);
        eventReader = new EventJournalReader(jdbc);
        mapper = new CommandPayloadMapper(objectMapper);
        replayService = new ReplayService(commandReader, eventReader, mapper, objectMapper,
                new kr.exchange.ledger.MarketCatalog(jdbc));
        takeOver(lease);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (runningLoop != null) {
            runningLoop.stop();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void replayReproducesIdenticalEventStream() throws Exception {
        MatchingEngineImpl live = new MatchingEngineImpl();
        SingleWriterLoop loop = newLoop(live, new AtomicReference<>());
        EngineBridge bridge = new EngineBridge(commandAppender, loop, mapper);
        loop.start();
        try {
            // 가격·시간 우선, 부분 체결, 취소·중복 취소(REJECTED), 시장가 소진, 빈 반대편
            // (REJECTED), IOC 잔량 취소까지 전 이벤트 유형(ACCEPTED/TRADE/CANCELED/REJECTED)을
            // 지나는 시나리오.
            submit(bridge, limitIntake(1, 1, OrderCommand.Side.SELL, "100", "1"));
            submit(bridge, limitIntake(1, 2, OrderCommand.Side.SELL, "100", "2"));
            submit(bridge, limitIntake(1, 3, OrderCommand.Side.SELL, "99", "1"));
            submit(bridge, limitIntake(2, 4, OrderCommand.Side.BUY, "100", "2.5"));
            submit(bridge, limitIntake(2, 5, OrderCommand.Side.BUY, "95", "1"));
            submit(bridge, cancelIntake(2, 5));
            submit(bridge, cancelIntake(2, 5));
            submit(bridge, marketBuyIntake(3, 8, "200"));
            submit(bridge, marketSellIntake(2, 9, "1"));
            submit(bridge, iocIntake(1, 10));
            submit(bridge, limitIntake(3, 11, OrderCommand.Side.SELL, "101", "1"));
            SubmitResult last = submit(bridge, limitIntake(3, 12, OrderCommand.Side.BUY, "101", "1"));
            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .until(() -> live.lastAppliedSeq() == last.seq());
        } finally {
            loop.stop();
        }
        liveChecksum = live.stateChecksum();
        liveLastAppliedSeq = live.lastAppliedSeq();

        List<EventJournalRow> rows = eventReader.readAfter(0, 1000);
        scenarioEventCount = rows.size();
        assertTrue(rows.size() >= 15, "이벤트 저널이 비었거나 부족하다: " + rows.size());
        // gapless + 레지스트리 트리거 정합
        assertEquals(rows.size(), queryLong("SELECT max(event_seq) - min(event_seq) + 1 FROM event_journal"));
        assertEquals(rows.size(), queryLong("SELECT count(*) FROM event_emission_registry"));
        long epoch = lease.requireLeader().epoch();
        assertEquals(rows.size(), jdbc.sql("SELECT count(*) FROM event_journal WHERE fencing_epoch = ?")
                .params(epoch).query(Long.class).single());
        // created_at = engine_ts 파생 확인(명령 저널과 조인)
        assertEquals(0, queryLong("""
                SELECT count(*) FROM event_journal e
                JOIN command_journal c ON c.seq = e.source_cmd_seq
                WHERE e.created_at <> to_timestamp(c.engine_ts / 1000.0)
                """));

        // 전체 재생: 이벤트 대조(event_seq 연속·ordinal·type·payload·created_at)는
        // ReplayService가 수행하고, 불일치는 예외로 표면화된다.
        MatchingEngineImpl replayed = new MatchingEngineImpl();
        ReplayResult result = replayService.rebuild(replayed);

        assertEquals(liveLastAppliedSeq, result.lastAppliedSeq());
        assertEquals(rows.size(), result.verifiedEvents(), "대조된 이벤트 수 불일치");
        assertTrue(result.pendingEvents().isEmpty(), "크래시 창이 없는데 pending이 남았다");
        assertEquals(liveChecksum, replayed.stateChecksum(), "재생 상태 체크섬 불일치");

        // trade_id = event_seq 규약의 대조 축: TRADE 이벤트가 존재하고 seq가 재현 가능해야 한다.
        assertTrue(rows.stream().anyMatch(r -> "TRADE".equals(r.eventType())));
        // 재생을 한 번 더 돌려도 결과가 같다(결정론).
        MatchingEngineImpl again = new MatchingEngineImpl();
        replayService.rebuild(again);
        assertEquals(replayed.stateChecksum(), again.stateChecksum());
    }

    @Test
    @Order(2)
    void seqGapIsRecoveredByCatchUp() {
        // 재기동 시나리오: 재생으로 상태 복원 후 루프를 연다.
        runningEngine = new MatchingEngineImpl();
        replayService.rebuild(runningEngine);
        assertEquals(liveLastAppliedSeq, runningEngine.lastAppliedSeq());
        loopFatal = new AtomicReference<>();
        runningLoop = newLoop(runningEngine, loopFatal);
        lease.onLeadershipLost(runningLoop::halt);
        runningLoop.start();

        // 저널에는 커밋됐지만 큐에 투입되지 않은 명령 2건(큐 유실 시나리오).
        long skipped1 = commandAppender.append(newDraft(4, "gap-1", 20, "100", "1")).seq();
        long skipped2 = commandAppender.append(newDraft(4, "gap-2", 21, "101", "1")).seq();

        // 다음 명령은 정상 투입된다. 수신 seq != lastApplied+1 → catch-up이 회수해야 한다.
        EngineBridge bridge = new EngineBridge(commandAppender, runningLoop, mapper);
        SubmitResult ack = submit(bridge, limitIntake(4, 22, OrderCommand.Side.BUY, "99", "1"));
        assertEquals(skipped2 + 1, ack.seq());

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> runningEngine.lastAppliedSeq() == ack.seq());
        // 갭 구간 명령의 이벤트(ACCEPTED)까지 저널에 남아야 한다.
        for (long seq : List.of(skipped1, skipped2, ack.seq())) {
            assertEquals(1, jdbc.sql(
                            "SELECT count(*) FROM event_journal WHERE source_cmd_seq = ? AND event_type = 'ACCEPTED'")
                    .params(seq).query(Long.class).single());
        }
        assertTrue(runningLoop.isRunning());
    }

    @Test
    @Order(3)
    void fencingRejectionDemotesAndHaltsLoop() {
        long staleEpoch = lease.requireLeader().epoch();

        // 신 리더 탈취. 구 리더(lease)는 아직 강등 사실을 모른다.
        LeaderLeaseService successor =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        takeOver(successor);
        assertEquals(staleEpoch + 1, successor.requireLeader().epoch());

        // 명령은 신 리더가 저널에 커밋하고, 구 리더 루프에 투입된다(크래시 창 재현).
        CommandJournalAppender successorAppender = new CommandJournalAppender(jdbc, tx, successor);
        fenceSeq = successorAppender.append(newDraft(5, "fence-1", 30, "100", "1")).seq();
        OrderCommand command = mapper.toCommand(commandReader.readAfter(fenceSeq - 1, 1).get(0));
        assertTrue(runningLoop.offer(command));

        // 이벤트 append의 assert_current_leader가 거부 → demote → 상실 리스너 → halt.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !runningLoop.isRunning());
        assertFalse(lease.isLeader(), "fencing 거부 후에도 강등되지 않았다");
        assertNotNull(loopFatal.get(), "fail-fast 통지가 없었다");
        // 구 epoch로 커밋된 이벤트 행은 0이어야 한다(불변식 7 축).
        assertEquals(0, jdbc.sql("SELECT count(*) FROM event_journal WHERE source_cmd_seq = ?")
                .params(fenceSeq).query(Long.class).single());
    }

    @Test
    @Order(4)
    void crashWindowEventsAreReappendedOnRecovery() throws Exception {
        // 재기동 1회째: 테스트 3이 남긴 크래시 창을 기동 복구가 event_journal에 메운다.
        LeaderLeaseService restartLease =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        takeOver(restartLease);
        EventJournalAppender restartAppender =
                new EventJournalAppender(jdbc, tx, restartLease, objectMapper);
        // 이 테스트는 저널 계층만 검증하므로 정산 없는 append를 sink로 쓴다(정산 결합은 M1-3 테스트).
        EngineRecovery recovery = new EngineRecovery(replayService, restartAppender::append, restartLease);

        long before = queryLong("SELECT count(*) FROM event_journal");
        MatchingEngineImpl engine = new MatchingEngineImpl();
        ReplayResult first = recovery.recover(engine);
        assertFalse(first.pendingEvents().isEmpty(), "크래시 창이 pending으로 잡혀야 한다");
        assertTrue(first.pendingEvents().stream().allMatch(e -> e.sourceCmdSeq() == fenceSeq));
        assertEquals(before + first.pendingEvents().size(),
                queryLong("SELECT count(*) FROM event_journal"));
        // 재적재분은 W+1부터 연속 배정이라 gapless가 유지된다(§5.5 ②).
        assertEquals(queryLong("SELECT count(*) FROM event_journal"),
                queryLong("SELECT max(event_seq) - min(event_seq) + 1 FROM event_journal"));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM event_journal "
                        + "WHERE source_cmd_seq = ? AND event_type = 'ACCEPTED' AND event_ordinal = 0")
                .params(fenceSeq).query(Long.class).single());

        // 신규 명령을 정상 처리한다(구현 전에는 이 지점부터 prefix가 깨져 다음 재생이 벽돌).
        SingleWriterLoop loop = new SingleWriterLoop(1024, engine, restartAppender::append,
                new JournalCatchUpSource(commandReader, mapper), new AtomicReference<Throwable>()::set);
        loop.start();
        try {
            EngineBridge bridge = new EngineBridge(
                    new CommandJournalAppender(jdbc, tx, restartLease), loop, mapper);
            SubmitResult ack = submit(bridge, limitIntake(6, 40, OrderCommand.Side.BUY, "98", "1"));
            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .until(() -> engine.lastAppliedSeq() == ack.seq());
        } finally {
            loop.stop();
        }

        // 재기동 2회째: pending 없이 전량 대조가 성공해야 한다.
        MatchingEngineImpl rebuilt = new MatchingEngineImpl();
        ReplayResult second = recovery.recover(rebuilt);
        assertTrue(second.pendingEvents().isEmpty(), "복구 후 재기동에 pending이 남았다");
        assertEquals(engine.lastAppliedSeq(), second.lastAppliedSeq());
        assertEquals(engine.stateChecksum(), rebuilt.stateChecksum());
    }

    @Test
    @Order(5)
    void nonFencingSinkFailureReleasesLeadershipAndBlocksIntake() {
        LeaderLeaseService failLease =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        takeOver(failLease);

        MatchingEngineImpl engine = new MatchingEngineImpl();
        replayService.rebuild(engine);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        // 일시 DB 오류 시뮬레이션: fencing 거부가 아닌 sink 실패.
        SingleWriterLoop loop = new SingleWriterLoop(64, engine,
                events -> { throw new IllegalStateException("이벤트 저널 접속 실패 시뮬레이션"); },
                new JournalCatchUpSource(commandReader, mapper),
                new LoopFatalHandler(failLease).andThen(fatal::set));
        failLease.onLeadershipLost(loop::halt);
        loop.start();

        EngineBridge bridge = new EngineBridge(
                new CommandJournalAppender(jdbc, tx, failLease), loop, mapper);
        submit(bridge, limitIntake(7, 50, OrderCommand.Side.SELL, "150", "1"));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !loop.isRunning());

        assertNotNull(fatal.get(), "fail-fast 통지가 없었다");
        assertFalse(failLease.isLeader(), "비 fencing sink 실패도 리더십을 내려놓아야 한다");
        assertFalse(failLease.tryAcquire(), "강등 후 재승격은 금지다");
        // 루프 정지 후 접수는 저널 커밋 전에 거부돼야 한다(무기한 미체결 ACK 금지).
        assertThrows(IllegalStateException.class,
                () -> bridge.submit(limitIntake(7, 51, OrderCommand.Side.SELL, "151", "1")));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM command_journal WHERE client_order_id = ?")
                .params("run-51").query(Long.class).single());

        // 이 테스트가 만든 크래시 창(run-50 커밋·이벤트 미적재)을 복구 경로로 치유해
        // 저널을 정합 상태로 되돌린다(복구 경로 재검증 겸용).
        LeaderLeaseService healLease =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        takeOver(healLease);
        EngineRecovery recovery = new EngineRecovery(replayService,
                new EventJournalAppender(jdbc, tx, healLease, objectMapper)::append, healLease);
        ReplayResult healed = recovery.recover(new MatchingEngineImpl());
        assertFalse(healed.pendingEvents().isEmpty());
        assertTrue(recovery.recover(new MatchingEngineImpl()).pendingEvents().isEmpty());
    }

    private static SingleWriterLoop newLoop(MatchingEngineImpl engine, AtomicReference<Throwable> fatal) {
        return new SingleWriterLoop(1024, engine, eventAppender::append,
                new JournalCatchUpSource(commandReader, mapper), fatal::set);
    }

    private static SubmitResult submit(EngineBridge bridge, OrderIntake intake) {
        SubmitResult result = bridge.submit(intake);
        assertFalse(result.replayed());
        assertTrue(result.queued());
        return result;
    }

    private static OrderIntake limitIntake(long userId, long orderId, OrderCommand.Side side,
                                           String price, String qty) {
        return new OrderIntake(1, CommandDraft.CommandType.NEW, userId, "run-" + orderId, orderId,
                BASE_TS + orderId, orderId, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal(qty), null,
                externalId(orderId), null, null);
    }

    private static OrderIntake marketBuyIntake(long userId, long orderId, String notional) {
        return new OrderIntake(1, CommandDraft.CommandType.NEW, userId, "run-" + orderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.MARKET,
                null, null, null, new BigDecimal(notional), externalId(orderId), null, null);
    }

    private static OrderIntake marketSellIntake(long userId, long orderId, String qty) {
        return new OrderIntake(1, CommandDraft.CommandType.NEW, userId, "run-" + orderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.SELL, OrderCommand.OrdType.MARKET,
                null, null, new BigDecimal(qty), null, externalId(orderId), null, null);
    }

    private static OrderIntake iocIntake(long userId, long orderId) {
        return new OrderIntake(1, CommandDraft.CommandType.NEW, userId, "run-" + orderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.IOC, new BigDecimal("100"), new BigDecimal("1"), null,
                externalId(orderId), null, null);
    }

    private static OrderIntake cancelIntake(long userId, long targetOrderId) {
        return new OrderIntake(1, CommandDraft.CommandType.CANCEL, userId, null, targetOrderId,
                BASE_TS + 500 + targetOrderId, targetOrderId, null, null, null, null, null, null,
                null, null, null);
    }

    /** 재생 결정론 유지를 위한 고정 external_id. 접수마다 새로 뽑으면 payload 대조가 깨진다. */
    private static java.util.UUID externalId(long orderId) {
        return java.util.UUID.nameUUIDFromBytes(("run-" + orderId).getBytes());
    }

    private static CommandDraft newDraft(long userId, String clientOrderId, long orderId,
                                         String price, String qty) {
        String payload = "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                + "\"price\":\"" + price + "\",\"qty\":\"" + qty + "\",\"quote_order_qty\":null}";
        return new CommandDraft(1, CommandDraft.CommandType.NEW, userId, clientOrderId,
                orderId, BASE_TS + 900 + orderId, orderId, payload);
    }

    /** 만료 유도 후 즉시 승격. 테스트 전용 리더 교체 경로. */
    private static void takeOver(LeaderLeaseService svc) {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
    }

    private static long queryLong(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

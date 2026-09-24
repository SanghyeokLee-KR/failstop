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
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 승격 재생 경로(대규모 gap) 회귀 검증(복구 RTO 설계 §4-1). CI 사각을 메운다: 기존
 * EngineRuntimeIntegrationTest는 catch-up 검증 전에 rebuild로 엔진을 head에 올려 결함을
 * 은폐한다. 본 테스트는 fresh 엔진(lastAppliedSeq=0) + 대규모 저널 gap을 실제로 밟는다.
 *
 * 계약 단언:
 *  (A) 승격 재생이 정산·append sink를 O(pending)회만 호출한다(클린 승격 pending=0 → 0회).
 *      계수 sink로 catch-up이 정산 경로를 O(저널)회 타지 않음을 직접 방지선으로 고정한다.
 *  (B) rebuild 후 설치 엔진이 head + 초기 리더 체크섬과 일치한다.
 *  (C) 접수 게이트 순서: 재생 완료 전 submit은 NotReady, 완료 후 신규 명령은 통과.
 *  (D) 벽시계 RTO: 승격 트리거~가시 첫 체결(TRADE 커밋) < 예산. engine_ts 파생값을 쓰지 않는다.
 * 별도로 runLoop Error 주입 시 fatal 경로·demote를 단언한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PromotionRecoveryIntegrationTest {

    private static final long BASE_TS = 1_753_100_000_000L;
    private static final int JOURNAL_SIZE = 1000;
    private static final long RTO_BUDGET_MS = 25_000;

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

    @BeforeAll
    static void buildLargeJournalAsLeader() throws Exception {
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
        loop.start();
        long lastSeq = 0;
        try {
            // 전량 동일 방향(SELL) 지정가 레스팅. 서로 교차하지 않아 gapless·pending=0을 유지한다.
            for (int i = 1; i <= JOURNAL_SIZE; i++) {
                String price = String.valueOf(1000 + i);
                lastSeq = bridge.submit(sellIntake(1, i, price)).seq();
            }
            long target = lastSeq;
            Awaitility.await().atMost(Duration.ofSeconds(60))
                    .until(() -> live.lastAppliedSeq() == target);
        } finally {
            loop.stop();
        }
        leaderHead = live.lastAppliedSeq();
        leaderChecksum = live.stateChecksum();
        assertEquals(leaderHead, commandReader.maxSeq());
        assertTrue(leaderHead >= JOURNAL_SIZE);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void promotionRebuildsFreshEngineBeforeAcceptingWithoutSettlementCatchUp() throws Exception {
        // 스탠바이 부팅 재현: stale fresh 엔진(seq 0) + 접수 닫힌 루프.
        LeaderLeaseService standbyLease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        EventJournalAppender eventAppender = new EventJournalAppender(jdbc, tx, standbyLease, objectMapper);
        AtomicInteger sinkCalls = new AtomicInteger();
        // 계수 sink: 재적재·정산 sink 호출 수를 센다. 클린 승격이면 pending=0이라 0회여야 한다.
        SingleWriterLoop.EventSink countingReappendSink = events -> {
            sinkCalls.incrementAndGet();
            eventAppender.append(events);
        };
        EngineRecovery recovery = new EngineRecovery(replayService, countingReappendSink, standbyLease);

        MatchingEngineImpl standbyEngine = new MatchingEngineImpl();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop standbyLoop = new SingleWriterLoop(4096, standbyEngine, eventAppender::append,
                new JournalCatchUpSource(commandReader, mapper), fatal::set);
        PromotionCoordinator coordinator = new PromotionCoordinator(
                recovery, standbyLoop, standbyLease, commandReader);
        standbyLease.onLeadershipGained(coordinator::onLeadershipGained);
        standbyLease.onLeadershipLost(coordinator::onLeadershipLost);
        EngineBridge bridge = new EngineBridge(new CommandJournalAppender(jdbc, tx, standbyLease),
                standbyLoop, mapper, coordinator.gate());

        standbyLoop.start();
        coordinator.bootstrap(standbyEngine, standbyLease.isLeader());
        coordinator.arm();
        try {
            // (C) 재생 완료 전: 게이트 닫힘, 접수는 NotReady.
            assertFalse(coordinator.gate().isAccepting());
            assertEquals(PromotionCoordinator.State.FOLLOWER, coordinator.state());
            assertThrows(NotReadyException.class,
                    () -> bridge.submit(buyIntake(2, 90001, "100000")));

            long t0 = System.nanoTime();
            takeOver(standbyLease);
            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> coordinator.state() == PromotionCoordinator.State.ACCEPTING);

            // (A) 승격 재생은 정산·append sink를 O(pending)=0회만 호출한다.
            assertEquals(0, sinkCalls.get(),
                    "클린 승격인데 재적재 sink가 호출됐다(정산 catch-up 회귀)");
            // (B) 설치 엔진이 head + 초기 리더 체크섬과 일치.
            assertEquals(leaderHead, coordinator.installedEngine().lastAppliedSeq());
            assertEquals(leaderChecksum, coordinator.installedEngine().stateChecksum());

            // (C) 재생 완료 후: 신규 매수 통과, 베스트 애스크(1001)와 교차해 TRADE 방출.
            assertTrue(coordinator.gate().isAccepting());
            long buySeq = bridge.submit(buyIntake(2, 90002, "100000")).seq();
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> tradeCommitted(buySeq));
            long rtoMs = (System.nanoTime() - t0) / 1_000_000;

            // (D) 벽시계 RTO: 승격 트리거~가시 첫 체결. engine_ts·created_at을 산정에 쓰지 않는다.
            assertTrue(rtoMs < RTO_BUDGET_MS, "가시 첫 체결 RTO 초과: " + rtoMs + "ms");
        } finally {
            standbyLoop.stop();
            coordinator.close();
            standbyLease.close();
        }
    }

    /**
     * C17 원천이 실제 복구에서 채워지는지 본다(검증설계 C17 REPLAY_DETERMINISM).
     *
     * recovery_runs에 쓰는 코드가 없으면 표가 늘 0행이라 판정이 빈 테이블 대조로 공허 통과한다.
     * 픽스처 INSERT로 판정 SQL만 보는 테스트는 그 상태를 구분하지 못하므로 배선 자체를 건다.
     */
    @Test
    @Order(3)
    void recoveryWritesRunRowWithReplayWindowFingerprint() {
        jdbc.sql("DELETE FROM recovery_runs").update();
        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        EngineRecovery recovery = new EngineRecovery(replayService,
                events -> {
                }, lease);
        recovery.setRecoveryRecorder(new kr.exchange.harness.RecoveryRunRecorder(jdbc));
        takeOver(lease);

        MatchingEngineImpl fresh = new MatchingEngineImpl();
        recovery.recover(fresh);

        Map<String, Object> row = jdbc.sql("""
                        SELECT market_id, replayed_from_seq, replayed_to_seq,
                               fp_replay, fp_live, matched
                        FROM recovery_runs ORDER BY id DESC LIMIT 1
                        """).query().singleRow();
        assertEquals(true, row.get("matched"), "예외 없이 끝난 재생은 matched=true다");
        // 전 마켓 통합 재생이라 마켓별 분해가 원천에 없다(DB§3.1 센티널 0).
        assertEquals(0, ((Number) row.get("market_id")).intValue());
        long from = ((Number) row.get("replayed_from_seq")).longValue();
        long to = ((Number) row.get("replayed_to_seq")).longValue();
        assertEquals(0L, from, "fresh 엔진이라 재생 시작이 0이다");
        // leaderHead는 클래스 셋업 시점 값이다. 앞선 테스트가 명령을 더 넣으므로 지금 head를
        // 읽는다. 고정 상수와 비교하면 실행 순서에 따라 깨진다(실측: 1000 대 1002).
        assertEquals(commandReader.maxSeqByMarket().values().stream().mapToLong(Long::longValue).max()
                .orElse(0), to, "재생 종점이 마켓별 head 의 최댓값이다");
        // fp는 재생 창 식별자다(DB§5.5). 형식이 계약이라 형식을 건다.
        String fp = String.valueOf(row.get("fp_replay"));
        String prefix = "seq:" + from + "-" + to + ":ev:";
        assertTrue(fp.startsWith(prefix),
                "fp_replay가 재생 창 식별자 형식이 아니다: " + fp);
        assertEquals(fp, row.get("fp_live"), "성공 경로는 두 값이 같다");
        // 재생 0건이 통과로 보이지 않도록 검증 이벤트 수가 fp에 실린다.
        long events = Long.parseLong(fp.substring(prefix.length()));
        assertTrue(events > 0, "저널에 명령이 있는데 검증 이벤트가 0이면 재생이 안 돈 것이다");
    }

    /**
     * 재생이 예외로 끝나면 matched=false가 남는가. C17의 실질 판정력이 이 경로에 있다.
     *
     * fp 두 컬럼은 성공 경로에서 같은 값이라 그 비교로는 matched가 항상 참이다. 즉 fp 대조가
     * 판정이 아니다. 판정은 이 경로가 도달하는가에 달렸다.
     */
    @Test
    @Order(4)
    void replayFailureRecordsMismatchAndRethrows() {
        jdbc.sql("DELETE FROM recovery_runs").update();
        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        // ReplayService는 클래스라 서브클래싱으로 실패를 주입한다. rebuild가 예외를 던지는
        // 상황이 곧 재생 결정론 위반이고, 그때 기록 경로가 도는지가 이 테스트의 대상이다.
        //
        // 대조 전용 진입점을 덮는다. 복구 1 단계가 그 경로로 도므로 여기를 덮어야 주입이
        // 실제 경로에 닿는다. 전량 재생을 덮으면 이 검증이 조용히 무의미해진다.
        ReplayService failing = new ReplayService(commandReader, eventReader, mapper,
                objectMapper, new MarketCatalog(jdbc)) {
            @Override
            public kr.exchange.journal.ReplayResult verifyToMarket(
                    kr.exchange.engine.core.MatchingEngine engine, int marketId,
                    kr.exchange.journal.JournalCheckpoints checkpoints) {
                throw new IllegalStateException("주입 결함: 재생 불일치");
            }
        };
        EngineRecovery recovery = new EngineRecovery(failing, events -> {
        }, lease);
        recovery.setRecoveryRecorder(new kr.exchange.harness.RecoveryRunRecorder(jdbc));
        takeOver(lease);

        assertThrows(IllegalStateException.class,
                () -> recovery.recover(new MatchingEngineImpl()),
                "기록만 남기고 삼키면 승격이 깨진 상태로 진행된다");

        Map<String, Object> row = jdbc.sql("""
                        SELECT fp_replay, fp_live, matched
                        FROM recovery_runs ORDER BY id DESC LIMIT 1
                        """).query().singleRow();
        assertEquals(false, row.get("matched"), "이 행이 C17이 검출하는 대상이다");
        assertEquals("MISMATCH", row.get("fp_replay"));
        assertTrue(String.valueOf(row.get("fp_live")).contains("주입 결함"),
                "fp_live에 원인이 남아야 사후에 무엇이 어긋났는지 안다");
    }

    @Test
    @Order(2)
    void errorInRunLoopTriggersFatalHandlerAndDemote() throws Exception {
        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        takeOver(lease);
        assertTrue(lease.isLeader());
        LeaderTerm fatalTerm = lease.currentTerm().orElseThrow();

        MatchingEngineImpl engine = new MatchingEngineImpl();
        replayService.rebuild(engine);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        // sink가 Error를 던진다. runLoop이 조용히 죽지 않고 fatal 통지 + demote를 강제해야 한다.
        SingleWriterLoop loop = new SingleWriterLoop(64, engine,
                events -> { throw new AssertionError("치명 오류 시뮬레이션"); },
                new JournalCatchUpSource(commandReader, mapper),
                new LoopFatalHandler(lease).andThen(fatal::set));
        lease.onLeadershipLost(loop::halt);
        // 재던진 Error를 워커 스레드 전용 핸들러로 캡처한다. 프로세스 기본 핸들러를 건드리지
        // 않아 재던짐이 테스트 인프라로 새지 않는다. 진입 기본값은 보존했다가 그대로 되돌린다.
        AtomicReference<Throwable> workerUncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previousDefault = Thread.getDefaultUncaughtExceptionHandler();
        loop.setWriterUncaughtExceptionHandler((t, e) -> workerUncaught.set(e));
        loop.start();
        try {
            EngineBridge bridge = new EngineBridge(new CommandJournalAppender(jdbc, tx, lease), loop, mapper);
            bridge.submit(buyIntake(3, 91001, "50"));
            // await(!isRunning)는 running=false와 rethrow 사이 경합을 남긴다. 워커 스레드 종료를
            // join으로 기다려 rethrow가 핸들러로 전파 완료된 뒤 단언한다.
            loop.awaitWriterTermination(Duration.ofSeconds(10).toMillis());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault);
        }
        assertFalse(loop.isRunning(), "fail-fast 후 running=false여야 한다");
        assertTrue(workerUncaught.get() instanceof AssertionError,
                "재던진 Error가 워커 스레드 핸들러로 캡처돼야 한다");
        assertEquals("치명 오류 시뮬레이션", workerUncaught.get().getMessage());
        assertTrue(fatal.get() instanceof Error, "Error가 fatal 경로로 통지돼야 한다");
        assertFalse(lease.isLeader(), "Error fail-fast는 리더십을 내려놓아야 한다");
        assertFalse(lease.tryAcquire(), "재구성 전 재획득 없음: 강등한 리스가 rearm 전에 다시 얻었다");
        // VM 오류가 아니므로 노드 치명이 아니다. 재구성이 끝나면 rearm 으로 후보에 돌아올 수 있다(DB 설계 §5.4-c 5).
        assertFalse(lease.isNodeFatal(), "VM 오류가 아닌 Error를 노드 치명으로 올렸다");

        // 재구성을 마친 수명주기가 rearm 하면 만료 리스를 더 큰 epoch 로 다시 얻는다. 강등 전 기간을 쥔 옛 쓰기는 새 epoch 를
        // 빌리지 못하고 DB 왕복 없이 거부된다.
        assertTrue(lease.rearm(), "노드 치명이 아닌 강등이 rearm 되지 않았다");
        takeOver(lease);
        assertTrue(lease.requireLeader().epoch() > fatalTerm.epoch(), "재획득 epoch 가 늘지 않았다");
        assertThrows(NotLeaderException.class, () -> new CommandJournalAppender(jdbc, tx, lease)
                .append(fatalTerm, new CommandDraft(1, CommandDraft.CommandType.NEW, 3L, "buy-91003", 91003L,
                        BASE_TS + 91003, 91003L, "{}"), null), "끝난 기간의 append 가 거부되지 않았다");
        assertEquals(0, jdbc.sql("SELECT count(*) FROM command_journal WHERE client_order_id = ?")
                .params("buy-91003").query(Long.class).single());
        lease.close();
    }

    /**
     * VM 오류(StackOverflowError)는 노드 치명이다(DB 설계 §5.4-c 5 NODE_FATAL). 루프가 멈추고 리더십을 놓는 것은 같지만,
     * 후보 표식을 rearm 으로 다시 세울 수 없어 리스가 만료돼도 다시 얻지 않는다. 복귀는 재기동뿐이다.
     */
    @Test
    @Order(5)
    void vmErrorInRunLoopIsNodeFatalAndCannotBeRearmed() throws Exception {
        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        takeOver(lease);

        MatchingEngineImpl engine = new MatchingEngineImpl();
        replayService.rebuild(engine);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(64, engine,
                events -> { throw new StackOverflowError("VM 오류 시뮬레이션"); },
                new JournalCatchUpSource(commandReader, mapper),
                new LoopFatalHandler(lease).andThen(fatal::set));
        lease.onLeadershipLost(loop::halt);
        AtomicReference<Throwable> workerUncaught = new AtomicReference<>();
        loop.setWriterUncaughtExceptionHandler((t, e) -> workerUncaught.set(e));
        loop.start();
        EngineBridge bridge = new EngineBridge(new CommandJournalAppender(jdbc, tx, lease), loop, mapper);
        bridge.submit(buyIntake(3, 91002, "50"));
        loop.awaitWriterTermination(Duration.ofSeconds(10).toMillis());

        assertFalse(loop.isRunning(), "VM 오류 뒤 루프가 살아 있다");
        assertTrue(fatal.get() instanceof StackOverflowError, "VM 오류가 치명 경로로 통지되지 않았다");
        assertFalse(lease.isLeader(), "VM 오류 뒤 리더십을 놓지 않았다");
        assertTrue(lease.isNodeFatal(), "VM 오류가 노드 치명으로 올라가지 않았다");
        assertFalse(lease.rearm(), "노드 치명을 rearm 이 풀었다");
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertFalse(lease.tryAcquire(), "노드 치명 리스가 만료 리스를 다시 얻었다(복귀는 재기동뿐이다)");
        lease.close();
    }

    private static boolean tradeCommitted(long sourceCmdSeq) {
        return jdbc.sql("SELECT count(*) FROM event_journal "
                        + "WHERE source_cmd_seq = ? AND event_type = 'TRADE'")
                .params(sourceCmdSeq).query(Long.class).single() > 0;
    }

    private static OrderIntake sellIntake(long userId, long orderId, String price) {
        return new OrderIntake(1, CommandDraft.CommandType.NEW, userId, "sell-" + orderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.SELL, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal("1"), null,
                externalId("sell-" + orderId), null, null);
    }

    private static OrderIntake buyIntake(long userId, long orderId, String price) {
        return new OrderIntake(1, CommandDraft.CommandType.NEW, userId, "buy-" + orderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal("1"), null,
                externalId("buy-" + orderId), null, null);
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

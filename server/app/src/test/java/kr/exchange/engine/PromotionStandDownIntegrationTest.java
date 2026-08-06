package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 승격 재생 실패의 강등 등급 회귀(DB 설계 §5.4 강등 사유 분류, 하네스 검증 §3 E2).
 *
 * 재현 대상은 E2 라이브 실측이다. 스탠바이가 epoch 2를 정상 획득한 123ms 뒤 승격 재생의 정산이
 * assert_current_leader 거부를 받았고, 그 한 번으로 종신 강등해 구 리더와 함께 양 노드가 후보에서
 * 빠지면서 클러스터가 리더 없이 영구 정지했다.
 *
 * 거부는 SQL 계층에서 주입한다. assert_current_leader를 래핑해 앞의 N회를 'fencing 위반'으로
 * 거부시키되 leader_lease 행은 건드리지 않는다. 곧 **리스 권위상으로는 정당한 현재 리더인데
 * assert만 거부하는** 상태이며, 이것이 라이브에서 관측된 형태다. nextval은 롤백되지 않으므로
 * 거부 횟수가 정확히 N으로 고정된다.
 *
 * 고정하는 계약:
 *  (A) 리스 권위가 유지되는 동안의 fencing 거부는 강등 사유가 아니다. 재생을 재시도해 승격을 마친다.
 *  (B) 재생이 예산을 소진해도 노드는 FOLLOWER로 남아 다음 만료 구동 승격의 후보가 된다.
 *      종신 강등한 구 리더가 있어도 클러스터가 리더 없는 상태로 수렴하지 않는다.
 *  (C) 그 회복 경로가 이중 리더·스테일 쓰기를 열지 않는다. 반납은 리스 행에 아무것도 쓰지 않으므로
 *      다음 리더는 종전대로 만료 후 epoch +1 단독 UPDATE로만 정해지고 구 epoch는 계속 거부된다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PromotionStandDownIntegrationTest {

    private static final long BASE_TS = 1_753_200_000_000L;
    private static final int SEGMENT_SIZE = 12;
    // 만료는 전부 행을 직접 만료시켜 유발한다. TTL을 길게 잡아 '아직 만료 전'을 단언하는 구간이
    // 테스트 실행 속도에 흔들리지 않게 한다.
    private static final Duration LEASE_TTL = Duration.ofSeconds(60);
    private static final Duration LEASE_TICK = Duration.ofSeconds(1);

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
    static CommandPayloadMapper mapper;
    static ReplayService replayService;

    private static int nextOrderId = 1;
    private static boolean faultInstalled;

    @BeforeAll
    static void migrate() {
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
        mapper = new CommandPayloadMapper(objectMapper);
        replayService = new ReplayService(commandReader, new EventJournalReader(jdbc),
                mapper, objectMapper, new MarketCatalog(jdbc));
    }

    @AfterAll
    static void tearDown() {
        removeFencingFault();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * (A) 리스 권위가 유지되는 동안의 fencing 거부로는 강등하지 않는다. 라이브에서 이 한 번이
     * 곧 종신 강등이었다. 재시도로 승격을 마치고 epoch도 그대로여야 한다(재선출 churn 없음).
     */
    @Test
    @Order(1)
    void transientFencingRejectionDuringReplayDoesNotDemoteLegitimateLeader() throws Exception {
        writeCrashWindowCommands();
        Standby standby = new Standby();
        try {
            // 첫 배치의 assert 한 번만 거부한다. leader_lease 행은 그대로라 이 노드는 여전히
            // 정당한 현재 리더다.
            installFencingFault(1);
            long epoch = takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.ACCEPTING);

            assertTrue(standby.lease.isLeader(), "권위가 유지된 거부로 리더십을 잃었다");
            assertEquals(epoch, currentEpoch(), "재생 재시도가 재선출을 유발했다(epoch 변동)");
            assertEquals(commandReader.maxSeq(), standby.coordinator.installedEngine().lastAppliedSeq());
            assertEquals(commandReader.maxSeq(), journaledEvents(),
                    "크래시 창 이벤트가 재적재되지 않았다");
        } finally {
            removeFencingFault();
            standby.close();
        }
    }

    /**
     * (B) 재생이 예산을 소진해도 후보로 남는다. 구 리더는 이미 종신 강등(E2에서는 이벤트 sink
     * 실패)이라 후보가 아니고, 이 노드마저 종신 강등하면 승격 가능한 후보가 0이 된다.
     */
    @Test
    @Order(2)
    void exhaustedReplayStandsDownRecoverablyAndClusterRegainsLeader() throws Exception {
        writeCrashWindowCommands();
        // 구 리더 모델: 루프 fail-fast로 종신 강등한 노드. 이후 어떤 경로로도 후보가 되지 않는다.
        LeaderLeaseService oldLeader = new LeaderLeaseService(jdbc, LEASE_TTL, LEASE_TICK);
        takeOver(oldLeader);
        new LoopFatalHandler(oldLeader).accept(new IllegalStateException("이벤트 sink 실패 모델"));
        assertFalse(oldLeader.isLeader());
        assertFalse(oldLeader.tryAcquire(), "종신 강등 노드의 재승격 금지가 풀렸다");

        Standby standby = new Standby();
        try {
            // 전 시도를 거부시킨다(예산 소진 경로).
            installFencingFault(10_000);
            long failedEpoch = takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.FOLLOWER);

            assertFalse(standby.lease.isLeader(), "재생 실패 후에도 리더십을 쥐고 있다");
            assertFalse(standby.coordinator.gate().isAccepting(), "재생 실패인데 접수가 열렸다");
            assertTrue(standby.loop.isRunning(),
                    "회복 가능 반납이 단일 라이터를 정지시켰다(재승격 불가 상태)");
            assertEquals(failedEpoch, currentEpoch(),
                    "반납은 leader_lease에 아무것도 쓰지 않아야 한다");

            // 원인이 걷힌 뒤 같은 노드가 다시 후보가 된다. 리스 만료는 TTL 경과와 동치라
            // 여기서는 행을 만료시켜 결정적으로 재현한다.
            removeFencingFault();
            expireLease();
            assertFalse(oldLeader.tryAcquire(), "구 리더가 되살아나면 안 된다");
            assertTrue(standby.lease.tryAcquire(),
                    "재생 실패 노드가 후보에서 영구 제외됐다(클러스터가 리더 없이 수렴)");

            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.ACCEPTING);
            assertTrue(currentEpoch() > failedEpoch, "재승격 epoch가 단조 증가하지 않았다");
            assertEquals(commandReader.maxSeq(), journaledEvents());
        } finally {
            removeFencingFault();
            standby.close();
            oldLeader.close();
        }
    }

    /**
     * (C) 회복 가능 반납이 이중 리더 창을 열지 않는다. 반납은 리스 행을 건드리지 않으므로 만료
     * 전에는 아무도 탈취하지 못하고, 만료 후 승격은 epoch +1이라 구 epoch가 계속 거부된다.
     */
    @Test
    @Order(3)
    void recoverableStandDownNeverOpensTwoLeaderWindow() throws Exception {
        writeCrashWindowCommands();
        LeaderLeaseService other = new LeaderLeaseService(jdbc, LEASE_TTL, LEASE_TICK);
        Standby standby = new Standby();
        try {
            installFencingFault(10_000);
            long standbyEpoch = takeOver(standby.lease);
            assertFalse(other.tryAcquire(), "유효 리스를 타 노드가 탈취했다");

            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.FOLLOWER);

            // 반납 직후. 리스 행은 아직 만료 전이라 탈취가 열리지 않는다.
            assertFalse(other.tryAcquire(), "반납이 만료 전 탈취 창을 열었다");

            removeFencingFault();
            expireLease();
            assertTrue(other.tryAcquire(), "만료 후 타 노드 승격이 막혔다");
            long otherEpoch = currentEpoch();
            assertEquals(standbyEpoch + 1, otherEpoch, "승격 epoch가 +1 단조가 아니다");

            // 반납한 노드는 유효 리스를 가진 새 리더를 밀어낼 수 없다.
            assertFalse(standby.lease.tryAcquire(), "유효 리스가 있는데 이중 리더가 성립했다");
            // 구 epoch는 계속 fencing된다(스테일 쓰기 0행 거부).
            assertThrows(DataAccessException.class, () -> jdbc
                    .sql("SELECT assert_current_leader(?, ?)")
                    .params(standby.lease.holder(), standbyEpoch)
                    .query((rs, rowNum) -> 0)
                    .list());
        } finally {
            removeFencingFault();
            standby.close();
            other.close();
        }
    }

    /**
     * (D) 재시도 예산은 첫 실패 시각부터 잰다. 승격 진입 시각부터 재면, 크래시 창이 커서 시도
     * 1회가 예산을 넘기는 순간 첫 실패에서 예산이 이미 소진돼 재시도가 한 번도 돌지 않는다.
     * 재승격 비용(TTL 대기 + 전량 재생)이 가장 큰 상황에서 정확히 재시도가 사라지는 결함이다.
     *
     * 음성 대조: 시도 1회를 예산(5초)보다 길게 잡았으므로, 종전 규칙이면 attempts가 1에서 멈추고
     * 상태는 FOLLOWER가 된다. attempts 2 + ACCEPTING이 그 규칙과 갈리는 지점이다.
     */
    @Test
    @Order(4)
    void retryRunsEvenWhenOneReplayExceedsTheRetryBudget() throws Exception {
        writeCrashWindowCommands();
        AtomicReference<ScriptedRecovery> script = new AtomicReference<>();
        Standby standby = new Standby((replay, sink, lease) -> {
            ScriptedRecovery r = new ScriptedRecovery(replay, sink, lease, 1,
                    PromotionCoordinator.REPLAY_RETRY_BUDGET_MILLIS + 1_000, () -> new IllegalStateException("일시 실패"));
            script.set(r);
            return r;
        });
        try {
            long epoch = takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(60))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.ACCEPTING);

            assertEquals(2, script.get().attempts(),
                    "예산을 재생 시간과 합산해 재시도가 사라졌다(큰 재생에서 재시도 0회)");
            assertTrue(standby.lease.isLeader());
            assertEquals(epoch, currentEpoch(), "재생 재시도가 재선출을 유발했다(epoch 변동)");
            assertEquals(commandReader.maxSeq(), journaledEvents(),
                    "크래시 창 이벤트가 재적재되지 않았다");
            assertEquals(0, standby.recoverableStandDowns.get(),
                    "승격에 성공했는데 반납 카운터가 올랐다");
        } finally {
            standby.close();
        }
    }

    /**
     * (E) 예산이 무한 재시도를 막는 성질은 유지한다. 예산은 다음 시도의 시작만 막고 진행 중인
     * 시도를 끊지 않으므로, 재생 1회가 예산보다 긴 경우 시도는 정확히 2회(첫 시도 + 재시도 1회)로
     * 상한된다. 리스 보유 상한 근거가 이 수치다.
     */
    @Test
    @Order(5)
    void longReplayGetsExactlyOneRetryBeforeRecoverableStandDown() throws Exception {
        writeCrashWindowCommands();
        AtomicReference<ScriptedRecovery> script = new AtomicReference<>();
        Standby standby = new Standby((replay, sink, lease) -> {
            ScriptedRecovery r = new ScriptedRecovery(replay, sink, lease, Integer.MAX_VALUE,
                    PromotionCoordinator.REPLAY_RETRY_BUDGET_MILLIS + 1_000, () -> new IllegalStateException("지속 실패"));
            script.set(r);
            return r;
        });
        try {
            long epoch = takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(60))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.FOLLOWER);

            assertEquals(2, script.get().attempts(),
                    "예산이 재시도 횟수를 상한하지 못했다(리스 보유 시간 상한 근거가 깨진다)");
            assertFalse(standby.lease.isLeader());
            assertTrue(standby.loop.isRunning(), "회복 가능 반납이 단일 라이터를 정지시켰다");
            assertEquals(1, standby.recoverableStandDowns.get(),
                    "회복 가능 반납이 관측 축에 잡히지 않는다");
            assertEquals(epoch, currentEpoch(), "반납은 leader_lease에 아무것도 쓰지 않아야 한다");
        } finally {
            standby.close();
        }
    }

    /**
     * (F) VM Error는 재시도 대상이 아니고 종신이다. 회복 가능 등급의 전제는 '같은 재생을 다시
     * 하면 같은 결과'인데 OOM·StackOverflow는 그 전제 밖이다. 재시도는 전량 재생을 3회 반복해
     * 상태를 악화시키고, 회복 가능 반납은 TTL마다 그 반복을 재개시킨다.
     *
     * 음성 대조는 (G)다. 같은 배선에서 RuntimeException이면 시도가 3회 돌고 후보로 남는다.
     */
    @Test
    @Order(6)
    void vmErrorDuringReplayIsNeverRetriedAndIsTerminal() throws Exception {
        writeCrashWindowCommands();
        AtomicReference<ScriptedRecovery> script = new AtomicReference<>();
        Standby standby = new Standby((replay, sink, lease) -> {
            ScriptedRecovery r = new ScriptedRecovery(replay, sink, lease, Integer.MAX_VALUE, 0,
                    () -> new StackOverflowError("승격 재생 VM Error 주입"));
            script.set(r);
            return r;
        });
        try {
            long epoch = takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.DEMOTED);

            assertEquals(1, script.get().attempts(), "VM Error에서 전량 재생을 재시도했다");
            assertFalse(standby.lease.isLeader());
            assertFalse(standby.coordinator.gate().isAccepting());
            assertEquals(0, standby.recoverableStandDowns.get(),
                    "VM Error가 회복 가능 축으로 계상됐다(등급 분리가 무너진다)");
            assertFalse(standby.loop.isRunning(),
                    "종신 강등인데 단일 라이터가 살아 있다(분류 축은 루프 정지 여부다)");
            assertEquals(epoch, currentEpoch(), "종신 강등도 leader_lease에 쓰지 않아야 한다");

            expireLease();
            assertFalse(standby.lease.tryAcquire(),
                    "VM Error로 강등한 노드가 재승격했다(복귀는 재기동뿐이다)");
        } finally {
            standby.close();
        }
    }

    /** (G) (F)의 음성 대조. 같은 실패 횟수라도 Exception이면 시도가 상한까지 돌고 후보로 남는다. */
    @Test
    @Order(7)
    void runtimeFailureRetriesToTheAttemptCapAndStaysRecoverable() throws Exception {
        writeCrashWindowCommands();
        AtomicReference<ScriptedRecovery> script = new AtomicReference<>();
        Standby standby = new Standby((replay, sink, lease) -> {
            ScriptedRecovery r = new ScriptedRecovery(replay, sink, lease, Integer.MAX_VALUE, 0,
                    () -> new IllegalStateException("지속 실패"));
            script.set(r);
            return r;
        });
        try {
            takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.FOLLOWER);

            assertEquals(PromotionCoordinator.REPLAY_ATTEMPTS, script.get().attempts(),
                    "즉시 실패인데 재시도 상한까지 돌지 않았다");
            assertTrue(standby.loop.isRunning());
            assertEquals(1, standby.recoverableStandDowns.get());

            expireLease();
            assertTrue(standby.lease.tryAcquire(), "일시 실패 노드가 후보에서 영구 제외됐다");
        } finally {
            standby.close();
        }
    }

    /**
     * (H) 접수 게이트는 소비자 생존까지 본다. 단일 라이터가 정지한 상태에서 게이트를 열면 저널에
     * 커밋되고 영원히 체결되지 않는 주문이 생긴다. 루프 정지는 강등 등급 분류의 종신 축 그 자체라
     * 회복 가능으로 내리면 죽은 루프 위에서 승격과 반납을 무한 반복한다.
     */
    @Test
    @Order(8)
    void promotionDoesNotOpenTheGateOnAStoppedWriterLoop() throws Exception {
        writeCrashWindowCommands();
        Standby standby = new Standby();
        try {
            standby.loop.stop();
            assertFalse(standby.loop.isRunning(), "루프 정지 전제가 성립하지 않았다");

            long epoch = takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> standby.coordinator.state() == PromotionCoordinator.State.DEMOTED);

            assertFalse(standby.coordinator.gate().isAccepting(),
                    "정지한 루프 위에서 접수가 열렸다");
            assertFalse(standby.lease.isLeader());
            assertEquals(0, standby.recoverableStandDowns.get(),
                    "루프 정지가 회복 가능으로 내려갔다(승격·반납 무한 반복 경로)");
            assertEquals(epoch, currentEpoch(), "강등이 leader_lease를 건드렸다");

            expireLease();
            assertFalse(standby.lease.tryAcquire(), "루프가 죽은 노드가 재승격했다");
        } finally {
            standby.close();
        }
    }

    /**
     * (I) 부팅 개방도 런타임 승격과 같은 조건을 본다. 현 배선은 loop.start()를 bootstrap보다
     * 먼저 부르므로 도달하지 않지만, 불변이 배선 순서에만 있으면 순서가 바뀌는 순간 무음으로
     * 깨진다. 기동 실패는 리스 만료를 거쳐 스탠바이 승격으로 수렴하므로 좀비 리더보다 안전하다.
     */
    @Test
    @Order(9)
    void bootstrapRefusesToOpenTheGateOnAStoppedWriterLoop() {
        LeaderLeaseService bootLease = new LeaderLeaseService(jdbc, LEASE_TTL, LEASE_TICK);
        EventJournalAppender sink = new EventJournalAppender(jdbc, tx, bootLease, objectMapper);
        MatchingEngineImpl engine = new MatchingEngineImpl();
        SingleWriterLoop stopped = new SingleWriterLoop(4096, engine, sink::append,
                new JournalCatchUpSource(commandReader, mapper),
                new AtomicReference<Throwable>()::set);
        PromotionCoordinator coordinator = new PromotionCoordinator(
                new EngineRecovery(replayService, sink::append, bootLease),
                stopped, bootLease, commandReader);
        try {
            assertFalse(stopped.isRunning(), "루프 미기동 전제가 성립하지 않았다");
            assertThrows(IllegalStateException.class, () -> coordinator.bootstrap(engine, true));
            assertFalse(coordinator.gate().isAccepting(), "부팅 경로가 접수를 열었다");
        } finally {
            coordinator.close();
            bootLease.close();
        }
    }

    // ── 배선 ────────────────────────────────────────────────────────────

    /** 스탠바이 한 대. 접수 게이트가 닫힌 채 리스만 경쟁하다가 승격 재생을 밟는다. */
    private static final class Standby implements AutoCloseable {
        final LeaderLeaseService lease = new LeaderLeaseService(jdbc, LEASE_TTL, LEASE_TICK);
        final SingleWriterLoop loop;
        final PromotionCoordinator coordinator;
        // 회복 가능 반납 관측 축(하네스 CICD §5-2). 종신 강등에서는 발화하지 않아야 한다.
        final AtomicInteger recoverableStandDowns = new AtomicInteger();

        Standby() {
            this((replayService, sink, lease) -> new EngineRecovery(replayService, sink, lease));
        }

        Standby(RecoveryFactory recoveryFactory) {
            EventJournalAppender sink = new EventJournalAppender(jdbc, tx, lease, objectMapper);
            MatchingEngineImpl engine = new MatchingEngineImpl();
            loop = new SingleWriterLoop(4096, engine, sink::append,
                    new JournalCatchUpSource(commandReader, mapper),
                    new AtomicReference<Throwable>()::set);
            coordinator = new PromotionCoordinator(
                    recoveryFactory.create(replayService, sink::append, lease),
                    loop, lease, commandReader);
            lease.onLeadershipGained(coordinator::onLeadershipGained);
            lease.onLeadershipLost(coordinator::onLeadershipLost);
            lease.onLeadershipLost(loop::halt);
            lease.onRecoverableStandDown(recoverableStandDowns::incrementAndGet);
            loop.start();
            coordinator.bootstrap(engine, lease.isLeader());
            coordinator.arm();
        }

        @Override
        public void close() throws Exception {
            loop.stop();
            coordinator.close();
            lease.close();
        }
    }

    @FunctionalInterface
    private interface RecoveryFactory {
        EngineRecovery create(ReplayService replayService, SingleWriterLoop.EventSink sink,
                              LeaderLeaseService lease);
    }

    /**
     * 재생 스크립트. 앞의 failures회를 delayMillis 지연 뒤 실패시키고 그 다음부터는 진짜 재생에
     * 위임한다. 지연이 필요한 이유는 M3 조건('시도 1회가 재시도 예산을 넘긴다')이 시간 축으로만
     * 재현되기 때문이다. 실패를 SQL로 주입하면 항상 즉시 실패라 그 조건이 만들어지지 않는다.
     */
    private static final class ScriptedRecovery extends EngineRecovery {

        private final int failures;
        private final long delayMillis;
        private final Supplier<Throwable> fault;
        private final AtomicInteger attempts = new AtomicInteger();

        ScriptedRecovery(ReplayService replayService, SingleWriterLoop.EventSink sink,
                         LeaderLeaseService lease, int failures, long delayMillis,
                         Supplier<Throwable> fault) {
            super(replayService, sink, lease);
            this.failures = failures;
            this.delayMillis = delayMillis;
            this.fault = fault;
        }

        @Override
        public ReplayResult recover(MatchingEngineImpl engine) {
            int attempt = attempts.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("재생 스크립트 지연 중단", e);
                }
            }
            if (attempt > failures) {
                return super.recover(engine);
            }
            Throwable t = fault.get();
            if (t instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) t;
        }

        int attempts() {
            return attempts.get();
        }
    }

    /**
     * 크래시 창 생성: 명령은 저널에 커밋하고 이벤트는 버린다(명령 fsync 후·이벤트 커밋 전 정지).
     * 스탠바이 재생은 이 구간을 pending으로 복원해 재적재 sink를 실제로 태운다.
     */
    private static void writeCrashWindowCommands() throws Exception {
        LeaderLeaseService leader = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        MatchingEngineImpl engine = new MatchingEngineImpl();
        SingleWriterLoop loop = new SingleWriterLoop(4096, engine, events -> { },
                new JournalCatchUpSource(commandReader, mapper),
                new AtomicReference<Throwable>()::set);
        EngineBridge bridge = new EngineBridge(
                new CommandJournalAppender(jdbc, tx, leader), loop, mapper);
        takeOver(leader);
        loop.start();
        try {
            long lastSeq = 0;
            for (int i = 0; i < SEGMENT_SIZE; i++) {
                lastSeq = bridge.submit(sellIntake(nextOrderId++)).seq();
            }
            long target = lastSeq;
            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> engine.lastAppliedSeq() == target);
        } finally {
            loop.stop();
            leader.close();
        }
    }

    private static OrderIntake sellIntake(int orderId) {
        // 전량 동일 방향 지정가 레스팅. 교차가 없어 명령당 ACCEPTED 하나만 방출한다.
        return new OrderIntake(1, CommandDraft.CommandType.NEW, 1, "sd-sell-" + orderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.SELL, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(100_000 + orderId),
                new BigDecimal("1"), null,
                UUID.nameUUIDFromBytes(("sd-sell-" + orderId).getBytes()), null, null);
    }

    // ── 주입·조회 ───────────────────────────────────────────────────────

    /**
     * assert_current_leader 앞의 N회 호출을 fencing 거부로 만든다. 원본은 이름만 바꿔 보존하고
     * 래퍼가 위임한다. 판정 카운터로 시퀀스를 쓰는 이유는 nextval이 롤백되지 않기 때문이다.
     * 거부 트랜잭션이 되감겨도 소진 횟수가 유지돼 주입량이 정확히 N으로 고정된다.
     */
    private static void installFencingFault(long calls) throws Exception {
        removeFencingFault();
        exec("CREATE SEQUENCE test_fencing_fault");
        exec("ALTER FUNCTION assert_current_leader(TEXT, BIGINT) "
                + "RENAME TO assert_current_leader_real");
        exec("""
                CREATE FUNCTION assert_current_leader(p_holder TEXT, p_epoch BIGINT)
                RETURNS void LANGUAGE plpgsql AS $fault$
                BEGIN
                    IF nextval('test_fencing_fault') <= %d THEN
                        RAISE EXCEPTION 'fencing 위반: 주입된 일시 거부';
                    END IF;
                    PERFORM assert_current_leader_real(p_holder, p_epoch);
                END $fault$
                """.formatted(calls));
        faultInstalled = true;
    }

    private static void removeFencingFault() {
        if (!faultInstalled) {
            return;
        }
        faultInstalled = false;
        try {
            exec("DROP FUNCTION assert_current_leader(TEXT, BIGINT)");
            exec("ALTER FUNCTION assert_current_leader_real(TEXT, BIGINT) "
                    + "RENAME TO assert_current_leader");
            exec("DROP SEQUENCE test_fencing_fault");
        } catch (Exception e) {
            throw new IllegalStateException("fencing 주입 원복 실패", e);
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long currentEpoch() {
        return jdbc.sql("SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
                .query(Long.class).single();
    }

    private static long journaledEvents() {
        return jdbc.sql("SELECT count(*) FROM event_journal").query(Long.class).single();
    }

    private static void expireLease() {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
    }

    private static long takeOver(LeaderLeaseService svc) {
        expireLease();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
        return svc.current().orElseThrow().epoch();
    }
}

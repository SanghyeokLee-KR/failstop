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
 * 승격 재생 실패의 강등 등급 회귀(DB 설계 §5.4 강등 사유 분류, 하네스 검증 §3 E2). 재현 대상은
 * 한 번의 fencing 거부가 종신 강등을 불러 양 노드가 후보에서 빠진 E2 라이브 실측이다.
 *
 * 거부는 3인자 판정 assert_lease_holder를 SQL 계층에서 래핑해 앞의 N회만 실패시키고 leader_lease 행은
 * 건드리지 않는다(쓰기 경로 네 곳이 모두 이 함수를 거친다, DB 설계 §5.4-b). 리스 권위상 정당한 리더인데
 * assert만 거부하는 상태가 라이브에서 관측된 형태다. nextval은 롤백되지 않으므로 거부 횟수가 정확히 N으로 고정된다.
 *
 * 고정하는 계약. (A) 리스 권위가 유지되는 동안의 fencing 거부는 강등 사유가 아니다. (B) 예산을
 * 소진해도 노드는 FOLLOWER로 남아 다음 만료 구동 승격의 후보가 된다. (C) 반납이 리스 행에 쓰지
 * 않으므로 그 회복 경로가 이중 리더·스테일 쓰기를 열지 않는다.
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
            // 주입이 쓰기 경로에서 실제로 소비됐다는 대조. 거부가 없어도 위 단언은 모두 통과하기 때문이다.
            assertTrue(faultCalls() > 1, "주입한 거부가 소비되지 않았거나 재시도가 판정을 다시 부르지 않았다");
        } finally {
            removeFencingFault();
            standby.close();
        }
    }

    /**
     * (B) 재생이 예산을 소진해도 후보로 남는다. 구 리더는 이미 강등(E2에서는 이벤트 sink 실패)해 재구성 전에는
     * 후보가 아니고, 이 노드마저 강등하면 승격 가능한 후보가 0이 된다.
     */
    @Test
    @Order(2)
    void exhaustedReplayStandsDownRecoverablyAndClusterRegainsLeader() throws Exception {
        writeCrashWindowCommands();
        // 구 리더 모델: 루프 fail-fast로 강등한 노드. 재구성(rearm) 전에는 어떤 경로로도 후보가 되지 않는다(DB 설계 §5.4-c 5).
        // 이 시험은 재구성하지 않으므로 끝까지 후보가 아니다.
        LeaderLeaseService oldLeader = new LeaderLeaseService(jdbc, LEASE_TTL, LEASE_TICK);
        takeOver(oldLeader);
        new LoopFatalHandler(oldLeader).accept(new IllegalStateException("이벤트 sink 실패 모델"));
        assertFalse(oldLeader.isLeader());
        assertFalse(oldLeader.tryAcquire(), "재구성 전 재획득 없음: 강등한 노드가 rearm 전에 리스를 다시 얻었다");

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
     * 음성 대조: 시도 1회를 예산(5초)보다 길게 잡았으므로, 진입 시각 기준 규칙이면 attempts가 1에서 멈추고
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
     * (H) 접수 게이트는 소비자 생존까지 본다. 정지한 라이터 위에서 게이트를 열면 저널에 커밋되고 영원히 체결되지 않는
     * 주문이 생긴다. 루프 정지는 강등 사유(PROMOTION_LOOP_DOWN)이고 회복 가능 반납이 아니다. 회복 가능으로 내리면 죽은 루프
     * 위에서 승격과 반납을 무한 반복한다.
     *
     * 파티션 배선(S4 설계 6-3)은 루프를 설치 때 기간마다 새로 만들므로 '미리 멈춘 루프' 장면이 성립하지 않는다. 새로 설치한
     * 루프가 게이트 개방 전에 죽도록 주입한다(PartitionNode.loopKillsBeforeGate). 게이트는 열리지 않고, 그 파티션은
     * REBUILDING 을 거쳐 FOLLOWER 로 돌아온다. 주입이 없으면 재구성 뒤 새 epoch 로 승격하고, 창 안에서 같은 주입을 세 번
     * 겪으면 BLOCKED 다. 강등은 리스 행에 쓰지 않는다.
     *
     * 성공 경로의 재생 표식 해제 지점(접수 개방 전)도 여기서 함께 고정된다. 해제가 개방 뒤로 밀리면 이 강등이 표식을 쥔 채
     * 일어나 회복 가능으로 내려가고 반납 계수 단언이 깨진다.
     */
    @Test
    @Order(8)
    void promotionDoesNotOpenTheGateOnAWriterLoopThatDiedBeforeIt() throws Exception {
        String resource = "matching-engine";
        try (kr.exchange.engine.lease.ReservationFixture f =
                     kr.exchange.engine.lease.ReservationFixture.create("pml_loop_down", 2)) {
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, kr.exchange.engine.lease.ReservationFixture.BTC, new BigDecimal("10"));
            // 설치할 마켓이 있어야 루프가 생긴다. 옛 리더가 주문 하나를 정산까지 마치고 죽었다.
            LeaderLeaseService oldLeader = f.lease();
            f.submit(f.appender(oldLeader, true), new MatchingEngineImpl(), f.settlement(oldLeader),
                    f.limit(kr.exchange.engine.lease.ReservationFixture.KRW_BTC, bob, OrderCommand.Side.SELL,
                            "50000", "0.1"));
            oldLeader.close();
            f.owner.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                    + "WHERE resource = ?").param(resource).update();

            try (PartitionNode node = new PartitionNode("node", f, new PartitionNode.Options().protocol(2))) {
                PartitionRuntime runtime = node.runtime(resource);
                AtomicInteger recoverable = new AtomicInteger();
                node.lease(resource).onRecoverableStandDown(recoverable::incrementAndGet);
                node.loopKillsBeforeGate.set(1);
                node.start();

                Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> runtime.demotions().size() == 1);
                assertEquals(DemotionReason.PROMOTION_LOOP_DOWN, runtime.demotions().get(0).reason());
                long downEpoch = node.lease(resource).leaseSnapshot().orElseThrow().epoch();
                assertFalse(node.everAccepting.get(), "죽은 루프 위에서 접수가 열렸다");
                assertEquals(0, recoverable.get(), "루프 정지가 회복 가능으로 내려갔다(승격·반납 무한 반복 경로)");
                assertEquals(1, runtime.breaker().count());
                // REBUILDING 을 거쳐 FOLLOWER 로 돌아오고, 주입이 없으면 옛 리스 만료 뒤 새 epoch 로 승격한다.
                Awaitility.await().atMost(Duration.ofSeconds(30))
                        .until(() -> runtime.state() == PromotionCoordinator.State.ACCEPTING);
                long reacquired = node.lease(resource).currentTerm().orElseThrow().epoch();
                assertTrue(reacquired > downEpoch, "재구성 뒤 승격 epoch 가 늘지 않았다");
                assertTrue(node.everAccepting.get());

                // 같은 주입을 창 안에서 두 번 더 겪으면 세 번째 강등에서 차단된다. 계획 강등으로 다음 승격을 부른다.
                node.loopKillsBeforeGate.set(2);
                runtime.setParticipate(false);
                runtime.setParticipate(true);
                Awaitility.await().atMost(Duration.ofSeconds(60))
                        .until(() -> runtime.state() == PromotionCoordinator.State.BLOCKED);
                assertEquals(3, runtime.demotions().stream()
                        .filter(d -> d.reason() == DemotionReason.PROMOTION_LOOP_DOWN).count());
                assertTrue(runtime.breaker().isOpen());
                assertFalse(node.lease(resource).isArmed(), "차단된 파티션이 후보로 남았다");
                assertEquals(0, recoverable.get());
            }
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

    /**
     * (J) 회복 가능 반납은 재생 구간 표식을 **쥔 채** 이뤄진다. 표식을 반납보다 먼저 걷으면 그
     * 사이에 도착한 renew 거부가 markLost의 종신 경로로 내려가 노드가 후보에서 빠진다. 재생
     * 구간에도 renew 틱이 계속 도는 것이 설계(승격 중 리스 유지)라 이 창은 이론적이지 않다.
     *
     * 주입 지점은 lease.standDown 진입 직전이다. 그 시점의 표식 상태가 등급을 가르는 전부이며,
     * 등급은 두 관측 축(회복 가능·종신 콜백)으로 경합 없이 갈린다.
     */
    @Test
    @Order(10)
    void renewRejectionRacingTheStandDownStaysRecoverable() throws Exception {
        writeCrashWindowCommands();
        RenewRacingLease lease = new RenewRacingLease();
        Standby standby = new Standby((replay, sink, l) ->
                new ScriptedRecovery(replay, sink, l, Integer.MAX_VALUE, 0,
                        () -> new IllegalStateException("지속 실패")), lease);
        try {
            long epoch = takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                    standby.recoverableStandDowns.get() + standby.terminalLosses.get() > 0);

            assertEquals(1, lease.injections(), "renew 거부 주입이 돌지 않아 창을 재현하지 못했다");
            assertEquals(0, standby.terminalLosses.get(),
                    "표식이 반납보다 먼저 걷혀 창에 낀 renew 거부가 종신으로 내려갔다");
            assertEquals(1, standby.recoverableStandDowns.get(),
                    "재생 구간의 renew 거부가 회복 가능 축에 잡히지 않았다");
            assertEquals(PromotionCoordinator.State.FOLLOWER, standby.coordinator.state());
            assertTrue(standby.loop.isRunning(), "회복 가능 반납이 단일 라이터를 정지시켰다");
            assertFalse(standby.lease.isLeader());
            // 주입기가 lease_until을 직접 만료시키므로 여기서 증명되는 것은 epoch 불변까지다.
            assertEquals(epoch, currentEpoch(), "반납이 epoch를 올렸다");

            assertTrue(standby.lease.tryAcquire(), "창에 낀 거부 한 번으로 후보에서 영구 제외됐다");
        } finally {
            standby.close();
        }
    }

    /**
     * (K) 종신 격상은 VM Error에 한정한다(§5.4). 술어가 Error 전체면
     * NoClassDefFoundError처럼 '같은 재생을 다시 하면 같은 결과'가 성립하는 오류까지 종신으로
     * 내려가, 클래스 적재 일시 실패 하나로 노드가 후보에서 영구히 빠진다. (F)의 경계 대조다.
     */
    @Test
    @Order(11)
    void nonVmErrorDuringReplayStaysRecoverable() throws Exception {
        writeCrashWindowCommands();
        AtomicReference<ScriptedRecovery> script = new AtomicReference<>();
        Standby standby = new Standby((replay, sink, lease) -> {
            ScriptedRecovery r = new ScriptedRecovery(replay, sink, lease, Integer.MAX_VALUE, 0,
                    () -> new NoClassDefFoundError("회복 가능 Error 주입"));
            script.set(r);
            return r;
        });
        try {
            long epoch = takeOver(standby.lease);

            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                    standby.recoverableStandDowns.get() + standby.terminalLosses.get() > 0);

            assertEquals(0, standby.terminalLosses.get(),
                    "VM Error가 아닌 Error가 종신으로 내려갔다(후보 소진 경로)");
            assertEquals(1, standby.recoverableStandDowns.get());
            assertEquals(PromotionCoordinator.State.FOLLOWER, standby.coordinator.state());
            assertEquals(PromotionCoordinator.REPLAY_ATTEMPTS, script.get().attempts(),
                    "회복 가능 Error를 재시도 대상에서 뺐다");
            assertTrue(standby.loop.isRunning(), "회복 가능 등급인데 단일 라이터가 정지했다");
            assertEquals(epoch, currentEpoch(), "반납은 leader_lease에 아무것도 쓰지 않아야 한다");

            expireLease();
            assertTrue(standby.lease.tryAcquire(), "클래스 적재 실패로 후보에서 영구 제외됐다");
        } finally {
            standby.close();
        }
    }

    /** 스탠바이 한 대. 접수 게이트가 닫힌 채 리스만 경쟁하다가 승격 재생을 밟는다. */
    private static final class Standby implements AutoCloseable {
        final LeaderLeaseService lease;
        final SingleWriterLoop loop;
        final PromotionCoordinator coordinator;
        // 회복 가능 반납 관측 축(하네스 CICD §5-2). 종신 강등에서는 발화하지 않아야 한다.
        final AtomicInteger recoverableStandDowns = new AtomicInteger();
        // 종신 강등 축. 두 축을 같이 세야 '어느 등급으로 내려갔는가'가 경합 없이 관측된다.
        final AtomicInteger terminalLosses = new AtomicInteger();

        Standby() {
            this((replayService, sink, lease) -> new EngineRecovery(replayService, sink, lease));
        }

        Standby(RecoveryFactory recoveryFactory) {
            this(recoveryFactory, new LeaderLeaseService(jdbc, LEASE_TTL, LEASE_TICK));
        }

        Standby(RecoveryFactory recoveryFactory, LeaderLeaseService lease) {
            this.lease = lease;
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
            lease.onLeadershipLost(terminalLosses::incrementAndGet);
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
     * standDown 진입 직전에 renew 거부를 한 번 주입하는 리스. [표식 해제 -> 반납] 사이의 창을
     * 재현하는 유일한 지점이 여기다. 리스를 만료시키고 하트비트를 한 번 돌리면 renew_leader_lease가
     * 거부하고, markLost가 그 시점의 표식 상태로 등급을 정한다.
     *
     * 재진입 시 주입하지 않는 이유: markLost가 다시 standDown을 부르므로 무한 재귀가 된다.
     */
    private static final class RenewRacingLease extends LeaderLeaseService {

        private final java.util.concurrent.atomic.AtomicBoolean armed =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        private final AtomicInteger injections = new AtomicInteger();

        RenewRacingLease() {
            super(jdbc, LEASE_TTL, LEASE_TICK);
        }

        @Override
        public void standDown(String reason) {
            if (armed.compareAndSet(true, false)) {
                expireLease();
                injections.incrementAndGet();
                renew();
            }
            super.standDown(reason);
        }

        int injections() {
            return injections.get();
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

    /**
     * 3인자 판정 assert_lease_holder 앞의 N회 호출을 fencing 거부로 만든다. 원본은 이름만 바꿔 보존하고
     * 래퍼가 위임한다. 2인자 assert_current_leader 에 걸면 쓰기 경로가 그 주입을 비껴간다(FencingInjectionCoverageTest).
     * 판정 카운터로 시퀀스를 쓰는 이유는 nextval이 롤백되지 않아 주입량이 정확히 N으로 고정되기 때문이다.
     */
    private static void installFencingFault(long calls) throws Exception {
        removeFencingFault();
        exec("CREATE SEQUENCE test_fencing_fault");
        exec("ALTER FUNCTION assert_lease_holder(TEXT, TEXT, BIGINT) "
                + "RENAME TO assert_lease_holder_real");
        exec("""
                CREATE FUNCTION assert_lease_holder(p_resource TEXT, p_holder TEXT, p_epoch BIGINT)
                RETURNS void LANGUAGE plpgsql AS $fault$
                BEGIN
                    IF nextval('test_fencing_fault') <= %d THEN
                        RAISE EXCEPTION 'fencing 위반: 주입된 일시 거부';
                    END IF;
                    PERFORM assert_lease_holder_real(p_resource, p_holder, p_epoch);
                END $fault$
                """.formatted(calls));
        faultInstalled = true;
    }

    /** 주입 래퍼가 불린 횟수. */
    private static long faultCalls() {
        return jdbc.sql("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM test_fencing_fault")
                .query(Long.class).single();
    }

    private static void removeFencingFault() {
        if (!faultInstalled) {
            return;
        }
        faultInstalled = false;
        try {
            exec("DROP FUNCTION assert_lease_holder(TEXT, TEXT, BIGINT)");
            exec("ALTER FUNCTION assert_lease_holder_real(TEXT, TEXT, BIGINT) "
                    + "RENAME TO assert_lease_holder");
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

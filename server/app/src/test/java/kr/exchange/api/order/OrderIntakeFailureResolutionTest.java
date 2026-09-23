package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.api.auth.CanonicalRequests;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주문 접수의 append 가 예외로 끝난 뒤의 멱등 키와 in-flight 예약 판정(V47 resolve_failed_order_intake).
 *
 * 커밋 응답만 잃은 요청은 자기 주문 id 로 커밋을 확인해 정상 접수로 끝나야 한다. 키를 지우거나 예약을 바로 풀면 같은 키
 * 재시도가 409 로 끝나고, 정산 전 창에서 다른 주문이 같은 잔고를 통과해 정산 예약 23514 로 노드가 강등한다. 같은 내용의
 * 다른 요청이 대조한 키(공유 표시)는 넣은 요청이 저널 전에 실패해도 남아야 한다. 판정은 DB 시계를 읽지 않는다.
 *
 * 영속 DB 를 쓰지 않는다. Testcontainers 일회용 컨테이너에만 마이그레이션을 적용한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderIntakeFailureResolutionTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int ETH = 3;
    private static final int KRW_BTC = 1;
    private static final int KRW_ETH = 2;
    private static final BigDecimal RATE = new BigDecimal("0.0005");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static ObjectMapper objectMapper = new ObjectMapper();
    static CommandPayloadMapper payloadMapper;
    static CommandJournalAppender appender;
    static MarketDirectory markets;
    static OrderQueryService orders;
    static SingleWriterLoop loop;
    static EngineBridge liveBridge;
    static OrderGateway liveGateway;
    static final AtomicReference<Throwable> fatal = new AtomicReference<>();
    static long alice;
    static long dora;

    private final ExecutorService pool = Executors.newCachedThreadPool();

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
        config.setMaximumPoolSize(12);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        payloadMapper = new CommandPayloadMapper(objectMapper);

        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.asset(ETH, "ETH", "Ether", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW, RATE, RATE);
        bootstrap.market(KRW_ETH, "KRW-ETH", ETH, KRW, RATE, RATE);
        alice = bootstrap.user("alice@intake.local");
        dora = bootstrap.user("dora@intake.local");
        for (long uid : new long[] {alice, dora}) {
            for (int asset : new int[] {KRW, BTC, ETH}) {
                bootstrap.userAccounts(uid, asset);
            }
        }
        bootstrap.deposit(alice, KRW, new BigDecimal("1000000000"));
        // 주문 하나(50,000,000 x 0.01 = 500,000 KRW 예약)만 낼 수 있는 잔고다.
        bootstrap.deposit(dora, KRW, new BigDecimal("500000"));

        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(30), Duration.ofSeconds(1));
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(lease.tryAcquire(), "테스트 리더 승격 실패");
        appender = new CommandJournalAppender(jdbc, tx, lease);
        SettlementService settlement = new SettlementService(jdbc, tx, lease, objectMapper,
                new MarketCatalog(jdbc), payloadMapper);
        MatchingEngineImpl engine = new MatchingEngineImpl();
        loop = new SingleWriterLoop(256, engine, settlement::appendAndSettle,
                new JournalCatchUpSource(new CommandJournalReader(jdbc), payloadMapper), fatal::set);
        liveBridge = new EngineBridge(appender, loop, payloadMapper);
        loop.start();
        markets = new MarketDirectory(jdbc);
        markets.start();
        orders = new OrderQueryService(jdbc);
        liveGateway = new OrderGateway(jdbc, liveBridge, markets, orders);
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        if (loop != null) {
            loop.stop();
        }
        if (markets != null) {
            markets.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        assertNull(fatal.get(), "정산 라이터가 멈췄다");
    }

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    /** 멈춘 submit 이 풀린 뒤 하는 일. */
    enum AfterRelease {
        /** 저널에 닿지 않고 실패한다. 페일오버 창의 라이터 정지가 이 모양이다. */
        FAIL,
        /** append 커밋 뒤 응답을 잃는다. 실제처럼 라이터 큐에 넣지 않고 커밋 뒤 예외를 던진다. */
        COMMIT_THEN_LOSE_REPLY,
        /** 정상 submit 으로 이어 간다. */
        PROCEED
    }

    /** 게이트웨이가 키를 잡은 뒤 submit 에서 멈췄다가 풀리면 정해진 방식으로 끝나는 요청. */
    static final class StalledBridge extends EngineBridge {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AfterRelease after;

        StalledBridge(AfterRelease after) {
            super(appender, loop, payloadMapper);
            this.after = after;
        }

        @Override
        public SubmitResult submit(OrderIntake intake) {
            reached.countDown();
            try {
                assertTrue(release.await(20, TimeUnit.SECONDS), "멈춘 요청이 풀리지 않았다");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            switch (after) {
                case PROCEED -> {
                    return super.submit(intake);
                }
                case COMMIT_THEN_LOSE_REPLY -> {
                    appender.append(new CommandDraft(intake.marketId(), intake.type(), intake.userId(),
                            intake.clientOrderId(), intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                            payloadMapper.toPayloadJson(intake)));
                    throw new DataAccessResourceFailureException("커밋 응답 유실(시험 주입)");
                }
                default -> throw new IllegalStateException("단일 라이터 루프 정지 상태: 접수 거부");
            }
        }

        static StalledBridge released(AfterRelease after) {
            StalledBridge bridge = new StalledBridge(after);
            bridge.release.countDown();
            return bridge;
        }
    }

    /** 키를 넣은 요청이 멈춘 사이 같은 내용의 요청이 그 키를 공유하고 커밋하면, 앞 요청의 늦은 실패가 키를 지우지 않는다. */
    @Test
    @Order(1)
    void committedOrderKeepsSharedKeyWhenFirstRequestFailsLate() throws Exception {
        String coid = "late-fail-shared";
        byte[] raw = body(coid);
        StalledBridge stalled = new StalledBridge(AfterRelease.FAIL);
        OrderGateway stalledGateway = new OrderGateway(jdbc, stalled, markets, orders);

        Future<OrderGateway.Placement> first = pool.submit(() -> stalledGateway.place(alice, json(raw), raw));
        assertTrue(stalled.reached.await(10, TimeUnit.SECONDS), "원 요청이 submit 에 닿지 못했다");
        assertEquals(requestHash(raw), storedHash(coid), "원 요청이 키를 넣지 않았다");
        assertFalse(keyShared(coid));

        OrderGateway.Placement committed = liveGateway.place(alice, json(raw), raw);
        assertFalse(committed.replayed());
        assertTrue(keyShared(coid), "기존 키로 대조한 요청이 공유 표시를 채우지 않았다");
        assertEquals(1, journaledNew(coid));

        stalled.release.countDown();
        ExecutionException failed = assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
        assertEquals(503, assertInstanceOf(ApiException.class, failed.getCause()).status());

        assertEquals(requestHash(raw), storedHash(coid), "원 요청의 늦은 실패가 커밋된 주문의 멱등 키를 지웠다");
        assertEquals(1, stalledGateway.keysKept());
        OrderGateway.Placement retry = liveGateway.place(alice, json(raw), raw);
        assertTrue(retry.replayed(), "커밋된 주문의 재시도가 멱등 재생으로 수렴하지 않았다");
        assertEquals(committed.row().id(), retry.row().id());
        assertEquals(1, journaledNew(coid));
    }

    @Test
    @Order(2)
    void keyIsDiscardedWhenSubmitFailsWithoutJournal() {
        String coid = "orphan-no-journal";
        byte[] raw = body(coid);
        OrderGateway failingGateway = new OrderGateway(jdbc, StalledBridge.released(AfterRelease.FAIL), markets,
                orders);

        ApiException rejected = assertThrows(ApiException.class, () -> failingGateway.place(alice, json(raw), raw));

        assertEquals(503, rejected.status());
        assertEquals(0, journaledNew(coid));
        assertNull(storedHash(coid), "저널에 없는 요청의 고아 키가 남았다");
        assertEquals(0, failingGateway.keysKept());
        OrderGateway.Placement retry = liveGateway.place(alice, json(raw), raw);
        assertFalse(retry.replayed(), "고아 키를 지운 뒤의 재시도는 새 접수여야 한다");
    }

    /**
     * 커밋 응답 유실. append 는 커밋됐으나 호출자는 예외를 받고 명령은 라이터 큐에 들어가지 않는다.
     *
     * 게이트웨이는 자기 주문 id 로 커밋을 확인하고 라이터에 회수를 요청해, 다음 명령을 기다리지 않고 정상 접수로 끝난다.
     * 키가 남아 같은 키 재시도는 같은 주문의 멱등 재생이다.
     */
    @Test
    @Order(3)
    void lostCommitReplyIsRecoveredAsAcceptedOrder() throws Exception {
        String coid = "ambiguous-commit";
        byte[] raw = body(coid);
        OrderGateway lostAckGateway = new OrderGateway(jdbc,
                StalledBridge.released(AfterRelease.COMMIT_THEN_LOSE_REPLY), markets, orders);

        OrderGateway.Placement placed = lostAckGateway.place(alice, json(raw), raw);

        assertFalse(placed.replayed());
        assertEquals("OPEN", placed.row().status(), "회수 요청 없이는 같은 마켓의 다음 명령까지 정산되지 않는다");
        assertEquals(1, lostAckGateway.commitsRecovered());
        assertEquals(1, journaledNew(coid));
        assertEquals(requestHash(raw), storedHash(coid), "응답을 잃은 커밋의 멱등 키가 지워졌다");

        OrderGateway.Placement retry = liveGateway.place(alice, json(raw), raw);
        assertTrue(retry.replayed(), "응답을 잃은 커밋의 재시도가 멱등 재생으로 수렴하지 않았다");
        assertEquals(placed.row().id(), retry.row().id());
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 기존 키로 대조한 요청(키를 넣지 않은 쪽)도 커밋 응답을 잃을 수 있다. 종전에는 이 경로가 예약을 바로 풀었다.
     * 키를 넣지 않았어도 자기 주문 id 로 커밋을 확인해 정상 접수로 끝난다.
     */
    @Test
    @Order(4)
    void lostCommitReplyOfSharingRequestIsRecovered() {
        String coid = "ambiguous-sharer";
        byte[] raw = body(coid);
        // 앞 요청이 저널 전에 실패하며 남긴 고아 키. 같은 내용이다.
        jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                .params(alice, coid, requestHash(raw))
                .update();
        OrderGateway lostAckGateway = new OrderGateway(jdbc,
                StalledBridge.released(AfterRelease.COMMIT_THEN_LOSE_REPLY), markets, orders);

        OrderGateway.Placement placed = lostAckGateway.place(alice, json(raw), raw);

        assertFalse(placed.replayed());
        assertEquals(1, lostAckGateway.commitsRecovered());
        assertTrue(keyShared(coid));
        assertEquals(1, journaledNew(coid));
        assertTrue(liveGateway.place(alice, json(raw), raw).replayed());
    }

    /**
     * 응답을 잃은 주문이 잔고 전부를 쓰면, 같은 사용자의 다음 주문은 게이트웨이에서 잔고 부족으로 끝나야 한다. 종전에는 예약이
     * 풀린 채 다음 주문이 가드를 통과했고, 그 주문의 append 가 갭 회수로 앞 주문을 먼저 정산한 뒤 자기 정산 예약이 23514 로
     * 실패해 라이터가 멈췄다.
     */
    @Test
    @Order(5)
    void lostCommitReplyDoesNotLetNextOrderOverdraw() {
        byte[] first = body("dora-lost", "50000000");
        OrderGateway lostAckGateway = new OrderGateway(jdbc,
                StalledBridge.released(AfterRelease.COMMIT_THEN_LOSE_REPLY), markets, orders);
        assertFalse(lostAckGateway.place(dora, json(first), first).replayed());

        byte[] second = body("dora-next", "50000000");
        ApiException rejected = assertThrows(ApiException.class, () -> liveGateway.place(dora, json(second), second));

        assertEquals(400, rejected.status());
        assertEquals("insufficient_funds", rejected.name());
        assertNull(fatal.get(), "정산 라이터가 멈췄다");
        assertTrue(loop.isRunning());
    }

    /** 판정은 같은 키로 진행 중인 append 의 커밋을 기다린 뒤 그 저널 행을 보고 자기 커밋으로 확인한다. */
    @Test
    @Order(6)
    void resolutionWaitsForInFlightAppendThenSeesOwnCommit() throws Exception {
        String coid = "inflight-append";
        String hash = "h-" + coid;
        long assignedId = 990_001L;
        jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                .params(alice, coid, hash)
                .update();
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        // 라이터에 넘기지 않는 명령이라 정산되지 않는다. 판정 대상은 저널 행의 실재뿐이다.
        Future<?> append = pool.submit(() -> tx.execute(status -> {
            appender.append(draft(KRW_ETH, coid, assignedId));
            appended.countDown();
            try {
                assertTrue(commit.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return null;
        }));
        assertTrue(appended.await(10, TimeUnit.SECONDS), "append 가 락 안에 들어가지 못했다");

        Future<String> resolution = pool.submit(() -> resolve(coid, hash, assignedId, true));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                .query(Long.class).single() >= 1);
        assertFalse(resolution.isDone(), "판정이 진행 중인 append 를 기다리지 않았다");

        commit.countDown();
        append.get(10, TimeUnit.SECONDS);
        assertEquals("JOURNALED", resolution.get(10, TimeUnit.SECONDS), "커밋된 append 를 자기 커밋으로 보지 못했다");
        assertEquals(hash, storedHash(coid), "커밋된 append 의 키를 지웠다");
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 키보다 먼저 저널에 있던 NEW 는 그 키의 주인이 아니다. 보존 기간이 지나 키를 잃은 옛 주문을 두고 내용이 다른 요청이
     * 저널 전에 실패하면 그 요청의 키는 지워져야 한다. 남기면 그 해시로 재시도가 내용 대조에 성공해 옛 주문을 200 으로
     * 돌려받는다.
     */
    @Test
    @Order(7)
    void keyOfDifferentContentIsDiscardedWhenOnlyAnOlderNewExists() {
        String coid = "purged-key-reuse";
        byte[] original = body(coid, "50000000");
        OrderGateway.Placement placed = liveGateway.place(alice, json(original), original);
        assertFalse(placed.replayed());
        // 보존 기간 경과 삭제를 흉내 낸다. 저널에는 NEW 가 남고 키만 없다.
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = ? AND idem_key = ?").params(alice, coid).update();

        byte[] other = body(coid, "49000000");
        OrderGateway failingGateway = new OrderGateway(jdbc, StalledBridge.released(AfterRelease.FAIL), markets,
                orders);
        ApiException rejected = assertThrows(ApiException.class,
                () -> failingGateway.place(alice, json(other), other));
        assertEquals(503, rejected.status());
        assertNull(storedHash(coid), "옛 NEW 를 주인으로 보고 내용이 다른 요청의 키를 남겼다");
        assertEquals(0, failingGateway.keysKept());

        ApiException reuse = assertThrows(ApiException.class, () -> liveGateway.place(alice, json(other), other));
        assertEquals(409, reuse.status());
        assertEquals("duplicate_client_order_id", reuse.name(), "내용이 다른 재사용이 옛 주문의 재생으로 흡수됐다");
        assertNull(storedHash(coid));
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 같은 내용의 요청 둘이 submit 에서 겹친다. 먼저 키를 넣은 요청이 저널 전에 실패해도 그 키는 두 번째 요청이 대조하며
     * 공유 표시를 채웠으므로 남고, 두 번째 요청의 커밋과 그 재시도가 그 키로 수렴한다.
     */
    @Test
    @Order(8)
    void secondRequestSharesKeySoFirstFailureKeepsIt() throws Exception {
        String coid = "share-before-fail";
        byte[] raw = body(coid);
        StalledBridge failing = new StalledBridge(AfterRelease.FAIL);
        StalledBridge proceed = new StalledBridge(AfterRelease.PROCEED);
        OrderGateway failingGateway = new OrderGateway(jdbc, failing, markets, orders);
        OrderGateway proceedGateway = new OrderGateway(jdbc, proceed, markets, orders);

        Future<OrderGateway.Placement> first = pool.submit(() -> failingGateway.place(alice, json(raw), raw));
        assertTrue(failing.reached.await(10, TimeUnit.SECONDS), "첫 요청이 submit 에 닿지 못했다");
        Future<OrderGateway.Placement> second = pool.submit(() -> proceedGateway.place(alice, json(raw), raw));
        assertTrue(proceed.reached.await(10, TimeUnit.SECONDS), "두 번째 요청이 submit 에 닿지 못했다");
        assertTrue(keyShared(coid));

        failing.release.countDown();
        ExecutionException failed = assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
        assertEquals(503, assertInstanceOf(ApiException.class, failed.getCause()).status());
        assertEquals(requestHash(raw), storedHash(coid), "공유된 키를 첫 요청의 실패가 지웠다");
        assertEquals(1, failingGateway.keysKept());

        proceed.release.countDown();
        OrderGateway.Placement committed = second.get(10, TimeUnit.SECONDS);
        assertFalse(committed.replayed());

        OrderGateway.Placement retry = liveGateway.place(alice, json(raw), raw);
        assertTrue(retry.replayed(), "커밋된 주문의 재시도가 멱등 재생으로 수렴하지 않았다");
        assertEquals(committed.row().id(), retry.row().id());
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 같은 내용의 요청 셋이 섞인 모양. 앞 요청이 지운 뒤 세 번째 요청이 키를 다시 넣고 멈춘 사이, 두 번째 요청이 그 키로 대조해
     * 커밋한다. 세 번째 요청은 자기가 넣은 키라 대조를 못 했지만 공유 표시로 같은 내용임을 알고 재생으로 끝나며 키를 지우지
     * 않는다. 종전에는 대조 불가 409 와 함께 키를 지워 두 번째 요청의 주문이 키를 잃었다.
     */
    @Test
    @Order(9)
    void insertingRequestReplaysThroughSharedKeyInsteadOfDiscardingIt() throws Exception {
        String coid = "three-way";
        byte[] raw = body(coid);
        StalledBridge third = new StalledBridge(AfterRelease.PROCEED);
        OrderGateway thirdGateway = new OrderGateway(jdbc, third, markets, orders);

        Future<OrderGateway.Placement> inserting = pool.submit(() -> thirdGateway.place(alice, json(raw), raw));
        assertTrue(third.reached.await(10, TimeUnit.SECONDS), "키를 넣은 요청이 submit 에 닿지 못했다");
        assertEquals(requestHash(raw), storedHash(coid));

        OrderGateway.Placement committed = liveGateway.place(alice, json(raw), raw);
        assertFalse(committed.replayed());
        assertTrue(keyShared(coid));

        third.release.countDown();
        OrderGateway.Placement replay = inserting.get(10, TimeUnit.SECONDS);
        assertTrue(replay.replayed(), "공유된 키를 넣은 요청이 같은 내용의 커밋을 재생으로 받지 못했다");
        assertEquals(committed.row().id(), replay.row().id());
        assertEquals(requestHash(raw), storedHash(coid), "대조 불가로 보고 커밋된 주문의 키를 지웠다");
        assertTrue(liveGateway.place(alice, json(raw), raw).replayed());
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 보존 기한 삭제로 키를 잃은 옛 주문 O 가 있을 때 내용이 다른 요청 X 와, X 와 같은 내용의 요청 B 가 겹친다. B 는 처음
     * 읽을 때 키를 보지 못해 저널을 보지 않고 가드를 잡았고, 그 사이 X 가 키를 넣어 B 는 INSERT 경합에서 진다.
     *
     * B 는 공유 표시를 채우기 전에 저널을 다시 봐야 한다. O 가 있으므로 표시하지 않고, 잡은 예약을 풀어 가드 없는 재생으로
     * 간다. 종전에는 B 가 표시를 채워 X 의 append 가 O 를 재생해도 표시 때문에 200 O 를 받고 대조하지 못한 키가 남았다.
     *
     * B 의 응답은 기존 키로 대조한 요청이 저널 실재를 본 경로와 같은 재생이다. 대조한 키가 X 가 넣은 대조 전 키라는 점은
     * 게이트웨이가 구별하지 못한다. 보존 기한 삭제가 배선되기 전에는 도달하지 않는 한계다(API 설계 §2-4).
     */
    @Test
    @Order(10)
    void losingRequestDoesNotMarkKeyWhenJournalAlreadyHasNew() throws Exception {
        String coid = "purged-race";
        byte[] original = body(coid, "50000000");
        OrderGateway.Placement old = liveGateway.place(alice, json(original), original);
        assertFalse(old.replayed());
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = ? AND idem_key = ?").params(alice, coid).update();

        byte[] other = body(coid, "49000000");
        StalledBridge xBridge = new StalledBridge(AfterRelease.PROCEED);
        OrderGateway xGateway = new OrderGateway(jdbc, xBridge, markets, orders);
        AtomicReference<Future<OrderGateway.Placement>> x = new AtomicReference<>();
        AtomicBoolean hooked = new AtomicBoolean();
        // B 가 키를 읽고 가드를 잡은 뒤 INSERT 하기 직전에 X 가 키를 넣고 submit 에서 멈추게 한다.
        JdbcClient beforeInsert = intercepting(jdbc, "INSERT INTO idempotency_keys", () -> {
            if (hooked.compareAndSet(false, true)) {
                x.set(pool.submit(() -> xGateway.place(alice, json(other), other)));
                try {
                    assertTrue(xBridge.reached.await(10, TimeUnit.SECONDS), "X 가 submit 에 닿지 못했다");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        });
        OrderGateway bGateway = new OrderGateway(beforeInsert, liveBridge, markets, orders);

        OrderGateway.Placement b = bGateway.place(alice, json(other), other);

        assertTrue(hooked.get(), "B 가 INSERT 경합 경로를 타지 않았다");
        assertTrue(b.replayed(), "저널에 NEW 가 있음을 본 B 가 가드 없는 재생으로 가지 않았다");
        assertFalse(keyShared(coid), "저널에 NEW 가 있는데 경합에서 진 요청이 공유 표시를 채웠다");
        assertEquals(0, bGateway.inflightPending(alice, (short) KRW).signum(), "B 가 잡은 예약이 풀리지 않았다");

        xBridge.release.countDown();
        ExecutionException rejected = assertThrows(ExecutionException.class, () -> x.get().get(10, TimeUnit.SECONDS));
        ApiException reuse = assertInstanceOf(ApiException.class, rejected.getCause(),
                "대조하지 못한 X 가 옛 주문의 재생을 받았다");
        assertEquals(409, reuse.status());
        assertEquals("duplicate_client_order_id", reuse.name());
        assertNull(storedHash(coid), "대조하지 못한 X 의 키가 남았다");
        assertEquals(0, xGateway.inflightPending(alice, (short) KRW).signum());
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 대조하지 못한 재생에서 공유 표시 조회가 DB 오류로 끝나면 대조 불가로 보고 거절한다. 예외를 그대로 올리면 거절 경로의
     * 키 정리가 빠져 이번 요청의 해시가 남고, 다음 재시도가 그 해시로 대조에 성공해 옛 주문을 200 으로 받는다.
     */
    @Test
    @Order(11)
    void sharedMarkLookupFailureRejectsAndDiscardsKey() {
        String coid = "shared-probe-fails";
        byte[] original = body(coid, "50000000");
        assertFalse(liveGateway.place(alice, json(original), original).replayed());
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = ? AND idem_key = ?").params(alice, coid).update();

        byte[] other = body(coid, "49000000");
        JdbcClient probeFails = intercepting(jdbc, "shared_at IS NOT NULL", () -> {
            throw new DataAccessResourceFailureException("공유 표시 조회 실패(시험 주입)");
        });
        OrderGateway gateway = new OrderGateway(probeFails, liveBridge, markets, orders);

        ApiException rejected = assertThrows(ApiException.class, () -> gateway.place(alice, json(other), other));

        assertEquals(409, rejected.status());
        assertEquals("duplicate_client_order_id", rejected.name());
        assertNull(storedHash(coid), "대조하지 못한 해시가 남았다");
        assertEquals(0, gateway.inflightPending(alice, (short) KRW).signum());
        ApiException retry = assertThrows(ApiException.class, () -> liveGateway.place(alice, json(other), other));
        assertEquals(409, retry.status(), "남은 해시로 재시도가 옛 주문의 재생을 받았다");
        assertEquals(1, journaledNew(coid));
    }

    /** sql 문에 fragment 가 든 호출 직전에 action 을 돌리는 JdbcClient. 게이트웨이 코드를 바꾸지 않고 경합 지점을 만든다. */
    private static JdbcClient intercepting(JdbcClient delegate, String fragment, Runnable action) {
        return (JdbcClient) Proxy.newProxyInstance(JdbcClient.class.getClassLoader(),
                new Class<?>[] {JdbcClient.class}, (proxy, method, args) -> {
                    if ("sql".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof String sql && sql.contains(fragment)) {
                        action.run();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static byte[] body(String clientOrderId) {
        return body(clientOrderId, "50000000");
    }

    private static byte[] body(String clientOrderId, String price) {
        return ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"" + price + "\","
                + "\"volume\":\"0.01\",\"client_order_id\":\"" + clientOrderId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode json(byte[] raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String requestHash(byte[] raw) {
        return CanonicalRequests.sha256Hex(CanonicalRequests.jcs(raw));
    }

    private static CommandDraft draft(int marketId, String clientOrderId, long orderId) {
        OrderIntake intake = new OrderIntake(marketId, CommandDraft.CommandType.NEW, alice, clientOrderId,
                orderId, 1_756_000_000_000L + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("3000000"), new BigDecimal("0.1"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes(StandardCharsets.UTF_8)), RATE, RATE);
        return new CommandDraft(intake.marketId(), intake.type(), intake.userId(), intake.clientOrderId(),
                intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(), payloadMapper.toPayloadJson(intake));
    }

    private static String resolve(String clientOrderId, String hash, long assignedId, boolean discard) {
        String outcome = jdbc.sql("SELECT out_outcome FROM resolve_failed_order_intake(?, ?, ?, ?, ?)")
                .params(alice, clientOrderId, hash, assignedId, discard)
                .query(String.class)
                .single();
        assertNotNull(outcome);
        return outcome;
    }

    private static String storedHash(String clientOrderId) {
        return jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(alice, clientOrderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static boolean keyShared(String clientOrderId) {
        return jdbc.sql("SELECT shared_at IS NOT NULL FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(alice, clientOrderId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
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

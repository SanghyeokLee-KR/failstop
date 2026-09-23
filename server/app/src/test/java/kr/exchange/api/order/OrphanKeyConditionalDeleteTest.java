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
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 고아 멱등 키의 조건부 삭제(ADR 0034 S1, V47)와 저널 전 실패의 게이트웨이 처리.
 *
 * 키를 넣은 요청이 멈춘 사이 같은 키·같은 내용의 다른 요청이 저널에 커밋하고, 원 요청이 뒤늦게 저널 전 실패로
 * 끝나도 커밋된 주문의 키는 남아야 한다. 남아야 그 주문의 재시도가 내용 대조 뒤 멱등 재생으로 수렴한다. 반대로
 * 키보다 먼저 있던 NEW 는 키의 주인이 아니어서, 내용이 다른 요청의 키를 남기는 근거가 되지 못한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrphanKeyConditionalDeleteTest {

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
        alice = bootstrap.user("alice@orphan.local");
        for (int asset : new int[] {KRW, BTC, ETH}) {
            bootstrap.userAccounts(alice, asset);
        }
        bootstrap.deposit(alice, KRW, new BigDecimal("1000000000"));

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
        /** 저널에 닿지 않고 실패한다. 페일오버 창의 라이터 정지, 접수 대기 상한, 일시 DB 오류가 이 모양이다. */
        FAIL,
        /** append 커밋 뒤 응답을 잃는다. 실제처럼 라이터 큐에 넣지 않고 커밋 뒤 예외를 던진다. */
        COMMIT_THEN_LOSE_REPLY,
        /** 정상 submit 으로 이어 간다. */
        PROCEED
    }

    /** 게이트웨이가 키를 넣은(또는 본) 뒤 submit 에서 멈췄다가 풀리면 정해진 방식으로 끝나는 요청. */
    static final class StalledBridge extends EngineBridge {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AfterRelease after;
        private final Supplier<RuntimeException> failure;

        StalledBridge(AfterRelease after) {
            this(after, () -> new IllegalStateException("단일 라이터 루프 정지 상태: 접수 거부"));
        }

        StalledBridge(AfterRelease after, Supplier<RuntimeException> failure) {
            super(appender, loop, payloadMapper);
            this.after = after;
            this.failure = failure;
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
                default -> throw failure.get();
            }
        }
    }

    @Test
    @Order(1)
    void committedOrderKeepsKeyWhenFirstRequestFailsLate() throws Exception {
        String coid = "a51-late-fail";
        byte[] raw = body(coid);
        StalledBridge stalled = new StalledBridge(AfterRelease.FAIL);
        OrderGateway stalledGateway = new OrderGateway(jdbc, stalled, markets, orders);

        Future<OrderGateway.Placement> first = pool.submit(() -> stalledGateway.place(alice, json(raw), raw));
        assertTrue(stalled.reached.await(10, TimeUnit.SECONDS), "원 요청이 submit 에 닿지 못했다");
        assertEquals(requestHash(raw), storedHash(coid), "원 요청이 키를 넣지 않았다");

        OrderGateway.Placement committed = liveGateway.place(alice, json(raw), raw);
        assertFalse(committed.replayed());
        assertEquals(1, journaledNew(coid));

        stalled.release.countDown();
        ExecutionException failed = assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
        assertEquals(503, assertInstanceOf(ApiException.class, failed.getCause()).status());

        assertEquals(requestHash(raw), storedHash(coid), "원 요청의 늦은 실패가 커밋된 주문의 멱등 키를 지웠다");
        OrderGateway.Placement retry = liveGateway.place(alice, json(raw), raw);
        assertTrue(retry.replayed(), "커밋된 주문의 재시도가 멱등 재생으로 수렴하지 않았다");
        assertEquals(committed.row().id(), retry.row().id());
        assertEquals(1, journaledNew(coid));
    }

    @Test
    @Order(2)
    void keyIsDiscardedWhenSubmitFailsWithoutJournal() throws Exception {
        String coid = "orphan-no-journal";
        byte[] raw = body(coid);
        StalledBridge failing = new StalledBridge(AfterRelease.FAIL);
        failing.release.countDown();
        OrderGateway failingGateway = new OrderGateway(jdbc, failing, markets, orders);

        ApiException rejected = assertThrows(ApiException.class, () -> failingGateway.place(alice, json(raw), raw));

        assertEquals(503, rejected.status());
        assertEquals(0, journaledNew(coid));
        assertNull(storedHash(coid), "저널에 없는 요청의 고아 키가 남았다");
        OrderGateway.Placement retry = liveGateway.place(alice, json(raw), raw);
        assertFalse(retry.replayed(), "고아 키를 지운 뒤의 재시도는 새 접수여야 한다");
    }

    /**
     * 커밋 응답 유실. append 는 커밋됐으나 호출자는 예외를 받고, 명령은 라이터 큐에 들어가지 않는다.
     *
     * 키는 남고(V47) 재시도는 새 주문을 만들지 않는다. 다만 그 NEW 는 같은 마켓의 다음 명령이 catch-up 을 일으킬
     * 때까지 정산되지 않아, 그 전의 재시도는 멱등 재생으로 인식되고도 409 order_in_progress 로 끝난다. 이것은 원하는
     * 계약이 아니라 지금의 한계를 고정한 것이다. 다음 명령이 들어온 뒤의 재시도는 원 주문을 200 으로 돌려받는다.
     */
    @Test
    @Order(3)
    void ambiguousCommitKeepsKeyAndRetryReplaysAfterCatchUp() throws Exception {
        String coid = "ambiguous-commit";
        byte[] raw = body(coid);
        StalledBridge lostAck = new StalledBridge(AfterRelease.COMMIT_THEN_LOSE_REPLY);
        lostAck.release.countDown();
        OrderGateway lostAckGateway = new OrderGateway(jdbc, lostAck, markets, orders);

        assertThrows(DataAccessResourceFailureException.class, () -> lostAckGateway.place(alice, json(raw), raw));

        assertEquals(1, journaledNew(coid));
        assertEquals(requestHash(raw), storedHash(coid), "응답을 잃은 커밋의 멱등 키가 지워졌다");
        assertEquals(1, lostAckGateway.orphanKeysKept(), "남긴 키를 세지 않았다");

        ApiException pending = assertThrows(ApiException.class, () -> liveGateway.place(alice, json(raw), raw));
        assertEquals(409, pending.status());
        assertEquals("order_in_progress", pending.name(), "재시도가 멱등 재생으로 인식되지 않았다");
        assertEquals(1, journaledNew(coid), "재시도가 새 주문을 저널에 넣었다");

        byte[] next = body("ambiguous-commit-next");
        assertFalse(liveGateway.place(alice, json(next), next).replayed());
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> orders.byClientOrderId(alice, coid).isPresent());

        OrderGateway.Placement retry = liveGateway.place(alice, json(raw), raw);
        assertTrue(retry.replayed(), "응답을 잃은 커밋의 재시도가 멱등 재생으로 수렴하지 않았다");
        assertEquals(orders.byClientOrderId(alice, coid).orElseThrow().id(), retry.row().id());
        assertEquals(1, journaledNew(coid));
    }

    /** 삭제 판정이 진행 중인 같은 키의 append 커밋을 기다린 뒤 그 저널 행을 보고 키를 남긴다. */
    @Test
    @Order(4)
    void discardWaitsForInFlightAppendThenKeepsKey() throws Exception {
        String coid = "inflight-append";
        String hash = "h-" + coid;
        jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                .params(alice, coid, hash)
                .update();
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        // 라이터에 넘기지 않는 마켓이라 이 명령은 정산되지 않는다. 판정 대상은 저널 행의 실재뿐이다.
        Future<?> append = pool.submit(() -> tx.execute(status -> {
            appender.append(draft(KRW_ETH, coid, 990_001L));
            appended.countDown();
            try {
                assertTrue(commit.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return null;
        }));
        assertTrue(appended.await(10, TimeUnit.SECONDS), "append 가 락 안에 들어가지 못했다");

        Future<Boolean> discard = pool.submit(() -> jdbc.sql("SELECT discard_orphan_idempotency_key(?, ?, ?)")
                .params(alice, coid, hash)
                .query(Boolean.class)
                .single());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                .query(Long.class).single() >= 1);
        assertFalse(discard.isDone(), "삭제 판정이 진행 중인 append 를 기다리지 않았다");

        commit.countDown();
        append.get(10, TimeUnit.SECONDS);
        assertFalse(discard.get(10, TimeUnit.SECONDS), "커밋된 append 의 키를 지웠다");
        assertEquals(hash, storedHash(coid));
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 키보다 먼저 저널에 있던 NEW 는 그 키의 주인이 아니다. 보존 기간이 지나 키를 잃은 옛 주문을 두고 내용이 다른 요청이
     * 저널 전에 실패하면 그 요청의 키는 지워져야 한다. 남기면 그 해시로 재시도가 내용 대조에 성공해 옛 주문을 200 으로
     * 돌려받는다.
     */
    @Test
    @Order(5)
    void keyOfDifferentContentIsDiscardedWhenOnlyAnOlderNewExists() throws Exception {
        String coid = "purged-key-reuse";
        byte[] original = body(coid, "50000000");
        OrderGateway.Placement placed = liveGateway.place(alice, json(original), original);
        assertFalse(placed.replayed());
        // 보존 기간 경과 삭제를 흉내 낸다. 저널에는 NEW 가 남고 키만 없다.
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = ? AND idem_key = ?").params(alice, coid).update();

        byte[] other = body(coid, "49000000");
        StalledBridge failing = new StalledBridge(AfterRelease.FAIL);
        failing.release.countDown();
        OrderGateway failingGateway = new OrderGateway(jdbc, failing, markets, orders);
        ApiException rejected = assertThrows(ApiException.class,
                () -> failingGateway.place(alice, json(other), other));
        assertEquals(503, rejected.status());
        assertNull(storedHash(coid), "옛 NEW 를 주인으로 보고 내용이 다른 요청의 키를 남겼다");
        assertEquals(0, failingGateway.orphanKeysKept());

        ApiException reuse = assertThrows(ApiException.class, () -> liveGateway.place(alice, json(other), other));
        assertEquals(409, reuse.status());
        assertEquals("duplicate_client_order_id", reuse.name(), "내용이 다른 재사용이 옛 주문의 재생으로 흡수됐다");
        assertNull(storedHash(coid));
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 키를 넣은 요청이 저널 전에 실패해 키를 지운 뒤, 그 키를 보고 내용을 대조한 두 번째 요청이 저널에 커밋한다.
     * 두 번째 요청이 키를 되살려야 그 주문의 재시도가 잔고 가드나 409 없이 멱등 재생으로 수렴한다.
     */
    @Test
    @Order(6)
    void secondRequestRestoresKeyDeletedBeforeItsAppend() throws Exception {
        String coid = "restore-after-busy";
        byte[] raw = body(coid);
        StalledBridge busy = new StalledBridge(AfterRelease.FAIL,
                () -> new EngineBridge.AcceptBusyException(KRW_BTC, 3_000));
        StalledBridge proceed = new StalledBridge(AfterRelease.PROCEED);
        OrderGateway busyGateway = new OrderGateway(jdbc, busy, markets, orders);
        OrderGateway proceedGateway = new OrderGateway(jdbc, proceed, markets, orders);

        Future<OrderGateway.Placement> first = pool.submit(() -> busyGateway.place(alice, json(raw), raw));
        assertTrue(busy.reached.await(10, TimeUnit.SECONDS), "첫 요청이 submit 에 닿지 못했다");
        Future<OrderGateway.Placement> second = pool.submit(() -> proceedGateway.place(alice, json(raw), raw));
        assertTrue(proceed.reached.await(10, TimeUnit.SECONDS), "두 번째 요청이 submit 에 닿지 못했다");

        busy.release.countDown();
        ExecutionException failed = assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
        ApiException busyRejected = assertInstanceOf(ApiException.class, failed.getCause());
        assertEquals(503, busyRejected.status(), "접수 대기 상한 초과가 API 예외로 바뀌지 않았다");
        assertNull(storedHash(coid), "저널에 없는 첫 요청의 키가 남았다");

        proceed.release.countDown();
        OrderGateway.Placement committed = second.get(10, TimeUnit.SECONDS);
        assertFalse(committed.replayed());
        assertEquals(requestHash(raw), storedHash(coid), "커밋된 주문의 키가 되살아나지 않았다");
        assertEquals(1, proceedGateway.keysRestored());

        OrderGateway.Placement retry = liveGateway.place(alice, json(raw), raw);
        assertTrue(retry.replayed(), "커밋된 주문의 재시도가 멱등 재생으로 수렴하지 않았다");
        assertEquals(committed.row().id(), retry.row().id());
        assertEquals(1, journaledNew(coid));
    }

    /** append 의 잠금 대기 초과는 커밋 전 롤백이라 저널 전 503 이다. 키를 지우고 API 예외로 돌려준다. */
    @Test
    @Order(7)
    void transientAppendErrorIsServiceUnavailableAndDiscardsKey() throws Exception {
        String coid = "append-lock-timeout";
        byte[] raw = body(coid);
        StalledBridge lockTimeout = new StalledBridge(AfterRelease.FAIL, () -> new UncategorizedSQLException(
                "append", "SELECT append_command_prologue(...)",
                new SQLException("canceling statement due to lock timeout", "55P03")));
        lockTimeout.release.countDown();
        OrderGateway gateway = new OrderGateway(jdbc, lockTimeout, markets, orders);

        ApiException rejected = assertThrows(ApiException.class, () -> gateway.place(alice, json(raw), raw));

        assertEquals(503, rejected.status());
        assertEquals("service_unavailable", rejected.name());
        assertEquals(1, rejected.retryAfter());
        assertNull(storedHash(coid));
        assertEquals(0, journaledNew(coid));
    }

    /** 일괄 취소에서 저널에 닿지 못한 취소를 unsubmitted 로 센다. 관리 폐쇄가 이 수로 미완료를 알린다. */
    @Test
    @Order(8)
    void cancelAllCountsCancelsThatNeverReachedJournal() throws Exception {
        byte[] raw = body("cancel-all-busy");
        assertFalse(liveGateway.place(alice, json(raw), raw).replayed());
        int open = orders.openOrderIds(alice, "KRW-BTC").size();
        assertTrue(open >= 1);
        StalledBridge busy = new StalledBridge(AfterRelease.FAIL,
                () -> new EngineBridge.AcceptBusyException(KRW_BTC, 3_000));
        busy.release.countDown();

        OrderGateway.BulkCancelResult result = new OrderGateway(jdbc, busy, markets, orders)
                .cancelAll(alice, "KRW-BTC");

        assertTrue(result.canceledIds().isEmpty());
        assertEquals(open, result.failed().size());
        assertEquals(open, result.unsubmitted(), "저널에 닿지 못한 취소를 세지 않았다");
        assertEquals(open, orders.openOrderIds(alice, "KRW-BTC").size(), "취소되지 않은 주문이 닫혔다");
    }

    private static byte[] body(String clientOrderId) {
        return body(clientOrderId, "50000000");
    }

    private static byte[] body(String clientOrderId, String price) {
        return ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"" + price + "\","
                + "\"volume\":\"0.01\",\"client_order_id\":\"" + clientOrderId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode json(byte[] raw) throws Exception {
        return objectMapper.readTree(raw);
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

    private static String storedHash(String clientOrderId) {
        return jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(alice, clientOrderId)
                .query(String.class)
                .optional()
                .orElse(null);
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

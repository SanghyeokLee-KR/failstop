package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.WriteGateFilter;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.transfer.TransferService;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.WriteAvailability;
import kr.exchange.engine.core.MatchingEngineImpl;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 거래 일시 중단과 게이트웨이(API 설계 §2-5-e, DB 설계 §5.4-f 6). 게이트를 지난 뒤 중단을 넘은 쓰기는 append 하지 않고 423 이며
 * 멱등 키를 남긴다. 중단 동안과 재개 뒤 유예 기간의 by-client 재조회는 거짓 404 를 내지 않는다. ACTIVE 가 아니거나 재확인 전이면
 * 재조회의 200 과 V47 커밋 회수의 201 을 409 로 미룬다.
 *
 * 쓰기 가능 상태는 탐지 스레드 없이 입력을 직접 넣어 움직인다. 커밋 대기 자체는 WriteAvailabilityIntegrationTest 가 본다.
 */
@Testcontainers
class OrderGatewayTradingSuspensionTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int KRW_BTC = 1;
    private static final BigDecimal RATE = new BigDecimal("0.0005");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static final ObjectMapper objectMapper = new ObjectMapper();
    static CommandPayloadMapper payloadMapper;
    static CommandJournalAppender appender;
    static MarketDirectory markets;
    static OrderQueryService orders;
    static SingleWriterLoop loop;
    static EngineBridge liveBridge;
    static final AtomicReference<Throwable> fatal = new AtomicReference<>();
    static long alice;

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);
    private long probeIds;

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
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        payloadMapper = new CommandPayloadMapper(objectMapper);

        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW, RATE, RATE);
        alice = bootstrap.user("alice@suspension.local");
        for (int asset : new int[] {KRW, BTC}) {
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
        loop = new SingleWriterLoop(256, new MatchingEngineImpl(), settlement::appendAndSettle,
                new JournalCatchUpSource(new CommandJournalReader(jdbc), payloadMapper), fatal::set);
        liveBridge = new EngineBridge(appender, loop, payloadMapper);
        loop.start();
        markets = new MarketDirectory(jdbc);
        markets.start();
        orders = new OrderQueryService(jdbc);
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

    /* ===== 쓰기 가능 상태를 손으로 움직인다 ===== */

    private WriteAvailability active() {
        WriteAvailability w = WriteAvailability.enabled(WriteAvailability.Settings.DEFAULTS, nanos::get, wall::get);
        w.probeConnected();
        resume(w);
        return w;
    }

    private void resume(WriteAvailability w) {
        fastProbe(w);
        fastProbe(w);
        nanos.addAndGet(1);
        w.settingRead("\"patroni-db-2\"", false);
        assertTrue(w.isActive());
    }

    private void fastProbe(WriteAvailability w) {
        long id = ++probeIds;
        w.probeSent(id);
        w.probeCompleted(id);
    }

    private void suspend(WriteAvailability w) {
        w.settingRead("*", false);
        assertEquals(WriteAvailability.State.SUSPENDED, w.view().state());
        w.settingRead("\"patroni-db-2\"", false);
    }

    private void passGrace() {
        nanos.addAndGet(Duration.ofSeconds(11).toNanos());
    }

    private OrderGateway gateway(EngineBridge bridge, WriteAvailability w) {
        OrderGateway gateway = new OrderGateway(jdbc, bridge, markets, orders);
        gateway.attachWriteAvailability(w);
        return gateway;
    }

    /* ===== 시험 ===== */

    @Test
    void writeThatCrossedASuspensionIsNotAppendedAndKeepsItsKey() {
        WriteAvailability w = active();
        OrderGateway gateway = gateway(liveBridge, w);
        long gate = w.generation();
        suspend(w);
        resume(w);
        String coid = "crossed-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] raw = body(coid);

        ApiException rejected = assertThrows(ApiException.class, () -> gateway.place(alice, json(raw), raw, gate));

        assertEquals(423, rejected.status());
        assertEquals("trading_suspended", rejected.name());
        assertEquals(0, journaledNew(coid), "중단을 넘은 요청이 append 됐다");
        assertNotNull(storedHash(coid), "멱등 키는 남긴다(고아 해시)");

        OrderGateway.Placement retry = gateway.place(alice, json(raw), raw, w.generation());
        assertFalse(retry.replayed(), "같은 내용의 재전송은 해시 대조를 지나 새 접수가 된다");
        assertEquals(1, journaledNew(coid));
    }

    @Test
    void writeArrivingWhileSuspendedIsRejectedBeforeAppend() {
        WriteAvailability w = active();
        OrderGateway gateway = gateway(liveBridge, w);
        w.settingRead("*", false);
        String coid = "suspended-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] raw = body(coid);

        ApiException rejected = assertThrows(ApiException.class,
                () -> gateway.place(alice, json(raw), raw, WriteGateFilter.NO_GATE));

        assertEquals(423, rejected.status());
        assertEquals(0, journaledNew(coid));
    }

    @Test
    void requeryDoesNotTurnAPendingKeyIntoA404WhileSuspendedOrWithinGrace() {
        WriteAvailability w = active();
        OrderGateway gateway = gateway(liveBridge, w);
        passGrace();
        String coid = "pending-" + UUID.randomUUID().toString().substring(0, 8);
        // 게이트 전에 들어와 키만 넣고 append 가 커밋 대기에 걸린 요청의 흔적이다.
        jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                .params(alice, coid, "0".repeat(64))
                .update();

        assertEquals(404, assertThrows(ApiException.class, () -> gateway.requery(alice, coid)).status(),
                "ACTIVE 이고 유예가 지났으면 저널이 권위다");

        suspend(w);
        ApiException suspended = assertThrows(ApiException.class, () -> gateway.requery(alice, coid));
        assertEquals(409, suspended.status(), "커밋을 기다리는 append 는 저널에 보이지 않는다");
        assertEquals("order_in_progress", suspended.name());
        assertEquals(1, suspended.retryAfter());

        resume(w);
        assertEquals(409, assertThrows(ApiException.class, () -> gateway.requery(alice, coid)).status(),
                "재개 뒤 10초는 늦게 커밋되는 append 를 기다린다");
        passGrace();
        assertEquals(404, assertThrows(ApiException.class, () -> gateway.requery(alice, coid)).status());
        assertEquals(2, w.deferred("requery"));

        String unknown = "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        suspend(w);
        assertEquals(404, assertThrows(ApiException.class, () -> gateway.requery(alice, unknown)).status(),
                "멱등 키 단계도 지나지 않은 키는 중단 중에도 미접수다");
    }

    @Test
    void requeryWithholdsAcceptedUntilActiveAndReconfirmed() {
        WriteAvailability w = active();
        OrderGateway gateway = gateway(liveBridge, w);
        String coid = "accepted-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] raw = body(coid);
        OrderGateway.Placement placed = gateway.place(alice, json(raw), raw, w.generation());

        assertEquals(placed.row().id(), gateway.requery(alice, coid).id());

        w.probeConnected();
        ApiException unconfirmed = assertThrows(ApiException.class, () -> gateway.requery(alice, coid));
        assertEquals(409, unconfirmed.status(), "탐지 연결이 끊긴 뒤에는 크래시 재시작을 배제하지 못한다");
        fastProbe(w);
        nanos.addAndGet(1);
        w.settingRead("\"patroni-db-2\"", false);
        assertEquals(placed.row().id(), gateway.requery(alice, coid).id());

        suspend(w);
        assertEquals(409, assertThrows(ApiException.class, () -> gateway.requery(alice, coid)).status());
        assertEquals(2, w.deferred("requery"));
    }

    @Test
    void recoveredCommitIsNotConfirmedWhileUnconfirmed() {
        WriteAvailability w = active();
        OrderGateway gateway = gateway(new LostReplyBridge(), w);
        w.probeConnected();
        String coid = "v47-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] raw = body(coid);

        ApiException deferred = assertThrows(ApiException.class, () -> gateway.place(alice, json(raw), raw,
                w.generation()));

        assertEquals(409, deferred.status(), "복제 확인 전의 행으로 201 을 내지 않는다");
        assertEquals("order_in_progress", deferred.name());
        assertEquals(1, w.deferred("v47"));
        assertEquals(1, gateway.commitsRecovered(), "V47 이 커밋을 찾고 따라잡기를 요청했다");
        assertEquals(1, journaledNew(coid));
        assertNotNull(storedHash(coid));

        fastProbe(w);
        nanos.addAndGet(1);
        w.settingRead("\"patroni-db-2\"", false);
        OrderGateway.Placement retry = gateway(liveBridge, w).place(alice, json(raw), raw, w.generation());
        assertTrue(retry.replayed(), "확정 뒤 같은 키 재전송은 같은 주문의 멱등 재생이다");
        assertEquals(1, journaledNew(coid));
    }

    /**
     * 같은 키 재전송의 재생 200 은 확정 응답 보류의 대상이 아니다(API 설계 §2-5-e). 재생도 append 머리말이 리스 행을 KEY SHARE 로
     * 잠가 WAL 을 쓰므로 그 커밋이 동기 대기 서버의 확인을 받은 뒤에야 나가고, WAL 순서상 앞선 NEW 도 이미 복제돼 있다. 재생
     * 트랜잭션이 WAL 을 쓰지 않게 바뀌면 이 시험이 깨진다. 그때는 재생에도 definitiveAllowed 를 걸어야 한다.
     */
    @Test
    void sameKeyReplayIsAnsweredOnlyAfterItsOwnSynchronousCommit() throws Exception {
        WriteAvailability w = active();
        OrderGateway gateway = gateway(liveBridge, w);
        String coid = "replay-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] raw = body(coid);
        OrderGateway.Placement placed = gateway.place(alice, json(raw), raw, w.generation());
        // 크래시 재시작 뒤처럼 탐지 연결이 다시 붙었고 확인된 탐지가 아직 없다(재확인 전).
        w.probeConnected();
        assertTrue(w.isActive());
        assertFalse(w.definitiveAllowed());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            setSyncStandbys("ghost");
            Future<String> canary = pool.submit(() -> jdbc.sql("SELECT pg_logical_emit_message(true, 'replay-canary', '')")
                    .query(String.class).single());
            Awaitility.await("동기 설정이 커밋에 걸림").atMost(Duration.ofSeconds(10)).until(() -> syncRepWaiters() >= 1);

            Future<OrderGateway.Placement> replay = pool.submit(() -> gateway.place(alice, json(raw), raw, w.generation()));
            Awaitility.await("재생 트랜잭션도 SyncRep 에서 기다림").atMost(Duration.ofSeconds(10))
                    .until(() -> syncRepWaiters() >= 2);
            assertThrows(TimeoutException.class, () -> replay.get(1, TimeUnit.SECONDS),
                    "동기 대기 서버의 확인 전에 재생 200 이 나갔다");

            setSyncStandbys("");
            OrderGateway.Placement replayed = replay.get(10, TimeUnit.SECONDS);
            assertTrue(replayed.replayed());
            assertEquals(placed.row().id(), replayed.row().id());
            canary.get(10, TimeUnit.SECONDS);
        } finally {
            setSyncStandbys("");
            pool.shutdownNow();
        }
    }

    @Test
    void cancelThatCrossedASuspensionLeavesTheOrderOpen() {
        WriteAvailability w = active();
        OrderGateway gateway = gateway(liveBridge, w);
        String coid = "cancel-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] raw = body(coid);
        OrderGateway.Placement placed = gateway.place(alice, json(raw), raw, w.generation());
        long gate = w.generation();
        suspend(w);
        resume(w);

        ApiException rejected = assertThrows(ApiException.class,
                () -> gateway.cancel(alice, placed.row().externalId(), gate));
        assertEquals(423, rejected.status());
        assertEquals("OPEN", orders.byInternalId(placed.row().id()).orElseThrow().status());

        OrderGateway.BulkCancelResult bulk = gateway.cancelAll(alice, "KRW-BTC", gate);
        assertTrue(bulk.unsubmitted() >= 1, "중단을 넘은 일괄 취소의 원소는 미완료로 센다");
        assertTrue(bulk.failed().stream().anyMatch(f -> "trading_suspended".equals(f.reason())));

        gateway.cancel(alice, placed.row().externalId(), w.generation());
        assertEquals("CANCELED", orders.byInternalId(placed.row().id()).orElseThrow().status());
    }

    @Test
    void withdrawalThatCrossedASuspensionIsNotWritten() {
        WriteAvailability w = active();
        TransferService transfers = new TransferService(jdbc, tx);
        transfers.attachWriteAvailability(w);
        long gate = w.generation();
        suspend(w);
        resume(w);
        String key = "wd-" + UUID.randomUUID();

        ApiException rejected = assertThrows(ApiException.class,
                () -> transfers.request(alice, "WITHDRAW", "KRW", "1000", key, gate));
        assertEquals(423, rejected.status());
        assertEquals(0, transferRows(key), "중단을 넘은 출금 요청이 잠금을 잡았다");

        TransferService.Result first = transfers.request(alice, "WITHDRAW", "KRW", "1000", key, w.generation());
        assertFalse(first.replayed());
        TransferService.Result replay = transfers.request(alice, "WITHDRAW", "KRW", "1000", key, gate);
        assertTrue(replay.replayed(), "이미 커밋된 같은 키는 쓰지 않는 재생이라 세대와 무관하다");
        assertEquals(1, transferRows(key));
    }

    /** append 가 커밋된 뒤 응답을 잃는다. 라이터 큐에 넣지 않는다. */
    static final class LostReplyBridge extends EngineBridge {
        LostReplyBridge() {
            super(appender, loop, payloadMapper);
        }

        @Override
        public SubmitResult submit(OrderIntake intake) {
            appender.append(new CommandDraft(intake.marketId(), intake.type(), intake.userId(),
                    intake.clientOrderId(), intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                    payloadMapper.toPayloadJson(intake)));
            throw new DataAccessResourceFailureException("커밋 응답 유실(시험 주입)");
        }
    }

    private static byte[] body(String clientOrderId) {
        return ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000000\","
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

    private static void setSyncStandbys(String value) {
        jdbc.sql("ALTER SYSTEM SET synchronous_standby_names = '" + value + "'").update();
        jdbc.sql("SELECT pg_reload_conf()").query(Boolean.class).single();
    }

    private static long syncRepWaiters() {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'SyncRep'").query(Long.class).single();
    }

    private static long transferRows(String idemKey) {
        return jdbc.sql("SELECT count(*) FROM transfer_requests WHERE user_id = ? AND idem_key = ?")
                .params(alice, idemKey)
                .query(Long.class)
                .single();
    }
}

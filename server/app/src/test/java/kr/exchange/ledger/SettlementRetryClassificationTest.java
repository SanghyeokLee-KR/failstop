package kr.exchange.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LoopFatalHandler;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.FencingErrors;
import kr.exchange.journal.JournalEntry;
import kr.exchange.journal.TransientDbErrors;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정산 sink 의 오류 분류(DB 설계 §5.4). 다른 세션이 잔고 행을 쥐어 난 잠금 대기 초과(55P03)는 재시도로 흡수하고
 * 강등하지 않는다. 한도를 넘긴 잠금 경합, fencing 거부, 잔고 CHECK 위반은 재시도 없이 지금처럼 치명으로 끝난다.
 *
 * 정산 풀에 lock_timeout 200ms 를 걸어 prod 풀(2초)의 거동을 짧게 재현한다. 잠금을 쥐는 쪽은 상한 없는 별도 세션이다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettlementRetryClassificationTest {

    private static final long BASE_TS = 1_755_000_000_000L;
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
    static CommandPayloadMapper payloadMapper;
    static CommandJournalReader commandReader;
    static MarketCatalog catalog;
    static long alice;
    static long bob;
    static long dave;
    static long nextOrderId = 70_000;

    LeaderLeaseService lease;
    CommandJournalAppender appender;
    SettlementService settlement;

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
        config.setConnectionInitSql("SET TIME ZONE 'UTC'; SET lock_timeout TO '200ms'");
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        payloadMapper = new CommandPayloadMapper(new ObjectMapper());
        commandReader = new CommandJournalReader(jdbc);
        catalog = new MarketCatalog(jdbc);

        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.asset(ETH, "ETH", "Ether", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW, RATE, RATE);
        bootstrap.market(KRW_ETH, "KRW-ETH", ETH, KRW, RATE, RATE);
        alice = bootstrap.user("alice@retry.local");
        bob = bootstrap.user("bob@retry.local");
        dave = bootstrap.user("dave@retry.local");
        for (long uid : new long[] {alice, bob, dave}) {
            for (int asset : new int[] {KRW, BTC, ETH}) {
                bootstrap.userAccounts(uid, asset);
            }
        }
        bootstrap.deposit(alice, KRW, new BigDecimal("1000000000"));
        bootstrap.deposit(bob, BTC, new BigDecimal("10"));
        // 잔고 사전 가드를 거치지 않은 명령이 정산 예약에서 CHECK 에 걸리는 경로를 만든다.
        bootstrap.deposit(dave, KRW, new BigDecimal("1000"));
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void newLeader() {
        // 강등은 종신이라 시험마다 새 리더를 세운다. 앞 시험의 리스는 갱신하지 않으므로 만료시켜 넘겨받는다.
        lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(30), Duration.ofSeconds(1));
        takeOver(lease);
        appender = new CommandJournalAppender(jdbc, tx, lease);
        settlement = new SettlementService(jdbc, tx, lease, new ObjectMapper(), catalog, payloadMapper);
    }

    @Test
    @Order(1)
    void balanceRowLockTimeoutIsRetriedAndAbsorbedWithoutDemotion() throws Exception {
        AtomicInteger demotions = new AtomicInteger();
        lease.onLeadershipLost(demotions::incrementAndGet);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        MatchingEngineImpl engine = new MatchingEngineImpl();
        SingleWriterLoop loop = new SingleWriterLoop(64, engine, settlement::appendAndSettle,
                new JournalCatchUpSource(commandReader, payloadMapper),
                new LoopFatalHandler(lease).andThen(fatal::set));
        EngineBridge bridge = new EngineBridge(appender, loop, payloadMapper);
        loop.start();
        long oid = nextOrderId++;
        try (Connection outside = outsideSession()) {
            holdBalanceRow(outside, account(alice, "AVAILABLE", KRW));
            bridge.submit(limit(KRW_BTC, alice, oid, OrderCommand.Side.BUY, "50000000", "0.01"));
            // 첫 시도가 55P03 으로 롤백된 것을 본 뒤에 잠금을 푼다. 다음 시도가 같은 배치를 처음부터 정산한다.
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> settlement.transientRetries() >= 1);
            outside.rollback();
        }
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(
                () -> queryLong("SELECT count(*) FROM orders WHERE id = " + oid) == 1);
        loop.stop();

        assertNull(fatal.get(), "흡수해야 할 잠금 대기 초과가 라이터를 세웠다");
        assertEquals(0, demotions.get(), "흡수해야 할 잠금 대기 초과가 강등으로 번졌다");
        assertTrue(lease.isLeader());
        assertTrue(settlement.transientRetries() >= 1 && settlement.transientRetries() < 4,
                "재시도 횟수: " + settlement.transientRetries());
        assertEquals("OPEN", queryString("SELECT status FROM orders WHERE id = " + oid));
        assertEquals(1, queryLong("SELECT count(*) FROM ledger_transactions "
                + "WHERE business_event_type = 'RESERVE' AND business_event_id = " + oid), "예약이 두 번 게시됐다");
        assertDecimal("500000", userBalance(alice, KRW, "locked"));
        assertConservation();
    }

    @Test
    @Order(2)
    void lockContentionBeyondRetryBudgetStaysFatal() throws Exception {
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        MatchingEngineImpl engine = new MatchingEngineImpl();
        SingleWriterLoop loop = new SingleWriterLoop(64, engine, settlement::appendAndSettle,
                new JournalCatchUpSource(commandReader, payloadMapper),
                new LoopFatalHandler(lease).andThen(fatal::set));
        EngineBridge bridge = new EngineBridge(appender, loop, payloadMapper);
        loop.start();
        long oid = nextOrderId++;
        long before = settlement.transientRetries();
        try (Connection outside = outsideSession()) {
            holdBalanceRow(outside, account(alice, "AVAILABLE", KRW));
            bridge.submit(limit(KRW_ETH, alice, oid, OrderCommand.Side.BUY, "3000000", "0.1"));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> fatal.get() != null);
            outside.rollback();
        } finally {
            loop.stop();
        }

        assertInstanceOf(DataAccessException.class, fatal.get());
        assertEquals("55P03", TransientDbErrors.sqlState(fatal.get()));
        assertEquals(7, settlement.transientRetries() - before, "시도 상한 8회(재시도 7회)를 지키지 않았다");
        assertEquals(1, settlement.transientExhausted(), "재시도 소진을 세지 않았다");
        assertFalse(lease.isLeader(), "한도를 넘긴 sink 실패가 강등하지 않았다");
        assertEquals(0, queryLong("SELECT count(*) FROM orders WHERE id = " + oid));
        assertConservation();
    }

    @Test
    @Order(3)
    void fencingRejectionIsNotRetriedAndDemotes() {
        long oid = nextOrderId++;
        List<EngineEvent> events = journaledEvents(limit(KRW_BTC, bob, oid, OrderCommand.Side.SELL,
                "60000000", "0.5"));
        LeaderLeaseService usurper = new LeaderLeaseService(jdbc, Duration.ofMinutes(30), Duration.ofSeconds(1));
        takeOver(usurper);
        long before = settlement.transientRetries();

        DataAccessException rejected = assertThrows(DataAccessException.class,
                () -> settlement.appendAndSettle(events));

        assertTrue(FencingErrors.isFencingRejection(rejected), rejected.toString());
        assertEquals(before, settlement.transientRetries(), "fencing 거부를 재시도했다");
        assertFalse(lease.isLeader(), "fencing 거부가 강등으로 이어지지 않았다");
        assertTrue(usurper.isLeader());
        assertEquals(0, queryLong("SELECT count(*) FROM orders WHERE id = " + oid));
    }

    @Test
    @Order(4)
    void balanceCheckViolationIsNotRetried() {
        long oid = nextOrderId++;
        List<EngineEvent> events = journaledEvents(limit(KRW_BTC, dave, oid, OrderCommand.Side.BUY,
                "50000000", "0.01"));
        long before = settlement.transientRetries();

        DataAccessException rejected = assertThrows(DataAccessException.class,
                () -> settlement.appendAndSettle(events));

        assertEquals("23514", TransientDbErrors.sqlState(rejected));
        assertEquals(before, settlement.transientRetries(), "잔고 CHECK 위반을 재시도했다");
        assertEquals(0, settlement.transientExhausted(), "재시도 대상이 아닌 오류를 소진으로 셌다");
        assertEquals(0, queryLong("SELECT count(*) FROM orders WHERE id = " + oid));
        assertDecimal("1000", userBalance(dave, KRW, "available"));
        assertConservation();
    }

    /**
     * 시도 수가 남아도 경과 기한이 지나면 새 시도를 열지 않는다. 한 시도가 문장마다 잠금 상한까지 기다릴 수 있어 시도 수만으로는
     * 라이터가 멈추는 시간이 묶이지 않기 때문이다. 기한을 첫 잠금 대기(200ms)보다 짧게 두면 재시도 없이 올라간다.
     */
    @Test
    @Order(5)
    void retryDeadlineStopsBeforeAttemptLimit() throws Exception {
        settlement.setRetryDeadline(Duration.ofMillis(100));
        long oid = nextOrderId++;
        List<EngineEvent> events = journaledEvents(limit(KRW_ETH, alice, oid, OrderCommand.Side.BUY,
                "3000000", "0.1"));

        DataAccessException exhausted;
        try (Connection outside = outsideSession()) {
            holdBalanceRow(outside, account(alice, "AVAILABLE", KRW));
            exhausted = assertThrows(DataAccessException.class, () -> settlement.appendAndSettle(events));
            outside.rollback();
        }

        assertEquals("55P03", TransientDbErrors.sqlState(exhausted));
        assertEquals(0, settlement.transientRetries(), "기한이 지난 뒤에도 배치를 다시 돌렸다");
        assertEquals(1, settlement.transientExhausted());
        assertEquals(0, queryLong("SELECT count(*) FROM orders WHERE id = " + oid));
        assertConservation();
    }

    /** 저널에 적고 새 엔진에 적용해 정산할 배치를 만든다. 라이터 없이 sink 를 직접 부르는 시험용이다. */
    private List<EngineEvent> journaledEvents(OrderIntake intake) {
        AppendResult ack = appender.append(new CommandDraft(
                intake.marketId(), intake.type(), intake.userId(), intake.clientOrderId(),
                intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                payloadMapper.toPayloadJson(intake)));
        JournalEntry entry = commandReader.readAfter(ack.seq() - 1, 1).get(0);
        List<EngineEvent> events = new MatchingEngineImpl().apply(payloadMapper.toCommand(entry));
        assertFalse(events.isEmpty());
        return events;
    }

    private static Connection outsideSession() throws Exception {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    private static void holdBalanceRow(Connection c, long accountId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM balances WHERE account_id = ? FOR UPDATE")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "잔고 행 없음: " + accountId);
            }
        }
    }

    private static long account(long userId, String purpose, int assetId) {
        return jdbc.sql("""
                        SELECT id FROM accounts
                        WHERE owner_type = 'USER' AND owner_id = ? AND purpose = ? AND asset_id = ? AND shard = 0
                        """)
                .params(userId, purpose, assetId)
                .query(Long.class)
                .single();
    }

    private static OrderIntake limit(int marketId, long userId, long orderId, OrderCommand.Side side,
                                     String price, String qty) {
        String clientOrderId = "rt-" + orderId;
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, userId, clientOrderId,
                orderId, BASE_TS + orderId, orderId, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal(qty), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes()), RATE, RATE);
    }

    private static void takeOver(LeaderLeaseService svc) {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
    }

    private static void assertConservation() {
        // C01 자산 보존, C02 잔고 프로젝션 대조.
        assertEquals(0, queryLong("SELECT count(*) FROM (SELECT asset_id FROM ledger_entries "
                + "GROUP BY asset_id HAVING SUM(amount) <> 0) x"));
        assertEquals(0, queryLong("SELECT count(*) FROM balance_ledger_check WHERE mismatch"));
    }

    private static BigDecimal userBalance(long userId, int assetId, String column) {
        return jdbc.sql("SELECT " + column + " FROM user_balances WHERE user_id = ? AND asset_id = ?")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .single();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "기대 " + expected + ", 실제 " + actual);
    }

    private static String queryString(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private static long queryLong(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

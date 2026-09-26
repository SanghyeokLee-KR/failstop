package kr.exchange.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.JournalEntry;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 신규 주문 타입(POST_ONLY·IOC·FOK·BEST)이 정산 경로(SettlementService)를 실제 구동해
 * DB 원장까지 사유·타입 불가지론적으로 처리됨을 실증한다. SettlementIntegrationTest는
 * LIMIT/MARKET만 덮는다.
 *
 * SettlementIntegrationTest와 저널·시퀀스를 공유하지 않도록 전용 컨테이너를 쓴다. FOK-reject의
 * "원장 무변경" 단언이 다른 테스트의 커밋에 오염되지 않아야 하기 때문이다. 각 케이스는 잔여
 * 호가를 취소로 비워 다음 케이스의 오더북 전제를 고정한다.
 *
 * 케이스: (A) BEST-sell-IOC 부분체결 후 잔량 RELEASE, (B) FOK 미충족 거절(예약 없음),
 * (C) FOK 전량 충족, (D) POST_ONLY 크로스 거절/비크로스 게시+체결, (E) IOC 부분체결 후 취소.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderTypeSettlementIntegrationTest {

    private static final long BASE_TS = 1_753_200_000_000L;
    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int KRW_BTC = 1;
    private static final BigDecimal RATE_5BP = new BigDecimal("0.0005");

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
    static CommandPayloadMapper payloadMapper;
    static MarketCatalog catalog;
    static SettlementService settlement;
    static MatchingEngineImpl engine;

    static long alice;
    static long bob;
    static long carol;
    static long nextOrderId = 9_000;

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
        lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(30), Duration.ofSeconds(1));
        commandAppender = new CommandJournalAppender(jdbc, tx, lease);
        commandReader = new CommandJournalReader(jdbc);
        payloadMapper = new CommandPayloadMapper(objectMapper);
        catalog = new MarketCatalog(jdbc);
        settlement = new SettlementService(jdbc, tx, lease, objectMapper, catalog, payloadMapper);
        engine = new MatchingEngineImpl();

        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW, RATE_5BP, RATE_5BP);
        alice = bootstrap.user("alice@ordtype.test");
        bob = bootstrap.user("bob@ordtype.test");
        carol = bootstrap.user("carol@ordtype.test");
        for (long uid : new long[] {alice, bob, carol}) {
            bootstrap.userAccounts(uid, KRW);
            bootstrap.userAccounts(uid, BTC);
            bootstrap.deposit(uid, KRW, new BigDecimal("1000000000"));
            bootstrap.deposit(uid, BTC, new BigDecimal("100"));
        }
        takeOver(lease);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void bestSellIocPartialFillReleasesRemainder() {
        // best 레벨(50,000,000) 0.01 + 하위 레벨(49,000,000)로 "best 한 레벨만 소비" 조건을 만든다.
        long bestBid = nextOrderId++;
        long deepBid = nextOrderId++;
        submit(limit(bob, bestBid, OrderCommand.Side.BUY, "50000000", "0.01"));
        submit(limit(carol, deepBid, OrderCommand.Side.BUY, "49000000", "1"));

        BigDecimal aliceBtcAvail0 = userBalance(alice, BTC, "available");
        BigDecimal aliceKrwAvail0 = userBalance(alice, KRW, "available");
        BigDecimal krwFee0 = feeBalance(KRW);
        BigDecimal btcFee0 = feeBalance(BTC);

        long best = nextOrderId++;
        submit(best(alice, best, OrderCommand.TimeInForce.IOC, "0.03"));

        // best는 반대편 최우선 레벨 1건(0.01)만 소비하고 잔량 0.02는 IOC로 종단(게시 안 함).
        assertEquals("CANCELED", queryString("SELECT status FROM orders WHERE id = " + best));
        assertDecimal("0.01", orderColumn(best, "filled_qty"));
        assertDecimal("0.02", orderColumn(best, "canceled_qty"));
        assertDecimal("0", orderColumn(best, "remaining_qty"));
        // 락 잔량 0: 예약 0.03 중 0.01 체결 소진, 0.02 해제.
        assertDecimal("0", orderColumn(best, "locked_amount"));
        assertDecimal("0", userBalance(alice, BTC, "locked"));
        // paid_fee 물질화: taker(매도측)는 quote 수수료. 500,000 x 5bp = 250 KRW.
        assertDecimal("250", orderColumn(best, "paid_fee"));
        assertEquals(1L, orderTradesCount(best));

        // trade 정합: 체결가=maker 지정가, taker_side=SELL, 양측 수수료 박제.
        assertEquals(1, queryLong("SELECT count(*) FROM trades WHERE taker_order_id = " + best));
        assertDecimal("50000000", queryDecimal(
                "SELECT price FROM trades WHERE taker_order_id = " + best));
        assertDecimal("0.01", queryDecimal(
                "SELECT quantity FROM trades WHERE taker_order_id = " + best));
        assertEquals("SELL", queryString(
                "SELECT taker_side FROM trades WHERE taker_order_id = " + best));
        assertDecimal("0.000005", queryDecimal(
                "SELECT maker_fee FROM trades WHERE taker_order_id = " + best));
        assertDecimal("250", queryDecimal(
                "SELECT taker_fee FROM trades WHERE taker_order_id = " + best));

        // 정산 경제성: alice는 0.01 BTC를 팔고 500,000 - 250 = 499,750 KRW를 받는다.
        assertDecimal("-0.01", userBalance(alice, BTC, "available").subtract(aliceBtcAvail0));
        assertDecimal("499750", userBalance(alice, KRW, "available").subtract(aliceKrwAvail0));
        assertDecimal("250", feeBalance(KRW).subtract(krwFee0));
        assertDecimal("0.000005", feeBalance(BTC).subtract(btcFee0));

        assertInvariants();
        submit(cancel(carol, deepBid));
    }

    @Test
    @Order(2)
    void fokNotFilledRejectsWithoutReserveOrLedger() {
        long maker = nextOrderId++;
        submit(limit(bob, maker, OrderCommand.Side.BUY, "50000000", "0.01"));

        // 스냅샷은 maker 게시 후. FOK 거절이 유발하는 원장 델타만 관측한다.
        long ledgerTx0 = queryLong("SELECT count(*) FROM ledger_transactions");
        long ledgerEntry0 = queryLong("SELECT count(*) FROM ledger_entries");
        BigDecimal aliceBtcLocked0 = userBalance(alice, BTC, "locked");
        BigDecimal aliceBtcAvail0 = userBalance(alice, BTC, "available");

        // 매도 0.05 요청 > 크로스 유동성 0.01 → 전량 충족 불가.
        long fok = nextOrderId++;
        submit(limitTif(alice, fok, OrderCommand.Side.SELL, "50000000", "0.05",
                OrderCommand.TimeInForce.FOK));

        assertEquals("REJECTED", queryString("SELECT status FROM orders WHERE id = " + fok));
        assertEquals("fok_not_filled",
                queryString("SELECT reject_reason FROM orders WHERE id = " + fok));
        assertDecimal("0", orderColumn(fok, "locked_amount"));
        // reserve_funds 미호출: 락도 available 이동도 없다.
        assertDecimal(aliceBtcLocked0.toPlainString(), userBalance(alice, BTC, "locked"));
        assertDecimal(aliceBtcAvail0.toPlainString(), userBalance(alice, BTC, "available"));
        // 원장 무변경: 거절은 event_journal의 REJECTED만 남기고 원장을 건드리지 않는다.
        assertEquals(ledgerTx0, queryLong("SELECT count(*) FROM ledger_transactions"));
        assertEquals(ledgerEntry0, queryLong("SELECT count(*) FROM ledger_entries"));
        assertEquals(0, queryLong("SELECT count(*) FROM ledger_transactions "
                + "WHERE business_event_type = 'RESERVE' AND business_event_id = " + fok));
        assertEquals(0, queryLong("SELECT count(*) FROM trades WHERE taker_order_id = " + fok));
        // maker 호가는 무손상으로 남는다.
        assertEquals("OPEN", queryString("SELECT status FROM orders WHERE id = " + maker));

        assertInvariants();
        submit(cancel(bob, maker));
    }

    @Test
    @Order(3)
    void fokFullyFillsAndSettles() {
        long maker = nextOrderId++;
        submit(limit(bob, maker, OrderCommand.Side.BUY, "50000000", "0.05"));

        BigDecimal aliceBtcAvail0 = userBalance(alice, BTC, "available");
        BigDecimal aliceKrwAvail0 = userBalance(alice, KRW, "available");

        // 매도 0.03 <= 크로스 유동성 0.05 → 전량 충족.
        long fok = nextOrderId++;
        submit(limitTif(alice, fok, OrderCommand.Side.SELL, "50000000", "0.03",
                OrderCommand.TimeInForce.FOK));

        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + fok));
        assertDecimal("0.03", orderColumn(fok, "filled_qty"));
        assertDecimal("0", orderColumn(fok, "remaining_qty"));
        // 락 잔량 0: base 예약 0.03 전량 소진.
        assertDecimal("0", orderColumn(fok, "locked_amount"));
        assertDecimal("0", userBalance(alice, BTC, "locked"));
        // paid_fee: 1,500,000 x 5bp = 750 KRW.
        assertDecimal("750", orderColumn(fok, "paid_fee"));
        assertEquals(1L, orderTradesCount(fok));
        assertEquals(1, queryLong("SELECT count(*) FROM trades WHERE taker_order_id = " + fok));
        assertDecimal("0.03", queryDecimal(
                "SELECT quantity FROM trades WHERE taker_order_id = " + fok));

        // 경제성: 0.03 BTC 매도, 1,500,000 - 750 = 1,499,250 KRW 수취.
        assertDecimal("-0.03", userBalance(alice, BTC, "available").subtract(aliceBtcAvail0));
        assertDecimal("1499250", userBalance(alice, KRW, "available").subtract(aliceKrwAvail0));

        assertEquals("PARTIAL", queryString("SELECT status FROM orders WHERE id = " + maker));
        assertInvariants();
        submit(cancel(bob, maker));
    }

    @Test
    @Order(4)
    void postOnlyRejectsOnCrossAndRestsOtherwise() {
        // 반대편 best ask 게시.
        long ask = nextOrderId++;
        submit(limit(bob, ask, OrderCommand.Side.SELL, "50000000", "0.01"));

        // D-1 크로스: 매수 50,000,000 >= best ask 50,000,000 → post_only_would_take 거절.
        long ledgerTx0 = queryLong("SELECT count(*) FROM ledger_transactions");
        BigDecimal aliceKrwLocked0 = userBalance(alice, KRW, "locked");
        long cross = nextOrderId++;
        submit(limitTif(alice, cross, OrderCommand.Side.BUY, "50000000", "0.01",
                OrderCommand.TimeInForce.POST_ONLY));
        assertEquals("REJECTED", queryString("SELECT status FROM orders WHERE id = " + cross));
        assertEquals("post_only_would_take",
                queryString("SELECT reject_reason FROM orders WHERE id = " + cross));
        assertDecimal("0", orderColumn(cross, "locked_amount"));
        assertDecimal(aliceKrwLocked0.toPlainString(), userBalance(alice, KRW, "locked"));
        assertEquals(ledgerTx0, queryLong("SELECT count(*) FROM ledger_transactions"),
                "post_only 크로스 거절은 원장을 건드리지 않는다");

        submit(cancel(bob, ask));

        // D-2 비크로스: 매수 49,000,000 < best ask(없음) → 게시(예약 정산) 후 taker가 체결.
        long rest = nextOrderId++;
        submit(limitTif(alice, rest, OrderCommand.Side.BUY, "49000000", "0.01",
                OrderCommand.TimeInForce.POST_ONLY));
        assertEquals("OPEN", queryString("SELECT status FROM orders WHERE id = " + rest));
        // 예약 정산: quote 락 UP(0.01 x 49,000,000) = 490,000.
        assertDecimal("490000", orderColumn(rest, "locked_amount"));
        assertDecimal("490000", userBalance(alice, KRW, "locked"));
        assertEquals(1, queryLong("SELECT count(*) FROM ledger_transactions "
                + "WHERE business_event_type = 'RESERVE' AND business_event_id = " + rest));

        // taker 매도로 게시된 POST_ONLY(maker)를 체결 → 정산 경로 완주.
        long taker = nextOrderId++;
        submit(limit(carol, taker, OrderCommand.Side.SELL, "49000000", "0.01"));
        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + rest));
        assertDecimal("0", orderColumn(rest, "locked_amount"));
        assertDecimal("0", userBalance(alice, KRW, "locked"));
        // maker(매수측) paid_fee: base 수수료 0.01 x 5bp = 0.000005 BTC.
        assertDecimal("0.000005", orderColumn(rest, "paid_fee"));
        assertEquals(1L, orderTradesCount(rest));
        assertDecimal("49000000", queryDecimal(
                "SELECT price FROM trades WHERE taker_order_id = " + taker));

        assertInvariants();
    }

    @Test
    @Order(5)
    void iocPartialFillSettlesFilledAndCancelsRemainder() {
        long maker = nextOrderId++;
        submit(limit(bob, maker, OrderCommand.Side.SELL, "50000000", "0.01"));

        BigDecimal aliceKrwAvail0 = userBalance(alice, KRW, "available");
        BigDecimal aliceBtcAvail0 = userBalance(alice, BTC, "available");

        // 매수 0.03 IOC. 크로스 유동성 0.01만 체결, 0.02는 게시 없이 취소.
        long ioc = nextOrderId++;
        submit(limitTif(alice, ioc, OrderCommand.Side.BUY, "50000000", "0.03",
                OrderCommand.TimeInForce.IOC));

        assertEquals("CANCELED", queryString("SELECT status FROM orders WHERE id = " + ioc));
        assertDecimal("0.01", orderColumn(ioc, "filled_qty"));
        assertDecimal("0.02", orderColumn(ioc, "canceled_qty"));
        assertDecimal("0", orderColumn(ioc, "remaining_qty"));
        // 락 잔량 0: 예약 1,500,000 중 500,000 체결 소진, 1,000,000 해제.
        assertDecimal("0", orderColumn(ioc, "locked_amount"));
        assertDecimal("0", userBalance(alice, KRW, "locked"));
        // paid_fee: taker(매수측) base 수수료 0.01 x 5bp = 0.000005 BTC.
        assertDecimal("0.000005", orderColumn(ioc, "paid_fee"));
        assertEquals(1L, orderTradesCount(ioc));
        assertEquals(1, queryLong("SELECT count(*) FROM trades WHERE taker_order_id = " + ioc));
        assertEquals("BUY", queryString(
                "SELECT taker_side FROM trades WHERE taker_order_id = " + ioc));

        // 경제성: 500,000 KRW 지불, 0.01 - 0.000005 = 0.009995 BTC 수취.
        assertDecimal("-500000", userBalance(alice, KRW, "available").subtract(aliceKrwAvail0));
        assertDecimal("0.009995", userBalance(alice, BTC, "available").subtract(aliceBtcAvail0));

        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + maker));
        assertInvariants();
    }

    private static void assertInvariants() {
        // C01 자산 보존: 자산별 원장 합 0.
        assertEquals(0, queryLong("SELECT count(*) FROM (SELECT asset_id FROM ledger_entries "
                + "GROUP BY asset_id HAVING SUM(amount) <> 0) x"));
        // C02 잔고 프로젝션 대조.
        assertEquals(0, queryLong("SELECT count(*) FROM balance_ledger_check WHERE mismatch"));
        // 봉인 트랜잭션 자산별 균형.
        assertEquals(0, queryLong("SELECT count(*) FROM ledger_transactions WHERE status = 'OPEN'"));
        assertEquals(0, queryLong("""
                SELECT count(*) FROM (
                    SELECT l.transaction_id FROM ledger_entries l
                    JOIN ledger_transactions t ON t.id = l.transaction_id AND t.status = 'SEALED'
                    GROUP BY l.transaction_id, l.asset_id HAVING SUM(l.amount) <> 0) x
                """));
        // trades와 TRADE 이벤트 1:1.
        assertEquals(0, queryLong("""
                SELECT count(*) FROM trades t
                LEFT JOIN event_journal e ON e.event_seq = t.id AND e.event_type = 'TRADE'
                WHERE e.event_seq IS NULL
                """));
        // FILLED/CANCELED 종결 주문은 잔여·락 0.
        assertEquals(0, queryLong("SELECT count(*) FROM orders "
                + "WHERE status IN ('FILLED','CANCELED') "
                + "AND (locked_amount <> 0 OR (remaining_qty IS NOT NULL AND remaining_qty <> 0))"));
        // LOCKED 계정 잔고 = 열린 주문 잔여 예약 합.
        assertEquals(0, lockedMismatchCount());
    }

    private static long lockedMismatchCount() {
        return queryLong("""
                SELECT count(*) FROM balances b
                JOIN accounts a ON a.id = b.account_id AND a.purpose = 'LOCKED'
                LEFT JOIN (
                    SELECT locked_account_id, SUM(locked_amount) AS lock_sum
                    FROM orders WHERE status IN ('OPEN','PARTIAL')
                    GROUP BY locked_account_id) o ON o.locked_account_id = b.account_id
                WHERE b.balance <> COALESCE(o.lock_sum, 0)
                """);
    }

    // 단일 라이터와 같은 순서로 돈다: append 후 미적용 저널을 전량 적용·정산한다.

    private static AppendResult append(OrderIntake intake) {
        return commandAppender.append(new CommandDraft(
                intake.marketId(), intake.type(), intake.userId(), intake.clientOrderId(),
                intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                payloadMapper.toPayloadJson(intake)));
    }

    private static void submit(OrderIntake intake) {
        append(intake);
        drain();
    }

    private static void drain() {
        while (true) {
            List<JournalEntry> batch = commandReader.readAfter(engine.lastAppliedSeq(), 200);
            if (batch.isEmpty()) {
                return;
            }
            for (JournalEntry entry : batch) {
                List<EngineEvent> events = engine.apply(payloadMapper.toCommand(entry));
                if (!events.isEmpty()) {
                    settlement.appendAndSettle(events);
                }
            }
        }
    }

    private static OrderIntake limit(long userId, long orderId, OrderCommand.Side side,
                                     String price, String qty) {
        return limitTif(userId, orderId, side, price, qty, OrderCommand.TimeInForce.GTC);
    }

    private static OrderIntake limitTif(long userId, long orderId, OrderCommand.Side side,
                                        String price, String qty, OrderCommand.TimeInForce tif) {
        return new OrderIntake(KRW_BTC, CommandDraft.CommandType.NEW, userId, "ot-" + orderId,
                orderId, BASE_TS + orderId, orderId, side, OrderCommand.OrdType.LIMIT,
                tif, new BigDecimal(price), new BigDecimal(qty), null,
                externalId(orderId), RATE_5BP, RATE_5BP);
    }

    private static OrderIntake best(long userId, long orderId, OrderCommand.TimeInForce tif,
                                    String qty) {
        return new OrderIntake(KRW_BTC, CommandDraft.CommandType.NEW, userId, "ot-" + orderId,
                orderId, BASE_TS + orderId, orderId, OrderCommand.Side.SELL,
                OrderCommand.OrdType.BEST, tif, null, new BigDecimal(qty), null,
                externalId(orderId), RATE_5BP, RATE_5BP);
    }

    private static OrderIntake cancel(long userId, long targetOrderId) {
        long ts = BASE_TS + 700_000 + nextOrderId++;
        return new OrderIntake(KRW_BTC, CommandDraft.CommandType.CANCEL, userId, null,
                targetOrderId, ts, ts, null, null, null, null, null, null, null, null, null);
    }

    private static UUID externalId(long orderId) {
        return UUID.nameUUIDFromBytes(("ot-" + orderId).getBytes());
    }

    private static void takeOver(LeaderLeaseService svc) {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
    }

    private static BigDecimal userBalance(long userId, int assetId, String column) {
        return jdbc.sql("SELECT " + column + " FROM user_balances "
                        + "WHERE user_id = ? AND asset_id = ?")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .single();
    }

    // P1 정산은 샤드 0 에 게시한다. V55 뒤에는 자산마다 샤드 1 계정(잔고 0)도 있어 샤드를 좁혀 한 행을 읽는다(ADR 0034 S7).
    private static BigDecimal feeBalance(int assetId) {
        return jdbc.sql("""
                        SELECT b.balance FROM balances b
                        JOIN accounts a ON a.id = b.account_id
                        WHERE a.owner_type = 'SYSTEM' AND a.purpose = 'FEE_INCOME' AND a.asset_id = ? AND a.shard = 0
                        """)
                .params(assetId)
                .query(BigDecimal.class)
                .single();
    }

    private static BigDecimal orderColumn(long orderId, String column) {
        return queryDecimal("SELECT " + column + " FROM orders WHERE id = " + orderId);
    }

    private static long orderTradesCount(long orderId) {
        return queryLong("SELECT trades_count FROM orders WHERE id = " + orderId);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertTrue(new BigDecimal(expected).compareTo(actual) == 0,
                "기대 " + expected + ", 실제 " + actual);
    }

    private static BigDecimal queryDecimal(String sql) {
        return jdbc.sql(sql).query(BigDecimal.class).single();
    }

    private static String queryString(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private static long queryLong(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

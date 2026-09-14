package kr.exchange.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.EngineRecovery;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.JournalEntry;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-3 정산 계층 통합 검증(하네스 T-C 상당 + §5.5 ② 크래시 창).
 * (a) 접수 예약, (b) §5.2 단일 체결 전문, (c) 부분 체결 연쇄의 잔차 흡수와 FILLED 락 소진,
 * (d) STP 감액 + 무작위 시퀀스의 자산 보존, (e) 크래시 창 재정산 멱등, (f) 중복 정산 스킵.
 * 저널·카운터가 전역 공유 자원이라 순서를 고정한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettlementIntegrationTest {

    private static final long BASE_TS = 1_753_100_000_000L;
    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int TBASE = 3;
    private static final int TQUOTE = 4;
    private static final int KRW_BTC = 1;
    private static final int T_MARKET = 2;
    private static final BigDecimal RATE_5BP = new BigDecimal("0.0005");
    private static final BigDecimal RATE_10BP = new BigDecimal("0.001");

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
    static ReplayService replayService;
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
        // 클래스 전체가 갱신 없이 한 리스로 달리므로 TTL을 테스트 시간보다 길게 잡는다.
        lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(30), Duration.ofSeconds(1));
        commandAppender = new CommandJournalAppender(jdbc, tx, lease);
        commandReader = new CommandJournalReader(jdbc);
        payloadMapper = new CommandPayloadMapper(objectMapper);
        catalog = new MarketCatalog(jdbc);
        settlement = new SettlementService(jdbc, tx, lease, objectMapper, catalog, payloadMapper);
        replayService = new ReplayService(commandReader,
                new kr.exchange.journal.EventJournalReader(jdbc), payloadMapper, objectMapper, catalog);
        engine = new MatchingEngineImpl();

        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.asset(TBASE, "TBASE", "Test Base", "CRYPTO", 18);
        bootstrap.asset(TQUOTE, "TQUOTE", "Test Quote", "CRYPTO", 18);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW, RATE_5BP, RATE_5BP);
        bootstrap.market(T_MARKET, "TQUOTE-TBASE", TBASE, TQUOTE, RATE_5BP, RATE_10BP);
        alice = bootstrap.user("alice@test.local");
        bob = bootstrap.user("bob@test.local");
        carol = bootstrap.user("carol@test.local");
        for (long uid : new long[] {alice, bob, carol}) {
            for (int asset : new int[] {KRW, BTC, TBASE, TQUOTE}) {
                bootstrap.userAccounts(uid, asset);
            }
            bootstrap.deposit(uid, KRW, new BigDecimal("1000000000"));
            bootstrap.deposit(uid, BTC, new BigDecimal("100"));
            bootstrap.deposit(uid, TBASE, new BigDecimal("1000000"));
            bootstrap.deposit(uid, TQUOTE, new BigDecimal("1000000000"));
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
    void acceptReservesAndCancelReleases() throws Exception {
        long oid = nextOrderId++;
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        SingleWriterLoop loop = new SingleWriterLoop(256, engine, settlement::appendAndSettle,
                new JournalCatchUpSource(commandReader, payloadMapper), fatal::set);
        EngineBridge bridge = new EngineBridge(commandAppender, loop, payloadMapper);
        loop.start();
        try {
            bridge.submit(limit(KRW_BTC, alice, oid, OrderCommand.Side.BUY,
                    "40000000", "0.01", RATE_5BP, RATE_5BP));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(
                    () -> queryLong("SELECT count(*) FROM orders WHERE id = " + oid) == 1);
        } finally {
            loop.stop();
        }
        assertNull(fatal.get());

        assertEquals("OPEN", queryString("SELECT status FROM orders WHERE id = " + oid));
        assertDecimal("400000", orderColumn(oid, "locked_amount"));
        assertDecimal("999600000", userBalance(alice, KRW, "available"));
        assertDecimal("400000", userBalance(alice, KRW, "locked"));
        // 예약 원장: RESERVE 헤더 1건 + 페어 분개, business_event_id = order_id(§5.1)
        assertEquals(1, queryLong("SELECT count(*) FROM ledger_transactions "
                + "WHERE business_event_type = 'RESERVE' AND business_event_id = " + oid));
        assertEquals(0, queryLong("SELECT count(*) FROM balance_ledger_check WHERE mismatch"));

        // 사용자 취소: 실잔여 전량 해제(dust 없는 환급, §5.3)
        submit(cancel(KRW_BTC, alice, oid));
        assertEquals("CANCELED", queryString("SELECT status FROM orders WHERE id = " + oid));
        assertDecimal("0", orderColumn(oid, "locked_amount"));
        assertDecimal("0", orderColumn(oid, "remaining_qty"));
        assertDecimal("0.01", orderColumn(oid, "canceled_qty"));
        assertDecimal("1000000000", userBalance(alice, KRW, "available"));
        assertDecimal("0", userBalance(alice, KRW, "locked"));
    }

    @Test
    @Order(2)
    void singleFillSettlesPerSection52() {
        long makerOid = nextOrderId++;
        long takerOid = nextOrderId++;
        submit(limit(KRW_BTC, bob, makerOid, OrderCommand.Side.SELL,
                "50000000", "0.004", RATE_5BP, RATE_5BP));
        submit(limit(KRW_BTC, carol, takerOid, OrderCommand.Side.BUY,
                "50000000", "0.01", RATE_5BP, RATE_5BP));

        // trade_id = event_seq, 수수료는 §4-B-5 양자화: maker 100 KRW, taker 0.000002 BTC
        assertEquals(1, queryLong("SELECT count(*) FROM trades WHERE taker_order_id = " + takerOid));
        assertDecimal("100", queryDecimal(
                "SELECT maker_fee FROM trades WHERE taker_order_id = " + takerOid));
        assertDecimal("0.000002", queryDecimal(
                "SELECT taker_fee FROM trades WHERE taker_order_id = " + takerOid));
        assertEquals(0, queryLong("""
                SELECT count(*) FROM trades t
                LEFT JOIN event_journal e ON e.event_seq = t.id AND e.event_type = 'TRADE'
                WHERE e.event_seq IS NULL
                """));
        // payload 박제 표기(§5.2 (0) text 계약)
        assertEquals("100", queryString("SELECT payload->>'makerFee' FROM event_journal "
                + "WHERE event_type = 'TRADE' AND (payload->>'takerOid')::bigint = " + takerOid));
        assertEquals("0.00000200", queryString("SELECT payload->>'takerFee' FROM event_journal "
                + "WHERE event_type = 'TRADE' AND (payload->>'takerOid')::bigint = " + takerOid));

        // maker(SELL): FILLED, base 락 전량 소비, quote는 수수료 차감 수취
        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + makerOid));
        assertDecimal("0", orderColumn(makerOid, "locked_amount"));
        assertDecimal("0", orderColumn(makerOid, "remaining_qty"));
        assertDecimal("1000199900", userBalance(bob, KRW, "available"));
        assertDecimal("99.996", userBalance(bob, BTC, "available"));
        assertDecimal("0", userBalance(bob, BTC, "locked"));

        // taker(BUY): PARTIAL, 락 차감 qty x L, base는 수수료 차감 수취
        assertEquals("PARTIAL", queryString("SELECT status FROM orders WHERE id = " + takerOid));
        assertDecimal("300000", orderColumn(takerOid, "locked_amount"));
        assertDecimal("200000", orderColumn(takerOid, "executed_quote"));
        assertDecimal("999500000", userBalance(carol, KRW, "available"));
        assertDecimal("300000", userBalance(carol, KRW, "locked"));
        assertDecimal("100.003998", userBalance(carol, BTC, "available"));

        // 수수료 계정 적립과 자산 보존
        assertDecimal("100", feeBalance(KRW));
        assertDecimal("0.000002", feeBalance(BTC));
        assertEquals(0, queryLong("SELECT count(*) FROM (SELECT asset_id FROM ledger_entries "
                + "GROUP BY asset_id HAVING SUM(amount) <> 0) x"));
        assertEquals(0, queryLong("SELECT count(*) FROM balance_ledger_check WHERE mismatch"));

        // 잔여 취소로 다음 테스트의 오더북을 비운다(교차 오염 방지).
        submit(cancel(KRW_BTC, carol, takerOid));
        assertEquals("CANCELED", queryString("SELECT status FROM orders WHERE id = " + takerOid));
        assertDecimal("0.006", orderColumn(takerOid, "canceled_qty"));
        assertDecimal("999800000", userBalance(carol, KRW, "available"));
        assertDecimal("0", userBalance(carol, KRW, "locked"));
    }

    @Test
    @Order(3)
    void partialFillChainConsumesEntireLockOnFill() {
        // 33,333,333 x 0.3 = 9,999,999.9: KRW scale 0 절사 잔차가 매 체결 발생하는 수치.
        String price = "33333333";
        long s1 = nextOrderId++;
        long s2 = nextOrderId++;
        long s3 = nextOrderId++;
        long buy = nextOrderId++;
        submit(limit(KRW_BTC, bob, s1, OrderCommand.Side.SELL, price, "0.3", RATE_5BP, RATE_5BP));
        submit(limit(KRW_BTC, bob, s2, OrderCommand.Side.SELL, price, "0.3", RATE_5BP, RATE_5BP));
        submit(limit(KRW_BTC, bob, s3, OrderCommand.Side.SELL, price, "0.4", RATE_5BP, RATE_5BP));
        submit(limit(KRW_BTC, alice, buy, OrderCommand.Side.BUY, price, "1", RATE_5BP, RATE_5BP));

        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + buy));
        assertDecimal("0", orderColumn(buy, "locked_amount"));
        assertDecimal("0", orderColumn(buy, "remaining_qty"));
        assertDecimal("1", orderColumn(buy, "filled_qty"));
        // 절사 체결대금 합: 9,999,999 + 9,999,999 + 13,333,333
        assertDecimal("33333331", orderColumn(buy, "executed_quote"));
        // 예약 33,333,333 중 마지막 체결이 실잔여를 소비하고 잔차 2를 환급(§5.2 (6))
        assertDecimal("966666669", userBalance(alice, KRW, "available"));
        assertDecimal("0", userBalance(alice, KRW, "locked"));
        // taker 수수료: 0.00015 + 0.00015 + 0.0002 BTC
        assertDecimal("100.9995", userBalance(alice, BTC, "available"));

        for (long oid : new long[] {s1, s2, s3}) {
            assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + oid));
            assertDecimal("0", orderColumn(oid, "locked_amount"));
        }
        // maker 수수료 5,000+5,000+6,667(HALF_UP). 잔차는 FEE leg가 흡수해 합=0 유지.
        assertDecimal("16767", feeBalance(KRW));
        assertEquals(0, queryLong("SELECT count(*) FROM (SELECT asset_id FROM ledger_entries "
                + "GROUP BY asset_id HAVING SUM(amount) <> 0) x"));
        assertEquals(0, queryLong("SELECT count(*) FROM balance_ledger_check WHERE mismatch"));
        assertEquals(0, lockedMismatchCount());
    }

    @Test
    @Order(4)
    void stpAndRandomSequencePreserveAssets() {
        // 결정적 STP 케이스 1: 부분 감액으로 열린 주문에 canceled_qty가 쌓인다(보존식).
        long resting = nextOrderId++;
        long incoming = nextOrderId++;
        submit(limit(T_MARKET, alice, resting, OrderCommand.Side.SELL, "100", "1", RATE_5BP, RATE_10BP));
        submit(limit(T_MARKET, alice, incoming, OrderCommand.Side.BUY, "100", "0.4", RATE_5BP, RATE_10BP));
        assertEquals("OPEN", queryString("SELECT status FROM orders WHERE id = " + resting));
        assertDecimal("0.4", orderColumn(resting, "canceled_qty"));
        assertDecimal("0.6", orderColumn(resting, "remaining_qty"));
        assertDecimal("0.6", orderColumn(resting, "locked_amount"));
        assertEquals("CANCELED", queryString("SELECT status FROM orders WHERE id = " + incoming));
        assertDecimal("0", orderColumn(incoming, "locked_amount"));

        // 케이스 2 전에 잔여 호가를 비운다. 남기면 시장가 매수가 STP 전에 타인 호가와 체결된다.
        submit(cancel(T_MARKET, alice, resting));
        assertDecimal("0", userBalance(alice, TBASE, "locked"));

        // 결정적 STP 케이스 2: 시장가 매수 taker의 감액은 quote 금액 단위다(§5.3 (0)).
        long ownAsk = nextOrderId++;
        long ownBuy = nextOrderId++;
        BigDecimal quoteBefore = userBalance(bob, TQUOTE, "available");
        submit(limit(T_MARKET, bob, ownAsk, OrderCommand.Side.SELL, "100", "2", RATE_5BP, RATE_10BP));
        submit(marketBuy(T_MARKET, bob, ownBuy, "50", RATE_5BP, RATE_10BP));
        assertEquals("CANCELED", queryString("SELECT status FROM orders WHERE id = " + ownBuy));
        assertDecimal("0", orderColumn(ownBuy, "locked_amount"));
        assertDecimal("0.5", orderColumn(ownAsk, "canceled_qty"));
        assertDecimal(quoteBefore.toPlainString(), userBalance(bob, TQUOTE, "available"));

        // 무작위 시퀀스: 지정가·시장가·취소 혼합. 시드 고정으로 재현 가능.
        Random random = new Random(20260722);
        long[] users = {alice, bob, carol};
        List<Long> submitted = new ArrayList<>(List.of(resting, ownAsk));
        for (int i = 0; i < 200; i++) {
            long uid = users[random.nextInt(users.length)];
            int op = random.nextInt(10);
            if (op < 4) {
                long oid = nextOrderId++;
                OrderCommand.Side side = random.nextBoolean()
                        ? OrderCommand.Side.BUY : OrderCommand.Side.SELL;
                BigDecimal price = new BigDecimal(9_000 + random.nextInt(2_001)).movePointLeft(2);
                BigDecimal qty = new BigDecimal(1 + random.nextInt(9_999)).movePointLeft(4);
                submit(limit(T_MARKET, uid, oid, side,
                        price.toPlainString(), qty.toPlainString(), RATE_5BP, RATE_10BP));
                submitted.add(oid);
            } else if (op < 6) {
                long oid = nextOrderId++;
                BigDecimal notional = new BigDecimal(1 + random.nextInt(5_000)).movePointLeft(1);
                submit(marketBuy(T_MARKET, uid, oid, notional.toPlainString(), RATE_5BP, RATE_10BP));
                submitted.add(oid);
            } else if (op < 8) {
                long oid = nextOrderId++;
                BigDecimal qty = new BigDecimal(1 + random.nextInt(9_999)).movePointLeft(4);
                submit(marketSell(T_MARKET, uid, oid, qty.toPlainString(), RATE_5BP, RATE_10BP));
                submitted.add(oid);
            } else {
                // 소유자 불일치·기체결 대상 취소는 REJECTED(order_not_found)로 종결되는 정상 경로다.
                long target = submitted.get(random.nextInt(submitted.size()));
                submit(cancel(T_MARKET, uid, target));
            }
        }

        assertInvariants();
        // 재생 결정론: 수수료 payload 재구성을 포함한 전량 대조가 통과해야 한다.
        MatchingEngineImpl replayed = new MatchingEngineImpl();
        ReplayResult result = replayService.rebuild(replayed);
        assertTrue(result.pendingEvents().isEmpty());
        assertEquals(engine.stateChecksum(), replayed.stateChecksum());
    }

    @Test
    @Order(5)
    void crashWindowIsResettledIdempotently() {
        long ask = nextOrderId++;
        submit(limit(KRW_BTC, bob, ask, OrderCommand.Side.SELL, "50000000", "0.5", RATE_5BP, RATE_5BP));
        // 크래시 재현: 명령은 커밋됐지만 이벤트·정산 커밋 전에 정지했다.
        long crash = nextOrderId++;
        append(limit(KRW_BTC, alice, crash, OrderCommand.Side.BUY, "50000000", "0.2", RATE_5BP, RATE_5BP));

        long eventsBefore = queryLong("SELECT count(*) FROM event_journal");
        long ledgerBefore = queryLong("SELECT count(*) FROM ledger_transactions");

        EngineRecovery recovery =
                new EngineRecovery(replayService, settlement::appendAndSettle, lease);
        MatchingEngineImpl recovered = new MatchingEngineImpl();
        ReplayResult first = recovery.recover(recovered);
        assertEquals(2, first.pendingEvents().size(), "ACCEPTED+TRADE가 pending이어야 한다");

        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + crash));
        assertDecimal("0", orderColumn(crash, "locked_amount"));
        assertEquals(1, queryLong("SELECT count(*) FROM trades WHERE taker_order_id = " + crash));
        assertEquals(eventsBefore + 2, queryLong("SELECT count(*) FROM event_journal"));

        // 2회째 복구: pending 없음 + 이중 정산 없음(멱등)
        long eventsAfter = queryLong("SELECT count(*) FROM event_journal");
        long ledgerAfter = queryLong("SELECT count(*) FROM ledger_transactions");
        ReplayResult second = recovery.recover(new MatchingEngineImpl());
        assertTrue(second.pendingEvents().isEmpty());
        assertEquals(eventsAfter, queryLong("SELECT count(*) FROM event_journal"));
        assertEquals(ledgerAfter, queryLong("SELECT count(*) FROM ledger_transactions"));
        assertTrue(ledgerAfter > ledgerBefore);
        assertEquals(0, queryLong("SELECT count(*) FROM (SELECT asset_id FROM ledger_entries "
                + "GROUP BY asset_id HAVING SUM(amount) <> 0) x"));

        // 동기 엔진 따라잡기: 재적용 이벤트는 레지스트리 멱등 판정으로 스킵된다.
        drain();
        assertEquals(recovered.stateChecksum(), engine.stateChecksum());
    }

    @Test
    @Order(6)
    void duplicateSettlementIsSkipped() {
        long oid = nextOrderId++;
        AppendResult ack = append(limit(KRW_BTC, carol, oid, OrderCommand.Side.BUY,
                "50000000", "0.1", RATE_5BP, RATE_5BP));
        JournalEntry entry = commandReader.readAfter(ack.seq() - 1, 1).get(0);
        List<EngineEvent> events = engine.apply(payloadMapper.toCommand(entry));
        assertEquals(2, events.size(), "ACCEPTED+TRADE 배치를 기대");
        List<EngineEvent> settled = settlement.appendAndSettle(events);
        assertTrue(settled.get(0).eventSeq() > 0);

        long eventCount = queryLong("SELECT count(*) FROM event_journal");
        long tradeCount = queryLong("SELECT count(*) FROM trades");
        long ledgerCount = queryLong("SELECT count(*) FROM ledger_transactions");
        List<String> balancesBefore = balanceSnapshot();

        settlement.appendAndSettle(events);

        assertEquals(eventCount, queryLong("SELECT count(*) FROM event_journal"));
        assertEquals(tradeCount, queryLong("SELECT count(*) FROM trades"));
        assertEquals(ledgerCount, queryLong("SELECT count(*) FROM ledger_transactions"));
        assertEquals(balancesBefore, balanceSnapshot());
        assertInvariants();
    }

    private static void assertInvariants() {
        // C01 자산 보존
        assertEquals(0, queryLong("SELECT count(*) FROM (SELECT asset_id FROM ledger_entries "
                + "GROUP BY asset_id HAVING SUM(amount) <> 0) x"));
        // C02 잔고 프로젝션 대조
        assertEquals(0, queryLong("SELECT count(*) FROM balance_ledger_check WHERE mismatch"));
        // ⑤⑥ gapless. C05 는 빈 저널에서 0 이라 행 실재를 먼저 본다.
        assertTrue(queryLong("SELECT count(*) FROM command_journal") > 0, "command_journal 행 실재");
        assertEquals(0, queryLong(InvariantCheck.C05_CMD_GAPLESS.sql()));
        // V36 에서 결번 없음의 축이 마켓 안으로 갔다. event_seq 는 marketId 대역 파생이라
        // 전역으로 세면 대역 사이가 비어 보이고 그것은 결번이 아니라 경계다.
        assertEquals(0, queryLong("""
                SELECT COALESCE(sum(gap), 0) FROM (
                    SELECT (max(market_seq) - min(market_seq) + 1) - count(*) AS gap
                    FROM event_journal WHERE market_seq IS NOT NULL GROUP BY market_id
                ) g
                """));
        // ③④ 봉인·균형
        assertEquals(0, queryLong("SELECT count(*) FROM ledger_transactions WHERE status = 'OPEN'"));
        assertEquals(0, queryLong("""
                SELECT count(*) FROM (
                    SELECT l.transaction_id FROM ledger_entries l
                    JOIN ledger_transactions t ON t.id = l.transaction_id AND t.status = 'SEALED'
                    GROUP BY l.transaction_id, l.asset_id HAVING SUM(l.amount) <> 0) x
                """));
        // ⑨ trades와 TRADE 이벤트 1:1
        assertEquals(0, queryLong("""
                SELECT count(*) FROM trades t
                LEFT JOIN event_journal e ON e.event_seq = t.id AND e.event_type = 'TRADE'
                WHERE e.event_seq IS NULL
                """));
        // T-C: FILLED면 잔여·락 0
        assertEquals(0, queryLong("SELECT count(*) FROM orders WHERE status = 'FILLED' "
                + "AND (locked_amount <> 0 OR (remaining_qty IS NOT NULL AND remaining_qty <> 0))"));
        // C14: LOCKED 계정 잔고 = 열린 주문 잔여 예약 합
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

    private static AppendResult append(OrderIntake intake) {
        return commandAppender.append(new CommandDraft(
                intake.marketId(), intake.type(), intake.userId(), intake.clientOrderId(),
                intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                payloadMapper.toPayloadJson(intake)));
    }

    /** 동기 구동: append 후 저널 미적용 구간 전체를 적용·정산한다(단일 라이터와 동일 순서). */
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

    private static OrderIntake limit(int marketId, long userId, long orderId,
                                     OrderCommand.Side side, String price, String qty,
                                     BigDecimal makerRate, BigDecimal takerRate) {
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, userId, "st-" + orderId,
                orderId, BASE_TS + orderId, orderId, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal(qty), null,
                externalId(orderId), makerRate, takerRate);
    }

    private static OrderIntake marketBuy(int marketId, long userId, long orderId, String notional,
                                         BigDecimal makerRate, BigDecimal takerRate) {
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, userId, "st-" + orderId,
                orderId, BASE_TS + orderId, orderId, OrderCommand.Side.BUY,
                OrderCommand.OrdType.MARKET, null, null, null, new BigDecimal(notional),
                externalId(orderId), makerRate, takerRate);
    }

    private static OrderIntake marketSell(int marketId, long userId, long orderId, String qty,
                                          BigDecimal makerRate, BigDecimal takerRate) {
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, userId, "st-" + orderId,
                orderId, BASE_TS + orderId, orderId, OrderCommand.Side.SELL,
                OrderCommand.OrdType.MARKET, null, null, new BigDecimal(qty), null,
                externalId(orderId), makerRate, takerRate);
    }

    private static OrderIntake cancel(int marketId, long userId, long targetOrderId) {
        long ts = BASE_TS + 700_000 + nextOrderId++;
        return new OrderIntake(marketId, CommandDraft.CommandType.CANCEL, userId, null,
                targetOrderId, ts, ts, null, null, null, null, null, null, null, null, null);
    }

    private static UUID externalId(long orderId) {
        return UUID.nameUUIDFromBytes(("st-" + orderId).getBytes());
    }

    private static void takeOver(LeaderLeaseService svc) {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
    }

    private static List<String> balanceSnapshot() {
        return jdbc.sql("SELECT account_id || ':' || balance FROM balances ORDER BY account_id")
                .query(String.class)
                .list();
    }

    private static BigDecimal userBalance(long userId, int assetId, String column) {
        return jdbc.sql("SELECT " + column + " FROM user_balances "
                        + "WHERE user_id = ? AND asset_id = ?")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .single();
    }

    private static BigDecimal feeBalance(int assetId) {
        return jdbc.sql("""
                        SELECT b.balance FROM balances b
                        JOIN accounts a ON a.id = b.account_id
                        WHERE a.owner_type = 'SYSTEM' AND a.purpose = 'FEE_INCOME' AND a.asset_id = ?
                        """)
                .params(assetId)
                .query(BigDecimal.class)
                .single();
    }

    private static BigDecimal orderColumn(long orderId, String column) {
        return queryDecimal("SELECT " + column + " FROM orders WHERE id = " + orderId);
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

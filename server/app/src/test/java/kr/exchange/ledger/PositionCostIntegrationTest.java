package kr.exchange.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V13 원가 프로젝션(position_cost) 통합 검증. 병합 조건 7건을 증명한다.
 * (1) no-RAISE: 형태 이탈 입력에서도 게시 트랜잭션이 커밋되고 가입 경로가 산다,
 * (2) 재구축 = 라이브 비트동일, (3) 부분 처분 후 평단가 유지·전량 처분 시 정확히 0,
 * (4) 원가 미상 분리, (5) 동일 txn 재적용 무동작, (6) 가격개선 환급 시 실지출 반영,
 * (7) 기존 원장 불변식 무손상.
 *
 * 저널·원장이 전역 공유 자원이라 순서를 고정한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PositionCostIntegrationTest {

    private static final long BASE_TS = 1_753_400_000_000L;
    // MemberAuthService.PROVISION_GENESIS_BASE 와 동일. 가입 경로 게시 형태를 그대로 재현한다.
    private static final long PROVISION_GENESIS_BASE = 1_000_000_000_000L;

    private static final int KRW = 1;
    private static final int BTC = 2;
    // 마켓에 등재하지 않는다. quote 판정과 형태 이탈 강등을 거래 경로와 격리해 확인한다.
    private static final int ETH = 3;
    private static final int XRP = 4;
    private static final int KRW_BTC = 1;

    private static final BigDecimal ZERO_RATE = BigDecimal.ZERO;
    private static final BigDecimal RATE_5BP = new BigDecimal("0.0005");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static LeaderLeaseService lease;
    static CommandJournalAppender commandAppender;
    static CommandJournalReader commandReader;
    static CommandPayloadMapper payloadMapper;
    static SettlementService settlement;
    static MatchingEngineImpl engine;

    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long eve;
    static long nextOrderId = 4_000;
    static long rawEventId = 800_000;

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
        ObjectMapper objectMapper = new ObjectMapper();
        lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(30), Duration.ofSeconds(1));
        commandAppender = new CommandJournalAppender(jdbc, tx, lease);
        commandReader = new CommandJournalReader(jdbc);
        payloadMapper = new CommandPayloadMapper(objectMapper);
        settlement = new SettlementService(jdbc, tx, lease, objectMapper, new MarketCatalog(jdbc),
                commandReader, payloadMapper);
        engine = new MatchingEngineImpl();

        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.asset(ETH, "ETH", "Ether", "CRYPTO", 18);
        bootstrap.asset(XRP, "XRP", "Ripple", "CRYPTO", 6);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW, ZERO_RATE, ZERO_RATE);
        alice = bootstrap.user("pc-alice@test.local");
        bob = bootstrap.user("pc-bob@test.local");
        carol = bootstrap.user("pc-carol@test.local");
        dave = bootstrap.user("pc-dave@test.local");
        eve = bootstrap.user("pc-eve@test.local");
        for (long uid : new long[] {alice, bob, carol, dave, eve}) {
            for (int asset : new int[] {KRW, BTC, ETH, XRP}) {
                bootstrap.userAccounts(uid, asset);
            }
        }
        // 시드 자금. 자산당 1트랜잭션이라 KRW 는 비quote 0개 GENESIS 로 게시된다.
        for (long uid : new long[] {alice, bob, carol, dave}) {
            bootstrap.deposit(uid, KRW, new BigDecimal("1000000000"));
        }
        // 시드 코인은 매수 상대거래가 없으므로 원가 미상이어야 한다.
        bootstrap.deposit(bob, BTC, new BigDecimal("10"));
        takeOver();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ------------------------------------------------------------------
    // (1) 시드 형태: KRW 단독 GENESIS 무동작, 코인 GENESIS 는 원가 미상
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void krwOnlyGenesisIsNoopAndSeedCoinIsUnknownCost() {
        // 비quote 0개 GENESIS 는 예외 없이 무동작이어야 한다. 여기서 던지면 회원가입과
        // 데모 프로비저닝이 통째로 죽는다(ApiConfig·BotSeeder·MemberAuthService 공통 형태).
        assertEquals(0, queryLong("SELECT count(*) FROM position_cost WHERE asset_id = " + KRW));
        assertEquals(5, queryLong("SELECT count(*) FROM ledger_transactions "
                + "WHERE business_event_type = 'GENESIS' AND status = 'SEALED'"),
                "시드 GENESIS 5건(KRW 4 + BTC 1)이 전부 봉인돼야 한다");

        assertDecimal("0", cost(bob, BTC, "cost_qty"));
        assertDecimal("0", cost(bob, BTC, "cost_amount"));
        assertDecimal("10", cost(bob, BTC, "unknown_qty"));
        assertDecimal("0", cost(bob, BTC, "realized_pnl"));
        assertDecimal("0", cost(bob, BTC, "unknown_proceeds"));

        // 가입 경로 실게시. MemberAuthService 와 동일한 business_event_id 네임스페이스·분개 형태.
        long genesisKrw = accountId("SYSTEM", 0, "GENESIS", KRW);
        long eveKrw = accountId("USER", eve, "AVAILABLE", KRW);
        long txn = post("GENESIS", PROVISION_GENESIS_BASE + eve,
                "[" + leg(genesisKrw, KRW, "-100000000", "GENESIS") + ","
                    + leg(eveKrw, KRW, "100000000", "GENESIS") + "]");
        assertTrue(txn > 0, "가입 GENESIS 게시가 실패하면 안 된다");
        assertEquals("SEALED", queryString("SELECT status FROM ledger_transactions WHERE id = " + txn));
        assertEquals(0, queryLong("SELECT count(*) FROM position_cost WHERE asset_id = " + KRW));
        assertDecimal("100000000", userBalance(eve, KRW, "available"));
    }

    // ------------------------------------------------------------------
    // (2) 형태 이탈 전부 no-RAISE. 원가 미상 강등으로 흡수한다.
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void shapeDeviationsDowngradeToUnknownWithoutRaise() {
        long ledgerBefore = queryLong("SELECT count(*) FROM ledger_transactions");

        // 비quote 자산이 둘인 단일 트랜잭션: 어느 쪽에도 quote 를 귀속할 수 없다.
        post("GENESIS", ++rawEventId,
                "[" + leg(accountId("SYSTEM", 0, "GENESIS", ETH), ETH, "-2", "GENESIS") + ","
                    + leg(accountId("USER", eve, "AVAILABLE", ETH), ETH, "2", "GENESIS") + ","
                    + leg(accountId("SYSTEM", 0, "GENESIS", XRP), XRP, "-5", "GENESIS") + ","
                    + leg(accountId("USER", eve, "AVAILABLE", XRP), XRP, "5", "GENESIS") + "]");
        assertDecimal("2", cost(eve, ETH, "unknown_qty"));
        assertDecimal("5", cost(eve, XRP, "unknown_qty"));
        assertDecimal("0", cost(eve, ETH, "cost_qty"));

        // base 와 quote 를 동시에 수취: 규칙 2 의 dQuote < 0 조건에 걸리지 않아 원가 미상이다.
        post("GENESIS", ++rawEventId,
                "[" + leg(accountId("SYSTEM", 0, "GENESIS", ETH), ETH, "-1", "GENESIS") + ","
                    + leg(accountId("USER", eve, "AVAILABLE", ETH), ETH, "1", "GENESIS") + ","
                    + leg(accountId("SYSTEM", 0, "GENESIS", KRW), KRW, "-7", "GENESIS") + ","
                    + leg(accountId("USER", eve, "AVAILABLE", KRW), KRW, "7", "GENESIS") + "]");
        assertDecimal("3", cost(eve, ETH, "unknown_qty"));
        assertDecimal("0", cost(eve, ETH, "cost_amount"));

        // quote leg 없는 처분: 원가 미상분만 줄고 수취액 귀속이 없다(음수 유발 조합).
        post("TRANSFER", ++rawEventId,
                "[" + leg(accountId("USER", eve, "AVAILABLE", ETH), ETH, "-0.5", "WITHDRAW") + ","
                    + leg(accountId("USER", alice, "AVAILABLE", ETH), ETH, "0.5", "DEPOSIT") + "]");
        assertDecimal("2.5", cost(eve, ETH, "unknown_qty"));
        assertDecimal("0.5", cost(alice, ETH, "unknown_qty"));
        assertDecimal("0", cost(eve, ETH, "realized_pnl"));
        assertDecimal("0", cost(eve, ETH, "unknown_proceeds"));

        assertEquals(ledgerBefore + 3, queryLong("SELECT count(*) FROM ledger_transactions"),
                "형태 이탈 게시가 롤백되면 안 된다");
        assertLedgerInvariants();
    }

    // ------------------------------------------------------------------
    // (4) 원가 미상 분리: 시드 물량 매도는 unknown_proceeds 로 간다
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    void unknownCostDisposalSeparatesProceeds() {
        long ask = nextOrderId++;
        long bid = nextOrderId++;
        submit(limit(bob, ask, OrderCommand.Side.SELL, "50000000", "1", ZERO_RATE, ZERO_RATE));
        submit(limit(carol, bid, OrderCommand.Side.BUY, "50000000", "1", ZERO_RATE, ZERO_RATE));
        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + bid));

        // 매도자: 원가 미상 물량이 줄고 수취액은 realized_pnl 이 아니라 unknown_proceeds 로 간다.
        assertDecimal("0", cost(bob, BTC, "cost_qty"));
        assertDecimal("9", cost(bob, BTC, "unknown_qty"));
        assertDecimal("0", cost(bob, BTC, "realized_pnl"));
        assertDecimal("50000000", cost(bob, BTC, "unknown_proceeds"));

        // 매수자: 원가 확보 취득. 나눗셈 없이 누산기 쌍만 늘어난다.
        assertDecimal("1", cost(carol, BTC, "cost_qty"));
        assertDecimal("50000000", cost(carol, BTC, "cost_amount"));
        assertDecimal("0", cost(carol, BTC, "unknown_qty"));
        assertLedgerInvariants();
    }

    // ------------------------------------------------------------------
    // (3) 부분 처분 후 평단가 유지, 전량 처분 시 두 값 정확히 0
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    void partialSellPreservesAverageAndFullSellZeroesBoth() {
        BigDecimal avgBefore = avgCost(carol, BTC);
        assertDecimal("50000000", avgBefore);

        sellToBuyer(carol, dave, "60000000", "0.4");
        assertDecimal("0.6", cost(carol, BTC, "cost_qty"));
        assertDecimal("30000000", cost(carol, BTC, "cost_amount"));
        assertEquals(0, avgBefore.compareTo(avgCost(carol, BTC)),
                "비례차감은 평단가를 바꾸지 않는다");
        // 24,000,000 수취 - 20,000,000 원가차감
        assertDecimal("4000000", cost(carol, BTC, "realized_pnl"));

        sellToBuyer(carol, dave, "40000000", "0.6");
        // 전량 스윕은 나눗셈 없이 잔여 원가를 전액 덜어낸다. chk_cost_pair 유지의 근거.
        assertEquals(0, cost(carol, BTC, "cost_qty").compareTo(BigDecimal.ZERO));
        assertEquals(0, cost(carol, BTC, "cost_amount").compareTo(BigDecimal.ZERO));
        assertDecimal("0", cost(carol, BTC, "unknown_qty"));
        assertDecimal("-2000000", cost(carol, BTC, "realized_pnl"));
        assertDecimal("0", cost(carol, BTC, "unknown_proceeds"));

        // 매수측 누산: 0.4 + 0.6 = 1.0, 24,000,000 + 24,000,000
        assertDecimal("1", cost(dave, BTC, "cost_qty"));
        assertDecimal("48000000", cost(dave, BTC, "cost_amount"));
        assertLedgerInvariants();
    }

    // ------------------------------------------------------------------
    // (6) 가격개선 환급: 원가는 락 금액이 아니라 실지출을 반영한다
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    void costUsesActualSpendNotLockedAmount() {
        BigDecimal proceedsBefore = cost(bob, BTC, "unknown_proceeds");
        long ask = nextOrderId++;
        long bid = nextOrderId++;
        // maker 50,000,000 에 taker 지정가 60,000,000. 체결가는 maker 가격이라 환급이 발생한다.
        submit(limit(bob, ask, OrderCommand.Side.SELL, "50000000", "0.01", ZERO_RATE, RATE_5BP));
        submit(limit(alice, bid, OrderCommand.Side.BUY, "60000000", "0.01", ZERO_RATE, RATE_5BP));
        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + bid));

        // 락은 600,000, 실지출은 500,000. 원가에 락 금액이 새면 여기서 어긋난다.
        assertDecimal("500000", cost(alice, BTC, "cost_amount"));
        // 매수 수수료(0.000005 BTC)는 순델타에 이미 반영돼 있다. 수수료 항을 따로 두지 않는다.
        assertDecimal("0.009995", cost(alice, BTC, "cost_qty"));
        assertDecimal("0", cost(alice, BTC, "unknown_qty"));

        assertDecimal("8.99", cost(bob, BTC, "unknown_qty"));
        assertDecimal(proceedsBefore.add(new BigDecimal("500000")).toPlainString(),
                cost(bob, BTC, "unknown_proceeds"));
        assertLedgerInvariants();
    }

    // ------------------------------------------------------------------
    // (5) 멱등: 같은 txn_id 재적용은 무동작
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    void reapplyingSameTransactionIsNoop() {
        long lastTxn = queryLong("SELECT max(id) FROM ledger_transactions");
        List<String> before = costSnapshot();
        jdbc.sql("SELECT apply_position_cost(?)").params(lastTxn).query().listOfRows();
        assertEquals(before, costSnapshot(), "워터마크가 재적용을 막아야 한다");

        // 전 구간 재적용도 무동작이어야 한다(재기동 catch-up 이 같은 원장을 다시 훑는 경우).
        jdbc.sql("SELECT apply_position_cost(id) FROM ledger_transactions ORDER BY id")
                .query()
                .listOfRows();
        assertEquals(before, costSnapshot());
    }

    // ------------------------------------------------------------------
    // (2) 재구축 = 라이브 비트동일
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    void rebuildReproducesLiveProjectionBitwise() {
        // 누산기 폭을 넓히려고 짧은 매매 시퀀스를 한 번 더 흘린다.
        sellToBuyer(dave, alice, "55000000", "0.25");
        sellToBuyer(alice, dave, "45000000", "0.1");
        sellToBuyer(bob, carol, "70000000", "0.5");

        List<String> live = costSnapshot();
        assertTrue(live.size() >= 5, "비교 대상 행이 있어야 의미가 있다");

        long replayed = queryLong("SELECT rebuild_position_cost(NULL)");
        assertTrue(replayed > 0);
        // updated_at 은 벽시계 관측 컬럼이라 비교 대상이 아니다. 나머지 전 컬럼이 비트동일해야 한다.
        assertEquals(live, costSnapshot(), "재구축 결과가 라이브와 달라지면 산식이 두 벌이라는 뜻이다");

        // 단일 사용자 재구축도 타 사용자 행을 건드리지 않는다(워터마크가 막는다).
        assertTrue(queryLong("SELECT rebuild_position_cost(" + carol + ")") > 0);
        assertEquals(live, costSnapshot());
    }

    // ------------------------------------------------------------------
    // (1) 예외 핸들러 실발동: 행 하나가 실패해도 원장과 형제 행은 산다
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    void rowFailureIsAbsorbedWithoutRollingBackLedger() {
        // realized_pnl 이 NUMERIC(40,18) 범위를 넘도록 상태를 인위 손상시킨다. 전량 스윕이
        // 22003 을 던지는 조합이라 핸들러가 실제로 발동한다(형태 강등·클램프로는 도달 못 한다).
        jdbc.sql("""
                        UPDATE position_cost
                        SET cost_qty = 1, cost_amount = 9999999999999999999999,
                            realized_pnl = -9999999999999999999999, unknown_qty = 4, last_txn_id = 0
                        WHERE user_id = ? AND asset_id = ?
                        """)
                .params(eve, XRP)
                .update();

        long txn = post("TRANSFER", ++rawEventId,
                "[" + leg(accountId("USER", eve, "AVAILABLE", XRP), XRP, "-1", "WITHDRAW") + ","
                    + leg(accountId("USER", alice, "AVAILABLE", XRP), XRP, "1", "DEPOSIT") + "]");

        assertEquals("SEALED", queryString("SELECT status FROM ledger_transactions WHERE id = " + txn),
                "프로젝션 실패가 원장 게시를 롤백시키면 안 된다");
        // 실패 행은 미갱신(워터마크 0 유지), 같은 트랜잭션의 형제 행은 정상 갱신.
        assertEquals(0, queryLong("SELECT last_txn_id FROM position_cost "
                + "WHERE user_id = " + eve + " AND asset_id = " + XRP));
        assertDecimal("1", cost(alice, XRP, "unknown_qty"));
        assertEquals(txn, queryLong("SELECT last_txn_id FROM position_cost "
                + "WHERE user_id = " + alice + " AND asset_id = " + XRP));
        assertLedgerInvariants();

        // 손상은 재구축이 교정한다. 이게 no-RAISE 를 감당하는 사후 수단이다.
        assertEquals(1, queryLong("SELECT count(*) FROM position_cost_qty_check"));
        assertTrue(queryLong("SELECT rebuild_position_cost(NULL)") > 0);
        assertEquals(0, queryLong("SELECT count(*) FROM position_cost_qty_check"));
        assertDecimal("0", cost(eve, XRP, "cost_qty"));
        assertDecimal("4", cost(eve, XRP, "unknown_qty"));
        assertDecimal("0", cost(eve, XRP, "realized_pnl"));
    }

    // ------------------------------------------------------------------
    // (3) 비례차감이 DOWN 절사인지 판별한다. round 로 바꾸면 이 단언이 깨진다.
    // ------------------------------------------------------------------

    @Test
    @Order(9)
    void proportionalReliefTruncatesDownInsteadOfRounding() {
        // cost_amount * known / cost_qty 가 cost_amount 로 반올림되는 극단값을 만든다.
        // 2.5 중 2.499999999999999999 처분: 정확값 0.9999999999999999996.
        //   DOWN 절사 -> relief 0.999999999999999999, 잔여 원가 1e-18 (쌍 유지)
        //   반올림    -> relief 1, cost_amount 0 인데 cost_qty 1e-18 (쌍 깨짐, 강등 발동)
        jdbc.sql("""
                        UPDATE position_cost
                        SET cost_qty = 2.5, cost_amount = 1, unknown_qty = 0,
                            realized_pnl = 0, unknown_proceeds = 0, last_txn_id = 0
                        WHERE user_id = ? AND asset_id = ?
                        """)
                .params(eve, ETH)
                .update();

        post("TRANSFER", ++rawEventId,
                "[" + leg(accountId("USER", eve, "AVAILABLE", ETH), ETH,
                          "-2.499999999999999999", "WITHDRAW") + ","
                    + leg(accountId("USER", alice, "AVAILABLE", ETH), ETH,
                          "2.499999999999999999", "DEPOSIT") + "]");

        assertDecimal("0.000000000000000001", cost(eve, ETH, "cost_qty"));
        assertDecimal("0.000000000000000001", cost(eve, ETH, "cost_amount"));
        assertDecimal("0", cost(eve, ETH, "unknown_qty"));
        assertDecimal("-0.999999999999999999", cost(eve, ETH, "realized_pnl"));
    }

    // ------------------------------------------------------------------
    // 권한 경계(필수수정 5): app_role 은 읽기만. 게시 경로는 재정의 후에도 살아 있어야 한다.
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    void appRolePostsThroughDefinerButCannotWriteProjection() throws Exception {
        long genesisXrp = accountId("SYSTEM", 0, "GENESIS", XRP);
        long eveXrp = accountId("USER", eve, "AVAILABLE", XRP);
        BigDecimal before = cost(eve, XRP, "unknown_qty");
        long eventId = ++rawEventId;

        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("GRANT app_role TO " + POSTGRES.getUsername());
            st.execute("SET ROLE app_role");

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM position_cost")) {
                assertTrue(rs.next(), "app_role 은 원가 프로젝션을 읽을 수 있어야 한다");
            }
            assertDenied(st, "UPDATE position_cost SET cost_qty = 0");
            assertDenied(st, "INSERT INTO position_cost (user_id, asset_id, quote_asset_id) "
                    + "VALUES (" + eve + ", " + XRP + ", " + KRW + ")");
            assertDenied(st, "DELETE FROM position_cost");
            // 쓰기 함수 자체도 앱에서 직접 호출할 수 없다. 게시 함수 내부 호출만이 유일 경로다.
            assertDenied(st, "SELECT apply_position_cost(1)");
            assertDenied(st, "SELECT rebuild_position_cost(NULL)");

            // 게시 함수 재정의가 app_role 의 EXECUTE 와 중첩 SECURITY DEFINER 호출을 깨지 않았는지.
            String entries = "[" + leg(genesisXrp, XRP, "-3", "GENESIS") + ","
                    + leg(eveXrp, XRP, "3", "GENESIS") + "]";
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT post_ledger_transaction('GENESIS', ?, ?::jsonb)")) {
                ps.setLong(1, eventId);
                ps.setString(2, entries);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next() && rs.getLong(1) > 0, "app_role 게시가 실패하면 안 된다");
                }
            }
        }

        assertDecimal(before.add(new BigDecimal("3")).toPlainString(), cost(eve, XRP, "unknown_qty"));
    }

    // ------------------------------------------------------------------
    // (7) 기존 원장 불변식 무손상
    // ------------------------------------------------------------------

    @Test
    @Order(11)
    void ledgerInvariantsSurviveProjection() {
        assertLedgerInvariants();
        // C24 원가 수량 보존, C25 원가 행 정합
        assertEquals(0, queryLong("SELECT count(*) FROM position_cost_qty_check"));
        assertEquals(0, queryLong("SELECT count(*) FROM position_cost_sanity_check"));
        // 원가 통화 자신에는 원가 행이 생기지 않는다.
        assertEquals(0, queryLong("SELECT count(*) FROM position_cost pc "
                + "JOIN cost_quote_assets q ON q.asset_id = pc.asset_id"));
        // 평단가는 저장하지 않는다. 조회 산식이 성립하는 행만 존재해야 한다.
        assertEquals(0, queryLong("SELECT count(*) FROM position_cost "
                + "WHERE cost_qty = 0 AND cost_amount <> 0"));
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    /** 권한 위반은 42501(insufficient_privilege)이어야 한다. 다른 실패는 검사 자체가 무효다. */
    private static void assertDenied(Statement st, String sql) {
        SQLException denied = null;
        try {
            st.execute(sql);
        } catch (SQLException e) {
            denied = e;
        }
        assertNotNull(denied, "app_role 이 실행하면 안 되는 구문이 통과했다: " + sql);
        assertEquals("42501", denied.getSQLState(), "기대와 다른 실패: " + denied.getMessage());
    }

    private static void assertLedgerInvariants() {
        assertEquals(0, queryLong("SELECT count(*) FROM (SELECT asset_id FROM ledger_entries "
                + "GROUP BY asset_id HAVING SUM(amount) <> 0) x"), "자산 보존");
        assertEquals(0, queryLong("SELECT count(*) FROM balance_ledger_check WHERE mismatch"),
                "잔고 프로젝션 대조");
        assertEquals(0, queryLong("SELECT count(*) FROM ledger_transactions WHERE status = 'OPEN'"),
                "미봉인 거래");
        assertEquals(0, queryLong("SELECT count(*) FROM orders WHERE status = 'FILLED' "
                + "AND (locked_amount <> 0 OR (remaining_qty IS NOT NULL AND remaining_qty <> 0))"),
                "FILLED 잔여·락");
    }

    /** maker 매도 + taker 전량 매수. 두 주문이 같은 수량이라 호가창이 비운 채로 끝난다. */
    private static void sellToBuyer(long seller, long buyer, String price, String qty) {
        long ask = nextOrderId++;
        long bid = nextOrderId++;
        submit(limit(seller, ask, OrderCommand.Side.SELL, price, qty, ZERO_RATE, ZERO_RATE));
        submit(limit(buyer, bid, OrderCommand.Side.BUY, price, qty, ZERO_RATE, ZERO_RATE));
        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + bid));
        assertEquals("FILLED", queryString("SELECT status FROM orders WHERE id = " + ask));
    }

    private static BigDecimal avgCost(long userId, int assetId) {
        return jdbc.sql("SELECT cost_amount / cost_qty FROM position_cost "
                        + "WHERE user_id = ? AND asset_id = ? AND cost_qty > 0")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .single();
    }

    private static List<String> costSnapshot() {
        return jdbc.sql("""
                        SELECT user_id || '|' || asset_id || '|' || quote_asset_id
                               || '|' || cost_qty || '|' || cost_amount || '|' || unknown_qty
                               || '|' || realized_pnl || '|' || unknown_proceeds
                               || '|' || last_txn_id
                        FROM position_cost ORDER BY user_id, asset_id
                        """)
                .query(String.class)
                .list();
    }

    private static BigDecimal cost(long userId, int assetId, String column) {
        BigDecimal value = jdbc.sql("SELECT " + column + " FROM position_cost "
                        + "WHERE user_id = ? AND asset_id = ?")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
        assertNotNull(value, "원가 행 없음: user=" + userId + ", asset=" + assetId);
        return value;
    }

    private static long post(String type, long eventId, String entries) {
        return jdbc.sql("SELECT post_ledger_transaction(?, ?, ?::jsonb)")
                .params(type, eventId, entries)
                .query(Long.class)
                .single();
    }

    private static String leg(long accountId, int assetId, String amount, String entryType) {
        return "{\"account_id\":%d,\"asset_id\":%d,\"amount\":%s,\"entry_type\":\"%s\"}"
                .formatted(accountId, assetId, amount, entryType);
    }

    private static long accountId(String ownerType, long ownerId, String purpose, int assetId) {
        return jdbc.sql("""
                        SELECT id FROM accounts
                        WHERE owner_type = ? AND owner_id = ? AND purpose = ?
                          AND asset_id = ? AND shard = 0
                        """)
                .params(ownerType, ownerId, purpose, assetId)
                .query(Long.class)
                .single();
    }

    private static void submit(OrderIntake intake) {
        commandAppender.append(new CommandDraft(
                intake.marketId(), intake.type(), intake.userId(), intake.clientOrderId(),
                intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                payloadMapper.toPayloadJson(intake)));
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
                                     String price, String qty,
                                     BigDecimal makerRate, BigDecimal takerRate) {
        return new OrderIntake(KRW_BTC, CommandDraft.CommandType.NEW, userId, "pc-" + orderId,
                orderId, BASE_TS + orderId, orderId, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal(qty), null,
                UUID.nameUUIDFromBytes(("pc-" + orderId).getBytes()), makerRate, takerRate);
    }

    private static void takeOver() {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(lease.tryAcquire(), "테스트 리더 승격 실패");
    }

    private static BigDecimal userBalance(long userId, int assetId, String column) {
        return jdbc.sql("SELECT " + column + " FROM user_balances "
                        + "WHERE user_id = ? AND asset_id = ?")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .single();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertTrue(new BigDecimal(expected).compareTo(actual) == 0,
                "기대 " + expected + ", 실제 " + actual);
    }

    private static String queryString(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private static long queryLong(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

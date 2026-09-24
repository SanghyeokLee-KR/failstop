package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.transfer.TransferService;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.WriterRouter;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.SettlementService;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 단일 리더 이중 지출(ADR 0034 S3 관문, S3 설계 6-2). 한 JVM 에서 실제 게이트웨이(P2 모드), 브리지, 마켓별 라이터, 정산,
 * 출금 서비스를 띄운다. 같은 사용자의 KRW 를 매 회 X 로 맞추고 장벽으로 두 행위자를 동시에 풀어 준다. 짝수 회는 두 마켓의
 * X 매수 둘, 홀수 회는 X 매수와 X 출금이다. 매 회 정확히 하나가 성공하고 하나가 잔고 부족으로 거부된다.
 *
 * 이어 체결을 섞는 단계(검토 R6)에서 정산 배치(부분 체결 + 잔여 해제)와 같은 사용자의 다른 마켓 P2 예약을 동시에 돌려
 * 접수 500·교착·잠금 계획 이탈이 없는지 본다. 회 수는 -Dpml.doubleSpend.rounds 로 바꾼다(빌드 기본값 200, 관문 기록 1,000).
 *
 * 컨텍스트가 뜨기 전에 소유자가 전환 함수로 P2 에 올린다. 앱은 기동 때 모드를 한 번 읽는다(LeaderProtocolBoot).
 *
 * 앱 컨텍스트는 배포와 같은 런타임 로그인으로 뜬다(S3 설계 6-1). 소유자가 Flyway 와 전환을 끝낸 뒤 deploy/provision-runtime-
 * roles.sql 로 app_role 소속 로그인을 만들고, 앱은 그 로그인으로 Flyway 없이 접속한다(compose.node-app.yml 과 같다). 그래서
 * V49 권한 회수와 소유자 권한 함수 경계가 걸린 채로 돈다. 시드와 판정 조회는 소유자 연결로 한다. 첫 판은 컨텍스트를 소유자로
 * 띄워 그 경계 밖에서 돌았다(S3 구현 검토).
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SingleLeaderDoubleSpendTest {

    private static final Logger log = LoggerFactory.getLogger(SingleLeaderDoubleSpendTest.class);

    private static final int KRW = 1;
    private static final int XRP = 4;
    private static final BigDecimal X = new BigDecimal("10000");
    private static final int ROUNDS = Integer.getInteger("pml.doubleSpend.rounds", 200);
    private static final int TRADE_ROUNDS = 200;
    private static final String APP_USER = "ds_app";
    private static final String APP_PASSWORD = "ds-app";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange")
            .withEnv("DB_USER", APP_USER).withEnv("DB_PASSWORD", APP_PASSWORD)
            .withEnv("BOT_DB_USER", "ds_bot").withEnv("BOT_DB_PASSWORD", "ds-bot")
            .withEnv("READ_DB_USER", "ds_read").withEnv("READ_DB_PASSWORD", "ds-read");

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement st = c.createStatement()) {
            st.executeQuery("SELECT * FROM set_leader_protocol(2::smallint)").close();
        } catch (Exception e) {
            throw new IllegalStateException("P2 전환 실패", e);
        }
        try {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(root.resolve("deploy/provision-runtime-roles.sql"))) {
                root = root.getParent();
            }
            if (root == null) {
                throw new IllegalStateException("deploy/provision-runtime-roles.sql 없음");
            }
            POSTGRES.copyFileToContainer(MountableFile.forHostPath(root.resolve("deploy/provision-runtime-roles.sql")),
                    "/tmp/provision.sql");
            var result = POSTGRES.execInContainer("psql", "-X", "-U", "exchange", "-d", "exchange",
                    "-v", "ON_ERROR_STOP=1", "-q", "-f", "/tmp/provision.sql");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("런타임 로그인 준비 실패: " + result.getStderr());
            }
        } catch (Exception e) {
            throw new IllegalStateException("런타임 로그인 준비 실패", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // 배포처럼 런타임 로그인은 마이그레이션을 하지 않는다. 소유자가 위에서 끝냈다.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("exchange.auth.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    /** 시드와 판정 조회용 소유자 연결. 앱 경로는 쓰지 않는다. */
    final JdbcClient owner = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(), POSTGRES.getPassword()));
    final AccountBootstrap bootstrap = new AccountBootstrap(owner);

    @Autowired
    OrderGateway gateway;
    @Autowired
    TransferService transfers;
    @Autowired
    SettlementService settlement;
    @Autowired
    LeaderLeaseService lease;
    @Autowired
    WriterRouter router;
    @Autowired
    kr.exchange.engine.PromotionCoordinator promotion;
    @Autowired
    JdbcClient appJdbc;
    @Autowired
    kr.exchange.api.market.MarketDirectory markets;

    long user;
    long maker;
    final ExecutorService pool = Executors.newFixedThreadPool(3);

    @BeforeAll
    void seed() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.asset(3, "ETH", "Ether", "CRYPTO", 8);
        bootstrap.asset(XRP, "XRP", "Ripple", "CRYPTO", 8);
        BigDecimal rate = new BigDecimal("0.0005");
        bootstrap.market(1, "KRW-BTC", 2, KRW, rate, rate);
        bootstrap.market(2, "KRW-ETH", 3, KRW, rate, rate);
        bootstrap.market(3, "KRW-XRP", XRP, KRW, rate, rate);
        user = bootstrap.user("spender@double.test");
        maker = bootstrap.user("maker@double.test");
        for (long u : new long[] {user, maker}) {
            for (int asset = 1; asset <= 4; asset++) {
                bootstrap.userAccounts(u, asset);
            }
        }
        bootstrap.deposit(user, KRW, X);
        bootstrap.deposit(maker, XRP, new BigDecimal("100000"));
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> lease.isLeader() && promotion.gate().isAccepting());
        // 마켓은 컨텍스트가 뜬 뒤에 넣었다. 카탈로그의 미스 재적재는 주기(REFRESH_MS)마다 한 번이라, 첫 회의 동시 주문 둘이
        // 함께 미스를 내면 뒤 요청이 낡은 스냅샷으로 invalid_parameter 를 받는다(시험 준비의 경합, 2026-09-24 전체 빌드에서 한 번).
        // 세 마켓을 카탈로그가 알 때까지 기다린다.
        Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .until(() -> markets.bySymbol("KRW-BTC") != null && markets.bySymbol("KRW-ETH") != null
                        && markets.bySymbol("KRW-XRP") != null);
        assertEquals(OrderGateway.ReserveMode.DB_RESERVATION, gateway.reserveMode(), "게이트웨이가 P2 로 뜨지 않았다");
        // 양성 축: 앱 컨텍스트가 실제로 런타임 로그인이고 V49 권한 상태다. 소유자로 떴다면 이 시험은 권한 경계 밖이다.
        assertEquals(APP_USER, appJdbc.sql("SELECT current_user").query(String.class).single());
        assertEquals(Boolean.FALSE, appJdbc.sql("SELECT has_table_privilege('leader_lease', 'UPDATE')"
                + " OR has_table_privilege('command_journal', 'INSERT')").query(Boolean.class).single(),
                "앱 로그인에 V49 가 회수한 권한이 남아 있다");
    }

    @Test
    void exactlyOneOfTwoConcurrentSpendsWinsEveryRoundAndTradesDoNotDeadlock() throws Exception {
        long insufficientBefore = settlement.insufficientFunds();
        int successes = 0;
        int failures = 0;
        for (int round = 0; round < ROUNDS; round++) {
            assertEquals(0, X.compareTo(available(user)), "회 시작 가용이 X 가 아니다: round=" + round);
            CyclicBarrier barrier = new CyclicBarrier(2);
            CompletableFuture<Outcome> a = CompletableFuture.supplyAsync(() -> buy(barrier, 1, "10000", "1"), pool);
            CompletableFuture<Outcome> b = round % 2 == 0
                    ? CompletableFuture.supplyAsync(() -> buy(barrier, 2, "10000", "1"), pool)
                    : CompletableFuture.supplyAsync(() -> withdraw(barrier), pool);
            List<Outcome> outcomes = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
            long won = outcomes.stream().filter(Outcome::success).count();
            assertEquals(1, won, "round=" + round + " " + outcomes);
            for (Outcome o : outcomes) {
                if (o.success()) {
                    successes++;
                    undo(o);
                } else {
                    failures++;
                    assertTrue(o.status() == 400 || o.status() == 422, "잔고 부족이 아닌 거부: " + o);
                    assertEquals("insufficient_funds", o.code(), o.toString());
                }
            }
        }
        assertEquals(ROUNDS, successes);
        assertEquals(ROUNDS, failures);
        assertTrue(gateway.reserveRejections("db") > 0, "DB 예약 경합이 한 번도 나지 않았다(공허 통과)");
        assertEquals(insufficientBefore, settlement.insufficientFunds(), "정산이 잔고 부족으로 끝났다");
        assertTrue(lease.isLeader(), "리더가 강등됐다");
        assertInvariants();
        // 관문 기록(-Dpml.doubleSpend.rounds=1000)이 실제로 그 회 수를 돌았는지 시험 출력에서 확인하려고 한 줄 남긴다.
        log.info("이중 지출 단계 끝: 회 {}, 성공 {}, 실패 {}, DB 경로 거부 {}", ROUNDS, successes, failures,
                gateway.reserveRejections("db"));

        tradeMixingPhase();
    }

    /** 체결을 섞는 단계(검토 R6). 성공·실패 수를 세지 않고 접수 500·교착·계획 이탈 0 을 본다. */
    private void tradeMixingPhase() throws Exception {
        bootstrap.deposit(user, KRW, new BigDecimal("1000000"));
        long deadlocksBefore = deadlocks();
        long transient40P01 = gateway.appendTransient("40P01");
        long missesBefore = settlement.lockPlanMisses();
        AtomicInteger serverErrors = new AtomicInteger();
        List<String> errors = new ArrayList<>();
        for (int round = 0; round < TRADE_ROUNDS; round++) {
            // 호가는 다른 계정이 매 회 채운다. 매도 1 XRP @1000.
            placeAs(maker, 3, "ask", "1000", "1");
            CyclicBarrier barrier = new CyclicBarrier(3);
            // 환급 0 인 IOC 매수(최우선 매도와 같은 지정가, 호가보다 많은 수량): 부분 체결과 잔여 해제가 한 배치다.
            CompletableFuture<Outcome> ioc = CompletableFuture.supplyAsync(
                    () -> place(barrier, user, 3, "bid", "1000", "2", "IOC"), pool);
            CompletableFuture<Outcome> m1 = CompletableFuture.supplyAsync(
                    () -> place(barrier, user, 1, "bid", "100", "1", null), pool);
            CompletableFuture<Outcome> m2 = CompletableFuture.supplyAsync(
                    () -> place(barrier, user, 2, "bid", "100", "1", null), pool);
            for (Outcome o : List.of(ioc.get(60, TimeUnit.SECONDS), m1.get(60, TimeUnit.SECONDS),
                    m2.get(60, TimeUnit.SECONDS))) {
                if (o.status() >= 500) {
                    serverErrors.incrementAndGet();
                    errors.add(o.toString());
                }
                if (o.success() && o.market() != 3) {
                    gateway.cancel(user, UUID.fromString(o.externalId()));
                }
            }
        }
        assertEquals(0, serverErrors.get(), "접수 5xx: " + errors);
        assertEquals(transient40P01, gateway.appendTransient("40P01"), "접수 append 가 교착으로 끝났다");
        assertEquals(missesBefore, settlement.lockPlanMisses(), "정산 게시가 잠금 계획 밖 계정을 썼다");
        Thread.sleep(11_000);
        assertEquals(deadlocksBefore, deadlocks(), "pg_stat_database.deadlocks 가 늘었다");
        assertTrue(lease.isLeader());
        assertInvariants();
        log.info("체결 섞기 단계 끝: 회 {}, 접수 5xx {}, 교착 증가 {}", TRADE_ROUNDS, serverErrors.get(),
                deadlocks() - deadlocksBefore);
    }

    private record Outcome(boolean success, int status, String code, int market, String externalId,
                           String withdrawId) {
    }

    private Outcome buy(CyclicBarrier barrier, int market, String price, String volume) {
        return place(barrier, user, market, "bid", price, volume, null);
    }

    private Outcome place(CyclicBarrier barrier, long owner, int market, String side, String price, String volume,
                          String tif) {
        String key = "ds-" + UUID.randomUUID().toString().substring(0, 18);
        byte[] raw = ("{\"market\":\"" + symbol(market) + "\",\"side\":\"" + side + "\",\"ord_type\":\"limit\","
                + "\"price\":\"" + price + "\",\"volume\":\"" + volume + "\",\"client_order_id\":\"" + key + "\""
                + (tif == null ? "" : ",\"time_in_force\":\"" + tif + "\"") + "}").getBytes(StandardCharsets.UTF_8);
        try {
            await(barrier);
            OrderGateway.Placement p = gateway.place(owner, new ObjectMapper().readTree(raw), raw);
            return new Outcome(true, 201, null, market, p.row().externalId().toString(), null);
        } catch (ApiException e) {
            return new Outcome(false, e.status(), e.name(), market, null, null);
        } catch (Exception e) {
            return new Outcome(false, 500, e.toString(), market, null, null);
        }
    }

    private void placeAs(long owner, int market, String side, String price, String volume) {
        Outcome o = place(null, owner, market, side, price, volume, null);
        assertTrue(o.success(), "호가를 채우지 못했다: " + o);
    }

    private Outcome withdraw(CyclicBarrier barrier) {
        try {
            await(barrier);
            TransferService.Result r = transfers.request(user, "WITHDRAW", "KRW", X.toPlainString(),
                    "wd-" + UUID.randomUUID().toString().substring(0, 18));
            return new Outcome(true, 201, null, 0, null, String.valueOf(r.view().get("external_id")));
        } catch (ApiException e) {
            return new Outcome(false, e.status(), e.name(), 0, null, null);
        } catch (Exception e) {
            return new Outcome(false, 500, e.toString(), 0, null, null);
        }
    }

    /** 성공한 주문은 취소하고 출금은 본인 취소해 다음 회의 X 를 되돌린다. */
    private void undo(Outcome o) {
        if (o.withdrawId() != null) {
            transfers.cancel(o.withdrawId(), user);
        } else {
            OrderGateway.Placement canceled = gateway.cancel(user, UUID.fromString(o.externalId()));
            assertEquals("CANCELED", canceled.row().status());
        }
    }

    private void assertInvariants() {
        for (InvariantCheck check : new InvariantCheck[] {InvariantCheck.C01_ASSET_CONSERVATION,
                InvariantCheck.C02_BAL_VS_LEDGER, InvariantCheck.C14_LOCKED_RESERVATION,
                InvariantCheck.valueOf("C31_RESERVATION_LINK")}) {
            assertEquals(0L, owner.sql(check.sql()).query(Long.class).single(), check.name());
        }
        for (int market = 1; market <= 3; market++) {
            assertTrue(router.isRunning(market), "라이터가 멈췄다: market=" + market);
        }
    }

    private BigDecimal available(long userId) {
        return owner.sql("SELECT available FROM user_balances WHERE user_id = ? AND asset_id = ?")
                .params(userId, KRW).query(BigDecimal.class).single();
    }

    private long deadlocks() {
        return owner.sql("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")
                .query(Long.class).single();
    }

    private static String symbol(int market) {
        return switch (market) {
            case 1 -> "KRW-BTC";
            case 2 -> "KRW-ETH";
            case 3 -> "KRW-XRP";
            default -> throw new IllegalArgumentException("market " + market);
        };
    }

    private static void await(CyclicBarrier barrier) throws Exception {
        if (barrier != null) {
            barrier.await(30, TimeUnit.SECONDS);
        }
    }
}

package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.TestSigner;
import kr.exchange.api.auth.ApiAuthFilter;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.ApiKeyDirectory;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.auth.NonceStore;
import kr.exchange.api.auth.SecretCipher;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.DbFailures;
import kr.exchange.journal.DbOutageErrors;
import kr.exchange.journal.DbReadRetry;
import kr.exchange.journal.FlakyDataSource;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 트랜잭션 밖 읽기의 한 번 더 읽기(API 설계 §2-5-d 'DB가 흔들린 직후', S5 설계 4-6·6-2). 연결 끊김(08006)으로 끝난 인증 키 조회와
 * 접수 전 읽기는 다른 연결로 한 번 더 읽고, 닿으면 요청이 계속된다. 둘째마저 DB 에 닿지 못하면 종전 503 이다. 커넥션 획득 실패와
 * 문장 취소(57014)는 다시 부르지 않는다.
 */
class DbReadRetryTest {

    private static final int KRW = 1;
    private static final int BTC = 2;

    static PartitionLeaseDb db;
    static DriverManagerDataSource owner;
    static long user;
    static DemoKeySeeder.IssuedKey key;
    static SecretCipher cipher;
    static MarketDirectory markets;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("pml_read_retry");
        owner = new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER);
        JdbcClient jdbc = JdbcClient.create(owner);
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        user = bootstrap.user("retry@gw.test");
        bootstrap.userAccounts(user, KRW);
        bootstrap.userAccounts(user, BTC);
        bootstrap.deposit(user, KRW, new BigDecimal("1000000000"));
        cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        key = new DemoKeySeeder(jdbc, cipher).issue(user, "TRADE", "retry");
        markets = new MarketDirectory(jdbc);
        markets.start();
    }

    @AfterAll
    static void close() {
        if (markets != null) {
            markets.close();
        }
    }

    @Test
    void helperCallsOnceMoreOnlyAfterALostConnection() {
        DbReadRetry retry = DbReadRetry.untracked();
        assertEquals("ok", retry.read(DbReadRetry.ORDER, failing(1, DbFailures::connectionFailure)));
        assertEquals(1, retry.recovered(DbReadRetry.ORDER));

        RuntimeException first = DbFailures.connectionFailure();
        AtomicInteger calls = new AtomicInteger();
        RuntimeException twice = assertThrows(RuntimeException.class, () -> retry.read(DbReadRetry.ORDER, () -> {
            calls.incrementAndGet();
            throw calls.get() == 1 ? first : DbFailures.connectionFailure();
        }));
        assertSame(first, twice, "첫 예외를 올리지 않았다");
        assertEquals(2, calls.get());
        assertEquals(1, retry.unreachable(DbReadRetry.ORDER));

        for (Supplier<RuntimeException> once : java.util.List.<Supplier<RuntimeException>>of(
                DbFailures::acquireFailure, () -> DbFailures.sql("57014"))) {
            AtomicInteger n = new AtomicInteger();
            assertThrows(RuntimeException.class, () -> retry.read(DbReadRetry.ORDER, () -> {
                n.incrementAndGet();
                throw once.get();
            }));
            assertEquals(1, n.get(), "다시 부르지 않아야 할 실패를 다시 불렀다");
        }
        assertEquals(1, retry.recovered(DbReadRetry.ORDER));
        assertEquals(1, retry.unreachable(DbReadRetry.ORDER));
    }

    /** 인증 키 조회(site auth). 한 번이면 요청이 계속되고, 두 번이면 종전 503, 획득 실패는 다시 읽지 않고 503 이다. */
    @Test
    void authKeyLookupReadsOnceMoreAfterALostConnection() throws Exception {
        FlakyDataSource flaky = new FlakyDataSource(owner);
        DbReadRetry retry = DbReadRetry.untracked();
        ApiAuthFilter filter = new ApiAuthFilter(new ApiAuthService(new ApiKeyDirectory(JdbcClient.create(flaky), retry),
                cipher, new NonceStore(), new ObjectMapper(), null, null), new ErrorRenderer(new ObjectMapper()),
                new LeaderWriteProgress());

        flaky.failStatements(1, "08006", sql -> sql.contains("api_keys"));
        MockFilterChain passed = new MockFilterChain();
        MockHttpServletResponse ok = run(filter, passed);
        assertNotNull(passed.getRequest(), "한 번 끊긴 키 조회 뒤 요청이 계속되지 않았다: " + ok.getStatus());
        assertEquals(1, retry.recovered(DbReadRetry.AUTH));

        flaky.failStatements(2, "08006", sql -> sql.contains("api_keys"));
        MockFilterChain refused = new MockFilterChain();
        MockHttpServletResponse unavailable = run(filter, refused);
        assertEquals(503, unavailable.getStatus());
        assertNull(refused.getRequest());
        assertEquals(1, retry.unreachable(DbReadRetry.AUTH));

        int prepared = flaky.prepared();
        flaky.failAcquire(1);
        assertEquals(503, run(filter, new MockFilterChain()).getStatus());
        assertEquals(prepared, flaky.prepared(), "획득 실패 뒤 다시 읽었다");
        assertEquals(1, retry.recovered(DbReadRetry.AUTH));
        assertEquals(1, retry.unreachable(DbReadRetry.AUTH));
    }

    /** 주문 접수 전 읽기(site order). 계정 상태 조회가 한 번 끊겨도 접수된다. 두 번이면 DB 연결 장애 예외, 57014 는 한 번뿐이다. */
    @Test
    void orderIntakeReadsOnceMoreAfterALostConnection() throws Exception {
        FlakyDataSource flaky = new FlakyDataSource(owner);
        DbReadRetry retry = DbReadRetry.untracked();
        JdbcClient jdbc = JdbcClient.create(flaky);
        OrderGateway gateway = new ApiConfig().orderGateway(jdbc, new ProjectingFakeBridge(jdbc), markets,
                new OrderQueryService(jdbc), new SimpleMeterRegistry(), new LeaderProtocolBoot(2), PartitionPlan.single());
        gateway.attachRouting(RoutingCounters.untracked(), retry);

        flaky.failStatements(1, "08006", sql -> sql.contains("FROM users WHERE id"));
        OrderGateway.Placement placed = place(gateway, "retry-" + UUID.randomUUID().toString().substring(0, 8));
        assertEquals("OPEN", placed.row().status());
        assertEquals(1, retry.recovered(DbReadRetry.ORDER));

        flaky.failStatements(2, "08006", sql -> sql.contains("FROM users WHERE id"));
        RuntimeException unreachable = assertThrows(RuntimeException.class,
                () -> place(gateway, "retry-" + UUID.randomUUID().toString().substring(0, 8)));
        assertTrue(DbOutageErrors.isOutage(unreachable), "DB 연결 장애가 아니다: " + unreachable);
        assertEquals(1, retry.unreachable(DbReadRetry.ORDER));

        int prepared = flaky.prepared();
        flaky.failStatements(1, "57014", sql -> sql.contains("FROM users WHERE id"));
        assertThrows(RuntimeException.class, () -> place(gateway, "retry-" + UUID.randomUUID().toString().substring(0, 8)));
        assertEquals(prepared + 1, flaky.prepared(), "57014 뒤 다시 읽었다");
        assertEquals(1, OrderGatewayTestAccess.orderIdAssignments(gateway), "접수 전 읽기에서 끝난 접수가 채번했다");
    }

    private static MockHttpServletResponse run(ApiAuthFilter filter, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/open");
        request.addHeader("Authorization", TestSigner.bearerForQuery(key.accessKey(), key.secretKey(), "GET",
                "/api/v1/orders/open", Map.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static OrderGateway.Placement place(OrderGateway gateway, String coid) throws Exception {
        byte[] raw = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"1\",\"client_order_id\":\"" + coid + "\"}").getBytes(StandardCharsets.UTF_8);
        return gateway.place(user, new ObjectMapper().readTree(raw), raw);
    }

    private static Supplier<String> failing(int times, Supplier<RuntimeException> failure) {
        AtomicInteger left = new AtomicInteger(times);
        return () -> {
            if (left.getAndDecrement() > 0) {
                throw failure.get();
            }
            return "ok";
        };
    }
}

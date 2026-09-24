package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.auth.ApiAuthFilter;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 마켓 접수 경합의 관측(API 설계 §2-5-b, 하네스 CI/CD §5-2). exchange.order.intake.contention{cause,response}가 원인과 응답
 * 코드별로 세고, 인증 필터가 경합 응답의 노드 층 수요를 되돌린다(DB 설계 §5.4-c 8).
 */
class IntakeContentionMetricsTest {

    private static final int KRW = 1;
    private static final int BTC = 2;

    static PartitionLeaseDb db;
    static JdbcClient jdbc;
    static MarketDirectory markets;
    static long user;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("gw_contention_meters");
        jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER));
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        user = bootstrap.user("meters@gw.test");
        bootstrap.userAccounts(user, KRW);
        bootstrap.userAccounts(user, BTC);
        bootstrap.deposit(user, KRW, new BigDecimal("1000000"));
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
    void contentionMetersCarryCauseAndResponseAndSeparateStuckHolder() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EngineBridge[] next = new EngineBridge[1];
        OrderGateway gateway = new ApiConfig().orderGateway(jdbc, new OrderGatewayReserveMappingTest.FakeBridge(
                        intake -> next[0].submit(intake)), markets, new OrderQueryService(jdbc), registry,
                new LeaderProtocolBoot(2), PartitionPlan.single());

        Set<String> series = new HashSet<>();
        for (Meter meter : registry.find("exchange.order.intake.contention").meters()) {
            series.add(meter.getId().getTag("cause") + "|" + meter.getId().getTag("response"));
        }
        assertEquals(12, series.size(), "원인 여섯과 응답 둘의 계수기가 모두 등록되지 않았다: " + series);
        Set<String> states = new HashSet<>();
        for (Meter meter : registry.find("exchange.order.append.transient").meters()) {
            states.add(meter.getId().getTag("sqlstate"));
        }
        assertEquals(Set.of("55P03", "57014", "40P01", "40001"), states);

        next[0] = new OrderGatewayReserveMappingTest.FakeBridge(intake -> {
            throw new org.springframework.dao.CannotAcquireLockException("append", new PSQLException(
                    new ServerErrorMessage("SERROR\u0000C55P03\u0000Mlock timeout\u0000")));
        });
        assertEquals(429, assertThrows(ApiException.class, () -> place(gateway)).status());
        next[0] = new OrderGatewayReserveMappingTest.FakeBridge(intake -> {
            throw new EngineBridge.AcceptBusyException(1, 2000, true);
        });
        assertEquals(429, assertThrows(ApiException.class, () -> place(gateway)).status());

        assertEquals(1.0, count(registry, "55P03", "429"));
        assertEquals(1.0, count(registry, "lock_wait_stuck", "429"));
        assertEquals(0.0, count(registry, "lock_wait", "429"), "멈춘 보유자를 lock_wait 로 셌다");
        assertEquals(1.0, registry.get("exchange.order.append.transient").tag("sqlstate", "55P03")
                .functionCounter().count());
    }

    /** 경합 응답(429·409)만 노드 층 수요에서 되돌린다. 배치의 200 과 표식 없는 429(레이트리밋)는 되돌리지 않는다. */
    @Test
    void authFilterRevertsDemandOnlyForContentionResponses() throws Exception {
        LeaderWriteProgress progress = new LeaderWriteProgress();
        ApiAuthFilter filter = new ApiAuthFilter(new ApiAuthService(null, null, null, null, null, null) {
            @Override
            public AuthPrincipal verify(String authorizationHeader, String canonical, boolean needsTrade) {
                return new AuthPrincipal(user, "ak", "TRADE", AuthPrincipal.KIND_API_KEY);
            }
        }, new ErrorRenderer(new ObjectMapper()), progress);

        filter.doFilter(order(), new MockHttpServletResponse(), (req, res) -> {
            req.setAttribute(ApiException.DEMAND_EXCLUDED_ATTRIBUTE, Boolean.TRUE);
            ((HttpServletResponse) res).setStatus(429);
        });
        assertEquals(1, progress.attempts());
        assertEquals(1, progress.excluded(), "경합 429 의 수요를 되돌리지 않았다");

        filter.doFilter(order(), new MockHttpServletResponse(), (req, res) -> {
            req.setAttribute(ApiException.DEMAND_EXCLUDED_ATTRIBUTE, Boolean.TRUE);
            ((HttpServletResponse) res).setStatus(200);
        });
        filter.doFilter(order(), new MockHttpServletResponse(), (req, res) ->
                ((HttpServletResponse) res).setStatus(429));
        assertEquals(3, progress.attempts());
        assertEquals(1, progress.excluded(), "배치 200 이나 표식 없는 429 의 수요를 되돌렸다");
    }

    private static MockHttpServletRequest order() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader("Authorization", "Bearer test");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static double count(SimpleMeterRegistry registry, String cause, String response) {
        return registry.get("exchange.order.intake.contention").tag("cause", cause).tag("response", response)
                .functionCounter().count();
    }

    private static OrderGateway.Placement place(OrderGateway gateway) throws Exception {
        String key = "cm-" + UUID.randomUUID().toString().substring(0, 12);
        byte[] raw = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"1\",\"client_order_id\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
        return gateway.place(user, new ObjectMapper().readTree(raw), raw);
    }
}

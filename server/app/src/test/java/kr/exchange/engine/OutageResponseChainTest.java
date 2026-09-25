package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.auth.ApiAuthFilter;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiExceptionHandler;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.AccountRepository;
import kr.exchange.api.order.OrderController;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderGatewayTestAccess;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.order.OrderRejectionMetrics;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.DbFailures;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DB 연결 장애와 리더 자격 거부의 응답을 실제 요청 경로로 확인한다(S4b 설계 6-2, API 설계 §2-5-c). 인증 필터, 주문 컨트롤러,
 * 게이트웨이, 예외 처리기를 운영 순서로 세우고 브리지만 실패를 낸다. 세 응답 모두 마켓 접수 경합이 아니므로 좀비 판정 수요를
 * 되돌리지 않는다. 배치 주문은 한 원소가 DB 에 닿지 못하면 남은 원소를 시도하지 않는다.
 */
class OutageResponseChainTest {

    private static final int KRW = 1;
    private static final int BTC = 2;

    static PartitionLeaseDb db;
    static JdbcClient jdbc;
    static MarketDirectory markets;
    static long user;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("gw_outage_chain");
        jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER));
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        user = bootstrap.user("outage-chain@gw.test");
        bootstrap.userAccounts(user, KRW);
        bootstrap.userAccounts(user, BTC);
        bootstrap.deposit(user, KRW, new BigDecimal("1000000000"));
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
    void outageResponsesThroughTheRealChainAreNotContentionDemand() throws Exception {
        Chain chain = new Chain();
        chain.submit = intake -> {
            throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
        };
        chain.v47Down.set(true);
        chain.place(order(unique("chain-unreachable")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error.name").value("service_unavailable"))
                .andExpect(jsonPath("$.error.message").value("DB 연결 장애. 재조회 후 판단 필요"));
        chain.v47Down.set(false);

        chain.place(order(unique("chain-discarded")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.detail.bucket").value("market"));

        String kept = unique("chain-kept");
        chain.submit = intake -> {
            jdbc.sql("UPDATE idempotency_keys SET shared_at = now() WHERE user_id = ? AND idem_key = ?")
                    .params(user, kept).update();
            throw DbFailures.fencing();
        };
        chain.place(order(kept))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.name").value("order_in_progress"));

        assertEquals(3, chain.progress.attempts(), "인증 필터가 주문 쓰기 수요를 세지 않았다");
        assertEquals(0, chain.progress.excluded(), "경합이 아닌 응답의 수요를 되돌렸다");
    }

    @Test
    void batchStopsAfterAnElementThatCannotReachTheDatabase() throws Exception {
        Chain chain = new Chain();
        AtomicInteger calls = new AtomicInteger();
        chain.submit = intake -> {
            if (calls.incrementAndGet() == 2) {
                chain.v47Down.set(true);
                throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
            }
            jdbc.sql("""
                            INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                                price, quantity, remaining_qty, status)
                            VALUES (?, ?, ?, 1, ?, 'BUY', 'LIMIT', 50000, 1, 1, 'OPEN')
                            """)
                    .params(intake.assignedOrderId(), UUID.randomUUID(), intake.userId(), intake.clientOrderId())
                    .update();
            return new SubmitResult(calls.get(), 1, false, true, true);
        };
        String third = unique("batch-3");
        String body = "[" + order(unique("batch-1")) + "," + order(unique("batch-2")) + "," + order(third) + "]";

        chain.mvc.perform(post("/api/v1/orders/batch").header("Authorization", "Bearer test")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("accepted"))
                .andExpect(jsonPath("$.results[1].status").value("in_progress"))
                .andExpect(jsonPath("$.results[1].error.name").value("service_unavailable"))
                .andExpect(jsonPath("$.results[2].status").value("in_progress"))
                .andExpect(jsonPath("$.results[2].client_order_id").value(third))
                .andExpect(jsonPath("$.results[2].error.name").value("service_unavailable"))
                .andExpect(jsonPath("$.results[2].error.retry_after").value(1))
                .andExpect(jsonPath("$.summary.in_progress").value(2));
        assertEquals(2, calls.get(), "DB 에 닿지 못한 원소 뒤의 원소를 시도했다");
    }

    private static String order(String key) {
        return "{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"1\",\"client_order_id\":\"" + key + "\"}";
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 운영 순서의 요청 경로. 브리지와 V47 판정만 시험이 정한다. 게이트는 열려 있다. */
    private static final class Chain {
        final LeaderWriteProgress progress = new LeaderWriteProgress();
        final AtomicBoolean v47Down = new AtomicBoolean();
        final MockMvc mvc;
        volatile Function<OrderIntake, SubmitResult> submit;

        Chain() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            EngineBridge bridge = new EngineBridge(null, new SingleWriterLoop(16, new MatchingEngineImpl(),
                    events -> { }, (afterSeq, limit) -> List.of(), fatal -> { }),
                    new CommandPayloadMapper(new ObjectMapper()), AcceptGate.ALWAYS_OPEN, marketId -> false) {
                @Override
                public SubmitResult submit(OrderIntake intake) {
                    return submit.apply(intake);
                }
            };
            OrderGateway gateway = new ApiConfig().orderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc),
                    registry, new LeaderProtocolBoot(2), PartitionPlan.single());
            OrderGatewayTestAccess.interceptResolutions(gateway,
                    call -> v47Down.get() ? DbFailures.acquireFailure() : null);
            OrderController controller = new OrderController(gateway, new OrderQueryService(jdbc),
                    new EpochSource(TestPartitions.unstarted(PartitionPlan.single()), jdbc), new ObjectMapper(),
                    new AccountRepository(jdbc), new OrderRejectionMetrics(registry), markets,
                    new AccountRateLimiter(10_000, 10_000, "account", "query"), 100_000);
            ApiAuthFilter auth = new ApiAuthFilter(new ApiAuthService(null, null, null, null, null, null) {
                @Override
                public AuthPrincipal verify(String authorizationHeader, String canonical, boolean needsTrade) {
                    return new AuthPrincipal(user, "ak", "TRADE", AuthPrincipal.KIND_API_KEY);
                }
            }, new ErrorRenderer(new ObjectMapper()), progress);
            mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new ApiExceptionHandler(new ErrorRenderer(new ObjectMapper())))
                    .addFilters(auth).build();
        }

        ResultActions place(String body) throws Exception {
            return mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer test")
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }
    }
}

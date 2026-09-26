package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.auth.ApiAuthFilter;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiExceptionHandler;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.AccountRepository;
import kr.exchange.api.order.OrderController;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.order.OrderRejectionMetrics;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 노드 층 좀비 판정의 경합 수요 제외를 실제 요청 경로로 확인한다(S4 설계 4-11·13절, DB 설계 §5.4-c 8). 인증 필터가 주문 쓰기
 * 수요를 세고, 게이트웨이가 경합으로 만든 API 예외를 예외 처리기가 응답으로 쓰면서 요청에 표식을 남기며, 인증 필터가 체인 뒤
 * 그 표식과 응답 코드로 수요를 되돌린다. 판정기는 시계를 주입한 노드 층 판정기이고 강등 기준은 운영 값 45초다.
 */
class ContentionDemandChainTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final Duration STANDDOWN_AFTER = Duration.ofSeconds(45);

    static PartitionLeaseDb db;
    static JdbcClient jdbc;
    static MarketDirectory markets;
    static long user;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("gw_demand_chain");
        jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER));
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        user = bootstrap.user("chain@gw.test");
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

    /** 확정 미접수의 429 와 같은 키 다른 요청이 관여한 409 가 수요 하한을 넘겨도 노드 층은 강등 기준 뒤에 강등하지 않는다. */
    @Test
    void contentionResponsesThroughTheRealChainAreNotDemand() throws Exception {
        Chain chain = new Chain(new ApiExceptionHandler(new ErrorRenderer(new ObjectMapper())));
        chain.failure = intake -> contention("55P03");
        for (int i = 0; i < 13; i++) {
            assertEquals(429, chain.place(), "확정 미접수 경합이 429 가 아니다");
        }
        // KEPT: 같은 내용의 다른 요청이 이 키에 공유 표시를 채워 V47 이 키를 남긴다.
        chain.failure = intake -> {
            jdbc.sql("UPDATE idempotency_keys SET shared_at = now() WHERE user_id = ? AND idem_key = ?")
                    .params(intake.userId(), intake.clientOrderId()).update();
            return contention("40001");
        };
        for (int i = 0; i < 13; i++) {
            assertEquals(409, chain.place(), "확정할 수 없는 경합이 409 가 아니다");
        }
        assertEquals(26, chain.progress.attempts(), "인증 필터가 주문 쓰기 수요를 세지 않았다");
        assertEquals(26, chain.progress.excluded(), "경합 응답의 수요를 되돌리지 않았다");

        assertFalse(chain.stalledAfterTheStanddownWindow(), "경합 응답만으로 노드 층이 강등했다");
    }

    /** 대조군. 표식을 남기지 않는 예외 처리기로 같은 경합을 보내면 수요로 남아 강등 기준 뒤 불능이다. */
    @Test
    void withoutTheDemandMarkTheSameContentionStallsTheNode() throws Exception {
        Chain chain = new Chain(new UnmarkedAdvice());
        chain.failure = intake -> contention("55P03");
        for (int i = 0; i < 25; i++) {
            assertEquals(429, chain.place());
        }
        assertEquals(0, chain.progress.excluded());

        assertTrue(chain.stalledAfterTheStanddownWindow(), "대조군이 강등하지 않아 판정이 공허하다");
    }

    /** 멈춘 보유자 뒤의 접수 락 대기 초과는 429 여도 수요로 남는다. 강등 기준 뒤 불능이다. */
    @Test
    void stuckHolderTimeoutsStayDemandAtTheNodeLayer() throws Exception {
        Chain chain = new Chain(new ApiExceptionHandler(new ErrorRenderer(new ObjectMapper())));
        chain.failure = intake -> new EngineBridge.AcceptBusyException(1, 2000, true);
        for (int i = 0; i < 25; i++) {
            assertEquals(429, chain.place());
        }
        assertEquals(25, chain.progress.attempts());
        assertEquals(0, chain.progress.excluded(), "멈춘 보유자 때문의 대기 초과를 수요에서 뺐다");

        assertTrue(chain.stalledAfterTheStanddownWindow(), "멈춘 보유자를 노드 층이 판정하지 않았다");
    }

    private static RuntimeException contention(String state) {
        return new CannotAcquireLockException("append " + state,
                new PSQLException(new ServerErrorMessage("SERROR\u0000C" + state + "\u0000Mappend failed\u0000")));
    }

    /** 인증 필터, 주문 컨트롤러, 게이트웨이, 예외 처리기를 운영과 같은 순서로 세운 요청 경로. 브리지만 경합을 낸다. */
    private static final class Chain {
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        final LeaderWriteProgress progress = new LeaderWriteProgress();
        final LeaderStallDetector node;
        final MockMvc mvc;
        volatile Function<OrderIntake, RuntimeException> failure;

        Chain(Object advice) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            node = LeaderStallDetector.node(null, progress, STANDDOWN_AFTER, registry, clock::get);
            node.onLeadershipGained();
            EngineBridge bridge = new EngineBridge(null, new SingleWriterLoop(16, new MatchingEngineImpl(),
                    events -> { }, (afterSeq, limit) -> List.of(), fatal -> { }),
                    new CommandPayloadMapper(new ObjectMapper()), AcceptGate.ALWAYS_OPEN, marketId -> false) {
                @Override
                public SubmitResult submit(OrderIntake intake) {
                    throw failure.apply(intake);
                }
            };
            OrderGateway gateway = new ApiConfig().orderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc),
                    registry, new LeaderProtocolBoot(2), PartitionPlan.single());
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
            mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).addFilters(auth).build();
        }

        int place() throws Exception {
            String key = "chain-" + UUID.randomUUID().toString().substring(0, 12);
            String body = "{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                    + "\"volume\":\"1\",\"client_order_id\":\"" + key + "\"}";
            return mvc.perform(post("/api/v1/orders").header("Authorization", "Bearer test")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
        }

        /** 붕괴 관측을 시작할 표본을 뜬 뒤 시계를 강등 기준보다 멀리 옮겨 다시 판정한다. */
        boolean stalledAfterTheStanddownWindow() {
            boolean first = node.serviceable();
            clock.addAndGet(STANDDOWN_AFTER.plusSeconds(1).toNanos());
            return first && !node.serviceable();
        }
    }

    /** 경합 표식을 남기지 않고 API 예외를 같은 코드로만 쓰는 예외 처리기. 대조군 배선이다. */
    @RestControllerAdvice
    static final class UnmarkedAdvice {
        private final ErrorRenderer renderer = new ErrorRenderer(new ObjectMapper());

        @ExceptionHandler(ApiException.class)
        ResponseEntity<Object> apiException(ApiException e) {
            return ResponseEntity.status(e.status())
                    .body(renderer.body(e.name(), e.getMessage(), e.detail(), e.retryAfter(), "chain"));
        }
    }
}

package kr.exchange.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.CancelHandoffCounters;
import kr.exchange.api.order.OrderCancelHandoff;
import kr.exchange.api.order.OrderCancelHandoffRepository;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.order.ProjectingFakeBridge;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.DbFailures;
import kr.exchange.journal.DbReadRetry;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 좀비 판정 노드 층 수요의 되돌림(DB 설계 §5.4-c 8 v2.57, S5 설계 4-8·6-2). 운영 순서 체인에서 두 경우만 응답 코드와 무관하게
 * 되돌린다. 게이트웨이의 소유 아님 503 과, 이 노드에서 submit 을 하나도 하지 않은 일괄 취소(모두 인계)다. 인계 행 넣기가 DB 오류로
 * 끝난 일괄 취소, 이 노드에서 submit 한 일괄 취소, 보통의 생성은 되돌리지 않는다. 선검사의 503 은 인증 필터 앞이라 시도 자체가 없다.
 */
class DemandExclusionRoutingTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final AtomicInteger USERS = new AtomicInteger();

    static PartitionLeaseDb db;
    static DriverManagerDataSource dataSource;
    static JdbcClient jdbc;
    static AccountBootstrap bootstrap;
    static MarketDirectory markets;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("pml_demand_routing");
        dataSource = new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER);
        jdbc = JdbcClient.create(dataSource);
        bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
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
    void gatewayNotOwnerIsExcluded() throws Exception {
        Node node = node(false, new OrderCancelHandoffRepository(jdbc, tx()));
        UUID open = openOrder(node.user);
        MockHttpServletResponse r = node.chain.mvc.perform(delete("/api/v1/orders/" + open)
                .header("Authorization", "Bearer test")).andReturn().getResponse();
        assertEquals(503, r.getStatus());
        assertEquals(1, node.chain.progress.attempts());
        assertEquals(1, node.chain.progress.excluded(), "소유 아님 503 의 수요를 되돌리지 않았다");
    }

    @Test
    void cancelAllThatOnlyHandsOverIsExcluded() throws Exception {
        Node node = node(false, new OrderCancelHandoffRepository(jdbc, tx()));
        openOrder(node.user);
        MockHttpServletResponse r = node.chain.mvc.perform(delete("/api/v1/orders")
                .header("Authorization", "Bearer test")).andReturn().getResponse();
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertTrue(r.getContentAsString().contains("partition_not_started"), r.getContentAsString());
        assertEquals(1, node.chain.progress.excluded(), "인계만 한 일괄 취소의 수요를 되돌리지 않았다");
    }

    @Test
    void cancelAllWhoseHandoffInsertFailedStaysDemand() throws Exception {
        OrderCancelHandoffRepository failing = new OrderCancelHandoffRepository(jdbc, tx()) {
            @Override
            public List<Long> insert(UUID requestId, long userId, String origin, List<NewRow> rows) {
                throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
            }
        };
        Node node = node(false, failing);
        openOrder(node.user);
        MockHttpServletResponse r = node.chain.mvc.perform(delete("/api/v1/orders")
                .header("Authorization", "Bearer test")).andReturn().getResponse();
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertTrue(r.getContentAsString().contains("service_unavailable"), r.getContentAsString());
        assertEquals(1, node.chain.progress.attempts());
        assertEquals(0, node.chain.progress.excluded(), "넣기가 DB 오류로 끝난 일괄 취소를 되돌렸다");
    }

    @Test
    void cancelAllThatSubmitsLocallyStaysDemand() throws Exception {
        Node node = node(true, new OrderCancelHandoffRepository(jdbc, tx()));
        UUID open = openOrder(node.user);
        MockHttpServletResponse r = node.chain.mvc.perform(delete("/api/v1/orders")
                .header("Authorization", "Bearer test")).andReturn().getResponse();
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertTrue(r.getContentAsString().contains("\"canceled_ids\":[\"" + open + "\"]"), r.getContentAsString());
        assertEquals(1, node.chain.progress.attempts());
        assertEquals(0, node.chain.progress.excluded(), "이 노드에서 submit 한 일괄 취소의 수요를 되돌렸다");
    }

    @Test
    void ordinaryCreateStaysDemand() throws Exception {
        Node node = node(true, new OrderCancelHandoffRepository(jdbc, tx()));
        MockHttpServletResponse r = node.chain.mvc.perform(post("/api/v1/orders")
                .header("Authorization", "Bearer test").contentType(MediaType.APPLICATION_JSON)
                .content(body("demand-" + UUID.randomUUID().toString().substring(0, 8)))).andReturn().getResponse();
        assertEquals(201, r.getStatus(), r.getContentAsString());
        assertEquals(1, node.chain.progress.attempts());
        assertEquals(0, node.chain.progress.excluded());
    }

    @Test
    void precheckRefusalNeverReachesTheDemandCounter() throws Exception {
        Node node = node(false, new OrderCancelHandoffRepository(jdbc, tx()));
        MockHttpServletResponse r = node.chain.mvc.perform(post("/api/v1/orders")
                .header("Authorization", "Bearer test").header("X-Exchange-Market", "KRW-BTC")
                .contentType(MediaType.APPLICATION_JSON).content(body("demand-pre"))).andReturn().getResponse();
        assertEquals(503, r.getStatus());
        assertEquals(0, node.chain.progress.attempts(), "선검사 503 이 인증 필터에 닿았다");
    }

    private record Node(long user, RoutedChain chain) {
    }

    /**
     * 게이트가 열렸거나 닫힌 노드 하나. 닫힌 노드에는 인계 요청자가 붙고 작업자는 없다(기다림 200ms). 열린 노드는 모두 직접
     * 취소한다.
     */
    private static Node node(boolean open, OrderCancelHandoffRepository repo) {
        long user = bootstrap.user("demand-" + USERS.incrementAndGet() + "@gw.test");
        bootstrap.userAccounts(user, KRW);
        bootstrap.userAccounts(user, BTC);
        bootstrap.deposit(user, KRW, new BigDecimal("100000000"));
        ProjectingFakeBridge bridge = new ProjectingFakeBridge(jdbc);
        bridge.open = open;
        OrderGateway gateway = new ApiConfig().orderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc),
                new SimpleMeterRegistry(), new LeaderProtocolBoot(2), PartitionPlan.single());
        gateway.attachRouting(RoutingCounters.untracked(), DbReadRetry.untracked());
        PartitionLeases leases = open ? null : TestPartitions.unstarted(PartitionPlan.single());
        if (leases != null) {
            gateway.attachCancelHandoff(new OrderCancelHandoff(repo, PartitionPlan.single(), leases,
                    new OrderCancelHandoff.Settings(Duration.ofMillis(200), Duration.ofMillis(50), 100, 1,
                            Duration.ofSeconds(60)), CancelHandoffCounters.untracked()));
        }
        ApiAuthService auth = new ApiAuthService(null, null, null, null, null, null) {
            @Override
            public AuthPrincipal verify(String authorizationHeader, String canonical, boolean needsTrade) {
                return new AuthPrincipal(user, "ak", "TRADE", AuthPrincipal.KIND_API_KEY);
            }
        };
        return new Node(user, new RoutedChain(jdbc, markets, bridge, gateway, auth, true, false));
    }

    private static TransactionTemplate tx() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private static UUID openOrder(long user) {
        UUID external = UUID.randomUUID();
        long id = jdbc.sql("SELECT nextval('order_id_seq')").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                            price, quantity, remaining_qty, status)
                        VALUES (?, ?, ?, 1, ?, 'BUY', 'LIMIT', 50000, 1, 1, 'OPEN')
                        """)
                .params(id, external, user, "demand-open-" + id)
                .update();
        return external;
    }

    private static String body(String coid) {
        return "{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"1\",\"client_order_id\":\"" + coid + "\"}";
    }
}

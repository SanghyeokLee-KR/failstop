package kr.exchange.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderGatewayTestAccess;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.order.ProjectingFakeBridge;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.DbReadRetry;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 힌트 없는 단건 취소가 그 마켓을 접수하지 않는 노드에 간 경우(API 설계 §2-5-d·§4-B-2, S5 설계 4-2·4-8·6-2). 선검사는 대상 주문을
 * 읽어야 마켓을 알므로 넘기고, 게이트웨이가 대상 주문을 읽은 뒤 채번 전에 소유 아님 503 을 낸다. 이 응답은 좀비 판정 수요에서
 * 되돌린다. 이미 종결된 주문은 쓰지 않으므로 소유하지 않는 노드도 200 + 현재 상태로 답한다.
 */
class UnhintedCancelNotOwnerTest {

    private static final int KRW = 1;
    private static final int BTC = 2;

    static PartitionLeaseDb db;
    static JdbcClient jdbc;
    static MarketDirectory markets;
    static long user;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("pml_unhinted");
        jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER));
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        user = bootstrap.user("unhinted@gw.test");
        bootstrap.userAccounts(user, KRW);
        bootstrap.userAccounts(user, BTC);
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
    void openOrderOfAForeignMarketIsRefusedBeforeAssignmentAndNotCountedAsDemand() throws Exception {
        ProjectingFakeBridge bridge = new ProjectingFakeBridge(jdbc);
        bridge.open = false;
        OrderGateway gateway = gateway(bridge);
        RoutedChain chain = new RoutedChain(jdbc, markets, bridge, gateway, stubAuth(), true, false);
        UUID open = order("OPEN");
        long journal = count("SELECT count(*) FROM command_journal");

        MockHttpServletResponse refused = chain.mvc.perform(delete("/api/v1/orders/" + open)
                .header("Authorization", "Bearer test")).andReturn().getResponse();

        assertEquals(503, refused.getStatus(), refused.getContentAsString());
        assertEquals("not-owner", refused.getHeader("X-Exchange-Route"));
        assertEquals("1", refused.getHeader("Retry-After"));
        JsonNode error = new ObjectMapper().readTree(refused.getContentAsByteArray()).path("error");
        assertEquals("not_owner", error.path("detail").path("route").asText());
        assertEquals(0, OrderGatewayTestAccess.orderIdAssignments(gateway), "채번 뒤에 돌려보냈다");
        assertEquals(0, bridge.submits.get());
        assertEquals(journal, count("SELECT count(*) FROM command_journal"));
        assertEquals(1, chain.progress.attempts(), "인증 필터가 수요를 세지 않았다");
        assertEquals(0, chain.netDemand(), "소유 아님 503 을 수요로 남겼다");
        assertEquals(1, chain.counters.cancelNotOwnerCount());
        assertEquals(1, chain.counters.precheckCount(RoutingCounters.UNRESOLVED), "힌트 없는 단건 취소는 선검사가 넘긴다");
        assertEquals(0, chain.counters.precheckCount(RoutingCounters.NOT_OWNER), "선검사 계수에 게이트웨이 503 을 셌다");
        assertEquals("OPEN", status(open));
    }

    @Test
    void terminalOrderIsAnsweredWithItsCurrentStateByAnyNode() throws Exception {
        ProjectingFakeBridge bridge = new ProjectingFakeBridge(jdbc);
        bridge.open = false;
        OrderGateway gateway = gateway(bridge);
        RoutedChain chain = new RoutedChain(jdbc, markets, bridge, gateway, stubAuth(), true, false);
        UUID canceled = order("CANCELED");

        MockHttpServletResponse ok = chain.mvc.perform(delete("/api/v1/orders/" + canceled)
                .header("Authorization", "Bearer test")).andReturn().getResponse();

        assertEquals(200, ok.getStatus(), ok.getContentAsString());
        assertEquals("CANCELED", new ObjectMapper().readTree(ok.getContentAsByteArray()).path("status").asText());
        assertEquals(0, chain.counters.cancelNotOwnerCount());
        assertEquals(0, OrderGatewayTestAccess.orderIdAssignments(gateway));
    }

    private static OrderGateway gateway(ProjectingFakeBridge bridge) {
        OrderGateway gateway = new ApiConfig().orderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc),
                new SimpleMeterRegistry(), new LeaderProtocolBoot(2), PartitionPlan.single());
        gateway.attachRouting(RoutingCounters.untracked(), DbReadRetry.untracked());
        return gateway;
    }

    static ApiAuthService stubAuth() {
        return new ApiAuthService(null, null, null, null, null, null) {
            @Override
            public AuthPrincipal verify(String authorizationHeader, String canonical, boolean needsTrade) {
                return new AuthPrincipal(user, "ak", "TRADE", AuthPrincipal.KIND_API_KEY);
            }
        };
    }

    private static UUID order(String status) {
        UUID external = UUID.randomUUID();
        long id = jdbc.sql("SELECT nextval('order_id_seq')").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                            price, quantity, remaining_qty, status)
                        VALUES (?, ?, ?, 1, ?, 'BUY', 'LIMIT', 50000, 1, ?, ?)
                        """)
                .params(id, external, user, "unhinted-" + id, "OPEN".equals(status) ? 1 : 0, status)
                .update();
        return external;
    }

    private static String status(UUID order) {
        return jdbc.sql("SELECT status FROM orders WHERE external_id = ?").param(order).query(String.class).single();
    }

    private static long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

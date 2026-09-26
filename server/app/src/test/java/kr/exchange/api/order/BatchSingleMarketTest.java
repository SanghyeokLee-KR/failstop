package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 한 배치 한 마켓(API 설계 §4-B-6 v2.57, S5 설계 4-3·6-2). market 이 문자열인 원소들의 값이 둘 이상이면 요청 전체가 400
 * batch_single_market 이고, 계정 토큰 차감과 원소 처리보다 먼저라 토큰·멱등 키·예약·채번을 남기지 않는다. 힌트가 원소의 마켓과
 * 다르면 400 market_hint_mismatch 다. market 이 없는 원소는 종전처럼 그 원소만 rejected 다.
 */
class BatchSingleMarketTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int ETH = 3;
    private static final AtomicInteger KEYS = new AtomicInteger();

    static PartitionLeaseDb db;
    static JdbcClient jdbc;
    static MarketDirectory markets;
    static AccountBootstrap bootstrap;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("pml_batch_single");
        jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER));
        bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.asset(ETH, "ETH", "Ether", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        bootstrap.market(2, "KRW-ETH", ETH, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
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
    void mixedMarketsAreRefusedBeforeTokensAndKeys() throws Exception {
        long user = user();
        HintedOrderApi api = new HintedOrderApi(jdbc, markets, user);
        String a = key();
        String b = key();
        MockHttpServletResponse mixed = batch(api, null, "[" + body("KRW-BTC", a) + "," + body("KRW-ETH", b) + "]");
        assertEquals(400, mixed.getStatus(), mixed.getContentAsString());
        assertEquals("batch_single_market", error(mixed).path("detail").path("constraint").asText());
        assertNull(mixed.getHeader("X-RateLimit-Remaining-account"), "계정 토큰 차감 뒤에 거절했다");
        assertEquals(0, keyRows(user, a) + keyRows(user, b), "멱등 키를 남겼다");
        assertEquals(0, OrderGatewayTestAccess.orderIdAssignments(api.gateway));
        assertEquals(1, api.counters.hintRejectedCount(RoutingCounters.BATCH_SINGLE_MARKET));

        // 계정 버킷이 그대로다. 다음 한 원소 배치가 한도에서 한 번만 빠진 잔량을 받는다.
        MockHttpServletResponse single = batch(api, null, "[" + body("KRW-BTC", key()) + "]");
        assertEquals(200, single.getStatus(), single.getContentAsString());
        assertEquals(String.valueOf(HintedOrderApi.ORDER_CAPACITY - 5), single.getHeader("X-RateLimit-Remaining-account"));
    }

    @Test
    void oneMarketBatchKeepsItsPerItemContract() throws Exception {
        long user = user();
        HintedOrderApi api = new HintedOrderApi(jdbc, markets, user);
        MockHttpServletResponse r = batch(api, "KRW-BTC", "[" + body("KRW-BTC", key()) + ","
                + "{\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\",\"volume\":\"1\",\"client_order_id\":\""
                + key() + "\"}," + body("KRW-BTC", key()) + "]");
        assertEquals(200, r.getStatus(), r.getContentAsString());
        JsonNode results = new ObjectMapper().readTree(r.getContentAsByteArray()).path("results");
        assertEquals("accepted", results.get(0).path("status").asText());
        assertEquals("rejected", results.get(1).path("status").asText(), "market 없는 원소는 그 원소만 거절된다");
        assertEquals("invalid_parameter", results.get(1).path("error").path("name").asText());
        assertEquals("accepted", results.get(2).path("status").asText());
    }

    @Test
    void hintDifferentFromTheElementsIsRefused() throws Exception {
        long user = user();
        HintedOrderApi api = new HintedOrderApi(jdbc, markets, user);
        String a = key();
        MockHttpServletResponse r = batch(api, "KRW-ETH", "[" + body("KRW-BTC", a) + "]");
        assertEquals(400, r.getStatus());
        assertEquals("market_hint_mismatch", error(r).path("detail").path("constraint").asText());
        assertEquals(0, keyRows(user, a));
        assertNull(r.getHeader("X-RateLimit-Remaining-account"));
    }

    private static MockHttpServletResponse batch(HintedOrderApi api, String hint, String body) throws Exception {
        var request = post("/api/v1/orders/batch").contentType(MediaType.APPLICATION_JSON).content(body);
        if (hint != null) {
            request.header("X-Exchange-Market", hint);
        }
        return api.mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode error(MockHttpServletResponse r) throws Exception {
        return new ObjectMapper().readTree(r.getContentAsByteArray()).path("error");
    }

    private static long user() {
        long id = bootstrap.user("batch-" + KEYS.incrementAndGet() + "@gw.test");
        for (int asset : new int[] {KRW, BTC, ETH}) {
            bootstrap.userAccounts(id, asset);
        }
        bootstrap.deposit(id, KRW, new BigDecimal("1000000000"));
        return id;
    }

    private static String body(String market, String key) {
        return "{\"market\":\"" + market + "\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"1\",\"client_order_id\":\"" + key + "\"}";
    }

    private static String key() {
        return "batch-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long keyRows(long user, String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(user, key).query(Long.class).single();
    }
}

package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.CommandDraft;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 마켓 힌트와 서버 대조(API 설계 §2-5-d·§4-B-1·§4-B-2, S5 설계 4-2·6-2). 힌트가 요청이 가리키는 마켓과 다르면 400
 * market_hint_mismatch 이고 멱등 키·예약·채번을 남기지 않는다. 대조는 대소문자까지 한다. 종결 주문도 힌트가 틀리면 400 이다.
 * 저널에만 있는 by-client 취소는 마켓을 모르므로 힌트를 대조하지 않는다. 마켓 없는 일괄 취소에 실린 힌트도 400 이다.
 */
class MarketHintMismatchTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int ETH = 3;
    private static final AtomicInteger KEYS = new AtomicInteger();

    static PartitionLeaseDb db;
    static JdbcClient jdbc;
    static MarketDirectory markets;
    static long user;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("pml_hint_mismatch");
        jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER));
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.asset(ETH, "ETH", "Ether", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        bootstrap.market(2, "KRW-ETH", ETH, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        user = bootstrap.user("hint@gw.test");
        for (int asset : new int[] {KRW, BTC, ETH}) {
            bootstrap.userAccounts(user, asset);
        }
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
    void createWithAHintOtherThanTheBodyMarketLeavesNothing() throws Exception {
        HintedOrderApi api = new HintedOrderApi(jdbc, markets, user);
        for (String hint : new String[] {"KRW-ETH", "krw-btc"}) {
            String key = key();
            MockHttpServletResponse r = perform(api, post("/api/v1/orders").header("X-Exchange-Market", hint)
                    .contentType(MediaType.APPLICATION_JSON).content(body("KRW-BTC", key)));
            assertMismatch(r, hint);
            assertEquals(0, keyRows(key), hint + " 불일치인데 멱등 키를 남겼다");
        }
        assertEquals(0, OrderGatewayTestAccess.orderIdAssignments(api.gateway));
        assertEquals(0, api.bridge.submits.get());
        assertEquals(2, api.counters.hintRejectedCount(RoutingCounters.HINT_MISMATCH));
        // 맞는 힌트는 그대로 접수된다.
        MockHttpServletResponse ok = perform(api, post("/api/v1/orders").header("X-Exchange-Market", "KRW-BTC")
                .contentType(MediaType.APPLICATION_JSON).content(body("KRW-BTC", key())));
        assertEquals(201, ok.getStatus(), ok.getContentAsString());
    }

    @Test
    void cancelWithAHintOtherThanTheOrderMarketIsRefusedBeforeAssignment() throws Exception {
        HintedOrderApi api = new HintedOrderApi(jdbc, markets, user);
        UUID open = order("OPEN");
        UUID canceled = order("CANCELED");
        assertMismatch(perform(api, delete("/api/v1/orders/" + open).header("X-Exchange-Market", "KRW-ETH")), "열린 주문");
        assertMismatch(perform(api, delete("/api/v1/orders/" + canceled).header("X-Exchange-Market", "KRW-ETH")),
                "종결 주문");
        assertMismatch(perform(api, delete("/api/v1/orders/" + open).header("X-Exchange-Market", "krw-btc")),
                "소문자 힌트");
        assertEquals(0, OrderGatewayTestAccess.orderIdAssignments(api.gateway), "채번 뒤에 대조했다");
        assertEquals("OPEN", status(open));
        MockHttpServletResponse ok = perform(api, delete("/api/v1/orders/" + open).header("X-Exchange-Market", "KRW-BTC"));
        assertEquals(200, ok.getStatus(), ok.getContentAsString());
        assertEquals("CANCELED", status(open));
    }

    @Test
    void byClientCancelOfAJournalOnlyOrderIgnoresTheHint() throws Exception {
        HintedOrderApi api = new HintedOrderApi(jdbc, markets, user);
        String key = key();
        long seq = jdbc.sql("SELECT COALESCE(max(seq), 0) + 1 FROM command_journal").query(Long.class).single();
        long id = jdbc.sql("SELECT nextval('order_id_seq')").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                     assigned_order_id, engine_ts, tie_break, payload, fencing_token,
                                                     checksum)
                        VALUES (?, 1, ?, ?, ?, ?, 0, 0, '{}', 1, 0)
                        """)
                .params(seq, CommandDraft.CommandType.NEW.name(), user, key, id).update();
        MockHttpServletResponse r = perform(api, delete("/api/v1/orders/by-client/" + key)
                .header("X-Exchange-Market", "KRW-ETH"));
        assertEquals(409, r.getStatus(), r.getContentAsString());
        assertEquals("order_in_progress", error(r).path("name").asText());
    }

    @Test
    void cancelAllRefusesAHintWithoutTheSameMarketQuery() throws Exception {
        HintedOrderApi api = new HintedOrderApi(jdbc, markets, user);
        MockHttpServletResponse noMarket = perform(api, delete("/api/v1/orders").header("X-Exchange-Market", "KRW-BTC"));
        assertMismatch(noMarket, "마켓 없는 일괄 취소");
        assertNull(noMarket.getHeader("X-RateLimit-Remaining-account"), "계정 토큰 차감 뒤에 거절했다");
        assertMismatch(perform(api, delete("/api/v1/orders").param("market", "KRW-BTC")
                .header("X-Exchange-Market", "KRW-ETH")), "다른 마켓");
        MockHttpServletResponse ok = perform(api, delete("/api/v1/orders").param("market", "KRW-BTC")
                .header("X-Exchange-Market", "KRW-BTC"));
        assertEquals(200, ok.getStatus(), ok.getContentAsString());
    }

    @Test
    void twoHintHeadersAre400EvenWithoutThePrecheck() throws Exception {
        HintedOrderApi api = new HintedOrderApi(jdbc, markets, user);
        MockHttpServletResponse r = perform(api, post("/api/v1/orders").header("X-Exchange-Market", "KRW-BTC")
                .header("X-Exchange-Market", "KRW-ETH").contentType(MediaType.APPLICATION_JSON)
                .content(body("KRW-BTC", key())));
        assertEquals(400, r.getStatus());
        assertEquals("market_hint_duplicate", error(r).path("detail").path("constraint").asText());
        assertNull(r.getHeader("X-RateLimit-Remaining-account"));
    }

    private static void assertMismatch(MockHttpServletResponse r, String scene) throws Exception {
        assertEquals(400, r.getStatus(), scene + ": " + r.getContentAsString());
        JsonNode error = error(r);
        assertEquals("invalid_parameter", error.path("name").asText(), scene);
        assertEquals("market_hint_mismatch", error.path("detail").path("constraint").asText(), scene);
    }

    private static JsonNode error(MockHttpServletResponse r) throws Exception {
        return new ObjectMapper().readTree(r.getContentAsByteArray()).path("error");
    }

    private static MockHttpServletResponse perform(HintedOrderApi api, RequestBuilder request) throws Exception {
        return api.mvc.perform(request).andReturn().getResponse();
    }

    private static String body(String market, String key) {
        return "{\"market\":\"" + market + "\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"1\",\"client_order_id\":\"" + key + "\"}";
    }

    private static String key() {
        return "hint-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long keyRows(String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(user, key).query(Long.class).single();
    }

    private static UUID order(String status) {
        UUID external = UUID.randomUUID();
        long id = jdbc.sql("SELECT nextval('order_id_seq')").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                            price, quantity, remaining_qty, status)
                        VALUES (?, ?, ?, 1, ?, 'BUY', 'LIMIT', 50000, 1, ?, ?)
                        """)
                .params(id, external, user, key(), "OPEN".equals(status) ? 1 : 0, status)
                .update();
        return external;
    }

    private static String status(UUID order) {
        return jdbc.sql("SELECT status FROM orders WHERE external_id = ?").param(order).query(String.class).single();
    }
}

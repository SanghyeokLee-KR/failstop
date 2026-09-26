package kr.exchange.api.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.AcceptGate;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.TestPartitions;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 소유권 선검사 필터(API 설계 §2-5-d, S5 설계 4-1·6-2). DB 없이 게이트를 고를 수 있는 가짜 게이트와 스냅샷 카탈로그로 본다.
 *
 * 소유하지 않는 마켓의 주문 쓰기는 503 + Retry-After: 1 + detail.route=not_owner + X-Exchange-Route: not-owner 이고 체인을
 * 부르지 않는다. 마켓은 서명된 값(생성·배치의 바디, 일괄 취소의 쿼리)을 먼저 보고, 없거나 카탈로그에 없는 값이면 힌트를 본다.
 * 카탈로그와 파티션 대응은 메모리만 읽어 모의 JdbcClient 호출이 0 이다.
 */
class MarketRoutingFilterTest {

    private static final int BTC = 1;
    private static final int ETH = 2;
    private static final int XRP = 3;

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcClient catalogJdbc;
    private MarketDirectory markets;
    private final Map<Integer, Boolean> owned = new HashMap<>();
    private RoutingCounters counters;
    private MarketRoutingFilter filter;

    @BeforeEach
    void setUp() {
        catalogJdbc = mock(JdbcClient.class);
        markets = MarketDirectory.ofSnapshot(catalogJdbc, List.of(market(BTC, "KRW-BTC"), market(ETH, "KRW-ETH"),
                market(XRP, "KRW-XRP")));
        // P3 흉내: 이 노드는 BTC 파티션을 쥐고 ETH 파티션은 쥐지 않는다. XRP 는 대응을 모른다.
        owned.put(BTC, true);
        owned.put(ETH, false);
        counters = RoutingCounters.untracked();
        filter = new MarketRoutingFilter(new ErrorRenderer(mapper), markets, bridge(), mapper, counters);
    }

    @Test
    void hintedSingleCancelIsJudgedByTheHint() throws Exception {
        Result ownedHint = run(delete("/api/v1/orders/9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b", "KRW-BTC"));
        ownedHint.assertPassed();
        Result foreignHint = run(delete("/api/v1/orders/by-client/c-1", "KRW-ETH"));
        foreignHint.assertNotOwner();
        assertEquals(1, counters.precheckCount(RoutingCounters.ACCEPTED));
        assertEquals(1, counters.precheckCount(RoutingCounters.NOT_OWNER));
        verifyNoInteractions(catalogJdbc);
    }

    @Test
    void duplicateHintHeadersAre400BeforeAnythingElse() throws Exception {
        MockHttpServletRequest twice = post("/api/v1/orders", body("KRW-BTC"), "KRW-BTC");
        twice.addHeader("X-Exchange-Market", "KRW-ETH");
        Result duplicate = run(twice);
        duplicate.assertStatus(400);
        assertEquals("market_hint_duplicate", duplicate.json().path("error").path("detail").path("constraint").asText());
        assertEquals(1, counters.hintRejectedCount(RoutingCounters.HINT_DUPLICATE));
        // 쉼표로 이어 붙인 한 줄도 여러 마켓이다(HTTP 는 같은 이름 헤더를 쉼표로 이은 한 줄과 같게 본다).
        run(post("/api/v1/orders", body("KRW-BTC"), "KRW-BTC, KRW-ETH")).assertStatus(400);
        assertEquals(2, counters.hintRejectedCount(RoutingCounters.HINT_DUPLICATE));
        assertEquals(0, counters.precheckCount(RoutingCounters.NOT_OWNER));
    }

    @Test
    void createIsJudgedByTheSignedBodyMarket() throws Exception {
        run(post("/api/v1/orders", body("KRW-ETH"), null)).assertNotOwner();
        run(post("/api/v1/orders", body("KRW-BTC"), null)).assertPassed();
        // 같은 이름이 여럿이면 컨트롤러의 트리 파싱처럼 마지막 값이다.
        run(post("/api/v1/orders", "{\"market\":\"KRW-BTC\",\"market\":\"KRW-ETH\",\"side\":\"bid\"}", null))
                .assertNotOwner();
        verifyNoInteractions(catalogJdbc);
    }

    /** 설계 검토 R4·R11 의 네 사례. 서명된 값을 먼저 본다. */
    @Test
    void signedValueWinsOverTheHint() throws Exception {
        // (a) 첫 원소에 market 이 없고 뒤 원소가 비소유인 배치는 503.
        run(post("/api/v1/orders/batch", "[{\"side\":\"bid\",\"client_order_id\":\"a\"}," + body("KRW-ETH") + "]",
                null)).assertNotOwner();
        // (b) 소문자 힌트(카탈로그에 없음)와 비소유 바디는 503.
        run(post("/api/v1/orders", body("KRW-ETH"), "krw-eth")).assertNotOwner();
        // (c) 소유 힌트와 비소유 바디는 503.
        run(post("/api/v1/orders", body("KRW-ETH"), "KRW-BTC")).assertNotOwner();
        // (d) 비소유 힌트와 소유 바디는 통과. 소유 노드가 인증 뒤 400 market_hint_mismatch 를 낸다.
        run(post("/api/v1/orders", body("KRW-BTC"), "KRW-ETH")).assertPassed();
        assertEquals(3, counters.precheckCount(RoutingCounters.NOT_OWNER));
        verifyNoInteractions(catalogJdbc);
    }

    @Test
    void batchUsesTheFirstElementWithAStringMarket() throws Exception {
        run(post("/api/v1/orders/batch", "[" + body("KRW-ETH") + "," + body("KRW-BTC") + "]", null)).assertNotOwner();
        run(post("/api/v1/orders/batch", "[" + body("KRW-BTC") + "," + body("KRW-ETH") + "]", null)).assertPassed();
        // market 이 문자열이 아닌 원소는 건너뛴다.
        run(post("/api/v1/orders/batch", "[{\"market\":7}," + body("KRW-ETH") + "]", null)).assertNotOwner();
    }

    @Test
    void cancelAllIsJudgedByTheMarketQuery() throws Exception {
        MockHttpServletRequest foreign = delete("/api/v1/orders", null);
        foreign.setParameter("market", "KRW-ETH");
        run(foreign).assertNotOwner();
        MockHttpServletRequest local = delete("/api/v1/orders", null);
        local.setParameter("market", "KRW-BTC");
        run(local).assertPassed();
        // 마켓 없는 일괄 취소는 찾지 못한 것이라 넘긴다. 게이트웨이가 파티션별로 나눈다.
        run(delete("/api/v1/orders", null)).assertPassed();
        assertEquals(1, counters.precheckCount(RoutingCounters.UNRESOLVED));
    }

    @Test
    void unresolvedRequestsPassWithoutTouchingTheDatabase() throws Exception {
        // 힌트 없는 단건 취소는 대상 주문을 읽어야 마켓을 안다. 게이트웨이가 채번 전에 판정한다.
        run(delete("/api/v1/orders/9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b", null)).assertPassed();
        // 카탈로그에 없는 마켓(기동 뒤 생긴 마켓, 오타)은 다시 적재하지 않고 넘긴다.
        run(post("/api/v1/orders", body("KRW-NEW"), null)).assertPassed();
        // 대응을 모르는 마켓(P3 에서 아직 읽지 않음)도 넘긴다.
        run(post("/api/v1/orders", body("KRW-XRP"), null)).assertPassed();
        // 깨진 JSON 은 뒤 단계가 종전처럼 400 을 낸다.
        run(post("/api/v1/orders", "{\"market\":\"KRW-ETH\",", null)).assertPassed();
        run(post("/api/v1/orders/batch", "[" + body("KRW-ETH"), null)).assertPassed();
        assertEquals(5, counters.precheckCount(RoutingCounters.UNRESOLVED));
        assertEquals(0, counters.precheckCount(RoutingCounters.NOT_OWNER));
        verifyNoInteractions(catalogJdbc);
    }

    @Test
    void readsAndOtherPathsAreNotPrechecked() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/orders/open");
        get.addHeader("X-Exchange-Market", "KRW-ETH");
        run(get).assertPassed();
        MockHttpServletRequest options = new MockHttpServletRequest("OPTIONS", "/api/v1/orders");
        options.addHeader("X-Exchange-Market", "KRW-ETH");
        run(options).assertPassed();
        assertEquals(0, counters.precheckCount(RoutingCounters.ACCEPTED) + counters.precheckCount(RoutingCounters.UNRESOLVED)
                + counters.precheckCount(RoutingCounters.NOT_OWNER), "대상이 아닌 요청을 셌다");
    }

    /** 엔진을 끈 노드(관리 평면, 조회 증설)는 수명주기가 없어 게이트가 늘 닫혀 있다. 모든 주문 쓰기를 여기서 돌려보낸다. */
    @Test
    void engineDisabledNodeRefusesEveryKnownMarket() throws Exception {
        EngineBridge disabled = new EngineBridge(null, loop(), new CommandPayloadMapper(mapper),
                TestPartitions.unstarted(PartitionPlan.single()), EngineBridge.NEVER_HALTED);
        MarketRoutingFilter node = new MarketRoutingFilter(new ErrorRenderer(mapper), markets, disabled, mapper,
                counters);
        run(node, post("/api/v1/orders", body("KRW-BTC"), "KRW-BTC")).assertNotOwner();
        run(node, delete("/api/v1/orders/9f2b7a4e-3d1c-4b0e-9a77-5c2f8e1d6a3b", "KRW-ETH")).assertNotOwner();
        verifyNoInteractions(catalogJdbc);
    }

    /** P3 파티션 게이트는 대응을 다시 읽지 않는다. 모르는 마켓은 넘기고 대응 표 조회가 없다. */
    @Test
    void partitionedPlanDoesNotReloadTheMappingBeforeAuthentication() throws Exception {
        JdbcClient planJdbc = mock(JdbcClient.class);
        EngineBridge p3 = new EngineBridge(null, loop(), new CommandPayloadMapper(mapper),
                TestPartitions.unstarted(PartitionPlan.partitioned(planJdbc)), EngineBridge.NEVER_HALTED);
        MarketRoutingFilter node = new MarketRoutingFilter(new ErrorRenderer(mapper), markets, p3, mapper, counters);
        run(node, post("/api/v1/orders", body("KRW-BTC"), "KRW-BTC")).assertPassed();
        assertEquals(1, counters.precheckCount(RoutingCounters.UNRESOLVED));
        verifyNoInteractions(planJdbc, catalogJdbc);
    }

    // ------------------------------------------------------------------ 도우미

    private EngineBridge bridge() {
        AcceptGate gate = new AcceptGate() {
            @Override
            public boolean isAccepting() {
                return true;
            }

            @Override
            public Boolean acceptingIfKnown(int marketId) {
                return owned.get(marketId);
            }
        };
        return new EngineBridge(null, loop(), new CommandPayloadMapper(mapper), gate, EngineBridge.NEVER_HALTED);
    }

    private static SingleWriterLoop loop() {
        return new SingleWriterLoop(16, new MatchingEngineImpl(), events -> { }, (afterSeq, limit) -> List.of(),
                fatal -> { });
    }

    private static MarketDirectory.Market market(int id, String symbol) {
        return new MarketDirectory.Market(id, symbol, "ACTIVE", (short) (id + 1), symbol.substring(4), symbol, 8,
                (short) 1, "KRW", 0, BigDecimal.ONE, new BigDecimal("0.0001"), BigDecimal.ZERO,
                new BigDecimal("1000000000"), new BigDecimal("1000000"), new BigDecimal("0.0005"),
                new BigDecimal("0.0005"));
    }

    private static String body(String market) {
        return "{\"market\":\"" + market + "\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"0.1\",\"client_order_id\":\"c-" + market + "\"}";
    }

    private static MockHttpServletRequest post(String path, String body, String hint) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (hint != null) {
            request.addHeader("X-Exchange-Market", hint);
        }
        return request;
    }

    private static MockHttpServletRequest delete(String path, String hint) {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", path);
        if (hint != null) {
            request.addHeader("X-Exchange-Market", hint);
        }
        return request;
    }

    private Result run(MockHttpServletRequest request) throws Exception {
        return run(filter, request);
    }

    private Result run(MarketRoutingFilter target, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        target.doFilter(request, response, chain);
        return new Result(response, chain);
    }

    private record Result(MockHttpServletResponse response, MockFilterChain chain) {
        void assertPassed() {
            assertNotNull(chain.getRequest(), "체인을 부르지 않았다: status=" + response.getStatus());
        }

        void assertStatus(int status) {
            assertEquals(status, response.getStatus());
            assertNull(chain.getRequest(), "거절했는데 체인을 불렀다");
        }

        void assertNotOwner() throws Exception {
            assertStatus(503);
            assertEquals("1", response.getHeader("Retry-After"));
            assertEquals("not-owner", response.getHeader("X-Exchange-Route"));
            JsonNode error = json().path("error");
            assertEquals("service_unavailable", error.path("name").asText());
            assertEquals("not_owner", error.path("detail").path("route").asText());
            assertEquals(1, error.path("retry_after").asInt());
            assertNull(response.getHeader("X-RateLimit-Remaining-account"), "레이트리밋을 탔다");
        }

        JsonNode json() throws Exception {
            return new ObjectMapper().readTree(response.getContentAsByteArray());
        }
    }
}

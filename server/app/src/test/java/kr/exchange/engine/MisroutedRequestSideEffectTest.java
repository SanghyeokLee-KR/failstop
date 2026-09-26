package kr.exchange.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.TestSigner;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.ApiKeyDirectory;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.auth.NonceStore;
import kr.exchange.api.auth.SecretCipher;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderGatewayTestAccess;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.DbReadRetry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 잘못 간 주문 쓰기는 아무 상태도 건드리지 않는다(S5 관문, API 설계 §2-5-d, S5 설계 4-1·6-2).
 *
 * 운영 순서의 필터 체인(바디 상한, 선검사, 수용 제한, 공개 버킷, 실제 인증·nonce 저장소)과 실제 컨트롤러·게이트웨이, 실제 DB 와
 * P2 노드다. 그 마켓 게이트를 닫고 같은 nonce 의 서명 요청을 두 번 보낸 뒤 게이트를 열고 같은 요청을 한 번 더 보낸다. 닫힌 동안은
 * 두 번 모두 503 이고 레이트리밋 헤더가 없으며 수용 슬롯·수요·키·예약·저널·채번이 0 이다. 연 뒤의 같은 요청이 201 이면 nonce 가
 * 등록되지 않은 것이고, 그 응답의 계정 잔량이 한도에서 한 번만 빠졌으면 닫힌 동안 토큰을 쓰지 않은 것이다.
 */
class MisroutedRequestSideEffectTest {

    private static final String ENGINE = "matching-engine";
    private static final long WINDOW = 60_000;

    @Test
    void misroutedWritesLeaveNoTraceAndTheSameSignedRequestLaterSucceeds() throws Exception {
        run(false);
    }

    private void run(boolean precheckAfterAuth) throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_misrouted", 2);
             PartitionNode node = new PartitionNode("owner", f, new PartitionNode.Options().protocol(2))) {
            node.start();
            awaitAccepting(node, ENGINE);
            MarketDirectory markets = new MarketDirectory(f.app);
            markets.start();
            try {
                long user = f.user("misrouted", new BigDecimal("100000000"));
                SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
                DemoKeySeeder.IssuedKey key = new DemoKeySeeder(f.owner, cipher).issue(user, "TRADE", "s5-misrouted");
                ApiAuthService auth = new ApiAuthService(new ApiKeyDirectory(f.app), cipher, new NonceStore(),
                        new ObjectMapper(), null, null);
                GatedBridge gated = new GatedBridge(node.bridge);
                OrderGateway gateway = new ApiConfig().orderGateway(f.app, gated, markets, new OrderQueryService(f.app),
                        new SimpleMeterRegistry(), new LeaderProtocolBoot(2), node.plan);
                gateway.attachRouting(RoutingCounters.untracked(), DbReadRetry.untracked());
                RoutedChain chain = new RoutedChain(f.app, markets, gated, gateway, auth, true, precheckAfterAuth);

                // 주문 생성.
                String coid = "misrouted-create";
                byte[] create = body("KRW-BTC", coid).getBytes(StandardCharsets.UTF_8);
                String createBearer = TestSigner.bearerForBody(key.accessKey(), key.secretKey(), "POST",
                        "/api/v1/orders", create, "nonce-create-" + UUID.randomUUID(), WINDOW);
                assertRefusedTwiceThenAccepted(f, chain, gated, gateway, user, List.of(coid),
                        () -> post("/api/v1/orders").header("Authorization", createBearer)
                                .contentType(MediaType.APPLICATION_JSON).content(create), 201,
                        RoutedChain.ORDER_CAPACITY - 5);

                // 보통 모양의 배치와 첫 원소에 market 이 없는 배치(R4). 뒤의 것은 컨트롤러가 첫 원소만 rejected 로 적는다.
                String b1 = "misrouted-batch-1";
                byte[] batch = ("[" + body("KRW-BTC", b1) + "]").getBytes(StandardCharsets.UTF_8);
                String batchBearer = TestSigner.bearerForBody(key.accessKey(), key.secretKey(), "POST",
                        "/api/v1/orders/batch", batch, "nonce-batch-" + UUID.randomUUID(), WINDOW);
                assertRefusedTwiceThenAccepted(f, chain, gated, gateway, user, List.of(b1),
                        () -> post("/api/v1/orders/batch").header("Authorization", batchBearer)
                                .contentType(MediaType.APPLICATION_JSON).content(batch), 200, null);
                String b2 = "misrouted-batch-2";
                byte[] headless = ("[{\"side\":\"bid\",\"client_order_id\":\"misrouted-batch-x\"},"
                        + body("KRW-BTC", b2) + "]").getBytes(StandardCharsets.UTF_8);
                String headlessBearer = TestSigner.bearerForBody(key.accessKey(), key.secretKey(), "POST",
                        "/api/v1/orders/batch", headless, "nonce-headless-" + UUID.randomUUID(), WINDOW);
                assertRefusedTwiceThenAccepted(f, chain, gated, gateway, user, List.of(b2, "misrouted-batch-x"),
                        () -> post("/api/v1/orders/batch").header("Authorization", headlessBearer)
                                .contentType(MediaType.APPLICATION_JSON).content(headless), 200, null);

                // 마켓을 준 일괄 취소.
                String cancelBearer = TestSigner.bearerForQuery(key.accessKey(), key.secretKey(), "DELETE",
                        "/api/v1/orders", Map.of("market", "KRW-BTC"), "nonce-cancel-" + UUID.randomUUID(), WINDOW);
                assertRefusedTwiceThenAccepted(f, chain, gated, gateway, user, List.of(),
                        () -> delete("/api/v1/orders").param("market", "KRW-BTC")
                                .header("Authorization", cancelBearer), 200, null);
            } finally {
                markets.close();
            }
        }
    }

    private interface Request {
        MockHttpServletRequestBuilder build();
    }

    private static void assertRefusedTwiceThenAccepted(ReservationFixture f, RoutedChain chain, GatedBridge gated,
                                                       OrderGateway gateway, long user, List<String> keys,
                                                       Request request, int acceptedStatus,
                                                       Integer expectedRemaining) throws Exception {
        long journalBefore = f.count("SELECT count(*) FROM command_journal");
        long reservationsBefore = f.count("SELECT count(*) FROM order_reservations");
        long assignmentsBefore = OrderGatewayTestAccess.orderIdAssignments(gateway);
        int reachedBefore = chain.admissionReached.get();
        long attemptsBefore = chain.progress.attempts();
        double notOwnerBefore = chain.counters.precheckCount(RoutingCounters.NOT_OWNER);
        gated.open = false;
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse refused = chain.mvc.perform(request.build()).andReturn().getResponse();
            assertEquals(503, refused.getStatus(), "닫힌 동안 " + (i + 1) + "번째: " + refused.getContentAsString());
            assertEquals("not-owner", refused.getHeader("X-Exchange-Route"));
            assertEquals("1", refused.getHeader("Retry-After"));
            JsonNode error = new ObjectMapper().readTree(refused.getContentAsByteArray()).path("error");
            assertEquals("not_owner", error.path("detail").path("route").asText());
            assertNull(refused.getHeader("X-RateLimit-Remaining-account"), "레이트리밋 토큰을 썼다");
        }
        assertEquals(reachedBefore, chain.admissionReached.get(), "수용 슬롯을 썼다");
        assertEquals(attemptsBefore, chain.progress.attempts(), "좀비 판정 수요로 셌다");
        assertEquals(notOwnerBefore + 2, chain.counters.precheckCount(RoutingCounters.NOT_OWNER));
        for (String key : keys) {
            assertEquals(0, f.count("SELECT count(*) FROM idempotency_keys WHERE user_id = " + user
                    + " AND idem_key = '" + key + "'"), "멱등 키를 남겼다: " + key);
        }
        assertEquals(journalBefore, f.count("SELECT count(*) FROM command_journal"), "저널에 닿았다");
        assertEquals(reservationsBefore, f.count("SELECT count(*) FROM order_reservations"), "예약을 남겼다");
        assertEquals(assignmentsBefore, OrderGatewayTestAccess.orderIdAssignments(gateway), "주문 id 를 채번했다");

        gated.open = true;
        MockHttpServletResponse accepted = chain.mvc.perform(request.build()).andReturn().getResponse();
        assertEquals(acceptedStatus, accepted.getStatus(), "연 뒤의 같은 요청: " + accepted.getContentAsString());
        if (expectedRemaining != null) {
            // 새 계정 버킷의 첫 차감이다. 닫힌 동안의 두 번이 토큰을 썼다면 한도에서 세 번 빠진 값이 된다.
            assertEquals(String.valueOf(expectedRemaining), accepted.getHeader("X-RateLimit-Remaining-account"));
        }
    }

    /** 실제 노드의 브리지 앞에서 선검사와 게이트웨이의 게이트만 시험이 닫는다. 접수는 실제 노드로 간다. */
    static final class GatedBridge extends EngineBridge {
        private final EngineBridge real;
        volatile boolean open = true;

        GatedBridge(EngineBridge real) {
            super(null, new SingleWriterLoop(16, new MatchingEngineImpl(), events -> { }, (afterSeq, limit) -> List.of(),
                    fatal -> { }), new CommandPayloadMapper(new ObjectMapper()), AcceptGate.ALWAYS_OPEN,
                    EngineBridge.NEVER_HALTED);
            this.real = real;
        }

        @Override
        public Boolean acceptingIfKnown(int marketId) {
            return open ? real.acceptingIfKnown(marketId) : Boolean.FALSE;
        }

        @Override
        public boolean accepting(int marketId) {
            return open && real.accepting(marketId);
        }

        @Override
        public SubmitResult submit(OrderIntake intake) {
            return real.submit(intake);
        }

        @Override
        public void requestCatchUp(int marketId, long seq) {
            real.requestCatchUp(marketId, seq);
        }
    }

    private static String body(String market, String coid) {
        return "{\"market\":\"" + market + "\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"0.1\",\"client_order_id\":\"" + coid + "\"}";
    }
}

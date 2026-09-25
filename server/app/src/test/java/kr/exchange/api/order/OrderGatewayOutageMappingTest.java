package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderGatewayReserveMappingTest.FakeBridge;
import kr.exchange.engine.NotLeaderException;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.DbFailures;
import kr.exchange.journal.StaleLeaderException;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DB 연결 장애와 리더 자격 거부로 끝난 주문 생성·취소의 응답(API 설계 §2-5-c, S4b 설계 4-3·6-2). 브리지는 가짜라 저널에 닿지
 * 않고, 멱등 키·V47 판정·계정·주문 행은 실제 DB 다. V47 이 DB 에 닿지 못하는 경우는 판정 이음매로 넣는다. 함수 이름을 바꿔
 * V47 을 끄면 42883 이라 DB 에 닿은 판정 실패가 되기 때문이다.
 *
 * 쓰기 업스트림에서 이 노드를 빼는 503 은 판정이나 재조회마저 DB 에 닿지 못했거나 그 마켓의 게이트가 닫혔을 때뿐이다.
 */
class OrderGatewayOutageMappingTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final String DB_UNREACHABLE = "DB 연결 장애. 재조회 후 판단 필요";
    private static final String FAILOVER = "페일오버 진행 중. 재조회 후 판단 필요";

    static PartitionLeaseDb db;
    static JdbcClient jdbc;
    static MarketDirectory markets;
    static AccountBootstrap bootstrap;
    static long rich;
    static final AtomicInteger KEYS = new AtomicInteger();

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("gw_outage_map");
        jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER));
        bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        rich = user("rich");
        markets = new MarketDirectory(jdbc);
        markets.start();
    }

    @AfterAll
    static void close() {
        if (markets != null) {
            markets.close();
        }
    }

    // ------------------------------------------------------------------ 주문 생성, DB 연결 장애

    /** V47 마저 커넥션 획득에 실패하면 DB 에 닿지 못한 것이라 503 이고 키를 남긴다. 획득 실패는 다시 부르지 않는다. */
    @Test
    void verdictUnreachableIs503AndKeepsTheKey() throws Exception {
        OrderGateway gateway = p2(throwing(() -> DbFailures.rollbackFailed(DbFailures.connectionFailure())));
        AtomicInteger v47 = OrderGatewayTestAccess.interceptResolutions(gateway, call -> DbFailures.acquireFailure());
        String key = key();

        ApiException e = assertThrows(ApiException.class, () -> place(gateway, rich, key));

        assertUnreachable(e);
        assertEquals(1, keyRows(rich, key), "결과를 모르는 접수의 키를 지웠다");
        assertEquals(1, gateway.unresolvedFailures());
        assertEquals(1, gateway.intakeUnavailable("connection", "503"));
        assertEquals(1, v47.get(), "획득 실패로 끝난 판정을 다시 불렀다");
    }

    /** V47 이 DB 에 닿았으나 판정하지 못했으면(잠금 대기 초과, 문장 시간 초과) 409 이고 리더를 빼는 503 이 아니다. */
    @Test
    void verdictReachedButFailedIs409() throws Exception {
        for (String state : new String[] {"55P03", "57014"}) {
            OrderGateway gateway = p2(throwing(() -> DbFailures.rollbackFailed(DbFailures.connectionFailure())));
            AtomicInteger v47 = OrderGatewayTestAccess.interceptResolutions(gateway, call -> DbFailures.sql(state));
            String key = key();

            ApiException e = assertThrows(ApiException.class, () -> place(gateway, rich, key));

            assertOrderInProgress(e, state);
            assertEquals(ApiException.DbOutage.NONE, e.dbOutage(), state);
            assertEquals(1, keyRows(rich, key), state + " 판정 실패인데 키를 지웠다");
            assertEquals(1, gateway.intakeUnavailable("connection", "409"), state);
            assertEquals(0, gateway.intakeUnavailable("connection", "503"), state + " DB 에 닿은 판정 실패로 503 을 냈다");
            assertEquals(1, v47.get(), state);
        }
    }

    /**
     * V47 의 연결이 끊겼으면 다른 연결로 한 번 더 부르되 키를 지우지 않는다. 첫 판정이 키를 지웠는지 모르므로 저널에 없다는 답도
     * 확정 미접수가 아닌 409 다. 이 장면은 첫 판정이 DB 에 가지 않아 키가 고아로 남는다. 셋째는 없다.
     */
    @Test
    void verdictIsCalledOnceMoreAfterALostConnection() throws Exception {
        OrderGateway gateway = p2(throwing(DbFailures::connectionFailure));
        AtomicInteger v47 = OrderGatewayTestAccess.interceptResolutions(gateway,
                call -> call == 1 ? DbFailures.connectionFailure() : null);
        String key = key();

        assertOrderInProgress(assertThrows(ApiException.class, () -> place(gateway, rich, key)), "끊긴 판정을 다시 봄");
        assertEquals(2, v47.get(), "끊긴 연결로 끝난 판정을 다시 부르지 않았다");
        assertEquals(1, gateway.intakeUnavailable("connection", "409"));
        assertEquals(0, gateway.intakeUnavailable("connection", "429"), "다시 본 판정으로 확정 미접수를 냈다");
        assertEquals(0, gateway.intakeUnavailable("connection", "503"));
        assertEquals(1, keyRows(rich, key), "다시 부른 판정이 키를 지웠다");

        OrderGateway twice = p2(throwing(DbFailures::connectionFailure));
        AtomicInteger calls = OrderGatewayTestAccess.interceptResolutions(twice, call -> DbFailures.connectionFailure());
        assertUnreachable(assertThrows(ApiException.class, () -> place(twice, rich, key())));
        assertEquals(2, calls.get(), "판정을 두 번보다 많이 불렀다");
    }

    /**
     * 판정된 연결 장애는 마켓 접수 경합과 같은 규칙이다. V47 이 이 요청의 키를 지웠으면 429, 공유 표시로 남겼으면 409, 이미 접수된
     * 주문의 재전송이면 409 다. 경합 계수에는 세지 않고 좀비 판정 수요에서 빼지 않는다.
     */
    @Test
    void resolvedConnectionFailuresFollowTheContentionRules() throws Exception {
        OrderGateway discarded = p2(throwing(() -> DbFailures.rollbackFailed(null)));
        String dropped = key();
        assertMarket429(assertThrows(ApiException.class, () -> place(discarded, rich, dropped)), "DISCARDED");
        assertEquals(0, keyRows(rich, dropped));
        assertEquals(1, discarded.intakeUnavailable("connection", "429"));
        assertNoContention(discarded);

        String kept = key();
        OrderGateway keptGateway = p2(new FakeBridge(intake -> {
            jdbc.sql("UPDATE idempotency_keys SET shared_at = now() WHERE user_id = ? AND idem_key = ?")
                    .params(rich, kept).update();
            throw DbFailures.connectionFailure();
        }));
        assertOrderInProgress(assertThrows(ApiException.class, () -> place(keptGateway, rich, kept)), "KEPT");
        assertEquals(1, keyRows(rich, kept), "공유 표시가 있는 키를 지웠다");
        assertEquals(1, keptGateway.intakeUnavailable("connection", "409"));
        assertNoContention(keptGateway);

        String replay = journaledKey(rich);
        OrderGateway replayGateway = p2(throwing(DbFailures::connectionFailure));
        AtomicInteger v47 = OrderGatewayTestAccess.interceptResolutions(replayGateway, call -> null);
        assertOrderInProgress(assertThrows(ApiException.class, () -> place(replayGateway, rich, replay)), "재전송");
        assertEquals(0, v47.get(), "키를 잡지 않은 재전송이 판정을 불렀다");
        assertEquals(1, replayGateway.intakeUnavailable("connection", "409"));
    }

    // ------------------------------------------------------------------ 주문 생성, 리더 자격 거부

    /** 게이트가 닫힌 노드(재확인이 거짓이라 강등)의 리더 자격 거부는 V47 결과와 무관하게 페일오버 503 이다. 키는 V47 이 정리한다. */
    @Test
    void leaderRejectionWithTheGateClosedIsFailover503() throws Exception {
        for (Map.Entry<String, Supplier<RuntimeException>> rejection : rejections().entrySet()) {
            FakeBridge bridge = throwing(rejection.getValue());
            bridge.gateOpen = false;
            OrderGateway gateway = p2(bridge);
            String key = key();

            ApiException e = assertThrows(ApiException.class, () -> place(gateway, rich, key));

            assertFailover(e, rejection.getKey());
            assertEquals(0, keyRows(rich, key), rejection.getKey() + " 뒤 V47 이 키를 정리하지 않았다");
            assertEquals(1, gateway.intakeUnavailable("fencing", "503"), rejection.getKey());

            FakeBridge replayBridge = throwing(rejection.getValue());
            replayBridge.gateOpen = false;
            OrderGateway replayGateway = p2(replayBridge);
            assertFailover(assertThrows(ApiException.class, () -> place(replayGateway, rich, journaledKey(rich))),
                    rejection.getKey() + " 재전송");
        }
    }

    /**
     * 게이트를 연 노드(재확인이 참, 재확인 왕복 실패, 새 기간 재승격)는 리더일 수 있으므로 빼지 않는다. V47 규칙대로 확정 미접수는
     * 429, 공유 표시는 409 이고, V47 마저 DB 에 닿지 못했을 때만 503 이다. 재전송은 409 다.
     */
    @Test
    void leaderRejectionWithTheGateOpenFollowsTheVerdict() throws Exception {
        for (Map.Entry<String, Supplier<RuntimeException>> rejection : rejections().entrySet()) {
            String name = rejection.getKey();
            OrderGateway discarded = p2(throwing(rejection.getValue()));
            assertMarket429(assertThrows(ApiException.class, () -> place(discarded, rich, key())), name);
            assertEquals(1, discarded.intakeUnavailable("fencing", "429"), name);
            assertEquals(0, discarded.intakeUnavailable("fencing", "503"), name + " 게이트를 연 노드가 503 을 냈다");

            String kept = key();
            OrderGateway keptGateway = p2(new FakeBridge(intake -> {
                jdbc.sql("UPDATE idempotency_keys SET shared_at = now() WHERE user_id = ? AND idem_key = ?")
                        .params(rich, kept).update();
                throw rejection.getValue().get();
            }));
            assertOrderInProgress(assertThrows(ApiException.class, () -> place(keptGateway, rich, kept)), name);
            assertEquals(1, keptGateway.intakeUnavailable("fencing", "409"), name);

            OrderGateway unreachable = p2(throwing(rejection.getValue()));
            OrderGatewayTestAccess.interceptResolutions(unreachable, call -> DbFailures.acquireFailure());
            assertUnreachable(assertThrows(ApiException.class, () -> place(unreachable, rich, key())));
            assertEquals(1, unreachable.intakeUnavailable("connection", "503"), name);

            OrderGateway replay = p2(throwing(rejection.getValue()));
            assertOrderInProgress(assertThrows(ApiException.class, () -> place(replay, rich, journaledKey(rich))),
                    name + " 재전송");
            assertEquals(1, replay.intakeUnavailable("fencing", "409"), name);
        }
    }

    // ------------------------------------------------------------------ 취소

    /**
     * 취소 append 의 DB 연결 장애는 대상 주문을 다시 읽어 DB 도달 여부를 본다. 읽혔으면 409, 연결이 끊겼으면 한 번 더 읽고, 읽기마저
     * DB 에 닿지 못했으면 503 이다.
     */
    @Test
    void cancelOutageRereadsTheTargetOrder() throws Exception {
        String[] expected = {"409", "409", "503"};
        List<IntFunction<RuntimeException>> rereads = List.of(read -> null,
                read -> read == 1 ? DbFailures.connectionFailure() : null, read -> DbFailures.acquireFailure());
        int[] reads = {1, 2, 1};
        for (int i = 0; i < 3; i++) {
            long user = user("cancel-" + i);
            UUID order = openOrder(user);
            FlakyOrders orders = new FlakyOrders(jdbc);
            orders.readFailure = rereads.get(i);
            OrderGateway gateway = p2(throwing(() -> DbFailures.rollbackFailed(DbFailures.connectionFailure())), orders);

            ApiException e = assertThrows(ApiException.class, () -> gateway.cancel(user, order));

            if ("503".equals(expected[i])) {
                assertUnreachable(e);
            } else {
                assertEquals(409, e.status(), "재조회 장면 " + i);
                assertEquals("order_in_progress", e.name());
                assertEquals("취소 처리 중. 재조회 필요", e.getMessage());
                assertEquals(1, e.retryAfter());
                assertEquals(ApiException.DbOutage.REACHED, e.dbOutage());
            }
            assertEquals(reads[i], orders.internalReads.get(), "재조회 장면 " + i + " 의 읽기 수");
            assertEquals(1, gateway.intakeUnavailable("connection", expected[i]), "재조회 장면 " + i);
        }
    }

    /** 취소의 리더 자격 거부는 롤백이 확정이다. 게이트가 닫혔으면 페일오버 503, 열렸으면 확정 미접수 429 다. */
    @Test
    void cancelLeaderRejectionUsesTheGate() throws Exception {
        long user = user("cancel-fencing");
        FakeBridge closed = throwing(DbFailures::fencing);
        closed.gateOpen = false;
        OrderGateway closedGateway = p2(closed);
        assertFailover(assertThrows(ApiException.class, () -> closedGateway.cancel(user, openOrder(user))), "닫힘");
        assertEquals(1, closedGateway.intakeUnavailable("fencing", "503"));

        OrderGateway openGateway = p2(throwing(DbFailures::fencing));
        assertMarket429(assertThrows(ApiException.class, () -> openGateway.cancel(user, openOrder(user))), "열림");
        assertEquals(1, openGateway.intakeUnavailable("fencing", "429"));
    }

    /**
     * 일괄 취소는 DB 연결 장애로 끝난 취소를 단건 응답과 무관하게 service_unavailable 로 적고 미완료로 센다. 재조회마저 DB 에 닿지
     * 못했으면 남은 주문은 시도하지 않는다. 게이트를 연 노드의 리더 자격 거부는 too_many_requests 로 미완료다.
     */
    @Test
    void bulkCancelCountsOutagesAsIncompleteAndStopsWhenUnreachable() throws Exception {
        // 재조회 성공: 둘째는 미완료, 셋째는 시도해 취소된다.
        long reached = user("bulk-reached");
        List<UUID> reachedOrders = List.of(openOrder(reached), openOrder(reached), openOrder(reached));
        FakeBridge reachedBridge = cancelling(reachedOrders.get(1),
                () -> DbFailures.rollbackFailed(DbFailures.connectionFailure()));
        OrderGateway.BulkCancelResult first = p2(reachedBridge).cancelAll(reached, null);
        assertEquals(List.of(reachedOrders.get(0).toString(), reachedOrders.get(2).toString()), first.canceledIds());
        assertEquals(List.of("service_unavailable"), reasons(first));
        assertEquals(3, reachedBridge.calls.get());
        assertEquals(1, first.unsubmitted());
        assertEquals(1, first.incomplete(), "재조회가 DB 에 닿은 409 를 저널 뒤 반영 대기로 보고 미완료에서 뺐다");

        // 재조회 실패: 둘째와 셋째가 미완료이고 셋째는 시도하지 않는다.
        long unreachable = user("bulk-unreachable");
        List<UUID> unreachableOrders = List.of(openOrder(unreachable), openOrder(unreachable), openOrder(unreachable));
        FakeBridge unreachableBridge = cancelling(unreachableOrders.get(1),
                () -> DbFailures.rollbackFailed(DbFailures.connectionFailure()));
        FlakyOrders flaky = new FlakyOrders(jdbc);
        // 첫째 취소의 반영 확인이 첫 읽기, 둘째 취소의 재조회가 둘째 읽기다.
        flaky.readFailure = read -> read == 2 ? DbFailures.acquireFailure() : null;
        OrderGateway.BulkCancelResult second = p2(unreachableBridge, flaky).cancelAll(unreachable, null);
        assertEquals(List.of(unreachableOrders.get(0).toString()), second.canceledIds());
        assertEquals(List.of("service_unavailable", "service_unavailable"), reasons(second));
        assertEquals(2, unreachableBridge.calls.get(), "DB 에 닿지 못한 뒤 남은 주문을 시도했다");
        assertEquals(2, second.unsubmitted());
        assertEquals(2, second.incomplete());

        // 셋째가 게이트를 연 노드의 리더 자격 거부다.
        long fencing = user("bulk-fencing");
        List<UUID> fencingOrders = List.of(openOrder(fencing), openOrder(fencing), openOrder(fencing));
        FakeBridge fencingBridge = new FakeBridge(intake -> {
            if (intake.assignedOrderId() == orderId(fencingOrders.get(1))) {
                throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
            }
            if (intake.assignedOrderId() == orderId(fencingOrders.get(2))) {
                throw DbFailures.fencing();
            }
            return cancel(intake.assignedOrderId());
        });
        OrderGateway.BulkCancelResult third = p2(fencingBridge).cancelAll(fencing, null);
        assertEquals(List.of("service_unavailable", "too_many_requests"), reasons(third));
        assertEquals(1, third.unsubmitted());
        assertEquals(1, third.contended());
        assertEquals(2, third.incomplete());
    }

    // ------------------------------------------------------------------ P1 in-flight 예약

    /**
     * P1 은 V47 이 판정하지 못하면(DB 에 닿았든 닿지 못했든) in-flight 예약을 바로 풀지 않고 지연 회수한다. 커밋됐다면 그 명령이
     * 정산될 때까지 예약이 필요하다. 확정 미접수(DISCARDED)면 곧바로 푼다.
     */
    @Test
    void p1DefersTheInflightReserveUnlessTheVerdictConfirmsNonAcceptance() throws Exception {
        OrderGateway unreachable = p1(throwing(() -> DbFailures.rollbackFailed(DbFailures.connectionFailure())));
        OrderGatewayTestAccess.interceptResolutions(unreachable, call -> DbFailures.acquireFailure());
        assertUnreachable(assertThrows(ApiException.class, () -> place(unreachable, rich, key())));
        assertEquals(0, new BigDecimal("50000").compareTo(unreachable.inflightPending(rich, (short) KRW)),
                "DB 에 닿지 못한 판정 뒤 예약을 바로 풀었다");

        OrderGateway timedOut = p1(throwing(() -> DbFailures.rollbackFailed(DbFailures.connectionFailure())));
        OrderGatewayTestAccess.interceptResolutions(timedOut, call -> DbFailures.sql("55P03"));
        assertOrderInProgress(assertThrows(ApiException.class, () -> place(timedOut, rich, key())), "P1 55P03");
        assertEquals(0, new BigDecimal("50000").compareTo(timedOut.inflightPending(rich, (short) KRW)),
                "DB 에 닿았으나 판정하지 못한 뒤 예약을 바로 풀었다");

        OrderGateway discarded = p1(throwing(() -> DbFailures.rollbackFailed(DbFailures.connectionFailure())));
        assertMarket429(assertThrows(ApiException.class, () -> place(discarded, rich, key())), "P1 DISCARDED");
        assertEquals(0, discarded.inflightPending(rich, (short) KRW).signum(), "확정 미접수의 예약이 남았다");
    }

    // ------------------------------------------------------------------ 도우미

    private static Map<String, Supplier<RuntimeException>> rejections() {
        return Map.of("P0001", DbFailures::fencing,
                "삽입 0행", () -> new StaleLeaderException("stale 리더 append 거부: holder=node, epoch=3"),
                "기간 종료", () -> new NotLeaderException("기간 종료: resource=matching-engine, epoch=3"));
    }

    private static FakeBridge throwing(Supplier<? extends RuntimeException> failure) {
        return new FakeBridge(intake -> {
            throw failure.get();
        });
    }

    /** 대상 주문의 취소만 failure 로 끝나고 나머지 취소는 주문 행을 CANCELED 로 바꾼다. */
    private static FakeBridge cancelling(UUID failing, Supplier<? extends RuntimeException> failure) {
        long failingId = orderId(failing);
        return new FakeBridge(intake -> {
            if (intake.assignedOrderId() == failingId) {
                throw failure.get();
            }
            return cancel(intake.assignedOrderId());
        });
    }

    private static SubmitResult cancel(long orderId) {
        jdbc.sql("UPDATE orders SET status = 'CANCELED', remaining_qty = 0 WHERE id = ?").params(orderId).update();
        return new SubmitResult(1, 1, false, true);
    }

    private static OrderGateway p2(FakeBridge bridge) {
        return p2(bridge, new OrderQueryService(jdbc));
    }

    private static OrderGateway p2(FakeBridge bridge, OrderQueryService orders) {
        return new OrderGateway(jdbc, bridge, markets, orders, OrderGateway.ReserveMode.DB_RESERVATION);
    }

    private static OrderGateway p1(FakeBridge bridge) {
        return new OrderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc));
    }

    private static void assertUnreachable(ApiException e) {
        assertEquals(503, e.status(), e.name());
        assertEquals("service_unavailable", e.name());
        assertEquals(DB_UNREACHABLE, e.getMessage());
        assertEquals(1, e.retryAfter());
        assertEquals(ApiException.DbOutage.UNREACHABLE, e.dbOutage());
        assertFalse(e.demandExcluded(), "DB 에 닿지 못한 응답을 좀비 판정 수요에서 뺐다");
    }

    private static void assertFailover(ApiException e, String scene) {
        assertEquals(503, e.status(), scene + ": " + e.name());
        assertEquals("service_unavailable", e.name(), scene);
        assertEquals(FAILOVER, e.getMessage(), scene);
        assertEquals(1, e.retryAfter(), scene);
        assertEquals(ApiException.DbOutage.NONE, e.dbOutage(), scene + " 게이트 닫힘의 503 에 조기 종료 표식을 달았다");
        assertFalse(e.demandExcluded(), scene);
    }

    private static void assertMarket429(ApiException e, String scene) {
        assertEquals(429, e.status(), scene + ": " + e.name());
        assertEquals("too_many_requests", e.name(), scene);
        assertEquals(Map.of("bucket", "market", "scope", "order"), e.detail(), scene);
        assertEquals(1, e.retryAfter(), scene);
        assertFalse(e.demandExcluded(), scene + " 경합이 아닌 응답을 좀비 판정 수요에서 뺐다");
    }

    private static void assertOrderInProgress(ApiException e, String scene) {
        assertEquals(409, e.status(), scene + ": " + e.name());
        assertEquals("order_in_progress", e.name(), scene);
        assertEquals(1, e.retryAfter(), scene);
        assertFalse(e.demandExcluded(), scene + " 경합이 아닌 응답을 좀비 판정 수요에서 뺐다");
    }

    private static void assertNoContention(OrderGateway gateway) {
        for (String cause : Set.of("lock_wait", "lock_wait_stuck", "55P03", "57014", "40P01", "40001")) {
            assertEquals(0, gateway.intakeContention(cause, "429") + gateway.intakeContention(cause, "409"),
                    cause + " 경합 계수에 DB 연결 장애를 셌다");
        }
    }

    private static List<String> reasons(OrderGateway.BulkCancelResult result) {
        return result.failed().stream().map(f -> f.reason()).toList();
    }

    private static long user(String name) {
        long id = bootstrap.user(name + "-" + KEYS.incrementAndGet() + "@outage.test");
        bootstrap.userAccounts(id, KRW);
        bootstrap.userAccounts(id, BTC);
        bootstrap.deposit(id, KRW, new BigDecimal("1000000000"));
        return id;
    }

    private static OrderGateway.Placement place(OrderGateway gateway, long user, String key) throws Exception {
        byte[] raw = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"1\",\"client_order_id\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
        return gateway.place(user, new ObjectMapper().readTree(raw), raw);
    }

    private static String key() {
        return "om-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 이 키의 해시와 저널의 NEW 를 둔다. 같은 내용의 요청은 키를 잡지 않은 재전송이 된다. */
    private static String journaledKey(long user) throws Exception {
        String key = key();
        byte[] raw = ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"1\",\"client_order_id\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
        String hash = kr.exchange.api.auth.CanonicalRequests.sha256Hex(kr.exchange.api.auth.CanonicalRequests.jcs(raw));
        jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                .params(user, key, hash).update();
        long seq = jdbc.sql("SELECT COALESCE(max(seq), 0) + 1 FROM command_journal").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                     assigned_order_id, engine_ts, tie_break, payload,
                                                     fencing_token, checksum)
                        VALUES (?, 1, ?, ?, ?, ?, 0, 0, '{}', 1, 0)
                        """)
                .params(seq, CommandDraft.CommandType.NEW.name(), user, key, nextval())
                .update();
        return key;
    }

    /** 미체결 주문 행 하나. 취소 대상이다. */
    private static UUID openOrder(long user) {
        UUID external = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                            price, quantity, remaining_qty, status)
                        VALUES (?, ?, ?, 1, ?, 'BUY', 'LIMIT', 50000, 1, 1, 'OPEN')
                        """)
                .params(nextval(), external, user, key())
                .update();
        return external;
    }

    private static long orderId(UUID external) {
        return jdbc.sql("SELECT id FROM orders WHERE external_id = ?").params(external).query(Long.class).single();
    }

    private static long nextval() {
        return jdbc.sql("SELECT nextval('order_id_seq')").query(Long.class).single();
    }

    private static long keyRows(long user, String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(user, key).query(Long.class).single();
    }

    /** 대상 주문 재조회(byInternalId)의 n 번째 읽기에 실패를 넣는 조회. */
    static final class FlakyOrders extends OrderQueryService {
        final AtomicInteger internalReads = new AtomicInteger();
        volatile IntFunction<RuntimeException> readFailure = read -> null;

        FlakyOrders(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public Optional<OrderRow> byInternalId(long orderId) {
            RuntimeException failure = readFailure.apply(internalReads.incrementAndGet());
            if (failure != null) {
                throw failure;
            }
            return super.byInternalId(orderId);
        }
    }
}

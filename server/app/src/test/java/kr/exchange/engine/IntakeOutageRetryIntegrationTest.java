package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderGatewayTestAccess;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.DbFailures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DB 연결 장애와 리더 자격 거부로 끝난 접수의 응답과 같은 키 재시도(S4b 설계 4-3·6-2, API 설계 §2-5-c). P2 한 노드와 실제
 * 게이트웨이에서 append 앞뒤에 결함을 넣고 V47 판정 이음매에 실패를 넣는다.
 *
 * 결과를 모르는 접수는 키와 예약을 지우지 않으므로 같은 키 재시도가 DB 복구 뒤 올바른 하나로 수렴한다. 커밋됐던 주문은 멱등
 * 재생, 커밋되지 않았던 주문은 새 접수다. 판정이 DB 에 닿았으면 503 이 아니고, 게이트를 연 리더의 fencing 거부는 리더를 빼지
 * 않는다. 연결이 끊긴 판정을 다시 부를 때는 키를 지우지 않아 두 판정 사이에 같은 키로 접수된 주문의 키가 남는다(S4b 설계 14절).
 */
class IntakeOutageRetryIntegrationTest {

    private static final String RESOURCE = "matching-engine";
    private static final String MARKET = "KRW-BTC";

    @Test
    void outageResponsesKeepTheKeyAndTheSameKeyRetryConverges() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_intake_outage", 2);
             PartitionNode node = new PartitionNode("node", f, new PartitionNode.Options().protocol(2))) {
            node.start();
            awaitAccepting(node, RESOURCE);
            MarketDirectory markets = new MarketDirectory(f.app);
            markets.start();
            try {
                OrderGateway gateway = gateway(f.app, node, markets);
                AtomicBoolean v47Down = new AtomicBoolean();
                OrderGatewayTestAccess.interceptResolutions(gateway,
                        call -> v47Down.get() ? DbFailures.acquireFailure() : null);

                commitThenLostResponse(f, node, gateway, v47Down);
                failedBeforeAppend(f, node, gateway, v47Down);
                journalCheckReachedButTimedOut(f, node, markets);
                fencingWithTheGateOpen(f, node, gateway);
                lostVerdictResponseWithASameKeyRequestBetween(f, node, markets);
            } finally {
                markets.close();
            }
        }
    }

    /** (a) append 는 커밋됐는데 롤백 실패를 받고 V47 도 DB 에 닿지 못한다. 503 이고 키·NEW·예약은 하나씩 남는다. */
    private static void commitThenLostResponse(ReservationFixture f, PartitionNode node, OrderGateway gateway,
                                               AtomicBoolean v47Down) throws Exception {
        long user = f.user("outage-a", new BigDecimal("100000000"));
        String key = "outage-a";
        AtomicBoolean lose = new AtomicBoolean(true);
        node.appendAfterFault = (term, draft, result) -> {
            if (key.equals(draft.clientOrderId()) && lose.getAndSet(false)) {
                throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
            }
        };
        v47Down.set(true);
        assertDbUnreachable(place(gateway, user, key), "(a)");
        v47Down.set(false);
        node.appendAfterFault = (term, draft, result) -> { };
        assertEquals(1, keyRows(f, user, key), "(a) 결과를 모르는 접수의 키를 지웠다");
        assertEquals(1, newRows(f, user, key), "(a) 커밋된 NEW 가 없다(장면 전제)");
        long orderId = f.count("SELECT assigned_order_id FROM command_journal WHERE command_type = 'NEW' AND user_id = "
                + user + " AND client_order_id = '" + key + "'");
        assertEquals(1, reservations(f, orderId), "(a) 예약 행");

        // 커밋된 명령은 라이터 큐에 들어가지 않았다. 물질화 전이면 409 이고, 같은 마켓의 다음 명령이 갭을 드러낸 뒤 재생이다.
        Object retry = place(gateway, user, key);
        if (retry instanceof ApiException pending) {
            assertEquals(409, pending.status(), "(a) 재시도: " + pending.name());
            assertEquals("order_in_progress", pending.name());
            assertInstanceOf(OrderGateway.Placement.class, place(gateway, user, "outage-a-next"));
            retry = place(gateway, user, key);
        }
        OrderGateway.Placement replayed = assertInstanceOf(OrderGateway.Placement.class, retry, "(a) 재시도: " + retry);
        assertTrue(replayed.replayed(), "(a) 커밋됐던 주문의 재시도가 멱등 재생이 아니다");
        assertEquals(orderId, replayed.row().id(), "(a) 재생이 다른 주문을 돌려줬다");
        assertEquals(1, newRows(f, user, key), "(a) 재시도가 NEW 를 하나 더 만들었다");
        assertEquals(1, reservations(f, orderId), "(a) 재시도가 예약을 하나 더 만들었다");
    }

    /** (b) append 전에 같은 예외로 끝나고 V47 도 DB 에 닿지 못한다. 503 이고 키만 남으며 재시도는 새 접수다. */
    private static void failedBeforeAppend(ReservationFixture f, PartitionNode node, OrderGateway gateway,
                                           AtomicBoolean v47Down) throws Exception {
        long user = f.user("outage-b", new BigDecimal("100000000"));
        String key = "outage-b";
        AtomicBoolean fail = new AtomicBoolean(true);
        node.appendFault = (term, draft) -> {
            if (key.equals(draft.clientOrderId()) && fail.getAndSet(false)) {
                throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
            }
        };
        v47Down.set(true);
        assertDbUnreachable(place(gateway, user, key), "(b)");
        v47Down.set(false);
        assertEquals(1, keyRows(f, user, key), "(b) 결과를 모르는 접수의 키를 지웠다");
        assertEquals(0, newRows(f, user, key));

        OrderGateway.Placement placed = assertInstanceOf(OrderGateway.Placement.class, place(gateway, user, key));
        assertFalse(placed.replayed(), "(b) 커밋되지 않았던 주문의 재시도가 새 접수(201)가 아니다");
        assertEquals(1, newRows(f, user, key));
        assertEquals(1, reservations(f, placed.row().id()));
        assertEquals(0, f.decimal("SELECT amount FROM order_reservations WHERE order_id = " + placed.row().id())
                .compareTo(f.locked(user, KRW)), "(b) 잠긴 잔고가 예약액과 다르다");
        node.appendFault = (term, draft) -> { };
    }

    /**
     * (c) append 가 연결 장애로 끝났는데 다른 세션이 같은 키의 어드바이저리 락을 쥐고 있어 V47 이 잠금 대기 초과(55P03)로 끝난다.
     * DB 는 살아 있으므로 409 이고 503 이 아니다. 락이 풀린 뒤 재시도는 새 접수다.
     */
    private static void journalCheckReachedButTimedOut(ReservationFixture f, PartitionNode node, MarketDirectory markets)
            throws Exception {
        long user = f.user("outage-c", new BigDecimal("100000000"));
        String key = "outage-c";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(f.db.url());
        config.setUsername(PartitionLeaseDb.OWNER);
        config.setPassword(PartitionLeaseDb.OWNER);
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("SET lock_timeout = '300ms'");
        try (HikariDataSource impatient = new HikariDataSource(config)) {
            OrderGateway gateway = gateway(JdbcClient.create(impatient), node, markets);
            AtomicBoolean fail = new AtomicBoolean(true);
            node.appendFault = (term, draft) -> {
                if (key.equals(draft.clientOrderId()) && fail.getAndSet(false)) {
                    throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
                }
            };
            try (Connection holder = f.db.owner()) {
                holder.setAutoCommit(false);
                try (PreparedStatement lock = holder.prepareStatement(
                        "SELECT pg_advisory_xact_lock(hashtextextended('cmd-new:' || ?::text || ':' || ?, 0))")) {
                    lock.setLong(1, user);
                    lock.setString(2, key);
                    lock.execute();
                }
                ApiException busy = assertInstanceOf(ApiException.class, place(gateway, user, key));
                assertEquals(409, busy.status(), "(c) DB 에 닿은 판정 실패가 409 가 아니다: " + busy.name());
                assertEquals("order_in_progress", busy.name());
                assertEquals(ApiException.DbOutage.NONE, busy.dbOutage());
                assertEquals(0, gateway.intakeUnavailable("connection", "503"), "(c) DB 에 닿은 판정 실패로 503 을 냈다");
                assertEquals(1, gateway.intakeUnavailable("connection", "409"));
                assertEquals(1, gateway.unresolvedFailures());
                holder.rollback();
            }
            assertEquals(1, keyRows(f, user, key), "(c) 판정하지 못한 접수의 키를 지웠다");
            assertEquals(0, newRows(f, user, key));
            OrderGateway.Placement placed = assertInstanceOf(OrderGateway.Placement.class, place(gateway, user, key));
            assertFalse(placed.replayed());
            assertEquals(1, newRows(f, user, key));
        } finally {
            node.appendFault = (term, draft) -> { };
        }
    }

    /**
     * (d) 머리말이 fencing 거부를 받았지만 리스가 유효해 실제 재확인이 참이다. 강등하지 않고 게이트가 열려 있으므로 리더를 빼는
     * 503 이 아니라 V47 규칙의 429 다. 같은 키 재시도는 새 접수다.
     */
    private static void fencingWithTheGateOpen(ReservationFixture f, PartitionNode node, OrderGateway gateway)
            throws Exception {
        long user = f.user("outage-d", new BigDecimal("100000000"));
        String key = "outage-d";
        LeaderTerm term = node.lease(RESOURCE).currentTerm().orElseThrow();
        node.appendPool.failNextPrologue(DbFailures.serverError("P0001", "fencing 위반: 시험 주입"));

        ApiException rejected = assertInstanceOf(ApiException.class, place(gateway, user, key));
        assertEquals(429, rejected.status(), "(d) 게이트를 연 리더의 fencing 거부: " + rejected.name());
        assertEquals("market", rejected.detail().get("bucket"));
        assertEquals(1, gateway.intakeUnavailable("fencing", "429"));
        assertEquals(0, gateway.intakeUnavailable("fencing", "503"));
        assertTrue(node.runtime(RESOURCE).demotions().isEmpty(), "(d) 재확인이 참인데 강등했다");
        assertTrue(node.accepting(RESOURCE), "(d) 게이트가 닫혔다");
        assertSame(term, node.lease(RESOURCE).currentTerm().orElseThrow(), "(d) 기간이 바뀌었다");

        OrderGateway.Placement placed = assertInstanceOf(OrderGateway.Placement.class, place(gateway, user, key));
        assertFalse(placed.replayed());
        assertEquals(1, newRows(f, user, key));
    }

    /**
     * (e) 요청 A 의 append 가 저널 전에 롤백 실패로 끝나고, 첫 V47 이 A 의 키를 지우고 커밋한 뒤 응답만 잃는다. 두 판정 사이에 같은
     * 내용의 요청 B 가 같은 키를 새로 넣고 접수된다. 다시 부른 V47 은 키를 지우지 않으므로 B 의 키가 남고 A 는 확정 미접수가 아니다.
     */
    private static void lostVerdictResponseWithASameKeyRequestBetween(ReservationFixture f, PartitionNode node,
                                                                      MarketDirectory markets) throws Exception {
        long user = f.user("outage-e", new BigDecimal("100000000"));
        String key = "outage-e";
        OrderGateway gateway = gateway(f.app, node, markets);
        AtomicReference<Object> between = new AtomicReference<>();
        List<Boolean> discards = OrderGatewayTestAccess.interceptAfterResolutions(gateway, (call, discard) -> {
            if (call != 1) {
                return null;
            }
            try {
                between.set(place(gateway, user, key));
            } catch (Exception e) {
                between.set(e);
            }
            return DbFailures.connectionFailure();
        });
        AtomicBoolean fail = new AtomicBoolean(true);
        node.appendFault = (term, draft) -> {
            if (key.equals(draft.clientOrderId()) && fail.getAndSet(false)) {
                throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
            }
        };
        try {
            ApiException first = assertInstanceOf(ApiException.class, place(gateway, user, key), "(e) A");
            assertEquals(409, first.status(), "(e) 다시 본 판정으로 확정 미접수를 냈다: " + first.name());
            assertEquals("order_in_progress", first.name());
            assertEquals(List.of(true, false), discards, "(e) 다시 부른 판정이 키를 지우라고 했다");
            assertEquals(1, gateway.intakeUnavailable("connection", "409"));
            assertEquals(0, gateway.intakeUnavailable("connection", "429"));
        } finally {
            node.appendFault = (term, draft) -> { };
        }
        OrderGateway.Placement b = assertInstanceOf(OrderGateway.Placement.class, between.get(),
                "(e) 두 판정 사이의 B: " + between.get());
        assertFalse(b.replayed(), "(e) B 가 새 접수가 아니다");
        assertEquals(1, keyRows(f, user, key), "(e) 다시 부른 판정이 B 의 키를 지웠다");
        assertEquals(1, newRows(f, user, key));

        Object retry = place(gateway, user, key);
        OrderGateway.Placement replayed = assertInstanceOf(OrderGateway.Placement.class, retry, "(e) B 의 재전송: " + retry);
        assertTrue(replayed.replayed(), "(e) B 의 재전송이 멱등 재생이 아니다");
        assertEquals(b.row().id(), replayed.row().id(), "(e) 재생이 다른 주문을 돌려줬다");
        assertEquals(1, newRows(f, user, key), "(e) 재전송이 NEW 를 하나 더 만들었다");
        assertEquals(1, reservations(f, b.row().id()));
    }

    private static void assertDbUnreachable(Object outcome, String scene) {
        if (!(outcome instanceof ApiException e)) {
            fail(scene + " 가 DB 연결 장애 응답이 아니다: " + outcome);
            return;
        }
        assertEquals(503, e.status(), scene + ": " + e.name());
        assertEquals("service_unavailable", e.name());
        assertEquals("DB 연결 장애. 재조회 후 판단 필요", e.getMessage());
        assertEquals(1, e.retryAfter());
        assertEquals(ApiException.DbOutage.UNREACHABLE, e.dbOutage());
    }

    private static OrderGateway gateway(JdbcClient jdbc, PartitionNode node, MarketDirectory markets) {
        return new ApiConfig().orderGateway(jdbc, node.bridge, markets, new OrderQueryService(jdbc),
                new SimpleMeterRegistry(), new LeaderProtocolBoot(2), node.plan);
    }

    private static Object place(OrderGateway gateway, long user, String key) throws Exception {
        byte[] raw = ("{\"market\":\"" + MARKET + "\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"50000\","
                + "\"volume\":\"0.1\",\"client_order_id\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
        try {
            return gateway.place(user, new ObjectMapper().readTree(raw), raw);
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static long keyRows(ReservationFixture f, long user, String key) {
        return f.count("SELECT count(*) FROM idempotency_keys WHERE user_id = " + user + " AND idem_key = '" + key + "'");
    }

    private static long newRows(ReservationFixture f, long user, String key) {
        return f.count("SELECT count(*) FROM command_journal WHERE command_type = '" + CommandDraft.CommandType.NEW
                + "' AND user_id = " + user + " AND client_order_id = '" + key + "'");
    }

    private static long reservations(ReservationFixture f, long orderId) {
        return f.count("SELECT count(*) FROM order_reservations WHERE order_id = " + orderId);
    }
}

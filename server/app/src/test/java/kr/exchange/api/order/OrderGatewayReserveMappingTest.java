package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.auth.CanonicalRequests;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.AcceptGate;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 게이트웨이의 P2 잔고 판정(ADR 0034 S3, S3 설계 4-9·6-2, API 설계 v2.54 §2-3·§2-4). 브리지는 가짜라 저널에 닿지 않고,
 * 멱등 키·V47 판정·계정은 실제 DB 다.
 */
class OrderGatewayReserveMappingTest {

    private static final int KRW = 1;
    private static final int BTC = 2;

    static PartitionLeaseDb db;
    static JdbcClient jdbc;
    static MarketDirectory markets;
    static long rich;
    static long poor;
    static final AtomicInteger KEYS = new AtomicInteger();

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("gw_reserve_map");
        jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER));
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        rich = bootstrap.user("rich@gw.test");
        poor = bootstrap.user("poor@gw.test");
        for (long u : new long[] {rich, poor}) {
            bootstrap.userAccounts(u, KRW);
            bootstrap.userAccounts(u, BTC);
        }
        bootstrap.deposit(rich, KRW, new BigDecimal("1000000"));
        bootstrap.deposit(poor, KRW, new BigDecimal("1000"));
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
    void dbInsufficientFundsBecomes400AndTheKeyIsCleaned() throws Exception {
        FakeBridge bridge = new FakeBridge(intake -> {
            throw new DataIntegrityViolationException("reserve", check("chk_balance_nonneg"));
        });
        OrderGateway gateway = new OrderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc),
                OrderGateway.ReserveMode.DB_RESERVATION);
        String key = key();

        ApiException e = assertThrows(ApiException.class, () -> place(gateway, rich, key, "50000", "1"));

        assertEquals(400, e.status());
        assertEquals("insufficient_funds", e.name());
        assertEquals(1, bridge.calls.get(), "DB 경로를 거치지 않았다");
        assertEquals(1, gateway.reserveRejections("db"));
        assertEquals(0, gateway.reserveRejections("precheck"));
        assertEquals(0, keyRows(rich, key), "V47 판정이 이 요청의 키를 정리하지 않았다");
        assertEquals(0, gateway.inflightPending(rich, (short) KRW).signum(), "P2 가 in-flight 누계를 잡았다");
    }

    @Test
    void checkViolationOfAnotherConstraintIsNotInsufficientFunds() throws Exception {
        DataIntegrityViolationException other = new DataIntegrityViolationException("other",
                check("order_reservations_amount_check"));
        OrderGateway gateway = new OrderGateway(jdbc, new FakeBridge(intake -> {
            throw other;
        }), markets, new OrderQueryService(jdbc), OrderGateway.ReserveMode.DB_RESERVATION);

        RuntimeException e = assertThrows(RuntimeException.class, () -> place(gateway, rich, key(), "50000", "1"));

        assertSame(other, e, "다른 제약의 23514 를 잔고 부족으로 바꿨다");
        assertEquals(0, gateway.reserveRejections("db"));
    }

    /**
     * 접수 append 일시 오류 계수의 양성 축(S3 구현 검토). 브리지가 교착(40P01)이나 직렬화 실패(40001)로 끝나면
     * exchange.order.append.transient{sqlstate} 가 오르고 응답은 원래 예외다(S4 전까지 500). 잠금 대기 초과(55P03)는 이
     * 계수가 세지 않는다. 이 사례가 없으면 이중 지출 시험과 스택 관문의 '접수 40P01 0' 판정은 계수가 깨져도 통과한다.
     */
    @Test
    void appendDeadlockAndSerializationFailuresAreCounted() throws Exception {
        for (String state : new String[] {"40P01", "40001", "55P03"}) {
            RuntimeException failure = new org.springframework.dao.CannotAcquireLockException("append " + state,
                    new PSQLException(new ServerErrorMessage("SERROR\u0000C" + state + "\u0000Mappend failed\u0000")));
            FakeBridge bridge = new FakeBridge(intake -> {
                throw failure;
            });
            OrderGateway gateway = p2(bridge);
            String key = key();

            RuntimeException e = assertThrows(RuntimeException.class, () -> place(gateway, rich, key, "50000", "1"));

            assertSame(failure, e, state + " 응답을 바꿨다");
            assertEquals(1, bridge.calls.get(), state + " 이 브리지에 닿지 않았다");
            assertEquals("55P03".equals(state) ? 0 : 1, gateway.appendTransient(state), state);
            assertEquals(0, keyRows(rich, key), state + " 뒤 V47 판정이 키를 정리하지 않았다");
        }
    }

    @Test
    void p2DoesNotHoldInflightAndP1ReleasesImmediatelyWhenTheDbReserved() throws Exception {
        // P2: 브리지 안에서 in-flight 누계가 0 이다.
        String p2Key = key();
        OrderGateway[] holder = new OrderGateway[1];
        FakeBridge p2Bridge = new FakeBridge(intake -> {
            assertEquals(0, holder[0].inflightPending(rich, (short) KRW).signum(), "P2 가 in-flight 누계를 잡았다");
            insertOrder(rich, intake.clientOrderId(), intake.assignedOrderId());
            return new SubmitResult(1, 1, false, true, true);
        });
        holder[0] = new OrderGateway(jdbc, p2Bridge, markets, new OrderQueryService(jdbc),
                OrderGateway.ReserveMode.DB_RESERVATION);
        assertEquals("OPEN", place(holder[0], rich, p2Key, "50000", "1").row().status());
        assertTrue(!holder[0].inflightEnabled());

        // P1 노드가 P2 DB 에서 돌면 DB 가 예약한 몫을 in-flight 에서 바로 푼다. 행이 보이지 않아 409 로 끝나도 남지 않는다.
        OrderGateway p1 = new OrderGateway(jdbc, new FakeBridge(intake -> new SubmitResult(2, 1, false, true, true)),
                markets, new OrderQueryService(jdbc));
        ApiException pending = assertThrows(ApiException.class, () -> place(p1, rich, key(), "50000", "1"));
        assertEquals(409, pending.status());
        assertEquals(0, p1.inflightPending(rich, (short) KRW).signum(), "DB 예약분을 in-flight 에 남겼다");
        // 양성 축: DB 가 예약하지 않은 P1 접수는 행을 못 보면 누계를 지연 회수로 남긴다.
        OrderGateway p1Plain = new OrderGateway(jdbc, new FakeBridge(intake -> new SubmitResult(3, 1, false, true)),
                markets, new OrderQueryService(jdbc));
        assertThrows(ApiException.class, () -> place(p1Plain, rich, key(), "50000", "1"));
        assertEquals(0, new BigDecimal("50000").compareTo(p1Plain.inflightPending(rich, (short) KRW)));
    }

    /** (a) 저장된 해시가 있고 내용이 같으면 원본이 저널에 없어도 빠른 거부를 건너뛴다. 잔고 조회가 없다. */
    @Test
    void storedSameHashSkipsFastRejectAndReplays() throws Exception {
        String key = key();
        insertOrder(poor, key, nextval());
        jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                .params(poor, key, hash(poor, key, "50000", "1")).update();
        FakeBridge bridge = new FakeBridge(intake -> new SubmitResult(7, 1, true, false));
        OrderGateway gateway = p2(bridge);
        AtomicInteger lookups = new AtomicInteger();
        gateway.setBalanceLookup((user, asset) -> {
            lookups.incrementAndGet();
            return BigDecimal.ZERO;
        });

        OrderGateway.Placement placement = place(gateway, poor, key, "50000", "1");

        assertTrue(placement.replayed());
        assertEquals(0, lookups.get(), "저장된 해시와 같은 재전송이 잔고를 조회했다");
        assertEquals(1, bridge.calls.get());
        assertEquals(1, sharedRows(poor, key), "키를 공유 표시로 선점하지 않았다");
    }

    /** (b) 첫 조회에 해시가 없어 빠른 거부에 걸렸지만 재조회에서 같은 내용의 해시가 보이면 재생으로 간다. */
    @Test
    void hashAppearingBeforeTheRejectSendsTheRequestToReplay() throws Exception {
        String key = key();
        insertOrder(poor, key, nextval());
        FakeBridge bridge = new FakeBridge(intake -> new SubmitResult(8, 1, true, false));
        OrderGateway gateway = p2(bridge);
        gateway.setBalanceLookup((user, asset) -> {
            // 원본이 이 순간 키를 넣고 예약과 함께 커밋했다. 잔고 조회는 원본의 예약이 빠진 값을 본다.
            jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                    .params(poor, key, hash(poor, key, "50000", "1")).update();
            return BigDecimal.ZERO;
        });

        OrderGateway.Placement placement = place(gateway, poor, key, "50000", "1");

        assertTrue(placement.replayed(), "원본과 같은 재전송이 400 을 받았다");
        assertEquals(1, bridge.calls.get());
        assertEquals(0, gateway.reserveRejections("precheck"));
    }

    /** (c) 재조회에도 해시가 없으면 400 insufficient_funds(precheck)다. (d) 다른 내용의 해시면 409 다. */
    @Test
    void fastRejectWithoutHashIs400AndDifferentHashIs409() throws Exception {
        FakeBridge bridge = new FakeBridge(intake -> {
            throw new AssertionError("빠른 거부가 브리지에 닿았다");
        });
        OrderGateway gateway = p2(bridge);
        String noHash = key();
        ApiException e = assertThrows(ApiException.class, () -> place(gateway, poor, noHash, "50000", "1"));
        assertEquals(400, e.status());
        assertEquals("insufficient_funds", e.name());
        assertEquals(1, gateway.reserveRejections("precheck"));
        assertEquals(0, keyRows(poor, noHash), "빠른 거부가 키를 소비했다");

        String other = key();
        gateway.setBalanceLookup((user, asset) -> {
            jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                    .params(poor, other, hash(poor, other, "40000", "1")).update();
            return BigDecimal.ZERO;
        });
        ApiException dup = assertThrows(ApiException.class, () -> place(gateway, poor, other, "50000", "1"));
        assertEquals(409, dup.status());
        assertEquals("duplicate_client_order_id", dup.name());
        assertEquals(0, bridge.calls.get());
    }

    /** (e) P1 은 종전대로다. 같은 인터리빙에서 in-flight 가드가 400 을 낸다(API 설계 §2-4 P1 예외 문장). */
    @Test
    void p1KeepsTheInflightGuardBehaviour() throws Exception {
        String key = key();
        FakeBridge bridge = new FakeBridge(intake -> new SubmitResult(9, 1, true, false));
        OrderGateway gateway = new OrderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc));
        gateway.setBalanceLookup((user, asset) -> {
            jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)"
                            + " ON CONFLICT DO NOTHING")
                    .params(poor, key, hash(poor, key, "50000", "1")).update();
            return BigDecimal.ZERO;
        });

        ApiException e = assertThrows(ApiException.class, () -> place(gateway, poor, key, "50000", "1"));

        assertEquals(400, e.status());
        assertEquals("insufficient_funds", e.name());
        assertEquals(0, bridge.calls.get());
    }

    private static OrderGateway p2(FakeBridge bridge) {
        return new OrderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc), OrderGateway.ReserveMode.DB_RESERVATION);
    }

    private static OrderGateway.Placement place(OrderGateway gateway, long user, String key, String price,
                                                String volume) throws Exception {
        byte[] raw = body(key, price, volume);
        return gateway.place(user, new ObjectMapper().readTree(raw), raw);
    }

    private static byte[] body(String key, String price, String volume) {
        return ("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"limit\",\"price\":\"" + price
                + "\",\"volume\":\"" + volume + "\",\"client_order_id\":\"" + key + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String hash(long user, String key, String price, String volume) {
        return CanonicalRequests.sha256Hex(CanonicalRequests.jcs(body(key, price, volume)));
    }

    private static String key() {
        return "rm-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long nextval() {
        return jdbc.sql("SELECT nextval('order_id_seq')").query(Long.class).single();
    }

    /** 조회 프로젝션에 이 키의 미체결 주문을 둔다. 재생 응답과 접수 응답이 이 행을 읽는다. */
    private static void insertOrder(long user, String key, long id) {
        jdbc.sql("""
                        INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                            price, quantity, remaining_qty, status)
                        VALUES (?, ?, ?, 1, ?, 'BUY', 'LIMIT', 50000, 1, 1, 'OPEN')
                        """)
                .params(id, UUID.randomUUID(), user, key)
                .update();
    }

    private static long keyRows(long user, String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(user, key).query(Long.class).single();
    }

    private static long sharedRows(long user, String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ? AND shared_at IS NOT NULL")
                .params(user, key).query(Long.class).single();
    }

    /** 서버 오류 필드를 가진 23514. 제약 이름이 서버 필드로 온다(S3 설계 3절 P6). */
    private static PSQLException check(String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\u0000C23514\u0000Mnew row violates check constraint \""
                + constraint + "\"\u0000n" + constraint + "\u0000"));
    }

    /** 저널에 닿지 않는 브리지. 결과는 시험이 정한다. */
    static final class FakeBridge extends EngineBridge {
        final AtomicInteger calls = new AtomicInteger();
        private final Function<OrderIntake, SubmitResult> result;

        FakeBridge(Function<OrderIntake, SubmitResult> result) {
            super(null, new SingleWriterLoop(16, new MatchingEngineImpl(), events -> {
            }, (afterSeq, limit) -> List.of(), fatal -> {
            }), new CommandPayloadMapper(new ObjectMapper()), AcceptGate.ALWAYS_OPEN, marketId -> false);
            this.result = result;
        }

        @Override
        public SubmitResult submit(OrderIntake intake) {
            calls.incrementAndGet();
            return result.apply(intake);
        }
    }
}

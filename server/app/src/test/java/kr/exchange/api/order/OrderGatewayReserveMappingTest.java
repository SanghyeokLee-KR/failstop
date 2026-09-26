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
     * 마켓 접수 경합의 확정 미접수(API 설계 §2-5-b). 키를 새로 넣은 요청이 경합으로 끝나고 V47 이 그 키를 지우면(DISCARDED)
     * 429 too_many_requests(bucket market)이고 503 이 아니다. 일시 오류 넷은 exchange.order.append.transient{sqlstate} 로 모두
     * 센다. 이 계수가 없으면 이중 지출 시험과 스택 관문의 '접수 40P01 0' 판정은 계수가 깨져도 통과한다.
     */
    @Test
    void contentionWithDiscardedKeyIsConfirmedNotAccepted429() throws Exception {
        for (String state : new String[] {"40P01", "40001", "55P03", "57014"}) {
            FakeBridge bridge = new FakeBridge(intake -> {
                throw contention(state);
            });
            OrderGateway gateway = p2(bridge);
            String key = key();

            ApiException e = assertThrows(ApiException.class, () -> place(gateway, rich, key, "50000", "1"));

            assertMarketContention429(e, state);
            assertTrue(e.demandExcluded(), state + " 경합을 좀비 판정 수요에서 빼지 않았다");
            assertEquals(1, bridge.calls.get(), state + " 이 브리지에 닿지 않았다");
            assertEquals(1, gateway.appendTransient(state), state);
            assertEquals(1, gateway.intakeContention(state, "429"), state);
            assertEquals(0, keyRows(rich, key), state + " 뒤 V47 판정이 키를 정리하지 않았다");
        }
        OrderGateway busy = p2(new FakeBridge(intake -> {
            throw new EngineBridge.AcceptBusyException(1, 2000);
        }));
        assertMarketContention429(assertThrows(ApiException.class, () -> place(busy, rich, key(), "50000", "1")),
                "lock_wait");
        assertEquals(1, busy.intakeContention("lock_wait", "429"));
        OrderGateway stuck = p2(new FakeBridge(intake -> {
            throw new EngineBridge.AcceptBusyException(1, 2000, true);
        }));
        ApiException stuckResponse = assertThrows(ApiException.class, () -> place(stuck, rich, key(), "50000", "1"));
        assertMarketContention429(stuckResponse, "lock_wait_stuck");
        assertTrue(!stuckResponse.demandExcluded(), "멈춘 보유자 때문의 대기 초과를 수요에서 뺐다");
        assertEquals(1, stuck.intakeContention("lock_wait_stuck", "429"));
    }

    /**
     * 확정할 수 없는 경합은 409 order_in_progress 다(API 설계 §2-5-b). 같은 키의 다른 요청이 공유 표시를 채워 키가 남은 경우
     * (KEPT), 키가 사라진 경우(ABSENT), 공유 표시를 채운 요청(NOT_JOURNALED), V47 판정 실패, 이미 저널에 있는 주문의 재전송이다.
     * 어느 것도 429 나 503 이 아니다.
     */
    @Test
    void contentionThatCannotConfirmNonAcceptanceIs409OrderInProgress() throws Exception {
        // KEPT: 이 요청이 넣은 키에 같은 내용의 다른 요청이 공유 표시를 채웠다.
        String kept = key();
        OrderGateway keptGateway = p2(new FakeBridge(intake -> {
            jdbc.sql("UPDATE idempotency_keys SET shared_at = now() WHERE user_id = ? AND idem_key = ?")
                    .params(rich, kept).update();
            throw contention("55P03");
        }));
        assertOrderInProgress(assertThrows(ApiException.class, () -> place(keptGateway, rich, kept, "50000", "1")));
        assertEquals(1, keyRows(rich, kept), "공유 표시가 있는 키를 지웠다");
        assertEquals(1, keptGateway.intakeContention("55P03", "409"));

        // ABSENT: 키 행이 사라졌다.
        String absent = key();
        OrderGateway absentGateway = p2(new FakeBridge(intake -> {
            jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = ? AND idem_key = ?").params(rich, absent).update();
            throw contention("40001");
        }));
        assertOrderInProgress(assertThrows(ApiException.class, () -> place(absentGateway, rich, absent, "50000", "1")));

        // NOT_JOURNALED: 같은 내용의 기존 키에 공유 표시를 채운 요청이다. 키를 넣은 원본이 뒤이어 커밋할 수 있다.
        String shared = key();
        jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                .params(rich, shared, hash(rich, shared, "50000", "1")).update();
        OrderGateway sharedGateway = p2(new FakeBridge(intake -> {
            throw new EngineBridge.AcceptBusyException(1, 2000);
        }));
        assertOrderInProgress(assertThrows(ApiException.class, () -> place(sharedGateway, rich, shared, "50000", "1")));
        assertEquals(1, sharedRows(rich, shared), "공유 표시 요청이 키를 지웠다");
        assertEquals(1, sharedGateway.intakeContention("lock_wait", "409"));

        // 재생: 같은 키의 주문이 이미 저널에 있어 키를 잡지 않은 재전송이다.
        String replay = key();
        jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                .params(rich, replay, hash(rich, replay, "50000", "1")).update();
        journalNew(rich, replay);
        OrderGateway replayGateway = p2(new FakeBridge(intake -> {
            throw contention("40P01");
        }));
        assertOrderInProgress(assertThrows(ApiException.class, () -> place(replayGateway, rich, replay, "50000", "1")));

        // V47 판정 실패: 키와 예약을 남기고 확정하지 않는다.
        String unresolved = key();
        OrderGateway unresolvedGateway = p2(new FakeBridge(intake -> {
            jdbc.sql("ALTER FUNCTION resolve_failed_order_intake(BIGINT, VARCHAR, VARCHAR, BIGINT, BOOLEAN) "
                    + "RENAME TO resolve_failed_order_intake_off").update();
            throw contention("55P03");
        }));
        try {
            assertOrderInProgress(assertThrows(ApiException.class,
                    () -> place(unresolvedGateway, rich, unresolved, "50000", "1")));
            assertEquals(1, unresolvedGateway.unresolvedFailures(), "판정 실패 경로를 타지 않았다");
            assertEquals(1, keyRows(rich, unresolved), "판정 실패인데 키를 지웠다");
        } finally {
            jdbc.sql("ALTER FUNCTION resolve_failed_order_intake_off(BIGINT, VARCHAR, VARCHAR, BIGINT, BOOLEAN) "
                    + "RENAME TO resolve_failed_order_intake").update();
        }
    }

    /**
     * 경합도 DB 불통도 리더 자격 거부도 아닌 예외의 응답은 그대로다. 연결이 아닌 자원 실패(54000)와 DB 밖 예외가 그렇다. DB 불통의
     * 응답은 OrderGatewayOutageMappingTest 가 본다. 비접수 55P03 은 이 경로가 아니다(ApiExceptionHandler 가 500 으로 둔다).
     */
    @Test
    void failuresThatAreNeitherContentionNorOutageNorFencingKeepTheirResponses() throws Exception {
        for (RuntimeException broken : new RuntimeException[] {kr.exchange.journal.DbFailures.sql("54000"),
                new IllegalArgumentException("주입")}) {
            OrderGateway gateway = p2(new FakeBridge(intake -> {
                throw broken;
            }));
            RuntimeException e = assertThrows(RuntimeException.class, () -> place(gateway, rich, key(), "50000", "1"));
            assertSame(broken, e, "그대로 올릴 예외의 응답을 바꿨다: " + broken);
            for (String cause : new String[] {"lock_wait", "lock_wait_stuck", "55P03", "57014", "40P01", "40001"}) {
                assertEquals(0, gateway.intakeContention(cause, "429") + gateway.intakeContention(cause, "409"), cause);
            }
            for (String cause : new String[] {"connection", "fencing"}) {
                for (String response : new String[] {"503", "429", "409"}) {
                    assertEquals(0, gateway.intakeUnavailable(cause, response), cause + "|" + response);
                }
            }
        }
    }

    private static RuntimeException contention(String state) {
        return new org.springframework.dao.CannotAcquireLockException("append " + state,
                new PSQLException(new ServerErrorMessage("SERROR\u0000C" + state + "\u0000Mappend failed\u0000")));
    }

    private static void assertMarketContention429(ApiException e, String cause) {
        assertEquals(429, e.status(), cause);
        assertEquals("too_many_requests", e.name(), cause);
        assertEquals("market", e.detail().get("bucket"), cause);
        assertEquals("order", e.detail().get("scope"), cause);
        assertEquals(1, e.retryAfter(), cause);
    }

    private static void assertOrderInProgress(ApiException e) {
        assertEquals(409, e.status(), "확정할 수 없는 경합이 409 가 아니다: " + e.name());
        assertEquals("order_in_progress", e.name());
        assertEquals(1, e.retryAfter());
        assertTrue(e.demandExcluded());
    }

    /** 저널에 이 키의 주문 생성 명령을 둔다. 재전송 판정(commandJournaled)이 읽는다. */
    private static void journalNew(long user, String key) {
        long seq = jdbc.sql("SELECT COALESCE(max(seq), 0) + 1 FROM command_journal").query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                     assigned_order_id, engine_ts, tie_break, payload,
                                                     fencing_token, checksum)
                        VALUES (?, 1, 'NEW', ?, ?, ?, 0, 0, '{}', 1, 0)
                        """)
                .params(seq, user, key, nextval())
                .update();
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

    /** 저널에 닿지 않는 브리지. 결과와 접수 게이트 상태는 시험이 정한다. 게이트는 기본으로 열려 있다(종전 ALWAYS_OPEN). */
    static final class FakeBridge extends EngineBridge {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean gateOpen = true;
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

        @Override
        public boolean accepting(int marketId) {
            return gateOpen;
        }
    }
}

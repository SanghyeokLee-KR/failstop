package kr.exchange.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.engine.AcceptGate;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.ledger.AccountBootstrap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔진 접수에서 마켓 정지를 만난 명령은 저널 전에 422 market_suspended 로 끝난다(API 설계 §4-B-1·§4-B-2).
 * 주문 생성은 선삽입한 멱등 키를 남기지 않고, 일괄 취소는 정지 마켓 주문을 원소별 실패로 집계한다.
 */
@Testcontainers
class OrderGatewayMarketHaltIntegrationTest {

    private static final int KRW = 1;
    private static final int BTC = 2;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static JdbcClient jdbc;
    static MarketDirectory markets;
    static long userId;

    @BeforeAll
    static void seed() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        userId = bootstrap.user("halt@gateway.test");
        bootstrap.userAccounts(userId, KRW);
        bootstrap.userAccounts(userId, BTC);
        bootstrap.deposit(userId, KRW, new BigDecimal("1000000"));
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
    void 검증_뒤_정지된_마켓의_주문은_422_market_suspended_로_끝나고_키를_남기지_않는다() throws Exception {
        SingleWriterLoop loop = loop();
        // 카탈로그 스냅샷은 ACTIVE 인데 엔진이 보는 정지 여부는 참이다. 관리자 정지가 두 검사 사이에 든 창이다.
        OrderGateway gateway = haltedGateway(loop);
        byte[] raw = """
                {"market":"KRW-BTC","side":"bid","ord_type":"limit","price":"50000","volume":"1",
                 "client_order_id":"halt-race-1"}
                """.getBytes(StandardCharsets.UTF_8);
        loop.start();
        try {
            ApiException e = assertThrows(ApiException.class,
                    () -> gateway.place(userId, new ObjectMapper().readTree(raw), raw));

            assertEquals(422, e.status());
            assertEquals("domain_rejected", e.name());
            assertEquals(Map.of("reject_reason", "market_suspended"), e.detail());
            // 저널 전 거부라 선삽입한 멱등 키를 지워야 재개 뒤 같은 키의 재시도가 잔고 가드를 다시 받는다.
            assertEquals(0L, jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                    .params(userId, "halt-race-1").query(Long.class).single());
            // 저널에 닿지 않은 거부라 잡은 in-flight 예약도 바로 풀려야 한다. 남으면 TTL 동안 같은 잔고의 주문이 오거부된다.
            assertEquals(0, gateway.inflightPending(userId, (short) KRW).signum(), "정지 거부가 잔고 예약을 남겼다");
            assertTrue(loop.isRunning(), "정지 거부가 라이터를 멈췄다");
        } finally {
            loop.stop();
        }
    }

    @Test
    void 정지된_마켓의_주문_취소는_422_market_suspended_이고_일괄_취소는_원소별_실패로_집계한다() throws Exception {
        UUID first = openOrder("halt-cancel-1");
        UUID second = openOrder("halt-cancel-2");
        SingleWriterLoop loop = loop();
        OrderGateway gateway = haltedGateway(loop);
        loop.start();
        try {
            ApiException e = assertThrows(ApiException.class, () -> gateway.cancel(userId, first));
            assertEquals(422, e.status());
            assertEquals("domain_rejected", e.name());
            assertEquals(Map.of("reject_reason", "market_suspended"), e.detail());

            // 한 건의 거부가 일괄 취소를 끊지 않는다. 폐쇄의 미체결 전량 취소도 이 경로를 탄다.
            OrderGateway.BulkCancelResult bulk = gateway.cancelAll(userId, "KRW-BTC");
            assertEquals(List.of(), bulk.canceledIds());
            assertEquals(List.of(first.toString(), second.toString()),
                    bulk.failed().stream().map(f -> f.id()).toList());
            assertTrue(bulk.failed().stream().allMatch(f -> "market_suspended".equals(f.reason())));
            // 정지 마켓의 거부는 재개가 먼저라 회원 폐쇄의 미완료(503) 수에 넣지 않는다(API 설계 §6-E v2.53).
            assertEquals(0, bulk.unsubmitted());
            assertEquals(2L, jdbc.sql("SELECT count(*) FROM orders WHERE user_id = ? AND status = 'OPEN'"
                            + " AND client_order_id LIKE 'halt-cancel-%'")
                    .params(userId).query(Long.class).single());
            assertTrue(loop.isRunning(), "정지 거부가 라이터를 멈췄다");
        } finally {
            loop.stop();
        }
    }

    private static SingleWriterLoop loop() {
        return new SingleWriterLoop(16, new MatchingEngineImpl(), events -> {
        }, (afterSeq, limit) -> List.of(), fatal -> {
        });
    }

    /** 엔진이 보는 정지 여부가 늘 참인 게이트웨이. 저널 경계만 대신하고 정지 거부는 그보다 앞이라 닿으면 실패다. */
    private static OrderGateway haltedGateway(SingleWriterLoop loop) {
        CommandJournalAppender appender = new CommandJournalAppender(null, null, null) {
            @Override
            public AppendResult append(CommandDraft draft) {
                throw new AssertionError("정지 마켓의 명령이 저널에 닿았다");
            }
        };
        EngineBridge bridge = new EngineBridge(appender, loop, new CommandPayloadMapper(new ObjectMapper()),
                AcceptGate.ALWAYS_OPEN, marketId -> true);
        return new OrderGateway(jdbc, bridge, markets, new OrderQueryService(jdbc));
    }

    /** 조회 프로젝션에 미체결 지정가 매수 한 건을 둔다. 취소 경로는 이 행으로 대상과 마켓을 찾는다. */
    private static UUID openOrder(String clientOrderId) {
        UUID externalId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                            price, quantity, remaining_qty, status)
                        VALUES (nextval('order_id_seq'), ?, ?, 1, ?, 'BUY', 'LIMIT', 50000, 1, 1, 'OPEN')
                        """)
                .params(externalId, userId, clientOrderId)
                .update();
        return externalId;
    }
}

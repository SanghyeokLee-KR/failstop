package kr.exchange.api.order;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.dto.CancelAllResponse;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.TestPartitions;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.DbFailures;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인계 요청자의 기다림이나 포기가 DB 오류로 끝난 일괄 취소(DB 설계 §5.4-d 2, S5 설계 6-2). 요청자는 그 행이 뒤에 처리될지 모르므로
 * DONE 이 아닌 행의 주문을 모두 partition_pending 으로 알린다. partition_not_started 는 '이 요청이 그 주문에 아무 명령도 넣지
 * 않았다'는 뜻이라 쓰지 않는다. 게이트가 닫힌 요청자와 작업자 없는 V54 표를 쓰고 저장소의 읽기나 포기만 DB 연결 장애로 끝나게 한다.
 */
class HandoffRequesterOutageTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final AtomicInteger USERS = new AtomicInteger();

    static PartitionLeaseDb db;
    static DriverManagerDataSource dataSource;
    static JdbcClient owner;
    static AccountBootstrap bootstrap;
    static MarketDirectory markets;

    @BeforeAll
    static void seed() {
        db = PartitionLeaseDb.create("pml_handoff_outage");
        dataSource = new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER, PartitionLeaseDb.OWNER);
        owner = JdbcClient.create(dataSource);
        bootstrap = new AccountBootstrap(owner);
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(1, "KRW-BTC", BTC, KRW, new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        markets = new MarketDirectory(owner);
        markets.start();
    }

    @AfterAll
    static void close() {
        if (markets != null) {
            markets.close();
        }
    }

    /** 첫 읽기부터 끊기면 행 상태를 하나도 모른다. 포기하지 못한 행은 PENDING 으로 남아 유효 시간 안에 처리될 수 있다. */
    @Test
    void firstReadOutageLeavesEveryHandedOrderPending() {
        Requester r = requester(new OrderCancelHandoffRepository(owner, tx()) {
            @Override
            public List<Row> byRequest(UUID requestId) {
                throw DbFailures.connectionFailure();
            }
        });

        assertAllPending(r, r.gateway.cancelAll(r.user, null));
        assertEquals(List.of("PENDING", "PENDING"), states(r.user));
    }

    /** 포기가 끊기면 마지막으로 읽은 PENDING 행이 그대로 남아 뒤에 점유될 수 있다. */
    @Test
    void abandonOutageLeavesUnfinishedRowsPending() {
        Requester r = requester(new OrderCancelHandoffRepository(owner, tx()) {
            @Override
            public int abandon(UUID requestId) {
                throw DbFailures.connectionFailure();
            }
        });

        assertAllPending(r, r.gateway.cancelAll(r.user, null));
        assertEquals(List.of("PENDING", "PENDING"), states(r.user));
    }

    /** 포기 뒤 다시 읽기가 끊기면 포기가 어느 행에 먹었는지 모른다. 행이 ABANDONED 가 됐어도 partition_pending 으로 알린다. */
    @Test
    void rereadOutageAfterAbandonLeavesRowsPending() {
        AtomicBoolean abandoned = new AtomicBoolean();
        Requester r = requester(new OrderCancelHandoffRepository(owner, tx()) {
            @Override
            public int abandon(UUID requestId) {
                int rows = super.abandon(requestId);
                abandoned.set(true);
                return rows;
            }

            @Override
            public List<Row> byRequest(UUID requestId) {
                if (abandoned.get()) {
                    throw DbFailures.connectionFailure();
                }
                return super.byRequest(requestId);
            }
        });

        assertAllPending(r, r.gateway.cancelAll(r.user, null));
        assertEquals(List.of("ABANDONED", "ABANDONED"), states(r.user));
    }

    /**
     * 만료가 먼저 닫은 행(넣은 지 5분이 지나 ABANDONED)을 읽은 뒤 포기가 끊긴 경우. 직접 취소가 유효 시간보다 오래 걸린 요청에서
     * 난다. 포기가 끊겼으므로 DONE 이 아닌 이 행들도 partition_pending 이다.
     */
    @Test
    void rowsClosedByExpiryAreStillPendingWhenAbandonFails() {
        Requester r = requester(new OrderCancelHandoffRepository(owner, tx()) {
            @Override
            public List<Long> insert(UUID requestId, long userId, String origin, List<NewRow> rows) {
                List<Long> ids = super.insert(requestId, userId, origin, rows);
                owner.sql("UPDATE order_cancel_requests SET created_at = now() - interval '6 minutes' WHERE request_id = ?")
                        .param(requestId).update();
                expire();
                return ids;
            }

            @Override
            public int abandon(UUID requestId) {
                throw DbFailures.connectionFailure();
            }
        });

        assertAllPending(r, r.gateway.cancelAll(r.user, null));
        assertEquals(List.of("ABANDONED", "ABANDONED"), states(r.user));
    }

    private record Requester(long user, List<String> orders, OrderGateway gateway, CancelHandoffCounters counters) {
    }

    /** 게이트가 닫힌 요청자(팔로워, 엔진을 끈 관리 평면)와 열린 주문 둘을 가진 회원. 행 하나에 주문 하나씩 넘긴다. */
    private static Requester requester(OrderCancelHandoffRepository repo) {
        long user = bootstrap.user("outage-" + USERS.incrementAndGet() + "@handoff.test");
        List<String> orders = List.of(openOrder(user).toString(), openOrder(user).toString());
        ProjectingFakeBridge bridge = new ProjectingFakeBridge(owner);
        bridge.open = false;
        OrderGateway gateway = new ApiConfig().orderGateway(owner, bridge, markets, new OrderQueryService(owner),
                new SimpleMeterRegistry(), new LeaderProtocolBoot(2), PartitionPlan.single());
        CancelHandoffCounters counters = CancelHandoffCounters.untracked();
        gateway.attachCancelHandoff(new OrderCancelHandoff(repo, PartitionPlan.single(),
                TestPartitions.unstarted(PartitionPlan.single()),
                new OrderCancelHandoff.Settings(Duration.ofMillis(200), Duration.ofMillis(50), 1, 1,
                        Duration.ofSeconds(60)), counters));
        return new Requester(user, orders, gateway, counters);
    }

    private static void assertAllPending(Requester r, OrderGateway.BulkCancelResult result) {
        assertTrue(result.canceledIds().isEmpty(), "취소됨: " + result.canceledIds());
        assertEquals(r.orders, result.failed().stream().map(CancelAllResponse.Failure::id).toList());
        assertEquals(List.of("partition_pending", "partition_pending"),
                result.failed().stream().map(CancelAllResponse.Failure::reason).toList());
        assertEquals(0, result.partitionNotStarted());
        assertEquals(2, r.counters.requests("pending"));
        assertEquals(0, r.counters.requests("not_started"), "넘긴 행을 시작하지 않은 것으로 셌다");
    }

    private static List<String> states(long user) {
        return owner.sql("SELECT state FROM order_cancel_requests WHERE user_id = ? ORDER BY id").param(user)
                .query(String.class).list();
    }

    private static TransactionTemplate tx() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private static UUID openOrder(long user) {
        UUID external = UUID.randomUUID();
        long id = owner.sql("SELECT nextval('order_id_seq')").query(Long.class).single();
        owner.sql("""
                        INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type,
                                            price, quantity, remaining_qty, status)
                        VALUES (?, ?, ?, 1, ?, 'BUY', 'LIMIT', 50000, 1, 1, 'OPEN')
                        """)
                .params(id, external, user, "outage-open-" + id)
                .update();
        return external;
    }
}

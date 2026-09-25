package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.CancelHandoffCounters;
import kr.exchange.api.order.CancelHandoffWorker;
import kr.exchange.api.order.OrderCancelHandoff;
import kr.exchange.api.order.OrderCancelHandoffRepository;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.config.ApiConfig;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 파티션 밖 취소 인계 시험의 공통 배선(S5 설계 6-2). 한 DB 를 쓰는 노드들(PartitionNode)과, 그 노드의 브리지를 쓰는 게이트웨이,
 * 인계 요청자·작업자를 운영 배선(ApiConfig)과 같게 조립한다. 게이트가 닫힌 요청자(엔진을 끈 관리 평면, 팔로워)는 수명주기 없는
 * 파티션 묶음으로 만든다.
 */
final class HandoffHarness implements AutoCloseable {

    final ReservationFixture f;
    final MarketDirectory markets;
    final OrderCancelHandoffRepository repo;
    private final List<AutoCloseable> closing = new ArrayList<>();

    HandoffHarness(ReservationFixture f) {
        this.f = f;
        this.markets = new MarketDirectory(f.app);
        markets.start();
        this.repo = new OrderCancelHandoffRepository(f.app, new TransactionTemplate(new JdbcTransactionManager(f.appPool)));
    }

    /** 빠른 시험용 인계 설정. wait 만 고른다. */
    static OrderCancelHandoff.Settings settings(Duration wait) {
        return new OrderCancelHandoff.Settings(wait, Duration.ofMillis(50), 100, 1, Duration.ofSeconds(60));
    }

    static OrderCancelHandoff.Settings settings(Duration wait, int chunk) {
        return new OrderCancelHandoff.Settings(wait, Duration.ofMillis(50), chunk, 1, Duration.ofSeconds(60));
    }

    /** 운영 배선과 같은 게이트웨이에 인계 요청자를 붙인다. */
    OrderGateway gateway(EngineBridge bridge, PartitionPlan plan, PartitionLeases leases,
                         OrderCancelHandoff.Settings settings, CancelHandoffCounters counters) {
        return gateway(bridge, plan, leases, settings, counters, new OrderQueryService(f.app));
    }

    OrderGateway gateway(EngineBridge bridge, PartitionPlan plan, PartitionLeases leases,
                         OrderCancelHandoff.Settings settings, CancelHandoffCounters counters, OrderQueryService orders) {
        OrderGateway gateway = new ApiConfig().orderGateway(f.app, bridge, markets, orders, new SimpleMeterRegistry(),
                new LeaderProtocolBoot(2), plan);
        gateway.attachCancelHandoff(new OrderCancelHandoff(repo, plan, leases, settings, counters));
        return gateway;
    }

    /** 노드의 브리지와 게이트를 쓰는 게이트웨이. */
    OrderGateway gateway(PartitionNode node, OrderCancelHandoff.Settings settings, CancelHandoffCounters counters) {
        return gateway(node.bridge, node.plan, node.leases, settings, counters);
    }

    /**
     * 어떤 파티션의 게이트도 열지 않은 요청자(엔진을 끈 관리 평면과 같다). 모든 주문을 소유 노드에 넘긴다. 브리지는 쓰이지
     * 않는다.
     */
    OrderGateway closedRequester(PartitionPlan plan, OrderCancelHandoff.Settings settings,
                                 CancelHandoffCounters counters) {
        PartitionLeases unstarted = TestPartitions.unstarted(plan);
        EngineBridge closed = new EngineBridge(null, new SingleWriterLoop(16, new MatchingEngineImpl(), events -> { },
                (afterSeq, limit) -> List.of(), fatal -> { }), new CommandPayloadMapper(new ObjectMapper()), unstarted,
                EngineBridge.NEVER_HALTED);
        return gateway(closed, plan, unstarted, settings, counters);
    }

    /** 노드의 인계 작업자. close 가 멈춘다. */
    CancelHandoffWorker worker(PartitionNode node, OrderGateway gateway, OrderCancelHandoff.Settings settings,
                               CancelHandoffCounters counters) {
        return worker(node, gateway, settings, counters, repo);
    }

    CancelHandoffWorker worker(PartitionNode node, OrderGateway gateway, OrderCancelHandoff.Settings settings,
                               CancelHandoffCounters counters, OrderCancelHandoffRepository repository) {
        CancelHandoffWorker worker = new CancelHandoffWorker(repository, gateway, node.leases, settings, counters);
        closing.add(worker);
        return worker;
    }

    /** 지정가 매수 주문을 노드에 넣고 프로젝션에 OPEN 으로 보일 때까지 기다린다. 주문의 external_id 를 돌려준다. */
    UUID openBuy(PartitionNode node, int market, long user) {
        OrderIntake intake = f.limit(market, user, OrderCommand.Side.BUY, "50000", "0.1");
        node.submit(intake);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(20))
                .until(() -> f.count("SELECT count(*) FROM orders WHERE id = " + intake.assignedOrderId()
                        + " AND status = 'OPEN'") == 1);
        return f.owner.sql("SELECT external_id FROM orders WHERE id = ?").param(intake.assignedOrderId())
                .query(UUID.class).single();
    }

    long openCount(long user) {
        return f.count("SELECT count(*) FROM orders WHERE user_id = " + user + " AND status IN ('OPEN','PARTIAL')");
    }

    String status(UUID order) {
        return f.owner.sql("SELECT status FROM orders WHERE external_id = ?").param(order).query(String.class).single();
    }

    List<OrderCancelHandoffRepository.State> states(long user) {
        return f.owner.sql("SELECT state FROM order_cancel_requests WHERE user_id = ? ORDER BY id").param(user)
                .query((rs, n) -> OrderCancelHandoffRepository.State.valueOf(rs.getString(1))).list();
    }

    JdbcClient owner() {
        return f.owner;
    }

    @Override
    public void close() throws Exception {
        for (AutoCloseable c : closing) {
            c.close();
        }
        markets.close();
    }
}

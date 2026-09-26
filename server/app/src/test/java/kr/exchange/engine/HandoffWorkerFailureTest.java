package kr.exchange.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.order.CancelHandoffCounters;
import kr.exchange.api.order.CancelHandoffWorker;
import kr.exchange.api.order.OrderCancelHandoff;
import kr.exchange.api.order.OrderCancelHandoffRepository;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderGatewayTestAccess;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.DbFailures;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인계 작업자의 예외 규칙(DB 설계 §5.4-d 3, S5 설계 4-4·6-2, 검토 대응 R2). 취소 전 조회가 한 번 더 읽은 뒤에도 DB 에 닿지 못하면
 * 그 주문과 남은 주문을 service_unavailable 로 적고, 결함 예외는 그 주문만 server_error 로 적는다. 끝내기의 DB 오류는 다시 한다.
 * 게이트가 열린 채 받은 리더 자격 거부는 같은 스레드가 이어 하되 같은 주문에서 세 번이면 too_many_requests 로 적는다. 행 처리
 * 밖으로 새는 예외가 나도 스레드는 살아 다음 행을 처리한다.
 */
class HandoffWorkerFailureTest {

    private static final String ENGINE = "matching-engine";

    @Test
    void workerRecordsOrderFailuresAndSurvivesLeaks() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_worker_failure", 2);
             PartitionNode node = new PartitionNode("owner", f, new PartitionNode.Options().protocol(2));
             HandoffHarness h = new HandoffHarness(f)) {
            node.start();
            awaitAccepting(node, ENGINE);
            long user = f.user("failure", new BigDecimal("100000000"));
            CancelHandoffCounters counters = CancelHandoffCounters.untracked();
            OrderCancelHandoff.Settings settings = HandoffHarness.settings(Duration.ofSeconds(1));
            FaultyOrders orders = new FaultyOrders(f);
            OrderGateway gateway = h.gateway(node.bridge, node.plan, node.leases, settings, counters, orders);
            FaultyRepository repo = new FaultyRepository(f);
            CancelHandoffWorker worker = h.worker(node, gateway, settings, counters, repo);
            AtomicBoolean leakOnce = new AtomicBoolean();
            OrderGatewayTestAccess.beforeHandoffComplete(worker, row -> {
                if (leakOnce.getAndSet(false)) {
                    throw new IllegalStateException("시험 주입: 결과 직렬화 실패");
                }
            });
            worker.start();

            // (a) 취소 전 조회가 08006 을 두 번 던진다. 그 주문과 남은 주문이 service_unavailable 이고 행은 DONE 이다.
            UUID a1 = h.openBuy(node, KRW_BTC, user);
            UUID a2 = h.openBuy(node, KRW_BTC, user);
            UUID a3 = h.openBuy(node, KRW_BTC, user);
            orders.failures.put(a2, new AtomicInteger(2));
            orders.failure = DbFailures::connectionFailure;
            UUID ra = insert(h, user, List.of(a1, a2, a3));
            UUID next = insert(h, user, List.of(h.openBuy(node, KRW_BTC, user)));
            JsonNode resultA = awaitResult(h, ra);
            assertEquals(List.of(a1.toString()), texts(resultA.path("canceled")));
            assertEquals(Map.of(a2.toString(), "service_unavailable", a3.toString(), "service_unavailable"),
                    failures(resultA));
            assertEquals(2, resultA.path("counts").path("unsubmitted").asInt());
            assertEquals("OPEN", h.status(a3), "시도하지 않아야 할 주문을 취소했다");
            awaitResult(h, next);
            assertEquals(1, gateway.readRetry().unreachable("order"), "한 번 더 읽지 않았다");

            // (b) 한 주문에서 결함 예외. 그 주문만 server_error 이고 뒤 주문은 취소되며 error 가 는다.
            UUID b1 = h.openBuy(node, KRW_BTC, user);
            UUID b2 = h.openBuy(node, KRW_BTC, user);
            orders.failures.put(b1, new AtomicInteger(1));
            orders.failure = () -> new IllegalArgumentException("시험 주입: 결함");
            double errorsBefore = counters.workerCount("error");
            JsonNode resultB = awaitResult(h, insert(h, user, List.of(b1, b2)));
            assertEquals(Map.of(b1.toString(), "server_error"), failures(resultB));
            assertEquals(List.of(b2.toString()), texts(resultB.path("canceled")));
            assertEquals(1, resultB.path("counts").path("errored").asInt());
            assertEquals(errorsBefore + 1, counters.workerCount("error"));

            // (c) 끝내기가 08006 을 한 번 던진다. 기간이 그대로이므로 다시 해 DONE 이다.
            repo.completeFailures.set(1);
            UUID c1 = h.openBuy(node, KRW_BTC, user);
            JsonNode resultC = awaitResult(h, insert(h, user, List.of(c1)));
            assertEquals(List.of(c1.toString()), texts(resultC.path("canceled")));
            assertEquals(0, repo.completeFailures.get());

            // (d) 게이트가 열린 채 리더 자격 거부(재확인이 참인 fencing 거부)가 한 번이면 이어 해 canceled 이고 resumed 가 는다.
            UUID d1 = h.openBuy(node, KRW_BTC, user);
            long d1Id = f.owner.sql("SELECT id FROM orders WHERE external_id = ?").param(d1).query(Long.class).single();
            AtomicInteger fencing = new AtomicInteger(1);
            node.appendFault = (term, draft) -> {
                if (draft.type() == CommandDraft.CommandType.CANCEL && draft.assignedOrderId() == d1Id
                        && fencing.getAndDecrement() > 0) {
                    throw DbFailures.fencing();
                }
            };
            double resumedBefore = counters.workerCount("resumed");
            JsonNode resultD = awaitResult(h, insert(h, user, List.of(d1)));
            assertEquals(List.of(d1.toString()), texts(resultD.path("canceled")));
            assertEquals(resumedBefore + 1, counters.workerCount("resumed"));
            assertTrue(node.accepting(ENGINE), "재확인이 참인데 게이트가 닫혔다");

            // 같은 주문에서 세 번 이어지면 로컬 일괄 취소처럼 too_many_requests 로 적고 넘어간다.
            UUID d2 = h.openBuy(node, KRW_BTC, user);
            long d2Id = f.owner.sql("SELECT id FROM orders WHERE external_id = ?").param(d2).query(Long.class).single();
            node.appendFault = (term, draft) -> {
                if (draft.type() == CommandDraft.CommandType.CANCEL && draft.assignedOrderId() == d2Id) {
                    throw DbFailures.fencing();
                }
            };
            JsonNode resultD3 = awaitResult(h, insert(h, user, List.of(d2)));
            node.appendFault = (term, draft) -> { };
            assertEquals(Map.of(d2.toString(), "too_many_requests"), failures(resultD3));
            assertEquals(1, resultD3.path("counts").path("contended").asInt());
            assertEquals("OPEN", h.status(d2));

            // (e) 행 처리 밖으로 새는 예외. 그 행은 CLAIMED 로 남고 스레드는 살아 다음 행을 처리한다.
            leakOnce.set(true);
            double leakErrors = counters.workerCount("error");
            UUID leaked = insert(h, user, List.of(h.openBuy(node, KRW_BTC, user)));
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> counters.workerCount("error") > leakErrors);
            assertEquals(OrderCancelHandoffRepository.State.CLAIMED, h.repo.byRequest(leaked).get(0).state());
            UUID after = insert(h, user, List.of(h.openBuy(node, KRW_BTC, user)));
            awaitResult(h, after);
            assertEquals(1, OrderGatewayTestAccess.aliveWorkerThreads(worker), "작업자 스레드가 죽었다");
        }
    }

    private static UUID insert(HandoffHarness h, long user, List<UUID> orders) {
        UUID request = UUID.randomUUID();
        h.repo.insert(request, user, OrderCancelHandoff.ORIGIN_CANCEL_ALL,
                List.of(new OrderCancelHandoffRepository.NewRow(ENGINE, orders)));
        return request;
    }

    private static JsonNode awaitResult(HandoffHarness h, UUID request) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() ->
                h.repo.byRequest(request).get(0).state() == OrderCancelHandoffRepository.State.DONE);
        return new ObjectMapper().readTree(h.repo.byRequest(request).get(0).result());
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, String> failures(JsonNode result) {
        Map<String, String> out = new java.util.HashMap<>();
        result.path("failed").forEach(n -> out.put(n.path("id").asText(), n.path("reason").asText()));
        return out;
    }

    /** 고른 주문의 취소 전 조회를 정한 횟수만큼 실패시키는 조회. */
    static final class FaultyOrders extends OrderQueryService {
        final Map<UUID, AtomicInteger> failures = new ConcurrentHashMap<>();
        volatile Supplier<RuntimeException> failure = DbFailures::connectionFailure;

        FaultyOrders(ReservationFixture f) {
            super(f.app);
        }

        @Override
        public Optional<OrderRow> byExternalId(UUID externalId) {
            AtomicInteger left = failures.get(externalId);
            if (left != null && left.getAndDecrement() > 0) {
                throw failure.get();
            }
            return super.byExternalId(externalId);
        }
    }

    /** 끝내기를 정한 횟수만큼 연결 끊김으로 끝내는 저장소. */
    static final class FaultyRepository extends OrderCancelHandoffRepository {
        final AtomicInteger completeFailures = new AtomicInteger();

        FaultyRepository(ReservationFixture f) {
            super(f.app, new TransactionTemplate(new JdbcTransactionManager(f.appPool)));
        }

        @Override
        public int complete(long id, String holder, long epoch, String resultJson) {
            if (completeFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw DbFailures.connectionFailure();
            }
            return super.complete(id, holder, epoch, resultJson);
        }
    }
}

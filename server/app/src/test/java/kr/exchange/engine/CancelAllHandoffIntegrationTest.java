package kr.exchange.engine;

import kr.exchange.api.order.CancelHandoffCounters;
import kr.exchange.api.order.CancelHandoffWorker;
import kr.exchange.api.order.OrderCancelHandoff;
import kr.exchange.api.order.OrderCancelHandoffRepository.State;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderGatewayTestAccess;
import kr.exchange.api.order.dto.CancelAllResponse;
import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW_XRP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 마켓 없는 일괄 취소의 파티션별 처리와 인계(DB 설계 §5.4-d, API 설계 §4-B-3, S5 설계 4-4·6-2).
 *
 * 한 DB 를 쓰는 두 게이트웨이다. 요청자는 게이트가 닫힌 노드(팔로워, 엔진을 끈 관리 평면)이고 작업자는 게이트가 열린 노드의
 * 실제 엔진·저널이다. 요청자는 넘긴 행을 기다리고, 가져가지 않은 몫은 포기해 partition_not_started 로, 시작했으나 끝내지 않은
 * 몫은 partition_pending 으로 알린다. 시작한 몫의 뒷부분은 포기하지 않는다.
 */
class CancelAllHandoffIntegrationTest {

    private static final String ENGINE = "matching-engine";

    /** (a) 작업자가 돌면 200 이고 모두 취소되며 행은 DONE, 잠긴 잔고는 주문 전 값으로 돌아온다. */
    @Test
    void ownerWorkerCancelsEverythingHandedOver() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_handoff_a", 2);
             PartitionNode node = new PartitionNode("owner", f, new PartitionNode.Options().protocol(2));
             HandoffHarness h = new HandoffHarness(f)) {
            node.start();
            awaitAccepting(node, ENGINE);
            long user = f.user("alice", new BigDecimal("100000000"));
            BigDecimal lockedBefore = f.locked(user, KRW);
            List<UUID> orders = List.of(h.openBuy(node, KRW_BTC, user), h.openBuy(node, KRW_BTC, user),
                    h.openBuy(node, KRW_ETH, user), h.openBuy(node, KRW_ETH, user));
            CancelHandoffCounters counters = CancelHandoffCounters.untracked();
            OrderGateway ownerGateway = h.gateway(node, HandoffHarness.settings(Duration.ofSeconds(5)), counters);
            h.worker(node, ownerGateway, HandoffHarness.settings(Duration.ofSeconds(5)), counters).start();
            OrderGateway requester = h.closedRequester(node.plan, HandoffHarness.settings(Duration.ofSeconds(5)),
                    counters);

            OrderGateway.BulkCancelResult result = requester.cancelAll(user, null);

            assertEquals(Set.copyOf(ids(orders)), Set.copyOf(result.canceledIds()), "인계한 주문이 모두 취소되지 않았다");
            assertTrue(result.failed().isEmpty(), "실패: " + result.failed());
            assertEquals(0, result.incomplete());
            assertEquals(0, result.localSubmits(), "게이트가 닫힌 요청자가 직접 submit 했다");
            assertEquals(List.of(State.DONE), h.states(user));
            assertEquals(0, h.openCount(user));
            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .until(() -> f.locked(user, KRW).compareTo(lockedBefore) == 0);
            assertEquals(1, counters.requests("done"));
            assertEquals(1, counters.workerCount("completed"));
        }
    }

    /**
     * (b) 작업자가 없으면 기다림이 끝날 때 포기하고 모두 partition_not_started 다. 포기한 행은 뒤에 작업자가 켜져도 점유되지
     * 않으므로 주문은 OPEN 으로 남는다. (c) 작업자가 첫 주문에서 멈추면 partition_pending 이고, 풀면 취소되고 행은 DONE 이다.
     */
    @Test
    void unstartedShareIsAbandonedAndStartedShareIsPending() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_handoff_bc", 2);
             PartitionNode node = new PartitionNode("owner", f, new PartitionNode.Options().protocol(2));
             HandoffHarness h = new HandoffHarness(f)) {
            node.start();
            awaitAccepting(node, ENGINE);
            long bob = f.user("bob", new BigDecimal("100000000"));
            List<UUID> bobs = List.of(h.openBuy(node, KRW_BTC, bob), h.openBuy(node, KRW_ETH, bob));
            CancelHandoffCounters counters = CancelHandoffCounters.untracked();
            OrderGateway requester = h.closedRequester(node.plan, HandoffHarness.settings(Duration.ofMillis(300)),
                    counters);

            // (b) 작업자 없음.
            OrderGateway.BulkCancelResult nobody = requester.cancelAll(bob, null);
            assertTrue(nobody.canceledIds().isEmpty());
            assertEquals(List.of("partition_not_started", "partition_not_started"), reasons(nobody));
            assertEquals(2, nobody.partitionNotStarted());
            assertEquals(2, nobody.incomplete());
            assertEquals(List.of(State.ABANDONED), h.states(bob));
            assertEquals(1, counters.requests("not_started"));

            OrderGateway ownerGateway = h.gateway(node, HandoffHarness.settings(Duration.ofSeconds(5)), counters);
            CancelHandoffWorker worker = h.worker(node, ownerGateway, HandoffHarness.settings(Duration.ofSeconds(5)),
                    counters);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean hold = new AtomicBoolean(true);
            OrderGatewayTestAccess.beforeHandoffOrder(worker, (row, index) -> {
                if (hold.get()) {
                    release.await(30, TimeUnit.SECONDS);
                }
            });
            worker.start();
            Thread.sleep(500);
            for (UUID order : bobs) {
                assertEquals("OPEN", h.status(order), "포기한 행의 주문이 뒤에 취소됐다");
            }

            // (c) 작업자가 첫 주문에서 멈춘다.
            long carol = f.user("carol", new BigDecimal("100000000"));
            List<UUID> carols = List.of(h.openBuy(node, KRW_BTC, carol), h.openBuy(node, KRW_ETH, carol));
            OrderGateway.BulkCancelResult stuck = requester.cancelAll(carol, null);
            assertEquals(List.of("partition_pending", "partition_pending"), reasons(stuck));
            assertEquals(2, stuck.partitionPending());
            assertEquals(List.of(State.CLAIMED), h.states(carol));
            hold.set(false);
            release.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> h.states(carol).equals(List.of(State.DONE)));
            for (UUID order : carols) {
                assertEquals("CANCELED", h.status(order));
            }
            for (UUID order : bobs) {
                assertEquals("OPEN", h.status(order), "포기한 행의 주문이 뒤에 취소됐다");
            }
        }
    }

    /** (d) P3 흉내. 요청자 노드는 p0 을 쥐어 p0 주문을 직접 취소하고, p1 주문은 p1 을 쥔 노드의 작업자가 취소한다. */
    @Test
    void p3LocalPartitionIsCanceledDirectlyAndTheOtherIsHandedOver() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_handoff_d", 2)) {
            f.db.setProtocol(3);
            try (PartitionNode a = new PartitionNode("node-a", f,
                         new PartitionNode.Options().preferred("matching-p0").participate("matching-p0"));
                 PartitionNode b = new PartitionNode("node-b", f,
                         new PartitionNode.Options().preferred("matching-p1").participate("matching-p1"));
                 HandoffHarness h = new HandoffHarness(f)) {
                a.start();
                b.start();
                awaitAccepting(a, "matching-p0");
                awaitAccepting(b, "matching-p1");
                long user = f.user("dave", new BigDecimal("100000000"));
                UUID p0 = h.openBuy(a, KRW_BTC, user);
                UUID p1a = h.openBuy(b, KRW_ETH, user);
                UUID p1b = h.openBuy(b, KRW_XRP, user);
                CancelHandoffCounters counters = CancelHandoffCounters.untracked();
                OrderGateway bGateway = h.gateway(b, HandoffHarness.settings(Duration.ofSeconds(5)), counters);
                h.worker(b, bGateway, HandoffHarness.settings(Duration.ofSeconds(5)), counters).start();
                OrderGateway aGateway = h.gateway(a, HandoffHarness.settings(Duration.ofSeconds(5)), counters);

                OrderGateway.BulkCancelResult result = aGateway.cancelAll(user, null);

                assertEquals(Set.of(p0.toString(), p1a.toString(), p1b.toString()), Set.copyOf(result.canceledIds()));
                assertTrue(result.failed().isEmpty(), "실패: " + result.failed());
                assertEquals(1, result.localSubmits(), "p0 주문을 직접 취소하지 않았다");
                List<String> resources = f.owner.sql("SELECT resource FROM order_cancel_requests WHERE user_id = ?")
                        .param(user).query(String.class).list();
                assertEquals(List.of("matching-p1"), resources, "p1 몫만 넘겨야 한다");
                // p1 주문의 취소 명령은 B 의 p1 기간 epoch 로 저널에 들어갔다.
                long p1Epoch = b.lease("matching-p1").currentTerm().orElseThrow().epoch();
                assertEquals(2, f.count("SELECT count(*) FROM command_journal WHERE command_type = 'CANCEL' AND user_id = "
                        + user + " AND market_id IN (2, 3) AND fencing_token = " + p1Epoch));
            }
        }
    }

    /**
     * (e) 행당 두 건으로 한 파티션 주문 여섯을 넘기고 작업자가 둘째 행에서 멈춘다. 한 요청에 DONE·CLAIMED·PENDING 이 섞이고 셋째
     * 행은 포기되지 않는다. 첫 행의 두 건은 canceled, 나머지 넷은 partition_pending 이고 풀면 여섯 모두 취소된다. (f) 그 뒤에 넣은
     * 다른 회원의 요청은 작업자가 바빠 기다림 안에 시작되지 않아 partition_not_started 이고 그 주문은 작업자가 한가해진 뒤에도
     * OPEN 이다.
     */
    @Test
    void aLargeHandoffKeepsItsTailAndQueuedRequestsAreNotStarted() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_handoff_ef", 2);
             PartitionNode node = new PartitionNode("owner", f, new PartitionNode.Options().protocol(2));
             HandoffHarness h = new HandoffHarness(f)) {
            node.start();
            awaitAccepting(node, ENGINE);
            long erin = f.user("erin", new BigDecimal("100000000"));
            List<UUID> erins = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                erins.add(h.openBuy(node, KRW_BTC, erin));
            }
            CancelHandoffCounters counters = CancelHandoffCounters.untracked();
            OrderCancelHandoff.Settings chunked = HandoffHarness.settings(Duration.ofMillis(1500), 2);
            OrderGateway ownerGateway = h.gateway(node, chunked, counters);
            CancelHandoffWorker worker = h.worker(node, ownerGateway, chunked, counters);
            CountDownLatch release = new CountDownLatch(1);
            // 둘째 행(셋째·넷째 주문)의 첫 주문 앞에서 멈춘다.
            OrderGatewayTestAccess.beforeHandoffOrder(worker, (row, index) -> {
                if (index == 0 && row.orderIds().get(0).equals(erins.get(2))) {
                    release.await(60, TimeUnit.SECONDS);
                }
            });
            worker.start();
            OrderGateway requester = h.closedRequester(node.plan, chunked, counters);

            OrderGateway.BulkCancelResult big = requester.cancelAll(erin, null);

            assertEquals(List.of(State.DONE, State.CLAIMED, State.PENDING), h.states(erin),
                    "셋째 행을 포기했거나 작업자가 둘째 행에서 멈추지 않았다");
            assertEquals(Set.of(erins.get(0).toString(), erins.get(1).toString()), Set.copyOf(big.canceledIds()));
            assertEquals(4, big.partitionPending());
            assertEquals(0, big.partitionNotStarted());

            // (f) 작업자가 멈춘 동안 들어온 다른 회원의 요청.
            long fay = f.user("fay", new BigDecimal("100000000"));
            UUID fays = h.openBuy(node, KRW_BTC, fay);
            OrderGateway.BulkCancelResult queued = requester.cancelAll(fay, null);
            assertEquals(List.of("partition_not_started"), reasons(queued));
            assertEquals(List.of(State.ABANDONED), h.states(fay));

            release.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(30))
                    .until(() -> h.states(erin).equals(List.of(State.DONE, State.DONE, State.DONE)));
            for (UUID order : erins) {
                assertEquals("CANCELED", h.status(order));
            }
            Thread.sleep(300);
            assertEquals("OPEN", h.status(fays), "포기한 요청의 주문이 취소됐다");
        }
    }

    private static List<String> ids(List<UUID> orders) {
        return orders.stream().map(UUID::toString).toList();
    }

    private static List<String> reasons(OrderGateway.BulkCancelResult result) {
        return result.failed().stream().map(CancelAllResponse.Failure::reason).collect(Collectors.toList());
    }
}

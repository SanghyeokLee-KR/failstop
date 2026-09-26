package kr.exchange.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.order.CancelHandoffCounters;
import kr.exchange.api.order.CancelHandoffWorker;
import kr.exchange.api.order.OrderCancelHandoff;
import kr.exchange.api.order.OrderCancelHandoffRepository;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderGatewayTestAccess;
import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인계 작업자의 기간 한정 실행(DB 설계 §5.4-d 3·5·7, S5 설계 4-4·6-2, 검토 대응 R1). 작업자는 주문마다 게이트가 점유 때와 같은
 * 기간으로 열려 있는지 보고, 아니면 행을 CLAIMED 로 둔 채 멈춘다. 그 행은 더 큰 epoch 의 작업자가 다시 점유한다. 리스를 잃은
 * 점유자(좀비)는 새 행을 점유하지 못하고, 리더 자격을 잃었다는 거부는 결과에 적지 않으며, 끝내기는 리스 확인으로 0행이다.
 * 그래서 옛 소유자의 실패가 결과로 굳지 않는다.
 */
class HandoffWorkerTermTest {

    private static final String ENGINE = "matching-engine";

    /** 행 처리 도중 기간이 끝나면 멈추고 행은 CLAIMED 다. 같은 노드가 새 기간을 얻으면 더 큰 epoch 로 다시 점유해 끝낸다. */
    @Test
    void aTermEndingMidRowStopsAndTheNextTermReclaims() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_worker_term", 2);
             PartitionNode node = new PartitionNode("owner", f, new PartitionNode.Options().protocol(2));
             HandoffHarness h = new HandoffHarness(f)) {
            node.start();
            awaitAccepting(node, ENGINE);
            long user = f.user("term", new BigDecimal("100000000"));
            List<UUID> orders = List.of(h.openBuy(node, KRW_BTC, user), h.openBuy(node, KRW_BTC, user),
                    h.openBuy(node, KRW_BTC, user));
            CancelHandoffCounters counters = CancelHandoffCounters.untracked();
            OrderCancelHandoff.Settings settings = HandoffHarness.settings(Duration.ofSeconds(1));
            OrderGateway gateway = h.gateway(node, settings, counters);
            CancelHandoffWorker worker = h.worker(node, gateway, settings, counters);
            CountDownLatch atSecond = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean hold = new AtomicBoolean(true);
            OrderGatewayTestAccess.beforeHandoffOrder(worker, (row, index) -> {
                if (index == 1 && hold.get()) {
                    atSecond.countDown();
                    release.await(30, TimeUnit.SECONDS);
                }
            });
            LeaderTerm first = node.lease(ENGINE).currentTerm().orElseThrow();
            UUID request = UUID.randomUUID();
            h.repo.insert(request, user, OrderCancelHandoff.ORIGIN_CANCEL_ALL,
                    List.of(new OrderCancelHandoffRepository.NewRow(ENGINE, orders)));
            worker.start();
            assertTrue(atSecond.await(20, TimeUnit.SECONDS), "작업자가 둘째 주문에 닿지 않았다");
            assertEquals("CANCELED", h.status(orders.get(0)));

            // 계획 강등(참여 끄기)이 게이트를 닫는다. 작업자는 둘째 주문 앞에서 기간이 바뀐 것을 보고 멈춘다.
            node.runtime(ENGINE).setParticipate(false);
            hold.set(false);
            release.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> counters.workerCount("stopped") >= 1);
            assertEquals(List.of(OrderCancelHandoffRepository.State.CLAIMED), h.states(user));
            assertEquals("OPEN", h.status(orders.get(1)), "끝난 기간으로 계속 처리했다");
            long rowId = h.repo.byRequest(request).get(0).id();
            // 강등은 DB 에 쓰지 않아 옛 기간의 리스 행이 TTL 까지 유효하다(DB 설계 §5.4-c 5). 그 동안 결과가 굳지 않은 것은
            // 작업자의 기간 확인 덕분이다. 리스가 만료된 뒤 옛 기간의 끝내기는 리스 확인으로 0행이다.
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> f.count("SELECT count(*) FROM leader_lease"
                    + " WHERE resource = '" + ENGINE + "' AND lease_until < clock_timestamp()") == 1);
            assertEquals(0, h.repo.complete(rowId, first.holder(), first.epoch(), "{}"), "끝난 기간으로 끝냈다");

            // 참여를 다시 켜면 리스 만료 뒤 새 epoch 를 얻고 작업자가 그 행을 다시 점유해 끝낸다.
            node.runtime(ENGINE).setParticipate(true);
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                    h.states(user).equals(List.of(OrderCancelHandoffRepository.State.DONE)));
            LeaderTerm second = node.lease(ENGINE).currentTerm().orElseThrow();
            assertTrue(second.epoch() > first.epoch());
            for (UUID order : orders) {
                assertEquals("CANCELED", h.status(order));
            }
            assertEquals(1, counters.workerCount("reclaimed"));
            assertEquals(second.epoch(), f.count("SELECT claimed_epoch FROM order_cancel_requests WHERE id = " + rowId));
        }
    }

    /** 다른 노드의 더 큰 epoch. 점유한 노드가 죽으면(리스를 반납하지 않음) 만료 뒤 다른 노드가 그 행을 다시 점유해 끝낸다. */
    @Test
    void anotherNodeWithALargerEpochFinishesADeadClaimersRow() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_worker_other", 2);
             PartitionNode a = new PartitionNode("node-a", f, new PartitionNode.Options().protocol(2));
             PartitionNode b = new PartitionNode("node-b", f, new PartitionNode.Options().protocol(2));
             HandoffHarness h = new HandoffHarness(f)) {
            a.start();
            awaitAccepting(a, ENGINE);
            b.start();
            long user = f.user("other", new BigDecimal("100000000"));
            List<UUID> orders = List.of(h.openBuy(a, KRW_BTC, user), h.openBuy(a, KRW_BTC, user));
            CancelHandoffCounters counters = CancelHandoffCounters.untracked();
            OrderCancelHandoff.Settings settings = HandoffHarness.settings(Duration.ofSeconds(1));
            CancelHandoffWorker aWorker = h.worker(a, h.gateway(a, settings, counters), settings, counters);
            CountDownLatch claimed = new CountDownLatch(1);
            CountDownLatch never = new CountDownLatch(1);
            OrderGatewayTestAccess.beforeHandoffOrder(aWorker, (row, index) -> {
                claimed.countDown();
                never.await(60, TimeUnit.SECONDS);
            });
            h.repo.insert(UUID.randomUUID(), user, OrderCancelHandoff.ORIGIN_CANCEL_ALL,
                    List.of(new OrderCancelHandoffRepository.NewRow(ENGINE, orders)));
            aWorker.start();
            assertTrue(claimed.await(20, TimeUnit.SECONDS));
            long aEpoch = a.lease(ENGINE).currentTerm().orElseThrow().epoch();

            a.close();
            awaitAccepting(b, ENGINE);
            h.worker(b, h.gateway(b, settings, counters), settings, counters).start();
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                    h.states(user).equals(List.of(OrderCancelHandoffRepository.State.DONE)));
            long bEpoch = b.lease(ENGINE).currentTerm().orElseThrow().epoch();
            assertTrue(bEpoch > aEpoch);
            for (UUID order : orders) {
                assertEquals("CANCELED", h.status(order));
            }
            never.countDown();
        }
    }

    /**
     * 좀비(R1). 게이트를 연 채 리스 행이 다른 holder·epoch 로 넘어간다. 점유 전이면 점유하지 못하고, 점유 뒤면 리더 자격 거부를
     * 결과에 적지 않아 행이 CLAIMED 로 남는다. 끝내기 직전에 잃으면 끝내기가 0행이다. 어느 경우든 더 큰 epoch 가 다시 점유해 주문을
     * 취소하고 행을 DONE 으로 끝낸다.
     */
    @Test
    void aZombieOwnersFailuresNeverBecomeTheResult() throws Exception {
        // 세 장면이 모두 리스를 잃게 하므로 파티션 치명 강등이 세 번이다. 차단기(기본 3회)가 막지 않게 상한을 올린다.
        PartitionNode.Options options = new PartitionNode.Options().protocol(2);
        options.maxDemotions = 10;
        try (ReservationFixture f = ReservationFixture.create("pml_worker_zombie", 2);
             PartitionNode node = new PartitionNode("owner", f, options);
             HandoffHarness h = new HandoffHarness(f)) {
            node.start();
            awaitAccepting(node, ENGINE);
            long user = f.user("zombie", new BigDecimal("100000000"));
            CancelHandoffCounters counters = CancelHandoffCounters.untracked();
            OrderCancelHandoff.Settings settings = HandoffHarness.settings(Duration.ofSeconds(1));
            OrderGateway gateway = h.gateway(node, settings, counters);
            CancelHandoffWorker worker = h.worker(node, gateway, settings, counters);
            worker.start();

            // 점유 전에 잃는다. 리스 행이 넘어간 뒤 넣은 행은 점유되지 않는다.
            UUID before = h.openBuy(node, KRW_BTC, user);
            f.db.seedLease(ENGINE, "someone-else", 9_100_000, 2);
            UUID request = UUID.randomUUID();
            h.repo.insert(request, user, OrderCancelHandoff.ORIGIN_CANCEL_ALL,
                    List.of(new OrderCancelHandoffRepository.NewRow(ENGINE, List.of(before))));
            Thread.sleep(250);
            assertEquals(OrderCancelHandoffRepository.State.PENDING, h.repo.byRequest(request).get(0).state(),
                    "리스를 잃은 점유자가 점유했다");
            awaitDone(h, request);
            assertCanceledOnly(h, request, before);

            // 점유 뒤에 잃는다. 리더 자격 거부는 결과에 적지 않고 행이 CLAIMED 로 남았다가 새 기간이 끝낸다.
            awaitAccepting(node, ENGINE);
            UUID after = h.openBuy(node, KRW_BTC, user);
            CountDownLatch claimed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OrderGatewayTestAccess.beforeHandoffOrder(worker, (row, index) -> {
                if (row.orderIds().contains(after)) {
                    claimed.countDown();
                    release.await(30, TimeUnit.SECONDS);
                }
            });
            UUID second = UUID.randomUUID();
            h.repo.insert(second, user, OrderCancelHandoff.ORIGIN_CANCEL_ALL,
                    List.of(new OrderCancelHandoffRepository.NewRow(ENGINE, List.of(after))));
            assertTrue(claimed.await(20, TimeUnit.SECONDS));
            f.db.seedLease(ENGINE, "someone-else", 9_200_000, 2);
            release.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> counters.workerCount("stopped") >= 1);
            assertEquals(OrderCancelHandoffRepository.State.CLAIMED, h.repo.byRequest(second).get(0).state(),
                    "리스를 잃은 점유자의 실패가 결과로 굳었다");
            OrderGatewayTestAccess.beforeHandoffOrder(worker, (row, index) -> { });
            awaitDone(h, second);
            assertCanceledOnly(h, second, after);

            // 끝내기 직전에 잃는다. 행의 주문이 이미 종결돼 쓰기가 없으므로 게이트는 열린 채다. 끝내기는 리스 확인으로 0행이다.
            awaitAccepting(node, ENGINE);
            UUID canceled = h.openBuy(node, KRW_BTC, user);
            gateway.cancel(user, canceled);
            AtomicBoolean moveOnce = new AtomicBoolean(true);
            OrderGatewayTestAccess.beforeHandoffComplete(worker, row -> {
                if (row.orderIds().contains(canceled) && moveOnce.getAndSet(false)) {
                    try {
                        f.db.seedLease(ENGINE, "someone-else", 9_300_000, 2);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
            double lostBefore = counters.workerCount("complete_lost");
            UUID third = UUID.randomUUID();
            h.repo.insert(third, user, OrderCancelHandoff.ORIGIN_CANCEL_ALL,
                    List.of(new OrderCancelHandoffRepository.NewRow(ENGINE, List.of(canceled))));
            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .until(() -> counters.workerCount("complete_lost") > lostBefore);
            OrderGatewayTestAccess.beforeHandoffComplete(worker, row -> { });
            awaitDone(h, third);
            assertCanceledOnly(h, third, canceled);
        }
    }

    private static void awaitDone(HandoffHarness h, UUID request) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100)).until(() ->
                h.repo.byRequest(request).get(0).state() == OrderCancelHandoffRepository.State.DONE);
    }

    /** 행의 결과가 그 주문의 취소 하나뿐이고 실패가 없다. 옛 소유자의 거부가 적히지 않았다. */
    private static void assertCanceledOnly(HandoffHarness h, UUID request, UUID order) throws Exception {
        JsonNode result = new ObjectMapper().readTree(h.repo.byRequest(request).get(0).result());
        assertEquals(1, result.path("canceled").size(), "결과: " + result);
        assertEquals(order.toString(), result.path("canceled").get(0).asText());
        assertEquals(0, result.path("failed").size(), "옛 소유자의 실패가 결과에 적혔다: " + result);
        assertEquals("CANCELED", h.status(order));
    }
}

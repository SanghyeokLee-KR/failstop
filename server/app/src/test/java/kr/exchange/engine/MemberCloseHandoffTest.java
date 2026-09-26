package kr.exchange.engine;

import kr.exchange.admin.MemberAdminService;
import kr.exchange.api.order.CancelHandoffCounters;
import kr.exchange.api.order.CancelHandoffWorker;
import kr.exchange.api.order.OrderCancelHandoff;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderGatewayTestAccess;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.DbFailures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 관리 평면의 회원 폐쇄(API 설계 v2.57 §6-E, S5 설계 2-4·4-4·6-2). 관리 평면은 엔진을 끈 노드라 어떤 파티션의 게이트도 열지
 * 않는다. 종전에는 폐쇄의 취소가 모두 저널 전에 거부돼 미체결이 있으면 늘 409(service_unavailable)였다. S5 부터는 파티션마다
 * 소유 노드에 넘기고, 소유 노드가 처리한 결과의 사유별 수를 합쳐 미완료를 센다.
 *
 * P3 흉내 두 파티션이다. 노드 A 가 p0(KRW-BTC), 노드 B 가 p1(KRW-ETH)을 쥐고 각자 인계 작업자를 돌린다.
 */
class MemberCloseHandoffTest {

    @Test
    void closeHandsOverEveryPartitionAndCountsTheOwnersReasons() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_close_handoff", 2)) {
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
                CancelHandoffCounters counters = CancelHandoffCounters.untracked();
                OrderCancelHandoff.Settings settings = HandoffHarness.settings(Duration.ofSeconds(3));
                OrderGateway aGateway = h.gateway(a, settings, counters);
                OrderGateway bGateway = h.gateway(b, settings, counters);
                CancelHandoffWorker aWorker = h.worker(a, aGateway, settings, counters);
                CancelHandoffWorker bWorker = h.worker(b, bGateway, settings, counters);
                aWorker.start();
                bWorker.start();
                // 관리 평면: 엔진을 끄고 게이트를 열지 않은 노드. 대응은 P3 계획으로 읽는다.
                OrderGateway admin = h.closedRequester(PartitionPlan.partitioned(f.app), settings, counters);
                MemberAdminService members = new MemberAdminService(f.owner,
                        new TransactionTemplate(new JdbcTransactionManager(f.ownerPool)), admin);

                // 작업자 동작: 두 파티션의 주문이 모두 취소되고 폐쇄는 미완료 없이 끝난다(컨트롤러의 200).
                Member working = member(f, h, a, b, "working");
                MemberAdminService.Transition done = members.close(working.external);
                assertEquals(0, done.pendingOrders(), "미완료: " + done.pendingReasons());
                assertEquals(2, done.canceledOrders());
                assertEquals("CANCELED", h.status(working.p0));
                assertEquals("CANCELED", h.status(working.p1));

                // 인계 대상 주문 하나가 정지 마켓이다. 소유 노드가 적은 market_suspended 를 합쳐 409 다(R9).
                Member halted = member(f, h, a, b, "halted");
                a.haltedMarkets.add(KRW_BTC);
                MemberAdminService.Transition suspended = members.close(halted.external);
                a.haltedMarkets.remove(KRW_BTC);
                assertEquals(Map.of("market_suspended", 1), suspended.pendingReasons());
                assertEquals(1, suspended.pendingOrders());
                assertEquals(1, suspended.canceledOrders());
                assertEquals("OPEN", h.status(halted.p0));
                assertEquals("CANCELED", h.status(halted.p1));

                // 작업자 쪽 마켓 접수 경합(append 트랜잭션의 55P03). 확정 미접수라 too_many_requests 다.
                Member contended = member(f, h, a, b, "contended");
                a.appendPool.failNextPrologue(DbFailures.serverError("55P03", "canceling statement due to lock timeout"));
                MemberAdminService.Transition busy = members.close(contended.external);
                assertEquals(Map.of("too_many_requests", 1), busy.pendingReasons());
                assertEquals("OPEN", h.status(contended.p0));

                // 작업자 쪽 append 의 DB 연결 장애. 재조회가 DB 에 닿으므로 그 주문만 service_unavailable 이다.
                Member outage = member(f, h, a, b, "outage");
                AtomicBoolean failOnce = new AtomicBoolean(true);
                a.appendFault = (term, draft) -> {
                    if (draft.type() == CommandDraft.CommandType.CANCEL && failOnce.getAndSet(false)) {
                        throw DbFailures.rollbackFailed(DbFailures.connectionFailure());
                    }
                };
                MemberAdminService.Transition unavailable = members.close(outage.external);
                a.appendFault = (term, draft) -> { };
                assertEquals(Map.of("service_unavailable", 1), unavailable.pendingReasons());
                assertEquals("CANCELED", h.status(outage.p1));

                // 작업자가 멈춤: 시작했으나 끝내지 않은 몫은 partition_pending 이고 풀면 취소된다.
                Member stalled = member(f, h, a, b, "stalled");
                CountDownLatch release = new CountDownLatch(1);
                OrderGatewayTestAccess.beforeHandoffOrder(bWorker, (row, index) -> {
                    if (row.orderIds().contains(stalled.p1)) {
                        release.await(30, TimeUnit.SECONDS);
                    }
                });
                MemberAdminService.Transition pending = members.close(stalled.external);
                assertEquals(Map.of("partition_pending", 1), pending.pendingReasons());
                assertEquals("CANCELED", h.status(stalled.p0));
                release.countDown();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20))
                        .until(() -> "CANCELED".equals(h.status(stalled.p1)));
                OrderGatewayTestAccess.beforeHandoffOrder(bWorker, (row, index) -> { });

                // 작업자 없음: 소유 노드가 기다림 안에 시작하지 않은 몫은 포기되고 partition_not_started 다. 주문은 열려 있다.
                aWorker.close();
                bWorker.close();
                Member orphan = member(f, h, a, b, "orphan");
                MemberAdminService.Transition notStarted = members.close(orphan.external);
                assertEquals(Map.of("partition_not_started", 2), notStarted.pendingReasons());
                assertEquals(2, notStarted.pendingOrders());
                assertEquals("CLOSED", f.owner.sql("SELECT status FROM users WHERE external_id = ?::uuid")
                        .param(orphan.external).query(String.class).single());
                aWorker.start();
                bWorker.start();
                Thread.sleep(500);
                assertEquals("OPEN", h.status(orphan.p0), "포기한 몫이 뒤에 취소됐다");
                assertEquals("OPEN", h.status(orphan.p1), "포기한 몫이 뒤에 취소됐다");
                // 새 승인으로 다시 부르면(CLOSED->CLOSED) 남은 주문을 다시 넘겨 취소한다.
                MemberAdminService.Transition retried = members.close(orphan.external);
                assertEquals(0, retried.pendingOrders(), "미완료: " + retried.pendingReasons());
                assertEquals(2, retried.canceledOrders());
            }
        }
    }

    private record Member(long id, String external, UUID p0, UUID p1) {
    }

    private static final AtomicInteger NAMES = new AtomicInteger();

    /** p0(KRW-BTC)과 p1(KRW-ETH)에 미체결 하나씩을 가진 회원. */
    private static Member member(ReservationFixture f, HandoffHarness h, PartitionNode a, PartitionNode b, String name) {
        long id = f.user(name + NAMES.incrementAndGet(), new BigDecimal("100000000"));
        String external = f.owner.sql("SELECT external_id::text FROM users WHERE id = ?").param(id)
                .query(String.class).single();
        return new Member(id, external, h.openBuy(a, KRW_BTC, id), h.openBuy(b, KRW_ETH, id));
    }
}

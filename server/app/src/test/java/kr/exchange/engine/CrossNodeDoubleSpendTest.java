package kr.exchange.engine;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.transfer.TransferService;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.SqlErrors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.S7Cutover.P0;
import static kr.exchange.engine.S7Cutover.P1;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 교차 노드 이중 지출(ADR 0034 S7 관문 E12 의 시험 판, S7 설계 5-2 Q1·6-2). P3 에서 app-1(p0)과 app-2(p1)가 같은 사용자의 KRW 를
 * 쓴다. 매 회 가용 KRW 를 X 로 맞추고 장벽으로 두 행위를 동시에 푼다. 짝수 회는 KRW-BTC 매수(app-1)와 KRW-ETH 매수(app-2),
 * 홀수 회는 한 마켓 매수와 출금이다. 두 행위 모두 X 의 80% 를 써서 매 회 정확히 하나만 성공한다. 진 쪽은 잔고 행 CHECK(23514,
 * chk_balance_nonneg)로 트랜잭션 전체가 롤백된 거절이고 게이트웨이가 400·422 insufficient_funds 로 바꾸는 예외다.
 *
 * 두 노드의 예약은 저널 append 와 같은 트랜잭션에서 같은 잔고 행을 잠그므로 행 잠금이 직렬화한다(ADR 0034 결정 5). 그래서 정산
 * 단계의 23514 가 0 이고, 교착과 파티션 치명 강등이 없다. 회 수는 -Dpml.crossNodeDoubleSpend.rounds 로 바꾼다(빌드 기본 200,
 * 관문 기록 1,000).
 */
class CrossNodeDoubleSpendTest {

    private static final Logger log = LoggerFactory.getLogger(CrossNodeDoubleSpendTest.class);
    private static final int ROUNDS = Integer.getInteger("pml.crossNodeDoubleSpend.rounds", 200);
    private static final BigDecimal X = new BigDecimal("10000");
    private static final String SPEND = "8000";

    @Test
    void exactlyOneOfTwoConcurrentSpendsOnTwoNodesWinsEveryRound() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("s7_cross_spend", 2)) {
            S7Cutover.latch(f);
            S7Cutover.switchTo(f, 3);
            long user = f.user("spender", X);
            TransferService transfers = new TransferService(f.app, f.appTx);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            PartitionNode a = new PartitionNode("app-1", f, new PartitionNode.Options().preferred(P0));
            PartitionNode b = new PartitionNode("app-2", f, new PartitionNode.Options().preferred(P1));
            try {
                a.start();
                b.start();
                awaitAccepting(a, P0);
                awaitAccepting(b, P1);
                long deadlocksBefore = deadlocks(f);
                long insufficientBefore = a.settlement.insufficientFunds() + b.settlement.insufficientFunds();
                int orderRejections = 0;
                int withdrawRejections = 0;
                for (int round = 0; round < ROUNDS; round++) {
                    assertEquals(0, X.compareTo(f.available(user, KRW)), "회 시작 가용이 X 가 아니다: round=" + round);
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    CompletableFuture<Outcome> first;
                    CompletableFuture<Outcome> second;
                    if (round % 2 == 0) {
                        first = CompletableFuture.supplyAsync(() -> buy(f, a, barrier, KRW_BTC, user), pool);
                        second = CompletableFuture.supplyAsync(() -> buy(f, b, barrier, KRW_ETH, user), pool);
                    } else {
                        boolean p0 = round % 4 == 1;
                        first = CompletableFuture.supplyAsync(
                                () -> buy(f, p0 ? a : b, barrier, p0 ? KRW_BTC : KRW_ETH, user), pool);
                        second = CompletableFuture.supplyAsync(() -> withdraw(transfers, barrier, user), pool);
                    }
                    List<Outcome> outcomes = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
                    assertEquals(1, outcomes.stream().filter(Outcome::success).count(), "round=" + round + " " + outcomes);
                    for (Outcome o : outcomes) {
                        if (o.success()) {
                            undo(f, transfers, a, b, user, o);
                        } else if (o.withdraw()) {
                            assertEquals("insufficient_funds:422", o.reason(), o.toString());
                            withdrawRejections++;
                        } else {
                            assertEquals("insufficient_funds", o.reason(), "잔고 부족이 아닌 주문 거절: " + o);
                            orderRejections++;
                        }
                    }
                    Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(20))
                            .until(() -> X.compareTo(f.available(user, KRW)) == 0);
                }
                assertTrue(orderRejections > 0, "주문 쪽 잔고 부족 거절이 한 번도 없다(경합이 나지 않았다)");
                assertEquals(ROUNDS, orderRejections + withdrawRejections);
                assertEquals(insufficientBefore, a.settlement.insufficientFunds() + b.settlement.insufficientFunds(),
                        "정산이 잔고 부족(23514)으로 끝났다");
                // 통계 누적은 세션이 쉴 때 늦게 반영된다. S3 의 단일 리더 판(SingleLeaderDoubleSpendTest)처럼 기다린 뒤 읽는다.
                Thread.sleep(11_000);
                assertEquals(deadlocksBefore, deadlocks(f), "교착이 났다");
                for (PartitionNode node : List.of(a, b)) {
                    for (String resource : node.plan.partitions()) {
                        assertTrue(node.runtime(resource).demotions().isEmpty(),
                                node.name + " " + resource + " 강등: " + node.runtime(resource).demotions());
                    }
                }
                assertTrue(a.accepting(P0) && b.accepting(P1), "두 노드가 선호 파티션을 계속 쥐지 않았다");
                S7Cutover.awaitDrained(f);
                for (InvariantCheck check : new InvariantCheck[] {InvariantCheck.C01_ASSET_CONSERVATION,
                        InvariantCheck.C02_BAL_VS_LEDGER, InvariantCheck.C14_LOCKED_RESERVATION,
                        InvariantCheck.C31_RESERVATION_LINK, InvariantCheck.C30_FENCING_RESOURCE_MATCH}) {
                    assertEquals(0L, f.invariant(check), check.name());
                }
                log.info("교차 노드 이중 지출: 회 {}, 주문 거절 {}, 출금 거절 {}", ROUNDS, orderRejections, withdrawRejections);
            } finally {
                a.close();
                b.close();
                pool.shutdownNow();
            }
        }
    }

    private record Outcome(boolean success, boolean withdraw, int market, long orderId, String withdrawId,
                           String reason) {
    }

    private static Outcome buy(ReservationFixture f, PartitionNode node, CyclicBarrier barrier, int market, long user) {
        OrderIntake intake = f.limit(market, user, OrderCommand.Side.BUY, SPEND, "1");
        try {
            barrier.await(30, TimeUnit.SECONDS);
            node.submit(intake);
            return new Outcome(true, false, market, intake.assignedOrderId(), null, null);
        } catch (RuntimeException e) {
            // 게이트웨이(OrderGateway)는 이 판정으로 400 insufficient_funds 를 낸다.
            return new Outcome(false, false, market, intake.assignedOrderId(), null,
                    SqlErrors.isInsufficientFunds(e) ? "insufficient_funds" : e.toString());
        } catch (Exception e) {
            return new Outcome(false, false, market, intake.assignedOrderId(), null, e.toString());
        }
    }

    private static Outcome withdraw(TransferService transfers, CyclicBarrier barrier, long user) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
            TransferService.Result r = transfers.request(user, "WITHDRAW", "KRW", SPEND,
                    "s7-wd-" + UUID.randomUUID().toString().substring(0, 18));
            return new Outcome(true, true, 0, 0, String.valueOf(r.view().get("external_id")), null);
        } catch (ApiException e) {
            return new Outcome(false, true, 0, 0, null, e.name() + ":" + e.status());
        } catch (Exception e) {
            return new Outcome(false, true, 0, 0, null, e.toString());
        }
    }

    /** 성공한 주문은 소유 노드로 취소하고 출금은 본인 취소해 다음 회의 X 를 되돌린다. */
    private static void undo(ReservationFixture f, TransferService transfers, PartitionNode a, PartitionNode b, long user,
                             Outcome o) {
        if (o.withdraw()) {
            transfers.cancel(o.withdrawId(), user);
        } else {
            PartitionNode owner = o.market() == KRW_BTC ? a : b;
            owner.submit(f.cancel(o.market(), user, o.orderId()));
        }
    }

    private static long deadlocks(ReservationFixture f) {
        return f.count("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()");
    }
}

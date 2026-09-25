package kr.exchange.ledger;

import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.SqlErrors;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정산 잠금 계획(ADR 0034 S3, S3 설계 4-13·11절 R6, DB 설계 §5.0 v2.54). 정산 배치는 원장 게시를 여러 번 하므로 게시 하나
 * 안의 오름차순만으로는 P2 접수 예약과 순환이 생긴다. 정산은 첫 게시 직전에 배치가 게시할 수 있는 잔고 행 전체를 한 번에
 * 오름차순으로 잡는다. 앱 로그인으로 실제 append 와 실제 정산을 돈다.
 */
class SettlementLockPlanTest {

    private static final OrderCommand.Side BUY = OrderCommand.Side.BUY;
    private static final OrderCommand.Side SELL = OrderCommand.Side.SELL;

    /**
     * (1) 교착 재현과 제거. alice 의 시장가 매도가 bob 의 매수와 부분 체결되고 잔여가 취소되는 배치를 정산한다. 첫 TRADE 게시
     * 뒤 장벽에서 멈춘 동안 alice 가 같은 마켓에서 매도를 낸다. 계획이 있으면 접수가 정산 커밋까지 기다리고 끝나며 교착이
     * 없다. 계획을 끈 판은 같은 장면이 40P01 을 낸다(양성 축, 시험이 교착을 잡을 수 있다).
     */
    @Test
    void lockPlanRemovesTheSettlementIntakeDeadlock() throws Exception {
        Outcome withPlan = deadlockScene("lock_plan_on", true);
        assertEquals(0, withPlan.settlementDeadlocks, "계획을 켰는데 정산이 교착 희생이 됐다");
        assertEquals(null, withPlan.intakeError, "계획을 켰는데 접수가 실패했다");
        assertEquals(0, withPlan.lockPlanMisses);
        assertEquals(0, withPlan.deadlockDelta, "계획을 켰는데 pg_stat_database.deadlocks 가 늘었다");

        Outcome withoutPlan = deadlockScene("lock_plan_off", false);
        long intake40P01 = withoutPlan.intakeError != null
                && "40P01".equals(SqlErrors.sqlState(withoutPlan.intakeError)) ? 1 : 0;
        assertTrue(withoutPlan.settlementDeadlocks + intake40P01 >= 1,
                "계획을 끈 판에서 교착이 나지 않았다. 장면이 교착을 만들지 못하면 위 판정이 공허하다");
        assertTrue(withoutPlan.deadlockDelta >= 1, "교착 계수가 늘지 않았다");
    }

    private record Outcome(long settlementDeadlocks, Throwable intakeError, long lockPlanMisses, long deadlockDelta) {
    }

    private static Outcome deadlockScene(String prefix, boolean plan) throws Exception {
        try (ReservationFixture f = ReservationFixture.create(prefix, 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            long bob = f.user("bob", new BigDecimal("1000000"));
            f.deposit(alice, BTC, new BigDecimal("1"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            SettlementService settlement = f.settlement(lease);
            settlement.setLockPlanEnabled(plan);
            MatchingEngineImpl engine = new MatchingEngineImpl();
            f.submit(appender, engine, settlement, f.limit(KRW_BTC, bob, BUY, "50000", "0.1"));
            List<EngineEvent> batch = f.journalAndApply(appender, engine, f.marketSell(KRW_BTC, alice, "0.3"));
            assertEquals(List.of(EngineEvent.EventType.ACCEPTED, EngineEvent.EventType.TRADE,
                    EngineEvent.EventType.CANCELED), batch.stream().map(EngineEvent::type).toList());
            long deadlocksBefore = deadlocks(f);

            CountDownLatch paused = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            AtomicBoolean first = new AtomicBoolean(true);
            settlement.setPostHook((kind, cmdSeq) -> {
                if ("TRADE".equals(kind) && first.compareAndSet(true, false)) {
                    paused.countDown();
                    try {
                        assertTrue(resume.await(30, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
            CompletableFuture<Void> settling = CompletableFuture.runAsync(() -> settlement.appendAndSettle(batch));
            assertTrue(paused.await(30, TimeUnit.SECONDS), "정산이 첫 TRADE 게시에 닿지 않았다");
            OrderIntake second = f.limit(KRW_BTC, alice, SELL, "60000", "0.1");
            CompletableFuture<Throwable> intake = CompletableFuture.supplyAsync(() -> {
                try {
                    f.append(appender, second);
                    return null;
                } catch (DataAccessException e) {
                    return e;
                }
            });
            // 접수가 잔고 행에서 기다리는 표본(양성 축). 계획이 있으면 AVAILABLE 에서, 없으면 LOCKED 에서 기다린다.
            assertTrue(waitingIntake(f), "접수가 잔고 행을 기다리지 않았다");
            resume.countDown();
            settling.get(60, TimeUnit.SECONDS);
            Throwable intakeError = intake.get(60, TimeUnit.SECONDS);
            long delta = plan ? settledDeadlockDelta(f, deadlocksBefore, false)
                    : settledDeadlockDelta(f, deadlocksBefore, true);
            if (plan) {
                assertTrue(settlement.lockPlansTaken() > 0, "잠금 계획이 돌지 않았다");
                // 두 트랜잭션 모두 끝났다. 매도 0.3 예약, 0.1 체결, 0.2 해제, 새 매도 0.1 예약.
                assertEquals(0, new BigDecimal("0.8").compareTo(f.available(alice, BTC)));
                assertEquals(0, f.invariant(InvariantCheck.C14_LOCKED_RESERVATION));
            }
            assertEquals(0, f.invariant(InvariantCheck.C01_ASSET_CONSERVATION));
            assertEquals(0, f.invariant(InvariantCheck.C02_BAL_VS_LEDGER));
            return new Outcome(settlement.transientRetries("40P01"), intakeError, settlement.lockPlanMisses(), delta);
        }
    }

    /**
     * (2) 계획 포함성. 고정 시드 난수 명령 2,000개(지정가·시장가·IOC·FOK·POST_ONLY, 자기 체결 방지, 취소, 사용자 넷, 마켓
     * 둘)를 P2 에서 흘린다. 모든 게시 계정이 계획 안이고 C01·C02·C14' 가 0 이다.
     */
    @Test
    void randomFlowNeverPostsOutsideThePlan() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("lock_plan_flow", 2)) {
            Flow flow = new Flow(f, 20260924L);
            flow.run(2_000);
            assertTrue(flow.settlement.lockPlansTaken() > 500, "계획이 거의 돌지 않았다: " + flow.settlement.lockPlansTaken());
            assertTrue(flow.trades() > 100, "체결이 적어 계획의 maker 행이 시험되지 않았다: " + flow.trades());
            assertEquals(0, flow.settlement.lockPlanMisses(), "게시가 잠금 계획 밖 계정을 썼다");
            assertEquals(0, flow.settlement.insufficientFunds());
            assertEquals(0, f.invariant(InvariantCheck.C01_ASSET_CONSERVATION));
            assertEquals(0, f.invariant(InvariantCheck.C02_BAL_VS_LEDGER));
            assertEquals(0, f.invariant(InvariantCheck.C14_LOCKED_RESERVATION));
            assertEquals(0, f.invariant(InvariantCheck.valueOf("C31_RESERVATION_LINK")));
        }
    }

    /**
     * (4) 계획 밖 게시 계수의 양성 축(S3 구현 검토). 계획이 게시 계정을 빠뜨리면 exchange.settlement.lock_plan.miss 가 오른다.
     * 운영 코드에는 계획이 게시 계정을 빠뜨리는 경로가 없어 시험 주입점으로 수수료 계정을 계획에서 뺀다. 이 사례가 없으면 (2)와
     * 이중 지출 시험, 스택 관문의 '계획 밖 게시 0' 판정은 탐지 코드가 깨져도 통과한다. 계수는 교착 가능성 신호일 뿐이라 정산은
     * 끝나고 원장은 맞다.
     */
    @Test
    void lockPlanMissCounterRisesWhenAPostLeavesThePlan() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("lock_plan_miss", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            long bob = f.user("bob", new BigDecimal("1000000"));
            f.deposit(alice, BTC, new BigDecimal("1"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            SettlementService settlement = f.settlement(lease);
            MatchingEngineImpl engine = new MatchingEngineImpl();
            f.submit(appender, engine, settlement, f.limit(KRW_BTC, bob, BUY, "50000", "0.1"));
            assertEquals(0, settlement.lockPlanMisses(), "준비 단계에서 계획 밖 게시가 났다");

            // 수수료 계정 집합은 V55 뒤 샤드 1(잔고 0, P3 전용)까지 담는다. 이 배치(P2)는 샤드 0 에만 게시하므로 집합을 모두
            // 계획에서 빼도 단언(계획 밖 게시가 수수료 leg 수만큼 센다)은 그대로 선다(ADR 0034 S7, S7 설계 6-5).
            java.util.Set<Long> fee = new java.util.HashSet<>(f.owner.sql(
                            "SELECT id FROM accounts WHERE owner_type = 'SYSTEM' AND purpose = 'FEE_INCOME'")
                    .query(Long.class).list());
            assertTrue(fee.size() >= 2, "수수료 계정이 없다: " + fee);
            settlement.setLockPlanTrim(fee::contains);
            long takenBefore = settlement.lockPlansTaken();
            List<EngineEvent> settled = f.submit(appender, engine, settlement, f.marketSell(KRW_BTC, alice, "0.3"));

            assertTrue(settled.stream().anyMatch(e -> e.type() == EngineEvent.EventType.TRADE), "체결이 없다");
            long feeLegs = f.count("""
                    SELECT count(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
                    WHERE t.business_event_type = 'TRADE' AND e.entry_type = 'FEE'
                    """);
            assertTrue(feeLegs >= 1, "체결 게시에 수수료 분개가 없어 이 사례가 공허하다");
            assertEquals(feeLegs, settlement.lockPlanMisses(), "계획에서 뺀 수수료 계정을 쓴 게시를 세지 않았다");
            assertEquals(takenBefore + 1, settlement.lockPlansTaken(), "계획이 돌지 않았다");
            assertEquals(0, settlement.insufficientFunds());
            assertEquals(0, f.invariant(InvariantCheck.C01_ASSET_CONSERVATION));
            assertEquals(0, f.invariant(InvariantCheck.C02_BAL_VS_LEDGER));
            assertEquals(0, f.invariant(InvariantCheck.C14_LOCKED_RESERVATION));
        }
    }

    /** (3) P1 에서도 계획이 돌고 결과 잔고가 계획을 끈 판과 같다. 계획은 잠그기만 한다. */
    @Test
    void p1PlanRunsAndLeavesTheSameBalances() throws Exception {
        String on;
        String off;
        long plansTaken;
        try (ReservationFixture f = ReservationFixture.create("lock_plan_p1_on", 1)) {
            Flow flow = new Flow(f, 7L);
            flow.run(400);
            plansTaken = flow.settlement.lockPlansTaken();
            assertEquals(0, flow.settlement.lockPlanMisses());
            on = balances(f);
        }
        try (ReservationFixture f = ReservationFixture.create("lock_plan_p1_off", 1)) {
            Flow flow = new Flow(f, 7L);
            flow.settlement.setLockPlanEnabled(false);
            flow.run(400);
            assertEquals(0, flow.settlement.lockPlansTaken());
            off = balances(f);
        }
        assertTrue(plansTaken > 100, "P1 에서 계획이 돌지 않았다: " + plansTaken);
        assertEquals(off, on, "잠금 계획이 결과 잔고를 바꿨다");
    }

    /** 사용자 넷과 마켓 둘의 난수 주문 흐름. 모든 명령을 저널에 적고 엔진에 적용해 정산한다. */
    static final class Flow {
        final ReservationFixture f;
        final SettlementService settlement;
        final CommandJournalAppender appender;
        final MatchingEngineImpl engine = new MatchingEngineImpl();
        final long[] users = new long[4];
        final Random random;
        final boolean p2;

        Flow(ReservationFixture f, long seed) {
            this.f = f;
            this.random = new Random(seed);
            this.p2 = f.protocol() == 2;
            for (int i = 0; i < users.length; i++) {
                users[i] = f.user("flow" + i, new BigDecimal("100000000"));
                f.deposit(users[i], BTC, new BigDecimal("1000"));
                f.deposit(users[i], ETH, new BigDecimal("1000"));
            }
            LeaderLeaseService lease = f.lease();
            this.appender = f.appender(lease, p2);
            this.settlement = f.settlement(lease);
        }

        void run(int commands) {
            for (int i = 0; i < commands; i++) {
                long user = users[random.nextInt(users.length)];
                int market = random.nextBoolean() ? KRW_BTC : KRW_ETH;
                OrderIntake intake = next(user, market);
                List<EngineEvent> events;
                try {
                    events = f.journalAndApply(appender, engine, intake);
                } catch (DataAccessException e) {
                    // P2 에서 잔고가 모자란 주문은 접수가 23514 로 거부한다. 흐름의 일부다.
                    assertTrue(SqlErrors.isInsufficientFunds(e), "접수가 잔고 부족이 아닌 이유로 실패했다: " + e);
                    continue;
                }
                settlement.appendAndSettle(events);
            }
        }

        long trades() {
            return f.count("SELECT count(*) FROM trades");
        }

        private OrderIntake next(long user, int market) {
            int kind = random.nextInt(10);
            String price = String.valueOf(45_000 + random.nextInt(10_001));
            String qty = BigDecimal.valueOf(1 + random.nextInt(50), 2).toPlainString();
            if (kind == 9) {
                List<Long> open = f.owner.sql("SELECT id FROM orders WHERE user_id = ? AND market_id = ?"
                                + " AND status IN ('OPEN', 'PARTIAL') ORDER BY id")
                        .params(user, market).query(Long.class).list();
                if (!open.isEmpty()) {
                    return f.cancel(market, user, open.get(random.nextInt(open.size())));
                }
            }
            OrderCommand.Side side = random.nextBoolean() ? BUY : SELL;
            return switch (kind) {
                case 0, 1, 2, 3 -> f.limit(market, user, side, price, qty);
                case 4 -> f.limit(market, user, side, price, qty, OrderCommand.TimeInForce.IOC);
                case 5 -> f.limit(market, user, side, price, qty, OrderCommand.TimeInForce.FOK);
                case 6 -> f.limit(market, user, side, price, qty, OrderCommand.TimeInForce.POST_ONLY);
                // 시장가 매수(금액 지정)는 quote scale 18 마켓만 받으므로(API 설계 §1.4 앱 가드) KRW 마켓에서는 IOC 매수로 둔다.
                case 7 -> side == BUY ? f.limit(market, user, side, price, qty, OrderCommand.TimeInForce.IOC)
                        : f.marketSell(market, user, qty);
                default -> f.limit(market, user, side, price, qty);
            };
        }
    }

    private static String balances(ReservationFixture f) {
        return f.owner.sql("""
                SELECT string_agg(a.owner_type || a.owner_id || ':' || a.purpose || ':' || a.asset_id || '=' || b.balance,
                                  ',' ORDER BY a.owner_type, a.owner_id, a.purpose, a.asset_id)
                FROM accounts a JOIN balances b ON b.account_id = a.id
                """).query(String.class).single();
    }

    private static long deadlocks(ReservationFixture f) {
        return f.count("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()");
    }

    /**
     * 교착 계수의 증가. 백엔드 통계는 모았다가 쓰고 유휴 10초 뒤로 미룰 수 있다(PG15 이후). expectIncrease 면 늘 때까지 최대
     * 15초 보고, 아니면 11초 기다린 뒤 한 번 읽는다.
     */
    private static long settledDeadlockDelta(ReservationFixture f, long before, boolean expectIncrease)
            throws InterruptedException {
        if (!expectIncrease) {
            Thread.sleep(11_000);
            return deadlocks(f) - before;
        }
        for (int i = 0; i < 30; i++) {
            long delta = deadlocks(f) - before;
            if (delta > 0) {
                return delta;
            }
            Thread.sleep(500);
        }
        return deadlocks(f) - before;
    }

    private static boolean waitingIntake(ReservationFixture f) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            long n = f.count("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query LIKE '%append_command_prologue%'
                      AND datname = current_database()
                    """);
            if (n > 0) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}

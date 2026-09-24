package kr.exchange.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.NodeServiceHealth;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.PartitionAcquirePolicy;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandJournalAppender;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수수료 샤드 함수(S4 설계 4-19, DB 설계 §1.2·§5.4-c 11, ADR 0034 결정 10). P1·P2 는 샤드 0 하나이고, P3 은 그 마켓 파티션의
 * 번호다. 두 파티션의 정산이 같은 수수료 잔고 행을 다투지 않게 하려는 것이다. 정산 잠금 계획은 그 배치 마켓 샤드의 수수료
 * 계정을 넣는다. 샤드 1 계정은 S4 가 만들지 않으므로(S7 선행 조건) P3 흉내 픽스처가 만든다.
 */
class FeeShardFunctionTest {

    @Test
    void singlePlanUsesShardZero() {
        SettlementService settlement = new SettlementService(null, null, null, null, null, null);
        assertEquals(0, settlement.feeShard(1), "계획이 없으면 샤드 0 이다");
        settlement.setPartitionPlan(PartitionPlan.single());
        assertEquals(0, settlement.feeShard(1));
        assertEquals(0, settlement.feeShard(2));
        assertEquals(0, settlement.feeShard(999));
    }

    @Test
    void partitionModeUsesThePartitionIndexAndTheLockPlanTakesThatShard() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_fee_shard", 2)) {
            f.db.setProtocol(3);
            for (int asset : new int[] {KRW, ETH}) {
                f.owner.sql("""
                        INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                        VALUES ('SYSTEM', 0, 'FEE_INCOME', ?, 1)
                        """).param(asset).update();
            }
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, ETH, new BigDecimal("10"));

            PartitionPlan plan = PartitionPlan.partitioned(f.app);
            SettlementService settlement = new SettlementService(f.app, f.appTx, null, new ObjectMapper(),
                    new MarketCatalog(f.app), f.mapper);
            settlement.setPartitionPlan(plan);
            assertEquals(0, settlement.feeShard(ReservationFixture.KRW_BTC), "p0 마켓은 샤드 0 이다");
            assertEquals(1, settlement.feeShard(KRW_ETH), "p1 마켓은 샤드 1 이다");
            assertEquals(1, settlement.feeShard(ReservationFixture.KRW_XRP));
            assertThrows(IllegalStateException.class, () -> settlement.feeShard(77), "대응 없는 마켓을 샤드로 골랐다");

            // p1 리더로 KRW-ETH 체결 하나를 정산한다.
            LeaderLeaseService p1 = new LeaderLeaseService("matching-p1", "fee-node",
                    new PostgresLeaderLeaseStore(f.appPool, "matching-p1", (short) 3, Duration.ofSeconds(1)),
                    FencingEpochPublisher.NONE, Duration.ofSeconds(60), Duration.ofSeconds(1),
                    NodeServiceHealth.ALWAYS_SERVICEABLE, PartitionAcquirePolicy.ALWAYS, new LeaderWriteProgress());
            assertTrue(p1.tryAcquire(), "p1 획득 실패");
            LeaderTerm term = p1.currentTerm().orElseThrow();
            CommandJournalAppender appender = new CommandJournalAppender(f.app, f.appTx, null);
            appender.setRequireReservation(true);
            MatchingEngineImpl engine = new MatchingEngineImpl();
            BigDecimal shard0Before = feeBalances(f, 0);
            for (OrderIntake intake : List.of(
                    f.limit(KRW_ETH, bob, OrderCommand.Side.SELL, "50000", "0.1"),
                    f.limit(KRW_ETH, alice, OrderCommand.Side.BUY, "50000", "0.1"))) {
                AppendResult ack = appender.append(term, f.draft(intake), intake.reserveCheck());
                List<EngineEvent> events = f.apply(engine, ack.seq(), KRW_ETH);
                settlement.appendAndSettle(term, events);
            }
            assertEquals(1, f.count("SELECT count(*) FROM trades WHERE market_id = " + KRW_ETH));
            assertTrue(feeBalances(f, 1).signum() > 0, "p1 마켓 수수료가 샤드 1 계정에 게시되지 않았다");
            assertEquals(0, shard0Before.compareTo(feeBalances(f, 0)), "p1 마켓 수수료가 샤드 0 계정을 건드렸다");
            assertEquals(0, settlement.lockPlanMisses(), "정산 게시가 잠금 계획 밖의 계정(다른 샤드)을 썼다");
            assertTrue(settlement.lockPlansTaken() > 0);
            p1.close();
        }
    }

    private static BigDecimal feeBalances(ReservationFixture f, int shard) {
        return f.decimal("""
                SELECT COALESCE(sum(b.balance), 0) FROM balances b JOIN accounts a ON a.id = b.account_id
                WHERE a.owner_type = 'SYSTEM' AND a.purpose = 'FEE_INCOME' AND a.shard = %d
                """.formatted(shard));
    }
}

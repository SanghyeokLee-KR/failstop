package kr.exchange.engine;

import kr.exchange.engine.lease.LeaderProtocolBoot;
import kr.exchange.engine.lease.PartitionLeaseDb;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파티션 계획(S4 설계 4-1, DB 설계 §5.4-c 1). P1·P2 는 matching-engine 하나가 모든 마켓을 덮고 DB 를 읽지 않는다. P3 은
 * matching-p0·matching-p1 이고 마켓의 파티션은 market_lease_partition 에서 지연 읽기로 얻는다. 기동 뒤 새로 생긴 마켓은
 * 처음 만날 때 다시 읽고, 대응이 없는 마켓은 null 이다.
 */
class PartitionPlanTest {

    @Test
    void singlePlanCoversEveryMarketWithoutTheDatabase() {
        for (PartitionPlan plan : List.of(PartitionPlan.single(),
                PartitionPlan.forBoot(new LeaderProtocolBoot(1), null),
                PartitionPlan.forBoot(new LeaderProtocolBoot(2), null),
                // 조회 역할 노드는 모드를 읽지 않는다. DB 없이 한 파티션 계획이다.
                PartitionPlan.forBoot(LeaderProtocolBoot.notRead("조회 역할"), null))) {
            assertEquals(List.of("matching-engine"), plan.partitions());
            assertFalse(plan.partitioned());
            assertEquals("matching-engine", plan.first());
            assertEquals("matching-engine", plan.partitionOf(1));
            assertEquals("matching-engine", plan.partitionOf(9_999));
            assertTrue(plan.scope("matching-engine").test(77));
            assertEquals(0, plan.index("matching-engine"));
            assertThrows(IllegalArgumentException.class, () -> plan.scope("matching-p0"));
            assertThrows(IllegalArgumentException.class, () -> plan.index("matching-p1"));
        }
    }

    @Test
    void partitionedPlanReadsTheMapLazilyAndPicksUpNewMarkets() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_plan");
        db.seedMarkets(1, 2, 3);
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER,
                PartitionLeaseDb.OWNER));
        PartitionPlan plan = PartitionPlan.forBoot(new LeaderProtocolBoot(3), jdbc);

        assertEquals(List.of("matching-p0", "matching-p1"), plan.partitions());
        assertTrue(plan.partitioned());
        assertEquals("matching-p0", plan.first());
        assertEquals(0, plan.index("matching-p0"));
        assertEquals(1, plan.index("matching-p1"));
        assertThrows(IllegalArgumentException.class, () -> plan.index("matching-engine"));
        // ADR 0034 결정한 값 3: 1·4 는 p0, 2·3 은 p1.
        assertEquals("matching-p0", plan.partitionOf(1));
        assertEquals("matching-p1", plan.partitionOf(2));
        assertEquals("matching-p1", plan.partitionOf(3));
        IntPredicate p0 = plan.scope("matching-p0");
        IntPredicate p1 = plan.scope("matching-p1");
        assertTrue(p0.test(1) && !p0.test(2) && !p0.test(3));
        assertTrue(p1.test(2) && p1.test(3) && !p1.test(1));

        // 기동 뒤 생긴 마켓은 처음 만날 때 다시 읽어 안다.
        assertNull(plan.partitionOf(4), "아직 없는 마켓의 대응이 있다");
        db.seedMarkets(4);
        assertEquals("matching-p0", plan.partitionOf(4));
        assertTrue(p0.test(4));
        // 트리거 없이 들어온 마켓은 대응이 없다. 그 마켓의 쓰기는 DB 가 55000 으로 거부한다.
        db.seedMarketWithoutTriggers(20);
        assertNull(plan.partitionOf(20));
        assertFalse(p0.test(20) || p1.test(20));
    }

    @Test
    void partitionedPlanNeedsTheDatabase() {
        assertThrows(IllegalArgumentException.class, () -> PartitionPlan.partitioned(null));
    }
}

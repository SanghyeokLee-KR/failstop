package kr.exchange.engine;

import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.DbFailures;
import kr.exchange.journal.ReplayMismatchException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파티션 재구성 재시도의 recovery_runs 기록(S4b 설계 2-1·4-2, DB 설계 §5.4-c 5·§5.5 v2.56). P2 한 노드에서 정산 주입으로
 * WRITER_FATAL 강등을 일으킨 뒤 재구성의 재생에 결함을 넣는다.
 *
 * 재생이 커넥션 획득 실패로 끝나면 대조를 끝내지 못한 것이라 불일치로 적지 않고, 재구성은 1초·2초 간격으로 다시 해 팔로워가
 * 된다. S4 관문 재주행 E2 에서 DB 복귀 순간의 획득 실패가 C17 1행으로 남은 경로다. 재생이 진짜 불일치로 끝나면 지금처럼
 * matched=false 한 행이 남고 다음 시도가 성공한다.
 */
class RebuildOutageRetryIntegrationTest {

    private static final String RESOURCE = "matching-engine";

    @Test
    void connectionFailuresDuringTheRebuildAreRetriedWithoutAMismatchRow() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_rebuild_outage", 2);
             PartitionNode node = new PartitionNode("node", f, new PartitionNode.Options().protocol(2))) {
            AtomicInteger outages = new AtomicInteger();
            demoteThenRebuild(f, node, () -> {
                if (outages.incrementAndGet() <= 2) {
                    throw DbFailures.acquireFailure();
                }
            });

            assertEquals(2, rebuilds(node, "failure"), "재구성이 두 번 실패한 뒤 성공하지 않았다");
            assertEquals(0, f.count("SELECT count(*) FROM recovery_runs WHERE NOT matched"),
                    "DB 에 닿지 못한 재구성 재생을 불일치로 적었다");
            assertTrue(f.count("SELECT count(*) FROM recovery_runs WHERE matched") >= 2,
                    "기동과 재구성의 성공 행이 없다(기록 경로가 배선되지 않았다)");
        }
    }

    @Test
    void aRealMismatchDuringTheRebuildIsRecordedOnce() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_rebuild_mismatch", 2);
             PartitionNode node = new PartitionNode("node", f, new PartitionNode.Options().protocol(2))) {
            AtomicBoolean poisoned = new AtomicBoolean(true);
            demoteThenRebuild(f, node, () -> {
                if (poisoned.getAndSet(false)) {
                    throw new ReplayMismatchException("주입: 재구성 재생 불일치");
                }
            });

            assertEquals(1, rebuilds(node, "failure"));
            assertEquals(1, f.count("SELECT count(*) FROM recovery_runs WHERE NOT matched"),
                    "재구성 재생의 불일치가 한 행으로 남지 않았다");
        }
    }

    /** 리더가 된 노드의 정산에 한 번 실패를 넣어 WRITER_FATAL 로 강등시키고, 재구성 재생에 결함을 건 뒤 재구성 성공을 기다린다. */
    private static void demoteThenRebuild(ReservationFixture f, PartitionNode node, PartitionNode.ReplayFault fault) {
        long bob = f.user("bob", BigDecimal.ZERO);
        f.deposit(bob, BTC, new BigDecimal("10"));
        AtomicBoolean poison = new AtomicBoolean(true);
        node.sinkFault = (marketId, term, events) -> {
            if (poison.getAndSet(false)) {
                throw new IllegalStateException("주입: 재시도 대상이 아닌 정산 실패");
            }
        };
        node.start();
        awaitAccepting(node, RESOURCE);
        node.replayFault = fault;
        node.submit(f.limit(KRW_BTC, bob, OrderCommand.Side.SELL, "50000", "0.1"));
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> rebuilds(node, "success") >= 1);
        assertEquals(DemotionReason.WRITER_FATAL, node.runtime(RESOURCE).demotions().get(0).reason());
        node.replayFault = () -> { };
    }

    private static long rebuilds(PartitionNode node, String outcome) {
        return node.registry.get("exchange.partition.rebuild").tags("partition", RESOURCE, "outcome", outcome)
                .timer().count();
    }
}

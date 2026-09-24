package kr.exchange.engine;

import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 선호 파티션과 비선호 유예(S4 설계 4-14·6-2, DB 설계 §5.4-c 9, ADR 0034 결정 2). P3 흉내 DB 에 두 노드(A 선호 p0, B 선호
 * p1)를 동시에 띄운다. 비선호 파티션은 기동 뒤 2×TTL, 만료를 본 뒤 1×TTL 을 기다리므로 기동 직후 선호 노드가 먼저 얻는다.
 *
 * A 를 멈추면 TTL 만료 뒤 B 가 p0 도 쥐고, A 가 돌아와도 자동으로 되돌리지 않는다. B 의 p0 참여를 끄면 B 는 p0 을 계획 강등하고
 * 리스를 만료시켜 A 가 얻는다.
 */
class PreferredAcquisitionIntegrationTest {

    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";

    @Test
    void preferredNodesTakeTheirPartitionsAndNoAutomaticFailback() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_preferred", 2)) {
            f.db.setProtocol(3);
            PartitionNode a = new PartitionNode("node-a", f, new PartitionNode.Options().preferred(P0));
            PartitionNode b = new PartitionNode("node-b", f, new PartitionNode.Options().preferred(P1));
            PartitionNode a2 = null;
            try {
                a.start();
                b.start();
                awaitAccepting(a, P0);
                awaitAccepting(b, P1);
                Duration ttl = a.options.ttl;
                // 비선호 유예(기동 뒤 2×TTL)를 넘겨도 서로의 선호 파티션을 빼앗지 않는다. 유효한 리스이기 때문이다.
                Thread.sleep(ttl.multipliedBy(3).toMillis());
                assertTrue(a.lease(P0).isLeader() && !a.lease(P1).isLeader(), "A 가 p0 만 쥐지 않았다");
                assertTrue(b.lease(P1).isLeader() && !b.lease(P0).isLeader(), "B 가 p1 만 쥐지 않았다");
                long p0Epoch = a.lease(P0).currentTerm().orElseThrow().epoch();

                // A 가 죽는다. p0 리스는 반납되지 않고 TTL 뒤 만료되며, B 는 만료를 본 뒤 1×TTL 을 더 기다려 얻는다.
                long stoppedAt = System.nanoTime();
                a.close();
                awaitAccepting(b, P0);
                long waitedMillis = (System.nanoTime() - stoppedAt) / 1_000_000;
                assertTrue(waitedMillis >= ttl.toMillis(), "B 가 A 의 리스 만료 전에 p0 을 얻었다: " + waitedMillis + "ms");
                assertTrue(b.lease(P0).currentTerm().orElseThrow().epoch() > p0Epoch);
                assertTrue(b.lease(P1).isLeader(), "p0 획득이 B 의 p1 을 흔들었다");

                // A 가 돌아와도(새 프로세스) 자동으로 되돌리지 않는다.
                a2 = new PartitionNode("node-a2", f, new PartitionNode.Options().preferred(P0)).start();
                Thread.sleep(ttl.multipliedBy(3).toMillis());
                assertFalse(a2.lease(P0).isLeader(), "돌아온 선호 노드가 유효한 p0 리스를 빼앗았다");
                assertTrue(b.lease(P0).isLeader(), "돌아온 선호 노드 때문에 B 가 p0 을 놓았다");
                assertEquals(PromotionCoordinator.State.FOLLOWER, a2.state(P0));

                // B 의 p0 참여를 끈다. B 는 계획 강등하고 리스를 만료시킨다. A 는 만료 뒤 p0 을 얻는다.
                long p0BeforeHandBack = b.lease(P0).currentTerm().orElseThrow().epoch();
                b.runtime(P0).setParticipate(false);
                awaitAccepting(a2, P0);
                assertTrue(a2.lease(P0).currentTerm().orElseThrow().epoch() > p0BeforeHandBack);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> b.state(P0) == PromotionCoordinator.State.FOLLOWER);
                assertFalse(b.lease(P0).isLeader());
                assertFalse(b.lease(P0).isArmed(), "참여를 끈 파티션이 후보로 돌아왔다");
                assertTrue(b.lease(P1).isLeader(), "p0 계획 강등이 B 의 p1 을 흔들었다");
                assertEquals(DemotionReason.PLANNED, b.runtime(P0).demotions().get(0).reason());
            } finally {
                a.close();
                b.close();
                if (a2 != null) {
                    a2.close();
                }
            }
        }
    }
}

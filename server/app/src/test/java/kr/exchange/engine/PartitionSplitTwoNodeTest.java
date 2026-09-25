package kr.exchange.engine;

import kr.exchange.engine.lease.ReservationFixture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitAccepting;
import static kr.exchange.engine.PartitionLeaseIsolationTest.awaitTrades;
import static kr.exchange.engine.PartitionLeaseIsolationTest.trade;
import static kr.exchange.engine.S7Cutover.P0;
import static kr.exchange.engine.S7Cutover.P1;
import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 두 노드의 파티션 분담(ADR 0034 S7 결정 2·4, DB 설계 §5.4-c 9·§5.4-e 1, S7 설계 4-6·6-2). 운영과 같은 전환 함수로 P3 에 올린 DB
 * 에서 두 노드를 함께 기동하면 각자 선호 파티션을 쥔다. app-2 가 죽으면(리스 반납 없음) app-1 은 p1 리스 만료를 본 뒤 1×TTL 을 더
 * 기다려 p1 을 얻고, 그동안 p0 의 epoch 는 그대로다. 되살아난 app-2 는 유효한 p1 을 되찾지 않는다. 재배치는 운영자가 app-1 의 p1
 * 참여를 끄는 것이고, 선호 노드인 app-2 가 만료 즉시 얻는다. 참여를 다시 켠 app-1 은 백업으로 남는다. 모든 단계의 체결은 그
 * 파티션 소유 노드의 epoch 로 쓰였다(C22'·C30 0).
 */
class PartitionSplitTwoNodeTest {

    @Test
    void preferredSplitTakeoverAfterExpiryPlusTtlNoFailbackAndRelocation() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("s7_split", 2)) {
            S7Cutover.latch(f);
            S7Cutover.switchTo(f, 3);
            long alice = f.user("alice", new BigDecimal("100000000"));
            long bob = f.user("bob", BigDecimal.ZERO);
            f.deposit(bob, BTC, new BigDecimal("10"));
            f.deposit(bob, ETH, new BigDecimal("10"));
            PartitionNode a = new PartitionNode("app-1", f, new PartitionNode.Options().preferred(P0));
            PartitionNode b = new PartitionNode("app-2", f, new PartitionNode.Options().preferred(P1));
            PartitionNode b2 = null;
            try {
                a.start();
                b.start();
                awaitAccepting(a, P0);
                awaitAccepting(b, P1);
                Duration ttl = a.options.ttl;
                Thread.sleep(ttl.multipliedBy(3).toMillis());
                assertEquals(P0, S7Cutover.owned(a), "함께 기동한 app-1 이 선호 p0 만 쥐지 않았다");
                assertEquals(P1, S7Cutover.owned(b), "함께 기동한 app-2 가 선호 p1 만 쥐지 않았다");
                trade(f, a, KRW_BTC, alice, bob);
                trade(f, b, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_BTC, 1);
                awaitTrades(f, KRW_ETH, 1);
                long p0Epoch = a.lease(P0).currentTerm().orElseThrow().epoch();
                long p1Epoch = b.lease(P1).currentTerm().orElseThrow().epoch();

                // app-2 가 죽는다. p1 리스는 반납하지 않는다. 만료 시각을 DB 에서 읽는다.
                b.close();
                OffsetDateTime expiry = f.owner.sql("SELECT lease_until FROM leader_lease WHERE resource = 'matching-p1'")
                        .query(OffsetDateTime.class).single();
                awaitAccepting(a, P1);
                long takeover = a.lease(P1).currentTerm().orElseThrow().epoch();
                assertTrue(takeover > p1Epoch, "넘겨받은 p1 epoch 가 옛 epoch 보다 크지 않다");
                OffsetDateTime acquiredAt = f.owner.sql("SELECT acquired_at FROM lease_acquisitions WHERE epoch = ?")
                        .param(takeover).query(OffsetDateTime.class).single();
                long afterExpiryMs = Duration.between(expiry, acquiredAt).toMillis();
                assertTrue(afterExpiryMs >= ttl.toMillis() - 150,
                        "비선호 app-1 이 만료 뒤 1×TTL 을 기다리지 않고 p1 을 얻었다: 만료 뒤 " + afterExpiryMs + "ms");
                assertEquals(p0Epoch, a.lease(P0).currentTerm().orElseThrow().epoch(), "p1 인계가 p0 epoch 를 바꿨다");
                trade(f, a, KRW_ETH, alice, bob);
                awaitTrades(f, KRW_ETH, 2);

                // app-2 가 새 프로세스로 돌아와도 유효한 p1 을 되찾지 않는다(결정 4).
                b2 = new PartitionNode("app-2b", f, new PartitionNode.Options().preferred(P1)).start();
                Thread.sleep(ttl.multipliedBy(3).toMillis());
                assertFalse(b2.lease(P1).isLeader(), "돌아온 선호 노드가 유효한 p1 리스를 빼앗았다");
                assertEquals(PromotionCoordinator.State.FOLLOWER, b2.state(P1));
                assertTrue(a.lease(P1).isLeader());

                // 재배치: app-1 의 p1 참여를 끈다(계획 강등, 리스 만료). 선호 노드 app-2 가 만료 즉시 얻는다.
                long beforeRelocation = a.lease(P1).currentTerm().orElseThrow().epoch();
                a.runtime(P1).setParticipate(false);
                awaitAccepting(b2, P1);
                assertTrue(b2.lease(P1).currentTerm().orElseThrow().epoch() > beforeRelocation);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                        .until(() -> a.state(P1) == PromotionCoordinator.State.FOLLOWER);
                assertEquals(DemotionReason.PLANNED, a.runtime(P1).demotions().get(0).reason());
                assertEquals(p0Epoch, a.lease(P0).currentTerm().orElseThrow().epoch(), "재배치가 p0 를 흔들었다");
                a.runtime(P1).setParticipate(true);
                Thread.sleep(ttl.multipliedBy(2).toMillis());
                assertTrue(b2.lease(P1).isLeader(), "참여를 다시 켠 app-1 이 유효한 p1 을 빼앗았다");
                assertTrue(a.lease(P1).isArmed(), "참여를 다시 켠 app-1 이 백업(후보)이 아니다");
                trade(f, b2, KRW_ETH, alice, bob);
                trade(f, a, KRW_BTC, alice, bob);
                awaitTrades(f, KRW_ETH, 3);
                awaitTrades(f, KRW_BTC, 2);
                S7Cutover.awaitDrained(f);
            } finally {
                a.close();
                b.close();
                if (b2 != null) {
                    b2.close();
                }
            }
            S7Cutover.assertAudit(S7Cutover.authoritative(f, "post"), S7Cutover.GATE);
        }
    }
}

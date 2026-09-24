package kr.exchange.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.journal.JournalCheckpoints;
import kr.exchange.journal.ReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 리더 노드의 팔로워 틱(S4 설계 4-10·13절). 이 노드가 쥔 파티션의 마켓은 물질화 경계를 읽지 않는다. 인계한 마켓의 커서는 인계
 * 시점에 멈춰 있어 그 경계 질의가 그 뒤 커밋된 명령을 매 틱 훑기 때문이다. 모든 파티션을 쥐면 틱은 DB 에 가지 않고, 쉬는
 * 파티션의 격차 게이지는 0 이다.
 */
class FollowerIdleLeaderTickTest {

    private static final String SINGLE = "matching-engine";

    @Test
    void tickMakesNoJournalRoundTripOnceTheOnlyPartitionIsHandedOver() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountingReplay replay = new CountingReplay(Map.of(1, 40L));
        FollowerCatchUp follower = new FollowerCatchUp(replay, PartitionPlan.single(), Map.of(), Duration.ofHours(1),
                registry);

        follower.tick();
        assertTrue(replay.calls.get() > 0, "따라잡는 파티션의 틱이 저널을 읽지 않았다(대조군)");
        assertEquals(40.0, lagMax(registry, SINGLE), "따라잡는 파티션의 격차");

        follower.handOver(SINGLE, Duration.ofSeconds(1));
        replay.calls.set(0);
        follower.tick();
        follower.tick();
        assertEquals(0, replay.calls.get(), "리더가 된 노드의 틱이 저널을 읽었다");
        assertEquals(2, follower.state(SINGLE));
        assertEquals(0.0, lagMax(registry, SINGLE), "쉬는 파티션의 격차가 0 이 아니다");

        // 승격이 접수를 열지 못해 되돌리면 다시 읽는다.
        follower.resume(SINGLE);
        follower.tick();
        assertTrue(replay.calls.get() > 0, "되돌린 팔로워의 틱이 저널을 읽지 않았다");
        assertEquals(40.0, lagMax(registry, SINGLE));
    }

    /** P3 흉내. p0 을 쥔 노드의 틱은 p1 마켓의 경계만 읽는다. 둘 다 쥐면 DB 에 가지 않는다. */
    @Test
    void boundaryQueryCoversOnlyThePartitionsThisNodeFollows() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("pml_follower_idle");
        db.seedMarkets(1, 2, 3);
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER,
                PartitionLeaseDb.OWNER));
        // ADR 0034 결정한 값 3: 1 은 p0, 2·3 은 p1.
        PartitionPlan plan = PartitionPlan.partitioned(jdbc);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountingReplay replay = new CountingReplay(Map.of(1, 10L, 2, 20L, 3, 30L));
        FollowerCatchUp follower = new FollowerCatchUp(replay, plan, Map.of(), Duration.ofHours(1), registry);

        follower.tick();
        assertEquals(Set.of(1, 2, 3), replay.lastIncluded(), "두 파티션을 따라잡는 틱의 경계 대상");

        follower.handOver("matching-p0", Duration.ofSeconds(1));
        follower.tick();
        assertEquals(Set.of(2, 3), replay.lastIncluded(), "쥔 파티션의 마켓 경계를 읽었다");
        assertEquals(0.0, lagMax(registry, "matching-p0"));
        assertEquals(30.0, lagMax(registry, "matching-p1"));

        follower.handOver("matching-p1", Duration.ofSeconds(1));
        replay.calls.set(0);
        follower.tick();
        assertEquals(0, replay.calls.get(), "모든 파티션을 쥔 노드의 틱이 저널을 읽었다");
    }

    private static double lagMax(SimpleMeterRegistry registry, String resource) {
        return registry.get("exchange.follower.catchup.lag_commands.max").tag("partition", resource).gauge().value();
    }

    /**
     * 저널 왕복을 세는 재생. 마켓 head 만 들고, 경계는 넘겨받은 커서 그대로라 적용할 명령이 없다. 판정에 든 마켓을 기록한다.
     */
    private static final class CountingReplay extends ReplayService {
        final AtomicInteger calls = new AtomicInteger();
        private final Map<Integer, Long> heads;
        private final List<Set<Integer>> included = new CopyOnWriteArrayList<>();

        CountingReplay(Map<Integer, Long> heads) {
            super(null, null, null, null, null);
            this.heads = heads;
        }

        @Override
        public JournalCheckpoints materializedThroughByMarket(Map<Integer, Long> cursors) {
            return materializedThroughByMarket(cursors, marketId -> true);
        }

        @Override
        public JournalCheckpoints materializedThroughByMarket(Map<Integer, Long> cursors, IntPredicate include) {
            calls.incrementAndGet();
            Set<Integer> markets = new TreeSet<>(cursors.keySet());
            markets.addAll(heads.keySet());
            Map<Integer, Long> bounds = new TreeMap<>();
            for (int marketId : markets) {
                if (include.test(marketId)) {
                    bounds.put(marketId, cursors.getOrDefault(marketId, 0L));
                }
            }
            included.add(new TreeSet<>(bounds.keySet()));
            return new JournalCheckpoints(bounds, 0);
        }

        @Override
        public Map<Integer, Long> journalHeads() {
            calls.incrementAndGet();
            return heads;
        }

        Set<Integer> lastIncluded() {
            return included.get(included.size() - 1);
        }
    }
}

package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.engine.core.MatchingEngine;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.JournalCheckpoints;
import kr.exchange.journal.JournalChecksum;
import kr.exchange.journal.JournalEntry;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 승격 재생 상한 계약(2026-09-09). 축은 마켓 벡터다(ADR 0022 5-4).
 *
 * 종전에는 승격 경로만 상한 없이 재생했다. 그것이 종료하는 근거는 "승격 시점에는 다른 노드가
 * 명령 저널에 쓸 수 없다" 는 fencing 하나였다. 그 전제가 깨지면, 곧 승격한 노드가 리스를 잃고
 * 다른 노드가 리더가 되면, 재생 루프가 새 리더의 명령을 계속 읽어 끝나지 않는다. 2026-09-08
 * 회차에서 실제로 CPU 200% 로 4분간 응답하지 못했다.
 *
 * 이 검증이 고정하는 것은 셋이다.
 *   1. 복구가 상한 없는 재생을 한 번도 부르지 않는다.
 *   2. 2 단계 상한이 한 번 읽어 고정한 마켓별 head 이고, 그 뒤에 늘어난 head 를 따라가지 않는다.
 *   3. 경계 계산에 엔진의 마켓 커서를 넘긴다.
 *
 * DB 를 쓰지 않는다. 판정 대상이 호출 형태이지 저장 내용이 아니기 때문이다. 실제 저널을 상대로
 * 도는 경로는 BootPromotionRetryIntegrationTest, CrashWindowReinjectionOrderTest, FollowerRejoinIntegrationTest 가 잰다.
 */
class PromotionReplayBoundTest {

    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Duration TICK = Duration.ofSeconds(3);
    private static final CommandPayloadMapper MAPPER = new CommandPayloadMapper(new ObjectMapper());

    /** 재생을 흉내 내며 받은 커서·상한과 호출 순서를 기록한다. 판정 대상이 이 기록이다. */
    private static final class RecordingReplayService extends ReplayService {

        final List<String> calls = new ArrayList<>();
        final List<Map<Integer, Long>> cursorsSeen = new ArrayList<>();
        final List<Map<Integer, Long>> headsReturned = new ArrayList<>();
        final Map<Integer, Long> tailUppers = new TreeMap<>();
        // 경계를 읽을 때마다 리더였는지. 재대조가 리더 획득 뒤인지를 이것으로 가른다.
        final List<Boolean> leaderAtBounds = new ArrayList<>();
        LeaderLeaseService lease;
        private final Map<Integer, Long> bounds;
        private final Map<Integer, Long> heads;
        // 부를 때마다 이 마켓의 head 가 늘어난다. 다른 노드가 계속 쓰는 상황을 만든다.
        private final int growingMarket;
        private final long headStep;

        RecordingReplayService(Map<Integer, Long> bounds, Map<Integer, Long> heads,
                               int growingMarket, long headStep) {
            super(null, null, null, null, null);
            this.bounds = bounds;
            this.heads = new TreeMap<>(heads);
            this.growingMarket = growingMarket;
            this.headStep = headStep;
        }

        long currentHead(int marketId) {
            return heads.get(marketId);
        }

        @Override
        public JournalCheckpoints materializedThroughByMarket(Map<Integer, Long> cursors) {
            calls.add("bounds");
            leaderAtBounds.add(lease != null && lease.isLeader());
            cursorsSeen.add(new TreeMap<>(cursors));
            return new JournalCheckpoints(bounds, 0);
        }

        @Override
        public Map<Integer, Long> journalHeads() {
            calls.add("heads");
            Map<Integer, Long> now = new TreeMap<>(heads);
            headsReturned.add(now);
            heads.merge(growingMarket, headStep, Long::sum);
            return now;
        }

        @Override
        public ReplayResult verifyToMarket(MatchingEngine engine, int marketId, JournalCheckpoints checkpoints) {
            calls.add("verify");
            long replayed = advance(engine, marketId, checkpoints.bound(marketId));
            return new ReplayResult(engine.appliedSeq(marketId), replayed, replayed, List.of());
        }

        @Override
        public ReplayResult replayTailMarket(MatchingEngine engine, int marketId, long headOfMarket) {
            calls.add("tail");
            tailUppers.put(marketId, headOfMarket);
            long replayed = advance(engine, marketId, headOfMarket);
            return new ReplayResult(engine.appliedSeq(marketId), replayed, 0, List.of());
        }

        @Override
        public ReplayResult rebuild(MatchingEngine engine) {
            throw new AssertionError("복구가 상한 없는 전량 재생을 불렀다");
        }

        @Override
        public ReplayResult rebuildMarket(MatchingEngine engine, int marketId) {
            throw new AssertionError("복구가 상한 없는 마켓 재생을 불렀다: market=" + marketId);
        }
    }

    /** 엔진의 마켓 커서를 upTo 로 올린다. 그 seq 의 명령 하나를 적용한다. */
    private static long advance(MatchingEngine engine, int marketId, long upTo) {
        if (upTo <= engine.appliedSeq(marketId)) {
            return 0;
        }
        engine.apply(command(upTo, marketId));
        return 1;
    }

    private static OrderCommand command(long seq, int marketId) {
        String payload = "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                + "\"price\":\"100\",\"qty\":\"1\",\"quote_order_qty\":null}";
        CommandDraft draft = new CommandDraft(marketId, CommandDraft.CommandType.NEW, 1L,
                "bound-" + seq, seq, 1_721_600_000_000L + seq, seq, payload);
        return MAPPER.toCommand(new JournalEntry(seq, marketId, draft.type(), draft.userId(),
                draft.clientOrderId(), draft.assignedOrderId(), draft.engineTs(), draft.tieBreak(),
                payload, 1L, JournalChecksum.of(seq, draft),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(draft.engineTs()), ZoneOffset.UTC)));
    }

    /** 메모리 리스 보관소. 이 검증은 리더 자격 유무만 필요하다. */
    private static final class MemoryStore implements LeaderLeaseStore {
        private long token;
        private String holder = "";

        @Override
        public Optional<Long> acquire(String h, Duration ttl) {
            token++;
            holder = h;
            return Optional.of(token);
        }

        @Override
        public boolean renew(String h, long epoch, Duration ttl) {
            return h.equals(holder) && epoch == token;
        }

        @Override
        public void release(String h, long epoch) {
            holder = "";
        }

        @Override
        public Optional<LeaseSnapshot> current() {
            return Optional.of(new LeaseSnapshot(holder, token, Instant.MAX));
        }

        @Override
        public double priorDowntimeSeconds(Duration ttl) {
            return 0;
        }

        @Override
        public String describe() {
            return "memory";
        }
    }

    private static LeaderLeaseService leader() {
        LeaderLeaseService lease = new LeaderLeaseService(new MemoryStore(),
                FencingEpochPublisher.NONE, TTL, TICK, NodeServiceHealth.ALWAYS_SERVICEABLE);
        assertTrue(lease.tryAcquire(), "검증 전제인 리더 승격이 실패했다");
        return lease;
    }

    @Test
    void 승격_재생은_상한_없는_재생을_부르지_않는다() {
        RecordingReplayService replay = new RecordingReplayService(
                Map.of(1, 100L, 2, 50L), Map.of(1, 130L, 2, 60L), 1, 0);
        try (LeaderLeaseService lease = leader()) {
            new EngineRecovery(replay, events -> {
            }, lease).recover(new MatchingEngineImpl());

            assertTrue(replay.calls.contains("verify"), "1 단계가 불리지 않았다");
            assertEquals(Map.of(1, 130L, 2, 60L), replay.tailUppers,
                    "2 단계가 마켓마다 head 로 상한되지 않았다");
        }
    }

    @Test
    void phaseTwoUsesHeadsCapturedOnceWhileOneMarketGrows() {
        // 마켓 1 의 head 가 부를 때마다 40 씩 는다. 다른 노드가 그 마켓에 계속 쓰는 상황이다.
        RecordingReplayService replay = new RecordingReplayService(
                Map.of(1, 100L, 2, 50L), Map.of(1, 130L, 2, 60L), 1, 40);
        try (LeaderLeaseService lease = leader()) {
            new EngineRecovery(replay, events -> {
            }, lease).recover(new MatchingEngineImpl());

            int firstTail = replay.calls.indexOf("tail");
            assertTrue(firstTail > 0, "2 단계가 돌지 않아 상한 고정이 판정되지 않는다");
            assertTrue(replay.calls.subList(firstTail, replay.calls.size()).stream().noneMatch("heads"::equals),
                    "2 단계 도중 head 를 다시 읽었다: " + replay.calls);
            int headsBeforeTail = (int) replay.calls.subList(0, firstTail).stream().filter("heads"::equals).count();
            Map<Integer, Long> captured = replay.headsReturned.get(headsBeforeTail - 1);
            List<String> beforeTail = replay.calls.subList(0, firstTail);
            assertEquals(2, beforeTail.stream().filter("bounds"::equals).count(),
                    "리더 자격 뒤 경계 재대조가 없다");
            assertTrue(beforeTail.lastIndexOf("heads") > beforeTail.lastIndexOf("bounds"),
                    "head 고정이 경계 재대조보다 앞섰다: " + replay.calls);
            assertTrue(captured.get(1) > 130, "고정 전에 head 가 늘지 않아 기동 대기 뒤 재읽기가 판정되지 않는다");
            assertEquals(captured, replay.tailUppers, "2 단계 상한이 한 번 고정한 마켓별 head 가 아니다");
            assertTrue(replay.currentHead(1) > replay.tailUppers.get(1),
                    "고정 뒤 head 가 늘지 않아 추종 여부가 판정되지 않는다");
        }
    }

    @Test
    void 초과분이_없으면_두단계로_넘어가지_않는다() {
        RecordingReplayService replay = new RecordingReplayService(
                Map.of(1, 100L, 2, 50L), Map.of(1, 100L, 2, 50L), 1, 0);
        try (LeaderLeaseService lease = leader()) {
            ReplayResult result = new EngineRecovery(replay, events -> {
            }, lease).recover(new MatchingEngineImpl());

            assertEquals(2, replay.calls.stream().filter("verify"::equals).count(), "마켓마다 1 단계가 돌지 않았다");
            assertTrue(replay.tailUppers.isEmpty(), "초과분이 없는데 2 단계가 돌았다");
            assertEquals(100, result.lastAppliedSeq());
        }
    }

    @Test
    void 경계_계산에_엔진의_마켓_커서를_넘긴다() {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        engine.apply(command(7, 2));
        RecordingReplayService replay = new RecordingReplayService(
                Map.of(1, 100L, 2, 50L), Map.of(1, 100L, 2, 50L), 1, 0);
        try (LeaderLeaseService lease = leader()) {
            new EngineRecovery(replay, events -> {
            }, lease).recover(engine);

            assertEquals(Map.of(2, 7L), replay.cursorsSeen.get(0),
                    "경계 계산이 엔진의 마켓 커서가 아닌 값을 받았다. 빈 커서면 마켓마다 전체 명령을 훑는다");
        }
    }

    /** 커서 없는 마켓에는 값을 넘기지 않아 판독기가 0 에서 연다. 스냅샷 seq 를 바닥으로 넘기면 그 아래 명령을 버린다. */
    @Test
    void 커서_없는_마켓에는_바닥을_넘기지_않는다() {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        engine.loadMarketSnapshot(2, 5, new OrderBookImpl(2));
        RecordingReplayService replay = new RecordingReplayService(
                Map.of(1, 100L, 2, 50L), Map.of(1, 100L, 2, 50L), 1, 0);
        try (LeaderLeaseService lease = leader()) {
            new EngineRecovery(replay, events -> {
            }, lease).recover(engine);

            assertEquals(Map.of(2, 5L), replay.cursorsSeen.get(0),
                    "스냅샷을 실은 마켓만 커서를 넘겨야 한다. 커서 없는 마켓 1 에 값이 들어가면 그 값이 바닥이 된다");
        }
    }

    @Test
    void 경계_재대조는_리더_획득_뒤다() {
        RecordingReplayService replay = new RecordingReplayService(
                Map.of(1, 100L, 2, 50L), Map.of(1, 130L, 2, 60L), 1, 0);
        LeaderLeaseService lease = new LeaderLeaseService(new MemoryStore(),
                FencingEpochPublisher.NONE, TTL, TICK, NodeServiceHealth.ALWAYS_SERVICEABLE);
        replay.lease = lease;
        try (lease) {
            new EngineRecovery(replay, events -> {
            }, lease).recover(new MatchingEngineImpl());

            assertTrue(lease.isLeader(), "초과분이 있는데 리더 자격을 얻지 않았다");
            assertEquals(List.of(false, true), replay.leaderAtBounds,
                    "1 단계 경계는 리더 없이, 재대조 경계는 리더 획득 뒤에 읽어야 한다");
        }
    }
}

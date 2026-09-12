package kr.exchange.engine;

import kr.exchange.engine.core.MatchingEngine;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;
import kr.exchange.journal.JournalCheckpoint;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 승격 재생 상한 계약(2026-09-09).
 *
 * 종전에는 승격 경로만 상한 없이 재생했다. 그것이 종료하는 근거는 "승격 시점에는 다른 노드가
 * 명령 저널에 쓸 수 없다" 는 fencing 하나였다. 그 전제가 깨지면, 곧 승격한 노드가 리스를 잃고
 * 다른 노드가 리더가 되면, 재생 루프가 새 리더의 명령을 계속 읽어 끝나지 않는다. 2026-09-08
 * 회차에서 실제로 CPU 200% 로 4분간 응답하지 못했다.
 *
 * 이 검증이 고정하는 것은 둘이다.
 *   1. 복구가 상한 없는 재생을 한 번도 부르지 않는다.
 *   2. 2 단계 상한이 그 시점에 잡은 저널 head 이고, 그 뒤에 늘어난 head 를 따라가지 않는다.
 *
 * DB 를 쓰지 않는다. 판정 대상이 호출 형태이지 저장 내용이 아니기 때문이다. 실제 저널을 상대로
 * 도는 경로는 BootPromotionRetryIntegrationTest 와 FollowerRejoinIntegrationTest 가 잰다.
 */
class PromotionReplayBoundTest {

    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Duration TICK = Duration.ofSeconds(3);

    /** 재생을 흉내 내며 받은 상한을 기록한다. 판정 대상이 이 기록이다. */
    private static final class RecordingReplayService extends ReplayService {

        private final List<JournalCheckpoint> bounds = new ArrayList<>();
        private final JournalCheckpoint checkpoint;
        // 부를 때마다 늘어난다. 다른 노드가 계속 쓰는 상황을 만든다.
        private final AtomicLong head;
        private final long headStep;

        RecordingReplayService(JournalCheckpoint checkpoint, long head, long headStep) {
            super(null, null, null, null, null);
            this.checkpoint = checkpoint;
            this.head = new AtomicLong(head);
            this.headStep = headStep;
        }

        @Override
        public JournalCheckpoint materializedThrough(long fromCmdSeq) {
            return checkpoint;
        }

        @Override
        public long journalHead() {
            return head.getAndAdd(headStep);
        }

        @Override
        public ReplayResult verifyTo(MatchingEngine engine, JournalCheckpoint bound) {
            bounds.add(bound);
            long from = engine.lastAppliedSeq();
            long applied = bound.commandSeq();
            return new ReplayResult(applied, applied - from, applied - from, List.of());
        }

        @Override
        public ReplayResult replayTail(MatchingEngine engine, long upperCmdSeq) {
            // 2 단계는 상한을 JournalCheckpoint 로 받지 않는다. 기록 형태를 맞춘다.
            bounds.add(new JournalCheckpoint(upperCmdSeq));
            return new ReplayResult(upperCmdSeq, 0, 0, List.of());
        }
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
                new JournalCheckpoint(100), 130, 0);
        try (LeaderLeaseService lease = leader()) {
            EngineRecovery recovery = new EngineRecovery(replay, events -> {
            }, lease);

            recovery.recover(new MatchingEngineImpl());

            assertFalse(replay.bounds.isEmpty(), "재생이 한 번도 불리지 않았다");
            for (JournalCheckpoint bound : replay.bounds) {
                assertNotNull(bound, "상한 없는 재생이 승격 경로에 남아 있다");
            }
        }
    }

    @Test
    void 두단계_상한은_그_시점의_head이고_이후_증가를_따라가지_않는다() {
        // head 가 부를 때마다 40 씩 는다. 다른 노드가 계속 쓰는 상황이다.
        RecordingReplayService replay = new RecordingReplayService(
                new JournalCheckpoint(100), 130, 40);
        try (LeaderLeaseService lease = leader()) {
            EngineRecovery recovery = new EngineRecovery(replay, events -> {
            }, lease);

            recovery.recover(new MatchingEngineImpl());

            assertEquals(2, replay.bounds.size(), "1 단계와 2 단계 두 번이어야 한다");
            assertEquals(100, replay.bounds.get(0).commandSeq(),
                    "1 단계 상한이 물질화 경계가 아니다");

            JournalCheckpoint tail = replay.bounds.get(1);
            assertEquals(170, tail.commandSeq(),
                    "2 단계 상한이 그 시점에 잡은 head 가 아니다. 늘어난 head 를 따라가면 끝나지 않는다");
        }
    }

    @Test
    void 초과분이_없으면_두단계로_넘어가지_않는다() {
        RecordingReplayService replay = new RecordingReplayService(
                new JournalCheckpoint(100), 100, 0);
        try (LeaderLeaseService lease = leader()) {
            EngineRecovery recovery = new EngineRecovery(replay, events -> {
            }, lease);

            ReplayResult result = recovery.recover(new MatchingEngineImpl());

            assertEquals(1, replay.bounds.size(), "초과분이 없는데 2 단계가 돌았다");
            assertEquals(100, result.lastAppliedSeq());
        }
    }
}

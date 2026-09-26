package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngine;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.JournalCheckpoints;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static kr.exchange.engine.lease.ReservationFixture.BTC;
import static kr.exchange.engine.lease.ReservationFixture.ETH;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static kr.exchange.engine.lease.ReservationFixture.KRW_ETH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 적용 도중의 팔로워 인계(S4 설계 4-10·6-2, DB 설계 §5.4-c 7).
 *
 * 팔로워 틱이 마켓을 적용하는 중에(명령 사이, 또는 명령을 적용한 뒤 대조 전) 승격이 인계를 요청한다. 시드를 바꿔 200회 돈다.
 * 어느 회차든 설치한 북의 상태 해시와 커서가 저널을 head 까지 재생한 기준값과 같다. 인계는 상한 안에 끝나 재생을
 * 생략하거나, 상한을 넘기면 fresh 재생으로 간다. 생략 설치는 인계가 완료된 경우에만 일어나고, 인계한 마켓은 그 뒤 틱이
 * 만지지 않는다.
 *
 * 상한은 회차마다 100ms 나 400ms 로 줄여 돈다(PromotionCoordinator.setHandoverLimit). 운영 값 2초로 넘김 회차를 돌면
 * 회차마다 2초가 든다. 상수 값 자체는 따로 단언한다.
 */
class FollowerHandoverRaceTest {

    private static final String RESOURCE = "matching-engine";
    private static final int ITERATIONS = 200;
    private static final int COMMANDS_PER_MARKET = 12;

    static ReservationFixture f;
    static LeaderLeaseService lease;
    static LeaderTerm term;
    static ReplayService plainReplay;
    static CommandJournalReader commandReader;
    static final Map<Integer, Long> HEAD = new TreeMap<>();
    static final Map<Integer, Long> HEAD_HASH = new TreeMap<>();
    static int totalApplies;

    @BeforeAll
    static void journal() throws Exception {
        f = ReservationFixture.create("pml_handover_race", 2);
        long alice = f.user("alice", new BigDecimal("100000000"));
        long bob = f.user("bob", BigDecimal.ZERO);
        f.deposit(bob, BTC, new BigDecimal("100"));
        f.deposit(bob, ETH, new BigDecimal("100"));
        lease = f.lease();
        term = lease.currentTerm().orElseThrow();
        CommandJournalAppender appender = f.appender(lease, true);
        SettlementService settlement = f.settlement(lease);
        MatchingEngineImpl live = new MatchingEngineImpl();
        // 쉬는 호가와 체결이 섞이게 넣는다. 매도는 가격을 올려 가며 쉬고, 세 번째마다 매수가 맨 아래 매도를 친다.
        for (int market : new int[] {KRW_BTC, KRW_ETH}) {
            for (int i = 0; i < COMMANDS_PER_MARKET; i++) {
                if (i % 3 == 2) {
                    f.submit(appender, live, settlement,
                            f.limit(market, alice, OrderCommand.Side.BUY, String.valueOf(50000 + i), "0.1"));
                } else {
                    f.submit(appender, live, settlement,
                            f.limit(market, bob, OrderCommand.Side.SELL, String.valueOf(50000 + i), "0.1"));
                }
            }
        }
        ObjectMapper objectMapper = new ObjectMapper();
        commandReader = new CommandJournalReader(f.app);
        plainReplay = new ReplayService(commandReader, new EventJournalReader(f.app), f.mapper, objectMapper,
                new MarketCatalog(f.app));
        // 기준값: 새 엔진에 저널을 head 까지 재생한 커서와 상태 해시.
        MatchingEngineImpl reference = new MatchingEngineImpl();
        JournalCheckpoints bounds = plainReplay.materializedThroughByMarket(reference);
        for (int market : new int[] {KRW_BTC, KRW_ETH}) {
            ReplayResult replayed = plainReplay.verifyToMarket(reference, market, bounds);
            totalApplies += (int) replayed.replayedCommands();
            HEAD.put(market, reference.appliedSeq(market));
            HEAD_HASH.put(market, reference.orderBook(market).stateChecksum());
        }
        assertEquals(HEAD, new TreeMap<>(commandReader.maxSeqByMarket()), "기준 재생이 head 에 닿지 않았다");
        assertEquals(live.orderBook(KRW_BTC).stateChecksum(), (long) HEAD_HASH.get(KRW_BTC));
        assertEquals(live.orderBook(KRW_ETH).stateChecksum(), (long) HEAD_HASH.get(KRW_ETH));
        assertTrue(f.count("SELECT count(*) FROM trades") > 0, "체결이 섞이지 않았다");
    }

    @AfterAll
    static void close() {
        if (f != null) {
            f.close();
        }
    }

    @Test
    void operationalHandoverLimitIsTwoSeconds() {
        assertEquals(Duration.ofSeconds(2), PromotionCoordinator.HANDOVER_LIMIT);
    }

    @Test
    void handoverDuringApplyInstallsExactlyTheJournalState() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine-promotion-race");
            t.setDaemon(true);
            return t;
        });
        int skipped = 0;
        int fresh = 0;
        try {
            for (int seed = 0; seed < ITERATIONS; seed++) {
                if (iteration(seed, worker)) {
                    skipped++;
                } else {
                    fresh++;
                }
            }
        } finally {
            worker.shutdownNow();
        }
        // 두 길이 모두 돌았어야 경합을 본 것이다.
        assertTrue(skipped > 0, "생략 설치가 한 번도 일어나지 않았다");
        assertTrue(fresh > 0, "fresh 재생이 한 번도 일어나지 않았다");
    }

    /**
     * 인계한 뒤 저널이 자라도 팔로워 틱은 인계한 마켓을 만지지 않는다(설치 뒤 팔로워가 그 마켓을 만지지 않는다). 대조군으로
     * 인계를 되돌리면 같은 틱이 새 명령을 적용해, 인계 뒤 틱에 적용할 것이 실제로 있었음을 보인다.
     */
    @Test
    void ticksAfterHandoverLeaveTheHandedMarketAloneWhileTheJournalGrows() throws Exception {
        try (ReservationFixture g = ReservationFixture.create("pml_handover_grow", 2)) {
            long alice = g.user("alice", new BigDecimal("100000000"));
            long bob = g.user("bob", BigDecimal.ZERO);
            g.deposit(bob, BTC, new BigDecimal("100"));
            LeaderLeaseService leader = g.lease();
            CommandJournalAppender appender = g.appender(leader, true);
            SettlementService settlement = g.settlement(leader);
            MatchingEngineImpl live = new MatchingEngineImpl();
            CommandJournalReader reader = new CommandJournalReader(g.app);
            for (int i = 0; i < 6; i++) {
                journalTrade(g, appender, live, settlement, alice, bob, i);
            }
            FollowerCatchUp follower = new FollowerCatchUp(new ReplayService(reader, new EventJournalReader(g.app),
                    g.mapper, new ObjectMapper(), new MarketCatalog(g.app)), PartitionPlan.single(), Map.of(),
                    Duration.ofHours(1), new SimpleMeterRegistry());
            follower.tick();
            assertEquals(reader.maxSeqForMarket(KRW_BTC), follower.appliedSeq(KRW_BTC), "팔로워가 head 에 닿지 않았다");

            MatchingEngineImpl handed = follower.handOver(RESOURCE, Duration.ofSeconds(1)).engines().get(KRW_BTC);
            long handedSeq = handed.appliedSeq(KRW_BTC);
            long handedHash = handed.orderBook(KRW_BTC).stateChecksum();
            for (int i = 6; i < 12; i++) {
                journalTrade(g, appender, live, settlement, alice, bob, i);
            }
            long grown = reader.maxSeqForMarket(KRW_BTC);
            assertTrue(grown > handedSeq, "인계 뒤 저널이 자라지 않았다");

            follower.tick();
            follower.tick();
            assertEquals(handedSeq, handed.appliedSeq(KRW_BTC), "인계 뒤 틱이 인계 엔진을 전진시켰다");
            assertEquals(handedHash, handed.orderBook(KRW_BTC).stateChecksum(), "인계 뒤 틱이 인계 엔진의 북을 바꿨다");

            follower.resume(RESOURCE);
            follower.tick();
            assertEquals(grown, handed.appliedSeq(KRW_BTC), "되돌린 팔로워가 새 명령을 적용하지 않았다(대조군)");
            assertEquals(live.orderBook(KRW_BTC).stateChecksum(), handed.orderBook(KRW_BTC).stateChecksum());
        }
    }

    /** 쉬는 매도 하나와 세 번째마다 맨 아래 매도를 치는 매수를 저널에 적고 정산한다. */
    private static void journalTrade(ReservationFixture g, CommandJournalAppender appender, MatchingEngineImpl live,
                                     SettlementService settlement, long buyer, long seller, int i) {
        OrderCommand.Side side = i % 3 == 2 ? OrderCommand.Side.BUY : OrderCommand.Side.SELL;
        g.submit(appender, live, settlement,
                g.limit(KRW_BTC, side == OrderCommand.Side.BUY ? buyer : seller, side, String.valueOf(50000 + i), "0.1"));
    }

    /** @return 이 회차가 인계 엔진을 설치했으면(재생 생략) 참 */
    private boolean iteration(int seed, ExecutorService worker) throws Exception {
        Random random = new Random(seed);
        Latch latch = new Latch(1 + random.nextInt(totalApplies), random.nextBoolean());
        long limitMillis = random.nextBoolean() ? 100 : 400;
        long releaseAfterMillis = random.nextInt(300);
        String at = "seed=" + seed + ", latch=" + latch.at + (latch.mid ? "(적용 뒤)" : "(명령 사이)")
                + ", limit=" + limitMillis + "ms, release=" + releaseAfterMillis + "ms";

        FollowerCatchUp follower = new FollowerCatchUp(new LatchingReplay(latch), PartitionPlan.single(), Map.of(),
                Duration.ofHours(1), new SimpleMeterRegistry());
        WriterRouter router = new WriterRouter((marketId, engine, loopTerm, slot) -> new WriterRouter.Shard(engine,
                new SingleWriterLoop(16, engine, events -> {
                    throw new IllegalStateException("이 시험은 명령을 넣지 않는다");
                }, new JournalCatchUpSource(commandReader, f.mapper, marketId), t -> { })));
        EngineRecovery recovery = new EngineRecovery(plainReplay, (loopTerm, events) -> {
            throw new IllegalStateException("저널이 모두 물질화돼 재적재할 것이 없다");
        }, null, n -> { });
        PromotionCoordinator coordinator = new PromotionCoordinator(RESOURCE, marketId -> true, recovery, router, lease,
                commandReader, AvailabilitySignal.NOOP, follower, worker);
        coordinator.setHandoverLimit(Duration.ofMillis(limitMillis));

        Thread tick = new Thread(follower::tick, LatchingReplay.TICK_THREAD);
        tick.setDaemon(true);
        tick.start();
        try {
            if (!latch.reached.await(10, TimeUnit.SECONDS)) {
                fail("틱이 래치에 닿지 않았다: " + at);
            }
            coordinator.onTermGained(term);
            Thread.sleep(releaseAfterMillis);
            latch.release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (coordinator.state() != PromotionCoordinator.State.ACCEPTING && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(PromotionCoordinator.State.ACCEPTING, coordinator.state(), "승격이 끝나지 않았다: " + at);
            tick.join(10_000);
            assertFalse(tick.isAlive(), "틱이 끝나지 않았다: " + at);

            // 설치한 북은 어느 길이든 저널을 head 까지 재생한 상태와 같다.
            for (int market : HEAD.keySet()) {
                MatchingEngineImpl installed = router.engine(market);
                assertNotNull(installed, "설치된 엔진이 없다: market=" + market + ", " + at);
                assertEquals((long) HEAD.get(market), installed.appliedSeq(market), "설치 커서: market=" + market + ", " + at);
                assertEquals((long) HEAD_HASH.get(market), installed.orderBook(market).stateChecksum(),
                        "설치 북 해시가 저널 재생과 다르다: market=" + market + ", " + at);
            }

            // 어느 길로 설치했는가. 생략 설치는 인계 엔진의 북을 그대로 들고 있다(PromotionCoordinator.installedEngine).
            FollowerCatchUp.Handover again = follower.handOver(RESOURCE, Duration.ofSeconds(1));
            MatchingEngineImpl installedView = coordinator.installedEngine();
            boolean skippedReplay = !again.engines().isEmpty() && HEAD.keySet().stream().allMatch(market -> {
                MatchingEngineImpl handed = again.engines().get(market);
                return handed != null && installedView.orderBook(market) == handed.orderBook(market);
            });
            if (releaseAfterMillis > limitMillis + 50) {
                assertFalse(skippedReplay, "인계가 상한을 넘겼는데 재생을 생략했다: " + at);
            }
            Map<Integer, long[]> handedState = new TreeMap<>();
            if (skippedReplay) {
                // 생략 설치의 인계 엔진은 head 에 닿아 있고 저널 재생과 같은 북이다.
                for (int market : HEAD.keySet()) {
                    MatchingEngineImpl handed = again.engines().get(market);
                    assertEquals((long) HEAD.get(market), handed.appliedSeq(market), at);
                    assertEquals((long) HEAD_HASH.get(market), handed.orderBook(market).stateChecksum(), at);
                    handedState.put(market, new long[] {handed.appliedSeq(market), handed.orderBook(market).stateChecksum()});
                }
            }
            // 인계한 뒤의 틱은 그 마켓을 만지지 않는다. 설치한 루프 엔진은 복사본이라 팔로워와 무관하다.
            follower.tick();
            follower.tick();
            for (int market : HEAD.keySet()) {
                MatchingEngineImpl installed = router.engine(market);
                assertEquals((long) HEAD.get(market), installed.appliedSeq(market), at);
                assertEquals((long) HEAD_HASH.get(market), installed.orderBook(market).stateChecksum(), at);
                long[] before = handedState.get(market);
                if (before != null) {
                    MatchingEngineImpl handed = again.engines().get(market);
                    assertEquals(before[0], handed.appliedSeq(market), "인계 뒤 틱이 인계 엔진을 전진시켰다: " + at);
                    assertEquals(before[1], handed.orderBook(market).stateChecksum(), "인계 뒤 틱이 북을 바꿨다: " + at);
                }
            }
            return skippedReplay;
        } finally {
            latch.release.countDown();
            tick.join(10_000);
            router.stopAll();
        }
    }

    /** 틱 스레드의 k 번째 적용에서 멈추는 래치. */
    private static final class Latch {
        final int at;
        // 참이면 명령을 적용한 뒤(대조 전), 거짓이면 명령 사이(적용 전)에서 멈춘다.
        final boolean mid;
        final AtomicInteger applies = new AtomicInteger();
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Latch(int at, boolean mid) {
            this.at = at;
            this.mid = mid;
        }
    }

    /** 틱 스레드에서만 래치를 거는 재생. 승격 재생은 다른 인스턴스를 쓴다. */
    private static final class LatchingReplay extends ReplayService {
        static final String TICK_THREAD = "handover-race-tick";
        private final Latch latch;

        LatchingReplay(Latch latch) {
            super(commandReader, new EventJournalReader(f.app), f.mapper, new ObjectMapper(), new MarketCatalog(f.app));
            this.latch = latch;
        }

        @Override
        public ReplayResult verifyToMarket(MatchingEngine engine, int marketId, JournalCheckpoints checkpoints) {
            if (!TICK_THREAD.equals(Thread.currentThread().getName())) {
                return super.verifyToMarket(engine, marketId, checkpoints);
            }
            return super.verifyToMarket(new MatchingEngine() {
                @Override
                public List<EngineEvent> apply(OrderCommand command) {
                    boolean hit = latch.applies.incrementAndGet() == latch.at;
                    if (hit && !latch.mid) {
                        pause();
                    }
                    List<EngineEvent> events = engine.apply(command);
                    if (hit && latch.mid) {
                        pause();
                    }
                    return events;
                }

                @Override
                public long appliedSeq(int market) {
                    return engine.appliedSeq(market);
                }

                @Override
                public long lastAppliedSeq() {
                    return engine.lastAppliedSeq();
                }

                @Override
                public long stateChecksum() {
                    return engine.stateChecksum();
                }
            }, marketId, checkpoints);
        }

        private void pause() {
            latch.reached.countDown();
            try {
                latch.release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

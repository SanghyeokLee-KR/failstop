package kr.exchange.engine;

import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.core.SnapshotCodec;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;
import kr.exchange.journal.CommandJournalReader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 승격 재생 생략 경로가 라이터에 설치하는지 본다.
 *
 * 생략 조건은 설치 엔진이 이미 저널 head 에 닿은 경우다. 그때 재생은 생략해도 되지만 설치까지
 * 생략하면 마켓별 라이터의 카운터가 부팅값에 멈춘 채 게이트가 열린다. 첫 명령이 갭으로 보이고
 * catch-up 이 이미 북에 반영된 구간을 다시 적용해 체결이 중복으로 난다.
 *
 * DB 를 쓰지 않는다. 판정 대상이 라우터 엔진의 카운터와 재적용 여부이지 저장 내용이 아니다.
 */
class PromotionSkipInstallTest {

    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Duration TICK = Duration.ofSeconds(3);
    private static final long BASE_TS = 1_721_600_000_000L;
    private static final int MARKET = 1;

    /** 명령 저널 대역. head 만 답한다. */
    private static final class FixedHead extends CommandJournalReader {
        private final long head;

        FixedHead(long head) {
            super(null);
            this.head = head;
        }

        @Override
        public long maxSeq() {
            return head;
        }
    }

    /** 전량 재생 호출 수만 센다. 생략에 실제로 들어갔는지 판정한다. */
    private static final class CountingRecovery extends EngineRecovery {
        private int calls;

        CountingRecovery(LeaderLeaseService lease) {
            super(null, events -> {
            }, lease);
        }

        @Override
        public kr.exchange.journal.ReplayResult recover(MatchingEngineImpl engine) {
            calls++;
            return new kr.exchange.journal.ReplayResult(0, 0, 0, List.of());
        }
    }

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

    private final List<OrderCommand> journal = new ArrayList<>();
    private final List<EngineEvent> sunk = new CopyOnWriteArrayList<>();

    private WriterRouter router() {
        return new WriterRouter(marketId -> {
            MatchingEngineImpl engine = new MatchingEngineImpl();
            SingleWriterLoop loop = new SingleWriterLoop(64, engine,
                    sunk::addAll,
                    (lastApplied, limit) -> journal.stream()
                            .filter(c -> c.seq() > lastApplied && c.marketId() == marketId)
                            .limit(limit).toList(),
                    t -> {
                    });
            return new WriterRouter.Shard(engine, loop);
        });
    }

    @Test
    void 승격_생략_경로가_라이터에_설치한다() throws Exception {
        MatchingEngineImpl global = new MatchingEngineImpl();
        apply(global, sell(1, 1, "100", "5"));
        apply(global, buy(2, 2, "100", "1"));
        apply(global, sell(3, 3, "101", "3"));
        apply(global, buy(4, 4, "99", "2"));

        WriterRouter router = router();
        router.installFrom(global);
        router.startAll();

        // 별칭이 끊겼는지 먼저 본다. 끊기지 않았으면 아래 팔로워 모사가 라이터 북까지 전진시켜
        // 이 시험이 무엇을 판정하는지 알 수 없게 된다.
        assertNotSame(global.orderBook(MARKET), router.engine(MARKET).orderBook(MARKET),
                "라우터가 북을 복사하지 않았다");
        assertEquals(4, router.engine(MARKET).lastAppliedSeq());

        // 팔로워 구간 모사. 전역 엔진만 전진한다. FollowerCatchUp.advance 가 하는 일과 같다.
        apply(global, buy(5, 5, "100", "2"));
        apply(global, sell(6, 6, "102", "1"));
        apply(global, buy(7, 7, "98", "1"));

        // 양성 증거 축. 판정 대상이 실제로 존재하는지 먼저 증명한다.
        assertEquals(7, global.lastAppliedSeq(), "팔로워 구간이 전진하지 않았다");
        assertEquals(4, router.engine(MARKET).lastAppliedSeq(),
                "라이터 카운터가 이미 전진했다면 이 시험은 결함을 판정하지 못한다");
        byte[] bookBefore = SnapshotCodec.encode(global.orderBook(MARKET));

        CountingRecovery recovery;
        try (LeaderLeaseService lease = new LeaderLeaseService(new MemoryStore(),
                FencingEpochPublisher.NONE, TTL, TICK, NodeServiceHealth.ALWAYS_SERVICEABLE)) {
            recovery = new CountingRecovery(lease);
            PromotionCoordinator coordinator = new PromotionCoordinator(
                    recovery, router, lease, new FixedHead(7), AvailabilitySignal.NOOP);
            coordinator.bootstrap(global, false);
            coordinator.arm();
            lease.onLeadershipGained(coordinator::onLeadershipGained);

            assertTrue(lease.tryAcquire(), "검증 전제인 리더 승격이 실패했다");
            await(() -> coordinator.state() == PromotionCoordinator.State.ACCEPTING);
        }

        // 생략에 실제로 들어갔는지. 전량 재생이 돌았으면 이 시험은 다른 경로를 잰 것이다.
        assertEquals(0, recovery.calls, "생략 경로가 아니라 전량 재생이 돌았다");

        assertEquals(7, router.engine(MARKET).lastAppliedSeq(),
                "생략 경로가 라이터에 설치하지 않아 카운터가 부팅값에 멈춰 있다");

        sunk.clear();
        journal.add(sell(8, 8, "103", "1"));
        assertTrue(router.offer(journal.get(journal.size() - 1)));
        await(() -> router.engine(MARKET).lastAppliedSeq() == 8);

        assertEquals(Set.of(8L),
                sunk.stream().map(EngineEvent::sourceCmdSeq).collect(Collectors.toSet()),
                "이미 반영된 명령이 갭으로 보여 다시 적용됐다");
        assertEquals(0, sunk.stream().filter(e -> e.type() == EngineEvent.EventType.TRADE).count(),
                "재적용이 레스팅 잔량을 다시 깎아 체결이 중복으로 났다");

        MatchingEngineImpl expected = new MatchingEngineImpl();
        expected.loadSnapshot(7, List.of(SnapshotCodec.decode(bookBefore)));
        expected.apply(journal.get(journal.size() - 1));
        assertArrayEquals(SnapshotCodec.encode(expected.orderBook(MARKET)),
                SnapshotCodec.encode(router.engine(MARKET).orderBook(MARKET)),
                "라이터 북이 팔로워 북에 seq 8 만 더한 상태와 다르다");
        // 바이트 비교는 exportEntries 계열이라 bidSizes·askSizes 를 덮지 않는다. 단 잔량 맵을
        // 따로 건다. 그 맵이 손상돼도 바이트는 같게 나온다.
        assertEquals(expected.orderBook(MARKET).bidDepth(10),
                router.engine(MARKET).orderBook(MARKET).bidDepth(10),
                "매수 단 잔량 맵이 다르다");
        assertEquals(expected.orderBook(MARKET).askDepth(10),
                router.engine(MARKET).orderBook(MARKET).askDepth(10),
                "매도 단 잔량 맵이 다르다");

        router.stopAll();
    }

    @Test
    void 정상_갭_회수는_그대로_돈다() throws Exception {
        MatchingEngineImpl global = new MatchingEngineImpl();
        for (int i = 1; i <= 4; i++) {
            OrderCommand c = buy(i, i, "9" + i, "1");
            journal.add(c);
            apply(global, c);
        }

        WriterRouter router = router();
        router.installFrom(global);
        router.startAll();

        // 라이터가 진짜로 뒤처진 상태를 만든다. 전역 엔진도 전진시키지 않는다.
        for (int i = 5; i <= 7; i++) {
            journal.add(buy(i, i, "9" + (i % 10), "1"));
        }
        OrderCommand last = buy(8, 8, "97", "1");
        journal.add(last);

        assertEquals(4, router.engine(MARKET).lastAppliedSeq());
        assertTrue(router.offer(last));
        await(() -> router.engine(MARKET).lastAppliedSeq() == 8);

        assertTrue(sunk.stream().map(EngineEvent::sourceCmdSeq).anyMatch(s -> s == 5L),
                "회수 경로가 죽었다. 뒤처진 라이터가 저널에서 명령을 되읽지 못한다");
        router.stopAll();
    }

    @Test
    void 승격_이후_생긴_마켓은_설치_진행도에서_출발한다() throws Exception {
        MatchingEngineImpl global = new MatchingEngineImpl();
        apply(global, buy(1, 1, "100", "1"));

        WriterRouter router = router();
        router.installFrom(global);
        router.startAll();

        // 부팅 복구본에 없던 마켓이다. 0 에서 출발하면 그 마켓의 전 이력을 회수해 재정산한다.
        assertEquals(1, router.engine(2).lastAppliedSeq(),
                "지연 생성 샤드가 설치 진행도가 아니라 0 에서 출발한다");
        router.stopAll();
    }

    private static void apply(MatchingEngineImpl engine, OrderCommand command) {
        engine.apply(command);
    }

    private static OrderCommand buy(long seq, long orderId, String price, String qty) {
        return order(seq, orderId, OrderCommand.Side.BUY, price, qty);
    }

    private static OrderCommand sell(long seq, long orderId, String price, String qty) {
        return order(seq, orderId, OrderCommand.Side.SELL, price, qty);
    }

    private static OrderCommand order(long seq, long orderId, OrderCommand.Side side,
                                      String price, String qty) {
        return new OrderCommand(seq, MARKET, OrderCommand.CommandType.NEW, 1, "c-" + orderId,
                orderId, BASE_TS + seq, seq, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal(qty), null, 1);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("5초 내 조건 미충족");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기 중 인터럽트", e);
            }
        }
    }
}

package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.core.SnapshotCodec;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.harness.RecoveryRunRecorder;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import kr.exchange.snapshot.SnapshotCapture;
import kr.exchange.snapshot.SnapshotPersister;
import kr.exchange.snapshot.SnapshotSource;
import kr.exchange.snapshot.SnapshotStore;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 스냅샷 훅 배선(ADR 0022 7단계). 운영 배선(EngineConfig.writerRouter)처럼 샤드마다 SnapshotCapture 를 걸고 영속화 워커
 * 하나를 나눠 쓴다. 래치 전 전역 채번과 래치 뒤 대역 채번 두 모드에서 마켓별 캡처, 스냅샷에서 꼬리만 재생하는 복구,
 * 페일오버 뒤 재생 결정론(C17), 갭 회수와 커서 0 출발, 스냅샷 시작 재생이 실패할 때의 폴백을 본다. 시험마다 같은
 * 컨테이너의 별도 데이터베이스를 쓴다.
 */
@Testcontainers
class SnapshotHookWiringIntegrationTest {

    enum Numbering { GLOBAL, BAND }

    private static final List<Integer> MARKETS = List.of(1, 2, 5, 12);
    private static final int KRW = 1;
    private static final long BASE_TS = 1_758_100_000_000L;
    private static final long CAPTURE_INTERVAL = 4;
    private static final Duration AWAIT = Duration.ofSeconds(90);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final AtomicLong ORDER_IDS = new AtomicLong(1);

    /**
     * 캡처가 마켓마다 그 마켓 커서로 찍힌다. 커서가 높은 마켓부터 채운다. 캡처 하나를 샤드끼리 나누면 대역 모드에서
     * 마켓 12 캡처가 워터마크를 올려 나머지 마켓의 캡처를 막는다.
     */
    @ParameterizedTest
    @EnumSource(Numbering.class)
    void capturesEachMarketAtItsOwnCursor(Numbering numbering) throws Exception {
        try (Cluster c = new Cluster("snap_capture", numbering)) {
            Node a = c.leader();
            a.router.startAll();
            EngineBridge bridge = a.bridge(AcceptGate.ALWAYS_OPEN);
            Map<Integer, List<Long>> acks = new TreeMap<>();
            for (int marketId : List.of(12, 5, 2, 1)) {
                acks.put(marketId, c.trade(bridge, marketId, 5));
            }
            c.awaitMaterialized(a, heads(acks));
            a.router.stopAll();
            c.awaitSnapshotsPersisted(a, acks.keySet());

            assertEquals(MARKETS.size(), c.count("SELECT count(DISTINCT market_id) FROM snapshots"));
            assertEquals(0, c.count("""
                    SELECT count(*) FROM snapshots s
                    WHERE NOT EXISTS (SELECT 1 FROM command_journal j
                                      WHERE j.market_id = s.market_id AND j.seq = s.last_applied_seq)
                    """), "스냅샷 행의 last_applied_seq 가 그 마켓의 명령이 아니다");
            assertEquals(0, c.count("SELECT count(*) FROM snapshots WHERE fencing_epoch <> " + a.epoch()));
            if (numbering == Numbering.BAND) {
                assertTrue(c.count("SELECT max(last_applied_seq) FROM snapshots WHERE market_id = 1")
                                < c.count("SELECT min(last_applied_seq) FROM snapshots WHERE market_id = 12"),
                        "낮은 대역 마켓 캡처가 높은 대역 마켓 커서 아래에 있지 않아 공유 캡처 결함을 판정하지 못한다");
            }

            Map<Integer, SnapshotSource.MarketLoaded> adopted = c.store.loadLatestAdoptablePerMarket();
            assertEquals(acks.keySet(), adopted.keySet());
            for (Map.Entry<Integer, SnapshotSource.MarketLoaded> e : adopted.entrySet()) {
                int marketId = e.getKey();
                MatchingEngineImpl upTo = new MatchingEngineImpl();
                c.replay.replayTailMarket(upTo, marketId, e.getValue().seq());
                assertEquals(e.getValue().seq(), upTo.appliedSeq(marketId));
                assertArrayEquals(SnapshotCodec.encode(upTo.orderBook(marketId)),
                        SnapshotCodec.encode(e.getValue().book()), "마켓 " + marketId + " 스냅샷 북이 그 커서까지 재생한 북과 다르다");
            }
        }
    }

    /** 복구가 마켓마다 채택한 스냅샷 커서 뒤 명령만 재생하고, 전량 재생과 같은 북에 닿는다. */
    @ParameterizedTest
    @EnumSource(Numbering.class)
    void recoveryFromSnapshotsReplaysOnlyTheTail(Numbering numbering) throws Exception {
        try (Cluster c = new Cluster("snap_tail", numbering)) {
            Node a = c.leader();
            a.router.startAll();
            EngineBridge bridge = a.bridge(AcceptGate.ALWAYS_OPEN);
            Map<Integer, List<Long>> covered = c.tradeAll(bridge, 4);
            c.awaitMaterialized(a, heads(covered));
            a.pauseCapture();
            c.awaitSnapshotsPersisted(a, covered.keySet());
            Map<Integer, Long> heads = heads(c.tradeAll(bridge, 3));
            c.awaitMaterialized(a, heads);
            a.router.stopAll();

            Map<Integer, SnapshotSource.MarketLoaded> adopted = c.store.loadLatestAdoptablePerMarket();
            assertEquals(heads.keySet(), adopted.keySet(), "스냅샷이 없는 마켓이 있어 마켓마다 꼬리를 판정하지 못한다");
            long tail = 0;
            for (int marketId : heads.keySet()) {
                tail += c.count("SELECT count(*) FROM command_journal WHERE market_id = " + marketId
                        + " AND seq > " + adopted.get(marketId).seq());
            }
            long total = c.count("SELECT count(*) FROM command_journal");
            assertTrue(tail > 0 && tail < total, "꼬리가 비었거나 스냅샷이 아무것도 덮지 않았다: tail=" + tail + ", total=" + total);

            MatchingEngineImpl recovered = new MatchingEngineImpl();
            ReplayResult result = a.recovery.recover(recovered);

            assertEquals(tail, result.replayedCommands(), "스냅샷 커서 뒤 꼬리만 재생하지 않았다");
            assertTrue(result.verifiedEvents() > 0, "꼬리 재생이 이벤트를 대조하지 않았다");
            assertTrue(result.pendingBatches().isEmpty());
            assertEquals(0, a.recovery.snapshotFallbacks(), "스냅샷 시작 복구가 폴백했다");
            assertBooksEqual(a.router, recovered, heads);
            long maxSnapshotSeq = adopted.values().stream().mapToLong(SnapshotSource.MarketLoaded::seq).max().orElseThrow();
            assertEquals(List.of(true), matchedInOrder(c));
            assertEquals(maxSnapshotSeq, c.count("SELECT replayed_from_seq FROM recovery_runs"),
                    "recovery_runs 재생 시작이 스냅샷 커서가 아니다");

            MatchingEngineImpl full = new MatchingEngineImpl();
            ReplayResult fullResult = new EngineRecovery(c.replay, events -> fail("정산이 끝난 저널에 재적재 대상이 있다"),
                    a.lease).recover(full);
            assertEquals(total, fullResult.replayedCommands());
            assertBooksEqual(a.router, full, heads);
            c.assertNoLossNoDoubleApply();
        }
    }

    /** 스냅샷을 쥔 채 페일오버해도 승격 재생이 스냅샷 커서에서 결정론으로 끝나고, 구 리더 신원으로는 스냅샷을 쓸 수 없다. */
    @ParameterizedTest
    @EnumSource(Numbering.class)
    void failoverReplaysFromSnapshotsDeterministically(Numbering numbering) throws Exception {
        int crashed = 5;
        try (Cluster c = new Cluster("snap_failover", numbering)) {
            Node a = c.leader();
            a.router.startAll();
            EngineBridge bridgeA = a.bridge(AcceptGate.ALWAYS_OPEN);
            Map<Integer, List<Long>> before = c.tradeAll(bridgeA, 4);
            c.awaitMaterialized(a, heads(before));
            a.pauseCapture();
            c.awaitSnapshotsPersisted(a, before.keySet());
            a.hold(crashed);
            Map<Integer, List<Long>> window = c.tradeAll(bridgeA, 2);
            c.awaitMaterialized(a, without(heads(window), Set.of(crashed)));
            LeaderIdentity stale = a.lease.requireLeader();
            a.router.haltAll();
            assertTrue(c.unmaterialized(crashed) > 0, "승격 전에 크래시 창이 없어 재적재 판정이 공허하다");

            Node b = c.node();
            PromotionCoordinator coordinator = new PromotionCoordinator(b.recovery, b.router, b.lease, c.commands,
                    AvailabilitySignal.NOOP);
            b.lease.onLeadershipGained(coordinator::onLeadershipGained);
            b.lease.onLeadershipLost(coordinator::onLeadershipLost);
            b.router.startAll();
            coordinator.bootstrap(new MatchingEngineImpl(), false);
            coordinator.arm();
            try {
                c.takeOver(b);
                Awaitility.await().atMost(AWAIT)
                        .until(() -> coordinator.state() == PromotionCoordinator.State.ACCEPTING);
                assertEquals(0, c.unmaterialized(), "승격이 크래시 창을 재적재하지 않았다");
                assertEquals(0, b.recovery.snapshotFallbacks(), "승격 재생이 스냅샷을 버렸다");
                assertEquals(0, c.count(InvariantCheck.C17_REPLAY_DETERMINISM.sql()), "C17");
                assertTrue(c.count("SELECT count(*) FROM recovery_runs WHERE matched AND replayed_from_seq > 0") > 0,
                        "승격 재생이 스냅샷 커서에서 시작한 기록이 없다");

                OrderBookImpl empty = new OrderBookImpl(1);
                SnapshotStore.MarketState probe = new SnapshotStore.MarketState(1, heads(before).get(1),
                        SnapshotCodec.encode(empty), empty.stateChecksum());
                long staleRows = c.count("SELECT count(*) FROM snapshots WHERE fencing_epoch = " + stale.epoch());
                assertThrows(RuntimeException.class, () -> c.store.persist(stale, List.of(probe)),
                        "구 리더 신원의 스냅샷 적재가 거부되지 않았다");
                assertEquals(staleRows, c.count("SELECT count(*) FROM snapshots WHERE fencing_epoch = " + stale.epoch()));

                Map<Integer, List<Long>> after = c.tradeAll(b.bridge(coordinator.gate()), 3);
                c.awaitMaterialized(b, heads(after));
                b.router.stopAll();
                c.awaitSnapshotsPersisted(b, after.keySet());
                assertTrue(b.epoch() > stale.epoch());

                c.assertNoLossNoDoubleApply();
                MatchingEngineImpl recovered = new MatchingEngineImpl();
                EngineRecovery restart = new EngineRecovery(c.replay, events -> fail("승격 뒤 재기동이 다시 재적재했다"),
                        b.lease, c.store);
                assertTrue(restart.recover(recovered).pendingBatches().isEmpty());
                assertEquals(0, restart.snapshotFallbacks());
                assertBooksEqual(b.router, recovered, heads(after));
            } finally {
                coordinator.close();
            }
        }
    }

    /**
     * 스냅샷에서 복구해 설치한 라이터가 잃은 투입을 그 마켓 커서에서 되읽고, 설치 뒤 처음 거래하는 마켓을 커서 0 에서
     * 시작한다. 대역 모드에서는 새 마켓 명령과 마켓 5 의 명령이 설치된 마켓 12 커서보다 작다.
     */
    @ParameterizedTest
    @EnumSource(Numbering.class)
    void gapRecoveryAndCursorZeroStartAfterSnapshotInstall(Numbering numbering) throws Exception {
        try (Cluster c = new Cluster("snap_gap", numbering)) {
            Node a = c.leader();
            a.router.startAll();
            EngineBridge bridgeA = a.bridge(AcceptGate.ALWAYS_OPEN);
            Map<Integer, List<Long>> first = new TreeMap<>();
            for (int marketId : List.of(12, 5)) {
                first.put(marketId, c.trade(bridgeA, marketId, 4));
            }
            c.awaitMaterialized(a, heads(first));
            a.router.stopAll();
            c.awaitSnapshotsPersisted(a, first.keySet());

            MatchingEngineImpl recovered = new MatchingEngineImpl();
            ReplayResult installed = a.recovery.recover(recovered);
            assertEquals(0, a.recovery.snapshotFallbacks(), "설치할 복구본이 스냅샷에서 시작하지 않았다");
            assertTrue(installed.replayedCommands() < c.count("SELECT count(*) FROM command_journal"));
            assertEquals(List.of(5, 12), recovered.markets(), "복구본에 거래한 마켓만 있어야 커서 0 출발을 판정한다");

            Node b = c.leader();
            b.router.installFrom(recovered);
            b.router.startAll();
            EngineBridge bridgeB = b.bridge(AcceptGate.ALWAYS_OPEN);

            long catchUpsBefore = b.router.loop(5).catchUpCalls();
            b.dropNextOffer(5);
            long lost = c.submit(bridgeB, 5, OrderCommand.Side.BUY, "100");
            long next = c.submit(bridgeB, 5, OrderCommand.Side.SELL, "100");
            Map<Integer, List<Long>> fresh = new TreeMap<>();
            for (int marketId : List.of(2, 1)) {
                fresh.put(marketId, c.trade(bridgeB, marketId, 3));
            }
            Map<Integer, Long> heads = new TreeMap<>(heads(fresh));
            heads.put(5, next);
            heads.put(12, heads(first).get(12));
            c.awaitMaterialized(b, heads);
            b.router.stopAll();

            assertTrue(b.router.loop(5).catchUpCalls() > catchUpsBefore, "잃은 투입을 갭 회수가 되읽지 않았다");
            assertTrue(c.count("SELECT count(*) FROM event_journal WHERE source_cmd_seq = " + lost) > 0,
                    "투입을 잃은 명령이 물질화되지 않았다");
            if (numbering == Numbering.BAND) {
                assertTrue(fresh.get(1).get(0) < heads(first).get(12) && lost < heads(first).get(12),
                        "설치된 높은 대역 커서보다 작은 명령이 없어 바닥 규칙을 판정하지 못한다");
            }
            c.assertNoLossNoDoubleApply();
            MatchingEngineImpl full = new MatchingEngineImpl();
            new EngineRecovery(c.replay, events -> fail("정산이 끝난 저널에 재적재 대상이 있다"), b.lease).recover(full);
            assertBooksEqual(b.router, full, heads);
        }
    }

    /**
     * 스냅샷 시작 재생이 저널과 갈리면 불일치 행을 남기고 전량 재생으로 폴백한다. 스냅샷 적재 자체가 실패하면 기록 없이
     * 폴백한다. 둘 다 복구는 저널 전량 재생과 같은 북에 닿는다.
     */
    @ParameterizedTest
    @EnumSource(Numbering.class)
    void fallsBackToFullReplayWhenSnapshotStartFails(Numbering numbering) throws Exception {
        int defective = 2;
        try (Cluster c = new Cluster("snap_fallback", numbering)) {
            Node a = c.leader();
            a.router.startAll();
            EngineBridge bridge = a.bridge(AcceptGate.ALWAYS_OPEN);
            // 매수만 먼저 쌓는다. 뒤이은 매도는 그 매수와 체결한다.
            Map<Integer, List<Long>> bids = new TreeMap<>();
            for (int marketId : MARKETS) {
                bids.put(marketId, c.orders(bridge, marketId, OrderCommand.Side.BUY, "100", 4));
            }
            c.awaitMaterialized(a, heads(bids));
            a.pauseCapture();
            c.awaitSnapshotsPersisted(a, bids.keySet());
            Map<Integer, List<Long>> asks = new TreeMap<>();
            for (int marketId : MARKETS) {
                asks.put(marketId, c.orders(bridge, marketId, OrderCommand.Side.SELL, "100", 2));
            }
            Map<Integer, Long> heads = heads(asks);
            c.awaitMaterialized(a, heads);
            a.router.stopAll();
            assertTrue(c.count("SELECT count(*) FROM trades WHERE market_id = " + defective) > 0,
                    "꼬리 매도가 체결하지 않아 결함 스냅샷이 드러나지 않는다");
            long total = c.count("SELECT count(*) FROM command_journal");

            // 결함 스냅샷. 매수를 모두 적용한 커서에 빈 북을 둔다. 꼬리 매도가 쉴 곳이 없어 저널의 체결과 갈린다.
            Map<Integer, SnapshotSource.MarketLoaded> adopted = c.store.loadLatestAdoptablePerMarket();
            assertEquals(Set.copyOf(MARKETS), adopted.keySet());
            Map<Integer, SnapshotSource.MarketLoaded> broken = new TreeMap<>(adopted);
            broken.put(defective, new SnapshotSource.MarketLoaded(heads(bids).get(defective), new OrderBookImpl(defective)));
            EngineRecovery withBroken = new EngineRecovery(c.replay, events -> fail("재적재 대상이 없어야 한다"),
                    a.lease, () -> broken);
            withBroken.setRecoveryRecorder(new RecoveryRunRecorder(c.jdbc));

            MatchingEngineImpl recovered = new MatchingEngineImpl();
            ReplayResult result = withBroken.recover(recovered);

            assertEquals(1, withBroken.snapshotFallbacks());
            assertEquals(total, result.replayedCommands(), "폴백이 전량 재생이 아니다");
            assertBooksEqual(a.router, recovered, heads);
            assertEquals(List.of(false, true), matchedInOrder(c), "스냅샷 시작 재생의 불일치와 전량 재생의 성공이 차례로 남지 않았다");
            assertEquals(1, c.count(InvariantCheck.C17_REPLAY_DETERMINISM.sql()), "폴백한 결함을 C17 이 보지 못한다");

            EngineRecovery unreadable = new EngineRecovery(c.replay, events -> fail("재적재 대상이 없어야 한다"),
                    a.lease, () -> {
                        throw new IllegalStateException("주입 결함: 스냅샷 판독 실패");
                    });
            unreadable.setRecoveryRecorder(new RecoveryRunRecorder(c.jdbc));
            MatchingEngineImpl again = new MatchingEngineImpl();
            assertEquals(total, unreadable.recover(again).replayedCommands());
            assertEquals(1, unreadable.snapshotFallbacks());
            assertEquals(List.of(false, true, true), matchedInOrder(c), "판독 실패 폴백이 불일치 행을 남겼다");
            assertBooksEqual(a.router, again, heads);
        }
    }

    private static List<Boolean> matchedInOrder(Cluster c) {
        return c.jdbc.sql("SELECT matched FROM recovery_runs ORDER BY id").query(Boolean.class).list();
    }

    private static void assertBooksEqual(WriterRouter live, MatchingEngineImpl other, Map<Integer, Long> heads) {
        for (Map.Entry<Integer, Long> head : heads.entrySet()) {
            int marketId = head.getKey();
            assertEquals(head.getValue(), other.appliedSeq(marketId), "마켓 " + marketId + " 커서");
            OrderBookImpl book = live.engine(marketId).orderBook(marketId);
            assertTrue(book.openOrderCount() > 0, "마켓 " + marketId + " 잔여 주문이 없어 북 비교가 공허하다");
            assertArrayEquals(SnapshotCodec.encode(book), SnapshotCodec.encode(other.orderBook(marketId)),
                    "마켓 " + marketId + " 북");
        }
    }

    private static Map<Integer, Long> heads(Map<Integer, List<Long>> acks) {
        Map<Integer, Long> heads = new TreeMap<>();
        acks.forEach((marketId, seqs) -> heads.put(marketId, seqs.get(seqs.size() - 1)));
        return heads;
    }

    private static Map<Integer, Long> without(Map<Integer, Long> heads, Set<Integer> markets) {
        Map<Integer, Long> rest = new TreeMap<>(heads);
        rest.keySet().removeAll(markets);
        return rest;
    }

    /** 데이터베이스 하나. 대역 모드면 트래픽 전에 래치를 건다. 마켓 m 은 기준 자산 m + 1 을 원화로 거래한다. */
    private static final class Cluster implements AutoCloseable {
        final HikariDataSource dataSource;
        final JdbcClient jdbc;
        final TransactionTemplate tx;
        final ObjectMapper json = new ObjectMapper();
        final CommandPayloadMapper mapper = new CommandPayloadMapper(json);
        final MarketCatalog catalog;
        final CommandJournalReader commands;
        final ReplayService replay;
        final SnapshotStore store;
        private final Map<Integer, Long> sellers = new TreeMap<>();
        private final Map<Integer, Long> buyers = new TreeMap<>();
        private final List<AutoCloseable> closeables = new ArrayList<>();

        Cluster(String name, Numbering numbering) throws SQLException {
            String database = name + "_" + numbering.name().toLowerCase(Locale.ROOT);
            try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()); Statement st = conn.createStatement()) {
                st.execute("CREATE DATABASE " + database);
            }
            String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + database);
            assertTrue(Flyway.configure()
                    .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate().success, "Flyway migration failed");
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(POSTGRES.getUsername());
            config.setPassword(POSTGRES.getPassword());
            config.setMaximumPoolSize(40);
            dataSource = new HikariDataSource(config);
            jdbc = JdbcClient.create(dataSource);
            tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
            catalog = new MarketCatalog(jdbc);
            commands = new CommandJournalReader(jdbc);
            replay = new ReplayService(commands, new EventJournalReader(jdbc), mapper, json, catalog);
            store = new SnapshotStore(jdbc, tx, "engine-m1", "default");

            AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
            bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
            for (int marketId : MARKETS) {
                int coin = marketId + 1;
                bootstrap.asset(coin, "SNAP" + marketId, "Snap Coin " + marketId, "CRYPTO", 8);
                bootstrap.market(marketId, "KRW-SNAP" + marketId, coin, KRW, BigDecimal.ZERO, BigDecimal.ZERO);
                long seller = bootstrap.user("seller" + marketId + "@snap.test");
                long buyer = bootstrap.user("buyer" + marketId + "@snap.test");
                for (long user : new long[] {seller, buyer}) {
                    bootstrap.userAccounts(user, KRW);
                    bootstrap.userAccounts(user, coin);
                }
                bootstrap.deposit(seller, coin, new BigDecimal("1000000"));
                bootstrap.deposit(buyer, KRW, new BigDecimal("1000000000"));
                sellers.put(marketId, seller);
                buyers.put(marketId, buyer);
            }
            if (numbering == Numbering.BAND) {
                assertTrue(jdbc.sql("SELECT latch_command_band_cutover() IS NOT NULL").query(Boolean.class).single(),
                        "트래픽 전 래치 실패");
            }
        }

        Node node() {
            Node node = new Node(this);
            closeables.add(node);
            return node;
        }

        Node leader() {
            Node node = node();
            takeOver(node);
            return node;
        }

        /** 리스를 만료시키고 이 노드를 올린다. 앞 리더의 쓰기는 이 뒤로 fencing 에 막힌다. */
        void takeOver(Node node) {
            jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                            + "WHERE resource = 'matching-engine'")
                    .update();
            assertTrue(node.lease.tryAcquire(), "리더 승격 실패");
        }

        /** 한 마켓에 매도와 매수를 번갈아 넣는다. 매도 100~102 와 매수 99~101 이 겹쳐 체결과 잔여 주문이 함께 남는다. */
        List<Long> trade(EngineBridge bridge, int marketId, int pairs) {
            List<Long> seqs = new ArrayList<>();
            for (int i = 0; i < pairs; i++) {
                seqs.add(submit(bridge, marketId, OrderCommand.Side.SELL, String.valueOf(100 + i % 3)));
                seqs.add(submit(bridge, marketId, OrderCommand.Side.BUY, String.valueOf(101 - i % 3)));
            }
            return seqs;
        }

        Map<Integer, List<Long>> tradeAll(EngineBridge bridge, int pairs) {
            Map<Integer, List<Long>> acks = new TreeMap<>();
            for (int marketId : MARKETS) {
                acks.put(marketId, trade(bridge, marketId, pairs));
            }
            return acks;
        }

        List<Long> orders(EngineBridge bridge, int marketId, OrderCommand.Side side, String price, int count) {
            List<Long> seqs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                seqs.add(submit(bridge, marketId, side, price));
            }
            return seqs;
        }

        long submit(EngineBridge bridge, int marketId, OrderCommand.Side side, String price) {
            long orderId = ORDER_IDS.getAndIncrement();
            String key = "snap-" + orderId;
            long user = side == OrderCommand.Side.SELL ? sellers.get(marketId) : buyers.get(marketId);
            SubmitResult result = bridge.submit(new OrderIntake(marketId, CommandDraft.CommandType.NEW, user, key,
                    orderId, BASE_TS + orderId, orderId, side, OrderCommand.OrdType.LIMIT, OrderCommand.TimeInForce.GTC,
                    new BigDecimal(price), BigDecimal.ONE, null, UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                    BigDecimal.ZERO, BigDecimal.ZERO));
            assertFalse(result.replayed(), "키가 모두 다른데 재전송으로 판정했다");
            return result.seq();
        }

        /** 샤드가 마지막 접수까지 적용했고 그 마켓의 모든 명령이 이벤트를 가질 때까지 기다린다. */
        void awaitMaterialized(Node node, Map<Integer, Long> heads) {
            Awaitility.await().atMost(AWAIT).until(() -> node.fatal.get() != null
                    || heads.entrySet().stream().allMatch(head ->
                    node.router.engine(head.getKey()).appliedSeq(head.getKey()) == head.getValue()
                            && unmaterialized(head.getKey()) == 0));
            assertNull(node.fatal.get(), "라이터가 치명 오류로 멈췄다");
        }

        /**
         * 마켓마다 이 노드 epoch 로 적재된 스냅샷 행 수가 캡처 수와 같고 0 보다 클 때까지 기다린다. 캡처가 더 늘지 않는
         * 시점(라이터 정지나 캡처 정지 뒤)에 부른다. 드롭과 적재 실패는 없어야 한다.
         */
        void awaitSnapshotsPersisted(Node node, Collection<Integer> markets) {
            long epoch = node.epoch();
            Awaitility.await().atMost(AWAIT).until(() -> markets.stream().allMatch(marketId ->
                    node.captured(marketId) > 0 && count("SELECT count(*) FROM snapshots WHERE market_id = " + marketId
                            + " AND fencing_epoch = " + epoch) == node.captured(marketId)));
            for (int marketId : markets) {
                assertEquals(0, node.meter("exchange.snapshot.dropped", marketId), "마켓 " + marketId + " 캡처 드롭");
                assertEquals(0, node.meter("exchange.snapshot.persist_failed", marketId), "마켓 " + marketId + " 적재 실패");
            }
        }

        long unmaterialized() {
            return count("SELECT count(*) FROM command_journal c "
                    + "WHERE NOT EXISTS (SELECT 1 FROM event_journal e WHERE e.source_cmd_seq = c.seq)");
        }

        long unmaterialized(int marketId) {
            return count("SELECT count(*) FROM command_journal c WHERE c.market_id = " + marketId
                    + " AND NOT EXISTS (SELECT 1 FROM event_journal e WHERE e.source_cmd_seq = c.seq)");
        }

        /** 유실 0(이벤트 없는 명령 없음)과 이중 적용 0(같은 명령의 같은 순번 이벤트나 주문이 둘 아님), CRITICAL 판정 전부 0. */
        void assertNoLossNoDoubleApply() {
            assertEquals(0, unmaterialized(), "이벤트 없는 명령이 있다");
            assertEquals(0, count("SELECT count(*) FROM (SELECT source_cmd_seq, event_ordinal FROM event_journal "
                    + "GROUP BY source_cmd_seq, event_ordinal HAVING count(*) > 1) d"), "같은 명령의 같은 순번 이벤트가 둘이다");
            assertEquals(0, count("SELECT count(*) FROM (SELECT command_seq FROM orders "
                    + "GROUP BY command_seq HAVING count(*) > 1) d"), "한 명령이 주문 둘로 물질화됐다");
            for (InvariantCheck check : InvariantCheck.values()) {
                if (check.severity() == InvariantCheck.Severity.CRITICAL) {
                    assertEquals(0, count(check.sql()), check.name());
                }
            }
        }

        long count(String sql) {
            return jdbc.sql(sql).query(Long.class).single();
        }

        @Override
        public void close() throws Exception {
            for (AutoCloseable closeable : closeables) {
                closeable.close();
            }
            dataSource.close();
        }
    }

    /** 한 노드의 리스, 정산, 마켓별 라이터와 캡처, 영속화 워커, 복구. 운영 배선(EngineConfig)과 같은 모양이다. */
    private static final class Node implements AutoCloseable {
        final LeaderLeaseService lease;
        final SettlementService settlement;
        final SnapshotPersister persister;
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final WriterRouter router;
        final EngineRecovery recovery;
        final AtomicReference<Throwable> fatal = new AtomicReference<>();
        private final Cluster cluster;
        // 걸린 마켓의 라이터는 정산 직전에 멈춘다. 멈춘 명령은 저널에만 있고 이벤트가 없다.
        private final Map<Integer, CountDownLatch> holds = new ConcurrentHashMap<>();
        // 투입을 한 번 버릴 마켓. 저널에는 커밋되지만 라이터 큐에 들어가지 않아 다음 명령이 갭으로 본다.
        private final Set<Integer> dropNextOffer = ConcurrentHashMap.newKeySet();
        // 캡처 정지 스위치. 훅과 정지가 같은 잠금을 잡아 정지가 돌아온 뒤에는 진행 중인 캡처도 없다.
        private final Object captureLock = new Object();
        private boolean capturing = true;

        Node(Cluster cluster) {
            this.cluster = cluster;
            lease = new LeaderLeaseService(cluster.jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
            settlement = new SettlementService(cluster.jdbc, cluster.tx, lease, cluster.json, cluster.catalog,
                    cluster.mapper);
            persister = new SnapshotPersister(cluster.store);
            router = new WriterRouter(marketId -> {
                MatchingEngineImpl engine = new MatchingEngineImpl();
                SingleWriterLoop loop = new SingleWriterLoop(4096, engine, events -> {
                    CountDownLatch hold = holds.get(marketId);
                    if (hold != null) {
                        hold.await();
                    }
                    settlement.appendAndSettle(events);
                }, new JournalCatchUpSource(cluster.commands, cluster.mapper, marketId), fatal::set);
                SnapshotCapture capture = new SnapshotCapture(persister, lease, marketId, CAPTURE_INTERVAL,
                        Duration.ofHours(1), 64, meters);
                loop.setSnapshotHook(current -> {
                    synchronized (captureLock) {
                        if (capturing) {
                            capture.afterCommand(current);
                        }
                    }
                });
                return new WriterRouter.Shard(engine, loop);
            });
            recovery = new EngineRecovery(cluster.replay, settlement::appendAndSettle, lease, cluster.store);
            recovery.setRecoveryRecorder(new RecoveryRunRecorder(cluster.jdbc));
        }

        long epoch() {
            return lease.requireLeader().epoch();
        }

        void pauseCapture() {
            synchronized (captureLock) {
                capturing = false;
            }
        }

        CountDownLatch hold(int marketId) {
            CountDownLatch hold = new CountDownLatch(1);
            holds.put(marketId, hold);
            return hold;
        }

        void dropNextOffer(int marketId) {
            dropNextOffer.add(marketId);
        }

        long captured(int marketId) {
            return meter("exchange.snapshot.captured", marketId);
        }

        long meter(String name, int marketId) {
            FunctionCounter counter = meters.find(name).tag("market", String.valueOf(marketId)).functionCounter();
            return counter == null ? 0 : (long) counter.count();
        }

        EngineBridge bridge(AcceptGate gate) {
            CommandSink routed = CommandSink.of(router);
            CommandSink sink = new CommandSink() {
                @Override
                public boolean offer(OrderCommand command) {
                    return dropNextOffer.remove(command.marketId()) || routed.offer(command);
                }

                @Override
                public boolean isRunning(int marketId) {
                    return routed.isRunning(marketId);
                }
            };
            return new EngineBridge(new CommandJournalAppender(cluster.jdbc, cluster.tx, lease), sink,
                    cluster.mapper, gate, EngineBridge.NEVER_HALTED);
        }

        @Override
        public void close() {
            router.haltAll();
            persister.close();
            lease.close();
        }
    }
}

package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.core.SnapshotCodec;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 트래픽 전에 래치를 건 대역 모드의 장애 경로(ADR 0022 7절 6단계 래치 전 확인 5). 12마켓 동시 접수와 체결, 재기동 복구
 * 동치, 팔로워 따라잡기, 크래시 창 재적재, 승격이 마켓 대역 seq 위에서 유실과 이중 적용 없이 끝나는지 본다. 운영 배선과
 * 같은 마켓별 라이터와 정산을 쓰고 시험마다 같은 컨테이너의 별도 데이터베이스를 쓴다.
 */
@Testcontainers
class BandModeFailoverIntegrationTest {

    private static final int MARKETS = 12;
    private static final int KRW = 1;
    private static final long BASE_TS = 1_758_000_000_000L;
    private static final Duration AWAIT = Duration.ofSeconds(90);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final AtomicLong ORDER_IDS = new AtomicLong(1);

    @Test
    void twelveMarketsAppendAndMatchOnBandSeqsAndRestartRecoveryEqualsLive() throws Exception {
        try (Cluster c = new Cluster("band_live")) {
            Node a = c.leader();
            a.router.startAll();
            Map<Integer, List<Long>> acks = c.trade(a.bridge(AcceptGate.ALWAYS_OPEN), 20);
            c.awaitMaterialized(a, heads(acks));
            a.router.stopAll();

            for (Map.Entry<Integer, List<Long>> market : acks.entrySet()) {
                long base = market.getKey() * c.span;
                assertEquals(LongStream.rangeClosed(base + 1, base + 40).boxed().toList(), market.getValue(),
                        "마켓 " + market.getKey() + " 접수 seq 가 그 마켓 대역에서 조밀하지 않다");
                assertTrue(c.count("SELECT count(*) FROM trades WHERE market_id = " + market.getKey()) > 0,
                        "마켓 " + market.getKey() + " 에 체결이 없다");
            }
            assertEquals(0, c.count("SELECT count(*) FROM command_journal WHERE seq < " + c.span),
                    "래치 뒤에 레거시 행이 생겼다");
            assertEquals(1, c.count("SELECT next_value FROM journal_counters WHERE name = 'command_seq'"),
                    "래치 뒤에 전역 카운터가 움직였다");
            c.assertNoLossNoDoubleApply();

            MatchingEngineImpl recovered = new MatchingEngineImpl();
            ReplayResult result = a.recovery.recover(recovered);
            assertTrue(result.pendingBatches().isEmpty(), "물질화가 끝난 저널의 재기동에 재적재 대상이 남았다");
            assertTrue(result.verifiedEvents() > 0, "재기동 재생이 이벤트를 대조하지 않았다");
            assertBooksEqual(a.router, recovered, heads(acks));
        }
    }

    @Test
    void followerCatchUpConvergesOnBandJournalAndCountsLagInCommands() throws Exception {
        int held = 5;
        try (Cluster c = new Cluster("band_follower")) {
            Node a = c.leader();
            // 마켓 5 라이터를 첫 정산 전에 붙잡는다. 그 마켓 대역 행이 하나도 물질화되지 않은 동안 커서로 재면 격차가 대역 폭이 된다.
            CountDownLatch release = a.hold(held);
            a.router.startAll();
            MatchingEngineImpl follower = new MatchingEngineImpl();
            try (FollowerCatchUp catchUp = new FollowerCatchUp(c.replay, follower, c.lease(), Duration.ofSeconds(1),
                    new SimpleMeterRegistry())) {
                ExecutorService submitter = Executors.newSingleThreadExecutor();
                Map<Integer, List<Long>> acks;
                int ticksWhileWriting = 0;
                try {
                    Future<Map<Integer, List<Long>>> load =
                            submitter.submit(() -> c.trade(a.bridge(AcceptGate.ALWAYS_OPEN), 20));
                    while (!load.isDone()) {
                        catchUp.tick();
                        ticksWhileWriting++;
                    }
                    acks = load.get();
                } finally {
                    submitter.shutdownNow();
                }
                Map<Integer, Long> heads = heads(acks);
                c.awaitMaterialized(a, without(heads, Set.of(held)));
                catchUp.tick();
                assertEquals(1, catchUp.state(), "따라잡는 중 상태가 아니다. -1 이면 대조가 갈렸다");
                assertEquals(0, follower.appliedSeq(held), "이벤트 없는 명령을 따라잡기가 적용했다");
                assertEquals(heads.get(held) - held * c.span, catchUp.lagCommands(),
                        "붙잡힌 마켓의 격차가 명령 수가 아니다");
                assertEquals(catchUp.lagCommands(), catchUp.lagCommandsSum(), "붙잡지 않은 마켓에 격차가 남았다");

                release.countDown();
                c.awaitMaterialized(a, heads);
                a.router.stopAll();
                long deadline = System.nanoTime() + AWAIT.toNanos();
                while (!follower.appliedSeqs().equals(heads) || catchUp.lagCommands() != 0) {
                    assertTrue(System.nanoTime() < deadline, "팔로워가 리더에 수렴하지 않았다: follower="
                            + follower.appliedSeqs() + ", leader=" + heads + ", state=" + catchUp.state());
                    catchUp.tick();
                }
                assertTrue(ticksWhileWriting > 0, "쓰기 도중 돈 따라잡기 틱이 없다");
                assertEquals(1, catchUp.state());
                assertTrue(catchUp.verifiedEvents() > 0, "따라잡기가 이벤트를 한 건도 대조하지 않았다");
                assertBooksEqual(a.router, follower, heads);
                c.assertNoLossNoDoubleApply();
            }
        }
    }

    @Test
    void crashWindowOnBandJournalIsReappendedExactlyOnceByNewLeader() throws Exception {
        Set<Integer> crashed = Set.of(3, 7, 11);
        try (Cluster c = new Cluster("band_crash")) {
            Node a = c.leader();
            a.router.startAll();
            EngineBridge bridge = a.bridge(AcceptGate.ALWAYS_OPEN);
            c.awaitMaterialized(a, heads(c.trade(bridge, 10)));
            crashed.forEach(a::hold);
            Map<Integer, Long> heads = heads(c.trade(bridge, 5));
            c.awaitMaterialized(a, without(heads, crashed));
            // 크래시. 붙잡힌 라이터는 인터럽트로 정산 없이 선다.
            a.router.haltAll();

            List<Long> window = c.jdbc.sql("""
                            SELECT c.seq FROM command_journal c
                            WHERE NOT EXISTS (SELECT 1 FROM event_journal e WHERE e.source_cmd_seq = c.seq)
                            ORDER BY c.seq
                            """)
                    .query(Long.class)
                    .list();
            assertEquals(crashed.size() * 10, window.size(), "크래시 창 크기가 붙잡힌 마켓의 접수 수와 다르다");
            assertTrue(window.stream().allMatch(seq -> crashed.contains((int) (seq / c.span))),
                    "붙잡지 않은 마켓에 크래시 창이 생겼다: " + window);

            Node b = c.leader();
            List<Long> reappended = Collections.synchronizedList(new ArrayList<>());
            MatchingEngineImpl recovered = new MatchingEngineImpl();
            ReplayResult result = new EngineRecovery(c.replay, events -> {
                reappended.add(events.get(0).sourceCmdSeq());
                b.settlement.appendAndSettle(events);
            }, b.lease).recover(recovered);

            assertEquals(window.size(), result.pendingBatches().size());
            assertEquals(window, reappended.stream().sorted().toList(), "재적재가 크래시 창과 다르다");
            for (int marketId : crashed) {
                List<Long> inMarket = reappended.stream().filter(seq -> seq / c.span == marketId).toList();
                assertEquals(inMarket.stream().sorted().toList(), inMarket,
                        "마켓 " + marketId + " 안 재적재가 seq 순서가 아니다");
            }
            c.assertNoLossNoDoubleApply();

            MatchingEngineImpl again = new MatchingEngineImpl();
            ReplayResult clean = new EngineRecovery(c.replay, events -> fail("재적재 뒤 재기동이 다시 재적재했다"),
                    b.lease).recover(again);
            assertTrue(clean.pendingBatches().isEmpty());
            for (int marketId = 1; marketId <= MARKETS; marketId++) {
                assertEquals(heads.get(marketId), recovered.appliedSeq(marketId), "마켓 " + marketId + " 복구 커서");
                assertEquals(heads.get(marketId), again.appliedSeq(marketId), "마켓 " + marketId + " 재기동 커서");
                assertArrayEquals(SnapshotCodec.encode(recovered.orderBook(marketId)),
                        SnapshotCodec.encode(again.orderBook(marketId)), "마켓 " + marketId + " 북");
            }
        }
    }

    @Test
    void promotionOnBandJournalReappendsCrashWindowAndContinuesEachMarketBand() throws Exception {
        int crashed = 6;
        try (Cluster c = new Cluster("band_promote")) {
            Node a = c.leader();
            a.router.startAll();
            EngineBridge bridge = a.bridge(AcceptGate.ALWAYS_OPEN);
            Map<Integer, List<Long>> before = c.trade(bridge, 10);
            c.awaitMaterialized(a, heads(before));
            a.hold(crashed);
            Map<Integer, List<Long>> window = c.trade(bridge, 3);
            c.awaitMaterialized(a, without(heads(window), Set.of(crashed)));
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

                Map<Integer, List<Long>> after = c.trade(b.bridge(coordinator.gate()), 10);
                c.awaitMaterialized(b, heads(after));
                b.router.stopAll();

                for (int marketId = 1; marketId <= MARKETS; marketId++) {
                    List<Long> all = new ArrayList<>(before.get(marketId));
                    all.addAll(window.get(marketId));
                    all.addAll(after.get(marketId));
                    long base = marketId * c.span;
                    assertEquals(LongStream.rangeClosed(base + 1, base + all.size()).boxed().toList(), all,
                            "마켓 " + marketId + " 대역이 승격 뒤 이어지지 않았다");
                }
                assertEquals(2, c.count("SELECT count(DISTINCT fencing_token) FROM command_journal"),
                        "승격 전후 epoch 가 둘이 아니다");
                c.assertNoLossNoDoubleApply();
                MatchingEngineImpl recovered = new MatchingEngineImpl();
                assertTrue(new EngineRecovery(c.replay, events -> fail("승격 뒤 재기동이 다시 재적재했다"), b.lease)
                        .recover(recovered).pendingBatches().isEmpty());
                assertBooksEqual(b.router, recovered, heads(after));
            } finally {
                coordinator.close();
            }
        }
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

    /** 래치를 건 데이터베이스 하나. 마켓 m 은 기준 자산 m + 1 을 원화로 거래하고 마켓마다 매도자와 매수자가 있다. */
    private static final class Cluster implements AutoCloseable {
        final HikariDataSource dataSource;
        final JdbcClient jdbc;
        final TransactionTemplate tx;
        final ObjectMapper json = new ObjectMapper();
        final CommandPayloadMapper mapper = new CommandPayloadMapper(json);
        final MarketCatalog catalog;
        final CommandJournalReader commands;
        final ReplayService replay;
        final long span;
        private final long[] sellers = new long[MARKETS + 1];
        private final long[] buyers = new long[MARKETS + 1];
        private final List<AutoCloseable> closeables = new ArrayList<>();

        Cluster(String name) throws SQLException {
            try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()); Statement st = conn.createStatement()) {
                st.execute("CREATE DATABASE " + name);
            }
            String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
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

            AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
            bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
            for (int marketId = 1; marketId <= MARKETS; marketId++) {
                int coin = marketId + 1;
                bootstrap.asset(coin, "BAND" + marketId, "Band Coin " + marketId, "CRYPTO", 8);
                bootstrap.market(marketId, "KRW-BAND" + marketId, coin, KRW, BigDecimal.ZERO, BigDecimal.ZERO);
                sellers[marketId] = bootstrap.user("seller" + marketId + "@band.test");
                buyers[marketId] = bootstrap.user("buyer" + marketId + "@band.test");
                for (long user : new long[] {sellers[marketId], buyers[marketId]}) {
                    bootstrap.userAccounts(user, KRW);
                    bootstrap.userAccounts(user, coin);
                }
                bootstrap.deposit(sellers[marketId], coin, new BigDecimal("1000000"));
                bootstrap.deposit(buyers[marketId], KRW, new BigDecimal("1000000000"));
            }
            assertTrue(jdbc.sql("SELECT latch_command_band_cutover() IS NOT NULL").query(Boolean.class).single(),
                    "트래픽 전 래치 실패");
            span = jdbc.sql("SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'")
                    .query(Long.class)
                    .single();
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

        LeaderLeaseService lease() {
            LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
            closeables.add(lease);
            return lease;
        }

        /** 마켓마다 매도자와 매수자가 동시에 접수한다. 가격대가 겹쳐 체결과 잔여 주문이 함께 남는다. 마켓별 접수 seq 오름차순이다. */
        Map<Integer, List<Long>> trade(EngineBridge bridge, int ordersPerSide) throws Exception {
            Map<Integer, List<Long>> acks = new ConcurrentHashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(MARKETS * 2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> writers = new ArrayList<>();
            try {
                for (int market = 1; market <= MARKETS; market++) {
                    int marketId = market;
                    List<Long> seqs = Collections.synchronizedList(new ArrayList<>());
                    acks.put(marketId, seqs);
                    for (OrderCommand.Side side : List.of(OrderCommand.Side.SELL, OrderCommand.Side.BUY)) {
                        writers.add(pool.submit(() -> {
                            go.await();
                            for (int i = 0; i < ordersPerSide; i++) {
                                SubmitResult result = bridge.submit(limit(marketId, side, i));
                                assertFalse(result.replayed(), "키가 모두 다른데 재전송으로 판정했다");
                                seqs.add(result.seq());
                            }
                            return null;
                        }));
                    }
                }
                go.countDown();
                for (Future<?> writer : writers) {
                    writer.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            Map<Integer, List<Long>> sorted = new TreeMap<>();
            acks.forEach((marketId, seqs) -> sorted.put(marketId, seqs.stream().sorted().toList()));
            return sorted;
        }

        private OrderIntake limit(int marketId, OrderCommand.Side side, int i) {
            long orderId = ORDER_IDS.getAndIncrement();
            String key = "band-" + orderId;
            // 매도 100~102 와 매수 99~101 이 겹친다.
            String price = String.valueOf(side == OrderCommand.Side.SELL ? 100 + i % 3 : 101 - i % 3);
            long user = side == OrderCommand.Side.SELL ? sellers[marketId] : buyers[marketId];
            return new OrderIntake(marketId, CommandDraft.CommandType.NEW, user, key, orderId, BASE_TS + orderId,
                    orderId, side, OrderCommand.OrdType.LIMIT, OrderCommand.TimeInForce.GTC, new BigDecimal(price),
                    BigDecimal.ONE, null, UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /** 샤드가 마지막 접수까지 적용했고 그 마켓의 모든 명령이 이벤트를 가질 때까지 기다린다. */
        void awaitMaterialized(Node node, Map<Integer, Long> heads) {
            Awaitility.await().atMost(AWAIT).until(() -> node.fatal.get() != null
                    || heads.entrySet().stream().allMatch(head ->
                    node.router.engine(head.getKey()).appliedSeq(head.getKey()) == head.getValue()
                            && unmaterialized(head.getKey()) == 0));
            assertNull(node.fatal.get(), "라이터가 치명 오류로 멈췄다");
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

    /** 한 노드의 리스, 정산, 마켓별 라이터, 복구. 운영 배선(EngineConfig)과 같은 모양이다. */
    private static final class Node implements AutoCloseable {
        final LeaderLeaseService lease;
        final SettlementService settlement;
        final WriterRouter router;
        final EngineRecovery recovery;
        final AtomicReference<Throwable> fatal = new AtomicReference<>();
        private final Cluster cluster;
        // 걸린 마켓의 라이터는 정산 직전에 멈춘다. 멈춘 명령은 저널에만 있고 이벤트가 없다.
        private final Map<Integer, CountDownLatch> holds = new ConcurrentHashMap<>();

        Node(Cluster cluster) {
            this.cluster = cluster;
            lease = new LeaderLeaseService(cluster.jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
            settlement = new SettlementService(cluster.jdbc, cluster.tx, lease, cluster.json, cluster.catalog,
                    cluster.mapper);
            router = new WriterRouter(marketId -> {
                MatchingEngineImpl engine = new MatchingEngineImpl();
                return new WriterRouter.Shard(engine, new SingleWriterLoop(4096, engine, events -> {
                    CountDownLatch hold = holds.get(marketId);
                    if (hold != null) {
                        hold.await();
                    }
                    settlement.appendAndSettle(events);
                }, new JournalCatchUpSource(cluster.commands, cluster.mapper, marketId), fatal::set));
            });
            recovery = new EngineRecovery(cluster.replay, settlement::appendAndSettle, lease);
        }

        CountDownLatch hold(int marketId) {
            CountDownLatch hold = new CountDownLatch(1);
            holds.put(marketId, hold);
            return hold;
        }

        EngineBridge bridge(AcceptGate gate) {
            return new EngineBridge(new CommandJournalAppender(cluster.jdbc, cluster.tx, lease), router,
                    cluster.mapper, gate, EngineBridge.NEVER_HALTED);
        }

        @Override
        public void close() {
            router.haltAll();
            lease.close();
        }
    }
}

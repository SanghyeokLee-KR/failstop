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
import org.flywaydb.core.api.configuration.FluentConfiguration;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 10^7 대역 이벤트 행이 있는 DB 에 V46 을 적용하는 승격 경로(ADR 0022 9단계). V45 에서 V46 이전 앱과 같은 파생으로 거래하고
 * 스냅샷을 찍은 뒤 V46 을 적용해, 승격 재적재와 스냅샷 꼬리 재생과 전량 재생이 경계를 넘어 결정론을 지키는지 래치 전 전역
 * 채번과 래치 뒤 대역 채번에서 본다. 운영 배선처럼 샤드마다 캡처를 걸고 시험마다 같은 컨테이너의 별도 데이터베이스를 쓴다.
 */
@Testcontainers
class EventBandWidenBoundaryIntegrationTest {

    enum Numbering { GLOBAL, BAND }

    private static final List<Integer> MARKETS = List.of(1, 3, 12);
    private static final int KRW = 1;
    private static final long BASE_TS = 1_758_200_000_000L;
    private static final long CAPTURE_INTERVAL = 4;
    private static final long LEGACY_SPAN = 10_000_000L;
    private static final long SPAN = 1_000_000_000_000L;
    private static final Duration AWAIT = Duration.ofSeconds(90);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final AtomicLong ORDER_IDS = new AtomicLong(1);

    /** V45 위에서 V48 리스 함수 자리를 채우는 판. 본문은 V48 전 앱이 보내던 SQL 과 같은 행을 같은 잠금으로 잡는다. */
    private static final List<String> LEASE_SHIMS = List.of("""
            CREATE FUNCTION try_acquire_lease(p_resource TEXT, p_holder TEXT, p_ttl INTERVAL) RETURNS BIGINT
            LANGUAGE sql AS $$ SELECT try_acquire_leader(p_holder, p_ttl) $$
            """, """
            CREATE FUNCTION renew_lease(p_resource TEXT, p_holder TEXT, p_epoch BIGINT, p_ttl INTERVAL) RETURNS BOOLEAN
            LANGUAGE sql AS $$ SELECT renew_leader_lease(p_holder, p_epoch, p_ttl) $$
            """, """
            CREATE FUNCTION assert_and_lock_market_leader(p_market_id SMALLINT, p_holder TEXT, p_epoch BIGINT)
            RETURNS BIGINT LANGUAGE sql AS $$ SELECT assert_and_lock_leader(p_holder, p_epoch) $$
            """, """
            CREATE FUNCTION append_command_journal_row(
                p_seq BIGINT, p_market_id SMALLINT, p_command_type VARCHAR, p_user_id BIGINT,
                p_client_order_id VARCHAR, p_assigned_order_id BIGINT, p_engine_ts BIGINT, p_tie_break BIGINT,
                p_payload JSONB, p_checksum BIGINT, p_holder TEXT, p_epoch BIGINT
            ) RETURNS INTEGER LANGUAGE plpgsql AS $$
            DECLARE v_rows INTEGER;
            BEGIN
                INSERT INTO command_journal
                  (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                   engine_ts, tie_break, payload, fencing_token, checksum)
                SELECT p_seq, p_market_id, p_command_type, p_user_id, p_client_order_id, p_assigned_order_id,
                       p_engine_ts, p_tie_break, p_payload, l.epoch, p_checksum
                FROM leader_lease l
                WHERE l.resource = 'matching-engine'
                  AND l.holder = p_holder AND l.epoch = p_epoch AND l.lease_until > clock_timestamp()
                FOR KEY SHARE OF l;
                GET DIAGNOSTICS v_rows = ROW_COUNT;
                RETURN v_rows;
            END $$
            """);

    /** V46 전 크래시 창을 새 리더가 확대 대역으로 재적재하고, 마켓마다 번호를 이어 거래한다. */
    @ParameterizedTest
    @EnumSource(Numbering.class)
    void promotionAfterWidenReappendsLegacyCrashWindowOnWidenedBand(Numbering numbering) throws Exception {
        int crashed = 3;
        try (Cluster c = new Cluster("widen_promote", numbering)) {
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
            a.router.haltAll();
            assertTrue(c.unmaterialized(crashed) > 0, "V46 전에 크래시 창이 없어 재적재 판정이 공허하다");
            assertEquals(0, c.count("SELECT count(*) FROM event_journal WHERE event_seq >= " + SPAN),
                    "V46 전에 확대 대역 행이 있다");

            c.widen();

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
                assertEquals(0, b.recovery.snapshotFallbacks(), "승격 재생이 V46 전 스냅샷을 버렸다");
                assertEquals(0, c.count(InvariantCheck.C17_REPLAY_DETERMINISM.sql()), "C17");
                assertTrue(c.count("SELECT count(*) FROM event_journal WHERE market_id = " + crashed
                        + " AND event_seq >= " + SPAN) > 0, "크래시 창 재적재가 확대 대역 값을 쓰지 않았다");

                Map<Integer, List<Long>> after = c.tradeAll(b.bridge(coordinator.gate()), 3);
                c.awaitMaterialized(b, heads(after));
                b.router.stopAll();

                c.assertCrossedEventBand();
                assertTrue(c.count("SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'TRADE'"
                        + " AND business_event_id >= " + SPAN) > 0, "확대 뒤 체결의 원장 멱등 키가 대역 값이 아니다");
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

    /** V46 전 스냅샷에서 시작한 꼬리 재생이 10^7 대역 이벤트와 확대 대역 이벤트를 함께 대조하고 전량 재생과 같은 북에 닿는다. */
    @ParameterizedTest
    @EnumSource(Numbering.class)
    void snapshotTailReplayVerifiesLegacyAndWidenedEventsAcrossBoundary(Numbering numbering) throws Exception {
        try (Cluster c = new Cluster("widen_tail", numbering)) {
            Node a = c.leader();
            a.router.startAll();
            EngineBridge bridgeA = a.bridge(AcceptGate.ALWAYS_OPEN);
            Map<Integer, List<Long>> covered = c.tradeAll(bridgeA, 4);
            c.awaitMaterialized(a, heads(covered));
            a.pauseCapture();
            c.awaitSnapshotsPersisted(a, covered.keySet());
            c.awaitMaterialized(a, heads(c.tradeAll(bridgeA, 2)));
            a.router.stopAll();

            c.widen();

            MatchingEngineImpl installed = new MatchingEngineImpl();
            a.recovery.recover(installed);
            Node b = c.leader();
            b.pauseCapture();
            b.router.installFrom(installed);
            b.router.startAll();
            Map<Integer, Long> heads = heads(c.tradeAll(b.bridge(AcceptGate.ALWAYS_OPEN), 3));
            c.awaitMaterialized(b, heads);
            b.router.stopAll();
            c.assertCrossedEventBand();

            Map<Integer, SnapshotSource.MarketLoaded> adopted = c.store.loadLatestAdoptablePerMarket();
            assertEquals(heads.keySet(), adopted.keySet(), "스냅샷이 없는 마켓이 있어 경계 꼬리를 판정하지 못한다");
            long tailCommands = 0;
            long legacyTailEvents = 0;
            long widenedTailEvents = 0;
            for (int marketId : heads.keySet()) {
                long cursor = adopted.get(marketId).seq();
                tailCommands += c.count("SELECT count(*) FROM command_journal WHERE market_id = " + marketId
                        + " AND seq > " + cursor);
                String tailEvents = "SELECT count(*) FROM event_journal WHERE market_id = " + marketId
                        + " AND source_cmd_seq > " + cursor + " AND event_seq ";
                legacyTailEvents += c.count(tailEvents + "< " + SPAN);
                widenedTailEvents += c.count(tailEvents + ">= " + SPAN);
            }
            assertTrue(legacyTailEvents > 0 && widenedTailEvents > 0,
                    "꼬리가 경계를 넘지 않아 대조가 공허하다: legacy=" + legacyTailEvents + ", widened=" + widenedTailEvents);

            MatchingEngineImpl recovered = new MatchingEngineImpl();
            ReplayResult result = b.recovery.recover(recovered);
            assertEquals(tailCommands, result.replayedCommands(), "스냅샷 커서 뒤 꼬리만 재생하지 않았다");
            assertEquals(legacyTailEvents + widenedTailEvents, result.verifiedEvents(), "꼬리 이벤트를 전부 대조하지 않았다");
            assertTrue(result.pendingBatches().isEmpty());
            assertEquals(0, b.recovery.snapshotFallbacks(), "스냅샷 시작 복구가 폴백했다");
            assertEquals(0, c.count(InvariantCheck.C17_REPLAY_DETERMINISM.sql()), "C17");
            assertBooksEqual(b.router, recovered, heads);

            MatchingEngineImpl full = new MatchingEngineImpl();
            new EngineRecovery(c.replay, events -> fail("정산이 끝난 저널에 재적재 대상이 있다"), b.lease).recover(full);
            assertBooksEqual(b.router, full, heads);
            c.assertNoLossNoDoubleApply();
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

    /** V45 데이터베이스 하나. 대역 모드면 트래픽 전에 래치를 건다. 마켓 m 은 기준 자산 m + 1 을 원화로 거래한다. */
    private static final class Cluster implements AutoCloseable {
        final String url;
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
            url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + database);
            assertTrue(flyway().target("45").load().migrate().success, "V45 까지의 Flyway migration failed");
            // V46 전 앱의 이벤트 채번과 같은 값을 낸다. 그 앱은 next_market_journal_value 로 지역 번호를 받아 10^7 을 곱했다.
            try (Connection conn = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE FUNCTION next_market_event_seq(p_market_id SMALLINT, p_count BIGINT)
                        RETURNS TABLE (out_market_seq BIGINT, out_event_seq BIGINT)
                        LANGUAGE plpgsql AS $$
                        DECLARE
                            v_local BIGINT;
                        BEGIN
                            v_local := next_market_journal_value(p_market_id, 'event_seq', p_count);
                            out_market_seq := v_local;
                            out_event_seq := p_market_id * %d + v_local;
                            RETURN NEXT;
                        END $$
                        """.formatted(LEGACY_SPAN));
                // 이 앱은 V48 리스 함수를 부른다. V45 에서는 V48 전 앱과 같은 행·잠금·판정을 내는 시험용 판을 두고 widen() 에서 지운다.
                for (String shim : LEASE_SHIMS) {
                    st.execute(shim);
                }
            }
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
                bootstrap.asset(coin, "WIDE" + marketId, "Widen Coin " + marketId, "CRYPTO", 8);
                bootstrap.market(marketId, "KRW-WIDE" + marketId, coin, KRW, BigDecimal.ZERO, BigDecimal.ZERO);
                long seller = bootstrap.user("seller" + marketId + "@widen.test");
                long buyer = bootstrap.user("buyer" + marketId + "@widen.test");
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

        private FluentConfiguration flyway() {
            return Flyway.configure()
                    .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration");
        }

        /** V46 전 파생 함수를 지우고 V46 을 적용한다. 라이터가 멈춘 배포 창에서 부른다(ADR 0022 7절 9단계 적용 시점 조건 2). */
        void widen() {
            jdbc.sql("DROP FUNCTION next_market_event_seq(SMALLINT, BIGINT)").update();
            jdbc.sql("""
                    DROP FUNCTION try_acquire_lease(TEXT, TEXT, INTERVAL), renew_lease(TEXT, TEXT, BIGINT, INTERVAL),
                        assert_and_lock_market_leader(SMALLINT, TEXT, BIGINT),
                        append_command_journal_row(BIGINT, SMALLINT, VARCHAR, BIGINT, VARCHAR, BIGINT, BIGINT, BIGINT,
                                                   JSONB, BIGINT, TEXT, BIGINT)
                    """).update();
            assertTrue(flyway().load().migrate().success, "V46 적용 실패");
            assertEquals(SPAN, count("SELECT band_span FROM journal_band_spec WHERE name = 'event_seq'"));
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

        /** 마켓마다 매도와 매수를 번갈아 넣는다. 매도 100~102 와 매수 99~101 이 겹쳐 체결과 잔여 주문이 함께 남는다. */
        Map<Integer, List<Long>> tradeAll(EngineBridge bridge, int pairs) {
            Map<Integer, List<Long>> acks = new TreeMap<>();
            for (int marketId : MARKETS) {
                List<Long> seqs = new ArrayList<>();
                for (int i = 0; i < pairs; i++) {
                    seqs.add(submit(bridge, marketId, OrderCommand.Side.SELL, String.valueOf(100 + i % 3)));
                    seqs.add(submit(bridge, marketId, OrderCommand.Side.BUY, String.valueOf(101 - i % 3)));
                }
                acks.put(marketId, seqs);
            }
            return acks;
        }

        long submit(EngineBridge bridge, int marketId, OrderCommand.Side side, String price) {
            long orderId = ORDER_IDS.getAndIncrement();
            String key = "widen-" + orderId;
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

        /** 마켓마다 이 노드 epoch 로 적재된 스냅샷 행 수가 캡처 수와 같고 0 보다 클 때까지 기다린다. 캡처를 멈춘 뒤에 부른다. */
        void awaitSnapshotsPersisted(Node node, Collection<Integer> markets) {
            long epoch = node.epoch();
            Awaitility.await().atMost(AWAIT).until(() -> markets.stream().allMatch(marketId ->
                    node.captured(marketId) > 0 && count("SELECT count(*) FROM snapshots WHERE market_id = " + marketId
                            + " AND fencing_epoch = " + epoch) == node.captured(marketId)));
        }

        /** 마켓마다 10^7 대역 행과 확대 대역 행이 둘 다 있고, 마켓 안 event_seq 가 번호 순으로 커지며 두 파생이 각자 맞다. */
        void assertCrossedEventBand() {
            for (int marketId : MARKETS) {
                String rows = "SELECT count(*) FROM event_journal WHERE market_id = " + marketId + " AND event_seq ";
                assertTrue(count(rows + "< " + SPAN) > 0, "마켓 " + marketId + " 에 10^7 대역 행이 없어 경계 판정이 공허하다");
                assertTrue(count(rows + ">= " + SPAN) > 0, "마켓 " + marketId + " 에 확대 대역 행이 없다");
            }
            assertEquals(0, count("""
                    SELECT count(*) FROM (
                        SELECT event_seq, lag(event_seq) OVER (PARTITION BY market_id ORDER BY market_seq) AS prev
                        FROM event_journal
                    ) t
                    WHERE t.prev >= t.event_seq
                    """), "마켓 안 event_seq 가 번호 순서로 커지지 않는다");
            assertEquals(0, count("SELECT count(*) FROM event_journal WHERE event_seq < " + SPAN
                    + " AND event_seq <> market_id * " + LEGACY_SPAN + " + market_seq"), "V46 전 행 파생이 10^7 이 아니다");
            assertEquals(0, count("SELECT count(*) FROM event_journal WHERE event_seq >= " + SPAN
                    + " AND event_seq <> market_id * " + SPAN + " + market_seq"), "V46 뒤 행 파생이 10^12 가 아니다");
            assertEquals(0, count("SELECT count(*) FROM event_journal_default"), "이벤트 행이 DEFAULT 로 갔다");
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

        long captured(int marketId) {
            FunctionCounter counter = meters.find("exchange.snapshot.captured")
                    .tag("market", String.valueOf(marketId)).functionCounter();
            return counter == null ? 0 : (long) counter.count();
        }

        EngineBridge bridge(AcceptGate gate) {
            return new EngineBridge(new CommandJournalAppender(cluster.jdbc, cluster.tx, lease), router,
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

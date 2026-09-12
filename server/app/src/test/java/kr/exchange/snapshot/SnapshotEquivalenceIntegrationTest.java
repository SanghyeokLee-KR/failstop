package kr.exchange.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.EngineRecovery;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.core.SnapshotCodec;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalAppender;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스냅샷 배선 결정론 등가성(복구 RTO 설계 §3-b, 하네스 T3-A). 저널 전량 재생과 [스냅샷 K 로드 +
 * K+1부터 tail 재생]이 바이트 동일해야 한다. 여러 K에서 반복한다.
 *
 * 바이트 판정은 SnapshotCodec.encode의 정준 직렬화를 마켓별로 비교하고 stateChecksum·markets·
 * lastAppliedSeq를 함께 본다. 스냅샷이 재생의 압축 프리픽스이므로 tail 명령 수는 head - K로
 * 상한된다.
 *
 * 범위 한정: 계정 잔고는 엔진이 아니라 정산 계층의 durable DB 상태라 두 경로가 같은 DB를
 * 공유한다. 여기서 버그로 갈릴 수 있는 상태는 엔진 오더북이고 그것만 바이트로 고정한다.
 */
@Testcontainers
class SnapshotEquivalenceIntegrationTest {

    private static final long BASE_TS = 1_753_200_000_000L;
    private static final String ENGINE_VERSION = "engine-m1";
    private static final String CONFIG_HASH = "default";
    // 초반·중반·경계·head를 덮는 캡처 지점. head=18(아래 스크립트 길이)과 정합해야 한다.
    private static final List<Long> TARGET_K = List.of(1L, 2L, 4L, 6L, 9L, 11L, 13L, 15L, 16L, 17L, 18L);
    private static final int JOURNAL_SIZE = 18;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static ObjectMapper objectMapper;
    static CommandJournalReader commandReader;
    static EventJournalReader eventReader;
    static CommandPayloadMapper mapper;
    static ReplayService replayService;

    static long leaderHead;
    static long leaderEpoch;
    // K -> 그 경계에서 동결한 마켓별 스냅샷(인코딩 바이트 + checksum).
    static final Map<Long, List<SnapshotStore.MarketState>> capturedByK = new LinkedHashMap<>();

    @BeforeAll
    static void buildMixedJournal() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        objectMapper = new ObjectMapper();
        commandReader = new CommandJournalReader(jdbc);
        eventReader = new EventJournalReader(jdbc);
        mapper = new CommandPayloadMapper(objectMapper);
        replayService = new ReplayService(commandReader, eventReader, mapper, objectMapper,
                new MarketCatalog(jdbc));

        LeaderLeaseService leader = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        takeOver(leader);
        leaderEpoch = leader.requireLeader().epoch();
        EventJournalAppender eventAppender = new EventJournalAppender(jdbc, tx, leader, objectMapper);
        MatchingEngineImpl live = new MatchingEngineImpl();
        SingleWriterLoop loop = new SingleWriterLoop(4096, live, eventAppender::append,
                new JournalCatchUpSource(commandReader, mapper), new AtomicReference<Throwable>()::set);
        EngineBridge bridge = new EngineBridge(new CommandJournalAppender(jdbc, tx, leader), loop, mapper);
        loop.start();
        try {
            for (OrderIntake intake : script()) {
                long seq = bridge.submit(intake).seq();
                Awaitility.await().atMost(Duration.ofSeconds(60))
                        .until(() -> live.lastAppliedSeq() == seq);
                if (TARGET_K.contains(seq)) {
                    // 라이터가 유휴인 경계에서 마켓 상태를 동결한다(적용 완료, 큐 비어 있음).
                    capturedByK.put(seq, freeze(live));
                }
            }
        } finally {
            try {
                loop.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        leaderHead = live.lastAppliedSeq();
        assertEquals(JOURNAL_SIZE, leaderHead, "스크립트 길이와 head 불일치");
        assertEquals(leaderHead, commandReader.maxSeq());
        // 스크립트가 실제로 다시장·부분체결·잔여 주문을 남겼는지 확인(빈약한 저널로 등가성이
        // 공허하게 통과하는 것을 막는다).
        assertEquals(List.of(1, 2, 3), live.markets(), "3개 마켓이 모두 활성이어야 한다");
        assertTrue(live.orderBook(1).openOrderCount() >= 3, "m1 잔여 주문 부족");
        assertTrue(live.orderBook(2).openOrderCount() >= 2, "m2 잔여 주문 부족");
        assertTrue(live.orderBook(3).openOrderCount() >= 2, "m3 잔여 주문 부족");
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void fullReplayReconstructsLiveHead() {
        MatchingEngineImpl a = fullReplay();
        assertEquals(leaderHead, a.lastAppliedSeq());
        assertEquals(List.of(1, 2, 3), a.markets());
    }

    @Test
    void snapshotTailEqualsFullReplayAtEveryK() {
        MatchingEngineImpl reference = fullReplay();
        SnapshotStore watermarkSource = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        for (long k : TARGET_K) {
            List<OrderBookImpl> books = new ArrayList<>();
            for (SnapshotStore.MarketState ms : capturedByK.get(k)) {
                OrderBookImpl book = SnapshotCodec.decode(ms.state());
                // codec 왕복 결정론: decode(encode(book)).checksum == 원본 checksum.
                assertEquals(ms.checksum(), book.stateChecksum(),
                        "codec 왕복 checksum 불일치: K=" + k + ", market=" + ms.marketId());
                books.add(book);
            }
            MatchingEngineImpl b = new MatchingEngineImpl();
            b.loadSnapshot(k, books);
            // 커서 시작점을 넘기지 않는다. loadSnapshot 이 lastAppliedSeq 를 K 로 세우고
            // 재생이 그 값에서 커서를 연다.
            ReplayResult tail = replayService.rebuild(b);

            assertEngineBytesEqual(reference, b, "K=" + k);
            assertEquals(leaderHead - k, tail.replayedCommands(),
                    "tail 재생이 스냅샷 경계 K=" + k + "로 상한되지 않았다");
            assertTrue(tail.pendingEvents().isEmpty(), "클린 저널에 pending: K=" + k);
        }
    }

    @Test
    void storeRecoveryAdoptsLatestThenFallsBackOnCorruption() {
        // 공유 append-only snapshots 테이블이라 형제 테스트가 심은 유효 세트도 후보에 든다.
        // 채택 seq를 하드코딩하지 않고 게이트가 고른 값으로 tail 상한을 검증한다(순서 무관).
        SnapshotStore store = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        store.persist(9, leaderEpoch, capturedByK.get(9L));

        long adopted = store.loadLatestAdoptable().orElseThrow().seq();
        assertTrue(adopted >= 9 && adopted < leaderHead, "유효 스냅샷 채택 실패: seq=" + adopted);
        ReplayResult adoptResult = recoverWith(store);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "store-adopt");
        assertEquals(leaderHead - adopted, adoptResult.replayedCommands(), "채택 tail 상한 위반");
        assertTrue(adoptResult.pendingEvents().isEmpty());

        // state_checksum 손상 세트를 최상위 seq에 추가한다. 채택 게이트가 폐기하고 직전 유효
        // 프리픽스로 폴백해야 한다(스냅샷 = 저널 파생 최적화, 단일 정본 아님).
        long kCorrupt = 17;
        List<SnapshotStore.MarketState> corrupt = new ArrayList<>();
        for (SnapshotStore.MarketState ms : capturedByK.get(kCorrupt)) {
            corrupt.add(new SnapshotStore.MarketState(ms.marketId(), ms.state(), ms.checksum() ^ 0x5A5AL));
        }
        store.persist(kCorrupt, leaderEpoch, corrupt);
        assertEquals(adopted, store.loadLatestAdoptable().orElseThrow().seq(),
                "손상 세트를 폐기하고 직전 유효 프리픽스로 폴백해야 한다");
        ReplayResult afterCorrupt = recoverWith(store);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "store-fallback-checksum");
        assertEquals(leaderHead - adopted, afterCorrupt.replayedCommands(),
                "손상 폴백 tail 상한 위반");
    }

    @Test
    void versionAndConfigMismatchDiscardAllAndFullReplay() {
        SnapshotStore base = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        base.persist(11, leaderEpoch, capturedByK.get(11L));
        assertFalse(base.loadLatestAdoptable().isEmpty(), "동일 버전·설정은 채택되어야 한다");

        // engine_version 불일치: 전 세트 폐기 → 전량 재생.
        SnapshotStore upgraded = new SnapshotStore(jdbc, tx, "engine-m2", CONFIG_HASH);
        assertTrue(upgraded.loadLatestAdoptable().isEmpty(), "engine_version 불일치 채택");
        ReplayResult byVersion = recoverWith(upgraded);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "version-fallback");
        assertEquals(leaderHead, byVersion.replayedCommands(), "폴백인데 전량 재생이 아니다");

        // config_hash 불일치: 전 세트 폐기 → 전량 재생.
        SnapshotStore reconfigured = new SnapshotStore(jdbc, tx, ENGINE_VERSION, "config-changed");
        assertTrue(reconfigured.loadLatestAdoptable().isEmpty(), "config_hash 불일치 채택");
        ReplayResult byConfig = recoverWith(reconfigured);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "config-fallback");
        assertEquals(leaderHead, byConfig.replayedCommands());
    }

    private MatchingEngineImpl fullReplay() {
        MatchingEngineImpl engine = new MatchingEngineImpl();
        replayService.rebuild(engine);
        return engine;
    }

    // recoverWith가 만든 엔진 참조. recover 결과(ReplayResult)와 엔진을 함께 반환할 수 없어
    // 단일 스레드 테스트에서 마지막 복구 엔진을 여기에 실어 검증한다.
    private MatchingEngineImpl lastRecovered;

    private ReplayResult recoverWith(SnapshotStore store) {
        LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        takeOver(lease);
        try {
            EngineRecovery recovery = new EngineRecovery(replayService,
                    new EventJournalAppender(jdbc, tx, lease, objectMapper)::append, lease, store);
            MatchingEngineImpl engine = new MatchingEngineImpl();
            ReplayResult result = recovery.recover(engine);
            lastRecovered = engine;
            return result;
        } finally {
            lease.close();
        }
    }

    /**
     * ADR 0021 4단계. 전역 재생과 마켓별 재생을 shadow 비교한다.
     *
     * 이 설계의 전제는 "재생이 요구하는 것은 마켓 안의 순서이지 전역 순서가 아니다" 이다.
     * 그 전제가 맞다면 한 마켓만 골라 재생한 북이 전 마켓을 재생한 북 중 그 마켓 것과
     * 바이트가 같아야 한다. 이 시험이 그것을 실증한다.
     *
     * 마켓별 재생은 전역 seq 에 구멍이 뚫린 스트림을 받는다. 그 구멍은 다른 마켓의
     * 명령이지 결번이 아니다. 그래도 같은 북이 나오면, 전역 순서에 기대던 자리를
     * 마켓 축으로 옮겨도 상태가 보존된다는 뜻이다.
     */
    @Test
    void marketReplayEqualsGlobalReplayPerMarket() {
        MatchingEngineImpl reference = fullReplay();
        assertFalse(reference.markets().isEmpty(), "재생할 마켓이 없다");

        for (int marketId : reference.markets()) {
            MatchingEngineImpl shard = new MatchingEngineImpl();
            replayService.rebuildMarket(shard, marketId);

            assertEquals(List.of(marketId), shard.markets(),
                    "마켓 재생이 남의 마켓을 건드렸다: market=" + marketId);

            OrderBookImpl expBook = reference.orderBook(marketId);
            OrderBookImpl actBook = shard.orderBook(marketId);
            assertEquals(expBook.openOrderCount(), actBook.openOrderCount(),
                    "openOrderCount market=" + marketId);
            assertArrayEquals(SnapshotCodec.encode(expBook), SnapshotCodec.encode(actBook),
                    "마켓별 재생이 전역 재생과 다른 북을 냈다: market=" + marketId);
        }
    }

    private static void assertEngineBytesEqual(MatchingEngineImpl expected, MatchingEngineImpl actual,
                                               String label) {
        assertEquals(expected.lastAppliedSeq(), actual.lastAppliedSeq(), label + ": lastAppliedSeq");
        assertEquals(expected.markets(), actual.markets(), label + ": markets");
        assertEquals(expected.stateChecksum(), actual.stateChecksum(), label + ": stateChecksum");
        for (int marketId : expected.markets()) {
            OrderBookImpl expBook = expected.orderBook(marketId);
            OrderBookImpl actBook = actual.orderBook(marketId);
            assertEquals(expBook.openOrderCount(), actBook.openOrderCount(),
                    label + ": openOrderCount market=" + marketId);
            assertArrayEquals(SnapshotCodec.encode(expBook), SnapshotCodec.encode(actBook),
                    label + ": book bytes market=" + marketId);
        }
    }

    private static List<SnapshotStore.MarketState> freeze(MatchingEngineImpl engine) {
        List<SnapshotStore.MarketState> markets = new ArrayList<>();
        for (int marketId : engine.markets()) {
            OrderBookImpl book = engine.orderBook(marketId);
            markets.add(new SnapshotStore.MarketState(
                    marketId, SnapshotCodec.encode(book), book.stateChecksum()));
        }
        return markets;
    }

    private static List<OrderIntake> script() {
        List<OrderIntake> s = new ArrayList<>();
        s.add(limit(1, 10, 1, OrderCommand.Side.SELL, "100", "5"));   // seq1 rest ask
        s.add(limit(1, 10, 2, OrderCommand.Side.SELL, "101", "5"));   // seq2 rest ask
        s.add(limit(2, 11, 3, OrderCommand.Side.SELL, "200", "3"));   // seq3 rest ask
        s.add(limit(1, 12, 4, OrderCommand.Side.BUY, "100", "2"));    // seq4 부분체결(id1 rem3)
        s.add(limit(3, 10, 5, OrderCommand.Side.BUY, "300", "4"));    // seq5 rest bid
        s.add(limit(1, 13, 6, OrderCommand.Side.BUY, "101", "6"));    // seq6 id1 소진+id2 부분(rem2)
        s.add(limit(1, 12, 7, OrderCommand.Side.SELL, "105", "4"));   // seq7 rest ask
        s.add(limit(1, 13, 8, OrderCommand.Side.BUY, "90", "3"));     // seq8 rest bid
        s.add(limit(2, 12, 9, OrderCommand.Side.BUY, "200", "1"));    // seq9 부분체결(id3 rem2)
        s.add(limit(2, 13, 10, OrderCommand.Side.SELL, "199", "10")); // seq10 rest ask
        s.add(limit(2, 10, 11, OrderCommand.Side.BUY, "199", "4"));   // seq11 부분체결(id10 rem6)
        s.add(cancel(1, 12, 7));                                      // seq12 취소 ask@105
        s.add(limit(3, 11, 12, OrderCommand.Side.SELL, "300", "1"));  // seq13 부분체결(id5 rem3)
        s.add(marketSell(3, 12, 13, "2"));                            // seq14 시장가 IOC(id5 rem1)
        s.add(limit(1, 13, 14, OrderCommand.Side.BUY, "101", "1"));   // seq15 부분체결(id2 rem1)
        s.add(cancel(2, 99, 9999));                                   // seq16 거부(order_not_found)
        s.add(limit(1, 13, 15, OrderCommand.Side.SELL, "101", "2"));  // seq17 rest ask, level101 FIFO
        s.add(limit(3, 13, 16, OrderCommand.Side.BUY, "300", "5"));   // seq18 rest bid, bid300 FIFO
        return s;
    }

    private static OrderIntake limit(int market, long user, long orderId,
                                     OrderCommand.Side side, String price, String qty) {
        String key = "n-" + orderId;
        return new OrderIntake(market, CommandDraft.CommandType.NEW, user, key, orderId,
                BASE_TS + orderId, orderId, side, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal(price), new BigDecimal(qty), null,
                externalId(key), null, null);
    }

    private static OrderIntake marketSell(int market, long user, long orderId, String qty) {
        String key = "n-" + orderId;
        return new OrderIntake(market, CommandDraft.CommandType.NEW, user, key, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.SELL, OrderCommand.OrdType.MARKET,
                OrderCommand.TimeInForce.GTC, null, new BigDecimal(qty), null,
                externalId(key), null, null);
    }

    private static OrderIntake cancel(int market, long user, long targetOrderId) {
        String key = "c-" + market + "-" + targetOrderId;
        return new OrderIntake(market, CommandDraft.CommandType.CANCEL, user, key, targetOrderId,
                BASE_TS + 100_000 + targetOrderId, targetOrderId, null, null, null, null, null,
                null, null, null, null);
    }

    private static UUID externalId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes());
    }

    private static void takeOver(LeaderLeaseService svc) {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
    }
}

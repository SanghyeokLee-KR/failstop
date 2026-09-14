package kr.exchange.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.AcceptGate;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.EngineRecovery;
import kr.exchange.engine.FollowerCatchUp;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.WriterRouter;
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
import kr.exchange.journal.JournalCheckpoints;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스냅샷 배선 결정론 등가성(복구 RTO 설계 §3-b, 하네스 T3-A). 저널 전량 재생과 [마켓마다 스냅샷 로드 +
 * 그 마켓 커서 뒤 tail 재생]이 바이트 동일해야 한다. 여러 캡처 지점에서 반복한다.
 *
 * 바이트 판정은 SnapshotCodec.encode의 정준 직렬화를 마켓별로 비교하고 stateChecksum·markets·
 * lastAppliedSeq를 함께 본다. 스냅샷이 재생의 압축 프리픽스이므로 tail 명령 수는 마켓마다 스냅샷 커서 뒤
 * 명령 수의 합으로 상한된다.
 *
 * 라이브 저널은 운영과 같은 마켓별 라이터(WriterRouter)가 만든다. 캡처 지점은 제출 순번으로 잡고 기대값은
 * 접수 응답 seq 와 샤드 커서에서 구한다(ADR 0022 7절 2단계).
 *
 * 범위 한정: 계정 잔고는 엔진이 아니라 정산 계층의 durable DB 상태라 두 경로가 같은 DB를
 * 공유한다. 여기서 버그로 갈릴 수 있는 상태는 엔진 오더북이고 그것만 바이트로 고정한다.
 */
@Testcontainers
class SnapshotEquivalenceIntegrationTest {

    private static final long BASE_TS = 1_753_200_000_000L;
    private static final String ENGINE_VERSION = "engine-m1";
    private static final String CONFIG_HASH = "default";
    // 캡처할 제출 순번(1부터). 초반·중반·경계·마지막 제출을 덮고 마지막 값은 script() 길이여야 한다.
    private static final List<Integer> CAPTURE_AT = List.of(1, 2, 4, 6, 9, 11, 13, 15, 16, 17, 18);

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
    // 제출 순번 -> 접수 응답 seq.
    static final Map<Integer, Long> ackSeqByOrdinal = new LinkedHashMap<>();
    // 캡처한 제출의 ack seq -> 그 적용 직후 전 샤드에서 동결한 마켓별 스냅샷(인코딩 바이트 + checksum)과 샤드 커서.
    static final Map<Long, List<SnapshotStore.MarketState>> capturedByK = new LinkedHashMap<>();
    static final Map<Long, Map<Integer, Long>> cursorsByK = new LinkedHashMap<>();

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
        EventJournalAppender eventAppender = new EventJournalAppender(jdbc, tx, leader, objectMapper);
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        WriterRouter router = liveRouter(commandReader, marketId -> eventAppender::append, fatal);
        EngineBridge bridge = new EngineBridge(new CommandJournalAppender(jdbc, tx, leader), router, mapper,
                AcceptGate.ALWAYS_OPEN, EngineBridge.NEVER_HALTED);
        router.startAll();
        List<OrderIntake> script = script();
        Map<Integer, Long> ackCursors = new TreeMap<>();
        try {
            for (int ordinal = 1; ordinal <= script.size(); ordinal++) {
                OrderIntake intake = script.get(ordinal - 1);
                long seq = bridge.submit(intake).seq();
                ackSeqByOrdinal.put(ordinal, seq);
                ackCursors.put(intake.marketId(), seq);
                MatchingEngineImpl shard = router.engine(intake.marketId());
                Awaitility.await().atMost(Duration.ofSeconds(60))
                        .until(() -> shard.appliedSeq(intake.marketId()) == seq);
                if (CAPTURE_AT.contains(ordinal)) {
                    // 제출마다 그 샤드의 적용을 기다렸으므로 모든 샤드가 유휴다(적용 완료, 큐 비어 있음).
                    Map<Integer, Long> cursors = new TreeMap<>();
                    for (int marketId : router.markets()) {
                        cursors.put(marketId, router.engine(marketId).appliedSeq(marketId));
                    }
                    assertEquals(ackCursors, cursors, "동결 시점 샤드 커서가 마켓별 마지막 접수 응답과 다르다: 제출 " + ordinal);
                    capturedByK.put(seq, freeze(router));
                    cursorsByK.put(seq, cursors);
                }
            }
        } finally {
            try {
                router.stopAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertNull(fatal.get(), "라이터가 치명 오류로 멈췄다");
        assertEquals(script.size(), CAPTURE_AT.get(CAPTURE_AT.size() - 1), "마지막 캡처가 마지막 제출이 아니다");
        leaderHead = ackSeqByOrdinal.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        assertEquals(leaderHead, commandReader.maxSeq());
        assertEquals(script.size(), tailCommands(Map.of()), "제출 수와 저널 명령 수가 다르다");
        // 스크립트가 실제로 다시장·부분체결·잔여 주문을 남겼는지 확인(빈약한 저널로 등가성이
        // 공허하게 통과하는 것을 막는다).
        assertEquals(List.of(1, 2, 3), router.markets(), "3개 마켓이 모두 활성이어야 한다");
        assertTrue(router.engine(1).orderBook(1).openOrderCount() >= 3, "m1 잔여 주문 부족");
        assertTrue(router.engine(2).orderBook(2).openOrderCount() >= 2, "m2 잔여 주문 부족");
        assertTrue(router.engine(3).orderBook(3).openOrderCount() >= 2, "m3 잔여 주문 부족");
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

    /**
     * 마켓마다 스냅샷 시작점에서 이어붙인 tail 재생이 전량 재생과 바이트가 같다.
     *
     * 시작점은 캡처마다 두 벌이다. 전 마켓을 같은 캡처에서 싣는 것과, 마켓 순번만큼 앞선 캡처에서 싣는 것이다.
     * 뒤의 것은 마켓 커서가 서로 다른 seq 에 서므로 전역 커서 하나로 tail 을 열면 커서가 낮은 마켓의 명령을 건너뛴다.
     */
    @Test
    void tailFromSnapshotStartEqualsFullReplayPerMarket() {
        MatchingEngineImpl reference = fullReplay();
        List<Integer> markets = List.copyOf(replayService.journalHeads().keySet());
        Map<Integer, Integer> loads = new TreeMap<>();
        Map<Integer, Long> tails = new TreeMap<>();
        long belowOtherCursors = 0;
        for (int stagger : List.of(0, 1)) {
            for (int i = 0; i < CAPTURE_AT.size(); i++) {
                MatchingEngineImpl b = new MatchingEngineImpl();
                Map<Integer, Long> start = new TreeMap<>();
                for (int j = 0; j < markets.size(); j++) {
                    int marketId = markets.get(j);
                    int ordinal = CAPTURE_AT.get(Math.max(0, i - stagger * j));
                    for (SnapshotStore.MarketState ms : captured(ordinal)) {
                        if (ms.marketId() != marketId) {
                            continue;
                        }
                        OrderBookImpl book = SnapshotCodec.decode(ms.state());
                        // codec 왕복 결정론: decode(encode(book)).checksum == 원본 checksum.
                        assertEquals(ms.checksum(), book.stateChecksum(),
                                "codec 왕복 checksum 불일치: 제출 " + ordinal + ", market=" + marketId);
                        long cursor = cursorsByK.get(ackSeq(ordinal)).get(marketId);
                        b.loadMarketSnapshot(marketId, cursor, book);
                        start.put(marketId, cursor);
                        loads.merge(marketId, 1, Integer::sum);
                    }
                }
                String label = "stagger=" + stagger + ", start=" + start;
                assertEquals(start, b.appliedSeqs(), "적재가 마켓 커서를 그 마켓 스냅샷 커서로 세우지 않았다: " + label);
                long maxStart = start.values().stream().mapToLong(Long::longValue).max().orElse(0);
                for (int marketId : markets) {
                    belowOtherCursors += commandsAfter(marketId, start.getOrDefault(marketId, 0L))
                            - commandsAfter(marketId, maxStart);
                }

                // 커서 시작점을 넘기지 않는다. 경계와 재생이 엔진의 마켓 커서에서 연다.
                JournalCheckpoints bounds = replayService.materializedThroughByMarket(b);
                long tail = 0;
                for (int marketId : bounds.bounds().keySet()) {
                    long applied = replayService.verifyToMarket(b, marketId, bounds).replayedCommands();
                    tails.merge(marketId, applied, Long::sum);
                    tail += applied;
                }

                assertEngineBytesEqual(reference, b, label);
                assertEquals(tailCommands(start), tail, "tail 이 마켓별 스냅샷 커서로 상한되지 않았다: " + label);
            }
        }
        assertEquals(markets, List.copyOf(loads.keySet()), "스냅샷에서 한 번도 싣지 않은 마켓이 있다");
        assertTrue(markets.stream().allMatch(marketId -> tails.getOrDefault(marketId, 0L) > 0),
                "tail 에서 명령을 적용한 적이 없는 마켓이 있다: " + tails);
        assertTrue(belowOtherCursors > 0, "다른 마켓 커서 아래에 남은 명령이 없어 전역 커서 tail 을 판정하지 못한다");
    }

    @Test
    void storeRecoveryAdoptsLatestThenFallsBackOnCorruption() {
        // 공유 append-only snapshots 테이블이라 형제 테스트가 심은 유효본도 후보에 든다. 채택 seq 를
        // 하드코딩하지 않고 게이트가 마켓마다 고른 값으로 tail 상한을 검증한다(순서 무관).
        SnapshotStore store = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        persistAsLeader(store, captured(9));

        Map<Integer, SnapshotSource.MarketLoaded> adopted = store.loadLatestAdoptablePerMarket();
        assertEquals(Set.of(1, 2, 3), adopted.keySet(), "유효 스냅샷을 채택하지 않은 마켓이 있다");
        assertTrue(adopted.entrySet().stream()
                        .allMatch(e -> e.getValue().seq() >= cursor(9, e.getKey()) && e.getValue().seq() < leaderHead),
                "유효 스냅샷 채택 실패: " + seqs(adopted));
        ReplayResult adoptResult = recoverWith(store);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "store-adopt");
        assertEquals(tailCommands(seqs(adopted)), adoptResult.replayedCommands(), "채택 tail 상한 위반");
        assertTrue(adoptResult.pendingEvents().isEmpty());

        // state_checksum 손상본을 전 마켓 최상위 seq 에 추가한다. 게이트가 폐기하고 마켓마다 직전 유효본으로
        // 물러서야 한다(스냅샷 = 저널 파생 최적화, 단일 정본 아님).
        List<SnapshotStore.MarketState> corrupt = new ArrayList<>();
        for (SnapshotStore.MarketState ms : captured(17)) {
            corrupt.add(new SnapshotStore.MarketState(ms.marketId(), ms.seq(), ms.state(), ms.checksum() ^ 0x5A5AL));
        }
        persistAsLeader(store, corrupt);
        assertEquals(seqs(adopted), seqs(store.loadLatestAdoptablePerMarket()),
                "손상본을 폐기하고 마켓마다 직전 유효본으로 물러서야 한다");
        ReplayResult afterCorrupt = recoverWith(store);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "store-fallback-checksum");
        assertEquals(tailCommands(seqs(adopted)), afterCorrupt.replayedCommands(), "손상 폴백 tail 상한 위반");
    }

    @Test
    void corruptSnapshotOfOneMarketFallsBackOnlyThatMarket() {
        SnapshotStore store = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        persistAsLeader(store, captured(13));
        List<SnapshotStore.MarketState> oneCorrupt = new ArrayList<>();
        for (SnapshotStore.MarketState ms : captured(15)) {
            long checksum = ms.marketId() == 3 ? ms.checksum() ^ 0x5A5AL : ms.checksum();
            oneCorrupt.add(new SnapshotStore.MarketState(ms.marketId(), ms.seq(), ms.state(), checksum));
        }
        persistAsLeader(store, oneCorrupt);

        Map<Integer, SnapshotSource.MarketLoaded> adopted = store.loadLatestAdoptablePerMarket();

        assertEquals(Set.of(1, 2, 3), adopted.keySet(), "채택본이 없는 마켓이 있다: " + seqs(adopted));
        assertTrue(adopted.get(1).seq() >= cursor(15, 1) && adopted.get(2).seq() >= cursor(15, 2),
                "손상 없는 마켓이 제출 15 스냅샷을 채택하지 않았다. 한 마켓 손상이 다른 마켓을 끌어내렸다: " + seqs(adopted));
        assertTrue(adopted.get(3).seq() >= cursor(13, 3) && adopted.get(3).seq() < cursor(15, 3),
                "손상 마켓이 폐기되지 않았거나 직전 유효본으로 물러서지 않았다: " + seqs(adopted));
        // 물러선 구간에 그 마켓 명령이 있어야 커서를 어디서 여는지가 북 바이트와 tail 수로 드러난다.
        assertTrue(commandsBetween(3, adopted.get(3).seq(), cursor(15, 3)) > 0,
                "마켓 3 이 물러선 구간에 명령이 없어 물러선 마켓의 커서가 판정되지 않는다");
        ReplayResult result = recoverWith(store);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "one-market-fallback");
        assertEquals(tailCommands(seqs(adopted)), result.replayedCommands(), "마켓별 tail 상한 위반");
        assertTrue(result.pendingEvents().isEmpty());
    }

    @Test
    void snapshotWhoseBookBelongsToAnotherMarketIsDiscarded() {
        SnapshotStore store = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        SnapshotStore.MarketState market2Book = captured(16).stream()
                .filter(ms -> ms.marketId() == 2).findFirst().orElseThrow();
        // 마켓 1 행에 마켓 2 의 북을 싣는다. checksum 은 그 북의 것이라 checksum 게이트는 통과한다.
        persistAsLeader(store,
                List.of(new SnapshotStore.MarketState(1, ackSeq(16), market2Book.state(), market2Book.checksum())));

        Map<Integer, SnapshotSource.MarketLoaded> adopted = store.loadLatestAdoptablePerMarket();

        assertTrue(!adopted.containsKey(1) || adopted.get(1).seq() != ackSeq(16),
                "다른 마켓의 북을 담은 스냅샷을 채택했다: " + seqs(adopted));
        recoverWith(store);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "foreign-book-discard");
    }

    private static long commandsBetween(int marketId, long afterSeq, long upToSeq) {
        return jdbc.sql("SELECT count(*) FROM command_journal WHERE market_id = ? AND seq > ? AND seq <= ?")
                .params(marketId, afterSeq, upToSeq).query(Long.class).single();
    }

    /** 마켓마다 커서 초과 명령 수의 합. 커서가 없는 마켓은 전 명령이다. */
    private static long tailCommands(Map<Integer, Long> cursors) {
        long total = 0;
        for (int marketId : replayService.journalHeads().keySet()) {
            total += commandsAfter(marketId, cursors.getOrDefault(marketId, 0L));
        }
        return total;
    }

    private static long commandsAfter(int marketId, long seq) {
        return jdbc.sql("SELECT count(*) FROM command_journal WHERE market_id = ? AND seq > ?")
                .params(marketId, seq).query(Long.class).single();
    }

    private static Map<Integer, Long> seqs(Map<Integer, SnapshotSource.MarketLoaded> adopted) {
        Map<Integer, Long> out = new TreeMap<>();
        adopted.forEach((marketId, loaded) -> out.put(marketId, loaded.seq()));
        return out;
    }

    @Test
    void versionAndConfigMismatchDiscardAllAndFullReplay() {
        SnapshotStore base = new SnapshotStore(jdbc, tx, ENGINE_VERSION, CONFIG_HASH);
        persistAsLeader(base, captured(11));
        assertFalse(base.loadLatestAdoptablePerMarket().isEmpty(), "동일 버전·설정은 채택되어야 한다");

        // engine_version 불일치: 전 세트 폐기 → 전량 재생.
        SnapshotStore upgraded = new SnapshotStore(jdbc, tx, "engine-m2", CONFIG_HASH);
        assertTrue(upgraded.loadLatestAdoptablePerMarket().isEmpty(), "engine_version 불일치 채택");
        ReplayResult byVersion = recoverWith(upgraded);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "version-fallback");
        assertEquals(tailCommands(Map.of()), byVersion.replayedCommands(), "폴백인데 전량 재생이 아니다");

        // config_hash 불일치: 전 세트 폐기 → 전량 재생.
        SnapshotStore reconfigured = new SnapshotStore(jdbc, tx, ENGINE_VERSION, "config-changed");
        assertTrue(reconfigured.loadLatestAdoptablePerMarket().isEmpty(), "config_hash 불일치 채택");
        ReplayResult byConfig = recoverWith(reconfigured);
        assertEngineBytesEqual(fullReplay(), lastRecovered, "config-fallback");
        assertEquals(tailCommands(Map.of()), byConfig.replayedCommands());
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

    /**
     * 마켓 상한 재생은 bound(m) 에서 멈추고 이어지는 tail 이 head(m) 까지 채워 전역 재생과 같은 북을 낸다.
     * 상한을 두 마켓 1 명령 사이의 다른 마켓 seq 자리에 둬 명령 seq 와 겹칠 때만 맞는 구현을 거른다.
     */
    @Test
    void boundedMarketReplayStopsAtMarketBound() {
        List<Long> seqs = jdbc.sql("SELECT seq FROM command_journal WHERE market_id = 1 ORDER BY seq")
                .query(Long.class).list();
        int k = -1;
        for (int i = 0; i + 1 < seqs.size(); i++) {
            if (seqs.get(i + 1) - seqs.get(i) > 1) {
                k = i;
                break;
            }
        }
        assertTrue(k >= 0, "마켓 1 명령 사이에 다른 마켓 seq 가 없어 상한 위치가 판정되지 않는다: " + seqs);
        long bound = seqs.get(k + 1) - 1;
        long head = seqs.get(seqs.size() - 1);
        MatchingEngineImpl shard = new MatchingEngineImpl();

        ReplayResult stable = replayService.verifyToMarket(shard, 1,
                new JournalCheckpoints(Map.of(1, bound), eventReader.legacyBound()));

        assertEquals(seqs.get(k), shard.appliedSeq(1), "상한 너머를 적용했거나 상한에 못 미쳤다");
        assertEquals(k + 1, stable.replayedCommands());
        assertTrue(stable.verifiedEvents() > 0, "대조한 이벤트가 없다");
        assertEquals(List.of(1), shard.markets(), "마켓 상한 재생이 남의 마켓을 건드렸다");
        MatchingEngineImpl exact = new MatchingEngineImpl();
        replayService.verifyToMarket(exact, 1,
                new JournalCheckpoints(Map.of(1, seqs.get(k)), eventReader.legacyBound()));
        assertEquals(seqs.get(k), exact.appliedSeq(1), "상한이 명령 seq 와 같을 때 그 명령을 적용하지 않았다");

        ReplayResult tail = replayService.replayTailMarket(shard, 1, head);

        assertEquals(seqs.subList(k + 1, seqs.size()),
                tail.pendingBatches().stream().map(ReplayResult.PendingBatch::seq).toList(),
                "tail 배치가 상한 위 마켓 1 명령과 다르다");
        assertEquals(head, shard.appliedSeq(1));
        assertArrayEquals(SnapshotCodec.encode(fullReplay().orderBook(1)),
                SnapshotCodec.encode(shard.orderBook(1)), "상한 재생과 tail 이 전역 재생과 다른 북을 냈다");
    }

    /**
     * 상한 있는 마켓별 재생이 라이브 라우터 샤드의 북과 같다(ADR 0022 7절 2단계).
     *
     * 캡처마다 그 시점 샤드 커서를 상한으로 fresh 엔진에서 재생해 동결한 샤드 북과 맞춘다. head 에서는 상한을 엔진
     * 커서로 구한 경계로 두고 그 경계가 샤드 커서와 같은지 본다. 전역 경계를 쓰면 head 가 낮은 마켓에서 갈린다.
     */
    @Test
    void boundedPerMarketReplayEqualsLive() {
        int last = CAPTURE_AT.get(CAPTURE_AT.size() - 1);
        Map<Integer, Long> headCursors = cursorsByK.get(ackSeq(last));
        assertEquals(replayService.journalHeads().keySet(), headCursors.keySet(), "라이브 샤드가 없는 저널 마켓이 있다");
        Map<Integer, Integer> belowHead = new TreeMap<>();
        for (int ordinal : CAPTURE_AT) {
            Map<Integer, Long> cursors = cursorsByK.get(ackSeq(ordinal));
            for (SnapshotStore.MarketState live : captured(ordinal)) {
                int marketId = live.marketId();
                long cursor = cursors.get(marketId);
                String label = "제출 " + ordinal + ", market=" + marketId;
                MatchingEngineImpl fresh = new MatchingEngineImpl();

                ReplayResult replayed = replayService.verifyToMarket(fresh, marketId,
                        new JournalCheckpoints(Map.of(marketId, cursor), eventReader.legacyBound()));

                assertTrue(replayed.replayedCommands() > 0, "적용한 명령이 없다: " + label);
                assertEquals(commandsAfter(marketId, 0) - commandsAfter(marketId, cursor), replayed.replayedCommands(),
                        "적용 건수가 상한 이하 마켓 명령 수와 다르다: " + label);
                assertEquals(cursor, fresh.appliedSeq(marketId), "상한 너머를 적용했거나 상한에 못 미쳤다: " + label);
                assertArrayEquals(live.state(), SnapshotCodec.encode(fresh.orderBook(marketId)),
                        "상한 재생이 그 시점 라이브 샤드 북과 다르다: " + label);
                if (cursor < headCursors.get(marketId)) {
                    belowHead.merge(marketId, 1, Integer::sum);
                }
            }
        }
        assertEquals(headCursors.keySet(), belowHead.keySet(), "head 아래 상한으로 재생한 적이 없는 마켓이 있다");

        for (SnapshotStore.MarketState live : captured(last)) {
            int marketId = live.marketId();
            MatchingEngineImpl fresh = new MatchingEngineImpl();
            JournalCheckpoints bounds = replayService.materializedThroughByMarket(fresh);

            ReplayResult replayed = replayService.verifyToMarket(fresh, marketId, bounds);

            assertTrue(replayed.replayedCommands() > 0, "적용한 명령이 없다: market=" + marketId);
            assertEquals(headCursors.get(marketId), bounds.bound(marketId),
                    "경계가 라이브 샤드 커서와 다르다: market=" + marketId);
            assertEquals(commandsAfter(marketId, 0), replayed.replayedCommands(),
                    "경계 재생이 그 마켓 명령을 다 적용하지 않았다: market=" + marketId);
            assertTrue(replayed.verifiedEvents() > 0, "대조한 이벤트가 없다: market=" + marketId);
            assertEquals(List.of(marketId), fresh.markets(), "마켓 재생이 남의 마켓을 건드렸다: market=" + marketId);
            assertArrayEquals(live.state(), SnapshotCodec.encode(fresh.orderBook(marketId)),
                    "경계 재생이 라이브 샤드 head 북과 다르다: market=" + marketId);
        }
    }

    /**
     * 라이브 마켓별 라이터가 쓰는 동안 돈 팔로워 따라잡기가, 쓰기가 멈춘 뒤 리더 샤드와 같은 북에 수렴한다.
     *
     * 공유 저널을 늘리면 형제 시험의 기대값이 흔들리므로 같은 컨테이너의 별도 데이터베이스에서 돈다. 끝에서 마켓 1 라이터를
     * 정산 직전에 붙잡아 낮은 seq 마켓에 구멍을 둔 채 마켓 2 를 앞세운다. 전역 경계로 따라잡으면 마켓 2 가 거기서 멈추고,
     * 마켓 판정에 전역 커서를 쓰면 구멍이 메워진 뒤 마켓 1 을 건너뛴다.
     */
    @Test
    void followerCatchUpWhileLiveWritersRunConverges() throws Exception {
        String database = "snapshot_eq_follower";
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + database);
        }
        String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + database);
        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(8);
        try (HikariDataSource ds = new HikariDataSource(config);
             LeaderLeaseService leader = new LeaderLeaseService(JdbcClient.create(ds), Duration.ofSeconds(120),
                     Duration.ofSeconds(1));
             LeaderLeaseService followerLease = new LeaderLeaseService(JdbcClient.create(ds),
                     Duration.ofSeconds(120), Duration.ofSeconds(1))) {
            JdbcClient db = JdbcClient.create(ds);
            TransactionTemplate dbTx = new TransactionTemplate(new JdbcTransactionManager(ds));
            CommandJournalReader reader = new CommandJournalReader(db);
            ReplayService replay = new ReplayService(reader, new EventJournalReader(db), mapper, objectMapper,
                    new MarketCatalog(db));
            assertTrue(leader.tryAcquire(), "테스트 리더 승격 실패");
            EventJournalAppender appender = new EventJournalAppender(db, dbTx, leader, objectMapper);
            AtomicBoolean holdMarket1 = new AtomicBoolean();
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> fatal = new AtomicReference<>();
            WriterRouter router = liveRouter(reader, marketId -> events -> {
                if (marketId == 1 && holdMarket1.get()) {
                    release.await();
                }
                appender.append(events);
            }, fatal);
            EngineBridge bridge = new EngineBridge(new CommandJournalAppender(db, dbTx, leader), router, mapper,
                    AcceptGate.ALWAYS_OPEN, EngineBridge.NEVER_HALTED);
            MatchingEngineImpl follower = new MatchingEngineImpl();
            FollowerCatchUp catchUp = new FollowerCatchUp(replay, follower, followerLease, Duration.ofSeconds(1),
                    new SimpleMeterRegistry());
            ExecutorService submitter = Executors.newSingleThreadExecutor();
            router.startAll();
            try {
                List<OrderIntake> script = followerScript();
                CountDownLatch followerMoved = new CountDownLatch(1);
                Future<Map<Integer, Long>> writes = submitter.submit(() -> {
                    Map<Integer, Long> lastAcks = new TreeMap<>();
                    for (int i = 0; i < script.size(); i++) {
                        // 뒤 절반은 팔로워가 한 번 전진한 뒤에 쓴다. 따라잡기와 라이브 쓰기의 겹침을 타이밍에 맡기지 않는다.
                        if (i == script.size() / 2) {
                            assertTrue(followerMoved.await(60, TimeUnit.SECONDS), "쓰기 도중 팔로워가 전진하지 않았다");
                        }
                        lastAcks.put(script.get(i).marketId(), bridge.submit(script.get(i)).seq());
                    }
                    return lastAcks;
                });
                while (!writes.isDone()) {
                    Map<Integer, Long> before = follower.appliedSeqs();
                    catchUp.tick();
                    if (!follower.appliedSeqs().equals(before)) {
                        followerMoved.countDown();
                    }
                }
                Map<Integer, Long> acks = new TreeMap<>(writes.get());
                assertEquals(0, followerMoved.getCount(), "쓰기 도중 전진한 따라잡기 틱이 없다");
                awaitApplied(router, acks);

                // 마켓 1 라이터를 정산 직전에 세운다. 그 명령은 샤드에 적용됐지만 이벤트가 없어 마켓 1 의 구멍이 된다.
                holdMarket1.set(true);
                long held = bridge.submit(limit(1, 32, 20_001, OrderCommand.Side.BUY, "100", "1")).seq();
                long ahead = bridge.submit(limit(2, 32, 20_002, OrderCommand.Side.BUY, "200", "1")).seq();
                acks.put(1, held);
                acks.put(2, ahead);
                assertTrue(held < ahead, "구멍 명령이 앞선 마켓 명령 아래에 있어야 전역 커서와 마켓 커서가 갈린다");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (follower.appliedSeq(2) < ahead) {
                    assertTrue(System.nanoTime() < deadline,
                            "마켓 1 구멍이 마켓 2 따라잡기를 눌렀다: state=" + catchUp.state());
                    catchUp.tick();
                }
                assertTrue(follower.appliedSeq(1) < held, "이벤트가 없는 마켓 1 명령을 따라잡기가 적용했다");
                assertTrue(catchUp.lagCommands() >= 1, "마켓 1 정체가 격차에 드러나지 않았다");
                release.countDown();

                awaitApplied(router, acks);
                router.stopAll();
                assertNull(fatal.get(), "라이브 라이터가 치명 오류로 멈췄다");
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!follower.appliedSeqs().equals(acks) || catchUp.lagCommands() != 0) {
                    assertTrue(System.nanoTime() < deadline, "팔로워가 리더 샤드 커서에 수렴하지 않았다: follower="
                            + follower.appliedSeqs() + ", leader=" + acks + ", state=" + catchUp.state());
                    catchUp.tick();
                }

                assertEquals(1, catchUp.state(), "따라잡는 중 상태가 아니다. -1 이면 대조가 갈렸다");
                assertEquals(0, catchUp.lagCommandsSum(), "마켓별 격차 합이 0 이 아니다");
                assertTrue(catchUp.verifiedEvents() > 0, "따라잡기가 이벤트를 한 건도 대조하지 않았다");
                assertTrue(db.sql("SELECT count(*) FROM event_journal WHERE event_type = 'TRADE'")
                        .query(Long.class).single() > 0, "체결이 없어 북 비교가 잔여 주문 적재만 본다");
                assertEquals(router.markets(), follower.markets(), "팔로워와 리더 샤드의 마켓 집합이 다르다");
                for (int marketId : router.markets()) {
                    OrderBookImpl leaderBook = router.engine(marketId).orderBook(marketId);
                    assertTrue(leaderBook.openOrderCount() > 0, "잔여 주문이 없다: market=" + marketId);
                    assertEquals(leaderBook.openOrderCount(), follower.orderBook(marketId).openOrderCount(),
                            "openOrderCount market=" + marketId);
                    assertArrayEquals(SnapshotCodec.encode(leaderBook),
                            SnapshotCodec.encode(follower.orderBook(marketId)),
                            "팔로워 북이 리더 샤드 북과 다르다: market=" + marketId);
                }
            } finally {
                // 붙잡힌 라이터를 풀지 않으면 비데몬 라이터 스레드가 남는다.
                release.countDown();
                submitter.shutdownNow();
                router.stopAll();
            }
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

    private static long ackSeq(int ordinal) {
        return ackSeqByOrdinal.get(ordinal);
    }

    /** 제출 순번 ordinal 을 적용한 직후 그 마켓 샤드의 커서. 스냅샷 행의 last_applied_seq 가 이 값이다. */
    private static long cursor(int ordinal, int marketId) {
        return cursorsByK.get(ackSeq(ordinal)).get(marketId);
    }

    /** 지금 리더로 적재한다. 적재 트랜잭션이 리더 검증을 거치므로 다른 시험의 승격으로 낡은 epoch 로는 쓸 수 없다. */
    private static void persistAsLeader(SnapshotStore store, List<SnapshotStore.MarketState> markets) {
        try (LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120),
                Duration.ofSeconds(1))) {
            takeOver(lease);
            store.persist(lease.requireLeader(), markets);
        }
    }

    /** 제출 순번 ordinal 을 적용한 직후 전 샤드에서 동결한 마켓별 스냅샷. */
    private static List<SnapshotStore.MarketState> captured(int ordinal) {
        return capturedByK.get(ackSeq(ordinal));
    }

    /** 운영 배선(EngineConfig.writerRouter)과 같은 마켓별 라이터. 갭 회수는 그 마켓 명령만 되읽는다. */
    private static WriterRouter liveRouter(CommandJournalReader reader, IntFunction<SingleWriterLoop.EventSink> sinks,
                                           AtomicReference<Throwable> fatal) {
        return new WriterRouter(marketId -> {
            MatchingEngineImpl engine = new MatchingEngineImpl();
            return new WriterRouter.Shard(engine, new SingleWriterLoop(4096, engine, sinks.apply(marketId),
                    new JournalCatchUpSource(reader, mapper, marketId), fatal::set));
        });
    }

    private static void awaitApplied(WriterRouter router, Map<Integer, Long> acks) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> acks.entrySet().stream()
                .allMatch(e -> router.engine(e.getKey()).appliedSeq(e.getKey()) == e.getValue()));
    }

    private static List<SnapshotStore.MarketState> freeze(WriterRouter router) {
        List<SnapshotStore.MarketState> markets = new ArrayList<>();
        for (int marketId : router.markets()) {
            MatchingEngineImpl shard = router.engine(marketId);
            OrderBookImpl book = shard.orderBook(marketId);
            markets.add(new SnapshotStore.MarketState(
                    marketId, shard.appliedSeq(marketId), SnapshotCodec.encode(book), book.stateChecksum()));
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

    /** 세 마켓에 번갈아 쌓이며 체결과 잔여 주문을 함께 남기는 제출 순서. */
    private static List<OrderIntake> followerScript() {
        List<OrderIntake> s = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            int market = 1 + i % 3;
            long orderId = 10_000 + i;
            s.add(i % 2 == 0
                    ? limit(market, 30, orderId, OrderCommand.Side.SELL, String.valueOf(100 + i % 7), "3")
                    : limit(market, 31, orderId, OrderCommand.Side.BUY, String.valueOf(103 - i % 5), "2"));
        }
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

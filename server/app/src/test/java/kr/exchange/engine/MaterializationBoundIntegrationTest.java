package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalAppender;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.EventJournalRow;
import kr.exchange.journal.JournalCheckpoints;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 재생 상한이 물질화된 명령 아래의 구멍 앞에서 멈추는지 본다(ADR 0023).
 *
 * 종전 상한은 `max(source_cmd_seq)` 였다. 명령 저널은 접수 시점에 커밋되고 이벤트는 마켓별
 * 라이터가 각자 정산 트랜잭션에서 적재하므로 그 값 아래에 구멍이 남는다. 상한이 구멍 위에
 * 서면 대조가 구멍 명령의 재생 이벤트와 그 위 명령의 저널 행을 짝지어 거짓 불일치를 낸다.
 * 팔로워는 복귀 경로 없이 굳는다. 실측은 `harness/evidence/materialization-holes-20260912/` 다.
 *
 * 클래스를 따로 두는 이유가 있다. 이 검증은 저널에 영원한 구멍을 남긴다. 합성 이벤트의
 * payload 가 재생 결과와 맞지 않으므로 그 뒤의 어떤 대조도 통과하지 못하고,
 * `event_journal` 은 append-only 라 되돌릴 수 없다. 같은 컨테이너를 쓰는 다른 검증과 섞으면
 * 실행 순서에 따라 결과가 갈린다.
 *
 * 마켓별 경계 검증(DB 설계 §5.5-a)은 시험마다 같은 컨테이너의 별도 데이터베이스를 쓴다. 명령과
 * 이벤트를 채번 없이 직접 넣어 구멍을 합성하므로 공유 데이터베이스에 두면 위 검증의 전역 복구와
 * 섞인다. 레거시 구간은 V39 까지 올린 데이터베이스에 market_id 없는 이벤트를 넣고 나머지
 * 마이그레이션을 적용해 만든다. V40 이 그때 journal_axis_boundary 를 박제한다.
 */
@Testcontainers
class MaterializationBoundIntegrationTest {

    private static final long BASE_TS = 1_753_400_000_000L;
    private static final String PAYLOAD =
            "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                    + "\"price\":\"100\",\"qty\":\"1\",\"quote_order_qty\":null}";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static ReplayService replayService;
    static EventJournalAppender leaderAppender;
    static CommandJournalAppender leaderCommands;
    static LeaderLeaseService liveLeader;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(6);
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        ObjectMapper objectMapper = new ObjectMapper();
        replayService = new ReplayService(new CommandJournalReader(jdbc),
                new EventJournalReader(jdbc), new CommandPayloadMapper(objectMapper),
                objectMapper, new MarketCatalog(jdbc));

        liveLeader = new LeaderLeaseService(jdbc, Duration.ofSeconds(30), Duration.ofSeconds(1));
        assertTrue(liveLeader.tryAcquire(), "리더 리스 선점 실패");
        liveLeader.start();
        leaderAppender = new EventJournalAppender(jdbc, tx, liveLeader, objectMapper);
        leaderCommands = new CommandJournalAppender(jdbc, tx, liveLeader);
    }

    @AfterAll
    static void tearDown() {
        if (liveLeader != null) {
            liveLeader.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void 상한은_물질화된_명령_아래_구멍_앞에서_멈춘다() {
        long base = appendCommand("hole-base", 201L);
        materializeAsLeader();
        assertEquals(base, replayService.materializedThrough(0).commandSeq(),
                "기준선이 서지 않았다");

        long hole = appendCommand("hole-gap", 202L);
        long above = appendCommand("hole-above", 203L);

        // 구멍 위 명령만 물질화한다. 마켓별 라이터가 서로 다른 트랜잭션으로 커밋하는 상황과
        // 같은 상태다. 저널은 append-only 라 INSERT 로만 만든다.
        insertSyntheticEvent(above);

        // 양성 증거 축. 구멍과 그 위 물질화가 실제로 존재하는지 먼저 증명한다.
        assertEquals(0, countEvents(hole), "구멍이 만들어지지 않았다");
        assertEquals(1, countEvents(above), "구멍 위 물질화가 만들어지지 않았다");
        // 종전 술어가 구멍 위를 가리키는 것을 같은 시험에서 대조로 남긴다. 이것이 없으면
        // 아래 단언이 교정 전 구현에서도 통과하는지 알 수 없다.
        assertEquals(above, jdbc.sql("SELECT max(source_cmd_seq) FROM event_journal")
                        .query(Long.class).single(),
                "종전 술어가 구멍 위를 가리키지 않는다. 이 검증은 아무것도 판정하지 않는다");

        assertEquals(base, replayService.materializedThrough(0).commandSeq(),
                "상한이 구멍을 넘어섰다");
    }

    @Test
    void holeInOneMarketDoesNotCapOtherMarket() throws SQLException {
        JdbcClient db = client(createDatabase("bound_hole_interleaved", null));
        insertCommands(db, 1, 1, 1);
        insertMarketEvents(db, 1, 1, 1, 1);
        insertCommands(db, 1, 2, 2);
        insertCommands(db, 2, 3, 3);
        insertMarketEvents(db, 2, 3, 3, 1);
        insertCommands(db, 1, 4, 4);
        insertMarketEvents(db, 1, 4, 4, 1);
        insertCommands(db, 2, 5, 5);
        insertMarketEvents(db, 2, 5, 5, 1);
        CommandJournalReader commands = new CommandJournalReader(db);
        EventJournalReader events = new EventJournalReader(db);

        assertEquals(0, countEvents(db, 2), "구멍이 만들어지지 않았다");
        assertEquals(Map.of(1, 4L, 2, 5L), commands.maxSeqByMarket());
        assertEquals(1, events.materializedThrough(0).commandSeq(),
                "전역 경계가 마켓 1 구멍에 멈추지 않는다. 이 검증은 아무것도 판정하지 않는다");

        JournalCheckpoints checkpoints = events.materializedThroughByMarket(Map.of());
        assertEquals(0, checkpoints.legacyBound());
        assertEquals(1, checkpoints.bound(1), "마켓 1 상한이 구멍 앞에 서지 않았다");
        assertEquals(commands.maxSeqForMarket(2), checkpoints.bound(2),
                "마켓 2 상한이 다른 마켓의 구멍에 묶였다");
    }

    @Test
    void marketBoundNeverExceedsHeadOrFloor() throws SQLException {
        String url = createDatabase("bound_head_floor", "39");
        JdbcClient db = client(url);
        insertCommands(db, 1, 1, 1);
        insertCommands(db, 2, 2, 2);
        insertCommands(db, 1, 3, 3);
        insertLegacyEvents(db, 1, 3, 1);
        migrate(url, null);
        insertCommands(db, 1, 4, 4);
        insertMarketEvents(db, 1, 4, 4, 1);
        insertCommands(db, 3, 5, 5);
        insertMarketEvents(db, 3, 5, 5, 1);
        insertCommands(db, 1, 6, 7);
        insertCommands(db, 3, 8, 8);
        insertCommands(db, 1, 9, 9);
        insertMarketEvents(db, 1, 9, 9, 1);
        CommandJournalReader commands = new CommandJournalReader(db);
        EventJournalReader events = new EventJournalReader(db);
        // 마켓 2 커서는 레거시 경계 아래, 마켓 3 커서는 구멍 위에 선다. 마켓 4 는 명령 없이 커서만 있다.
        // 바닥이 커서와 경계 중 큰 값인지, 구멍 탐색이 바닥 위에서만 도는지를 지나게 한다.
        Map<Integer, Long> cursors = Map.of(1, 4L, 2, 1L, 3, 8L, 4, 10L);

        JournalCheckpoints checkpoints = events.materializedThroughByMarket(cursors);

        assertEquals(3, checkpoints.legacyBound(), "레거시 경계가 서지 않아 바닥 축이 판정되지 않는다");
        assertEquals(Map.of(1, 5L, 2, 3L, 3, 8L, 4, 10L), checkpoints.bounds());
        int belowHead = 0;
        int floorAboveHead = 0;
        int multiHoleAboveFloor = 0;
        int holeUpToFloor = 0;
        for (Map.Entry<Integer, Long> entry : checkpoints.bounds().entrySet()) {
            int marketId = entry.getKey();
            long bound = entry.getValue();
            long floor = Math.max(cursors.getOrDefault(marketId, 0L), checkpoints.legacyBound());
            long head = commands.maxSeqForMarket(marketId);
            assertTrue(floor <= bound, "마켓 " + marketId + " 상한이 바닥 아래다: " + bound);
            assertTrue(bound <= Math.max(head, floor),
                    "마켓 " + marketId + " 상한이 head 와 바닥을 넘었다: " + bound);
            if (bound < head) {
                belowHead++;
            }
            if (bound == floor && floor > head) {
                floorAboveHead++;
            }
            if (countEventless(db, marketId, floor, head) >= 2) {
                multiHoleAboveFloor++;
            }
            if (countEventless(db, marketId, 0, floor) > 0) {
                holeUpToFloor++;
            }
        }
        assertTrue(belowHead > 0, "구멍으로 head 아래에 선 마켓이 없어 위쪽 부등호가 판정되지 않는다");
        assertTrue(floorAboveHead > 0, "바닥이 head 위인 마켓이 없어 아래쪽 부등호가 판정되지 않는다");
        assertTrue(multiHoleAboveFloor > 0, "바닥 위 구멍이 둘 이상인 마켓이 없어 첫 구멍 선택이 판정되지 않는다");
        assertTrue(holeUpToFloor > 0,
                "바닥 이하에 구멍이 있는 마켓이 없어 구멍 경로의 아래쪽 부등호가 판정되지 않는다");
    }

    @Test
    void holeInOneMarketDoesNotLowerOtherMarketBelowItsHead() throws SQLException {
        JdbcClient db = client(createDatabase("bound_hole_below_other", null));
        insertCommands(db, 2, 100, 100);
        insertCommands(db, 1, 101, 200);
        insertMarketEvents(db, 1, 101, 200, 1);
        EventJournalReader events = new EventJournalReader(db);

        assertEquals(0, countEvents(db, 100), "구멍이 만들어지지 않았다");
        assertEquals(100, db.sql("SELECT count(*) FROM event_journal WHERE market_id = 1")
                .query(Long.class).single(), "마켓 1 물질화가 만들어지지 않았다");
        assertEquals(99, events.materializedThrough(0).commandSeq(),
                "전역 경계가 마켓 2 구멍에 멈추지 않는다. 이 검증은 아무것도 판정하지 않는다");

        JournalCheckpoints checkpoints = events.materializedThroughByMarket(Map.of());
        assertEquals(200, checkpoints.bound(1), "마켓 1 상한이 다른 마켓의 구멍으로 내려갔다");
        assertEquals(99, checkpoints.bound(2), "마켓 2 상한이 구멍 앞에 서지 않았다");
    }

    @Test
    void legacyNullMarketEventsPairedViaCommandJoin() throws SQLException {
        String url = createDatabase("legacy_null_market_events", "39");
        JdbcClient db = client(url);
        insertCommands(db, 1, 1, 1);
        insertLegacyEvents(db, 1, 1, 2);
        insertCommands(db, 2, 2, 2);
        // V36 전 크래시 창 명령을 V36 뒤에 재적재한 모양이다. 경계 이하인데 market_id 가 차 있다.
        insertMarketEvents(db, 2, 2, 2, 1);
        insertCommands(db, 1, 3, 3);
        insertLegacyEvents(db, 3, 3, 2);
        migrate(url, null);
        insertCommands(db, 1, 4, 4);
        insertMarketEvents(db, 1, 4, 4, 2);
        insertCommands(db, 2, 5, 5);
        insertMarketEvents(db, 2, 5, 5, 1);
        EventJournalReader events = new EventJournalReader(db);
        long legacyBound = events.materializedThroughByMarket(Map.of()).legacyBound();

        assertEquals(3, legacyBound, "레거시 경계가 서지 않아 레거시 패스가 판정되지 않는다");
        assertEquals(4, db.sql("SELECT count(*) FROM event_journal WHERE market_id IS NULL")
                .query(Long.class).single(), "market_id 없는 이벤트가 만들어지지 않았다");
        assertEquals(1, db.sql("SELECT count(*) FROM event_journal WHERE market_id IS NOT NULL"
                        + " AND source_cmd_seq <= ?").param(legacyBound).query(Long.class).single(),
                "경계 이하에 market_id 가 찬 이벤트가 없어 갈래 겹침이 판정되지 않는다");

        // 한 행씩 읽어 커서가 경계 명령의 ordinal 사이 (3, 0) 에 서는 자리까지 지나게 한다.
        List<String> market1 = readOneByOne(events, 1, legacyBound);
        assertTrue(market1.size() > 0, "판독 결과가 비었다");
        assertEquals(List.of("1:0", "1:1", "3:0", "3:1", "4:0", "4:1"), market1);
        assertEquals(market1, keys(events.readAfterForMarket(0, -1, 1, legacyBound, 100)),
                "한 배치로 읽은 순서가 한 행씩 읽은 순서와 다르다");
        assertEquals(List.of("2:0", "5:0"), readOneByOne(events, 2, legacyBound));
        assertEquals(List.of("2:0", "5:0"), keys(events.readAfterForMarket(0, -1, 2, legacyBound, 100)),
                "경계 이하 market_id 가 찬 행을 두 갈래가 겹쳐 읽었다");
        assertEquals(List.of("4:0", "4:1"), keys(events.readAfterForMarket(0, -1, 1, 100)),
                "레거시 패스 없는 판독에 market_id 없는 행이 섞였다. 위 결과가 조인에서 왔는지 알 수 없다");
    }

    private static List<String> readOneByOne(EventJournalReader events, int marketId, long legacyBound) {
        List<String> keys = new ArrayList<>();
        long cmdSeq = 0;
        int ordinal = -1;
        for (int i = 0; i < 100; i++) {
            List<EventJournalRow> page = events.readAfterForMarket(cmdSeq, ordinal, marketId, legacyBound, 1);
            if (page.isEmpty()) {
                return keys;
            }
            EventJournalRow row = page.get(0);
            keys.add(row.sourceCmdSeq() + ":" + row.eventOrdinal());
            cmdSeq = row.sourceCmdSeq();
            ordinal = row.eventOrdinal();
        }
        return fail("판독 커서가 전진하지 않는다: " + keys);
    }

    private static List<String> keys(List<EventJournalRow> rows) {
        return rows.stream().map(row -> row.sourceCmdSeq() + ":" + row.eventOrdinal()).toList();
    }

    /** 이름으로 데이터베이스를 만들고 target 판까지 올린 뒤 JDBC URL 을 돌려준다. null 이면 끝까지 올린다. */
    private static String createDatabase(String name, String target) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
        migrate(url, target);
        return url;
    }

    private static void migrate(String url, String target) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    private static JdbcClient client(String url) {
        return JdbcClient.create(
                new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static void insertCommands(JdbcClient db, int marketId, long fromSeq, long toSeq) {
        db.sql("""
                        INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                     assigned_order_id, engine_ts, tie_break, payload,
                                                     fencing_token, checksum)
                        SELECT s, ?, 'NEW', 1, 'bound-' || s, s, 0, s, '{}'::jsonb, 1, 0
                        FROM generate_series(?::bigint, ?::bigint) s
                        """)
                .params(marketId, fromSeq, toSeq)
                .update();
    }

    /** V36 뒤 모양의 이벤트. 명령마다 ordinal 0 부터 ordinals 개다. */
    private static void insertMarketEvents(JdbcClient db, int marketId, long fromSeq, long toSeq,
                                           int ordinals) {
        db.sql("""
                        INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type,
                                                   payload, payload_schema_version, fencing_epoch,
                                                   created_at, market_id, market_seq)
                        SELECT ?::bigint * 10000000 + s * 10 + o, s, o, 'ACCEPTED', '{}'::jsonb, 1, 1,
                               TIMESTAMPTZ '2026-07-01 00:00:00+00', ?, s * 10 + o
                        FROM generate_series(?::bigint, ?::bigint) s, generate_series(0, ? - 1) o
                        """)
                .params(marketId, marketId, fromSeq, toSeq, ordinals)
                .update();
    }

    /** V36 전 모양의 이벤트. market_id 와 market_seq 가 비어 있다. */
    private static void insertLegacyEvents(JdbcClient db, long fromSeq, long toSeq, int ordinals) {
        db.sql("""
                        INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type,
                                                   payload, payload_schema_version, fencing_epoch,
                                                   created_at)
                        SELECT s * 10 + o, s, o, 'ACCEPTED', '{}'::jsonb, 1, 1,
                               TIMESTAMPTZ '2026-07-01 00:00:00+00'
                        FROM generate_series(?::bigint, ?::bigint) s, generate_series(0, ? - 1) o
                        """)
                .params(fromSeq, toSeq, ordinals)
                .update();
    }

    /** 구멍 위 물질화를 만든다. 재생 대조를 통과할 필요가 없으므로 최소 행이면 된다. */
    private static void insertSyntheticEvent(long sourceCmdSeq) {
        jdbc.sql("INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type,"
                        + " payload, payload_schema_version, fencing_epoch, created_at, market_id,"
                        + " market_seq) VALUES (?, ?, 0, 'ACCEPTED', '{}'::jsonb, 1, 1, now(), 1, ?)")
                .params(10_000_000L + sourceCmdSeq, sourceCmdSeq, 9_000_000L + sourceCmdSeq)
                .update();
    }

    private static void materializeAsLeader() {
        EngineRecovery leaderRecovery =
                new EngineRecovery(replayService, leaderAppender::append, liveLeader);
        leaderRecovery.recover(new MatchingEngineImpl());
    }

    private static long appendCommand(String clientOrderId, long orderId) {
        return leaderCommands.append(new CommandDraft(1, CommandDraft.CommandType.NEW, 11L,
                clientOrderId, orderId, BASE_TS + orderId, orderId, PAYLOAD)).seq();
    }

    private static long countEvents(long sourceCmdSeq) {
        return countEvents(jdbc, sourceCmdSeq);
    }

    private static long countEvents(JdbcClient db, long sourceCmdSeq) {
        return db.sql("SELECT count(*) FROM event_journal WHERE source_cmd_seq = ?")
                .param(sourceCmdSeq)
                .query(Long.class)
                .single();
    }

    /** 마켓 marketId 에서 seq 가 (afterSeq, upToSeq] 이고 이벤트가 없는 명령 수. */
    private static long countEventless(JdbcClient db, int marketId, long afterSeq, long upToSeq) {
        return db.sql("""
                        SELECT count(*) FROM command_journal c
                        WHERE c.market_id = ? AND c.seq > ? AND c.seq <= ?
                          AND NOT EXISTS (SELECT 1 FROM event_journal e WHERE e.source_cmd_seq = c.seq)
                        """)
                .params(marketId, afterSeq, upToSeq)
                .query(Long.class)
                .single();
    }
}

package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 대역 채번 래치 전환 창(ADR 0022 7절 6단계). 12마켓 접수가 도는 중에 래치를 걸어도 마켓마다 래치 전 행이 모두 먼저
 * 커밋되고 대역 행이 뒤따르며 C28 이 0 인지 본다. 커밋 순서는 track_commit_timestamp 로 직접 읽는다.
 */
@Testcontainers
class CommandBandCutoverWindowTest {

    private static final int MARKETS = 12;
    private static final int WRITERS_PER_MARKET = 3;
    // 래치 앞뒤로 마켓마다 이만큼은 커밋돼야 순서 판정이 공허하지 않다.
    private static final int ROWS_PER_SIDE = 5;
    private static final long DEADLINE_SECONDS = 120;
    private static final long BASE_TS = 1_757_950_000_000L;
    private static final String PAYLOAD =
            "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"time_in_force\":\"GTC\","
                    + "\"price\":\"100\",\"qty\":\"1\",\"quote_order_qty\":null}";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange")
            .withCommand("postgres", "-c", "fsync=off", "-c", "track_commit_timestamp=on");

    private static final CommandPayloadMapper MAPPER = new CommandPayloadMapper(new ObjectMapper());
    private static final AtomicLong ORDER_IDS = new AtomicLong(1);

    @Test
    void latchUnderTwelveMarketLoadCommitsLegacyRowsBeforeBandRowsInEachMarket() throws Exception {
        try (Db db = Db.create("cutover_load")) {
            RecordingSink sink = new RecordingSink();
            EngineBridge bridge = new EngineBridge(db.appender, sink, MAPPER, AcceptGate.ALWAYS_OPEN,
                    EngineBridge.NEVER_HALTED);
            AtomicBoolean stop = new AtomicBoolean();
            ExecutorService pool = Executors.newFixedThreadPool(MARKETS * WRITERS_PER_MARKET);
            List<Future<?>> writers = new ArrayList<>();
            try {
                for (int market = 1; market <= MARKETS; market++) {
                    int marketId = market;
                    for (int writer = 0; writer < WRITERS_PER_MARKET; writer++) {
                        String prefix = "cut-m" + marketId + "-w" + writer + "-";
                        writers.add(pool.submit(() -> {
                            for (int i = 0; !stop.get(); i++) {
                                bridge.submit(newOrder(marketId, 2_000 + marketId, prefix + i));
                            }
                            return null;
                        }));
                    }
                }
                awaitEveryMarket(sink, seq -> seq < db.span, writers);
                db.latch();
                awaitEveryMarket(sink, seq -> seq >= db.span, writers);
            } finally {
                stop.set(true);
                pool.shutdown();
                assertTrue(pool.awaitTermination(DEADLINE_SECONDS, TimeUnit.SECONDS), "접수 부하가 멈추지 않았다");
            }
            for (Future<?> writer : writers) {
                writer.get();
            }

            for (int marketId = 1; marketId <= MARKETS; marketId++) {
                List<Long> offered = sink.snapshot(marketId);
                for (int i = 1; i < offered.size(); i++) {
                    // 대역 seq 는 모든 레거시 seq 보다 크므로 증가가 곧 '대역 행 뒤에 레거시 행 없음' 이다.
                    assertTrue(offered.get(i - 1) < offered.get(i),
                            "마켓 " + marketId + " 투입 순서가 seq 순서와 다르다: " + offered);
                }
            }
            assertEquals(MARKETS, db.count("""
                    SELECT count(*) FROM (
                        SELECT max(pg_xact_commit_timestamp(xmin)) FILTER (WHERE seq < %1$d) AS last_legacy,
                               min(pg_xact_commit_timestamp(xmin)) FILTER (WHERE seq >= %1$d) AS first_band
                        FROM command_journal
                        GROUP BY market_id
                    ) t
                    WHERE t.last_legacy < t.first_band
                    """.formatted(db.span)), "레거시 행의 마지막 커밋이 대역 행의 첫 커밋보다 앞서지 않은 마켓이 있다");
            for (InvariantCheck check : List.of(InvariantCheck.C05_CMD_GAPLESS,
                    InvariantCheck.C07P_DUP_ORDER_JOURNAL, InvariantCheck.C23_COUNTER_SEQ_CONSISTENCY,
                    InvariantCheck.C27_CMD_DEFAULT_PARTITION, InvariantCheck.C28_CMD_LEGACY_AFTER_CUTOVER)) {
                assertEquals(0, db.count(check.sql()), check.name());
            }
        }
    }

    /**
     * 래치가 커밋되기 전에 래치를 읽은 append 가 래치 커밋 뒤에 커밋되는 창. 그 레거시 행은 latched_at 보다 늦게
     * 접수됐지만 같은 마켓의 다음 append 가 대역을 받으므로 섞임이 아니다. C28 이 latched_at 을 읽지 않는 이유다.
     */
    @Test
    void appendThatReadTheLatchBeforeItCommittedStaysLegacyAndIsNotMixing() throws Exception {
        try (Db db = Db.create("cutover_window");
             Connection latcher = db.open();
             Connection writer = db.open()) {
            LeaderIdentity leader = db.lease.requireLeader();
            latcher.setAutoCommit(false);
            try (Statement st = latcher.createStatement();
                 ResultSet rs = st.executeQuery("SELECT latch_command_band_cutover()")) {
                assertTrue(rs.next());
            }

            writer.setAutoCommit(false);
            long inFlight;
            try (PreparedStatement ps = writer.prepareStatement(
                    "SELECT out_seq FROM append_command_prologue(?, ?, TRUE, ?, ?, 1::smallint)")) {
                ps.setString(1, leader.holder());
                ps.setLong(2, leader.epoch());
                ps.setLong(3, 3_001L);
                ps.setString(4, "window-a");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    inFlight = rs.getLong(1);
                }
            }
            assertTrue(inFlight < db.span, "커밋 전 래치가 보였다: seq=" + inFlight);
            latcher.commit();
            try (PreparedStatement ps = writer.prepareStatement("""
                    INSERT INTO command_journal
                      (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                       engine_ts, tie_break, payload, fencing_token, checksum)
                    VALUES (?, 1, 'NEW', 3001, 'window-a', ?, ?, 0, ?::jsonb, ?, 0)
                    """)) {
                long orderId = ORDER_IDS.getAndIncrement();
                ps.setLong(1, inFlight);
                ps.setLong(2, orderId);
                ps.setLong(3, BASE_TS + orderId);
                ps.setString(4, PAYLOAD);
                ps.setLong(5, leader.epoch());
                assertEquals(1, ps.executeUpdate());
            }
            writer.commit();

            long orderId = ORDER_IDS.getAndIncrement();
            long next = db.appender.append(new CommandDraft(1, CommandDraft.CommandType.NEW, 3_001L, "window-b",
                    orderId, BASE_TS + orderId, orderId, PAYLOAD)).seq();
            assertEquals(db.span + 1, next, "래치 커밋 뒤 같은 마켓의 다음 접수가 대역을 받지 않았다");

            assertEquals(1, db.count("SELECT count(*) FROM command_journal WHERE seq < " + db.span
                            + " AND created_at > (SELECT latched_at FROM journal_cutover)"),
                    "latched_at 보다 늦게 접수된 레거시 행이 없어 이 창을 밟지 않았다");
            assertEquals(0, db.count(InvariantCheck.C28_CMD_LEGACY_AFTER_CUTOVER.sql()));
            assertEquals(0, db.count(InvariantCheck.C05_CMD_GAPLESS.sql()));
        }
    }

    /** 마켓마다 조건을 만족하는 투입이 ROWS_PER_SIDE 건 이상이 될 때까지 기다린다. 라이터가 먼저 죽으면 그 예외를 올린다. */
    private static void awaitEveryMarket(RecordingSink sink, LongPredicate side, List<Future<?>> writers)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        while (!everyMarketHas(sink, side)) {
            for (Future<?> writer : writers) {
                if (writer.isDone()) {
                    writer.get();
                    fail("멈추라고 하지 않았는데 접수 라이터가 끝났다");
                }
            }
            if (System.nanoTime() > deadline) {
                fail("마켓마다 " + ROWS_PER_SIDE + "건이 " + DEADLINE_SECONDS + "초 안에 커밋되지 않았다");
            }
            Thread.sleep(10);
        }
    }

    private static boolean everyMarketHas(RecordingSink sink, LongPredicate side) {
        for (int marketId = 1; marketId <= MARKETS; marketId++) {
            if (sink.snapshot(marketId).stream().filter(side::test).count() < ROWS_PER_SIDE) {
                return false;
            }
        }
        return true;
    }

    private static OrderIntake newOrder(int marketId, long userId, String clientOrderId) {
        long orderId = ORDER_IDS.getAndIncrement();
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, userId, clientOrderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("100"), new BigDecimal("1"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes(StandardCharsets.UTF_8)), null, null);
    }

    /** 라이터 대신 마켓별 투입 순서를 기록한다. 투입은 마켓 접수 락 안에서 커밋 직후에 일어난다. */
    private static final class RecordingSink implements CommandSink {
        private final Map<Integer, List<Long>> offered = new ConcurrentHashMap<>();

        @Override
        public boolean offer(OrderCommand command) {
            List<Long> seqs = offered.computeIfAbsent(command.marketId(), m -> new ArrayList<>());
            synchronized (seqs) {
                seqs.add(command.seq());
            }
            return true;
        }

        @Override
        public boolean isRunning(int marketId) {
            return true;
        }

        List<Long> snapshot(int marketId) {
            List<Long> seqs = offered.get(marketId);
            if (seqs == null) {
                return List.of();
            }
            synchronized (seqs) {
                return List.copyOf(seqs);
            }
        }
    }

    /** 12마켓을 올린 데이터베이스 하나와 그 위의 리더·append 배선. */
    private static final class Db implements AutoCloseable {
        final String url;
        final HikariDataSource pool;
        final JdbcClient jdbc;
        final LeaderLeaseService lease;
        final CommandJournalAppender appender;
        final long span;

        private Db(String url) {
            this.url = url;
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(POSTGRES.getUsername());
            config.setPassword(POSTGRES.getPassword());
            config.setMaximumPoolSize(MARKETS + 4);
            config.setConnectionTimeout(5000);
            pool = new HikariDataSource(config);
            jdbc = JdbcClient.create(pool);
            lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(300), Duration.ofSeconds(1));
            assertTrue(lease.tryAcquire(), "시험 리더 승격 실패");
            appender = new CommandJournalAppender(jdbc, new TransactionTemplate(new JdbcTransactionManager(pool)),
                    lease);
            span = count("SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'");
        }

        static Db create(String name) throws SQLException {
            try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()); Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE " + name);
            }
            String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
            assertTrue(Flyway.configure()
                    .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate().success, "Flyway migration failed");
            try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("""
                        INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                            (1, 'KRW', 'Korean Won', 'FIAT', 0),
                            (2, 'BTC', 'Bitcoin', 'CRYPTO', 8)
                        """);
                for (int id = 1; id <= MARKETS; id++) {
                    st.execute("""
                            INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                                 tick_size, lot_size, min_notional, max_notional, max_qty)
                            VALUES (%d, 'CUT-%d', 2, 1, 1000, 0.0001, 5000, 100000000000, 10000)
                            """.formatted(id, id));
                }
                st.executeUpdate("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'");
            }
            return new Db(url);
        }

        Connection open() throws SQLException {
            return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        long count(String sql) {
            return jdbc.sql(sql).query(Long.class).single();
        }

        void latch() {
            assertTrue(jdbc.sql("SELECT latch_command_band_cutover() IS NOT NULL").query(Boolean.class).single());
        }

        @Override
        public void close() {
            lease.close();
            pool.close();
        }
    }
}

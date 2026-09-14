package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 마켓별 접수 락 아래 12마켓 동시 append(ADR 0022 4-4, 7절 5단계 선행 조건). EngineBridge,
 * CommandJournalAppender, append_command_prologue 로 이어지는 실제 append 경로를 돌리고, 라이터 자리에는
 * 마켓별 투입 순서를 기록하는 sink 를 꽂는다. 래치 전이라 채번은 전역 카운터 한 행이다.
 */
@Testcontainers
class MultiMarketConcurrentAppendTest {

    private static final int MARKETS = 12;
    private static final int WRITERS_PER_MARKET = 3;
    private static final int SUBMITS_PER_WRITER = 10;
    // 교착이면 이 안에 끝나지 않는다.
    private static final long LOAD_DEADLINE_SECONDS = 120;
    // 리스 갱신 한 번의 상한. 갱신 굶김은 TTL 단위의 초로 나타난다.
    private static final Duration RENEW_DEADLINE = Duration.ofSeconds(1);
    private static final int RENEW_TICKS = 20;
    private static final long TICK_MILLIS = 100;
    private static final long BASE_TS = 1_757_800_000_000L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final CommandPayloadMapper MAPPER = new CommandPayloadMapper(new ObjectMapper());
    private static final AtomicLong ORDER_IDS = new AtomicLong(1);

    static HikariDataSource appendPool;
    static HikariDataSource leasePool;
    static LeaderLeaseService lease;
    static CommandJournalAppender appender;

    @BeforeAll
    static void setUp() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // 운영 append 전용 풀과 같은 크기다.
        appendPool = pool("mm-append", 14, null);
        // 갱신이 잠금을 조금이라도 기다리면 55P03 으로 실패하게 한다. 경과 시간 상한은 멈춤 방지용이다.
        leasePool = pool("mm-lease", 2, "SET lock_timeout = '100ms'");
        lease = new LeaderLeaseService(JdbcClient.create(leasePool), Duration.ofSeconds(120),
                Duration.ofSeconds(1));
        appender = new CommandJournalAppender(JdbcClient.create(appendPool),
                new TransactionTemplate(new JdbcTransactionManager(appendPool)), lease);
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                    + "WHERE resource = 'matching-engine'");
        }
        assertTrue(lease.tryAcquire(), "시험 리더 승격 실패");
    }

    @AfterAll
    static void tearDown() {
        if (appendPool != null) {
            appendPool.close();
        }
        if (leasePool != null) {
            leasePool.close();
        }
    }

    @Test
    void twelveMarketsAppendWithGaplessSeqAndSeqOrderedOffersPerMarket() throws Exception {
        RecordingSink sink = new RecordingSink();
        EngineBridge bridge = bridge(sink);
        long before = queryLong("SELECT COALESCE(max(seq), 0) FROM command_journal");

        Load load = Load.start(bridge, "order", SUBMITS_PER_WRITER, new AtomicBoolean());
        load.await();

        int perMarket = WRITERS_PER_MARKET * SUBMITS_PER_WRITER;
        int total = MARKETS * perMarket;
        List<Long> seqs = new ArrayList<>(load.newSeqs);
        Collections.sort(seqs);
        assertEquals(total, seqs.size(), "신규 접수 응답 수가 제출 수와 다르다");
        assertEquals(total, seqs.stream().distinct().count(), "같은 seq 가 두 접수에 배정됐다");
        assertEquals(before + 1, seqs.get(0), "이 주행의 첫 seq 가 직전 최대 바로 다음이 아니다");
        assertEquals(before + total, seqs.get(total - 1), "이 주행의 seq 구간에 결번이 있다");
        assertEquals(total, queryLong("SELECT count(*) FROM command_journal WHERE seq > " + before));
        assertEquals(0, queryLong(InvariantCheck.C05_CMD_GAPLESS.sql()), "C05 결번 없음 위반");

        assertTrue(load.maxSameMarketInFlight.get() >= 2,
                "같은 마켓 접수가 겹치지 않아 투입 순서 판정이 공허하다");
        assertEquals(MARKETS, sink.offered.size(), "투입이 없는 마켓이 있다");
        for (Map.Entry<Integer, List<Long>> market : sink.offered.entrySet()) {
            int marketId = market.getKey();
            List<Long> offered = List.copyOf(market.getValue());
            assertEquals(perMarket, offered.size(), "마켓 " + marketId + " 투입 수");
            assertEquals(perMarket, bridge.submitCount(marketId), "마켓 " + marketId + " 접수 계측 수");
            for (int i = 1; i < offered.size(); i++) {
                assertTrue(offered.get(i - 1) < offered.get(i),
                        "마켓 " + marketId + " 투입 순서가 seq 순서와 다르다: " + offered);
            }
        }
    }

    @Test
    void sameClientOrderIdInTwoMarketsLeavesOneNewRowAndOneReplay() throws Exception {
        RecordingSink sink = new RecordingSink();
        EngineBridge bridge = bridge(sink);
        long userId = 7_001;
        String clientOrderId = "cross-market-1";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = open(); Connection observer = open()) {
            // 두 append 를 머리말의 어드바이저리 락 앞에 함께 세운다. 둘이 함께 줄을 서면 마켓별 접수 락이
            // 두 append 를 겹치게 두었다는 뜻이고, 그 뒤의 결과는 어드바이저리 락 하나가 직렬화한 것이다.
            blocker.setAutoCommit(false);
            try (PreparedStatement ps = blocker.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended('cmd-new:' || ?::text || ':' || ?, 0))")) {
                ps.setLong(1, userId);
                ps.setString(2, clientOrderId);
                ps.executeQuery().close();
            }
            Future<SubmitResult> market1 = pool.submit(() -> bridge.submit(newOrder(1, userId, clientOrderId)));
            Future<SubmitResult> market2 = pool.submit(() -> bridge.submit(newOrder(2, userId, clientOrderId)));
            awaitAdvisoryWaiters(observer, 2);
            blocker.commit();

            SubmitResult first = market1.get(10, TimeUnit.SECONDS);
            SubmitResult second = market2.get(10, TimeUnit.SECONDS);
            assertTrue(first.replayed() != second.replayed(),
                    "재전송 판정이 정확히 하나가 아니다: " + first + ", " + second);
            assertEquals(first.seq(), second.seq(), "두 접수가 서로 다른 명령을 가리킨다");
            assertEquals(1, queryLong("SELECT count(*) FROM command_journal WHERE command_type = 'NEW' "
                            + "AND user_id = " + userId + " AND client_order_id = '" + clientOrderId + "'"),
                    "같은 키의 NEW 행이 하나가 아니다");
            assertEquals(1, sink.offered.values().stream().mapToInt(List::size).sum(),
                    "재전송 명령이 라이터에 다시 투입됐다");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void leaseRenewDoesNotStarveWhileTwelveMarketsAppend() throws Exception {
        EngineBridge bridge = bridge(new RecordingSink());
        AtomicBoolean stop = new AtomicBoolean();
        Load load = Load.start(bridge, "renew", Integer.MAX_VALUE, stop);
        int ticksWithAppendInFlight = 0;
        int ticksWithLeaseRowLocked = 0;
        long slowestRenewNanos = 0;
        try (Connection observer = open()) {
            awaitCommits(load, MARKETS);
            for (int tick = 0; tick < RENEW_TICKS; tick++) {
                if (load.inFlight.get() > 0) {
                    ticksWithAppendInFlight++;
                }
                if (queryLong(observer, "SELECT count(*) FROM pg_locks WHERE relation = 'leader_lease'::regclass "
                        + "AND mode = 'RowShareLock' AND granted") > 0) {
                    ticksWithLeaseRowLocked++;
                }
                long started = System.nanoTime();
                assertTrue(lease.renew(), "append 중 리스 갱신이 거부됐다");
                slowestRenewNanos = Math.max(slowestRenewNanos, System.nanoTime() - started);
                Thread.sleep(TICK_MILLIS);
            }
        } finally {
            stop.set(true);
            load.await();
        }
        assertEquals(RENEW_TICKS, ticksWithAppendInFlight, "append 가 돌지 않은 틱이 있어 갱신 판정이 공허하다");
        assertTrue(ticksWithLeaseRowLocked > 0, "append 가 리스 행 잠금을 쥔 틱을 한 번도 보지 못했다");
        assertTrue(slowestRenewNanos < RENEW_DEADLINE.toNanos(),
                "append 중 리스 갱신 한 번이 " + TimeUnit.NANOSECONDS.toMillis(slowestRenewNanos) + "ms 걸렸다");
    }

    /** 마켓마다 라이터 여럿이 같은 마켓에 동시에 접수한다. 같은 마켓의 겹침이 있어야 투입 순서 판정이 선다. */
    private static final class Load {
        private final ExecutorService pool = Executors.newFixedThreadPool(MARKETS * WRITERS_PER_MARKET);
        private final List<Future<?>> writers = new ArrayList<>();
        private final ConcurrentLinkedQueue<Long> newSeqs = new ConcurrentLinkedQueue<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxSameMarketInFlight = new AtomicInteger();

        static Load start(EngineBridge bridge, String tag, int submitsPerWriter, AtomicBoolean stop) {
            Load load = new Load();
            CountDownLatch go = new CountDownLatch(1);
            for (int market = 1; market <= MARKETS; market++) {
                int marketId = market;
                AtomicInteger marketInFlight = new AtomicInteger();
                for (int writer = 0; writer < WRITERS_PER_MARKET; writer++) {
                    String prefix = tag + "-m" + marketId + "-w" + writer + "-";
                    load.writers.add(load.pool.submit(() -> {
                        go.await();
                        for (int i = 0; i < submitsPerWriter && !stop.get(); i++) {
                            load.maxSameMarketInFlight.accumulateAndGet(marketInFlight.incrementAndGet(), Math::max);
                            load.inFlight.incrementAndGet();
                            try {
                                SubmitResult result = bridge.submit(newOrder(marketId, 1_000 + marketId, prefix + i));
                                assertFalse(result.replayed(), "키가 모두 다른데 재전송으로 판정했다");
                                load.newSeqs.add(result.seq());
                            } finally {
                                load.inFlight.decrementAndGet();
                                marketInFlight.decrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
            }
            go.countDown();
            return load;
        }

        /** 교착이면 시간 안에 끝나지 않는다. 라이터가 던진 예외는 그대로 올린다. */
        void await() throws Exception {
            pool.shutdown();
            if (!pool.awaitTermination(LOAD_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                fail("12마켓 동시 append 가 " + LOAD_DEADLINE_SECONDS + "초 안에 끝나지 않았다(교착 의심)");
            }
            for (Future<?> writer : writers) {
                writer.get();
            }
        }
    }

    /** 라이터 대신 마켓별 투입 순서를 기록한다. */
    private static final class RecordingSink implements CommandSink {
        private final Map<Integer, List<Long>> offered = new ConcurrentHashMap<>();

        @Override
        public boolean offer(OrderCommand command) {
            offered.computeIfAbsent(command.marketId(), m -> Collections.synchronizedList(new ArrayList<>()))
                    .add(command.seq());
            return true;
        }

        @Override
        public boolean isRunning(int marketId) {
            return true;
        }
    }

    private static EngineBridge bridge(CommandSink sink) {
        return new EngineBridge(appender, sink, MAPPER, AcceptGate.ALWAYS_OPEN, EngineBridge.NEVER_HALTED);
    }

    private static OrderIntake newOrder(int marketId, long userId, String clientOrderId) {
        long orderId = ORDER_IDS.getAndIncrement();
        return new OrderIntake(marketId, CommandDraft.CommandType.NEW, userId, clientOrderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("100"), new BigDecimal("1"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes(StandardCharsets.UTF_8)), null, null);
    }

    private static void awaitCommits(Load load, int atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (load.newSeqs.size() < atLeast) {
            if (System.nanoTime() > deadline) {
                fail("append 부하가 시작되지 않았다: 커밋 " + load.newSeqs.size());
            }
            Thread.sleep(10);
        }
    }

    private static void awaitAdvisoryWaiters(Connection observer, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = queryLong(observer,
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted");
            if (waiting == expected) {
                return;
            }
            Thread.sleep(20);
        }
        fail("어드바이저리 락 앞에서 기다리는 append 가 " + expected + " 개가 아니다: " + waiting);
    }

    private static HikariDataSource pool(String name, int size, String initSql) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setPoolName(name);
        config.setMaximumPoolSize(size);
        config.setConnectionTimeout(2000);
        if (initSql != null) {
            config.setConnectionInitSql(initSql);
        }
        return new HikariDataSource(config);
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection c = open()) {
            return queryLong(c, sql);
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getLong(1);
        }
    }
}

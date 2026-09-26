package kr.exchange.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.NotLeaderException;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.harness.InvariantCheck;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-1 (b)~(e) + 배선: 동시 채번 gapless(T6-E 축), client_order_id 멱등, fencing 거부(T4-2 축),
 * catch-up 순서 조회, 저널 ACK 후 단일 라이터 공급. 저널과 리스가 전역 공유 자원이라 순서를 고정한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommandJournalIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static LeaderLeaseService lease;
    static CommandJournalAppender appender;
    static CommandJournalReader reader;

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
        config.setMaximumPoolSize(12);
        dataSource = new HikariDataSource(config);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        appender = new CommandJournalAppender(jdbc, tx, lease);
        reader = new CommandJournalReader(jdbc);
        takeOver(lease);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void concurrentAppendsWithRollbacksLeaveNoPermanentGap() throws Exception {
        int appendThreads = 6;
        int appendsPerThread = 20;
        int rollbackThreads = 3;
        int rollbacksPerThread = 10;

        ExecutorService pool = Executors.newFixedThreadPool(appendThreads + rollbackThreads);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < appendThreads; t++) {
            final int worker = t;
            tasks.add(() -> {
                for (int i = 0; i < appendsPerThread; i++) {
                    appender.append(newDraft("gap-w" + worker + "-" + i, 1000 + worker));
                }
                return null;
            });
        }
        for (int t = 0; t < rollbackThreads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < rollbacksPerThread; i++) {
                    allocateThenRollback();
                }
                return null;
            });
        }
        List<Future<Void>> results = pool.invokeAll(tasks);
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "동시 작업 미종료");
        for (Future<Void> f : results) {
            f.get();
        }

        long count = queryLong("SELECT count(*) FROM command_journal");
        assertEquals((long) appendThreads * appendsPerThread, count, "커밋된 append 수 불일치");
        assertEquals(0, queryLong(InvariantCheck.C05_CMD_GAPLESS.sql()), "커밋된 seq에 영구 결번 존재");
        // 카운터 정합(§6.3 ⑰): 롤백 채번이 회수되지 않았다면 next_value가 max+1을 초과한다.
        assertEquals(queryLong("SELECT max(seq) + 1 FROM command_journal"),
                queryLong("SELECT next_value FROM journal_counters WHERE name = 'command_seq'"),
                "롤백 채번이 카운터에서 회수되지 않았다");
    }

    @Test
    @Order(2)
    void duplicateNewCommandReturnsExistingWithoutInsert() throws Exception {
        CommandDraft draft = newDraft("idem-1", 42);
        AppendResult origin = appender.append(draft);
        assertFalse(origin.replayed());

        AppendResult replay = appender.append(draft);
        assertTrue(replay.replayed(), "재전송은 replay로 판정돼야 한다");
        assertEquals(origin.seq(), replay.seq());
        assertEquals(origin.assignedOrderId(), replay.assignedOrderId());
        assertEquals(1, queryLong(
                "SELECT count(*) FROM command_journal WHERE client_order_id = 'idem-1'"));

        // 동시 재전송: 어드바이저리 락 직렬화로 삽입은 정확히 1건이어야 한다.
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<AppendResult>> futures = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            futures.add(pool.submit(() -> appender.append(newDraft("idem-race", 43))));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        long inserts = 0;
        Long seq = null;
        for (Future<AppendResult> f : futures) {
            AppendResult r = f.get();
            if (!r.replayed()) {
                inserts++;
            }
            if (seq == null) {
                seq = r.seq();
            }
            assertEquals(seq, r.seq(), "동시 재전송이 서로 다른 seq를 받았다");
        }
        assertEquals(1, inserts, "동일 키 동시 append의 신규 삽입은 1건이어야 한다");
        assertEquals(1, queryLong(
                "SELECT count(*) FROM command_journal WHERE client_order_id = 'idem-race'"));
    }

    @Test
    @Order(3)
    void staleLeaderAppendRejectedWithZeroCommit() {
        long staleEpoch = lease.requireLeader().epoch();
        String staleHolder = lease.holder();

        // 탈취: 만료 후 신 리더 승격. 구 lease 객체는 옛 epoch를 계속 들고 있다.
        LeaderLeaseService successor =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        takeOver(successor);
        assertEquals(staleEpoch + 1, successor.requireLeader().epoch());

        long before = queryLong("SELECT count(*) FROM command_journal");
        assertThrows(DataAccessException.class,
                () -> appender.append(newDraft("stale-append", 77)),
                "구 epoch append는 assert_current_leader가 거부해야 한다");
        // fencing 확정 거부는 renew 틱을 기다리지 않고 즉시 강등한다(§5.4).
        assertFalse(lease.isLeader(), "fencing 거부 후에도 강등되지 않았다");
        assertEquals(before, queryLong("SELECT count(*) FROM command_journal"), "stale append 커밋 발생");
        assertEquals(0, queryLong(
                "SELECT count(*) FROM command_journal WHERE client_order_id = 'stale-append'"));

        // 운영 append SQL 자체 계약: 구 epoch·구 holder 참조는 0행이다(§5.4). 함수가 넣은 행 수를 돌려준다(V48).
        int rows = jdbc.sql(CommandJournalAppender.INSERT_SQL)
                .params(999_999_999L, 1, "NEW", 1L, "stale-direct", 1L, 0L, 0L, "{}", 0L,
                        staleHolder, staleEpoch)
                .query(Integer.class)
                .single();
        assertEquals(0, rows, "구 epoch fencing INSERT가 0행이 아니다");

        // holder·epoch 가 맞아도 만료된 리스면 0행이다. 만료 판정은 문장 평가 시점이다.
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second'"
                + " WHERE resource = 'matching-engine'").update();
        int expiredRows = jdbc.sql(CommandJournalAppender.INSERT_SQL)
                .params(999_999_998L, 1, "NEW", 1L, "expired-direct", 1L, 0L, 0L, "{}", 0L,
                        successor.holder(), successor.requireLeader().epoch())
                .query(Integer.class)
                .single();
        assertEquals(0, expiredRows, "만료 리스의 fencing INSERT가 0행이 아니다");
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() + interval '120 seconds'"
                + " WHERE resource = 'matching-engine'").update();

        // 리더가 아닌 인스턴스는 DB 왕복 전에 거부된다.
        LeaderLeaseService neverLeader =
                new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        CommandJournalAppender rejected = new CommandJournalAppender(jdbc, tx, neverLeader);
        assertThrows(NotLeaderException.class, () -> rejected.append(newDraft("no-leader", 78)));

        // 신 리더 경로는 살아 있고, 실패 append가 결번을 남기지 않았다.
        CommandJournalAppender active = new CommandJournalAppender(jdbc, tx, successor);
        AppendResult ok = active.append(newDraft("post-takeover", 79));
        assertEquals(successor.requireLeader().epoch(), ok.fencingToken());
        assertTrue(queryLong("SELECT count(*) FROM command_journal") > 0, "command_journal 행 실재");
        assertEquals(0, queryLong(InvariantCheck.C05_CMD_GAPLESS.sql()), "실패 append 가 결번을 남김");

        // 강등 인스턴스는 재구성(rearm) 전에 리스를 다시 얻지 않는다(DB 설계 §5.4-c 5). 이 시험은 재구성하지 않으므로
        // 재기동을 흉내 내 새 인스턴스가 리더십을 잡고 이어간다.
        lease = new LeaderLeaseService(jdbc, Duration.ofSeconds(120), Duration.ofSeconds(1));
        appender = new CommandJournalAppender(jdbc, tx, lease);
        takeOver(lease);
    }

    @Test
    @Order(4)
    void readerReturnsCommandsAfterSeqInOrder() {
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            seqs.add(appender.append(newDraft("catchup-" + i, 200 + i)).seq());
        }
        // CANCEL도 같은 저널을 지나며 client_order_id는 null 허용이다.
        CommandDraft cancel = new CommandDraft(1, CommandDraft.CommandType.CANCEL,
                9, null, 204, 1721600000000L, 6, "{\"order_id\":204}");
        seqs.add(appender.append(cancel).seq());

        List<JournalEntry> entries = reader.readAfter(seqs.get(1), 100);
        assertEquals(seqs.subList(2, seqs.size()),
                entries.stream().map(JournalEntry::seq).toList(), "seq 초과분 순서 불일치");

        JournalEntry firstEntry = entries.get(0);
        assertEquals("catchup-2", firstEntry.clientOrderId());
        assertEquals(CommandDraft.CommandType.NEW, firstEntry.type());
        assertEquals(202, firstEntry.assignedOrderId());
        assertNotNull(firstEntry.createdAt());
        // 저장된 checksum이 동일 canonical 계산과 일치해야 재생 손상 판별이 성립한다.
        CommandDraft roundTrip = new CommandDraft(firstEntry.marketId(), firstEntry.type(),
                firstEntry.userId(), firstEntry.clientOrderId(), firstEntry.assignedOrderId(),
                firstEntry.engineTs(), firstEntry.tieBreak(), firstEntry.payloadJson());
        assertEquals(JournalChecksum.of(firstEntry.seq(), roundTrip), firstEntry.checksum());

        JournalEntry last = entries.get(entries.size() - 1);
        assertEquals(CommandDraft.CommandType.CANCEL, last.type());
        assertNull(last.clientOrderId());

        // limit 단위 페이지네이션도 순서를 보존한다.
        List<JournalEntry> page = reader.readAfter(seqs.get(1), 2);
        assertEquals(seqs.subList(2, 4), page.stream().map(JournalEntry::seq).toList());
    }

    // append ACK 후에만 단일 라이터 큐로 공급하고, replay는 큐에 재투입하지 않는다.

    @Test
    @Order(5)
    void bridgeFeedsWriterLoopAfterJournalAck() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MatchingEngineImpl engine = new MatchingEngineImpl();
        // 앞 테스트들이 쌓은 저널 이력 탓에 첫 명령은 seq 갭이다. catch-up 경로가 메운다.
        SingleWriterLoop loop = new SingleWriterLoop(1024, engine, events -> { },
                new JournalCatchUpSource(reader, new CommandPayloadMapper(objectMapper)),
                t -> { });
        EngineBridge bridge = new EngineBridge(appender, loop, new CommandPayloadMapper(objectMapper));
        loop.start();
        try {
            OrderIntake intake = new OrderIntake(1, CommandDraft.CommandType.NEW, 11,
                    "bridge-1", 501, 1721600000000L, 7,
                    OrderCommand.Side.BUY, OrderCommand.OrdType.LIMIT,
                    OrderCommand.TimeInForce.GTC,
                    new BigDecimal("50000000"), new BigDecimal("0.01"), null,
                    java.util.UUID.nameUUIDFromBytes("bridge-1".getBytes()), null, null);

            SubmitResult ack = bridge.submit(intake);
            assertFalse(ack.replayed());
            assertTrue(ack.queued(), "저널 ACK 후 큐 공급 실패");
            assertEquals(lease.requireLeader().epoch(), ack.epoch());
            assertEquals(1, queryLong(
                    "SELECT count(*) FROM command_journal WHERE client_order_id = 'bridge-1'"));
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .until(() -> engine.lastAppliedSeq() == ack.seq());

            SubmitResult replay = bridge.submit(intake);
            assertTrue(replay.replayed());
            assertFalse(replay.queued(), "replay는 재투입하지 않는다");
            assertEquals(ack.seq(), replay.seq());
            assertEquals(ack.seq(), engine.lastAppliedSeq(), "replay가 엔진에 이중 적용됐다");
        } finally {
            loop.stop();
        }
    }

    private static CommandDraft newDraft(String clientOrderId, long assignedOrderId) {
        return new CommandDraft(1, CommandDraft.CommandType.NEW, 7, clientOrderId,
                assignedOrderId, 1721600000000L, 5,
                "{\"side\":\"BUY\",\"ord_type\":\"LIMIT\",\"price\":\"50000000\",\"qty\":\"0.01\"}");
    }

    /** 만료 유도 후 즉시 승격. 테스트 전용 리더 교체 경로. */
    private static void takeOver(LeaderLeaseService svc) {
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE resource = 'matching-engine'")
                .update();
        assertTrue(svc.tryAcquire(), "테스트 리더 승격 실패");
    }

    /** 채번만 하고 롤백하는 워커. 커밋 seq 연속성이 유지되는지 보는 (b)의 교란 입력이다. */
    private static void allocateThenRollback() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT next_journal_value('command_seq')");
                Thread.sleep(2);
            } finally {
                conn.rollback();
            }
        }
    }

    private static long queryLong(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

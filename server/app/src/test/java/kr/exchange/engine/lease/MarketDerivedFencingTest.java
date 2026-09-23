package kr.exchange.engine.lease;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.NodeServiceHealth;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SnapshotCodec;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalAppender;
import kr.exchange.journal.FencingErrors;
import kr.exchange.journal.JournalEntry;
import kr.exchange.journal.TransientDbErrors;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import kr.exchange.snapshot.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static kr.exchange.engine.lease.EpochAllocationTest.acquire;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryLong;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 쓰기 권한 검사를 기록할 행의 마켓에서 도출한다(S2 설계 4-3, DB 설계 §5.4-b). P1 에서는 모든 마켓이 matching-engine 이라
 * 오늘과 같고, P3 을 흉내 낸 별도 DB 에서는 다른 파티션 리스로 쓰는 append 가 0행이고 대응 없는 마켓 쓰기는 55000 이다.
 * P3 판은 SQL 함수와 함께 실제 자바 쓰기 경로 네 곳(명령 append, 정산, 이벤트 append, 스냅샷)을 돌린다.
 */
class MarketDerivedFencingTest {

    private static final BigDecimal RATE = new BigDecimal("0.0005");
    private static final long BASE_TS = 1_758_000_000_000L;

    @Test
    void p1DerivesMatchingEngineForEveryMarketAndWritesWithoutMarketRow() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("mdf_p1");
        db.seedMarkets(1, 2);
        db.seedMarketWithoutTriggers(20);
        try (Connection c = db.owner()) {
            for (int market : new int[] {1, 2, 20, 77}) {
                assertEquals("matching-engine", queryString(c, "SELECT lease_resource_for_market(" + market + "::smallint)"),
                        "마켓 " + market);
            }
            long epoch = acquire(c, "matching-engine", "node-a");

            c.setAutoCommit(false);
            long seq = prologue(c, "node-a", epoch, "m77", 77);
            assertEquals(1, append(c, seq, 77, "node-a", epoch), "markets 에 없는 마켓의 append 가 거부됐다");
            c.commit();
            assertEquals(0, append(c, seq + 100_000, 1, "node-a", epoch - 1), "오래된 epoch append 가 들어갔다");
            c.rollback();
            db.expire("matching-engine");
            assertEquals(0, append(c, seq + 100_001, 1, "node-a", epoch), "만료된 리스로 append 가 들어갔다");
            c.rollback();
            c.setAutoCommit(true);

            assertEquals("77|" + epoch, queryString(c, "SELECT market_id || '|' || fencing_token FROM command_journal"));
        }
    }

    /** 정산 머리말 뒤 마켓 검사. 명령 행 마켓과 배치 마켓이 다르거나 배치가 한 마켓이 아니면 계약 위반이다. */
    @Test
    void settlementRejectsBatchMarketThatDiffersFromCommandInP1() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("mdf_settle");
        try (HikariDataSource ds = db.ownerPool()) {
            JdbcClient jdbc = JdbcClient.create(ds);
            TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(ds));
            AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
            bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
            bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
            bootstrap.asset(3, "ETH", "Ether", "CRYPTO", 8);
            bootstrap.market(1, "KRW-BTC", 2, 1, RATE, RATE);
            bootstrap.market(2, "KRW-ETH", 3, 1, RATE, RATE);
            long bob = bootstrap.user("bob@mdf.local");
            for (int asset : new int[] {1, 2, 3}) {
                bootstrap.userAccounts(bob, asset);
            }
            bootstrap.deposit(bob, 2, new BigDecimal("10"));

            LeaderLeaseService lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(5), Duration.ofSeconds(1));
            db.expire("matching-engine");
            assertTrue(lease.tryAcquire());
            CommandPayloadMapper mapper = new CommandPayloadMapper(new ObjectMapper());
            CommandJournalAppender appender = new CommandJournalAppender(jdbc, tx, lease);
            SettlementService settlement = new SettlementService(jdbc, tx, lease, new ObjectMapper(),
                    new MarketCatalog(jdbc), mapper);

            OrderIntake intake = sell(1, bob, 81_001);
            AppendResult ack = appender.append(new CommandDraft(intake.marketId(), intake.type(), intake.userId(),
                    intake.clientOrderId(), intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                    mapper.toPayloadJson(intake)));
            JournalEntry entry = new CommandJournalReader(jdbc).readAfter(ack.seq() - 1, 1).get(0);
            List<EngineEvent> events = new MatchingEngineImpl().apply(mapper.toCommand(entry));
            assertEquals(1, events.size());

            IllegalStateException otherMarket = assertThrows(IllegalStateException.class,
                    () -> settlement.appendAndSettle(List.of(withMarket(events.get(0), 2, 0))));
            assertTrue(otherMarket.getMessage().contains("마켓"), otherMarket.getMessage());
            IllegalStateException mixed = assertThrows(IllegalStateException.class,
                    () -> settlement.appendAndSettle(List.of(events.get(0), withMarket(events.get(0), 2, 1))));
            assertTrue(mixed.getMessage().contains("마켓"), mixed.getMessage());
            assertEquals(0, jdbc.sql("SELECT count(*) FROM orders").query(Long.class).single());
            assertTrue(lease.isLeader(), "계약 위반 거부가 리스 판정으로 분류됐다");

            settlement.appendAndSettle(events);
            assertEquals(1, jdbc.sql("SELECT count(*) FROM orders WHERE id = 81001").query(Long.class).single());
            lease.close();
        }
    }

    @Test
    void p3FencesAnotherPartitionAndRefusesUnmappedMarket() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("mdf_p3");
        db.seedMarkets(1, 2);
        db.seedMarketWithoutTriggers(20);
        db.setProtocol(3);
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(db.url(), PartitionLeaseDb.OWNER,
                PartitionLeaseDb.OWNER));
        try (Connection c = db.owner()) {
            long p0 = acquire(c, "matching-p0", "node-p0");
            long p1 = acquire(c, "matching-p1", "node-p1");
            assertEquals("matching-p0|matching-p1", queryString(c,
                    "SELECT lease_resource_for_market(1::smallint) || '|' || lease_resource_for_market(2::smallint)"));

            c.setAutoCommit(false);
            assertEquals(0, append(c, 900_001, 2, "node-p0", p0), "p0 리스로 p1 마켓 행이 들어갔다");
            SQLException crossPrologue = assertThrows(SQLException.class, () -> prologue(c, "node-p0", p0, "x2", 2));
            assertEquals("P0001", crossPrologue.getSQLState(), crossPrologue.getMessage());
            assertTrue(firstLine(crossPrologue).contains("fencing 위반")
                    && firstLine(crossPrologue).endsWith(" resource=matching-p1"), crossPrologue.getMessage());
            c.rollback();

            long own = prologue(c, "node-p0", p0, "x1", 1);
            assertEquals(1, append(c, own, 1, "node-p0", p0));
            long other = prologue(c, "node-p1", p1, "y2", 2);
            assertEquals(1, append(c, other, 2, "node-p1", p1));
            c.commit();
            c.setAutoCommit(true);
            assertEquals("1:" + p0 + ",2:" + p1, queryString(c, """
                    SELECT string_agg(market_id || ':' || fencing_token, ',' ORDER BY market_id) FROM command_journal
                    """));
        }

        DataAccessException crossLock = assertThrows(DataAccessException.class, () -> jdbc
                .sql("SELECT assert_and_lock_market_leader(2::smallint, ?, ?)")
                .params("node-p0", queryEpoch(db, "matching-p0"))
                .query(Long.class).single());
        assertTrue(FencingErrors.isFencingRejection(crossLock), FencingErrors.rejectionDetail(crossLock));
        assertTrue(FencingErrors.rejectionDetail(crossLock).endsWith(" resource=matching-p1"),
                FencingErrors.rejectionDetail(crossLock));

        // 대응 없는 마켓은 설정 결함이다. 강등 분류 문구를 쓰지 않는 55000 으로 거부한다.
        long p0 = queryEpoch(db, "matching-p0");
        for (String sql : List.of(
                "SELECT lease_resource_for_market(20::smallint)",
                "SELECT assert_and_lock_market_leader(20::smallint, 'node-p0', " + p0 + ")",
                "SELECT out_seq FROM append_command_prologue('node-p0', " + p0 + ", true, 1, 'z20', 20::smallint)",
                "SELECT append_command_journal_row(900002, 20::smallint, 'NEW', 1, 'z20', 9002, 0, 0, '{}'::jsonb, 0, "
                        + "'node-p0', " + p0 + ")")) {
            DataAccessException refused = assertThrows(DataAccessException.class,
                    () -> jdbc.sql(sql).query(String.class).single(), sql);
            assertEquals("55000", TransientDbErrors.sqlState(refused), sql);
            assertFalse(FencingErrors.isFencingRejection(refused), "대응 누락이 강등 사유로 분류됐다: " + sql);
        }
        assertEquals(2, db.ownerLong("SELECT count(*) FROM command_journal"));
    }

    /** p0 리더의 명령 append 는 p0 마켓만 쓴다. p1 마켓은 머리말이 p1 리스로 거부하고 대응 없는 마켓은 55000 이다. */
    @Test
    void p3CommandAppenderJudgesTheLeaseOfTheDraftMarket() throws Exception {
        try (P3Writers w = new P3Writers("mdf_java_append")) {
            assertEquals(w.p0.leader.epoch(), w.append(w.p0, 1, 84_001).fencingToken());
            assertEquals(w.p1.leader.epoch(), w.append(w.p1, 2, 84_002).fencingToken());

            // 머리말 판정에서 거부된다. append 함수의 0행(StaleLeaderException)까지 가지 않는다.
            assertFencedBy(assertThrows(DataAccessException.class, () -> w.append(w.p0, 2, 84_003)), "matching-p1");
            DataAccessException unmapped = assertThrows(DataAccessException.class, () -> w.append(w.p0, 77, 84_004));
            assertEquals("55000", TransientDbErrors.sqlState(unmapped), FencingErrors.rejectionDetail(unmapped));
            assertFalse(FencingErrors.isFencingRejection(unmapped), "대응 누락이 강등 사유로 분류됐다");

            assertEquals(w.byMarket(1, 2), w.rows("command_journal", "fencing_token"));
            w.assertBothStillLead();
        }
    }

    /** 정산은 명령 행 마켓의 리스를 잠근다. 명령 행이 없을 때만 배치 마켓을 쓰고, 리스 거부가 마켓 검사보다 먼저다. */
    @Test
    void p3SettlementLocksTheLeaseOfTheCommandRowMarket() throws Exception {
        try (P3Writers w = new P3Writers("mdf_java_settle")) {
            List<EngineEvent> own = w.journaled(w.p0, 1, 85_001);
            List<EngineEvent> other = w.journaled(w.p1, 2, 85_002);
            List<EngineEvent> relabeled = other.stream().map(e -> withMarket(e, 1, e.eventOrdinal())).toList();
            List<EngineEvent> absent = other.stream().map(e -> withSource(e, e.sourceCmdSeq() + 1_000_000)).toList();

            assertFencedBy(assertThrows(DataAccessException.class, () -> w.p0.settlement.appendAndSettle(other)),
                    "matching-p1");
            assertFencedBy(assertThrows(DataAccessException.class, () -> w.p0.settlement.appendAndSettle(relabeled)),
                    "matching-p1");
            assertFencedBy(assertThrows(DataAccessException.class, () -> w.p0.settlement.appendAndSettle(absent)),
                    "matching-p1");
            IllegalStateException missing = assertThrows(IllegalStateException.class,
                    () -> w.p1.settlement.appendAndSettle(absent));
            assertTrue(missing.getMessage().contains("저널에 없다"), missing.getMessage());
            assertEquals(0, w.count("SELECT count(*) FROM event_journal") + w.count("SELECT count(*) FROM orders"));

            w.p0.settlement.appendAndSettle(own);
            w.p1.settlement.appendAndSettle(other);
            assertEquals(w.byMarket(1, 2), w.rows("event_journal", "fencing_epoch"));
            assertEquals(2, w.count("SELECT count(*) FROM orders WHERE id IN (85001, 85002)"));
            w.assertBothStillLead();
        }
    }

    @Test
    void p3EventAppenderLocksTheLeaseOfTheBatchMarket() throws Exception {
        try (P3Writers w = new P3Writers("mdf_java_event")) {
            List<EngineEvent> own = w.journaled(w.p0, 1, 86_001);
            List<EngineEvent> other = w.journaled(w.p1, 2, 86_002);

            assertFencedBy(assertThrows(DataAccessException.class, () -> w.p0.events.append(other)), "matching-p1");
            assertEquals(0, w.count("SELECT count(*) FROM event_journal"));

            w.p0.events.append(own);
            w.p1.events.append(other);
            assertEquals(w.byMarket(1, 2), w.rows("event_journal", "fencing_epoch"));
            w.assertBothStillLead();
        }
    }

    /**
     * 스냅샷은 마켓마다 그 마켓의 리스를 잠근다. 판정이 리더의 epoch 와 리스 행이 같을 때만 통과하고 epoch 는 모든 리스를 통틀어
     * 유일하므로, 한 리더의 호출이 두 파티션을 넘으면 다른 파티션에서 거부되고 한 행도 남지 않는다.
     */
    @Test
    void p3SnapshotStoreLocksEachMarketLeaseAndRefusesAnotherPartition() throws Exception {
        try (P3Writers w = new P3Writers("mdf_java_snapshot")) {
            w.p0.snapshots.persist(w.p0.leader, List.of(emptyBook(1)));
            w.p1.snapshots.persist(w.p1.leader, List.of(emptyBook(2)));
            assertEquals(w.byMarket(1, 2), w.rows("snapshots", "fencing_epoch"));

            assertFencedBy(assertThrows(DataAccessException.class,
                    () -> w.p0.snapshots.persist(w.p0.leader, List.of(emptyBook(1), emptyBook(2)))), "matching-p1");
            assertFencedBy(assertThrows(DataAccessException.class,
                    () -> w.p1.snapshots.persist(w.p1.leader, List.of(emptyBook(1)))), "matching-p0");
            assertEquals(2, w.count("SELECT count(*) FROM snapshots"));
        }
    }

    /** 강등 분류 문구의 거부이고, 거부한 리스 자원이 접미사로 붙는다. */
    private static void assertFencedBy(DataAccessException e, String resource) {
        assertTrue(FencingErrors.isFencingRejection(e), FencingErrors.rejectionDetail(e));
        assertTrue(FencingErrors.rejectionDetail(e).endsWith(" resource=" + resource), FencingErrors.rejectionDetail(e));
    }

    private static SnapshotStore.MarketState emptyBook(int market) {
        OrderBookImpl book = new OrderBookImpl(market);
        return new SnapshotStore.MarketState(market, 1, SnapshotCodec.encode(book), book.stateChecksum());
    }

    /**
     * P3 을 흉내 낸 새 DB 에서 p0·p1 리스를 따로 쥔 두 리더와 각 리더의 실제 쓰기 경로. 마켓 1 은 p0, 2 는 p1 이다(결정한 값 3).
     * P1 에서는 어느 마켓을 넘겨도 같은 리스 행이라, 자바가 판정에 넘기는 마켓이 틀려도 P3 에서만 드러난다.
     */
    private static final class P3Writers implements AutoCloseable {
        final PartitionLeaseDb db;
        final HikariDataSource pool;
        final JdbcClient jdbc;
        final CommandPayloadMapper mapper = new CommandPayloadMapper(new ObjectMapper());
        final long bob;
        final Writer p0;
        final Writer p1;

        P3Writers(String prefix) throws SQLException {
            db = PartitionLeaseDb.create(prefix);
            pool = db.ownerPool();
            jdbc = JdbcClient.create(pool);
            TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(pool));
            AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
            bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
            bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
            bootstrap.asset(3, "ETH", "Ether", "CRYPTO", 8);
            bootstrap.market(1, "KRW-BTC", 2, 1, RATE, RATE);
            bootstrap.market(2, "KRW-ETH", 3, 1, RATE, RATE);
            bob = bootstrap.user("bob@mdf-p3.local");
            for (int asset : new int[] {1, 2, 3}) {
                bootstrap.userAccounts(bob, asset);
            }
            bootstrap.deposit(bob, 2, new BigDecimal("10"));
            bootstrap.deposit(bob, 3, new BigDecimal("10"));
            db.setProtocol(3);
            p0 = new Writer(jdbc, tx, mapper, "matching-p0");
            p1 = new Writer(jdbc, tx, mapper, "matching-p1");
        }

        AppendResult append(Writer writer, int market, long orderId) {
            OrderIntake intake = sell(market, bob, orderId);
            return writer.appender.append(new CommandDraft(intake.marketId(), intake.type(), intake.userId(),
                    intake.clientOrderId(), intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                    mapper.toPayloadJson(intake)));
        }

        /** writer 로 저널에 적은 명령을 새 엔진에 적용해 정산·이벤트 적재에 넘길 배치를 만든다. */
        List<EngineEvent> journaled(Writer writer, int market, long orderId) {
            AppendResult ack = append(writer, market, orderId);
            JournalEntry entry = new CommandJournalReader(jdbc).readAfter(ack.seq() - 1, 1).get(0);
            List<EngineEvent> events = new MatchingEngineImpl().apply(mapper.toCommand(entry));
            assertFalse(events.isEmpty());
            return events;
        }

        /** 표에 쓰인 (마켓, epoch) 쌍을 'market:epoch' 꼴로 잇는다. */
        String rows(String table, String epochColumn) {
            return jdbc.sql("SELECT string_agg(market_id || ':' || epoch, ',' ORDER BY market_id, epoch) FROM "
                            + "(SELECT DISTINCT market_id, " + epochColumn + " AS epoch FROM " + table + ") r")
                    .query(String.class)
                    .single();
        }

        /** 마켓 a 는 p0 epoch, b 는 p1 epoch 로 쓰였다는 기대값. */
        String byMarket(int a, int b) {
            return a + ":" + p0.leader.epoch() + "," + b + ":" + p1.leader.epoch();
        }

        long count(String sql) {
            return jdbc.sql(sql).query(Long.class).single();
        }

        void assertBothStillLead() {
            assertTrue(p0.lease.isLeader() && p1.lease.isLeader(), "다른 파티션의 거부가 자기 리스를 강등시켰다");
        }

        @Override
        public void close() {
            p0.lease.close();
            p1.lease.close();
            pool.close();
        }
    }

    /** 파티션 리스 하나를 쥔 리더와 그 리더로 만든 쓰기 경로 네 곳. */
    private static final class Writer {
        final LeaderLeaseService lease;
        final LeaderIdentity leader;
        final CommandJournalAppender appender;
        final SettlementService settlement;
        final EventJournalAppender events;
        final SnapshotStore snapshots;

        Writer(JdbcClient jdbc, TransactionTemplate tx, CommandPayloadMapper mapper, String resource) {
            lease = new LeaderLeaseService(new PartitionStore(jdbc, resource), FencingEpochPublisher.NONE,
                    Duration.ofMinutes(5), Duration.ofSeconds(1), NodeServiceHealth.ALWAYS_SERVICEABLE);
            assertTrue(lease.tryAcquire(), resource + " 획득 실패");
            leader = lease.requireLeader();
            appender = new CommandJournalAppender(jdbc, tx, lease);
            settlement = new SettlementService(jdbc, tx, lease, new ObjectMapper(), new MarketCatalog(jdbc), mapper);
            events = new EventJournalAppender(jdbc, tx, lease, new ObjectMapper());
            snapshots = new SnapshotStore(jdbc, tx, "s2-test", "s2-config");
        }
    }

    /** 파티션 자원을 V48 함수로 획득·갱신한다. S4 의 파티션 리스 서비스 전까지 시험만 쓴다. */
    private record PartitionStore(JdbcClient jdbc, String resource) implements LeaderLeaseStore {

        @Override
        public Optional<Long> acquire(String holder, Duration ttl) {
            return jdbc.sql("SELECT try_acquire_lease(?, ?, ? * interval '1 millisecond')")
                    .params(resource, holder, ttl.toMillis())
                    .query(Long.class)
                    .optional();
        }

        @Override
        public boolean renew(String holder, long epoch, Duration ttl) {
            return Boolean.TRUE.equals(jdbc.sql("SELECT renew_lease(?, ?, ?, ? * interval '1 millisecond')")
                    .params(resource, holder, epoch, ttl.toMillis())
                    .query(Boolean.class)
                    .single());
        }

        @Override
        public void release(String holder, long epoch) {
            jdbc.sql("SELECT release_lease(?, ?, ?)").params(resource, holder, epoch).query(Boolean.class).single();
        }

        @Override
        public Optional<LeaseSnapshot> current() {
            return jdbc.sql("SELECT holder, epoch, lease_until FROM leader_lease WHERE resource = ?")
                    .param(resource)
                    .query((rs, rowNum) -> new LeaseSnapshot(rs.getString("holder"), rs.getLong("epoch"),
                            rs.getObject("lease_until", OffsetDateTime.class).toInstant()))
                    .optional();
        }

        @Override
        public double priorDowntimeSeconds(Duration ttl) {
            return 0;
        }

        @Override
        public String describe() {
            return "test-partition(" + resource + ")";
        }
    }

    private static long queryEpoch(PartitionLeaseDb db, String resource) throws SQLException {
        return db.ownerLong("SELECT epoch FROM leader_lease WHERE resource = '" + resource + "'");
    }

    static long prologue(Connection c, String holder, long epoch, String clientOrderId, int market) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT out_seq FROM append_command_prologue(?, ?, true, 1, ?, ?::smallint)")) {
            ps.setString(1, holder);
            ps.setLong(2, epoch);
            ps.setString(3, clientOrderId);
            ps.setInt(4, market);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /** append_command_journal_row 가 넣은 행 수. */
    static int append(Connection c, long seq, int market, String holder, long epoch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT append_command_journal_row(?, ?::smallint, 'NEW', 1, ?, ?, 0, 0, '{}'::jsonb, 0, ?, ?)
                """)) {
            ps.setLong(1, seq);
            ps.setInt(2, market);
            ps.setString(3, "c" + seq);
            ps.setLong(4, seq);
            ps.setString(5, holder);
            ps.setLong(6, epoch);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private static OrderIntake sell(int market, long userId, long orderId) {
        String clientOrderId = "mdf-" + orderId;
        return new OrderIntake(market, CommandDraft.CommandType.NEW, userId, clientOrderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.SELL, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("60000000"), new BigDecimal("0.5"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes()), RATE, RATE);
    }

    private static EngineEvent withMarket(EngineEvent e, int market, int ordinal) {
        return new EngineEvent(e.eventSeq(), e.sourceCmdSeq(), ordinal, market, e.type(), e.engineTs(),
                e.orderId(), e.counterOrderId(), e.makerUserId(), e.takerUserId(), e.takerSide(), e.price(),
                e.quantity(), e.reason());
    }

    private static EngineEvent withSource(EngineEvent e, long cmdSeq) {
        return new EngineEvent(e.eventSeq(), cmdSeq, e.eventOrdinal(), e.marketId(), e.type(), e.engineTs(),
                e.orderId(), e.counterOrderId(), e.makerUserId(), e.takerUserId(), e.takerSide(), e.price(),
                e.quantity(), e.reason());
    }

    private static String firstLine(SQLException e) {
        String message = e.getMessage();
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}

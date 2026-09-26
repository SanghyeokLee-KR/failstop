package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.JournalCheckpoints;
import kr.exchange.journal.JournalChecksum;
import kr.exchange.journal.JournalEntry;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayResult.PendingBatch;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 크래시 창 재적재 순서(ADR 0022 5-3). 마켓별 큐를 (created_at, seq) 머리 키로 병합하는지, 마켓 안 seq 순서를
 * 바꾸지 않는지, 2 단계 방출에 이미 물질화된 명령이 없는지(5-4) 본다.
 *
 * 명령은 V40 next_market_command_seq 로 대역 seq 를 받아 직접 넣는다. 대역에서는 seq 오름차순이 마켓 id 순서라
 * 시간 순서와 갈리는 저널을 만들 수 있다. 시험마다 같은 컨테이너의 별도 데이터베이스를 쓴다.
 */
@Testcontainers
class CrashWindowReinjectionOrderTest {

    private static final long COMMAND_BAND = 1_000_000_000_000L;
    private static final int KRW = 1;
    private static final int COIN2 = 2;
    private static final int COIN12 = 3;
    private static final int M2 = 2;
    private static final int M12 = 12;
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-13T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    @Test
    void mergePreservesSeqOrderWithinEachMarket() throws Exception {
        try (Fixture f = new Fixture("cw_merge_order")) {
            f.bootstrap.deposit(f.bob, KRW, new BigDecimal("100000"));
            f.insertSettled(f.limit(M2, f.bob, OrderCommand.Side.BUY, "100"), T0);
            long settled12 = f.insertSettled(f.limit(M12, f.bob, OrderCommand.Side.BUY, "100"), T0);
            long hole2a = f.insert(f.limit(M2, f.bob, OrderCommand.Side.BUY, "101"), T0.plusSeconds(2));
            long hole2b = f.insert(f.limit(M2, f.bob, OrderCommand.Side.BUY, "102"), T0.plusSeconds(3));
            long hole12 = f.insert(f.limit(M12, f.bob, OrderCommand.Side.BUY, "103"), T0.plusSeconds(1));
            assertTrue(settled12 > hole2b,
                    "마켓 12 의 물질화된 명령이 마켓 2 구멍 위에 없어 전역 경계와 마켓 경계가 갈리지 않는다");
            List<Long> order = new ArrayList<>();
            List<Long> materialized = new ArrayList<>();

            ReplayResult result = new EngineRecovery(f.replay, f.recording(order, materialized), f.lease)
                    .recover(new MatchingEngineImpl());

            assertEquals(List.of(hole12, hole2a, hole2b), order, "재적재 순서가 (created_at, seq) 병합이 아니다");
            assertEquals(List.of(hole2a, hole2b), order.stream().filter(seq -> seq / COMMAND_BAND == M2).toList(),
                    "마켓 안 seq 순서가 바뀌었다");
            assertEquals(List.of(), materialized, "2 단계 방출에 이미 물질화된 명령이 섞였다");
            assertTrue(result.verifiedEvents() > 0, "1 단계 대조가 비었다");
            for (long seq : order) {
                assertTrue(f.events(seq) > 0, "재적재 뒤 이벤트가 없다: seq=" + seq);
            }
            assertEquals(0, f.unbalancedAssets(), "자산별 원장 합이 0 이 아니다");
        }
    }

    @Test
    void crossMarketFundingInversionFailsOnSeqAxisPassesOnMergeAxis() throws Exception {
        try (Fixture f = new Fixture("cw_cross_market_funding")) {
            f.bootstrap.deposit(f.bob, KRW, new BigDecimal("1000"));
            f.bootstrap.deposit(f.carol, COIN2, BigDecimal.ONE);
            f.bootstrap.deposit(f.alice, COIN12, BigDecimal.ONE);
            f.insertSettled(f.limit(M12, f.bob, OrderCommand.Side.BUY, "1000"), T0);
            f.insertSettled(f.limit(M2, f.carol, OrderCommand.Side.SELL, "1000"), T0);
            // 크래시 창: 마켓 12 에서 팔아 받은 KRW 로 마켓 2 에서 산다. 잔고가 딱 맞는다.
            long sell12 = f.insert(f.limit(M12, f.alice, OrderCommand.Side.SELL, "1000"), T0.plusSeconds(1));
            long buy2 = f.insert(f.limit(M2, f.alice, OrderCommand.Side.BUY, "1000"), T0.plusSeconds(2));
            assertTrue(buy2 < sell12, "seq 축에서 매수가 매도보다 앞서지 않아 역전이 판정되지 않는다");
            assertEquals(0, f.available(f.alice, KRW).signum(), "매수 대금이 크래시 창 밖에서 이미 있다");

            MatchingEngineImpl engine = new MatchingEngineImpl();
            JournalCheckpoints bounds = f.replay.materializedThroughByMarket(Map.of());
            List<PendingBatch> seqAxis = new ArrayList<>();
            for (int marketId : List.of(M2, M12)) {
                f.replay.verifyToMarket(engine, marketId, bounds);
                seqAxis.addAll(f.replay.replayTailMarket(engine, marketId,
                        f.commands.maxSeqForMarket(marketId)).pendingBatches());
            }
            seqAxis.sort(Comparator.comparingLong(PendingBatch::seq));
            assertEquals(List.of(buy2, sell12), seqAxis.stream().map(PendingBatch::seq).toList());
            DataAccessException rejected = assertThrows(DataAccessException.class,
                    () -> f.settlement.appendAndSettle(seqAxis.get(0).events()));
            assertEquals("23514", sqlState(rejected), "seq 축 재적재가 잔고 부족으로 서지 않았다");
            assertEquals(0, f.events(buy2), "거부된 배치가 일부라도 커밋됐다");

            List<Long> order = new ArrayList<>();
            List<Long> materialized = new ArrayList<>();
            new EngineRecovery(f.replay, f.recording(order, materialized), f.lease)
                    .recover(new MatchingEngineImpl());

            assertEquals(List.of(sell12, buy2), order, "병합 순서가 매도 대금보다 매수를 먼저 정산했다");
            assertEquals(List.of(), materialized);
            assertEquals(2, f.count("SELECT count(*) FROM trades"), "크래시 창 두 체결이 정산되지 않았다");
            assertEquals(0, f.available(f.alice, KRW).signum());
            assertEquals(0, BigDecimal.ONE.compareTo(f.available(f.alice, COIN2)));
            assertEquals(0, f.unbalancedAssets(), "자산별 원장 합이 0 이 아니다");
        }
    }

    @Test
    void inMarketCreatedAtInversionKeepsSeqOrderAndCounts() throws Exception {
        try (Fixture f = new Fixture("cw_in_market_inversion")) {
            f.bootstrap.deposit(f.bob, KRW, new BigDecimal("100000"));
            long first2 = f.insert(f.limit(M2, f.bob, OrderCommand.Side.BUY, "100"), T0.plusSeconds(5));
            long second2 = f.insert(f.limit(M2, f.bob, OrderCommand.Side.BUY, "101"), T0.plusSeconds(1));
            long only12 = f.insert(f.limit(M12, f.bob, OrderCommand.Side.BUY, "102"), T0.plusSeconds(3));
            List<Long> order = new ArrayList<>();
            List<Long> materialized = new ArrayList<>();
            EngineRecovery recovery = new EngineRecovery(f.replay, f.recording(order, materialized), f.lease);

            recovery.recover(new MatchingEngineImpl());

            assertEquals(List.of(only12, first2, second2), order, "마켓 안 역전 배치가 seq 순서를 앞질렀다");
            assertEquals(1, recovery.reinjectCreatedAtInversions());
            assertEquals(List.of(), materialized);
        }
    }

    /**
     * 예약 판(ADR 0034 S3, S3 설계 4-8·6-2). P2 에서는 매도 대금으로 다른 마켓 매수를 하는 역전 저널이 생기지 않는다. 매도가
     * 정산되지 않은 동안 매수 머리말이 잔고 행에서 예약하다 23514 로 거부되기 때문이다.
     */
    @Test
    void p2RefusesTheCrossMarketFundingInversionAtIntake() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("cw_p2_inversion", 2)) {
            long alice = f.user("alice", BigDecimal.ZERO);
            long carol = f.user("carol", new BigDecimal("1000"));
            f.deposit(alice, ReservationFixture.ETH, BigDecimal.ONE);
            LeaderLeaseService lease = f.lease();
            kr.exchange.journal.CommandJournalAppender appender = f.appender(lease, true);
            MatchingEngineImpl engine = new MatchingEngineImpl();
            f.submit(appender, engine, f.settlement(lease),
                    f.limit(ReservationFixture.KRW_ETH, carol, OrderCommand.Side.BUY, "1000", "1"));
            // 크래시 창: 마켓 2 에서 팔아 받을 KRW 로 마켓 1 에서 산다. 매도는 저널에만 있고 정산되지 않았다.
            f.append(appender, f.limit(ReservationFixture.KRW_ETH, alice, OrderCommand.Side.SELL, "1000", "1"));
            OrderIntake buy = f.limit(ReservationFixture.KRW_BTC, alice, OrderCommand.Side.BUY, "1000", "1");

            DataAccessException refused = assertThrows(DataAccessException.class, () -> f.append(appender, buy));

            assertTrue(kr.exchange.journal.SqlErrors.isInsufficientFunds(refused), refused.toString());
            assertEquals(0, f.count("SELECT count(*) FROM command_journal WHERE assigned_order_id = "
                    + buy.assignedOrderId()), "잔고 없는 매수가 저널에 들어갔다");
            assertTrue(lease.isLeader());
        }
    }

    /**
     * 예약 판(S3 설계 6-2). 두 주문을 모두 예약할 만큼 가진 사용자의 예약 명령 둘을 정산 없이 남기고 seq 축 순서와 병합 순서로
     * 각각 재적재한다. 정산이 예약을 다시 부르지 않으므로 둘 다 23514 없이 끝나고 최종 잔고가 같다.
     */
    @Test
    void reservedCommandsReinjectInEitherOrderWithoutInsufficientFunds() throws Exception {
        String seqAxis = reinjectReserved("cw_p2_seq_axis", false);
        String mergeAxis = reinjectReserved("cw_p2_merge_axis", true);
        assertEquals(seqAxis, mergeAxis, "재적재 순서에 따라 결과 잔고가 갈렸다");
    }

    /** 예약 명령 둘(뒤 seq 가 앞 created_at)을 남기고 재적재한 뒤 잔고와 주문 행을 잇는다. */
    private static String reinjectReserved(String prefix, boolean merge) throws Exception {
        try (ReservationFixture f = ReservationFixture.create(prefix, 2)) {
            long alice = f.user("alice", new BigDecimal("100000"));
            LeaderLeaseService lease = f.lease();
            OrderIntake a = f.limit(ReservationFixture.KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1");
            OrderIntake b = f.limit(ReservationFixture.KRW_ETH, alice, OrderCommand.Side.BUY, "40000", "0.1");
            long seqA = insertReserved(f, lease, a, T0.plusSeconds(2));
            long seqB = insertReserved(f, lease, b, T0.plusSeconds(1));
            assertTrue(seqA < seqB, "seq 축과 시간 축이 갈리지 않는다");
            assertEquals(0, new BigDecimal("91000").compareTo(f.available(alice, ReservationFixture.KRW)));
            SettlementService settlement = f.settlement(lease);
            ReplayService replay = new ReplayService(new CommandJournalReader(f.app), new EventJournalReader(f.app),
                    f.mapper, new ObjectMapper(), new MarketCatalog(f.app));
            List<Long> order = new ArrayList<>();
            if (merge) {
                new EngineRecovery(replay, events -> {
                    order.add(events.get(0).sourceCmdSeq());
                    settlement.appendAndSettle(events);
                }, lease).recover(new MatchingEngineImpl());
                assertEquals(List.of(seqB, seqA), order, "병합 순서가 created_at 순이 아니다");
            } else {
                MatchingEngineImpl engine = new MatchingEngineImpl();
                JournalCheckpoints bounds = replay.materializedThroughByMarket(Map.of());
                List<PendingBatch> batches = new ArrayList<>();
                CommandJournalReader commands = new CommandJournalReader(f.app);
                for (int market : List.of(ReservationFixture.KRW_BTC, ReservationFixture.KRW_ETH)) {
                    replay.verifyToMarket(engine, market, bounds);
                    batches.addAll(replay.replayTailMarket(engine, market, commands.maxSeqForMarket(market))
                            .pendingBatches());
                }
                batches.sort(Comparator.comparingLong(PendingBatch::seq));
                for (PendingBatch batch : batches) {
                    order.add(batch.seq());
                    settlement.appendAndSettle(batch.events());
                }
                assertEquals(List.of(seqA, seqB), order);
            }
            assertEquals(0, settlement.insufficientFunds());
            assertEquals(0, f.invariant(kr.exchange.harness.InvariantCheck.C14_LOCKED_RESERVATION));
            return f.owner.sql("""
                    SELECT (SELECT string_agg(a.purpose || a.asset_id || '=' || b.balance, ',' ORDER BY a.purpose, a.asset_id)
                              FROM accounts a JOIN balances b ON b.account_id = a.id
                             WHERE a.owner_type = 'USER' AND a.owner_id = ?) || '|'
                        || (SELECT string_agg(o.status || ':' || o.locked_amount, ',' ORDER BY o.id) FROM orders o)
                    """).param(alice).query(String.class).single();
        }
    }

    /** 10인자 머리말로 예약한 뒤 소유자가 저널 행을 넣어 created_at 을 정한다(한 트랜잭션). */
    private static long insertReserved(ReservationFixture f, LeaderLeaseService lease, OrderIntake intake,
                                       OffsetDateTime createdAt) throws SQLException {
        CommandDraft draft = f.draft(intake);
        try (Connection c = f.db.owner()) {
            c.setAutoCommit(false);
            long seq;
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT out_seq, out_reserved FROM append_command_prologue("
                            + "?, ?, true, ?, ?, ?::smallint, ?, ?::jsonb, ?::smallint, ?::numeric)")) {
                ps.setString(1, lease.requireLeader().holder());
                ps.setLong(2, lease.requireLeader().epoch());
                ps.setLong(3, draft.userId());
                ps.setString(4, draft.clientOrderId());
                ps.setInt(5, draft.marketId());
                ps.setLong(6, draft.assignedOrderId());
                ps.setString(7, draft.payloadJson());
                ps.setShort(8, intake.reserveCheck().assetId());
                ps.setBigDecimal(9, intake.reserveCheck().amount());
                java.sql.ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("out_reserved"));
                seq = rs.getLong("out_seq");
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                 assigned_order_id, engine_ts, tie_break, payload, fencing_token,
                                                 checksum, created_at)
                    VALUES (?, ?, 'NEW', ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """)) {
                ps.setLong(1, seq);
                ps.setInt(2, draft.marketId());
                ps.setLong(3, draft.userId());
                ps.setString(4, draft.clientOrderId());
                ps.setLong(5, draft.assignedOrderId());
                ps.setLong(6, draft.engineTs());
                ps.setLong(7, draft.tieBreak());
                ps.setString(8, draft.payloadJson());
                ps.setLong(9, lease.requireLeader().epoch());
                ps.setLong(10, JournalChecksum.of(seq, draft));
                ps.setObject(11, createdAt);
                assertEquals(1, ps.executeUpdate());
            }
            c.commit();
            return seq;
        }
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** 데이터베이스 하나에 붙인 정산·재생 배선과 사용자 셋. */
    private static final class Fixture implements AutoCloseable {
        final HikariDataSource dataSource;
        final JdbcClient jdbc;
        final LeaderLeaseService lease;
        final CommandPayloadMapper mapper;
        final CommandJournalReader commands;
        final SettlementService settlement;
        final ReplayService replay;
        final AccountBootstrap bootstrap;
        final long alice;
        final long bob;
        final long carol;
        // 크래시 창 밖 명령을 라이터처럼 적용하는 엔진. 정산에 넘길 이벤트를 만든다.
        private final MatchingEngineImpl live = new MatchingEngineImpl();
        private long nextOrderId = 1_000;

        Fixture(String name) throws SQLException {
            try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement st = conn.createStatement()) {
                st.execute("CREATE DATABASE " + name);
            }
            String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
            Flyway.configure()
                    .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(POSTGRES.getUsername());
            config.setPassword(POSTGRES.getPassword());
            config.setMaximumPoolSize(4);
            dataSource = new HikariDataSource(config);
            jdbc = JdbcClient.create(dataSource);
            TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
            ObjectMapper json = new ObjectMapper();
            mapper = new CommandPayloadMapper(json);
            MarketCatalog catalog = new MarketCatalog(jdbc);
            commands = new CommandJournalReader(jdbc);
            lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(30), Duration.ofSeconds(1));
            settlement = new SettlementService(jdbc, tx, lease, json, catalog, mapper);
            replay = new ReplayService(commands, new EventJournalReader(jdbc), mapper, json, catalog);

            bootstrap = new AccountBootstrap(jdbc);
            bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
            bootstrap.asset(COIN2, "CTWO", "Coin Two", "CRYPTO", 8);
            bootstrap.asset(COIN12, "CTWELVE", "Coin Twelve", "CRYPTO", 8);
            bootstrap.market(M2, "KRW-CTWO", COIN2, KRW, BigDecimal.ZERO, BigDecimal.ZERO);
            bootstrap.market(M12, "KRW-CTWELVE", COIN12, KRW, BigDecimal.ZERO, BigDecimal.ZERO);
            alice = bootstrap.user("alice@test.local");
            bob = bootstrap.user("bob@test.local");
            carol = bootstrap.user("carol@test.local");
            for (long uid : new long[] {alice, bob, carol}) {
                for (int asset : new int[] {KRW, COIN2, COIN12}) {
                    bootstrap.userAccounts(uid, asset);
                }
            }
            jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                            + "WHERE resource = 'matching-engine'")
                    .update();
            assertTrue(lease.tryAcquire(), "테스트 리더 승격 실패");
        }

        OrderIntake limit(int marketId, long userId, OrderCommand.Side side, String price) {
            long orderId = nextOrderId++;
            String key = "cw-" + orderId;
            return new OrderIntake(marketId, CommandDraft.CommandType.NEW, userId, key, orderId,
                    T0.toInstant().toEpochMilli() + orderId, orderId, side, OrderCommand.OrdType.LIMIT,
                    OrderCommand.TimeInForce.GTC, new BigDecimal(price), BigDecimal.ONE, null,
                    UUID.nameUUIDFromBytes(key.getBytes()), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /** 대역 seq 로 명령만 넣는다. created_at 을 정해 접수 시간 순서를 만든다. */
        long insert(OrderIntake intake, OffsetDateTime createdAt) {
            long seq = jdbc.sql("SELECT next_market_command_seq(?::smallint)")
                    .param(intake.marketId())
                    .query(Long.class)
                    .single();
            CommandDraft draft = new CommandDraft(intake.marketId(), intake.type(), intake.userId(),
                    intake.clientOrderId(), intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                    mapper.toPayloadJson(intake));
            jdbc.sql("""
                            INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                         assigned_order_id, engine_ts, tie_break, payload,
                                                         fencing_token, checksum, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                            """)
                    .params(seq, draft.marketId(), draft.type().name(), draft.userId(), draft.clientOrderId(),
                            draft.assignedOrderId(), draft.engineTs(), draft.tieBreak(), draft.payloadJson(),
                            lease.requireLeader().epoch(), JournalChecksum.of(seq, draft), createdAt)
                    .update();
            return seq;
        }

        /** 명령을 넣고 라이터처럼 적용·정산까지 끝낸다. 크래시 창 밖의 물질화된 명령이다. */
        long insertSettled(OrderIntake intake, OffsetDateTime createdAt) {
            long seq = insert(intake, createdAt);
            JournalEntry entry = commands.readAfterForMarket(seq - 1, intake.marketId(), seq, 1).get(0);
            settlement.appendAndSettle(live.apply(mapper.toCommand(entry)));
            return seq;
        }

        /** 넘겨받은 순서와, 그 순간 이미 이벤트가 있던 명령을 기록한 뒤 정산으로 넘긴다. */
        SingleWriterLoop.EventSink recording(List<Long> order, List<Long> materialized) {
            return events -> {
                long seq = events.get(0).sourceCmdSeq();
                order.add(seq);
                if (events(seq) > 0) {
                    materialized.add(seq);
                }
                settlement.appendAndSettle(events);
            };
        }

        long events(long seq) {
            return count("SELECT count(*) FROM event_journal WHERE source_cmd_seq = " + seq);
        }

        long unbalancedAssets() {
            return count("SELECT count(*) FROM (SELECT asset_id FROM ledger_entries "
                    + "GROUP BY asset_id HAVING SUM(amount) <> 0) x");
        }

        long count(String sql) {
            return jdbc.sql(sql).query(Long.class).single();
        }

        BigDecimal available(long userId, int assetId) {
            return jdbc.sql("SELECT available FROM user_balances WHERE user_id = ? AND asset_id = ?")
                    .params(userId, assetId)
                    .query(BigDecimal.class)
                    .single();
        }

        @Override
        public void close() {
            lease.close();
            dataSource.close();
        }
    }
}

package kr.exchange.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.LeaderIdentity;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SnapshotCodec;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import kr.exchange.snapshot.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fencing 거부 주입의 범위(S2 설계 6-1, DB 설계 §5.4-b). 판정 규칙은 3인자 assert_lease_holder 한 곳에 있어 그 함수에 주입하면
 * 네 쓰기 경로(append 머리말, 정산 머리말, 이벤트 append, 스냅샷)가 모두 거부된다. 옛 2인자 assert_current_leader 에만 주입하면
 * 새 경로가 그 주입을 비껴간다(V30 머리 주석이 적은 무력화). 이 대조가 주입 시험을 3인자 판으로 옮긴 이유다.
 */
class FencingInjectionCoverageTest {

    private static final BigDecimal RATE = new BigDecimal("0.0005");
    private static final long BASE_TS = 1_758_200_000_000L;

    @Test
    void faultInThreeArgumentJudgeRejectsEveryWritePath() throws Exception {
        try (Fixture f = new Fixture("inject_three")) {
            f.db.exec("CREATE SEQUENCE test_fencing_fault");
            f.db.exec("ALTER FUNCTION assert_lease_holder(TEXT, TEXT, BIGINT) RENAME TO assert_lease_holder_real");
            f.db.exec("""
                    CREATE FUNCTION assert_lease_holder(p_resource TEXT, p_holder TEXT, p_epoch BIGINT)
                    RETURNS void LANGUAGE plpgsql AS $fault$
                    BEGIN
                        PERFORM nextval('test_fencing_fault');
                        RAISE EXCEPTION 'fencing 위반: 주입된 거부';
                    END $fault$
                    """);

            long calls = 0;
            for (Executable path : f.writePaths()) {
                DataAccessException rejected = assertThrows(DataAccessException.class, path);
                assertTrue(FencingErrors.isFencingRejection(rejected), FencingErrors.rejectionDetail(rejected));
                assertTrue(FencingErrors.rejectionDetail(rejected).contains("주입된 거부"),
                        FencingErrors.rejectionDetail(rejected));
                long now = f.db.ownerLong(
                        "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM test_fencing_fault");
                assertTrue(now > calls, "주입 지점을 거치지 않은 쓰기 경로가 있다");
                calls = now;
            }
            assertEquals(0, f.writes(), "주입된 거부 아래에서 쓰기가 남았다");
            assertTrue(f.lease.isLeader(), "권위가 유지된 거부로 강등됐다");
        }
    }

    @Test
    void faultInLegacyTwoArgumentJudgeMissesTheNewWritePaths() throws Throwable {
        try (Fixture f = new Fixture("inject_two")) {
            f.db.exec("CREATE SEQUENCE test_fencing_fault");
            f.db.exec("ALTER FUNCTION assert_current_leader(TEXT, BIGINT) RENAME TO assert_current_leader_real");
            f.db.exec("""
                    CREATE FUNCTION assert_current_leader(p_holder TEXT, p_epoch BIGINT)
                    RETURNS void LANGUAGE plpgsql AS $fault$
                    BEGIN
                        PERFORM nextval('test_fencing_fault');
                        RAISE EXCEPTION 'fencing 위반: 주입된 거부';
                    END $fault$
                    """);

            for (Executable path : f.writePaths()) {
                path.execute();
            }
            assertEquals(4, f.writes(), "새 쓰기 경로가 2인자 판정을 거쳤다");
            assertEquals("false", f.db.ownerString("SELECT is_called::text FROM test_fencing_fault"),
                    "새 쓰기 경로가 2인자 판정을 불렀다");

            // 주입은 살아 있다. 옛 경로(2인자 잠금 함수)는 거부된다.
            DataAccessException legacy = assertThrows(DataAccessException.class, () -> f.jdbc
                    .sql("SELECT assert_and_lock_leader(?, ?)")
                    .params(f.leader.holder(), f.leader.epoch())
                    .query(Long.class).single());
            assertTrue(FencingErrors.isFencingRejection(legacy), FencingErrors.rejectionDetail(legacy));
        }
    }

    /** 리더 하나와 정산·이벤트 적재에 쓸 명령 둘을 미리 만든다. 주입은 준비가 끝난 뒤에 건다. */
    private static final class Fixture implements AutoCloseable {
        final PartitionLeaseDb db;
        final HikariDataSource pool;
        final JdbcClient jdbc;
        final LeaderLeaseService lease;
        final LeaderIdentity leader;
        final CommandJournalAppender appender;
        final SettlementService settlement;
        final EventJournalAppender events;
        final SnapshotStore snapshots;
        final CommandPayloadMapper mapper = new CommandPayloadMapper(new ObjectMapper());
        final long bob;
        final List<EngineEvent> toSettle;
        final List<EngineEvent> toJournal;
        final long before;

        Fixture(String prefix) throws Exception {
            db = PartitionLeaseDb.create(prefix);
            pool = db.ownerPool();
            jdbc = JdbcClient.create(pool);
            TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(pool));
            AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
            bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
            bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
            bootstrap.market(1, "KRW-BTC", 2, 1, RATE, RATE);
            bob = bootstrap.user("bob@inject.local");
            bootstrap.userAccounts(bob, 1);
            bootstrap.userAccounts(bob, 2);
            bootstrap.deposit(bob, 2, new BigDecimal("10"));

            lease = new LeaderLeaseService(jdbc, Duration.ofMinutes(5), Duration.ofSeconds(1));
            assertTrue(lease.tryAcquire());
            leader = lease.requireLeader();
            appender = new CommandJournalAppender(jdbc, tx, lease);
            settlement = new SettlementService(jdbc, tx, lease, new ObjectMapper(), new MarketCatalog(jdbc), mapper);
            events = new EventJournalAppender(jdbc, tx, lease, new ObjectMapper());
            snapshots = new SnapshotStore(jdbc, tx, "s2-test", "s2-config");
            toSettle = journaled(83_001);
            toJournal = journaled(83_002);
            before = writes();
        }

        /** 네 쓰기 경로. 순서대로 append 머리말, 정산 머리말, 이벤트 append, 스냅샷 잠금을 지난다. */
        List<Executable> writePaths() {
            OrderBookImpl book = new OrderBookImpl(1);
            return List.of(
                    () -> appender.append(draft(sell(83_003))),
                    () -> settlement.appendAndSettle(toSettle),
                    () -> events.append(toJournal),
                    () -> snapshots.persist(leader, List.of(new SnapshotStore.MarketState(1, 1,
                            SnapshotCodec.encode(book), book.stateChecksum()))));
        }

        /** 준비 뒤 네 경로가 남긴 행 수. 명령, 정산 이벤트, 적재 이벤트, 스냅샷이 하나씩이면 4 다. */
        long writes() {
            return jdbc.sql("""
                    SELECT (SELECT count(*) FROM command_journal) + (SELECT count(*) FROM event_journal)
                         + (SELECT count(*) FROM snapshots)
                    """).query(Long.class).single() - before;
        }

        private List<EngineEvent> journaled(long orderId) {
            AppendResult ack = appender.append(draft(sell(orderId)));
            JournalEntry entry = new CommandJournalReader(jdbc).readAfter(ack.seq() - 1, 1).get(0);
            return new MatchingEngineImpl().apply(mapper.toCommand(entry));
        }

        private CommandDraft draft(OrderIntake intake) {
            return new CommandDraft(intake.marketId(), intake.type(), intake.userId(), intake.clientOrderId(),
                    intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(), mapper.toPayloadJson(intake));
        }

        private OrderIntake sell(long orderId) {
            String clientOrderId = "inject-" + orderId;
            return new OrderIntake(1, CommandDraft.CommandType.NEW, bob, clientOrderId, orderId,
                    BASE_TS + orderId, orderId, OrderCommand.Side.SELL, OrderCommand.OrdType.LIMIT,
                    OrderCommand.TimeInForce.GTC, new BigDecimal("60000000"), new BigDecimal("0.1"), null,
                    UUID.nameUUIDFromBytes(clientOrderId.getBytes()), RATE, RATE);
        }

        @Override
        public void close() {
            lease.close();
            pool.close();
        }
    }
}

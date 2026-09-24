package kr.exchange.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderBookImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.core.SnapshotCodec;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.engine.lease.PostgresLeaderLeaseStore;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalAppender;
import kr.exchange.journal.JournalEntry;
import kr.exchange.journal.TransientDbErrors;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import kr.exchange.snapshot.SnapshotStore;
import org.junit.jupiter.api.Test;
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
 * 앱 로그인으로 S2 쓰기 경로를 V49 권한 상태에서 돌린다(S2 설계 6-1). 로컬 카오스와 통합 시험은 소유자로 접속해 권한 누락을
 * 가린다. 이 시험이 "V49 이미지에서 S2 이미지로 권한 조치 없이 되돌린다"(S2 설계 4-1 의 2)의 근거다.
 */
class AppRoleWritePathIntegrationTest {

    private static final BigDecimal RATE = new BigDecimal("0.0005");
    private static final long BASE_TS = 1_758_100_000_000L;

    @Test
    void s2WritePathsRunAsAppLoginWithoutLeaseUpdateOrJournalInsertGrant() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("app_role_path").withRuntimeRoles();
        long bob;
        try (HikariDataSource ownerPool = db.ownerPool()) {
            AccountBootstrap bootstrap = new AccountBootstrap(JdbcClient.create(ownerPool));
            bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
            bootstrap.asset(2, "BTC", "Bitcoin", "CRYPTO", 8);
            bootstrap.market(1, "KRW-BTC", 2, 1, RATE, RATE);
            bob = bootstrap.user("bob@app-role.local");
            bootstrap.userAccounts(bob, 1);
            bootstrap.userAccounts(bob, 2);
            bootstrap.deposit(bob, 2, new BigDecimal("10"));
        }
        // V49 권한 상태는 Flyway 가 이미 만들었다(S3 설계 4-1, V49 는 저장소 마이그레이션이다).

        try (HikariDataSource appPool = db.appPool()) {
            JdbcClient app = JdbcClient.create(appPool);
            TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(appPool));
            CommandPayloadMapper mapper = new CommandPayloadMapper(new ObjectMapper());

            // V49 권한 상태가 실제로 걸렸다는 대조. 옛 경로의 직접 UPDATE 는 거부된다.
            DataAccessException direct = assertThrows(DataAccessException.class, () -> app.sql(
                    "UPDATE leader_lease SET lease_until = lease_until WHERE resource = 'matching-engine'").update());
            assertEquals("42501", TransientDbErrors.sqlState(direct));

            LeaderLeaseService lease = new LeaderLeaseService(app, Duration.ofMinutes(5), Duration.ofSeconds(1));
            assertTrue(lease.tryAcquire(), "앱 로그인 승격 실패");
            assertTrue(lease.renew(), "앱 로그인 갱신 실패");
            LeaderIdentity leader = lease.requireLeader();

            CommandJournalAppender appender = new CommandJournalAppender(app, tx, lease);
            SettlementService settlement = new SettlementService(app, tx, lease, new ObjectMapper(),
                    new MarketCatalog(app), mapper);
            List<EngineEvent> settled = journaled(app, appender, mapper, bob, 82_001);
            settlement.appendAndSettle(settled);

            EventJournalAppender eventAppender = new EventJournalAppender(app, tx, lease, new ObjectMapper());
            eventAppender.append(journaled(app, appender, mapper, bob, 82_002));

            SnapshotStore snapshots = new SnapshotStore(app, tx, "s2-test", "s2-config");
            OrderBookImpl book = new OrderBookImpl(1);
            snapshots.persist(leader, List.of(new SnapshotStore.MarketState(1, 1, SnapshotCodec.encode(book),
                    book.stateChecksum())));

            assertTrue(lease.isLeader(), "쓰기 경로가 권한 오류로 강등을 불렀다");
            assertEquals(2, count(app, "SELECT count(*) FROM command_journal WHERE fencing_token = " + leader.epoch()));
            assertEquals(1, count(app, "SELECT count(*) FROM orders WHERE id = 82001"));
            assertEquals(2, count(app, "SELECT count(*) FROM event_journal WHERE fencing_epoch = " + leader.epoch()));
            assertEquals(1, count(app, "SELECT count(*) FROM snapshots WHERE fencing_epoch = " + leader.epoch()));

            // 반납은 직접 UPDATE 에서 소유자 권한 함수로 옮겼다. V49 뒤에도 된다.
            new PostgresLeaderLeaseStore(app).release(leader.holder(), leader.epoch());
            assertEquals(1, count(app, "SELECT count(*) FROM leader_lease "
                    + "WHERE resource = 'matching-engine' AND lease_until < clock_timestamp()"));
            lease.close();
        }
    }

    /** 저널에 적고 새 엔진에 적용해 정산할 배치를 만든다. */
    private static List<EngineEvent> journaled(JdbcClient jdbc, CommandJournalAppender appender,
                                               CommandPayloadMapper mapper, long userId, long orderId) {
        String clientOrderId = "app-role-" + orderId;
        OrderIntake intake = new OrderIntake(1, CommandDraft.CommandType.NEW, userId, clientOrderId, orderId,
                BASE_TS + orderId, orderId, OrderCommand.Side.SELL, OrderCommand.OrdType.LIMIT,
                OrderCommand.TimeInForce.GTC, new BigDecimal("60000000"), new BigDecimal("0.5"), null,
                UUID.nameUUIDFromBytes(clientOrderId.getBytes()), RATE, RATE);
        AppendResult ack = appender.append(new CommandDraft(intake.marketId(), intake.type(), intake.userId(),
                intake.clientOrderId(), intake.assignedOrderId(), intake.engineTs(), intake.tieBreak(),
                mapper.toPayloadJson(intake)));
        JournalEntry entry = new CommandJournalReader(jdbc).readAfter(ack.seq() - 1, 1).get(0);
        return new MatchingEngineImpl().apply(mapper.toCommand(entry));
    }

    private static long count(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

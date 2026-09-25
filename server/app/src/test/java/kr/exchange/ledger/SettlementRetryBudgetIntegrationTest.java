package kr.exchange.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.TransientDbErrors;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static kr.exchange.engine.lease.ReservationFixture.KRW;
import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정산 일시 오류 재시도 한도(DB 설계 §5.4 v2.56, S4b 설계 4-6 F1). 운영 기본 풀과 같은 lock_timeout 2초에서 다른 세션이 잔고 행을
 * 쥔다. 한 시도가 약 2초라 종전 한도(4회·5초)는 6초쯤에 소진됐고, 그것이 PG 지연 주입(E1)에서 라이터를 치명으로 만들었다.
 * 9초 쥐면 늘린 한도(8회·15초) 안에서 흡수하고, 20초 쥐면 여전히 소진해 호출자(라이터)에게 올린다.
 */
class SettlementRetryBudgetIntegrationTest {

    @Test
    void balanceRowHeldForNineSecondsIsAbsorbedWithinTheBudget() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_settle_budget_9s", 1);
             HikariDataSource pool = prodLockTimeoutPool(f)) {
            Scene scene = new Scene(f, pool);
            long started = System.nanoTime();
            try (Connection holder = scene.holdKrwBalance(9)) {
                scene.settlement.appendAndSettle(scene.events);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMs >= 8_500, "잠금이 풀리기 전에 정산이 끝났다(장면 전제): " + elapsedMs + "ms");
            assertTrue(scene.settlement.transientRetries() >= 3, "재시도 수: " + scene.settlement.transientRetries());
            assertEquals(0, scene.settlement.transientExhausted(), "9초 잠금을 한도 안에서 흡수하지 못했다");
            assertEquals(1, f.count("SELECT count(*) FROM orders WHERE id = " + scene.orderId));
            assertEquals(1, f.count("SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE' "
                    + "AND business_event_id = " + scene.orderId), "예약이 두 번 게시됐다");
            assertTrue(scene.lease.isLeader());
        }
    }

    @Test
    void balanceRowHeldForTwentySecondsStillExhaustsTheBudget() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("pml_settle_budget_20s", 1);
             HikariDataSource pool = prodLockTimeoutPool(f)) {
            Scene scene = new Scene(f, pool);
            long started = System.nanoTime();
            DataAccessException exhausted;
            try (Connection holder = scene.holdKrwBalance(20)) {
                exhausted = assertThrows(DataAccessException.class, () -> scene.settlement.appendAndSettle(scene.events));
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals("55P03", TransientDbErrors.sqlState(exhausted));
            assertEquals(1, scene.settlement.transientExhausted(), "한도를 넘긴 일시 오류를 소진으로 세지 않았다");
            assertTrue(elapsedMs >= 13_000 && elapsedMs < 19_000, "기한 15초 근처에서 멈추지 않았다: " + elapsedMs + "ms");
            assertEquals(0, f.count("SELECT count(*) FROM orders WHERE id = " + scene.orderId));
            assertFalse(scene.settlement.transientRetries() >= 8, "시도 상한 8회를 넘겼다");
        }
    }

    /** 운영 기본 풀(prod 프로필)과 같은 lock_timeout 2초의 정산 풀. */
    private static HikariDataSource prodLockTimeoutPool(ReservationFixture f) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(f.db.url());
        config.setUsername(PartitionLeaseDb.OWNER);
        config.setPassword(PartitionLeaseDb.OWNER);
        config.setMaximumPoolSize(4);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'; SET lock_timeout TO '2s'");
        return new HikariDataSource(config);
    }

    /** P1 리더 하나와 정산할 매수 한 건. 정산이 그 사용자의 KRW 잔고 행을 잡는다. */
    private static final class Scene {
        final ReservationFixture f;
        final LeaderLeaseService lease;
        final SettlementService settlement;
        final long user;
        final long orderId;
        final List<EngineEvent> events;

        Scene(ReservationFixture f, HikariDataSource pool) {
            this.f = f;
            this.user = f.user("budget", new BigDecimal("100000000"));
            this.lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, false);
            JdbcClient jdbc = JdbcClient.create(pool);
            this.settlement = new SettlementService(jdbc, new TransactionTemplate(new JdbcTransactionManager(pool)),
                    lease, new ObjectMapper(), new MarketCatalog(jdbc), f.mapper);
            var intake = f.limit(KRW_BTC, user, OrderCommand.Side.BUY, "50000", "0.1");
            this.orderId = intake.assignedOrderId();
            this.events = f.journalAndApply(appender, new MatchingEngineImpl(), intake);
        }

        /** 다른 세션이 그 사용자의 KRW 가용 잔고 행을 쥐고, seconds 뒤에 놓는다. 반환한 연결을 닫으면 곧바로 놓는다. */
        Connection holdKrwBalance(int seconds) throws Exception {
            Connection holder = f.db.owner();
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT balance FROM balances WHERE account_id = ? FOR UPDATE")) {
                ps.setLong(1, f.account(user, "AVAILABLE", KRW));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "잠글 잔고 행이 없다");
                }
            }
            ScheduledExecutorService release = Executors.newSingleThreadScheduledExecutor();
            release.schedule(() -> {
                try {
                    holder.rollback();
                } catch (Exception ignored) {
                    // 연결을 이미 닫았으면 잠금도 이미 풀렸다.
                }
            }, seconds, TimeUnit.SECONDS);
            release.shutdown();
            return holder;
        }
    }
}

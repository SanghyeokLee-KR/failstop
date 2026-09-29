package kr.exchange.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.journal.CommandJournalAppender;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정산 전 예약 감시 게이지(ADR 0034 S3, S3 설계 4-16, 11절 R8). 전용 실행기로 모든 노드에서 돌고 하네스 설정이 없어도 돈다.
 */
class ReservationBacklogMonitorTest {

    @Test
    void gaugesFollowUnsettledReservationsAndFailToNaN() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("backlog_gauge", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            SettlementService settlement = f.settlement(lease);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ReservationBacklogMonitor monitor = new ReservationBacklogMonitor(f.app, f.appTx, registry,
                    Duration.ofMillis(200));

            monitor.refresh();
            assertEquals(0.0, monitor.pending());
            assertEquals(0.0, monitor.oldestSeconds());

            List<EngineEvent> pending = f.journalAndApply(appender, new MatchingEngineImpl(),
                    f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.3"));
            monitor.refresh();
            assertEquals(1.0, monitor.pending(), "정산 전 예약을 세지 않았다");
            Thread.sleep(2_100);
            monitor.refresh();
            assertTrue(monitor.oldestSeconds() >= 2.0, "가장 오래된 예약의 나이가 늘지 않았다: " + monitor.oldestSeconds());
            assertEquals(1.0, registry.get("exchange.reservation.unsettled.count").gauge().value());

            settlement.appendAndSettle(pending);
            monitor.refresh();
            assertEquals(0.0, monitor.pending());
            assertEquals(0.0, monitor.oldestSeconds());

            // 하네스 설정 없이 전용 실행기로 돈다. 주기 계산이 게이지를 다시 채우는지 본다.
            f.journalAndApply(appender, new MatchingEngineImpl(),
                    f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1"));
            monitor.start();
            try {
                for (int i = 0; i < 50 && monitor.pending() != 1.0; i++) {
                    Thread.sleep(100);
                }
                assertEquals(1.0, monitor.pending(), "전용 실행기가 게이지를 계산하지 않았다");
            } finally {
                monitor.close();
            }

            // 질의 실패(저널을 읽을 권한이 없는 조회 로그인, provision-runtime-roles.sql)는 NaN 과 실패 계수다.
            try (var readerPool = new com.zaxxer.hikari.HikariDataSource()) {
                readerPool.setJdbcUrl(f.db.url());
                readerPool.setUsername(kr.exchange.engine.lease.PartitionLeaseDb.READER);
                readerPool.setPassword("s2-read");
                ReservationBacklogMonitor failing = new ReservationBacklogMonitor(JdbcClient.create(readerPool),
                        new TransactionTemplate(new JdbcTransactionManager(readerPool)), new SimpleMeterRegistry());
                failing.refresh();
                assertTrue(Double.isNaN(failing.pending()));
                assertTrue(Double.isNaN(failing.oldestSeconds()));
                assertEquals(1.0, failing.queryFailures());
            }
        }
    }

    /**
     * 2026-09-27 시연 스택의 모양을 줄여 만든다. 명령은 옛 전역 seq 잎(p0)에 세 마켓이 섞여 있고, 이벤트는 마켓 대역 잎에 한 마켓씩 있으며,
     * 나머지 아홉 마켓은 비었다. 게이지와 C14' 는 이 모양에서도 대기 명령만 읽어야 한다(S3 설계 3절 P8).
     */
    @Test
    void backlogQueriesDoNotScanJournalsWhenMostMarketsAreIdle() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("backlog_plan");
        db.seedMarkets(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        db.exec("""
                INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                                             engine_ts, tie_break, payload, fencing_token, checksum)
                SELECT g, (ARRAY[1, 2, 4])[1 + g % 3], CASE WHEN g % 7 = 0 THEN 'CANCEL' ELSE 'NEW' END, 1,
                       CASE WHEN g % 7 = 0 THEN NULL ELSE 'c' || g END, g, g, g, '{}', 1, 0
                FROM generate_series(1, 200000) g
                """);
        db.exec("""
                INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch,
                                           created_at, market_id, market_seq)
                SELECT market_id * 1000000000000 + n, seq, 0,
                       CASE command_type WHEN 'NEW' THEN 'ACCEPTED' ELSE 'CANCELED' END, '{}', 1, now(), market_id, n
                FROM (SELECT c.*, row_number() OVER (PARTITION BY c.market_id ORDER BY c.seq) AS n
                      FROM command_journal c) c
                """);
        long user;
        try (HikariDataSource pool = db.ownerPool()) {
            AccountBootstrap bootstrap = new AccountBootstrap(JdbcClient.create(pool));
            user = bootstrap.user("backlog@plan.test");
            bootstrap.userAccounts(user, 1);
        }
        long locked = db.ownerLong("SELECT id FROM accounts WHERE owner_type = 'USER' AND owner_id = " + user
                + " AND purpose = 'LOCKED' AND asset_id = 1 AND shard = 0");
        // 정산 전 예약 둘(마켓 1 의 커서 위, 이벤트가 하나도 없는 마켓 7)과 커서 아래에서 정산이 끝난 예약 하나(마켓 2).
        db.exec("""
                INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                                             engine_ts, tie_break, payload, fencing_token, checksum)
                VALUES (200001, 1, 'NEW', 1, 'p1', 200001, 200001, 200001, '{}', 1, 0),
                       (200002, 7, 'NEW', 1, 'p7', 200002, 200002, 200002, '{}', 1, 0)
                """);
        db.exec("""
                INSERT INTO order_reservations (command_seq, order_id, user_id, market_id, asset_id, available_account_id,
                                                locked_account_id, amount, ledger_txn_id, payload_sha256, created_at)
                VALUES (4, 4, %1$d, 2, 1, %2$d, %2$d, 1, 1, decode('00', 'hex'), now()),
                       (200001, 200001, %1$d, 1, 1, %2$d, %2$d, 1, 2, decode('00', 'hex'), now() - interval '90 seconds'),
                       (200002, 200002, %1$d, 7, 1, %2$d, %2$d, 1, 3, decode('00', 'hex'), now())
                """.formatted(user, locked));
        db.exec("ANALYZE");
        long pages = db.ownerLong("SELECT relpages FROM pg_class WHERE relname = 'command_journal_p0'");
        assertTrue(pages > 2_000, "재현 규모가 작아 잎 훑기와 색인 탐색이 구별되지 않는다: " + pages);

        try (HikariDataSource pool = db.ownerPool()) {
            JdbcClient jdbc = JdbcClient.create(pool);
            TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(pool));
            ReservationBacklogMonitor monitor = new ReservationBacklogMonitor(jdbc, tx, new SimpleMeterRegistry());
            monitor.refresh();
            assertEquals(2.0, monitor.pending(), "커서 위 예약과 이벤트 없는 마켓의 예약을 함께 세야 한다");
            assertTrue(monitor.oldestSeconds() >= 90.0, "가장 오래된 예약의 나이: " + monitor.oldestSeconds());
            // LOCKED 잔고 0 에 정산 전 예약 2 가 붙어 한 계정이 어긋난다. 예약 항이 실제로 돌았다는 양성 증거다.
            assertEquals(1, jdbc.sql(InvariantCheck.C14_LOCKED_RESERVATION.sql()).query(Long.class).single());

            long gauge = sharedBlocks(jdbc, tx, ReservationBacklogMonitor.QUERY);
            long c14 = sharedBlocks(jdbc, tx, InvariantCheck.C14_LOCKED_RESERVATION.sql());
            assertAll(
                    () -> assertTrue(gauge < pages / 2, "게이지가 저널 잎을 훑었다(공유 버퍼 " + gauge + ", p0 잎 " + pages + "쪽)"),
                    () -> assertTrue(c14 < pages / 2, "C14' 가 저널 잎을 훑었다(공유 버퍼 " + c14 + ", p0 잎 " + pages + "쪽)"));
        }
    }

    /** 앱과 같은 설정(트랜잭션 안에서 JIT 끔)으로 문장을 실행해 공유 버퍼 접근 수(적중과 읽기의 합)를 낸다. */
    private static long sharedBlocks(JdbcClient jdbc, TransactionTemplate tx, String sql) throws Exception {
        String plan = tx.execute(status -> {
            jdbc.sql("SET LOCAL jit = off").update();
            return jdbc.sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql).query(String.class).single();
        });
        JsonNode top = new ObjectMapper().readTree(plan).get(0).get("Plan");
        return top.get("Shared Hit Blocks").asLong() + top.get("Shared Read Blocks").asLong();
    }
}

package kr.exchange.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.harness.InvariantCheck;
import kr.exchange.harness.S7Audit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 일회성 측정(2026-09-27). ReservationBacklogMonitorTest 와 같은 합성 데이터에서 옛·새 문장의 공유 버퍼를 잰다. 커밋하지 않는다. */
class TmpBacklogBlocksProbeTest {

    @Test
    void probe() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("backlog_probe");
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
        String dir = "harness/evidence/reservation-backlog-plan-20260927/sql/";
        String gaugeBefore = Files.readString(S7Audit.repoPath(dir + "gauge-before.sql"), StandardCharsets.UTF_8);
        String c14Before = Files.readString(S7Audit.repoPath(dir + "c14-before.sql"), StandardCharsets.UTF_8);

        try (HikariDataSource pool = db.ownerPool()) {
            JdbcClient jdbc = JdbcClient.create(pool);
            TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(pool));
            System.out.println("PROBE p0_pages=" + pages + " threshold=" + pages / 2);
            System.out.println("PROBE gauge_before=" + sharedBlocks(jdbc, tx, gaugeBefore));
            System.out.println("PROBE gauge_after=" + sharedBlocks(jdbc, tx, ReservationBacklogMonitor.QUERY));
            System.out.println("PROBE c14_before=" + sharedBlocks(jdbc, tx, c14Before));
            System.out.println("PROBE c14_after=" + sharedBlocks(jdbc, tx, InvariantCheck.C14_LOCKED_RESERVATION.sql()));
            System.out.println("PROBE c14_before_value=" + jdbc.sql(c14Before).query(Long.class).single()
                    + " c14_after_value=" + jdbc.sql(InvariantCheck.C14_LOCKED_RESERVATION.sql()).query(Long.class).single());
            System.out.println("PROBE gauge_before_value=" + jdbc.sql(gaugeBefore)
                    .query((rs, n) -> rs.getLong("pending") + "/" + rs.getLong("oldest_s")).single()
                    + " gauge_after_value=" + jdbc.sql(ReservationBacklogMonitor.QUERY)
                    .query((rs, n) -> rs.getLong("pending") + "/" + rs.getLong("oldest_s")).single());
        }
    }

    private static long sharedBlocks(JdbcClient jdbc, TransactionTemplate tx, String sql) throws Exception {
        String plan = tx.execute(status -> {
            jdbc.sql("SET LOCAL jit = off").update();
            return jdbc.sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql).query(String.class).single();
        });
        JsonNode top = new ObjectMapper().readTree(plan).get(0).get("Plan");
        return top.get("Shared Hit Blocks").asLong() + top.get("Shared Read Blocks").asLong();
    }
}

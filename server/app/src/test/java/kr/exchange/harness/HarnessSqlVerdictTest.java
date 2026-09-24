package kr.exchange.harness;

import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.api.transfer.TransferService;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.engine.lease.ReservationFixture;
import kr.exchange.journal.AppendResult;
import kr.exchange.journal.CommandDraft;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.JournalChecksum;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.ledger.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static kr.exchange.engine.lease.ReservationFixture.KRW_BTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 하네스 SQL 두 벌의 S3 판정(ADR 0034 S3, S3 설계 4-12·6-2, 하네스 검증설계 §4-1). C29·C31 의 N/A·PASS·FAIL 판정 어휘,
 * C14' 의 두 판(예약 항 유무)과 정산 커서 경계, C31 앱 판과 전수 판의 차이를 결과로 대조한다.
 */
class HarnessSqlVerdictTest {

    // 예약 항을 표 전체 반조인으로 본 첫 판 C14'(S3 설계 3절 C21 의 대조 기준). 커서 판과 경계만 다르다.
    private static final String C14_FULL_ANTI_JOIN = """
            SELECT count(*) FROM (
                SELECT a.id FROM accounts a JOIN balances b ON b.account_id = a.id
                LEFT JOIN (SELECT locked_account_id AS account_id, SUM(locked_amount) AS amt FROM orders
                           WHERE status IN ('PENDING','OPEN','PARTIAL') GROUP BY locked_account_id) o
                       ON o.account_id = a.id
                LEFT JOIN (SELECT r.locked_account_id AS account_id, SUM(r.amount) AS amt FROM order_reservations r
                           WHERE NOT EXISTS (SELECT 1 FROM event_emission_registry e
                                              WHERE e.source_cmd_seq = r.command_seq)
                           GROUP BY r.locked_account_id) u ON u.account_id = a.id
                LEFT JOIN (SELECT la.id AS account_id, SUM(t.amount) AS amt FROM transfer_requests t
                           JOIN accounts la ON la.owner_type = 'USER' AND la.owner_id = t.user_id
                                           AND la.purpose = 'LOCKED' AND la.asset_id = t.asset_id AND la.shard = 0
                           WHERE t.direction = 'WITHDRAW' AND t.status = 'REQUESTED' GROUP BY la.id) w
                       ON w.account_id = a.id
                WHERE a.purpose = 'LOCKED'
                  AND b.balance <> COALESCE(o.amt, 0) + COALESCE(u.amt, 0) + COALESCE(w.amt, 0)) x
            """;

    // 개정 전 C14(열린 주문 합만). 대기 출금이 있는 계정을 위반으로 셌다(음성 대조).
    private static final String C14_BEFORE_S3 = """
            SELECT count(*) FROM (
                SELECT b.account_id FROM accounts a JOIN balances b ON b.account_id = a.id
                LEFT JOIN orders o ON o.locked_account_id = a.id AND o.status IN ('PENDING','OPEN','PARTIAL')
                WHERE a.purpose = 'LOCKED'
                GROUP BY b.account_id, b.balance
                HAVING b.balance <> COALESCE(SUM(o.locked_amount), 0)) x
            """;

    @Test
    void c29AndC31JudgeNotApplicablePassAndFailBySchema() throws Exception {
        PartitionLeaseDb v47 = PartitionLeaseDb.createAt("verdict_v47", "47");
        v47.seedMarkets(1, 2);
        PartitionLeaseDb v48 = PartitionLeaseDb.createAt("verdict_v48", "48");
        v48.seedMarkets(1, 2);
        PartitionLeaseDb broken = PartitionLeaseDb.createAt("verdict_v48_broken", "48");
        broken.seedMarkets(1, 2);
        broken.exec("DROP TABLE leader_protocol_history");
        PartitionLeaseDb v50 = PartitionLeaseDb.create("verdict_v50");
        v50.seedMarkets(1, 2);

        Map<String, String> s47 = summaryByCode(v47);
        Map<String, String> s48 = summaryByCode(v48);
        Map<String, String> sBroken = summaryByCode(broken);
        Map<String, String> s50 = summaryByCode(v50);
        assertEquals(s50.keySet(), s47.keySet(), "V47 DB 에서 빠진 판정이 있다");
        assertEquals(s50.keySet(), s48.keySet());
        assertEquals(s50.keySet(), sBroken.keySet());
        for (String code : s50.keySet()) {
            if (!code.equals("C29") && !code.equals("C31")) {
                assertEquals(s50.get(code), s47.get(code), code);
                assertEquals(s50.get(code), s48.get(code), code);
                assertEquals(s50.get(code), sBroken.get(code), code);
            }
        }
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS||N/A", s47.get("C29"));
        assertEquals("C31|RESERVATION_LINK|CRITICAL|ALWAYS||N/A", s47.get("C31"));
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS|0|PASS", s48.get("C29"));
        assertEquals("C31|RESERVATION_LINK|CRITICAL|ALWAYS||N/A", s48.get("C31"));
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS|1|FAIL", sBroken.get("C29"),
                "V48 객체가 일부만 있으면 FAIL 이고 violated_rows 는 없는 객체 수다");
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS|0|PASS", s50.get("C29"));
        assertEquals("C31|RESERVATION_LINK|CRITICAL|ALWAYS|0|PASS", s50.get("C31"));
        // N/A 는 C29·C31 밖에서 나오지 않는다.
        for (Map<String, String> rows : List.of(s47, s48, sBroken, s50)) {
            rows.forEach((code, line) -> assertTrue(!line.endsWith("|N/A") || code.equals("C29") || code.equals("C31"),
                    line));
        }

        List<String> h47 = headers(v47.psql("invariants.sql"));
        List<String> h50 = headers(v50.psql("invariants.sql"));
        assertEquals(h50.stream().map(HarnessSqlVerdictTest::headerCode).toList(),
                h47.stream().map(HarnessSqlVerdictTest::headerCode).toList(), "V47 DB 에서 빠진 판정 블록이 있다");
        assertEquals(List.of("C29", "C31"), marked(h47, "N/A"), String.join("\n", h47));
        assertEquals(List.of("C31"), marked(headers(v48.psql("invariants.sql")), "N/A"));
        assertEquals(List.of("C29"), marked(headers(broken.psql("invariants.sql")), "FAIL"));
        assertEquals(List.of(), marked(h50, "N/A"));
        assertEquals(List.of(), marked(h50, "SKIP"), "V50 DB 에서 미평가 블록이 있다");
    }

    /** C14' 는 예약 항 유무 두 판이 예약 없는 DB 에서 같은 값이고, 대기 출금이 있어도 0 이다. 개정 전 판은 1 이다. */
    @Test
    void c14PrimeCountsPendingWithdrawalsInBothVariants() throws Exception {
        PartitionLeaseDb v48 = PartitionLeaseDb.createAt("c14p_v48", "48");
        PartitionLeaseDb v50 = PartitionLeaseDb.create("c14p_v50");
        for (PartitionLeaseDb db : List.of(v48, v50)) {
            try (HikariDataSource pool = db.ownerPool()) {
                JdbcClient jdbc = JdbcClient.create(pool);
                AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
                bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
                long user = bootstrap.user("withdraw@c14p.test");
                bootstrap.userAccounts(user, 1);
                bootstrap.deposit(user, 1, new BigDecimal("10000"));
                new TransferService(jdbc, new TransactionTemplate(new JdbcTransactionManager(pool)))
                        .request(user, "WITHDRAW", "KRW", "3000", "c14p-1");
                assertEquals(1, jdbc.sql(C14_BEFORE_S3).query(Long.class).single(),
                        "개정 전 C14 가 대기 출금을 위반으로 세지 않았다(음성 대조가 성립하지 않는다)");
            }
            assertEquals("C14|LOCKED_RESERVATION|HIGH|ALWAYS|0|PASS", summaryByCode(db).get("C14"), db.name());
        }
        try (HikariDataSource pool = v50.ownerPool()) {
            assertEquals(0, JdbcClient.create(pool).sql(InvariantCheck.C14_LOCKED_RESERVATION.sql())
                    .query(Long.class).single());
        }
    }

    /**
     * 정산 커서 경계(S3 설계 3절 C21, 11절 R5). 커서 위 정산 전 예약은 커서 판과 첫 판 모두 0 이고, 정산 틈(같은 마켓의 뒤
     * 예약만 정산해 앞 예약이 커서 아래에 남음)은 커서 판만 초과 LOCKED 1 을 낸다. 하네스 SQL 과 앱 enum 이 같은 값이다.
     */
    @Test
    void c14PrimeCursorBoundaryIsConservative() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("c14p_cursor", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            SettlementService settlement = f.settlement(lease);
            MatchingEngineImpl engine = new MatchingEngineImpl();
            AppendResult first = f.append(appender, f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1"));
            assertEquals(0, f.invariant(InvariantCheck.C14_LOCKED_RESERVATION), "커서 위 정산 전 예약을 위반으로 셌다");
            assertEquals(0, f.count(C14_FULL_ANTI_JOIN));
            assertEquals("0", summaryByCode(f.db).get("C14").split("\\|")[4]);

            AppendResult second = f.append(appender, f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.2"));
            settlement.appendAndSettle(f.apply(engine, second.seq(), KRW_BTC));
            assertTrue(first.seq() < second.seq());
            assertEquals(1, f.invariant(InvariantCheck.C14_LOCKED_RESERVATION), "정산 틈을 초과 LOCKED 로 드러내지 않았다");
            assertEquals(0, f.count(C14_FULL_ANTI_JOIN), "첫 판은 정산 틈을 정상으로 본다");
            assertEquals("C14|LOCKED_RESERVATION|HIGH|ALWAYS|1|FAIL", summaryByCode(f.db).get("C14"));

            settlement.appendAndSettle(f.apply(new MatchingEngineImpl(), first.seq(), KRW_BTC));
            assertEquals(0, f.invariant(InvariantCheck.C14_LOCKED_RESERVATION));
        }
    }

    /** C31 앱 판은 창 안의 예약 없는 P2 NEW 를 세고, 저널에 없는 예약 행(고아)은 전수 판(하네스 SQL)만 센다. */
    @Test
    void c31AppWindowAndFullVariantDifferOnlyOnOrphans() throws Exception {
        try (ReservationFixture f = ReservationFixture.create("c31_variants", 2)) {
            long alice = f.user("alice", new BigDecimal("1000000"));
            LeaderLeaseService lease = f.lease();
            CommandJournalAppender appender = f.appender(lease, true);
            f.submit(appender, new MatchingEngineImpl(), f.settlement(lease),
                    f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1"));
            assertEquals(0, c31App(f));
            assertEquals("0", summaryByCode(f.db).get("C31").split("\\|")[4]);

            // (b) P2 epoch 로 기록된 예약 없는 NEW. 소유자가 저널 행 함수를 거치지 않고 넣는다.
            long seq = f.owner.sql("SELECT next_journal_value('command_seq')").query(Long.class).single();
            var intake = f.limit(KRW_BTC, alice, OrderCommand.Side.BUY, "50000", "0.1");
            CommandDraft draft = f.draft(intake);
            f.owner.sql("""
                            INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                         assigned_order_id, engine_ts, tie_break, payload, fencing_token,
                                                         checksum)
                            VALUES (?, ?, 'NEW', ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                            """)
                    .params(seq, draft.marketId(), draft.userId(), draft.clientOrderId(), draft.assignedOrderId(),
                            draft.engineTs(), draft.tieBreak(), draft.payloadJson(), lease.requireLeader().epoch(),
                            JournalChecksum.of(seq, draft))
                    .update();
            assertEquals(1, c31App(f), "앱 판이 창 안의 예약 없는 P2 NEW 를 세지 않았다");
            assertEquals("1", summaryByCode(f.db).get("C31").split("\\|")[4]);

            // (c) 저널에 없는 예약 행(고아)은 전수 판만 센다. 원장 대조 (a)는 통과하도록 정상 예약 행을 seq 만 바꿔 복사한다.
            f.owner.sql("""
                    INSERT INTO order_reservations (command_seq, order_id, user_id, market_id, asset_id,
                                                    available_account_id, locked_account_id, amount, ledger_txn_id,
                                                    payload_sha256, created_at)
                    SELECT 987654321, order_id, user_id, market_id, asset_id, available_account_id, locked_account_id,
                           amount, ledger_txn_id, payload_sha256, created_at
                    FROM order_reservations ORDER BY command_seq LIMIT 1
                    """).update();
            assertEquals(1, c31App(f), "앱 판은 고아 예약 행을 보지 않는다(창의 NEW 에 붙은 행만 본다)");
            assertEquals("C31|RESERVATION_LINK|CRITICAL|ALWAYS|2|FAIL", summaryByCode(f.db).get("C31"));
        }
    }

    private static long c31App(ReservationFixture f) {
        return f.owner.sql(InvariantCheck.valueOf("C31_RESERVATION_LINK").sql()).query(Long.class).single();
    }

    /** summary.sql 의 판정 행을 코드별로 모은다. 한 코드가 두 번 나오면 실패다. */
    static Map<String, String> summaryByCode(PartitionLeaseDb db) throws Exception {
        Map<String, String> rows = new TreeMap<>();
        for (String line : db.psql("summary.sql")) {
            String[] f = line.split("\\|", -1);
            assertEquals(6, f.length, line);
            assertTrue(List.of("PASS", "FAIL", "SKIP", "N/A").contains(f[5]), line);
            assertNull(rows.put(f[0], line), "판정 코드 중복: " + line);
        }
        return rows;
    }

    private static List<String> headers(List<String> lines) {
        return lines.stream().filter(line -> line.startsWith("== C")).toList();
    }

    private static String headerCode(String header) {
        return header.substring(3, header.indexOf(' ', 3));
    }

    private static List<String> marked(List<String> headers, String verdict) {
        return headers.stream().filter(h -> h.contains("] " + verdict + ":")).map(HarnessSqlVerdictTest::headerCode)
                .toList();
    }
}

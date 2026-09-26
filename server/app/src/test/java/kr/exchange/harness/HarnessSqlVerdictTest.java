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
import static kr.exchange.harness.S7Audit.acquire;
import static kr.exchange.harness.S7Audit.cmd;
import static kr.exchange.harness.S7Audit.evt;
import static kr.exchange.harness.S7Audit.release;
import static kr.exchange.harness.S7Audit.snap;
import static kr.exchange.harness.S7Audit.trade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 하네스 SQL 두 벌의 S3 판정(ADR 0034 S3, S3 설계 4-12·6-2, 하네스 검증설계 §4-1). C29·C30·C31 의 N/A·PASS·FAIL 판정 어휘,
 * C14' 의 두 판(예약 항 유무)과 정산 커서 경계, C31 앱 판과 전수 판의 차이를 결과로 대조한다.
 *
 * S7(ADR 0034 S7, S7 설계 6-2)은 C22' 와 C30 을 더한다. 앱 enum·summary.sql·invariants.sql 의 값이 DB 설계 §6.4 ⑯·⑳ 목록 판의
 * 행 수와 모든 단계에서 같은지 설계 3절 P7-5·R7 의 픽스처로 본다. 사후 감사가 세지 못하는 두 경우(전환을 가로지른 트랜잭션,
 * 3->2 뒤 옛 P3 epoch 의 이벤트·체결)는 음성 대조로 고정하고, 그때 구간 경계 증분 표본(segment-old-rows.sql)이 느는 것을 양성
 * 대조로 본다.
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

    private static final String ME = "matching-engine";
    private static final String P0 = "matching-p0";
    private static final String P1 = "matching-p1";

    @Test
    void c29C30AndC31JudgeNotApplicablePassAndFailBySchema() throws Exception {
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
        // C22 는 V48 객체가 모두 있으면 C22' 판, 아니면 개정 전 판으로 돌고 위반 없는 DB 에서 같은 값이다(ADR 0034 S7).
        for (String code : s50.keySet()) {
            if (!HarnessSqlCodes.NOT_APPLICABLE.contains(code)) {
                assertEquals(s50.get(code), s47.get(code), code);
                assertEquals(s50.get(code), s48.get(code), code);
                assertEquals(s50.get(code), sBroken.get(code), code);
            }
        }
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS||N/A", s47.get("C29"));
        assertEquals("C30|FENCING_RESOURCE_MATCH|CRITICAL|CONDITIONAL||N/A", s47.get("C30"));
        assertEquals("C31|RESERVATION_LINK|CRITICAL|ALWAYS||N/A", s47.get("C31"));
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS|0|PASS", s48.get("C29"));
        assertEquals("C30|FENCING_RESOURCE_MATCH|CRITICAL|CONDITIONAL|0|PASS", s48.get("C30"));
        assertEquals("C31|RESERVATION_LINK|CRITICAL|ALWAYS||N/A", s48.get("C31"));
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS|1|FAIL", sBroken.get("C29"),
                "V48 객체가 일부만 있으면 FAIL 이고 violated_rows 는 없는 객체 수다");
        assertEquals("C30|FENCING_RESOURCE_MATCH|CRITICAL|CONDITIONAL|1|FAIL", sBroken.get("C30"),
                "C30 은 C29 와 같은 V48 객체 다섯을 읽는다");
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS|0|PASS", s50.get("C29"));
        assertEquals("C30|FENCING_RESOURCE_MATCH|CRITICAL|CONDITIONAL|0|PASS", s50.get("C30"));
        assertEquals("C31|RESERVATION_LINK|CRITICAL|ALWAYS|0|PASS", s50.get("C31"));
        // N/A 는 공용 목록(C29·C30·C31) 밖에서 나오지 않는다.
        for (Map<String, String> rows : List.of(s47, s48, sBroken, s50)) {
            rows.forEach((code, line) -> assertTrue(!line.endsWith("|N/A")
                    || HarnessSqlCodes.NOT_APPLICABLE.contains(code), line));
        }
        assertEquals(33, s50.size(), "summary.sql 판정 행 수(하네스 검증설계 §4-1 개수: SQL 판정 블록 33)");

        List<String> h47 = headers(v47.psql("invariants.sql"));
        List<String> h50 = headers(v50.psql("invariants.sql"));
        assertEquals(h50.stream().map(HarnessSqlVerdictTest::headerCode).toList(),
                h47.stream().map(HarnessSqlVerdictTest::headerCode).toList(), "V47 DB 에서 빠진 판정 블록이 있다");
        assertEquals(HarnessSqlCodes.NOT_APPLICABLE, marked(h47, "N/A"), String.join("\n", h47));
        assertEquals(HarnessSqlCodes.V50_OBJECTS, marked(headers(v48.psql("invariants.sql")), "N/A"));
        assertEquals(HarnessSqlCodes.V48_OBJECTS, marked(headers(broken.psql("invariants.sql")), "FAIL"));
        assertEquals(List.of(), marked(h50, "N/A"));
        assertEquals(List.of(), marked(h50, "SKIP"), "V50 DB 에서 미평가 블록이 있다");
    }

    /**
     * P7-5 교차 행과 되돌리기(S7 설계 3절, 6-2). P2 행 뒤 2->3 에서 두 파티션 리더가 번갈아 쓰면 개정 전 C22 는 split-brain 없이
     * 거짓 위반 2 를 내고 C22' 는 0 이다. 3->2 되돌리기 뒤 P2 행과 2->3 재진입 뒤 P3 행에서도 0 이다. 네 평가기가 모든 단계에서
     * 같고, 전환마다 전환 직후 뜬 구간 경계 증분 표본이 끝까지 그대로다.
     */
    @Test
    void c22PrimeSeparatesPartitionsAcrossCutoverRollbackAndReentry() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("s7_audit_clean");
        db.seedMarkets(1, 2, 3);
        S7Audit.installProbe(db);
        assertClean(S7Audit.take(db).agreed("P1 빈 DB"));
        Map<Long, Long> samples = new TreeMap<>();

        assertEquals(3, db.switchProtocol(2, false));
        samples.putAll(sampleLast(db));
        db.exec("SELECT latch_command_band_cutover()");
        long me = acquire(db, ME, "N1");
        cmd(db, 1, me);
        cmd(db, 2, me);
        evt(db, 1, me, 101);
        snap(db, 1, me);
        trade(db, 2, me);
        release(db, ME, "N1", me);
        assertClean(S7Audit.take(db).agreed("P2 행"));

        assertEquals(3, db.switchProtocol(3, false));
        samples.putAll(sampleLast(db));
        long p0 = acquire(db, P0, "N1");
        long p1 = acquire(db, P1, "N2");
        for (int i = 0; i < 5; i++) {
            cmd(db, i % 2 == 0 ? 1 : 2, i % 2 == 0 ? p0 : p1);
        }
        evt(db, 1, p0, 102);
        snap(db, 1, p0);
        snap(db, 2, p1);
        trade(db, 2, p1);
        assertClean(S7Audit.take(db).agreed("P3 교차 행"));
        try (java.sql.Connection c = db.owner()) {
            assertEquals(2, S7Audit.c22Before(c),
                    "개정 전 C22 가 두 파티션의 번갈아 쓴 행을 거짓 위반으로 세지 않았다(음성 대조가 성립하지 않는다)");
        }

        assertEquals(3, db.switchProtocol(2, true));
        samples.putAll(sampleLast(db));
        long me2 = acquire(db, ME, "N1");
        cmd(db, 1, me2);
        cmd(db, 2, me2);
        cmd(db, 3, me2);
        assertClean(S7Audit.take(db).agreed("3->2 뒤 P2 행"));
        release(db, ME, "N1", me2);

        assertEquals(3, db.switchProtocol(3, false));
        samples.putAll(sampleLast(db));
        long p0b = acquire(db, P0, "N2");
        long p1b = acquire(db, P1, "N1");
        cmd(db, 2, p1b);
        cmd(db, 1, p0b);
        cmd(db, 3, p1b);
        snap(db, 3, p1b);
        S7Audit.Snapshot end = S7Audit.take(db).agreed("재진입 뒤 P3 행");
        assertClean(end);
        assertEquals(4, samples.size(), "전환 넷(1->2, 2->3, 3->2, 2->3)의 표본");
        assertEquals(samples, end.oldRows(), "전환 뒤 옛 구간 epoch 행이 늘었다(구간 경계 증분)");
    }

    /**
     * P7-5 주입(S7 설계 3절). 쓰기 판정을 우회한 다섯 결함을 맞는 팔이 센다. 같은 파티션의 역행은 C22' 시간 축·마켓 안 팔,
     * 파티션을 넘는 쓰기(네 표)는 C30, 2->3 뒤 옛 구간(P2) epoch 쓰기는 구간 경계 팔, 퇴역 epoch 와 획득 이력 없는 epoch 는 C30 이다.
     * 주입은 마켓을 나눠 서로 겹치지 않게 한다(V48 대응: 1·4·5·7 p0, 2·3·6 p1).
     */
    @Test
    void injectedStaleAndCrossPartitionWritesAreCountedByTheRightArm() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("s7_audit_inject");
        db.seedMarkets(1, 2, 3, 4, 5, 6, 7);
        S7Audit.installProbe(db);
        db.switchProtocol(2, false);
        db.exec("SELECT latch_command_band_cutover()");
        long me = acquire(db, ME, "N1");
        cmd(db, 1, me);
        cmd(db, 2, me);
        release(db, ME, "N1", me);
        db.switchProtocol(3, false);
        long h23 = S7Audit.lastHistoryId(db);
        long p0 = acquire(db, P0, "N1");
        long p1 = acquire(db, P1, "N2");
        cmd(db, 1, p0);
        cmd(db, 2, p1);
        cmd(db, 1, p0);
        cmd(db, 2, p1);
        S7Audit.Snapshot base = S7Audit.take(db).agreed("P3 행");
        assertClean(base);

        // V1: p0 를 N2 가 새 epoch 로 넘겨받은 뒤 옛 p0 epoch 로 마켓 4(p0)에 쓴다.
        release(db, P0, "N1", p0);
        long p0n = acquire(db, P0, "N2");
        cmd(db, 4, p0n);
        cmd(db, 4, p0);
        S7Audit.Snapshot v1 = S7Audit.take(db).agreed("V1 같은 파티션 역행");
        assertEquals(base.arm("time", "command_journal") + 1, v1.arm("time", "command_journal"));
        assertEquals(base.arm("market", "command_journal") + 1, v1.arm("market", "command_journal"));
        assertEquals(base.c30Listing(), v1.c30Listing(), "같은 파티션의 역행은 C30 이 아니라 C22' 가 센다");

        // V2: p1 마켓 6 에 p0 epoch 로 네 표를 쓴다(파티션을 넘는 쓰기).
        long v2 = cmd(db, 6, p0n);
        evt(db, 6, p0n, v2);
        snap(db, 6, p0n);
        trade(db, 6, p0n);
        S7Audit.Snapshot s2 = S7Audit.take(db).agreed("V2 파티션을 넘는 쓰기");
        for (String tbl : List.of("command_journal", "event_journal", "trades", "snapshots")) {
            assertEquals(v1.c30(tbl) + 1, s2.c30(tbl), "C30 이 " + tbl + " 의 파티션을 넘는 쓰기를 세지 않았다");
        }
        assertEquals(v1.c22Listing(), s2.c22Listing(), "파티션별 시간 축 팔은 파티션을 넘는 쓰기를 보지 못한다(C30 이 맡는다)");

        // V3: 2->3 뒤 새 트랜잭션이 옛 구간(P2) epoch 로 새 구간 행이 없는 마켓 3 에 쓴다.
        cmd(db, 3, me);
        S7Audit.Snapshot s3 = S7Audit.take(db).agreed("V3 옛 구간 epoch");
        assertEquals(s2.arm("boundary", "command_journal") + 1, s3.arm("boundary", "command_journal"));
        assertEquals(s2.c22Listing() + 1, s3.c22Listing(), "시간 축·마켓 안 팔이 이 행을 셌다(앞 행이 없는 마켓이다)");
        assertEquals(s2.c30Listing(), s3.c30Listing());
        assertEquals(s2.oldRows().get(h23) + 1, s3.oldRows().get(h23), "구간 경계 증분이 옛 구간 행을 세지 않았다");

        // V4: 2->3 이 퇴역시킨 p0 epoch 로 마켓 5(p0)에 쓴다. V5: 획득 이력이 없는 P3 범위 epoch 로 마켓 7(p0)에 쓴다.
        long retired = S7Audit.exec(db, "SELECT max(epoch) FROM lease_acquisitions WHERE resource = 'matching-p0'"
                + " AND origin = 'RETIRE' AND protocol = 3");
        cmd(db, 5, retired);
        S7Audit.Snapshot s4 = S7Audit.take(db).agreed("V4 퇴역 epoch");
        assertEquals(s3.c30("command_journal") + 1, s4.c30("command_journal"), "퇴역 epoch 쓰기를 C30 이 세지 않았다");
        long unknown = S7Audit.exec(db, "SELECT max(epoch) + 1000 FROM lease_acquisitions");
        cmd(db, 7, unknown);
        S7Audit.Snapshot s5 = S7Audit.take(db).agreed("V5 이력 없는 epoch");
        assertEquals(s4.c30("command_journal") + 1, s5.c30("command_journal"), "획득 이력 없는 epoch 쓰기를 C30 이 세지 않았다");
    }

    /**
     * R7 경계(S7 설계 3절 R7-3~R7-5, 11절 지적 2·9). 2->3 뒤 옛 구간 epoch 로 쓴 스냅샷·명령은 구간 경계 팔이 센다. 3->2 를 가로지른
     * 트랜잭션(전환 전에 시작해 전환 뒤 커밋)은 구간 경계 팔이 세지 못하고, 새 구간 행이 먼저 커밋된 마켓만 마켓 안 팔이 센다.
     * 3->2 뒤 옛 P3 epoch 로 쓴 이벤트·체결은 C22'·C30 이 세지 못한다. 두 빈틈 모두 구간 경계 증분 표본은 센다.
     */
    @Test
    void cutoverStraddlingTransactionsAndOldSegmentEventsAreKnownAuditGaps() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("s7_audit_boundary");
        db.seedMarkets(1, 2, 3);
        S7Audit.installProbe(db);
        db.switchProtocol(2, false);
        db.exec("SELECT latch_command_band_cutover()");
        long me = acquire(db, ME, "N1");
        cmd(db, 1, me);
        cmd(db, 2, me);
        evt(db, 1, me, 101);
        snap(db, 1, me);
        trade(db, 2, me);
        release(db, ME, "N1", me);
        db.switchProtocol(3, false);
        long h23 = S7Audit.lastHistoryId(db);
        long after23;
        try (java.sql.Connection c = db.owner()) {
            after23 = S7Audit.oldRows(c).get(h23);
        }
        long p0 = acquire(db, P0, "N1");
        long p1 = acquire(db, P1, "N2");
        cmd(db, 1, p0);
        cmd(db, 2, p1);
        cmd(db, 1, p0);
        cmd(db, 2, p1);
        evt(db, 1, p0, 102);
        snap(db, 1, p0);
        snap(db, 2, p1);
        trade(db, 2, p1);
        S7Audit.Snapshot p3 = S7Audit.take(db).agreed("P3 행");
        assertClean(p3);
        assertEquals(after23, p3.oldRows().get(h23));

        // R7-3: 2->3 뒤 새 트랜잭션이 P2 epoch 로 쓴 스냅샷(마켓 1)과 명령(새 구간 행이 없는 마켓 3).
        snap(db, 1, me);
        S7Audit.Snapshot r3s = S7Audit.take(db).agreed("R7-3 스냅샷");
        assertEquals(1, r3s.arm("boundary", "snapshots"), "구간 경계 팔이 옛 구간 epoch 스냅샷을 세지 않았다");
        assertEquals(after23 + 1, r3s.oldRows().get(h23));
        cmd(db, 3, me);
        S7Audit.Snapshot r3c = S7Audit.take(db).agreed("R7-3 명령");
        assertEquals(1, r3c.arm("boundary", "command_journal"), "구간 경계 팔이 옛 구간 epoch 명령을 세지 않았다");
        assertEquals(after23 + 2, r3c.oldRows().get(h23));

        // R7-4: A 가 3->2 전에 트랜잭션을 열고, 전환과 새 구간 행(마켓 1) 커밋 뒤 옛 P3 epoch 로 마켓 3·1 에 쓰고 커밋한다.
        long h32;
        long after32;
        try (java.sql.Connection a = db.owner()) {
            a.setAutoCommit(false);
            PartitionLeaseDb.queryString(a, "SELECT now()::text");
            assertEquals(3, db.switchProtocol(2, true));
            h32 = S7Audit.lastHistoryId(db);
            try (java.sql.Connection c = db.owner()) {
                after32 = S7Audit.oldRows(c).get(h32);
            }
            long me2 = acquire(db, ME, "N1");
            cmd(db, 1, me2);
            PartitionLeaseDb.queryLong(a, "SELECT probe.cmd_now(3::smallint, " + p1 + ")");
            PartitionLeaseDb.queryLong(a, "SELECT probe.cmd_now(1::smallint, " + p0 + ")");
            a.commit();
        }
        assertEquals("true", db.ownerString("""
                SELECT bool_and(c.created_at < h.changed_at)::text
                  FROM command_journal c, leader_protocol_history h
                 WHERE h.id = %d AND c.market_id IN (1, 3)
                   AND c.seq = (SELECT max(x.seq) FROM command_journal x WHERE x.market_id = c.market_id)
                """.formatted(h32)), "가로지른 트랜잭션의 created_at 이 전환 앞이 아니다(시험 전제)");
        S7Audit.Snapshot r4 = S7Audit.take(db).agreed("R7-4 가로지른 트랜잭션");
        assertEquals(r3c.arm("boundary", "command_journal"), r4.arm("boundary", "command_journal"),
                "구간 경계 팔은 전환을 가로지른 트랜잭션을 보지 못한다(알려진 빈틈, 음성 대조)");
        assertEquals(r3c.arm("market", "command_journal") + 1, r4.arm("market", "command_journal"),
                "새 구간 행이 먼저 커밋된 마켓 1 의 행을 마켓 안 팔이 세지 않았다");
        assertEquals(r3c.arm("time", "command_journal"), r4.arm("time", "command_journal"));
        assertEquals(r3c.c30Listing(), r4.c30Listing(), "정상 획득한 P3 epoch 의 제 파티션 쓰기는 C30 이 세지 않는다");
        assertEquals(after32 + 2, r4.oldRows().get(h32), "구간 경계 증분이 가로지른 트랜잭션의 두 행을 세지 않았다");

        // R7-5: 3->2 뒤 새 트랜잭션이 옛 P3 epoch 로 쓴 이벤트·체결, 이어 같은 epoch 의 스냅샷.
        evt(db, 2, p1, 901);
        trade(db, 1, p0);
        S7Audit.Snapshot r5 = S7Audit.take(db).agreed("R7-5 이벤트·체결");
        assertEquals(r4.c22Listing(), r5.c22Listing(), "C22' 는 이벤트·체결 표를 읽지 않는다(알려진 빈틈, 음성 대조)");
        assertEquals(r4.c30Listing(), r5.c30Listing(), "정상 획득 epoch 의 제 파티션 이벤트·체결은 C30 이 세지 않는다(음성 대조)");
        assertEquals(r4.oldRows().get(h32) + 2, r5.oldRows().get(h32), "구간 경계 증분이 옛 P3 epoch 이벤트·체결을 세지 않았다");
        snap(db, 2, p1);
        S7Audit.Snapshot r5s = S7Audit.take(db).agreed("R7-5 스냅샷");
        assertEquals(r5.arm("boundary", "snapshots") + 1, r5s.arm("boundary", "snapshots"));
        assertEquals(r5.oldRows().get(h32) + 1, r5s.oldRows().get(h32));
    }

    /**
     * P3 구간이 없는 DB(S7 설계 4-15). C22' 의 시간 축 팔과 마켓 안 팔은 개정 전 C22 와 같은 행을 내고 구간 경계 팔은 0 이다.
     * P2 구간 안에 옛 epoch 쓰기(split-brain 흔적)를 넣어 두 판이 모두 0 이 아닌 곳에서 대조한다. C30 은 0 이다.
     */
    @Test
    void withoutPartitionSegmentsC22PrimeEqualsTheOldC22() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("s7_audit_nop3");
        db.seedMarkets(1, 2);
        S7Audit.installProbe(db);
        long e1 = acquire(db, ME, "N1");
        cmd(db, 1, e1);
        cmd(db, 2, e1);
        release(db, ME, "N1", e1);
        db.switchProtocol(2, false);
        long e2 = acquire(db, ME, "N2");
        cmd(db, 1, e2);
        cmd(db, 2, e2);
        release(db, ME, "N2", e2);
        long e3 = acquire(db, ME, "N3");
        cmd(db, 1, e3);
        cmd(db, 1, e2);
        S7Audit.Snapshot s = S7Audit.take(db).agreed("P3 없는 DB");
        long before;
        try (java.sql.Connection c = db.owner()) {
            before = S7Audit.c22Before(c);
        }
        assertEquals(2, before, "개정 전 C22 가 P2 안의 역행을 세지 않았다(양성 축)");
        assertEquals(before, s.arm("time", "command_journal") + s.arm("market", "command_journal"));
        assertEquals(0, s.arm("boundary", "command_journal") + s.arm("boundary", "snapshots"));
        assertEquals(0, s.c30Listing());
    }

    private static void assertClean(S7Audit.Snapshot s) {
        assertEquals(0, s.c22Listing(), "C22' 가 0 이 아니다: " + s);
        assertEquals(0, s.c30Listing(), "C30 이 0 이 아니다: " + s);
    }

    /** 가장 최근 전환 이력 행의 구간 경계 증분 표본(전환 직후). */
    private static Map<Long, Long> sampleLast(PartitionLeaseDb db) throws Exception {
        long id = S7Audit.lastHistoryId(db);
        try (java.sql.Connection c = db.owner()) {
            return Map.of(id, S7Audit.oldRows(c).get(id));
        }
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

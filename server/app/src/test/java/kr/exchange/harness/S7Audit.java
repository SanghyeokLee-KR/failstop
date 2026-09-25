package kr.exchange.harness;

import kr.exchange.engine.lease.PartitionLeaseDb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7 사후 감사 시험의 공용 도구(ADR 0034 S7, S7 설계 6-2). C22'·C30 을 네 곳에서 센다. 앱 enum(수 판), 하네스 summary.sql(수 판),
 * 하네스 invariants.sql(목록 판), DB 설계 §6.4 ⑯·⑳ 의 목록 판이다. 목록 판은 계약 문서에서 그대로 뽑아 돌린다. 문서의 SQL 과
 * 코드의 SQL 이 갈리면 행 수가 달라져 드러난다. 구간 경계 증분 표본(harness/sql/segment-old-rows.sql)도 그대로 돌린다.
 *
 * 쓰기 흉내 함수(probe 스키마)는 소유자가 쓰기 판정을 거치지 않고 저널·체결·스냅샷 행을 넣는다. 사후 감사가 잡아야 할 결함,
 * 곧 쓰기 판정을 우회한 쓰기의 흉내다(설계 3절 P7-5·R7 과 같은 방식).
 */
public final class S7Audit {

    /** 개정 전 C22(S6 까지의 앱 enum). P3 행에서 거짓 위반을 내는 음성 대조다. */
    public static final String C22_BEFORE_S7 = """
            SELECT (
                SELECT count(*) FROM (
                    SELECT fencing_token, lag(fencing_token) OVER (ORDER BY created_at, seq) AS prev FROM command_journal
                ) t WHERE t.prev > t.fencing_token
            ) + (
                SELECT count(*) FROM (
                    SELECT fencing_token, lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq) AS prev
                    FROM command_journal
                ) t WHERE t.prev > t.fencing_token
            )
            """;

    private static final String DB_DESIGN = "백엔드_DB_설계.md";

    private S7Audit() {
    }

    /** 한 시점의 감사 값. 목록 판(arm, tbl)별 행 수와 네 평가기의 값, 구간 경계 증분 표본이다. */
    public record Snapshot(Map<String, Long> c22ByArm, long c22Listing, long c22App, long c22Summary, long c22Invariants,
                           Map<String, Long> c30ByTable, long c30Listing, long c30App, long c30Summary, long c30Invariants,
                           Map<Long, Long> oldRows) {

        public long arm(String arm, String tbl) {
            return c22ByArm.getOrDefault(arm + ":" + tbl, 0L);
        }

        public long c30(String tbl) {
            return c30ByTable.getOrDefault(tbl, 0L);
        }

        /** 네 평가기가 같은 값을 냈는가. 다르면 그 자리에서 실패한다. */
        public Snapshot agreed(String label) {
            assertEquals(c22Listing, c22App, label + ": C22' 앱 enum 이 DB 설계 목록 판과 다르다 " + this);
            assertEquals(c22Listing, c22Summary, label + ": C22' summary.sql 이 목록 판과 다르다 " + this);
            assertEquals(c22Listing, c22Invariants, label + ": C22' invariants.sql 이 목록 판과 다르다 " + this);
            assertEquals(c30Listing, c30App, label + ": C30 앱 enum 이 DB 설계 목록 판과 다르다 " + this);
            assertEquals(c30Listing, c30Summary, label + ": C30 summary.sql 이 목록 판과 다르다 " + this);
            assertEquals(c30Listing, c30Invariants, label + ": C30 invariants.sql 이 목록 판과 다르다 " + this);
            return this;
        }
    }

    /** 네 평가기로 센다. 하네스 SQL 은 컨테이너 psql 로 돈다. */
    public static Snapshot take(PartitionLeaseDb db) throws Exception {
        Map<String, Long> c22ByArm = new TreeMap<>();
        Map<String, Long> c30ByTable = new TreeMap<>();
        long c22App;
        long c30App;
        Map<Long, Long> oldRows;
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT arm || ':' || tbl, count(*) FROM (" + c22PrimeListing()
                    + ") x GROUP BY 1")) {
                while (rs.next()) {
                    c22ByArm.put(rs.getString(1), rs.getLong(2));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT tbl, count(*) FROM (" + c30Listing() + ") x GROUP BY 1")) {
                while (rs.next()) {
                    c30ByTable.put(rs.getString(1), rs.getLong(2));
                }
            }
            c22App = PartitionLeaseDb.queryLong(c, InvariantCheck.C22_FENCING_MONOTONIC.sql());
            c30App = PartitionLeaseDb.queryLong(c, InvariantCheck.C30_FENCING_RESOURCE_MATCH.sql());
            oldRows = oldRows(c);
        }
        Map<String, String> summary = HarnessSqlVerdictTest.summaryByCode(db);
        List<String> invariants = db.psql("invariants.sql");
        return new Snapshot(c22ByArm, c22ByArm.values().stream().mapToLong(Long::longValue).sum(), c22App,
                summaryValue(summary, "C22"), block(invariants, "C22").size(),
                c30ByTable, c30ByTable.values().stream().mapToLong(Long::longValue).sum(), c30App,
                summaryValue(summary, "C30"), block(invariants, "C30").size(), oldRows);
    }

    /** 구간 경계 증분 표본. 이력 행 id 마다 옛 구간 epoch 행 수다(harness/sql/segment-old-rows.sql). */
    public static Map<Long, Long> oldRows(Connection c) throws SQLException {
        Map<Long, Long> rows = new TreeMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(segmentOldRowsSql())) {
            while (rs.next()) {
                rows.put(rs.getLong("id"), rs.getLong("old_rows"));
            }
        }
        return rows;
    }

    public static long c22Before(Connection c) throws SQLException {
        return PartitionLeaseDb.queryLong(c, C22_BEFORE_S7);
    }

    private static long summaryValue(Map<String, String> summary, String code) {
        String line = summary.get(code);
        assertNotNull(line, "summary.sql 에 " + code + " 행이 없다");
        String[] f = line.split("\\|", -1);
        assertTrue(f[5].equals("PASS") || f[5].equals("FAIL"), "평가되지 않은 판정: " + line);
        return Long.parseLong(f[4]);
    }

    /** invariants.sql 출력에서 한 판정 블록의 결과 행(머리 줄 다음부터 다음 머리 줄 전까지). */
    public static List<String> block(List<String> lines, String code) {
        int from = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("== " + code + " ")) {
                assertTrue(from < 0, "판정 블록이 두 번 나왔다: " + code);
                from = i + 1;
            }
        }
        assertTrue(from > 0, "invariants.sql 에 " + code + " 블록이 없다");
        int to = from;
        while (to < lines.size() && !lines.get(to).startsWith("== ")) {
            to++;
        }
        return lines.subList(from, to).stream().filter(line -> !line.isBlank()).toList();
    }

    /** DB 설계 §6.4 ⑯ 목록 판(C22'). 열은 (arm, tbl, subject)다. */
    public static String c22PrimeListing() throws IOException {
        return docStatement("-- ⑯ fencing_epoch 단조성 감사");
    }

    /** DB 설계 §6.4 ⑳ 목록 판(C30). 열은 (tbl, subject)다. */
    public static String c30Listing() throws IOException {
        return docStatement("-- ⑳ C30 파티션 epoch 의 자원 대조");
    }

    /** 표식 주석 뒤 첫 WITH 문장부터 문장 끝 세미콜론 앞까지. */
    private static String docStatement(String marker) throws IOException {
        String doc = dbDesign();
        int at = doc.indexOf(marker);
        assertTrue(at >= 0, "DB 설계 문서에 표식이 없다: " + marker);
        int start = doc.indexOf("WITH seg AS (", at);
        int end = doc.indexOf(';', start);
        assertTrue(start > at && end > start, "DB 설계 문서에서 문장을 찾지 못했다: " + marker);
        return doc.substring(start, end);
    }

    /** DB 설계 문서 본문(줄바꿈 LF). */
    public static String dbDesign() throws IOException {
        return Files.readString(repoPath("docs/reference/contracts").resolve(DB_DESIGN), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    public static String segmentOldRowsSql() {
        String dir = System.getProperty("harness.sql.dir");
        assertNotNull(dir, "시스템 속성 harness.sql.dir 없음(server/app/build.gradle.kts 가 넘긴다)");
        try {
            return Files.readString(Path.of(dir, "segment-old-rows.sql"), StandardCharsets.UTF_8).trim()
                    .replaceAll(";\\s*$", "");
        } catch (IOException e) {
            throw new IllegalStateException("segment-old-rows.sql 을 읽지 못했다", e);
        }
    }

    /** 저장소 안의 ASCII 경로. 작업 디렉터리에서 위로 찾는다(PartitionLeaseDb 와 같은 방식). */
    public static Path repoPath(String relative) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(relative))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException(relative + " 없음");
        }
        return root.resolve(relative);
    }

    /**
     * 쓰기 흉내 함수를 probe 스키마에 만든다. cmd 는 created_at 을 호출 시각(clock_timestamp)으로, cmd_now 는 DEFAULT now()
     * (트랜잭션 시작 시각)로 둔다. 모든 행은 쓰기 판정(append_command_journal_row, 시장 도출 fencing)을 거치지 않는다.
     */
    public static void installProbe(PartitionLeaseDb db) throws SQLException {
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            // 2->3 선행 조건(fee_shard_missing)을 채운다. 샤드 0 을 넣으면 V55 트리거가 샤드 1 을 같은 트랜잭션에 만든다.
            st.execute("""
                    INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                    SELECT 'SYSTEM', 0, 'FEE_INCOME', a.id, 0 FROM assets a
                    ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING
                    """);
            st.execute("CREATE SCHEMA IF NOT EXISTS probe");
            st.execute("""
                    CREATE FUNCTION probe.cmd(p_market SMALLINT, p_epoch BIGINT) RETURNS BIGINT
                    LANGUAGE plpgsql AS $$
                    DECLARE v_seq BIGINT := next_market_command_seq(p_market);
                    BEGIN
                      INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                                                   engine_ts, tie_break, payload, fencing_token, checksum, created_at)
                      VALUES (v_seq, p_market, 'CANCEL', 1, 'p' || (v_seq % 1000000000000), v_seq % 1000000000000, 0, 0,
                              '{}'::jsonb, p_epoch, 0, clock_timestamp());
                      PERFORM pg_sleep(0.002);
                      RETURN v_seq;
                    END $$
                    """);
            st.execute("""
                    CREATE FUNCTION probe.cmd_now(p_market SMALLINT, p_epoch BIGINT) RETURNS BIGINT
                    LANGUAGE plpgsql AS $$
                    DECLARE v_seq BIGINT := next_market_command_seq(p_market);
                    BEGIN
                      INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                                                   engine_ts, tie_break, payload, fencing_token, checksum)
                      VALUES (v_seq, p_market, 'CANCEL', 1, 'p' || (v_seq % 1000000000000), v_seq % 1000000000000, 0, 0,
                              '{}'::jsonb, p_epoch, 0);
                      RETURN v_seq;
                    END $$
                    """);
            st.execute("""
                    CREATE FUNCTION probe.evt(p_market SMALLINT, p_epoch BIGINT, p_src BIGINT) RETURNS BIGINT
                    LANGUAGE plpgsql AS $$
                    DECLARE r RECORD;
                    BEGIN
                      SELECT * INTO r FROM next_market_event_seq(p_market);
                      INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch,
                                                 created_at, market_id, market_seq)
                      VALUES (r.out_event_seq, p_src, 0, 'ACCEPTED', '{}'::jsonb, p_epoch, clock_timestamp(), p_market,
                              r.out_market_seq);
                      RETURN r.out_event_seq;
                    END $$
                    """);
            st.execute("""
                    CREATE FUNCTION probe.snap(p_market SMALLINT, p_epoch BIGINT) RETURNS BIGINT
                    LANGUAGE sql AS $$
                      INSERT INTO snapshots (market_id, last_applied_seq, state, state_checksum, engine_version, config_hash,
                                             format_version, fencing_epoch)
                      VALUES (p_market, 0, '\\x00'::bytea, 0, 'probe', 'probe', 1, p_epoch) RETURNING snapshot_id
                    $$
                    """);
            st.execute("""
                    CREATE FUNCTION probe.trade(p_market SMALLINT, p_epoch BIGINT) RETURNS BIGINT
                    LANGUAGE plpgsql AS $$
                    DECLARE r RECORD;
                    BEGIN
                      SELECT * INTO r FROM next_market_event_seq(p_market);
                      INSERT INTO trades (id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id,
                                          taker_side, price, quantity, fencing_epoch, executed_at, created_at)
                      VALUES (r.out_event_seq, p_market, 1, 2, 1, 1, 'BUY', 1, 1, p_epoch, clock_timestamp(), clock_timestamp());
                      RETURN r.out_event_seq;
                    END $$
                    """);
        }
    }

    /** 소유자 한 문장. 결과 첫 열을 long 으로. */
    public static long exec(PartitionLeaseDb db, String sql) throws SQLException {
        try (Connection c = db.owner()) {
            return PartitionLeaseDb.queryLong(c, sql);
        }
    }

    /** 자원을 소유자로 얻는다(최고 모드 3). 얻지 못하면 실패다. */
    public static long acquire(PartitionLeaseDb db, String resource, String holder) throws SQLException {
        String epoch = db.ownerString("SELECT try_acquire_lease('" + resource + "', '" + holder
                + "', interval '60 seconds', 3::smallint)");
        assertNotNull(epoch, resource + " 획득 실패(" + holder + ")");
        return Long.parseLong(epoch);
    }

    public static void release(PartitionLeaseDb db, String resource, String holder, long epoch) throws SQLException {
        db.ownerString("SELECT release_lease('" + resource + "', '" + holder + "', " + epoch + ")::text");
    }

    public static long cmd(PartitionLeaseDb db, int market, long epoch) throws SQLException {
        return exec(db, "SELECT probe.cmd(" + market + "::smallint, " + epoch + ")");
    }

    public static long evt(PartitionLeaseDb db, int market, long epoch, long source) throws SQLException {
        return exec(db, "SELECT probe.evt(" + market + "::smallint, " + epoch + ", " + source + ")");
    }

    public static long snap(PartitionLeaseDb db, int market, long epoch) throws SQLException {
        return exec(db, "SELECT probe.snap(" + market + "::smallint, " + epoch + ")");
    }

    public static long trade(PartitionLeaseDb db, int market, long epoch) throws SQLException {
        return exec(db, "SELECT probe.trade(" + market + "::smallint, " + epoch + ")");
    }

    /** 마지막 이력 행 id(가장 최근 전환). */
    public static long lastHistoryId(PartitionLeaseDb db) throws SQLException {
        return exec(db, "SELECT max(id) FROM leader_protocol_history");
    }
}

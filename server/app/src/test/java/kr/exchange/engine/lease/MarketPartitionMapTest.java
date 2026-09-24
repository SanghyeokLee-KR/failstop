package kr.exchange.engine.lease;

import kr.exchange.harness.InvariantCheck;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static kr.exchange.engine.lease.PartitionLeaseDb.queryLong;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 마켓과 리스 파티션의 대응(S2 설계 4-5, ADR 0034 결정한 값 3). V48 이 기존 마켓을 시드하고 markets 트리거가 새 마켓을
 * 넣으며, 대응 행은 고치거나 지울 수 없다. C29 는 대응·리스 행·프로토콜 행 누락을 세고 하네스 SQL 두 벌과 값이 같다.
 * V48 객체가 모두 없는 DB 에서 하네스 SQL 은 C29 를 N/A 로 낸다(ADR 0034 S3 판정 어휘).
 */
class MarketPartitionMapTest {

    private static final String APPEND_ONLY = "P0001";

    @Test
    void v48SeedsDecidedAssignmentForExistingMarkets() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.createAt("mlp_seed", "47");
        db.seedMarkets(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        db.migrate();
        assertEquals("1:p0,2:p1,3:p1,4:p0,5:p0,6:p1,7:p0,8:p1,9:p0,10:p1,11:p0,12:p1", mapping(db));
    }

    @Test
    void marketsAddedByAppRoleAfterV48AreMappedByTrigger() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("mlp_trigger").withRuntimeRoles();
        db.seedMarkets(1);
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            st.execute(PartitionLeaseDb.insertMarketSql(13));
            st.execute(PartitionLeaseDb.insertMarketSql(14));
            // 트리거 발화는 함수 EXECUTE 를 보지 않는다. 앱 역할에는 그 권한이 없다.
            assertEquals("false", queryString(app,
                    "SELECT has_function_privilege('seed_market_lease_partition()', 'EXECUTE')::text"));
            SQLException direct = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO market_lease_partition (market_id, resource) VALUES (15, 'matching-p0')"));
            assertEquals("42501", direct.getSQLState(), direct.getMessage());
        }
        assertEquals("1:p0,13:p0,14:p1", mapping(db));
        assertEquals(PartitionLeaseDb.APP, db.ownerString(
                "SELECT mapped_by FROM market_lease_partition WHERE market_id = 13"));
    }

    @Test
    void mappingIsAppendOnlyAndOnlyNamesPartitionResources() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("mlp_append");
        db.seedMarkets(1, 2);
        db.seedMarketWithoutTriggers(3);
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            for (String sql : List.of(
                    "UPDATE market_lease_partition SET resource = 'matching-p1' WHERE market_id = 1",
                    "DELETE FROM market_lease_partition WHERE market_id = 1",
                    "TRUNCATE market_lease_partition")) {
                SQLException refused = assertThrows(SQLException.class, () -> st.execute(sql), sql);
                assertEquals(APPEND_ONLY, refused.getSQLState(), refused.getMessage());
                assertTrue(refused.getMessage().contains("append-only 위반"), refused.getMessage());
            }
            SQLException engine = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO market_lease_partition (market_id, resource) VALUES (3, 'matching-engine')"));
            assertEquals("23514", engine.getSQLState(), engine.getMessage());
            SQLException missingMarket = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO market_lease_partition (market_id, resource) VALUES (99, 'matching-p0')"));
            assertEquals("23503", missingMarket.getSQLState(), missingMarket.getMessage());
            assertEquals(0, st.executeUpdate("INSERT INTO market_lease_partition (market_id, resource) "
                    + "VALUES (1, 'matching-p1') ON CONFLICT (market_id) DO NOTHING"));
        }
        assertEquals("1:p0,2:p1", mapping(db));
    }

    /** C29 는 세 항의 합이다. 트리거를 끈 마켓, 리스 행 누락, 프로토콜 행 누락을 하나씩 더해 세 벌이 같은 값을 내는지 본다. */
    @Test
    void c29CountsEachMissingRowAndHarnessCopiesAgree() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("mlp_c29");
        db.seedMarkets(1, 2);
        assertC29(db, 0);

        db.seedMarketWithoutTriggers(20);
        assertC29(db, 1);

        // 외래키와 추가 전용 트리거를 끄지 않으면 지울 수 없는 행이다. 복원·수동 조작이 남기는 손상을 흉내 낸다.
        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("DELETE FROM leader_lease WHERE resource = 'matching-p1'");
            st.execute("SET session_replication_role = origin");
        }
        assertC29(db, 2);

        try (Connection c = db.owner(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("DELETE FROM leader_protocol");
            st.execute("SET session_replication_role = origin");
        }
        assertC29(db, 3);
    }

    /**
     * V48 표가 없는 DB(V47 이하 스키마, S2 배포 전 백업의 복원본)에서 하네스 SQL 은 C29 만 SKIP 으로 내고 나머지 판정은 V48 DB 와
     * 같게 낸다. summary.sql 은 판정 전체가 한 문장이라 표를 그냥 읽으면 모든 판정이 사라진다.
     */
    @Test
    void harnessSqlJudgesC29NotApplicableWhereV48ObjectsAreMissing() throws Exception {
        PartitionLeaseDb v47 = PartitionLeaseDb.createAt("mlp_sql_v47", "47");
        v47.seedMarkets(1, 2);
        PartitionLeaseDb v48 = PartitionLeaseDb.create("mlp_sql_v48");
        v48.seedMarkets(1, 2);

        Map<String, String> before = summaryByCode(v47);
        Map<String, String> after = summaryByCode(v48);
        assertEquals(after.keySet(), before.keySet(), "V47 DB 에서 빠진 판정이 있다");
        assertTrue(after.size() > 30, "summary.sql 판정 행이 모자라다: " + after.keySet());
        // C31 은 V50 객체를 읽으므로 V47 DB 에서 함께 N/A 다. 두 코드의 판정 전체는 HarnessSqlVerdictTest 가 본다.
        for (String code : after.keySet()) {
            if (!code.equals("C29") && !code.equals("C31")) {
                assertEquals(after.get(code), before.get(code), code);
            }
        }
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS|0|PASS", after.get("C29"));
        // 2026-09-24 S3 개정: V48 객체가 모두 없는 스키마는 SKIP(미평가)이 아니라 N/A(대상 없음)다(하네스 검증설계 §4-1).
        assertEquals("C29|MARKET_PARTITION_MAP|CRITICAL|ALWAYS||N/A", before.get("C29"));

        List<String> sections47 = sections(v47.psql("invariants.sql"));
        List<String> sections48 = sections(v48.psql("invariants.sql"));
        assertEquals(sections48.stream().map(MarketPartitionMapTest::sectionCode).toList(),
                sections47.stream().map(MarketPartitionMapTest::sectionCode).toList(), "V47 DB 에서 빠진 판정 블록이 있다");
        assertEquals(List.of(), skipped(sections47), String.join("\n", sections47));
        assertTrue(sections47.stream().anyMatch(s -> s.startsWith("== C29 ") && s.contains("] N/A:")),
                String.join("\n", sections47));
        assertEquals(List.of(), skipped(sections48), String.join("\n", sections48));
    }

    private static List<String> skipped(List<String> sections) {
        return sections.stream().filter(s -> s.contains("] SKIP")).map(MarketPartitionMapTest::sectionCode).toList();
    }

    /** summary.sql 의 판정 행을 코드별로 모은다. 한 코드가 두 번 나오면 실패다. */
    private static Map<String, String> summaryByCode(PartitionLeaseDb db) throws Exception {
        Map<String, String> rows = new TreeMap<>();
        for (String line : db.psql("summary.sql")) {
            String[] f = line.split("\\|", -1);
            assertEquals(6, f.length, line);
            assertNull(rows.put(f[0], line), "판정 코드 중복: " + line);
        }
        return rows;
    }

    /** invariants.sql 의 판정 블록 머리 줄. */
    private static List<String> sections(List<String> lines) {
        return lines.stream().filter(line -> line.startsWith("== C")).toList();
    }

    private static String sectionCode(String header) {
        return header.substring(3, header.indexOf(' ', 3));
    }

    private static void assertC29(PartitionLeaseDb db, long expected) throws Exception {
        long app;
        try (Connection c = db.owner()) {
            app = queryLong(c, InvariantCheck.C29_MARKET_PARTITION_MAP.sql());
        }
        assertEquals(expected, app, "InvariantCheck C29");

        String summary = db.psql("summary.sql").stream()
                .filter(line -> line.startsWith("C29|"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("summary.sql 에 C29 행 없음"));
        String[] f = summary.split("\\|", -1);
        assertEquals(6, f.length, summary);
        assertEquals("MARKET_PARTITION_MAP|CRITICAL|ALWAYS", f[1] + "|" + f[2] + "|" + f[3], summary);
        assertEquals(expected, Long.parseLong(f[4]), "summary.sql C29");
        assertEquals(expected == 0 ? "PASS" : "FAIL", f[5], "summary.sql verdict");

        long invariants = 0;
        boolean inSection = false;
        boolean seen = false;
        for (String line : db.psql("invariants.sql")) {
            if (line.startsWith("== ")) {
                inSection = line.startsWith("== C29 ");
                seen |= inSection;
                continue;
            }
            if (inSection) {
                String[] row = line.split("\\|", -1);
                assertEquals(3, row.length, "invariants.sql C29 행 형식: " + line);
                assertTrue(List.of("unmapped_market", "missing_lease", "protocol_rows").contains(row[0]), line);
                invariants += Long.parseLong(row[2]);
            }
        }
        assertTrue(seen, "invariants.sql 에 C29 절 없음");
        assertEquals(expected, invariants, "invariants.sql C29");
    }

    /** 마켓별 파티션을 'id:p0' 꼴로 잇는다. */
    private static String mapping(PartitionLeaseDb db) throws SQLException {
        return db.ownerString("""
                SELECT string_agg(market_id || ':' || replace(resource, 'matching-', ''), ',' ORDER BY market_id)
                FROM market_lease_partition
                """);
    }

    @Test
    void mappingTableHasNoWriterOutsideTheTrigger() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.create("mlp_grants").withRuntimeRoles();
        for (String role : List.of("app_role", "harness_role")) {
            assertEquals("true|false", db.ownerString("""
                    SELECT has_table_privilege('%1$s', 'market_lease_partition', 'SELECT')::text || '|'
                        || has_table_privilege('%1$s', 'market_lease_partition', 'INSERT')::text
                    """.formatted(role)), role);
        }
        assertFalse(Boolean.parseBoolean(db.ownerString(
                "SELECT has_table_privilege('" + PartitionLeaseDb.READER + "', 'market_lease_partition', 'SELECT')::text")),
                "조회 로그인이 대응 표를 읽는다");
    }
}

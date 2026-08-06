package kr.exchange;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파티션 지평 회귀 고정. outbox는 V1이 2026-07까지, V8이 2026-09부터 만들어 2026-08 한 달이
 * 어느 정규 파티션에도 속하지 않았고, 2026-08이 되자 신규 outbox 행이 전부 outbox_default로
 * 유입되어 C15_DEFAULT_PARTITION이 상시 위반됐다. V12가 그 구멍을 닫는다.
 *
 * 결함의 성격이 달력 구동이라 "그 달이 오기 전에는 전 테스트가 green"이었다. 특정 월을 박아
 * 두는 단언은 같은 결함을 다시 놓치므로, 아래는 시각을 실행 시점에서 얻어 판정한다.
 *   (1) 지금 이 순간이 정규 파티션에 속한다: 오늘 만들어지는 행이 DEFAULT로 가지 않는다.
 *   (2) 앞으로 {@value #LOOKAHEAD_MONTHS}개월도 속한다: 지평 소진 전에 실패해 선생성을 강제한다.
 *       이 단언은 수동 선생성 지평(현재 2027-09-01)을 6개월 앞두고 의도적으로 깨진다.
 *       깨지면 월 파티션을 연장하는 마이그레이션을 추가한다(DB 설계 §3.2).
 *   (3) 정규 파티션 사슬에 내부 구멍이 없다: V1과 V8 사이처럼 경계가 어긋난 조합을 잡는다.
 * seq 축 저널은 달력과 무관하므로 (3)과 현재 채번값 커버리지로 판정한다.
 *
 * 파티션 경계는 pg_get_expr(relpartbound)로 읽는다. 출력은 timestamptz·bigint 모두
 * FOR VALUES FROM ('...') TO ('...') 형태의 인용 리터럴이고, timestamptz는 오프셋을 포함해
 * 렌더링되므로 같은 세션에서 되캐스팅하면 무손실이다.
 */
@Testcontainers
class PartitionCoverageIntegrationTest {

    /** 지평 소진 경보 선행 기간. 마이그레이션 작성·검토·배포에 필요한 여유. */
    private static final int LOOKAHEAD_MONTHS = 6;

    private static final List<String> PARTITIONED_PARENTS = List.of(
            "trades", "ledger_entries", "outbox", "command_journal", "event_journal");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    /** 주어진 시점을 어느 정규 파티션도 담지 않는 시간 파티션 부모. 빈 결과가 정상. */
    private static final String UNCOVERED_TIME_PARENTS = """
            WITH probe AS (SELECT now() + make_interval(months => ?::int) AS at),
                 tp AS (
                     SELECT p.oid, p.relname
                     FROM pg_class p
                     JOIN pg_namespace n ON n.oid = p.relnamespace
                     JOIN pg_partitioned_table pt ON pt.partrelid = p.oid
                     JOIN pg_attribute a ON a.attrelid = p.oid AND a.attnum = pt.partattrs[0]
                     WHERE p.relkind = 'p' AND n.nspname = 'public'
                       AND pt.partstrat = 'r' AND a.atttypid = 'timestamptz'::regtype
                 )
            SELECT tp.relname
            FROM tp, probe
            WHERE NOT EXISTS (
                SELECT 1
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                CROSS JOIN LATERAL (SELECT pg_get_expr(c.relpartbound, c.oid) AS b) e
                WHERE i.inhparent = tp.oid AND e.b <> 'DEFAULT'
                  AND probe.at >= substring(e.b from 'FROM \\(''([^'']*)''\\)')::timestamptz
                  AND probe.at <  substring(e.b from 'TO \\(''([^'']*)''\\)')::timestamptz
            )
            ORDER BY 1
            """;

    /**
     * 부모별 정규 파티션 수와 "하한이 다른 파티션의 상한과 맞물린" 수. 사슬이 하나로 이어지면
     * 후자가 전자보다 정확히 1 작다. 구멍이 하나 생길 때마다 그 차이가 1씩 벌어진다.
     * 정렬을 쓰지 않아 경계 리터럴의 자료형(시간·정수)에 무관하다.
     */
    private static final String PARTITION_CHAINS = """
            WITH r AS (
                SELECT i.inhparent AS parent,
                       substring(pg_get_expr(c.relpartbound, c.oid)
                                 from 'FROM \\(''([^'']*)''\\)') AS lo,
                       substring(pg_get_expr(c.relpartbound, c.oid)
                                 from 'TO \\(''([^'']*)''\\)')   AS hi
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_namespace n ON n.oid = p.relnamespace
                WHERE c.relispartition AND n.nspname = 'public'
                  AND pg_get_expr(c.relpartbound, c.oid) <> 'DEFAULT'
            )
            SELECT parent::regclass::text AS parent,
                   count(*) AS parts,
                   count(*) FILTER (
                       WHERE EXISTS (SELECT 1 FROM r prev
                                     WHERE prev.parent = r.parent AND prev.hi = r.lo)
                   ) AS chained
            FROM r
            GROUP BY parent
            ORDER BY 1
            """;

    /** 저널 채번 현재값을 담는 정규 파티션 수. 1이 정상. */
    private static final String SEQ_COUNTER_COVERAGE = """
            SELECT count(*)
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            CROSS JOIN LATERAL (SELECT pg_get_expr(c.relpartbound, c.oid) AS b) e
            CROSS JOIN LATERAL (SELECT next_value FROM journal_counters WHERE name = ?) j
            WHERE i.inhparent = ?::regclass AND e.b <> 'DEFAULT'
              AND j.next_value >= substring(e.b from 'FROM \\(''([^'']*)''\\)')::bigint
              AND j.next_value <  substring(e.b from 'TO \\(''([^'']*)''\\)')::bigint
            """;

    /** InvariantCheck.C15_DEFAULT_PARTITION 본문과 동일. 이 테스트가 막는 위반의 정의. */
    private static final String C15_DEFAULT_PARTITION = """
            SELECT (SELECT count(*) FROM ledger_entries_default)
                 + (SELECT count(*) FROM command_journal_default)
                 + (SELECT count(*) FROM event_journal_default)
                 + (SELECT count(*) FROM trades_default)
                 + (SELECT count(*) FROM outbox_default)
            """;

    @BeforeAll
    static void migrate() {
        var result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertTrue(result.success, "Flyway migration failed");
    }

    // ------------------------------------------------------------------
    // (1)(2) 시간 축: 지금과 향후 N개월이 정규 파티션에 속한다
    // ------------------------------------------------------------------

    @Test
    void timePartitionsCoverNowAndLookaheadMonths() throws Exception {
        try (Connection conn = open()) {
            for (int month = 0; month <= LOOKAHEAD_MONTHS; month++) {
                List<String> uncovered = uncoveredAt(conn, month);
                assertTrue(uncovered.isEmpty(),
                        "지금부터 " + month + "개월 뒤 구간의 파티션 결손: " + uncovered
                        + ". 해당 월 파티션을 만드는 마이그레이션을 추가하라(DB 설계 §3.2)");
            }
        }
    }

    /**
     * V12가 닫은 구멍의 직접 고정. 경계는 UTC 월 경계여야 outbox_p2026_07의 상한이자
     * outbox_p2026_09의 하한과 맞물린다.
     */
    @Test
    void outboxHasTheAugust2026PartitionThatV12Added() throws Exception {
        // pg_get_expr는 timestamptz 경계를 세션 TZ로 렌더링하므로 문자열 비교가 아니라
        // 되캐스팅 후 시점 비교로 판정한다(pgjdbc가 세션 TZ를 JVM zone으로 맞춘다).
        try (Connection conn = open()) {
            assertEquals("true", queryString(conn, """
                    SELECT (substring(pg_get_expr(c.relpartbound, c.oid)
                                      from 'FROM \\(''([^'']*)''\\)')::timestamptz
                            = TIMESTAMPTZ '2026-08-01 00:00:00+00'
                        AND substring(pg_get_expr(c.relpartbound, c.oid)
                                      from 'TO \\(''([^'']*)''\\)')::timestamptz
                            = TIMESTAMPTZ '2026-09-01 00:00:00+00')::text
                    FROM pg_inherits i
                    JOIN pg_class c ON c.oid = i.inhrelid
                    WHERE i.inhparent = 'outbox'::regclass
                      AND c.relname = 'outbox_p2026_08'
                    """), "outbox 2026-08 파티션 부재 또는 UTC 월 경계 불일치");
        }
    }

    /**
     * 음성 대조. 두 질의가 무엇을 검사하든 항상 빈 결과를 내는 형태였다면 위 단언들은
     * 아무것도 증명하지 못한다. 이번 달을 통째로 비운 프로브 테이블을 만들어 두 질의가
     * 그 부모를 지목하는지 확인하고, 제거 후 clean 복귀까지 본다. 실 스키마는 건드리지
     * 않는다. 경계는 실행 시점에서 계산해 달력 고정을 두지 않는다.
     */
    @Test
    void coverageQueriesDetectAnArtificialHole() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            String prev = queryString(conn,
                    "SELECT (date_trunc('month', now()) - interval '1 month')::text");
            String cur = queryString(conn, "SELECT date_trunc('month', now())::text");
            String next = queryString(conn,
                    "SELECT (date_trunc('month', now()) + interval '1 month')::text");
            String afterNext = queryString(conn,
                    "SELECT (date_trunc('month', now()) + interval '2 months')::text");

            st.execute("CREATE TABLE coverage_probe (at TIMESTAMPTZ NOT NULL) "
                    + "PARTITION BY RANGE (at)");
            try {
                st.execute("CREATE TABLE coverage_probe_prev PARTITION OF coverage_probe "
                        + "FOR VALUES FROM ('" + prev + "') TO ('" + cur + "')");
                st.execute("CREATE TABLE coverage_probe_next PARTITION OF coverage_probe "
                        + "FOR VALUES FROM ('" + next + "') TO ('" + afterNext + "')");

                assertTrue(uncoveredAt(conn, 0).contains("coverage_probe"),
                        "이번 달을 비운 프로브를 커버리지 질의가 놓침");
                assertTrue(brokenChains(conn).contains("coverage_probe"),
                        "끊긴 사슬을 연속성 질의가 놓침");
                // 프로브가 실 테이블을 오염시키지 않았다: 나머지 부모는 여전히 clean.
                assertEquals(List.of("coverage_probe"), brokenChains(conn),
                        "프로브 외 부모가 함께 실패로 보고됨");
            } finally {
                st.execute("DROP TABLE IF EXISTS coverage_probe");
            }
            assertTrue(uncoveredAt(conn, 0).isEmpty(), "프로브 제거 후 커버리지 clean 미복귀");
            assertTrue(brokenChains(conn).isEmpty(), "프로브 제거 후 연속성 clean 미복귀");
        }
    }

    // ------------------------------------------------------------------
    // (3) 전 파티션 테이블: 정규 파티션 사슬에 내부 구멍이 없다
    // ------------------------------------------------------------------

    @Test
    void noInteriorGapInAnyPartitionChain() throws Exception {
        try (Connection conn = open()) {
            List<String> seen = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(PARTITION_CHAINS);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String parent = rs.getString("parent");
                    long parts = rs.getLong("parts");
                    long chained = rs.getLong("chained");
                    seen.add(parent);
                    assertEquals(parts - 1, chained,
                            parent + " 정규 파티션 사슬이 끊김(파티션 " + parts + "개, 맞물림 "
                            + chained + "개). V1과 후속 마이그레이션의 경계가 어긋났다");
                }
            }
            // 질의가 아무 부모도 못 찾고 빈 결과로 통과하는 위양성 차단.
            assertTrue(seen.containsAll(PARTITIONED_PARENTS),
                    "파티션 부모 누락: 기대 " + PARTITIONED_PARENTS + ", 관측 " + seen);
        }
    }

    // ------------------------------------------------------------------
    // seq 축: 현재 채번값이 정규 파티션에 속한다
    // ------------------------------------------------------------------

    @Test
    void seqJournalsCoverCurrentCounterValue() throws Exception {
        try (Connection conn = open()) {
            assertEquals(1, seqCoverage(conn, "command_seq", "command_journal"),
                    "command_journal이 현재 command_seq 채번값을 담는 정규 파티션을 갖지 않음");
            assertEquals(1, seqCoverage(conn, "event_seq", "event_journal"),
                    "event_journal이 현재 event_seq 채번값을 담는 정규 파티션을 갖지 않음");
        }
    }

    // ------------------------------------------------------------------
    // C15 직결: 지금 만든 outbox 행이 DEFAULT로 가지 않는다
    // ------------------------------------------------------------------

    @Test
    void outboxRowCreatedNowRoutesAwayFromDefaultPartition() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            String partition;
            try (ResultSet rs = st.executeQuery("""
                    INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
                    VALUES ('TRADE', 'coverage-probe', 'TradeExecuted', '{}')
                    RETURNING tableoid::regclass::text
                    """)) {
                assertTrue(rs.next(), "outbox 삽입이 행을 반환하지 않음");
                partition = rs.getString(1);
            }
            assertNotEquals("outbox_default", partition,
                    "지금 생성한 outbox 행이 DEFAULT로 유입됨(C15_DEFAULT_PARTITION 위반 경로)");

            try (ResultSet rs = st.executeQuery(C15_DEFAULT_PARTITION)) {
                rs.next();
                assertEquals(0, rs.getLong(1),
                        "DEFAULT 파티션 유입 행 존재. 파티션 " + partition + "로 라우팅됐으나 총합 비0");
            }
            st.execute("DELETE FROM outbox WHERE aggregate_id = 'coverage-probe'");
        }
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private List<String> uncoveredAt(Connection conn, int monthsAhead) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(UNCOVERED_TIME_PARENTS)) {
            ps.setInt(1, monthsAhead);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private List<String> brokenChains(Connection conn) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(PARTITION_CHAINS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getLong("chained") != rs.getLong("parts") - 1) {
                    out.add(rs.getString("parent"));
                }
            }
        }
        return out;
    }

    private long seqCoverage(Connection conn, String counter, String parent) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SEQ_COUNTER_COVERAGE)) {
            ps.setString(1, counter);
            ps.setString(2, parent);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String queryString(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getString(1);
        }
    }
}

package kr.exchange.harness;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static kr.exchange.harness.InvariantCheck.C05_CMD_GAPLESS;
import static kr.exchange.harness.InvariantCheck.C15_DEFAULT_PARTITION;
import static kr.exchange.harness.InvariantCheck.C22_FENCING_MONOTONIC;
import static kr.exchange.harness.InvariantCheck.C23_COUNTER_SEQ_CONSISTENCY;
import static kr.exchange.harness.InvariantCheck.C27_CMD_DEFAULT_PARTITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 명령 저널 대역 축의 판정 술어(ADR 0022 6절). 커트오버 전 축에서는 팔별로 구 술어와 같은 값을, 대역 행에서는
 * 거짓 위반 0 을, 합성한 위반에서는 1 이상을 내는지 본다. 행을 넣는 시험은 롤백한다(command_journal 은
 * append-only). psql 두 벌 대조만 커밋이 필요해 시험마다 같은 컨테이너에 별도 데이터베이스를 만든다.
 */
@Testcontainers
class InvariantBandAxisIntegrationTest {

    /** 개정 전 술어. 커트오버 전 동치의 대조 기준이고 판정에는 쓰지 않는다. */
    static final String LEGACY_C05 =
            "SELECT COALESCE((max(seq) - min(seq) + 1) - count(*), 0) FROM command_journal";
    static final String LEGACY_C15 = """
            SELECT (SELECT count(*) FROM ledger_entries_default)
                 + (SELECT count(*) FROM command_journal_default)
                 + (SELECT count(*) FROM event_journal_default)
                 + (SELECT count(*) FROM trades_default)
                 + (SELECT count(*) FROM outbox_default)
            """;
    static final String LEGACY_C22 = """
            SELECT count(*) FROM (
                SELECT seq, fencing_token,
                       lag(fencing_token) OVER (ORDER BY seq) AS prev
                FROM command_journal
            ) t
            WHERE t.prev > t.fencing_token
            """;
    static final String LEGACY_C23 = """
            SELECT (
                SELECT count(*) FROM journal_counters c
                WHERE c.name = 'command_seq'
                  AND c.next_value <= (SELECT COALESCE(max(seq), 0) FROM command_journal)
            ) + (
                SELECT count(*) FROM market_journal_counters m
                WHERE m.name = 'event_seq'
                  AND m.next_value <= (SELECT COALESCE(max(e.market_seq), 0)
                                       FROM event_journal e
                                       WHERE e.market_id = m.market_id
                                         AND e.market_seq IS NOT NULL)
            )
            """;

    /** C22 의 두 팔. C22 시험마다 두 팔의 합이 C22 값과 같음을 단언해 enum 본문과 묶는다. */
    private static final String ARM_TIME = """
            SELECT count(*) FROM (
                SELECT fencing_token,
                       lag(fencing_token) OVER (ORDER BY created_at, seq) AS prev
                FROM command_journal
            ) t
            WHERE t.prev > t.fencing_token
            """;
    private static final String ARM_MARKET = """
            SELECT count(*) FROM (
                SELECT fencing_token,
                       lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq) AS prev
                FROM command_journal
            ) t
            WHERE t.prev > t.fencing_token
            """;

    private static final List<InvariantCheck> BAND_AXIS_CHECKS = List.of(
            C05_CMD_GAPLESS, C15_DEFAULT_PARTITION, C22_FENCING_MONOTONIC,
            C23_COUNTER_SEQ_CONSISTENCY, C27_CMD_DEFAULT_PARTITION);

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static long span;

    private record Cmd(long seq, int market, long epoch, int second) {
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection conn) throws Exception;
    }

    @BeforeAll
    static void prepare() throws Exception {
        prepareDatabase(POSTGRES.getJdbcUrl());
        try (Connection conn = open(POSTGRES.getJdbcUrl())) {
            span = queryLong(conn, "SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'");
        }
        String dir = System.getProperty("harness.sql.dir");
        assertNotNull(dir, "시스템 속성 harness.sql.dir 없음(server/app/build.gradle.kts 가 넘긴다)");
        for (String file : List.of("summary.sql", "invariants.sql")) {
            Path path = Path.of(dir, file);
            assertTrue(Files.isRegularFile(path), "하네스 SQL 없음: " + path);
            POSTGRES.copyFileToContainer(MountableFile.forHostPath(path), "/tmp/" + file);
        }
    }

    // ── 커트오버 전 동치 ─────────────────────────────────────────────────

    @Test
    void c05LegacyTermEqualsGlobalPredicateOnDenseAxis() throws Exception {
        inRollback(conn -> {
            insert(conn, legacyAxis());
            assertLegacyAxis(conn, 8);
            assertEquals(0, queryLong(conn, LEGACY_C05));
            assertEquals(queryLong(conn, LEGACY_C05), eval(conn, C05_CMD_GAPLESS));
        });
        inRollback(conn -> {
            List<Cmd> holed = new ArrayList<>(legacyAxis());
            holed.removeIf(r -> r.seq() == 5);
            insert(conn, holed);
            assertLegacyAxis(conn, 7);
            assertEquals(1, queryLong(conn, LEGACY_C05), "결번 합성을 구 술어가 놓침");
            assertEquals(queryLong(conn, LEGACY_C05), eval(conn, C05_CMD_GAPLESS));
        });
    }

    @Test
    void c15PlusC27EqualsLegacyC15() throws Exception {
        inRollback(conn -> {
            insert(conn, legacyAxis());
            assertLegacyAxis(conn, 8);
            assertEquals(0, eval(conn, C15_DEFAULT_PARTITION));
            assertEquals(0, eval(conn, C27_CMD_DEFAULT_PARTITION));
            assertEquals(queryLong(conn, LEGACY_C15),
                    eval(conn, C15_DEFAULT_PARTITION) + eval(conn, C27_CMD_DEFAULT_PARTITION));

            // outbox 는 created_at 범위 파티션이고 2000-01 을 덮는 잎이 없다.
            assertEquals("outbox_default", queryString(conn, """
                    INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
                    VALUES ('TRADE', 'band-axis-probe', 'TradeExecuted', '{}', TIMESTAMPTZ '2000-01-01 00:00:00+00')
                    RETURNING tableoid::regclass::text
                    """));
            assertEquals(1, queryLong(conn, LEGACY_C15), "DEFAULT 유입을 구 C15 가 놓침");
            assertEquals(1, eval(conn, C15_DEFAULT_PARTITION));
            assertEquals(0, eval(conn, C27_CMD_DEFAULT_PARTITION));
            assertEquals(queryLong(conn, LEGACY_C15),
                    eval(conn, C15_DEFAULT_PARTITION) + eval(conn, C27_CMD_DEFAULT_PARTITION));
        });
    }

    /**
     * 동치는 시간 축 팔과 구 값 사이에만 선다. 구 값이 0 이면 마켓 안 팔도 0 이지만, 구 값이 0 보다 크면
     * 마켓 안 팔과 합산에는 구 값 대비 상한이 없다.
     */
    @Test
    void c22ArmsMatchLegacyPerArm() throws Exception {
        inRollback(conn -> {
            insert(conn, legacyAxis());
            assertLegacyAxis(conn, 8);
            assertEquals(0, queryLong(conn, LEGACY_C22));
            assertEquals(queryLong(conn, LEGACY_C22), queryLong(conn, ARM_TIME));
            assertEquals(0, queryLong(conn, ARM_MARKET));
            assertC22IsArmSum(conn);
        });
        inRollback(conn -> {
            List<Cmd> rows = new ArrayList<>(legacyAxis());
            rows.set(7, new Cmd(8, 2, 1, 8));
            insert(conn, rows);
            assertLegacyAxis(conn, 8);
            assertEquals(1, queryLong(conn, LEGACY_C22), "epoch 역행 합성을 구 술어가 놓침");
            assertEquals(1, queryLong(conn, ARM_TIME));
            assertEquals(1, queryLong(conn, ARM_MARKET));
            assertEquals(2, eval(conn, C22_FENCING_MONOTONIC));
            assertC22IsArmSum(conn);
        });
        inRollback(conn -> {
            // 전역 역행 하나가 두 마켓에서 각각 역행으로 세어진다.
            insert(conn, legacyAxis(1, 1, 3, 3, 2, 2, 3, 3));
            assertLegacyAxis(conn, 8);
            assertEquals(1, queryLong(conn, LEGACY_C22));
            assertEquals(queryLong(conn, LEGACY_C22), queryLong(conn, ARM_TIME));
            assertEquals(2, queryLong(conn, ARM_MARKET), "마켓 안 팔이 구 값을 넘는 모양");
            assertEquals(3, eval(conn, C22_FENCING_MONOTONIC));
            assertC22IsArmSum(conn);
        });
    }

    @Test
    void c23LegacyAndEventArmsEqualLegacyPredicate() throws Exception {
        inRollback(conn -> {
            insert(conn, legacyAxis());
            setGlobalCounter(conn, 9);
            assertLegacyAxis(conn, 8);
            assertEquals(0, queryLong(conn, LEGACY_C23));
            assertEquals(queryLong(conn, LEGACY_C23), eval(conn, C23_COUNTER_SEQ_CONSISTENCY));

            insertEvent(conn, 1, eventCounter(conn, 1));
            assertEquals(1, queryLong(conn, LEGACY_C23), "이벤트 카운터 재사용을 구 술어가 놓침");
            assertEquals(queryLong(conn, LEGACY_C23), eval(conn, C23_COUNTER_SEQ_CONSISTENCY));

            setGlobalCounter(conn, 1);
            assertEquals(2, queryLong(conn, LEGACY_C23), "카운터 역행을 구 술어가 놓침");
            assertEquals(queryLong(conn, LEGACY_C23), eval(conn, C23_COUNTER_SEQ_CONSISTENCY));
        });
    }

    // ── 위반 합성 검출 ───────────────────────────────────────────────────

    @Test
    void c05BandTermDetectsHoleInBandLeaf() throws Exception {
        inRollback(conn -> {
            insert(conn, bandAxis());
            assertBandAxis(conn, 5);
            assertTrue(queryLong(conn, LEGACY_C05) > 0, "대역 행에서 구 C05 가 위반을 내야 대역 축을 밟은 것이다");
            assertEquals(0, eval(conn, C05_CMD_GAPLESS));
        });
        inRollback(conn -> {
            List<Cmd> rows = new ArrayList<>(bandAxis());
            rows.replaceAll(r -> r.seq() == span + 3 ? new Cmd(span + 4, 1, 2, 11) : r);
            insert(conn, rows);
            assertBandAxis(conn, 5);
            assertEquals(1, eval(conn, C05_CMD_GAPLESS));
        });
    }

    @Test
    void c23BandArmCatchesMarketCounterBelowLocalMax() throws Exception {
        inRollback(conn -> {
            loadBandAxis(conn);
            assertEquals(3, queryLong(conn,
                    "SELECT count(*) FROM command_journal WHERE market_id = 1 AND seq >= " + span));
            assertEquals(1, queryLong(conn, LEGACY_C23), "대역 행에서 구 C23 이 위반을 내야 대역 축을 밟은 것이다");
            assertEquals(0, eval(conn, C23_COUNTER_SEQ_CONSISTENCY));

            setMarketCounter(conn, 1, 3);
            assertEquals(1, eval(conn, C23_COUNTER_SEQ_CONSISTENCY));
            setMarketCounter(conn, 1, 4);
            assertEquals(0, eval(conn, C23_COUNTER_SEQ_CONSISTENCY));
        });
    }

    /** 승격 뒤 구 리더가 다른 마켓에 쓴 모양. 마켓 안 팔만으로는 잡지 못한다(ADR 0022 10-8). */
    @Test
    void c22TimeArmDetectsCrossMarketEpochRegression() throws Exception {
        inRollback(conn -> {
            List<Cmd> rows = new ArrayList<>(legacyPrefix(6));
            rows.add(new Cmd(span + 1, 1, 2, 10));
            rows.add(new Cmd(2 * span + 1, 2, 1, 11));
            insert(conn, rows);
            assertBandAxis(conn, 2);
            assertEquals(1, queryLong(conn, ARM_TIME));
            assertEquals(0, queryLong(conn, ARM_MARKET));
            assertEquals(1, eval(conn, C22_FENCING_MONOTONIC));
            assertC22IsArmSum(conn);
        });
    }

    @Test
    void c22InMarketArmDetectsEpochRegressionHiddenByClock() throws Exception {
        inRollback(conn -> {
            List<Cmd> rows = new ArrayList<>(legacyPrefix(6));
            rows.add(new Cmd(span + 1, 1, 2, 20));
            rows.add(new Cmd(span + 2, 1, 1, 15));
            insert(conn, rows);
            assertEquals(2, queryLong(conn, "SELECT count(*) FROM command_journal WHERE seq >= " + span));
            assertEquals(2, queryLong(conn, "SELECT count(DISTINCT fencing_token) FROM command_journal"));
            assertEquals(0, queryLong(conn, ARM_TIME));
            assertEquals(1, queryLong(conn, ARM_MARKET));
            assertEquals(1, eval(conn, C22_FENCING_MONOTONIC));
            assertC22IsArmSum(conn);
        });
    }

    /**
     * ADR 0022 9-17 의 거짓 양성을 고정한다. 구 epoch 행은 마켓 안 순서가 맞고 시계만 뒤로 갔다.
     * 이 한계를 없애는 변경이 오면 이 시험과 9-17 문구를 함께 고친다.
     */
    @Test
    void c22TimeArmCountsClockRegressionWithoutSplitBrain() throws Exception {
        inRollback(conn -> {
            List<Cmd> rows = new ArrayList<>(legacyPrefix(6));
            rows.add(new Cmd(span + 1, 1, 1, 30));
            rows.add(new Cmd(2 * span + 1, 2, 2, 25));
            insert(conn, rows);
            assertBandAxis(conn, 2);
            assertEquals(1, queryLong(conn, ARM_TIME));
            assertEquals(0, queryLong(conn, ARM_MARKET));
            assertC22IsArmSum(conn);
        });
    }

    @Test
    void c27DetectsDefaultPartitionRow() throws Exception {
        inRollback(conn -> {
            insert(conn, legacyAxis());
            assertLegacyAxis(conn, 8);
            assertEquals(0, eval(conn, C27_CMD_DEFAULT_PARTITION));
            assertEquals(0, queryLong(conn, "SELECT count(*) FROM markets WHERE id = 9"));

            assertEquals("command_journal_default", insertRow(conn, new Cmd(9 * span + 1, 9, 3, 9)));
            assertEquals(1, eval(conn, C27_CMD_DEFAULT_PARTITION));
            assertEquals(0, eval(conn, C15_DEFAULT_PARTITION), "명령 축 DEFAULT 행을 C15 가 이중 계상");
            assertEquals(1, queryLong(conn, LEGACY_C15));
            assertEquals(queryLong(conn, LEGACY_C15),
                    eval(conn, C15_DEFAULT_PARTITION) + eval(conn, C27_CMD_DEFAULT_PARTITION));
        });
        try (Connection conn = open(POSTGRES.getJdbcUrl())) {
            assertEquals(0, eval(conn, C27_CMD_DEFAULT_PARTITION), "롤백 뒤 C27 clean 미복귀");
        }
    }

    @Test
    void bandSpecMissingFailsClosed() throws Exception {
        inRollback(conn -> {
            insert(conn, legacyAxis());
            setGlobalCounter(conn, 9);
            assertLegacyAxis(conn, 8);
            assertEquals(0, eval(conn, C05_CMD_GAPLESS));
            assertEquals(0, eval(conn, C23_COUNTER_SEQ_CONSISTENCY));

            assertEquals(1, update(conn, "DELETE FROM journal_band_spec WHERE name = 'command_seq'"));
            assertEquals(1, eval(conn, C05_CMD_GAPLESS));
            assertEquals(1, eval(conn, C23_COUNTER_SEQ_CONSISTENCY));
        });
    }

    // ── 대역 행 거짓 양성 0 ──────────────────────────────────────────────

    /** 0 이 데이터 부족이 아니라 술어 개정 덕임을 같은 데이터의 구 술어 위반으로 보인다. */
    @Test
    void bandRowsProduceNoFalseCriticalWhereLegacyPredicatesFail() throws Exception {
        inRollback(conn -> {
            loadBandAxis(conn);
            assertBandAxis(conn, 5);
            assertTrue(queryLong(conn, LEGACY_C05) > 0, "구 C05 가 대역 사이를 결번으로 세야 한다");
            assertTrue(queryLong(conn, LEGACY_C22) > 0, "구 C22 가 seq 순서의 마켓 경계를 역행으로 세야 한다");
            assertEquals(1, queryLong(conn, LEGACY_C23), "구 C23 이 얼어붙은 전역 카운터를 위반으로 세야 한다");

            assertEquals(0, eval(conn, C05_CMD_GAPLESS));
            assertEquals(0, queryLong(conn, ARM_TIME));
            assertEquals(0, queryLong(conn, ARM_MARKET));
            assertEquals(0, eval(conn, C22_FENCING_MONOTONIC));
            assertEquals(0, eval(conn, C23_COUNTER_SEQ_CONSISTENCY));
            assertEquals(0, eval(conn, C27_CMD_DEFAULT_PARTITION));
        });
    }

    // ── 세 벌 대조 ───────────────────────────────────────────────────────

    @Test
    void summaryAndInvariantsSqlMatchInvariantCheckOnLegacyAxis() throws Exception {
        String db = "par_leg";
        try (Connection conn = open(createDatabase(db))) {
            insert(conn, legacyAxis());
            setGlobalCounter(conn, 9);
            assertLegacyAxis(conn, 8);
            Map<InvariantCheck, Map<String, Long>> arms = assertThreeCopiesAgree(conn, db);
            BAND_AXIS_CHECKS.forEach(check -> assertEquals(Map.of(), arms.get(check), check.name()));
        }
    }

    @Test
    void summaryAndInvariantsSqlMatchInvariantCheckOnBandAxis() throws Exception {
        String db = "par_band";
        try (Connection conn = open(createDatabase(db))) {
            loadBandAxis(conn);
            assertBandAxis(conn, 5);
            assertTrue(queryLong(conn, LEGACY_C05) > 0);
            assertTrue(queryLong(conn, LEGACY_C22) > 0);
            assertEquals(1, queryLong(conn, LEGACY_C23));
            Map<InvariantCheck, Map<String, Long>> arms = assertThreeCopiesAgree(conn, db);
            BAND_AXIS_CHECKS.forEach(check -> assertEquals(Map.of(), arms.get(check), check.name()));
        }
    }

    /**
     * 0 끼리의 일치는 대조가 아니므로 모든 팔이 위반을 내는 데이터에서 맞춘다. 한 체크 안의 팔 크기를 달리해
     * 어느 사본에서 팔 하나가 빠져도 합계나 팔별 값이 어긋나게 한다. 명세 부재 팔은 par_nospec 이 본다.
     */
    @Test
    void threeCopiesAgreeWithEveryArmNonZero() throws Exception {
        String db = "par_viol";
        try (Connection conn = open(createDatabase(db))) {
            List<Cmd> rows = new ArrayList<>(legacyPrefix(6));
            rows.removeIf(r -> r.seq() == 3 || r.seq() == 4);
            rows.add(new Cmd(span + 1, 1, 1, 7));
            rows.add(new Cmd(2 * span + 1, 2, 1, 8));
            rows.add(new Cmd(span + 2, 1, 1, 9));
            rows.add(new Cmd(span + 5, 1, 1, 10));
            rows.add(new Cmd(span + 4, 1, 2, 11));
            rows.add(new Cmd(2 * span + 3, 2, 1, 12));
            rows.add(new Cmd(2 * span + 2, 2, 2, 20));
            insert(conn, rows);
            assertEquals("command_journal_default", insertRow(conn, new Cmd(9 * span + 1, 9, 1, 14)));
            assertEquals("outbox_default", queryString(conn, """
                    INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
                    VALUES ('TRADE', 'band-axis-parity', 'TradeExecuted', '{}', TIMESTAMPTZ '2000-01-01 00:00:00+00')
                    RETURNING tableoid::regclass::text
                    """));
            insertEvent(conn, 1, eventCounter(conn, 1));
            setGlobalCounter(conn, 6);
            setMarketCounter(conn, 1, 5);
            setMarketCounter(conn, 2, 3);
            assertBandAxis(conn, 8);

            assertEquals(3, eval(conn, C05_CMD_GAPLESS), "레거시 결번 2 + 마켓 1 대역 결번 1");
            assertEquals(1, queryLong(conn, ARM_TIME), "마켓 사이 epoch 역행");
            assertEquals(2, queryLong(conn, ARM_MARKET), "마켓 1 은 시계가 가린 역행, 마켓 2 는 마켓 사이 역행과 겹침");
            assertEquals(3, eval(conn, C22_FENCING_MONOTONIC));
            assertC22IsArmSum(conn);
            assertEquals(4, eval(conn, C23_COUNTER_SEQ_CONSISTENCY), "레거시 1 + 대역 2 + 이벤트 1");
            assertEquals(1, eval(conn, C15_DEFAULT_PARTITION), "outbox DEFAULT 행");
            assertEquals(1, eval(conn, C27_CMD_DEFAULT_PARTITION), "command_journal DEFAULT 행");

            Map<InvariantCheck, Map<String, Long>> arms = assertThreeCopiesAgree(conn, db);
            assertEquals(Map.of("legacy", 2L, "band", 1L), arms.get(C05_CMD_GAPLESS));
            assertEquals(Map.of("time", 1L, "market", 2L), arms.get(C22_FENCING_MONOTONIC));
            assertEquals(Map.of("legacy", 1L, "band", 2L, "event", 1L), arms.get(C23_COUNTER_SEQ_CONSISTENCY));
            assertEquals(Map.of("outbox_default", 1L), arms.get(C15_DEFAULT_PARTITION));
            assertEquals(Map.of("command_journal_default", 1L), arms.get(C27_CMD_DEFAULT_PARTITION));
        }
    }

    @Test
    void summaryReportsBandSpecMissingAsFail() throws Exception {
        String db = "par_nospec";
        try (Connection conn = open(createDatabase(db))) {
            insert(conn, legacyAxis());
            setGlobalCounter(conn, 9);
            assertLegacyAxis(conn, 8);
            assertEquals(1, update(conn, "DELETE FROM journal_band_spec WHERE name = 'command_seq'"));

            Map<InvariantCheck, Map<String, Long>> arms = assertThreeCopiesAgree(conn, db);
            assertEquals(Map.of("band_spec_missing", 1L), arms.get(C05_CMD_GAPLESS));
            assertEquals(Map.of("band_spec_missing", 1L), arms.get(C23_COUNTER_SEQ_CONSISTENCY));
            assertEquals("FAIL", summaryRows(db).get("C05")[5]);
            assertEquals("FAIL", summaryRows(db).get("C23")[5]);
        }
    }

    // ── 데이터 ───────────────────────────────────────────────────────────

    /** seq 1..n, 마켓 1·2 교대, epoch 1, created_at 은 seq 초. */
    private static List<Cmd> legacyPrefix(int n) {
        List<Cmd> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            rows.add(new Cmd(i, i % 2 == 1 ? 1 : 2, 1, i));
        }
        return rows;
    }

    /** 전역 조밀 축. 두 마켓이 seq 1..8 을 번갈아 쓰고 epoch 가 1, 2, 3 으로 두 번 바뀐다. */
    private static List<Cmd> legacyAxis() {
        return legacyAxis(1, 1, 1, 2, 2, 2, 3, 3);
    }

    /** 두 마켓이 seq 1..n 을 번갈아 쓰고 i 번째 행의 epoch 가 epochs[i - 1] 인 전역 조밀 축. */
    private static List<Cmd> legacyAxis(long... epochs) {
        List<Cmd> rows = new ArrayList<>();
        for (int i = 1; i <= epochs.length; i++) {
            rows.add(new Cmd(i, i % 2 == 1 ? 1 : 2, epochs[i - 1], i));
        }
        return rows;
    }

    /**
     * 레거시 6행 뒤 두 마켓 대역 행. 10초에 승격해 앞은 epoch 1, 뒤는 epoch 2 다. seq 순서로는 마켓 1 의
     * epoch 2 행 다음에 마켓 2 의 epoch 1 행이 온다.
     */
    private static List<Cmd> bandAxis() {
        List<Cmd> rows = new ArrayList<>(legacyPrefix(6));
        rows.add(new Cmd(span + 1, 1, 1, 7));
        rows.add(new Cmd(2 * span + 1, 2, 1, 8));
        rows.add(new Cmd(span + 2, 1, 1, 9));
        rows.add(new Cmd(span + 3, 1, 2, 11));
        rows.add(new Cmd(2 * span + 2, 2, 2, 12));
        return rows;
    }

    /** bandAxis 와 채번이 정상 진행한 카운터(전역 7, 마켓 1 은 4, 마켓 2 는 3). */
    private static void loadBandAxis(Connection conn) throws SQLException {
        insert(conn, bandAxis());
        setGlobalCounter(conn, 7);
        setMarketCounter(conn, 1, 4);
        setMarketCounter(conn, 2, 3);
    }

    private static void assertLegacyAxis(Connection conn, long rows) throws SQLException {
        assertEquals(rows, queryLong(conn, "SELECT count(*) FROM command_journal"), "합성 행 수");
        assertEquals(2, queryLong(conn, "SELECT count(DISTINCT market_id) FROM command_journal"));
        assertTrue(queryLong(conn, "SELECT count(DISTINCT fencing_token) FROM command_journal") >= 2,
                "epoch 전이가 있어야 C22 가 공허하지 않다");
        assertEquals(0, queryLong(conn, "SELECT count(*) FROM command_journal WHERE seq >= " + span),
                "대역 행이 없어야 커트오버 전 축이다");
    }

    private static void assertBandAxis(Connection conn, long bandRows) throws SQLException {
        assertEquals(bandRows, queryLong(conn, "SELECT count(*) FROM command_journal WHERE seq >= " + span),
                "대역 행 수");
        assertTrue(queryLong(conn,
                        "SELECT count(DISTINCT market_id) FROM command_journal WHERE seq >= " + span) >= 2,
                "대역 행이 두 마켓 이상에 있어야 마켓별 항이 공허하지 않다");
        assertTrue(queryLong(conn, "SELECT count(*) FROM command_journal WHERE seq < " + span) > 0,
                "레거시 행");
        assertTrue(queryLong(conn, "SELECT count(DISTINCT fencing_token) FROM command_journal") >= 2,
                "epoch 전이");
    }

    private static void assertC22IsArmSum(Connection conn) throws SQLException {
        assertEquals(queryLong(conn, ARM_TIME) + queryLong(conn, ARM_MARKET), eval(conn, C22_FENCING_MONOTONIC),
                "C22 값이 두 팔의 합이 아니다");
    }

    private static void insert(Connection conn, List<Cmd> rows) throws SQLException {
        for (Cmd row : rows) {
            assertFalse("command_journal_default".equals(insertRow(conn, row)), "합성 행이 DEFAULT 로 떨어짐: " + row);
        }
    }

    /** CANCEL 로 넣어 NEW 만 보는 체크(C07P, C13A)를 건드리지 않는다. 담긴 잎 이름을 돌려준다. */
    private static String insertRow(Connection conn, Cmd row) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                             assigned_order_id, engine_ts, tie_break, payload,
                                             fencing_token, checksum, created_at)
                VALUES (?, ?, 'CANCEL', 1, NULL, ?, 0, 0, '{}', ?, 0, ?)
                RETURNING tableoid::regclass::text
                """)) {
            ps.setLong(1, row.seq());
            ps.setInt(2, row.market());
            ps.setLong(3, row.seq());
            ps.setLong(4, row.epoch());
            ps.setObject(5, T0.plusSeconds(row.second()));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "command_journal 삽입이 행을 반환하지 않음");
                return rs.getString(1);
            }
        }
    }

    /** event_seq 는 V36 규약대로 market_id * 10^7 + market_seq 로 파생한다. */
    private static void insertEvent(Connection conn, int market, long marketSeq) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
                                           fencing_epoch, created_at, market_id, market_seq)
                VALUES (?, 1, 0, 'ACCEPTED', '{}', 1, ?, ?, ?)
                """)) {
            ps.setLong(1, market * 10_000_000L + marketSeq);
            ps.setObject(2, T0);
            ps.setInt(3, market);
            ps.setLong(4, marketSeq);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static long eventCounter(Connection conn, int market) throws SQLException {
        return queryLong(conn, "SELECT next_value FROM market_journal_counters WHERE market_id = " + market
                + " AND name = 'event_seq'");
    }

    private static void setGlobalCounter(Connection conn, long next) throws SQLException {
        assertEquals(1, update(conn,
                "UPDATE journal_counters SET next_value = " + next + " WHERE name = 'command_seq'"));
    }

    private static void setMarketCounter(Connection conn, int market, long next) throws SQLException {
        assertEquals(1, update(conn, "UPDATE market_journal_counters SET next_value = " + next
                + " WHERE market_id = " + market + " AND name = 'command_seq'"));
    }

    // ── 세 벌 대조 도우미 ────────────────────────────────────────────────

    /**
     * 다섯 체크마다 InvariantCheck, summary.sql, invariants.sql 합계가 같음을 단언하고 invariants.sql 의
     * 팔별 값(값 0 인 팔은 행이 없어 빠진다)을 돌려준다. 합계만으로는 0 인 팔의 누락을 잡지 못한다.
     */
    private static Map<InvariantCheck, Map<String, Long>> assertThreeCopiesAgree(Connection conn, String db)
            throws Exception {
        Map<String, String[]> summary = summaryRows(db);
        Map<String, List<String[]>> sections = invariantsSections(db);
        Map<InvariantCheck, Map<String, Long>> arms = new EnumMap<>(InvariantCheck.class);
        for (InvariantCheck check : BAND_AXIS_CHECKS) {
            String code = check.name().substring(0, 3);
            long app = eval(conn, check);
            String[] row = summary.get(code);
            assertNotNull(row, "summary.sql 에 " + code + " 행 없음");
            List<String[]> section = sections.get(code);
            assertNotNull(section, "invariants.sql 에 " + code + " 절 없음");
            Map<String, Long> checkArms = invariantsArms(check, section);
            assertEquals(app, Long.parseLong(row[4]), code + " InvariantCheck 대 summary.sql");
            assertEquals(app, checkArms.values().stream().mapToLong(Long::longValue).sum(),
                    code + " InvariantCheck 대 invariants.sql");
            assertEquals(app == 0 ? "PASS" : "FAIL", row[5], code + " summary.sql verdict");
            arms.put(check, checkArms);
        }
        return arms;
    }

    /** code 별 행. 한 code 에 행이 둘이거나 열 수가 다르면 실패다. */
    private static Map<String, String[]> summaryRows(String db) throws Exception {
        Map<String, String[]> rows = new HashMap<>();
        for (String line : psql(db, "summary.sql")) {
            String[] fields = line.split("\\|", -1);
            assertEquals(6, fields.length, "summary.sql 행 형식 불일치: " + line);
            assertNull(rows.put(fields[0], fields), "summary.sql 에 같은 code 가 둘: " + fields[0]);
        }
        return rows;
    }

    /** '== <code> ' 머리 줄마다 그 뒤 결과 행을 모은다. */
    private static Map<String, List<String[]>> invariantsSections(String db) throws Exception {
        Map<String, List<String[]>> sections = new HashMap<>();
        List<String[]> current = null;
        for (String line : psql(db, "invariants.sql")) {
            if (line.startsWith("== ")) {
                current = new ArrayList<>();
                String code = line.substring(3, line.indexOf(' ', 3));
                assertNull(sections.put(code, current), "invariants.sql 에 같은 머리가 둘: " + code);
            } else {
                assertNotNull(current, "invariants.sql 첫 머리 앞에 출력: " + line);
                current.add(line.split("\\|", -1));
            }
        }
        return sections;
    }

    /** 첫 열(팔 또는 파티션 이름)별 값. C05 는 gaps 열 합, C15·C27 은 rows 열 합, C22·C23 은 행 수다. */
    private static Map<String, Long> invariantsArms(InvariantCheck check, List<String[]> rows) {
        Map<String, Long> arms = new HashMap<>();
        for (String[] f : rows) {
            long value = switch (check) {
                case C05_CMD_GAPLESS -> {
                    assertRow(check, f, 3, Set.of("band_spec_missing", "legacy", "band"));
                    yield Long.parseLong(f[2]);
                }
                case C15_DEFAULT_PARTITION, C27_CMD_DEFAULT_PARTITION -> {
                    assertEquals(2, f.length, check.name() + " 행 형식: " + String.join("|", f));
                    assertTrue(f[0].endsWith("_default"), check.name() + " 행 형식: " + String.join("|", f));
                    yield Long.parseLong(f[1]);
                }
                case C22_FENCING_MONOTONIC -> {
                    assertRow(check, f, 6, Set.of("time", "market"));
                    yield 1;
                }
                case C23_COUNTER_SEQ_CONSISTENCY -> {
                    assertTrue(Set.of("band_spec_missing", "legacy", "band", "event").contains(f[0]),
                            check.name() + " 행 형식: " + String.join("|", f));
                    yield 1;
                }
                default -> throw new IllegalArgumentException(check.name());
            };
            arms.merge(f[0], value, Long::sum);
        }
        return arms;
    }

    private static void assertRow(InvariantCheck check, String[] f, int width, Set<String> heads) {
        assertEquals(width, f.length, check.name() + " 행 형식: " + String.join("|", f));
        assertTrue(heads.contains(f[0]), check.name() + " 행 형식: " + String.join("|", f));
    }

    /** 종료코드를 보지 않으면 문법 오류가 빈 출력, 곧 위반 0 으로 읽힌다. */
    private static List<String> psql(String db, String file) throws Exception {
        String uri = "postgresql://%s:%s@localhost:%d/%s".formatted(POSTGRES.getUsername(), POSTGRES.getPassword(),
                PostgreSQLContainer.POSTGRESQL_PORT, db);
        ExecResult result = POSTGRES.execInContainer("psql", "-X", "-d", uri,
                "-v", "ON_ERROR_STOP=1", "-Atq", "-f", "/tmp/" + file);
        assertEquals(0, result.getExitCode(), file + " 실행 실패: " + result.getStderr());
        return result.getStdout().lines().toList();
    }

    // ── JDBC ─────────────────────────────────────────────────────────────

    private static void inRollback(SqlWork work) throws Exception {
        try (Connection conn = open(POSTGRES.getJdbcUrl())) {
            conn.setAutoCommit(false);
            try {
                work.run(conn);
            } finally {
                conn.rollback();
            }
        }
    }

    /** 이름으로 데이터베이스를 만들고 전 마이그레이션과 자산·마켓 1·2 를 올린 뒤 JDBC URL 을 돌려준다. */
    private static String createDatabase(String name) throws SQLException {
        try (Connection conn = open(POSTGRES.getJdbcUrl()); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        String url = POSTGRES.getJdbcUrl().replace("/exchange", "/" + name);
        prepareDatabase(url);
        return url;
    }

    /** 마켓 행 삽입 트리거가 대역 잎과 두 카운터를 만든다(V40). */
    private static void prepareDatabase(String url) throws SQLException {
        var result = Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertTrue(result.success, "Flyway migration failed");
        try (Connection conn = open(url); Statement st = conn.createStatement()) {
            st.execute("""
                    INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                        (1, 'KRW', 'Korean Won', 'FIAT', 0),
                        (2, 'BTC', 'Bitcoin', 'CRYPTO', 8)
                    ON CONFLICT (id) DO NOTHING
                    """);
            for (int id = 1; id <= 2; id++) {
                st.execute("""
                        INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                             tick_size, lot_size, min_notional, max_notional, max_qty)
                        VALUES (%d, 'BAND-%d', 2, 1, 1000, 0.0001, 5000, 100000000000, 10000)
                        ON CONFLICT (id) DO NOTHING
                        """.formatted(id, id));
            }
        }
    }

    private static long eval(Connection conn, InvariantCheck check) throws SQLException {
        return queryLong(conn, check.sql());
    }

    private static Connection open(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getLong(1);
        }
    }

    private static String queryString(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getString(1);
        }
    }

    private static int update(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate(sql);
        }
    }
}

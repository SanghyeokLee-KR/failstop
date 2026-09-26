package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 경보 폴러의 사건 열기·닫기(설계 6-1, DB 설계 §10.3). Alertmanager 는 로컬 스텁이고 시계는 시험이 움직인다.
 * 해제는 성공한 폴링에서 150초 연속으로 보이지 않고 그 사이 Alertmanager 가 재시작하지 않았을 때만 한다.
 */
@Testcontainers
class IncidentPollerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");
    private static final String A = "a000000000000001";
    private static final String B = "b000000000000002";

    static JdbcClient jdbc;
    static IncidentStore store;

    private StubSource am;
    private TestClock clock;
    private IncidentPoller poller;
    private volatile List<String> active = new ArrayList<>();
    private volatile boolean failing;
    private volatile Instant amStarted = T0.minusSeconds(3600);

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(ds);
        store = new IncidentStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)),
                new ObjectMapper());
    }

    @BeforeEach
    void start() throws Exception {
        jdbc.sql("ALTER TABLE ops_incident_events DISABLE TRIGGER USER").update();
        jdbc.sql("DELETE FROM ops_incident_events").update();
        jdbc.sql("ALTER TABLE ops_incident_events ENABLE TRIGGER USER").update();
        jdbc.sql("DELETE FROM ops_incidents").update();
        am = new StubSource();
        am.handler = r -> {
            if (r.path().equals("/api/v2/status")) {
                return StubSource.Response.json("{\"uptime\":\"" + amStarted + "\",\"config\":{\"original\":\"secret\"}}");
            }
            if (failing) {
                return StubSource.Response.status(500, "down");
            }
            return StubSource.Response.json("[" + String.join(",", active) + "]");
        };
        clock = new TestClock(T0);
        poller = new IncidentPoller(new AlertmanagerClient(am.uri(), Duration.ofSeconds(2), new ObjectMapper()),
                store, clock, Duration.ofSeconds(150), List.of("Watchdog"));
    }

    @AfterEach
    void stop() {
        am.close();
    }

    @Test
    void 처음_보인_경보는_OPEN_사건과_OPENED_이벤트가_되고_지난_폴링_결과에_남는다() {
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00.123Z"));

        poller.pollOnce();

        assertEquals(1, count("SELECT count(*) FROM ops_incidents WHERE fingerprint = '" + A + "' AND status = 'OPEN' "
                + "AND severity = 'warning' AND alertname = 'LinuxDiskSpaceLow'"));
        assertEquals(1, count("SELECT count(*) FROM ops_incident_events WHERE kind = 'OPENED'"));
        assertTrue(poller.last().healthy());
        assertEquals(1, poller.last().alerts().size());

        poller.pollOnce();
        assertEquals(1, count("SELECT count(*) FROM ops_incidents"), "같은 (지문, 시작 시각)은 새 사건이 아니다");
    }

    @Test
    void 연속_150초_부재_뒤에만_닫고_해제_시각은_처음_빠진_폴링_시각이다() {
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        poller.pollOnce();
        active = List.of();
        int polls = 1;
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(15));
            poller.pollOnce();
            polls++;
        }
        assertEquals("OPEN", status(A), "처음 빠진 뒤 135초는 아직 닫지 않는다");

        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();
        polls++;

        assertEquals(12, polls);
        assertEquals("RESOLVED", status(A));
        assertEquals(1, count("SELECT count(*) FROM ops_incidents WHERE resolved_at = '" + T0.plusSeconds(15) + "'"));
        assertEquals(1, count("SELECT count(*) FROM ops_incident_events WHERE kind = 'RESOLVED'"));
    }

    @Test
    void 폴링이_실패하면_닫지_않고_부재를_처음부터_다시_센다() {
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        poller.pollOnce();
        active = List.of();
        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();

        failing = true;
        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofSeconds(15));
            poller.pollOnce();
        }
        assertFalse(poller.last().healthy());
        assertEquals("OPEN", status(A), "모르는 것을 해제로 적지 않는다");

        failing = false;
        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();
        assertEquals("OPEN", status(A), "실패 뒤 첫 부재부터 다시 센다");
        clock.advance(Duration.ofSeconds(150));
        poller.pollOnce();
        assertEquals("RESOLVED", status(A));
    }

    @Test
    void 빈_목록이_잠시_이어진_뒤_다시_보이면_닫지_않는다() {
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        poller.pollOnce();
        active = List.of();
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(15));
            poller.pollOnce();
        }
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();
        active = List.of();
        for (int i = 0; i < 9; i++) {
            clock.advance(Duration.ofSeconds(15));
            poller.pollOnce();
        }

        assertEquals("OPEN", status(A));
        assertEquals(0, count("SELECT count(*) FROM ops_incident_events WHERE kind = 'RESOLVED'"));
    }

    @Test
    void 부재_중에_Alertmanager_가_다시_시작했으면_부재를_다시_센다() {
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        poller.pollOnce();
        active = List.of();
        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();
        amStarted = T0.plusSeconds(100);

        clock.advance(Duration.ofSeconds(150));
        poller.pollOnce();
        assertEquals("OPEN", status(A), "재시작 직후의 빈 목록을 해제로 읽지 않는다");
        assertTrue(am.requests("/api/v2/status").size() >= 1);

        clock.advance(Duration.ofSeconds(150));
        poller.pollOnce();
        assertEquals("RESOLVED", status(A));
        assertEquals(1, count("SELECT count(*) FROM ops_incidents WHERE resolved_at = '" + T0.plusSeconds(165) + "'"));
    }

    @Test
    void 같은_지문이_새_시작_시각으로_오면_이전_사건을_닫고_새로_연다() {
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        poller.pollOnce();
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:59:00Z"));
        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();

        assertEquals(0, poller.last().writeFailures());
        assertEquals(1, count("SELECT count(*) FROM ops_incidents WHERE status = 'RESOLVED' "
                + "AND resolved_at = '2026-09-24T23:59:00Z'"));
        assertEquals(1, count("SELECT count(*) FROM ops_incidents WHERE status = 'OPEN' "
                + "AND started_at = '2026-09-24T23:59:00Z'"));
        assertEquals(1, count("SELECT count(*) FROM ops_incident_events WHERE text = '새 발생으로 닫음'"));
    }

    @Test
    void 닫힌_같은_지문과_시작_시각이_다시_오면_그_사건을_다시_연다() {
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        poller.pollOnce();
        active = List.of();
        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();
        clock.advance(Duration.ofSeconds(150));
        poller.pollOnce();
        assertEquals("RESOLVED", status(A));

        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        clock.advance(Duration.ofSeconds(15));
        poller.pollOnce();

        assertEquals(1, count("SELECT count(*) FROM ops_incidents"));
        assertEquals("OPEN", status(A));
        assertEquals(1, count("SELECT count(*) FROM ops_incident_events WHERE text = '다시 열림'"));
    }

    @Test
    void 생존_신호_경보는_사건이_되지_않고_모르는_등급은_other_다() {
        active = List.of(
                alert("c000000000000003", "Watchdog", "none", "2026-09-24T00:00:00Z"),
                alert(A, "LinuxDiskSpaceLow", "none", "2026-09-24T23:50:00Z"),
                alert(B, "DatabaseBackupStale", "CRITICAL", "2026-09-24T23:50:00Z"),
                "{\"fingerprint\":\"d000000000000004\",\"labels\":{\"alertname\":\"NoSeverity\"},\"annotations\":{},"
                        + "\"startsAt\":\"2026-09-24T23:50:00Z\",\"status\":{\"state\":\"active\",\"silencedBy\":[]}}");

        poller.pollOnce();

        assertEquals(0, count("SELECT count(*) FROM ops_incidents WHERE alertname = 'Watchdog'"));
        assertEquals(3, count("SELECT count(*) FROM ops_incidents WHERE severity = 'other'"));
        assertEquals(List.of("c000000000000003"), List.copyOf(poller.last().heartbeats()));
        assertEquals(3, poller.last().alerts().size());
    }

    @Test
    void 한_경보의_쓰기_실패가_다른_경보의_열기와_해제를_막지_않는다() {
        active = List.of(alert(A, "LinuxDiskSpaceLow", "warning", "2026-09-24T23:50:00Z"));
        poller.pollOnce();
        jdbc.sql("""
                CREATE FUNCTION test_reject_broken() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'broken'; END $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER trg_test_reject_broken BEFORE INSERT ON ops_incidents
                FOR EACH ROW WHEN (NEW.alertname = 'Broken') EXECUTE FUNCTION test_reject_broken()
                """).update();
        try {
            active = List.of(alert(B, "Broken", "warning", "2026-09-24T23:55:00Z"),
                    alert("e000000000000005", "Fine", "warning", "2026-09-24T23:55:00Z"),
                    alert("NOT-HEX", "BadFingerprint", "warning", "2026-09-24T23:55:00Z"));
            clock.advance(Duration.ofSeconds(15));
            poller.pollOnce();
            clock.advance(Duration.ofSeconds(150));
            poller.pollOnce();

            assertEquals(2, poller.last().writeFailures());
            assertEquals("RESOLVED", status(A), "A 는 두 폴링 모두 없었으므로 닫힌다");
            assertEquals("OPEN", status("e000000000000005"));
            assertEquals(0, count("SELECT count(*) FROM ops_incidents WHERE fingerprint = '" + B + "'"));
        } finally {
            jdbc.sql("DROP TRIGGER trg_test_reject_broken ON ops_incidents").update();
            jdbc.sql("DROP FUNCTION test_reject_broken()").update();
        }
    }

    private static String alert(String fingerprint, String name, String severity, String startsAt) {
        return "{\"fingerprint\":\"" + fingerprint + "\",\"labels\":{\"alertname\":\"" + name + "\",\"severity\":\""
                + severity + "\",\"node\":\"db-primary\",\"instance\":\"10.0.0.1:9100\"},"
                + "\"annotations\":{\"summary\":\"요약\\n줄바꿈\"},\"startsAt\":\"" + startsAt + "\","
                + "\"status\":{\"state\":\"active\",\"silencedBy\":[],\"inhibitedBy\":[]}}";
    }

    private static String status(String fingerprint) {
        return jdbc.sql("SELECT status FROM ops_incidents WHERE fingerprint = ? ORDER BY id DESC LIMIT 1")
                .param(fingerprint).query(String.class).single();
    }

    private static long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

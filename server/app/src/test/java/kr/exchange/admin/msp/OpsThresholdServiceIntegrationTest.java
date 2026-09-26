package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.AdminOperatorSeeder;
import kr.exchange.admin.AdminRole;
import kr.exchange.admin.ApprovalService;
import kr.exchange.api.common.ApiException;
import org.flywaydb.core.Flyway;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 임계값 레지스트리의 변경·되돌리기·설정 적용(API 설계 §6-F, 설계 5-2·5-4). 승인 티켓과 트랜잭션은 실제
 * DB 에서 돌린다. 검증이 실패하면 소각도 롤백돼 같은 티켓을 다시 쓸 수 있는지까지 본다.
 */
@Testcontainers
class OpsThresholdServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static ApprovalService approvals;
    SimpleMeterRegistry registry;
    OpsThresholdService service;

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
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        approvals = new ApprovalService(jdbc, Duration.ofMinutes(5));
        new AdminOperatorSeeder(jdbc).ensureOperator("op-01", "데모 운영자 1", Set.of(AdminRole.SUPER));
    }

    @BeforeEach
    void resetRegistry() {
        // 이력은 append-only 라 앱은 지울 수 없다. 시험끼리 섞이지 않게 소유자가 트리거를 잠시 끄고 초기값만 남긴다.
        jdbc.sql("ALTER TABLE ops_threshold_changes DISABLE TRIGGER USER").update();
        jdbc.sql("DELETE FROM ops_threshold_changes WHERE via <> 'SEED'").update();
        jdbc.sql("ALTER TABLE ops_threshold_changes ENABLE TRIGGER USER").update();
        jdbc.sql("UPDATE ops_thresholds SET value = default_value, version = 1, updated_by = 'migration:V70'").update();
        registry = new SimpleMeterRegistry();
        service = new OpsThresholdService(jdbc, tx, approvals,
                new AdminAuditService(jdbc, new ObjectMapper()), registry);
        service.refreshGauge();
    }

    @Test
    void 티켓_소각과_변경과_이력과_감사가_함께_남고_게이지가_커밋_뒤_바뀐다() {
        String token = ticket("host.disk_free_alert:0.2");

        OpsThresholdService.Applied applied = service.set("host.disk_free_alert", "0.20", "디스크 증설 전 조기 경보",
                "op-01", token, "req-1");

        assertEquals("0.15", plain(applied.from()));
        assertEquals("0.2", plain(applied.to()));
        assertEquals(2, applied.version());
        assertEquals("MANUAL", applied.via());
        assertEquals("0.2000", text("SELECT value::text FROM ops_thresholds WHERE key = 'host.disk_free_alert'"));
        assertEquals("CONSUMED", text("SELECT state FROM admin_approvals WHERE approval_token = '" + token + "'"));
        assertEquals(1, count("SELECT count(*) FROM ops_threshold_changes WHERE id = " + applied.changeId()
                + " AND via = 'MANUAL' AND approval_id = " + applied.approvalId() + " AND version = 2"));
        assertEquals(1, count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.threshold.set' "
                + "AND target = 'host.disk_free_alert' AND approval_id = " + applied.approvalId()));
        assertEquals(0.2, gauge("host.disk_free_alert"), 1e-9);
    }

    @Test
    void 값이_다른_티켓은_binding_mismatch_로_거부되고_값은_그대로다() {
        String token = ticket("host.disk_free_alert:0.25");

        ApiException e = assertThrows(ApiException.class,
                () -> service.set("host.disk_free_alert", "0.3", "", "op-01", token, "req"));

        assertEquals(403, e.status());
        assertEquals("binding_mismatch", e.detail().get("reason"));
        assertEquals("0.1500", text("SELECT value::text FROM ops_thresholds WHERE key = 'host.disk_free_alert'"));
    }

    @Test
    void 형식과_소수_자릿수와_범위와_없는_항목은_티켓을_쓰기_전에_거부한다() {
        String token = ticket("host.disk_free_alert:0.12345");

        assertInvalid("scale", () -> service.set("host.disk_free_alert", "0.12345", "", "op-01", token, "r"));
        assertInvalid("format", () -> service.set("host.disk_free_alert", "abc", "", "op-01", token, "r"));
        assertInvalid("format", () -> service.set("host.disk_free_alert", "-0.1", "", "op-01", token, "r"));
        assertInvalid("format", () -> service.set("host.disk_free_alert", "1e-1", "", "op-01", token, "r"));
        assertInvalid("range", () -> service.set("host.disk_free_alert", "0.5", "", "op-01", token, "r"));
        ApiException missing = assertThrows(ApiException.class,
                () -> service.set("host.none_alert", "0.2", "", "op-01", token, "r"));
        assertEquals(404, missing.status());
        assertEquals("ISSUED", text("SELECT state FROM admin_approvals WHERE approval_token = '" + token + "'"));
    }

    @Test
    void 간격_위반은_T3_로_거부되고_소각이_롤백돼_같은_티켓을_다시_쓴다() {
        String inTicket = ticket("read.scale_in_cpu:0.5");

        ApiException denied = assertThrows(ApiException.class,
                () -> service.set("read.scale_in_cpu", "0.50", "", "op-01", inTicket, "r"));
        assertEquals(409, denied.status());
        assertEquals("msp_policy_denied", denied.name());
        assertEquals("T3", denied.detail().get("rule"));
        assertEquals("ISSUED", text("SELECT state FROM admin_approvals WHERE approval_token = '" + inTicket + "'"));

        service.set("read.scale_out_cpu", "0.7", "", "op-01", ticket("read.scale_out_cpu:0.7"), "r");
        OpsThresholdService.Applied applied = service.set("read.scale_in_cpu", "0.50", "", "op-01", inTicket, "r");
        assertEquals("0.5", plain(applied.to()));
    }

    @Test
    void 간격은_정확히_0_15_까지_허용한다() {
        service.set("read.scale_in_cpu", "0.45", "", "op-01", ticket("read.scale_in_cpu:0.45"), "r");
        assertEquals("0.4500", text("SELECT value::text FROM ops_thresholds WHERE key = 'read.scale_in_cpu'"));
    }

    @Test
    void 최신_변경을_되돌리면_직전_값으로_REVERT_이력을_남긴다() {
        OpsThresholdService.Applied set = service.set("host.disk_free_alert", "0.2", "", "op-01",
                ticket("host.disk_free_alert:0.2"), "r");

        OpsThresholdService.Applied reverted = service.revert(set.changeId(), "되돌림", "op-01",
                ticket("host.disk_free_alert:0.15"), "r");

        assertEquals("REVERT", reverted.via());
        assertEquals(set.changeId(), reverted.revertOf());
        assertEquals(3, reverted.version());
        assertEquals("0.1500", text("SELECT value::text FROM ops_thresholds WHERE key = 'host.disk_free_alert'"));
        assertEquals(1, count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.threshold.revert' "
                + "AND approval_id = " + reverted.approvalId()));
    }

    @Test
    void 최신이_아닌_변경의_되돌리기는_409이고_티켓은_남는다() {
        OpsThresholdService.Applied first = service.set("host.disk_free_alert", "0.2", "", "op-01",
                ticket("host.disk_free_alert:0.2"), "r");
        service.set("host.disk_free_alert", "0.25", "", "op-01", ticket("host.disk_free_alert:0.25"), "r");
        String token = ticket("host.disk_free_alert:0.15");

        ApiException e = assertThrows(ApiException.class,
                () -> service.revert(first.changeId(), "", "op-01", token, "r"));

        assertEquals(409, e.status());
        assertEquals("threshold_revert_conflict", e.name());
        assertEquals("ISSUED", text("SELECT state FROM admin_approvals WHERE approval_token = '" + token + "'"));
    }

    @Test
    void 초기값은_되돌릴_수_없다() {
        long seed = jdbc.sql("SELECT id FROM ops_threshold_changes WHERE key = 'host.disk_free_alert' AND via = 'SEED'")
                .query(Long.class).single();

        ApiException e = assertThrows(ApiException.class,
                () -> service.revert(seed, "", "op-01", ticket("host.disk_free_alert:null"), "r"));

        assertEquals(409, e.status());
        assertEquals("seed", e.detail().get("reason"));
    }

    @Test
    void 미설정_항목은_NaN_으로_내보내고_되돌리면_다시_미설정이다() {
        assertTrue(Double.isNaN(gauge("host.cpu_alert")));
        OpsThresholdService.Applied set = service.set("host.cpu_alert", "0.8", "", "op-01",
                ticket("host.cpu_alert:0.8"), "r");
        assertEquals(0.8, gauge("host.cpu_alert"), 1e-9);

        service.revert(set.changeId(), "", "op-01", ticket("host.cpu_alert:null"), "r");

        assertEquals(1, count("SELECT count(*) FROM ops_thresholds WHERE key = 'host.cpu_alert' AND value IS NULL"));
        assertTrue(Double.isNaN(gauge("host.cpu_alert")));
    }

    @Test
    void 되돌리기가_간격을_깨면_T3_로_거부한다() {
        OpsThresholdService.Applied out = service.set("read.scale_out_cpu", "0.65", "", "op-01",
                ticket("read.scale_out_cpu:0.65"), "r");
        service.set("read.scale_in_cpu", "0.5", "", "op-01", ticket("read.scale_in_cpu:0.5"), "r");

        // 증설 임계만 0.60 으로 되돌리면 간격이 0.10 이 된다. 최신 변경인지는 항목별로 본다.
        ApiException e = assertThrows(ApiException.class,
                () -> service.revert(out.changeId(), "", "op-01", ticket("read.scale_out_cpu:0.6"), "r"));

        assertEquals("T3", e.detail().get("rule"));
        assertEquals("0.6500", text("SELECT value::text FROM ops_thresholds WHERE key = 'read.scale_out_cpu'"));
    }

    @Test
    void 설정_기본값은_사람이_바꾸기_전까지만_적용한다() {
        service.applyConfigDefaults(new BigDecimal("0.25"), new BigDecimal("0.10"));

        assertEquals("0.2500", text("SELECT value::text FROM ops_thresholds WHERE key = 'read.scale_out_cpu'"));
        assertEquals("0.1000", text("SELECT value::text FROM ops_thresholds WHERE key = 'read.scale_in_cpu'"));
        assertEquals(2, count("SELECT count(*) FROM ops_threshold_changes WHERE via = 'CONFIG'"));
        assertEquals(0.25, gauge("read.scale_out_cpu"), 1e-9);

        // 같은 값이면 이력을 늘리지 않는다.
        service.applyConfigDefaults(new BigDecimal("0.25"), new BigDecimal("0.10"));
        assertEquals(2, count("SELECT count(*) FROM ops_threshold_changes WHERE via = 'CONFIG'"));

        service.set("read.scale_out_cpu", "0.4", "", "op-01", ticket("read.scale_out_cpu:0.4"), "r");
        service.applyConfigDefaults(new BigDecimal("0.30"), new BigDecimal("0.10"));
        assertEquals("0.4000", text("SELECT value::text FROM ops_thresholds WHERE key = 'read.scale_out_cpu'"));
        assertEquals(2, count("SELECT count(*) FROM ops_threshold_changes WHERE via = 'CONFIG'"));
    }

    @Test
    void 설정_쌍이_범위나_간격을_어기면_기동을_멈춘다() {
        assertThrows(IllegalStateException.class,
                () -> service.applyConfigDefaults(new BigDecimal("0.30"), new BigDecimal("0.20")));
        assertThrows(IllegalStateException.class,
                () -> service.applyConfigDefaults(new BigDecimal("0.10"), new BigDecimal("0.05")));
        assertThrows(IllegalStateException.class,
                () -> service.applyConfigDefaults(new BigDecimal("0.60001"), new BigDecimal("0.30")));
        assertEquals(0, count("SELECT count(*) FROM ops_threshold_changes WHERE via = 'CONFIG'"));
    }

    @Test
    void 변경_이력은_변경_시각_역순이고_항목으로_거른다() {
        service.set("host.disk_free_alert", "0.2", "", "op-01", ticket("host.disk_free_alert:0.2"), "r");
        service.set("host.disk_free_alert", "0.25", "", "op-01", ticket("host.disk_free_alert:0.25"), "r");

        var rows = service.changes("host.disk_free_alert", null, 10);

        assertEquals(3, rows.size());
        assertEquals("MANUAL", rows.get(0).view().via());
        assertEquals("0.25", plain(rows.get(0).view().to()));
        assertEquals("SEED", rows.get(2).view().via());
    }

    private String ticket(String target) {
        return approvals.issue("op-01", "op-01", "ops.threshold.set", target).approvalToken();
    }

    private double gauge(String key) {
        Gauge g = registry.find(OpsThresholdService.GAUGE).tag("key", key).gauge();
        return g.value();
    }

    private static void assertInvalid(String constraint, org.junit.jupiter.api.function.Executable call) {
        ApiException e = assertThrows(ApiException.class, call);
        assertEquals(400, e.status());
        assertEquals(Map.of("constraint", constraint, "field", "value"), e.detail());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String text(String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private static long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

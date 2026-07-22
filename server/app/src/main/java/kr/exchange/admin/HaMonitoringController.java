package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * HA 모니터링(관리자 설계 §2-3, 읽기 전용). 하네스가 적재한 invariant_checks·recovery_runs·
 * failover_runs와 leader_lease를 소비만 한다. 파괴 액션 버튼을 노출하지 않는다(§6 안전).
 * 조회는 단독 운영자(VIEWER 이상)로 가능하다.
 */
@RestController
@RequestMapping("/admin/v1/ha")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class HaMonitoringController {

    private final JdbcClient jdbc;

    public HaMonitoringController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 리더십·워터마크(leader_lease DB 직조회가 권위, 노드 무관). */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        Map<String, Object> lease = jdbc.sql("""
                        SELECT holder, epoch, lease_until, clock_timestamp() AS now,
                               (lease_until > clock_timestamp()) AS leader_alive
                        FROM leader_lease WHERE resource = 'matching-engine'
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "holder", rs.getString("holder") == null ? "" : rs.getString("holder"),
                        "epoch", rs.getLong("epoch"),
                        "lease_until", String.valueOf(rs.getObject("lease_until", OffsetDateTime.class)),
                        "leader_alive", rs.getBoolean("leader_alive")))
                .optional()
                .orElse(Map.of("leader_alive", false));
        long commandWm = scalar("SELECT COALESCE(max(seq), 0) FROM command_journal");
        long eventWm = scalar("SELECT COALESCE(max(event_seq), 0) FROM event_journal");
        // 적용 지연은 command seq 단일 축에서 잰다. 접수된 명령 최대 seq(command_watermark) 대비
        // 이벤트를 낳은 명령 최대 seq(event_journal.source_cmd_seq)의 격차다. command seq와 event
        // seq는 별개 채번이라 그 차이는 지연이 아니므로 섞지 않는다.
        long appliedCmdWm = scalar("SELECT COALESCE(max(source_cmd_seq), 0) FROM event_journal");
        return Map.of(
                "schema_version", "1.0",
                "lease", lease,
                "command_watermark", commandWm,
                "event_watermark", eventWm,
                "applied_command_watermark", appliedCmdWm,
                "apply_lag", Math.max(0, commandWm - appliedCmdWm),
                "trades_count", scalar("SELECT count(*) FROM trades"));
    }

    /** 복구 이력: recovery_runs(RTO duration·재생 seq) + failover_runs(RTO/RPO/verdict). */
    @GetMapping("/recovery")
    public Map<String, Object> recovery(HttpServletRequest request,
                                        @RequestParam(defaultValue = "20") int limit) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        int capped = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> recoveries = jdbc.sql("""
                        SELECT market_id, replayed_from_seq, replayed_to_seq, fp_replay, fp_live,
                               matched, duration_ms, created_at
                        FROM recovery_runs ORDER BY created_at DESC LIMIT ?
                        """)
                .param(capped)
                .query((rs, n) -> Map.<String, Object>of(
                        "market_id", rs.getInt("market_id"),
                        "replayed_from_seq", rs.getLong("replayed_from_seq"),
                        "replayed_to_seq", rs.getLong("replayed_to_seq"),
                        "matched", rs.getObject("matched") == null ? "" : rs.getBoolean("matched"),
                        "duration_ms", rs.getObject("duration_ms") == null ? "" : rs.getInt("duration_ms"),
                        "created_at", String.valueOf(rs.getObject("created_at", OffsetDateTime.class))))
                .list();
        List<Map<String, Object>> failovers = jdbc.sql("""
                        SELECT experiment_id, old_leader, new_leader, rto_seconds, downtime_seconds,
                               rpo_records, verdict, created_at
                        FROM harness.failover_runs ORDER BY created_at DESC LIMIT ?
                        """)
                .param(capped)
                .query((rs, n) -> Map.<String, Object>of(
                        "experiment_id", str(rs.getString("experiment_id")),
                        "rto_seconds", str(rs.getString("rto_seconds")),
                        "downtime_seconds", str(rs.getString("downtime_seconds")),
                        "rpo_records", rs.getObject("rpo_records") == null ? "" : rs.getLong("rpo_records"),
                        "verdict", str(rs.getString("verdict")),
                        "created_at", String.valueOf(rs.getObject("created_at", OffsetDateTime.class))))
                .list();
        return Map.of("schema_version", "1.0", "recovery_runs", recoveries, "failover_runs", failovers);
    }

    /** 불변식 최신 상태: check_name별 최근 1건(ok·violated_rows). */
    @GetMapping("/invariants")
    public Map<String, Object> invariants(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        List<Map<String, Object>> checks = jdbc.sql("""
                        SELECT DISTINCT ON (check_name)
                               check_name, ok, violated_rows, checked_at
                        FROM invariant_checks
                        ORDER BY check_name, checked_at DESC
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "check_name", rs.getString("check_name"),
                        "ok", rs.getBoolean("ok"),
                        "violated_rows", rs.getLong("violated_rows"),
                        "checked_at", String.valueOf(rs.getObject("checked_at", OffsetDateTime.class))))
                .list();
        return Map.of("schema_version", "1.0", "checks", checks);
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }

    private long scalar(String sql) {
        Long v = jdbc.sql(sql).query(Long.class).single();
        return v == null ? 0 : v;
    }
}

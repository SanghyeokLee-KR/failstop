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

    /**
     * 리더십·워터마크·복구 관측(leader_lease DB 직조회가 권위, 노드 무관). 노출 필드는 전부
     * DB 실적재분이다. 노드 팔로워 레지스트리·복제 큐 depth·standby readiness는 단일 라이터 +
     * 저널 재생 복구 구조라 시스템에 실재하지 않으므로 노출하지 않는다(날조 회피). 지연은 저널
     * head 대비 applied seq(apply_lag)로만 잰다.
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        // lease_remaining_ms는 리스 신선도 지표다. renew가 lease_until을 now+ttl로 밀므로 잔여는
        // 마지막 심박 이후 경과의 역상(ttl로 유계)이다. 별도 heartbeat 컬럼이 없어 이 값이 권위다.
        Map<String, Object> lease = jdbc.sql("""
                        SELECT holder, epoch, lease_until, clock_timestamp() AS now,
                               (lease_until > clock_timestamp()) AS leader_alive,
                               GREATEST(0, EXTRACT(EPOCH FROM (lease_until - clock_timestamp()))
                                          * 1000)::bigint AS lease_remaining_ms
                        FROM leader_lease WHERE resource = 'matching-engine'
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "holder", rs.getString("holder") == null ? "" : rs.getString("holder"),
                        "epoch", rs.getLong("epoch"),
                        "lease_until", ts(rs.getObject("lease_until", OffsetDateTime.class)),
                        "leader_alive", rs.getBoolean("leader_alive"),
                        "lease_remaining_ms", rs.getLong("lease_remaining_ms")))
                .optional()
                .orElse(Map.of("leader_alive", false));
        long commandWm = scalar("SELECT COALESCE(max(seq), 0) FROM command_journal");
        long eventWm = scalar("SELECT COALESCE(max(event_seq), 0) FROM event_journal");
        // 적용 지연은 command seq 단일 축에서 잰다. 접수된 명령 최대 seq(command_watermark) 대비
        // 이벤트를 낳은 명령 최대 seq(event_journal.source_cmd_seq)의 격차다. command seq와 event
        // seq는 별개 채번이라 그 차이는 지연이 아니므로 섞지 않는다.
        long appliedCmdWm = scalar("SELECT COALESCE(max(source_cmd_seq), 0) FROM event_journal");
        // 저널 head 대비 스냅샷 워터마크. 마켓별 최신 스냅샷이 담은 last_applied_seq의 전역 최대.
        long snapshotSeq = scalar("SELECT COALESCE(max(last_applied_seq), 0) FROM snapshots");
        return Map.ofEntries(
                Map.entry("schema_version", "1.0"),
                Map.entry("lease", lease),
                Map.entry("command_watermark", commandWm),
                Map.entry("event_watermark", eventWm),
                Map.entry("applied_command_watermark", appliedCmdWm),
                Map.entry("apply_lag", Math.max(0, commandWm - appliedCmdWm)),
                Map.entry("snapshot_seq", snapshotSeq),
                Map.entry("trades_count", scalar("SELECT count(*) FROM trades")),
                Map.entry("recovery_runs_count", scalar("SELECT count(*) FROM recovery_runs")),
                Map.entry("last_failover", lastFailover()));
    }

    /**
     * 마지막 failover 측정(harness.failover_runs 최신 1건). 실험 이력이 없으면 빈 객체다.
     * 다운타임·RTO는 NUMERIC(10,3)을 문자열로 노출한다(금액·수량 외 수치도 문자열 규약 준수).
     */
    private Map<String, Object> lastFailover() {
        return jdbc.sql("""
                        SELECT downtime_seconds, rto_seconds, verdict, created_at
                        FROM harness.failover_runs ORDER BY created_at DESC LIMIT 1
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "downtime_seconds", str(rs.getString("downtime_seconds")),
                        "rto_seconds", str(rs.getString("rto_seconds")),
                        "verdict", str(rs.getString("verdict")),
                        "at", ts(rs.getObject("created_at", OffsetDateTime.class))))
                .optional()
                .orElse(Map.of());
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
                        "created_at", ts(rs.getObject("created_at", OffsetDateTime.class))))
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
                        "created_at", ts(rs.getObject("created_at", OffsetDateTime.class))))
                .list();
        // 판정 객체. 종전에는 배열 둘만 반환해 화면이 행을 보여줄 뿐 통과 여부를 말하지
        // 못했다. 대본이 "복구 성공"을 선언하는데 그 근거를 사람이 눈으로 세야 했다.
        // 기준은 계약이다. RTO<30s, RPO=0(API§7-3 P6), 재생 결정론 matched=false 0(C17).
        // 표가 비면 통과가 아니라 미판정이다. 0행 대조로 통과를 내면 그것이 공허 통과다.
        Map<String, Object> verdict = new java.util.LinkedHashMap<>();
        long replayTotal = recoveries.size();
        long replayMismatched = recoveries.stream()
                .filter(r -> Boolean.FALSE.equals(r.get("matched"))).count();
        verdict.put("replay_runs", replayTotal);
        verdict.put("replay_mismatched", replayMismatched);
        verdict.put("replay_deterministic",
                replayTotal == 0 ? "UNKNOWN" : (replayMismatched == 0 ? "PASS" : "FAIL"));

        long foTotal = failovers.size();
        long rtoBreach = failovers.stream().filter(f -> {
            try {
                String v = String.valueOf(f.get("rto_seconds"));
                return !v.isEmpty() && Double.parseDouble(v) >= 30.0;
            } catch (NumberFormatException e) {
                // 파싱 불가는 통과로 세지 않는다. 값을 못 읽은 것과 기준을 지킨 것은 다르다.
                return true;
            }
        }).count();
        long rpoBreach = failovers.stream().filter(f -> {
            Object v = f.get("rpo_records");
            return !(v instanceof Number) || ((Number) v).longValue() != 0L;
        }).count();
        verdict.put("failover_runs", foTotal);
        verdict.put("rto_breach", rtoBreach);
        verdict.put("rpo_breach", rpoBreach);
        verdict.put("rto_target_seconds", 30);
        verdict.put("failover_ok",
                foTotal == 0 ? "UNKNOWN" : (rtoBreach == 0 && rpoBreach == 0 ? "PASS" : "FAIL"));

        boolean anyUnknown = replayTotal == 0 || foTotal == 0;
        boolean anyFail = replayMismatched > 0 || rtoBreach > 0 || rpoBreach > 0;
        verdict.put("overall", anyFail ? "FAIL" : (anyUnknown ? "UNKNOWN" : "PASS"));

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("schema_version", "1.1");
        out.put("verdict", verdict);
        out.put("recovery_runs", recoveries);
        out.put("failover_runs", failovers);
        return out;
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
                        "checked_at", ts(rs.getObject("checked_at", OffsetDateTime.class))))
                .list();
        return Map.of("schema_version", "1.0", "checks", checks);
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }

    // API §2-1 UTC 밀리초 3자리 고정. OffsetDateTime.toString의 가변 소수부·오프셋 표기를 쓰지 않는다.
    private static String ts(OffsetDateTime odt) {
        return odt == null ? "" : kr.exchange.api.common.ApiFormats.time(odt);
    }

    private long scalar(String sql) {
        Long v = jdbc.sql(sql).query(Long.class).single();
        return v == null ? 0 : v;
    }
}

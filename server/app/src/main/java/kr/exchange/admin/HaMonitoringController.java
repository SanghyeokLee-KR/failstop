package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
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

    /** RTO 목표. API§7-4 SLO. 이 값 이상이면 위반이다. */
    private static final double RTO_TARGET_SECONDS = 30.0;

    /** 창 미지정 시 하한. 이 표들의 최초 행보다 앞선다. */
    private static final OffsetDateTime EPOCH_FLOOR =
            OffsetDateTime.parse("1970-01-01T00:00:00Z");

    /**
     * 판정 창 하한을 읽는다. ISO-8601 순간(2026-08-30T00:00:00Z)만 받는다.
     *
     * 파싱 실패를 무시하고 전체 창으로 떨어뜨리지 않는다. 오타 하나가 조용히 판정
     * 범위를 넓혀 창 밖 실패를 도로 끌어들이는데, 응답은 그 사실을 말하지 않는다.
     */
    private static OffsetDateTime parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(since.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(400, "invalid_parameter",
                    "since는 ISO-8601 순간이어야 합니다(예: 2026-08-30T00:00:00Z).");
        }
    }

    /**
     * 집계 count를 읽는다. PG count(*)는 int8이라 실사용에서는 Long이지만, 드라이버가
     * 바뀌어 Number가 아닌 값이 오면 판정을 세울 수 없다.
     *
     * 센티널을 돌려주지 않는다. 예컨대 -1은 "> 0"도 "== 0"도 아니라서 판정식을 그대로
     * 통과하고, failover 축이 FAIL도 UNKNOWN도 아닌 PASS가 된다. 방어를 주장하는 코드가
     * 반대로 동작한다. 읽지 못한 것은 미판정이므로 예외로 끊는다.
     */
    private static long num(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("집계 결과가 숫자가 아니다: "
                + (v == null ? "null" : v.getClass().getName()));
    }

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
                .query((rs, n) -> {
                    // 키 순서를 고정한다. Map.of는 JVM 기동마다 순회 순서가 바뀌어 증빙
                    // 캡처와 화면 diff가 실행마다 달라진다. 이 컨트롤러의 전 투영과 엔벌로프에 같은 규율을 쓴다.
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("holder", rs.getString("holder") == null ? "" : rs.getString("holder"));
                    row.put("epoch", rs.getLong("epoch"));
                    row.put("lease_until", ts(rs.getObject("lease_until", OffsetDateTime.class)));
                    row.put("leader_alive", rs.getBoolean("leader_alive"));
                    row.put("lease_remaining_ms", rs.getLong("lease_remaining_ms"));
                    return row;
                })
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
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("schema_version", "1.0");
        out.put("lease", lease);
        out.put("command_watermark", commandWm);
        out.put("event_watermark", eventWm);
        out.put("applied_command_watermark", appliedCmdWm);
        out.put("apply_lag", Math.max(0, commandWm - appliedCmdWm));
        out.put("snapshot_seq", snapshotSeq);
        out.put("trades_count", scalar("SELECT count(*) FROM trades"));
        out.put("recovery_runs_count", scalar("SELECT count(*) FROM recovery_runs"));
        out.put("last_failover", lastFailover());
        return out;
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
                .query((rs, n) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("downtime_seconds", str(rs.getString("downtime_seconds")));
                    row.put("rto_seconds", str(rs.getString("rto_seconds")));
                    row.put("verdict", str(rs.getString("verdict")));
                    row.put("at", ts(rs.getObject("created_at", OffsetDateTime.class)));
                    return row;
                })
                .optional()
                .orElse(Map.of());
    }

    /** 복구 이력: recovery_runs(RTO duration·재생 seq) + failover_runs(RTO/RPO/verdict). */
    @GetMapping("/recovery")
    public Map<String, Object> recovery(HttpServletRequest request,
                                        @RequestParam(defaultValue = "20") int limit,
                                        @RequestParam(required = false) String since) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        int capped = Math.max(1, Math.min(limit, 200));

        // 판정 창. 표 전체를 세면 과거 실패 주행 1건이 판정을 영구히 FAIL로 고정한다.
        // failover_runs는 append-only 누적이고 비우는 훅이 없는데, e5·e7 스크립트가
        // 실패 주행을 verdict='FAIL'로 적재한다. 카오스 실험을 한 번 실패하면 그 뒤로
        // "복구 후 overall=PASS"를 수동 DB 삭제 없이는 영영 얻지 못한다.
        //
        // 기본값을 두지 않는다. 기본 창을 정하면 그 값이 판정 범위를 조용히 정하고,
        // 창 밖 위반을 못 보는 문제가 형태만 바꿔 돌아온다. 창을 안 주면 전체가
        // 대상이고 scope가 그렇게 말한다. 창을 좁히는 것은 호출자의 명시 선택이다.
        OffsetDateTime sinceAt = parseSince(since);
        // 창이 없으면 전체다. NULL 비교를 SQL에 넣으면 PG가 파라미터 타입을 추론하지
        // 못해 캐스팅이 필요하고 조건이 두 배가 된다. 하한 센티널이 같은 일을 한다.
        OffsetDateTime floor = sinceAt == null ? EPOCH_FLOOR : sinceAt;
        List<Map<String, Object>> recoveries = jdbc.sql("""
                        SELECT market_id, replayed_from_seq, replayed_to_seq, fp_replay, fp_live,
                               matched, duration_ms, created_at
                        FROM recovery_runs WHERE created_at >= ?
                        ORDER BY created_at DESC LIMIT ?
                        """)
                .params(floor, capped)
                .query((rs, n) -> {
                    // Map.of는 JVM 기동마다 순회 순서가 바뀐다. 응답 키 순서가 비결정이면
                    // 증빙 캡처와 화면 diff가 실행마다 달라진다. failover 행과 규율을 맞춘다.
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("market_id", rs.getInt("market_id"));
                    row.put("replayed_from_seq", rs.getLong("replayed_from_seq"));
                    row.put("replayed_to_seq", rs.getLong("replayed_to_seq"));
                    row.put("matched", rs.getObject("matched") == null ? "" : rs.getBoolean("matched"));
                    row.put("duration_ms",
                            rs.getObject("duration_ms") == null ? "" : rs.getInt("duration_ms"));
                    // fp를 뽑고 버리면 재생 0건과 실재 재생을 화면에서 가를 수 없다.
                    // fp에 verified_events가 실려 있어 그 구분이 여기서 선다.
                    row.put("fp_replay", str(rs.getString("fp_replay")));
                    row.put("fp_live", str(rs.getString("fp_live")));
                    row.put("created_at", ts(rs.getObject("created_at", OffsetDateTime.class)));
                    return row;
                })
                .list();
        List<Map<String, Object>> failovers = jdbc.sql("""
                        SELECT experiment_id, old_leader, new_leader, old_epoch, new_epoch,
                               rto_seconds, downtime_seconds, rpo_records, verdict, created_at
                        FROM harness.failover_runs WHERE created_at >= ?
                        ORDER BY created_at DESC LIMIT ?
                        """)
                .params(floor, capped)
                .query((rs, n) -> {
                    // SELECT하고 버리는 컬럼을 두지 않는다. 뽑았다는 것은 쓰려고 했다는 뜻이고,
                    // 버리면 화면이 왜 그 값을 못 보는지 코드가 말하지 않는다. epoch 단조는
                    // 페일오버 판정의 축이라 행에 있으면 낸다(fp 두 컬럼과 같은 교정).
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("experiment_id", str(rs.getString("experiment_id")));
                    row.put("old_leader", str(rs.getString("old_leader")));
                    row.put("new_leader", str(rs.getString("new_leader")));
                    row.put("old_epoch", rs.getObject("old_epoch") == null ? "" : rs.getLong("old_epoch"));
                    row.put("new_epoch", rs.getObject("new_epoch") == null ? "" : rs.getLong("new_epoch"));
                    row.put("rto_seconds", str(rs.getString("rto_seconds")));
                    row.put("downtime_seconds", str(rs.getString("downtime_seconds")));
                    row.put("rpo_records",
                            rs.getObject("rpo_records") == null ? "" : rs.getLong("rpo_records"));
                    row.put("verdict", str(rs.getString("verdict")));
                    row.put("created_at", ts(rs.getObject("created_at", OffsetDateTime.class)));
                    return row;
                })
                .list();
        // 판정 객체. 종전에는 배열 둘만 반환해 화면이 행을 보여줄 뿐 통과 여부를 말하지
        // 못했다. 대본이 "복구 성공"을 선언하는데 그 근거를 사람이 눈으로 세야 했다.
        // 기준은 계약이다. RTO<30s, RPO=0(API§7-3 P6), 재생 결정론 matched=false 0(C17).
        // 표가 비면 통과가 아니라 미판정이다. 0행 대조로 통과를 내면 그것이 공허 통과다.
        //
        // 집계는 표 전체에서 한다. 위 배열은 limit로 자른 창이라 그것을 세면 창 밖 실패가
        // 안 보인다. 기본 limit 20에서 21번째 실패 행이 있어도 PASS가 나온다.
        // 배열은 화면 표시용이고 판정은 전역이다.
        Map<String, Object> agg = jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM recovery_runs
                             WHERE created_at >= ?) AS replay_runs,
                          (SELECT count(*) FROM recovery_runs
                             WHERE created_at >= ? AND matched IS DISTINCT FROM true) AS replay_bad,
                          (SELECT count(*) FROM harness.failover_runs
                             WHERE created_at >= ?) AS fo_runs,
                          (SELECT count(*) FROM harness.failover_runs
                             WHERE created_at >= ? AND rto_seconds IS NULL) AS rto_missing,
                          (SELECT count(*) FROM harness.failover_runs
                             WHERE created_at >= ? AND rto_seconds >= ?) AS rto_breach,
                          (SELECT count(*) FROM harness.failover_runs
                             WHERE created_at >= ? AND rpo_records IS NULL) AS rpo_missing,
                          (SELECT count(*) FROM harness.failover_runs
                             WHERE created_at >= ? AND rpo_records <> 0) AS rpo_breach,
                          (SELECT count(*) FROM harness.failover_runs
                             WHERE created_at >= ? AND verdict IS DISTINCT FROM 'PASS') AS row_fail
                        """)
                .params(floor, floor, floor, floor, floor, RTO_TARGET_SECONDS,
                        floor, floor, floor)
                .query().singleRow();
        long replayTotal = num(agg.get("replay_runs"));
        // matched가 NULL인 행도 불일치로 센다. 기록자가 값을 못 넣은 것과 일치는 다르다.
        long replayMismatched = num(agg.get("replay_bad"));
        long foTotal = num(agg.get("fo_runs"));
        long rtoMissing = num(agg.get("rto_missing"));
        long rtoBreach = num(agg.get("rto_breach"));
        long rpoMissing = num(agg.get("rpo_missing"));
        long rpoBreach = num(agg.get("rpo_breach"));
        // 행 자체의 verdict를 판정에 넣는다. 하네스는 RTO·RPO 외에도 무결성 clean·epoch
        // 단조·체결 재개를 함께 보고 그 결론을 이 컬럼에 박는다. 그것을 버리면 RTO만
        // 정상인 실패 주행이 통과한다.
        long rowFail = num(agg.get("row_fail"));

        Map<String, Object> verdict = new java.util.LinkedHashMap<>();
        verdict.put("scope", sinceAt == null ? "all" : "since");
        verdict.put("since", sinceAt == null ? "" : ts(sinceAt));
        verdict.put("replay_runs", replayTotal);
        verdict.put("replay_mismatched", replayMismatched);
        verdict.put("replay_deterministic",
                replayTotal == 0 ? "UNKNOWN" : (replayMismatched == 0 ? "PASS" : "FAIL"));
        verdict.put("failover_runs", foTotal);
        verdict.put("rto_breach", rtoBreach);
        verdict.put("rto_missing", rtoMissing);
        verdict.put("rpo_breach", rpoBreach);
        verdict.put("rpo_missing", rpoMissing);
        verdict.put("row_verdict_fail", rowFail);
        // 같은 응답의 rto_seconds·downtime_seconds가 문자열이다(금액·수량 외 수치도
        // 문자열 규약). 임계만 raw double이면 소비자가 실측과 비교할 때 타입을 넘나든다.
        // Locale.ROOT를 준다. 기본 로케일에서는 소수점 구분자가 쉼표인 곳이 있어
        // "30,000"이 나가고 정본이 못 박은 "30.000"과 어긋난다.
        verdict.put("rto_target_seconds",
                String.format(java.util.Locale.ROOT, "%.3f", RTO_TARGET_SECONDS));
        boolean foFail = rtoBreach > 0 || rpoBreach > 0 || rowFail > 0;
        boolean foUnknown = foTotal == 0 || rtoMissing > 0 || rpoMissing > 0;
        verdict.put("failover_ok", foFail ? "FAIL" : (foUnknown ? "UNKNOWN" : "PASS"));

        // FAIL이 UNKNOWN을 이긴다. 하나라도 확정 위반이면 나머지가 미판정이어도 실패다.
        boolean anyFail = replayMismatched > 0 || foFail;
        boolean anyUnknown = replayTotal == 0 || foUnknown;
        verdict.put("overall", anyFail ? "FAIL" : (anyUnknown ? "UNKNOWN" : "PASS"));

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        // 1.0에서 1.2 순증. verdict 객체 자체가 신설이고 그 안이 판정 셋과 계수 필드다.
        // 두 단계를 올린 이유는 sim 두 엔드포인트가 같은 라운드에서 1.0에서 1.1로 갔고
        // 이쪽이 더 큰 변화라 같은 폭이면 규칙이 서지 않기 때문이다.
        out.put("schema_version", "1.2");
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
                .query((rs, n) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("check_name", rs.getString("check_name"));
                    row.put("ok", rs.getBoolean("ok"));
                    row.put("violated_rows", rs.getLong("violated_rows"));
                    row.put("checked_at", ts(rs.getObject("checked_at", OffsetDateTime.class)));
                    return row;
                })
                .list();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("schema_version", "1.0");
        out.put("checks", checks);
        return out;
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

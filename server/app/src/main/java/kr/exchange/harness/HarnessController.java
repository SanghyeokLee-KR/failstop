package kr.exchange.harness;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 하네스 오케스트레이터용 내부 API. 실험 판정 프로토콜(사전·사후 권위 run)과 페일오버
 * 측정 적재를 REST로 노출한다. exchange.harness.enabled=true에서만 등록된다.
 *
 * 공개 LB에 라우팅하지 않는다(격리 토폴로지 내부 전용). 인증은 토폴로지 네트워크 격리로
 * 대신하며, 운영 반영 시 admin 격리(API§3-6)를 태운다.
 */
@RestController
@RequestMapping("/harness/v1")
@ConditionalOnProperty(prefix = "exchange.harness", name = "enabled", havingValue = "true")
public class HarnessController {

    private final JdbcClient jdbc;
    private final InvariantVerifier verifier;
    private final FailoverRecorder failoverRecorder;
    private final LoadKeySeeder loadKeys;

    public HarnessController(JdbcClient jdbc, InvariantVerifier verifier,
                             FailoverRecorder failoverRecorder, LoadKeySeeder loadKeys) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.failoverRecorder = failoverRecorder;
        this.loadKeys = loadKeys;
    }

    /** 리더십·워터마크·체결 관측(leader_lease DB 직조회가 권위, 노드 무관). */
    @GetMapping("/status")
    public StatusResponse status() {
        return jdbc.sql("""
                SELECT holder, epoch, lease_until, clock_timestamp() AS now,
                       (lease_until > clock_timestamp()) AS leader_alive
                FROM leader_lease WHERE resource = 'matching-engine'
                """)
                .query((rs, n) -> new StatusResponse(
                        rs.getString("holder"),
                        rs.getLong("epoch"),
                        rs.getObject("lease_until", OffsetDateTime.class),
                        rs.getObject("now", OffsetDateTime.class),
                        rs.getBoolean("leader_alive"),
                        scalar("SELECT COALESCE(max(seq), 0) FROM command_journal"),
                        scalar("SELECT COALESCE(max(event_seq), 0) FROM event_journal"),
                        scalar("SELECT count(*) FROM trades")))
                .single();
    }

    /** 권위 run 실행(실험 프로토콜 ⓐ 사전 / ⓓ 사후). */
    @PostMapping("/invariants/run")
    public RunResponse runInvariants(@RequestParam String runId,
                                     @RequestParam(defaultValue = "adhoc") String window) {
        AuthoritativeRun run = verifier.runAuthoritative(runId, window);
        List<CheckView> checks = run.results().stream()
                .map(r -> new CheckView(r.check().name(), r.check().severity().name(),
                        r.ok(), r.violatedRows()))
                .toList();
        return new RunResponse(run.runId(), run.window(),
                run.commandWatermark(), run.eventWatermark(),
                run.pass() ? "PASS" : "FAIL", run.criticalClean(), run.totalViolations(), checks);
    }

    /** 실험 시작 훅: 스테일 ACK 원장 제거(C16 오탐 차단, §2-5). */
    @PostMapping("/loadtest-acks/reset")
    public Map<String, Object> resetLoadtestAcks() {
        jdbc.sql("TRUNCATE harness.loadtest_acks").update();
        return Map.of("truncated", "harness.loadtest_acks");
    }

    /**
     * k6 부하 계정 발급(§7-3 seed 잡). 응답을 그대로 load/secrets/k6_keys.json에 넣는다.
     *
     * 시크릿은 발급 시점에만 평문이라 이 응답이 유일한 통로다. DB의 enc_secret은 마스터
     * 키로 암호화돼 있어 나중에 뒤져서 얻을 수 없다.
     */
    @PostMapping("/loadtest-keys")
    public List<Map<String, String>> issueLoadKeys(
            @RequestParam(defaultValue = "KRW-BTC") String market,
            @RequestParam(defaultValue = "4") int count,
            @RequestParam(defaultValue = "1000000000") String quote,
            @RequestParam(defaultValue = "100") String base) {
        return loadKeys.seed(market, count, new BigDecimal(quote), new BigDecimal(base));
    }

    /** 페일오버 측정 적재(오케스트레이터가 RTO/RPO/verdict 계산 후 호출). */
    @PostMapping("/failover")
    public Map<String, Object> recordFailover(@RequestBody FailoverRequest req) {
        // 적재 전 검증. verdict가 null이면 INSERT는 성공하나 응답 Map.of가 null 값에서
        // NPE→500, 행은 이미 적재돼 IDENTITY PK로 재시도마다 중복 누적된다. DDL 경계
        // (experiment_id VARCHAR(32) NOT NULL, verdict VARCHAR(8))도 진입부에서 400으로 막는다.
        requireField(req.experimentId(), "experimentId", 32);
        requireField(req.verdict(), "verdict", 8);
        long id = failoverRecorder.record(new FailoverRecorder.FailoverRecord(
                req.experimentId(), req.oldLeader(), req.newLeader(),
                req.oldEpoch(), req.newEpoch(),
                req.lastHeartbeatTs(), req.takeoverTs(), req.firstTradeTs(),
                req.rtoSeconds(), req.downtimeSeconds(), req.rpoRecords(),
                req.verdict(), req.details()));
        return Map.of("failover_run_id", id, "verdict", req.verdict());
    }

    private static void requireField(String value, String name, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 필수");
        }
        if (value.length() > maxLen) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " 길이 초과(max " + maxLen + ")");
        }
    }

    private long scalar(String sql) {
        Long v = jdbc.sql(sql).query(Long.class).single();
        return v == null ? 0 : v;
    }

    public record StatusResponse(String holder, long epoch, OffsetDateTime leaseUntil,
                                 OffsetDateTime now, boolean leaderAlive,
                                 long commandWatermark, long eventWatermark, long tradesCount) {
    }

    public record CheckView(String checkName, String severity, boolean ok, long violatedRows) {
    }

    public record RunResponse(String runId, String window, long commandWatermark,
                              long eventWatermark, String verdict, boolean criticalClean,
                              long totalViolations, List<CheckView> checks) {
    }

    public record FailoverRequest(String experimentId, String oldLeader, String newLeader,
                                  Long oldEpoch, Long newEpoch,
                                  OffsetDateTime lastHeartbeatTs, OffsetDateTime takeoverTs,
                                  OffsetDateTime firstTradeTs,
                                  BigDecimal rtoSeconds, BigDecimal downtimeSeconds,
                                  Long rpoRecords, String verdict, Map<String, Object> details) {
    }
}

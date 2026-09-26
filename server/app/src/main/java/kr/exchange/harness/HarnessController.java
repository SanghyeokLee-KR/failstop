package kr.exchange.harness;

import kr.exchange.api.common.WriteGateFilter;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.WriteAvailability;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.lease.LeaseSnapshot;
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
    // 리더 판정의 권위. leader_lease 행이 아니라 자원마다의 리스 보관소다.
    private final PartitionLeases leases;
    private final WriteAvailability writeAvailability;

    public HarnessController(JdbcClient jdbc, InvariantVerifier verifier,
                             FailoverRecorder failoverRecorder, LoadKeySeeder loadKeys,
                             PartitionLeases leases, WriteAvailability writeAvailability) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.failoverRecorder = failoverRecorder;
        this.loadKeys = loadKeys;
        this.leases = leases;
        this.writeAvailability = writeAvailability;
    }

    /**
     * 하네스 기록은 거래 일시 중단 동안 새로 시작하지 않는다(DB 설계 §5.4-f 6). 쓰기 게이트는 이 경로를 막지 않으므로 여기서
     * 423 으로 돌려보낸다. 기록이 커밋 대기에 걸리면 실행기가 재개까지 매달린다.
     */
    private void requireWritable() {
        if (!writeAvailability.isActive()) {
            throw WriteGateFilter.tradingSuspended(writeAvailability.view());
        }
    }

    /**
     * 리더십·워터마크·체결 관측. 리더 판정의 권위는 리스 보관소이고 노드와 무관하다. resource 로 리스 자원을 고르고, 없으면
     * P1·P2 의 matching-engine 이다. 파티션 리더 모드(P3)에서는 자원이 필수다. 한 사건이 파티션 하나만 옮기므로 어느 자원을
     * 보는지 모른 채 판정하면 다른 파티션의 리더를 보게 된다.
     */
    @GetMapping("/status")
    public StatusResponse status(@RequestParam(required = false) String resource) {
        LeaderLeaseStore store = leases.get(resolveResource(resource)).store();
        // 리더 판정의 권위는 리스 보관소다. leader_lease 행을 직접 읽던 종전 판정은 리스가
        // PostgreSQL 밖으로 나간 뒤 틀린다. 그 행은 승격 때 한 번만 게시되고 lease_until 이
        // 100년 뒤라 leader_alive 가 항상 참이 된다. 2026-09-08 리더 페일오버 실측에서 리더가
        // 죽고 리스가 만료된 11초 동안 이 엔드포인트가 죽은 노드를 살아 있다고 답했다.
        //
        // 만료 판정은 보관소 시계로 한다. 묻는 쪽 시계를 쓰면 스큐가 판정에 들어온다.
        java.util.Optional<LeaseSnapshot> lease = store.current();
        java.time.Instant storeNow = store.now();
        List<LeaseView> views = leases.all().stream().map(l -> view(l.resource(), l.store())).toList();
        return new StatusResponse(
                lease.map(LeaseSnapshot::holder).orElse(""),
                lease.map(LeaseSnapshot::epoch).orElse(0L),
                lease.map(l -> l.leaseUntil().atOffset(java.time.ZoneOffset.UTC)).orElse(null),
                storeNow.atOffset(java.time.ZoneOffset.UTC),
                alive(lease, storeNow),
                scalar("SELECT COALESCE(max(seq), 0) FROM command_journal"),
                scalar("SELECT COALESCE(max(event_seq), 0) FROM event_journal"),
                scalar("SELECT count(*) FROM trades"),
                views);
    }

    private static LeaseView view(String resource, LeaderLeaseStore store) {
        java.util.Optional<LeaseSnapshot> lease = store.current();
        java.time.Instant storeNow = store.now();
        return new LeaseView(resource,
                lease.map(LeaseSnapshot::holder).orElse(""),
                lease.map(LeaseSnapshot::epoch).orElse(0L),
                lease.map(l -> l.leaseUntil().atOffset(java.time.ZoneOffset.UTC)).orElse(null),
                alive(lease, storeNow));
    }

    private static boolean alive(java.util.Optional<LeaseSnapshot> lease, java.time.Instant storeNow) {
        return lease.isPresent() && !lease.get().holder().isEmpty() && !lease.get().expiredAt(storeNow);
    }

    /** 요청의 리스 자원. 없으면 P1·P2 의 matching-engine 이고 P3 은 400 이다. 계획에 없는 자원도 400 이다. */
    private String resolveResource(String resource) {
        if (resource == null || resource.isBlank()) {
            if (leases.plan().partitioned()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "파티션 리더 모드에서는 resource 가 필수다: " + leases.plan().partitions());
            }
            return leases.plan().first();
        }
        if (!leases.plan().contains(resource)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "계획에 없는 resource: " + resource + " (계획 " + leases.plan().partitions() + ")");
        }
        return resource;
    }

    /** 권위 run 실행(실험 프로토콜 ⓐ 사전 / ⓓ 사후). */
    @PostMapping("/invariants/run")
    public RunResponse runInvariants(@RequestParam String runId,
                                     @RequestParam(defaultValue = "adhoc") String window) {
        requireWritable();
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
        requireWritable();
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
        requireWritable();
        return loadKeys.seed(market, count, new BigDecimal(quote), new BigDecimal(base));
    }

    /** 페일오버 측정 적재(오케스트레이터가 RTO/RPO/verdict 계산 후 호출). */
    @PostMapping("/failover")
    public Map<String, Object> recordFailover(@RequestBody FailoverRequest req) {
        requireWritable();
        // 적재 전 검증. verdict가 null이면 INSERT는 성공하나 응답 Map.of가 null 값에서
        // NPE→500, 행은 이미 적재돼 IDENTITY PK로 재시도마다 중복 누적된다. DDL 경계
        // (experiment_id VARCHAR(32) NOT NULL, verdict VARCHAR(8))도 진입부에서 400으로 막는다.
        requireField(req.experimentId(), "experimentId", 32);
        requireField(req.verdict(), "verdict", 8);
        String resource = resolveResource(req.resource());
        long id = failoverRecorder.record(new FailoverRecorder.FailoverRecord(
                req.experimentId(), req.oldLeader(), req.newLeader(),
                req.oldEpoch(), req.newEpoch(),
                req.lastHeartbeatTs(), req.takeoverTs(), req.firstTradeTs(),
                req.rtoSeconds(), req.downtimeSeconds(), req.rpoRecords(),
                req.verdict(), req.details(), resource));
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

    /** holder·epoch·leaseUntil·leaderAlive 는 고른 자원의 리스이고 leases 는 계획의 모든 자원이다. */
    public record StatusResponse(String holder, long epoch, OffsetDateTime leaseUntil,
                                 OffsetDateTime now, boolean leaderAlive,
                                 long commandWatermark, long eventWatermark, long tradesCount,
                                 List<LeaseView> leases) {
    }

    public record LeaseView(String resource, String holder, long epoch, OffsetDateTime leaseUntil,
                            boolean leaderAlive) {
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
                                  Long rpoRecords, String verdict, Map<String, Object> details,
                                  String resource) {
    }
}

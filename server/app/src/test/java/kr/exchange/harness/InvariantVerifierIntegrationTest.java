package kr.exchange.harness;

import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.sim.BotFleet;
import kr.exchange.sim.BotFleetGovernor;
import kr.exchange.sim.BotSeeder;
import kr.exchange.sim.OpsGovernor;
import kr.exchange.sim.SimProperties;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 불변식 검증기 + 인프로세스 페일오버 증거 산출(E1 데이터 레벨 절반). 봇 플릿으로 체결을
 * 만들고, 실험 판정 프로토콜대로 사전 권위 run → 리더 상실·승격(epoch+1)·스테일 fencing
 * 거부 → 사후 권위 run을 거쳐, invariant_checks·harness.failover_runs에 PASS 증거를 남긴다.
 *
 * 컨테이너 docker kill E1(스탠바이 실엔진에서 체결 재개까지)은 docker-compose.chaos.yml
 * 토폴로지가 담당한다. 본 테스트는 무결성·fencing·판정 프로토콜을 CI 재현 가능하게 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "exchange.harness.enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InvariantVerifierIntegrationTest {

    private static final int KRW = 1;
    private static final int BTC = 2;
    private static final int KRW_BTC = 1;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountBootstrap bootstrap;
    @Autowired
    DemoKeySeeder keySeeder;
    @Autowired
    InvariantVerifier verifier;
    @Autowired
    FailoverRecorder failoverRecorder;
    @Autowired
    LeaderLeaseService appLease;
    @Autowired
    HikariDataSource dataSource;
    @LocalServerPort
    int port;

    @BeforeAll
    void seedAndTrade() {
        bootstrap.asset(KRW, "KRW", "Korean Won", "FIAT", 0);
        bootstrap.asset(BTC, "BTC", "Bitcoin", "CRYPTO", 8);
        bootstrap.market(KRW_BTC, "KRW-BTC", BTC, KRW,
                new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        jdbc.sql("UPDATE markets SET tick_size = 1000, lot_size = 0.0001, min_notional = 5000 "
                        + "WHERE id = ?")
                .params(KRW_BTC)
                .update();
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() ->
                count("SELECT count(*) FROM leader_lease WHERE resource = 'matching-engine' "
                        + "AND lease_until > now()") > 0);

        SimProperties props = new SimProperties(
                "http://localhost:" + port, 42, 60, "KRW-BTC", "50000000",
                new SimProperties.Funding("1000000000", "100"),
                new SimProperties.MarketMaker(2, 10, 3, "0.01", 300),
                new SimProperties.Taker(2, 4.0, "0.001", 5, 0.2), null);
        BotFleet fleet = new BotFleet(props, new BotSeeder(jdbc, bootstrap, keySeeder),
                new OpsGovernor(props.targetOps()), new BotFleetGovernor(30, 80, 300, 3));
        fleet.start();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(40)).until(() ->
                    count("SELECT count(*) FROM trades") >= 8);
        } finally {
            fleet.stop();
        }
        // 정지 정상성: in-flight 소진 후 저널 증가 정지(연속 2회 동수).
        long[] prev = {-1};
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    long now = count("SELECT count(*) FROM command_journal");
                    boolean stable = now == prev[0];
                    prev[0] = now;
                    return stable;
                });

        // C16이 실제 조인을 검증하도록 봇 NEW 명령을 ACK 원장으로 적재(RPO=0 원천).
        jdbc.sql("""
                INSERT INTO harness.loadtest_acks (user_id, client_order_id, order_id, acked_at, source)
                SELECT cj.user_id, cj.client_order_id, cj.assigned_order_id, cj.created_at, 'bot'
                FROM command_journal cj
                JOIN users u ON u.id = cj.user_id
                WHERE cj.command_type = 'NEW' AND cj.client_order_id IS NOT NULL AND u.kind = 'BOT'
                ON CONFLICT DO NOTHING
                """).update();
    }

    @Test
    @Order(1)
    void preRunAllInvariantsPass() {
        assertTrue(count("SELECT count(*) FROM trades") >= 8, "체결 선행");
        AuthoritativeRun pre = verifier.runAuthoritative("E1-itest", "pre");
        assertReportsClean(pre);
        // 적재 확인: 사전 run의 전 체크가 invariant_checks에 남는다.
        assertEquals(InvariantCheck.values().length, count("""
                SELECT count(*) FROM invariant_checks
                WHERE details->>'run_id' = 'E1-itest' AND details->>'window' = 'pre'
                """), "사전 run 체크 적재 누락");
        assertTrue(count("""
                SELECT count(*) FROM harness.loadtest_acks
                """) > 0, "C16 원천 ACK 원장 적재");
    }

    @Test
    @Order(2)
    void failoverBumpsEpochFencesStaleLeaderAndKeepsInvariants() {
        // T1 기준: 승격 직전 리스 상태를 선캡처(구 holder·epoch·lease_until).
        Lease before = currentLease();
        long ttlMs = 10_000;
        OffsetDateTime lastHeartbeat = before.leaseUntil().minus(Duration.ofMillis(ttlMs));

        // 리더 상실 유도(리스 만료) 후 스탠바이 승격. epoch 정확히 +1.
        jdbc.sql("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                + "WHERE resource = 'matching-engine'").update();
        LeaderLeaseService standby = new LeaderLeaseService(
                JdbcClient.create(dataSource), Duration.ofMillis(ttlMs), Duration.ofMillis(2000));
        OffsetDateTime t2 = OffsetDateTime.now();
        assertTrue(standby.tryAcquire(), "스탠바이 승격 실패");
        long newEpoch = standby.requireLeader().epoch();
        assertEquals(before.epoch() + 1, newEpoch, "승격은 epoch 정확히 +1");

        // 구 리더(app)는 상실을 감지하고 재승격이 금지된다(self-shutdown).
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !appLease.isLeader());

        // split-brain 반증: 구 epoch로의 fencing 검증은 거부된다(스테일 append 0행 경로).
        assertThrows(RuntimeException.class, () ->
                jdbc.sql("SELECT assert_current_leader(?, ?)")
                        .params(before.holder(), before.epoch())
                        .query(Object.class).optional());

        // 사후 권위 run: 페일오버를 거쳐도 전 무결성 불변식 유지.
        AuthoritativeRun post = verifier.runAuthoritative("E1-itest", "post");
        assertReportsClean(post);

        long rpoRecords = post.results().stream()
                .filter(r -> r.check() == InvariantCheck.C16_ACK_JOURNALED)
                .findFirst().orElseThrow().violatedRows();
        assertEquals(0, rpoRecords, "RPO=0: ACK된 주문 유실 0");

        BigDecimal downtime = BigDecimal.valueOf(
                        Duration.between(lastHeartbeat, t2).toMillis())
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        boolean pass = post.criticalClean() && rpoRecords == 0
                && downtime.compareTo(BigDecimal.valueOf(30)) < 0;

        long id = failoverRecorder.record(new FailoverRecorder.FailoverRecord(
                "E1", before.holder(), standby.holder(), before.epoch(), newEpoch,
                lastHeartbeat, t2, null, downtime, downtime, rpoRecords,
                pass ? "PASS" : "FAIL",
                Map.of("mode", "in-process", "note",
                        "detection+promotion RTO; 체결 재개 RTO는 컨테이너 E1이 측정")));
        assertTrue(id > 0, "failover_runs 적재");

        // 기계 판정: PASS 증거가 실제로 남았다.
        assertEquals(1, count("""
                SELECT count(*) FROM harness.failover_runs
                WHERE experiment_id = 'E1' AND verdict = 'PASS' AND rpo_records = 0
                  AND new_epoch = old_epoch + 1
                """), "failover_runs PASS 증거 누락");
        assertTrue(downtime.compareTo(BigDecimal.valueOf(30)) < 0, "감지+승격 30s 이내");

        standby.close();
    }

    /**
     * 음성 검증: 위반을 고의 주입하면 검증기가 잡는지 확인한다. 사전·사후 run이 모두 clean만
     * 단언하면 "항상 통과하는 검증기"를 배제하지 못하므로, 검증 로직 자체를 반증한다.
     * 원장(append-only)은 건드리지 않고, 주입 가능한 recovery_runs(C17)·loadtest_acks(C16)로
     * SCHEDULED·AUTHORITATIVE 두 경로를 각각 반증한다. 마지막 순서라 정리 후 잔여 무해.
     */
    @Test
    @Order(3)
    void verifierCatchesInjectedViolations() {
        // C17(SCHEDULED, CRITICAL): 재생 비결정 1건 주입 → runScheduled가 잡아야 한다.
        jdbc.sql("""
                INSERT INTO recovery_runs
                    (market_id, replayed_from_seq, replayed_to_seq, fp_replay, fp_live, matched)
                VALUES (?, 0, 0, 'deadbeef', 'cafef00d', false)
                """).params(KRW_BTC).update();
        InvariantResult c17dirty = runScheduledCheck(InvariantCheck.C17_REPLAY_DETERMINISM);
        assertFalse(c17dirty.ok(), "주입한 재생 비결정을 C17이 놓침");
        assertTrue(c17dirty.violatedRows() >= 1, "C17 위반행 계수 0");

        // 주입 제거 후 재검증 clean 복귀(항상 FAIL·게이지 고착 반증).
        jdbc.sql("DELETE FROM recovery_runs WHERE matched = false").update();
        assertTrue(runScheduledCheck(InvariantCheck.C17_REPLAY_DETERMINISM).ok(),
                "주입 제거 후 C17 clean 미복귀");

        // C16(AUTHORITATIVE_ONLY, CRITICAL): command_journal에 없는 ACK 1건 주입 → 권위 run이
        // 잡고 criticalClean·pass가 모두 false가 돼야 한다.
        jdbc.sql("""
                INSERT INTO harness.loadtest_acks (user_id, client_order_id, acked_at, source)
                VALUES (?, ?, now(), 'neg')
                """).params(999_999L, "NEG-INJECT-0001").update();
        AuthoritativeRun dirty = verifier.runAuthoritative("E1-neg", "inject");
        InvariantResult c16 = dirty.results().stream()
                .filter(r -> r.check() == InvariantCheck.C16_ACK_JOURNALED)
                .findFirst().orElseThrow();
        assertFalse(c16.ok(), "주입한 미저널 ACK를 C16이 놓침");
        assertTrue(c16.violatedRows() >= 1, "C16 위반행 계수 0");
        assertFalse(dirty.criticalClean(), "CRITICAL 위반이 있는데 criticalClean=true");
        assertFalse(dirty.pass(), "위반이 있는데 verdict PASS");
        // 위반이 invariant_checks에 ok=false로 적재됐다(검증기가 결과를 삼키지 않음).
        assertEquals(1, count("""
                SELECT count(*) FROM invariant_checks
                WHERE check_name = 'C16_ACK_JOURNALED' AND ok = false
                  AND details->>'run_id' = 'E1-neg'
                """), "위반 결과 미적재");

        jdbc.sql("DELETE FROM harness.loadtest_acks WHERE source = 'neg'").update();
    }

    /**
     * 스코프 필터 검증(복구 RTO 설계 §3-c). C13A는 AUTHORITATIVE_ONLY로 이동해 상시 스케줄에서
     * 제외되고 권위 run에서만 평가된다. 상시 결과에 프로젝션 지연 오탐이 섞이지 않는 근거다.
     */
    @Test
    @Order(4)
    void scheduledScopeExcludesC13aButAuthoritativeEvaluatesIt() {
        var scheduled = verifier.runScheduled();
        assertTrue(scheduled.stream().allMatch(r -> r.check().scope() == InvariantCheck.Scope.SCHEDULED),
                "runScheduled가 SCHEDULED 외 스코프를 평가함");
        assertTrue(scheduled.stream().noneMatch(r -> r.check() == InvariantCheck.C13A_ORDER_MATERIALIZED),
                "C13A는 상시 스케줄에서 제외돼야 한다");
        assertEquals(InvariantCheck.Scope.AUTHORITATIVE_ONLY,
                InvariantCheck.C13A_ORDER_MATERIALIZED.scope());

        AuthoritativeRun run = verifier.runAuthoritative("scope-probe", "probe");
        assertTrue(run.results().stream()
                        .anyMatch(r -> r.check() == InvariantCheck.C13A_ORDER_MATERIALIZED),
                "C13A는 권위 run에서 평가돼야 한다");
    }

    private InvariantResult runScheduledCheck(InvariantCheck target) {
        return verifier.runScheduled().stream()
                .filter(r -> r.check() == target)
                .findFirst().orElseThrow();
    }

    private void assertReportsClean(AuthoritativeRun run) {
        for (InvariantResult r : run.results()) {
            assertTrue(r.ok(), () -> "불변식 위반: " + r.check().name()
                    + " 위반행=" + r.violatedRows());
        }
        assertTrue(run.pass(), "권위 run verdict PASS");
    }

    private Lease currentLease() {
        return jdbc.sql("""
                SELECT holder, epoch, lease_until
                FROM leader_lease WHERE resource = 'matching-engine'
                """)
                .query((rs, n) -> new Lease(rs.getString("holder"), rs.getLong("epoch"),
                        rs.getObject("lease_until", OffsetDateTime.class)))
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private record Lease(String holder, long epoch, OffsetDateTime leaseUntil) {
    }
}

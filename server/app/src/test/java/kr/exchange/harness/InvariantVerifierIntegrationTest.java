package kr.exchange.harness;

import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.ledger.AccountBootstrap;
import kr.exchange.sim.BotFleet;
import kr.exchange.sim.BotFleetGovernor;
import kr.exchange.sim.BotSeeder;
import kr.exchange.sim.DbRunOrdinalSource;
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
                new OpsGovernor(props.targetOps()), new BotFleetGovernor(30, 80, 300, 3),
                new DbRunOrdinalSource(jdbc));
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

    /**
     * C20 음성 검증: orders.paid_fee·trades_count 물질화가 trades 실합과 어긋나면 C20이 잡는지
     * 반증한다. 프로젝션 드리프트(정산 UPDATE 누락·이중)를 모사한다. orders는 정산이 갱신하는
     * 프로젝션이라 주입 후 복원해도 append-only 원장 불변식을 건드리지 않는다. 마지막 순서라
     * 복원 후 잔여 무해.
     */
    @Test
    @Order(5)
    void verifierCatchesC20FeeMaterializationDrift() {
        // trade leg를 가진 주문 하나를 고른다. 봇 체결이 maker·taker 주문을 남겼다.
        long orderId = count("""
                SELECT o.id FROM orders o
                WHERE o.created_at >= now() - interval '90 days'
                  AND EXISTS (SELECT 1 FROM trades t
                              WHERE t.maker_order_id = o.id OR t.taker_order_id = o.id)
                ORDER BY o.id LIMIT 1
                """);
        assertTrue(orderId > 0, "trade leg를 가진 주문이 없다(봇 체결 선행 실패)");

        // 기준: 주입 전 C20 clean.
        assertTrue(runScheduledCheck(InvariantCheck.C20_ORDER_FEE_MATERIALIZED).ok(),
                "주입 전 C20이 이미 위반");

        // paid_fee 드리프트 주입 → 위반 검출.
        jdbc.sql("UPDATE orders SET paid_fee = paid_fee + 1 WHERE id = ?").params(orderId).update();
        InvariantResult feeDirty = runScheduledCheck(InvariantCheck.C20_ORDER_FEE_MATERIALIZED);
        assertFalse(feeDirty.ok(), "주입한 paid_fee 드리프트를 C20이 놓침");
        assertTrue(feeDirty.violatedRows() >= 1, "C20 위반행 계수 0");
        jdbc.sql("UPDATE orders SET paid_fee = paid_fee - 1 WHERE id = ?").params(orderId).update();
        assertTrue(runScheduledCheck(InvariantCheck.C20_ORDER_FEE_MATERIALIZED).ok(),
                "paid_fee 복원 후 C20 clean 미복귀(항상 FAIL·게이지 고착 반증)");

        // trades_count 드리프트 주입 → 위반 검출.
        jdbc.sql("UPDATE orders SET trades_count = trades_count + 1 WHERE id = ?")
                .params(orderId).update();
        InvariantResult countDirty = runScheduledCheck(InvariantCheck.C20_ORDER_FEE_MATERIALIZED);
        assertFalse(countDirty.ok(), "주입한 trades_count 드리프트를 C20이 놓침");
        assertTrue(countDirty.violatedRows() >= 1, "C20 위반행 계수 0");
        jdbc.sql("UPDATE orders SET trades_count = trades_count - 1 WHERE id = ?")
                .params(orderId).update();
        assertTrue(runScheduledCheck(InvariantCheck.C20_ORDER_FEE_MATERIALIZED).ok(),
                "trades_count 복원 후 C20 clean 미복귀");
    }

    /**
     * C13B(SCHEDULED, CRITICAL). orders -> command_journal 역참조다. 주문 행이 저널에 없는
     * seq 를 가리키거나 seq 가 비면 위반이다.
     *
     * 이 검사에 대응 테스트가 없었다. 2026-09-10 문서 감사가 잡았다. 정방향 C13A 는
     * scheduledScopeExcludesC13aButAuthoritativeEvaluatesIt 이 스코프를 확인하지만
     * 역방향은 아무 테스트도 위반을 주입해 본 적이 없다. 검사가 상시 통과만 하면
     * 그것이 clean 인지 검사가 죽은 것인지 갈리지 않는다.
     *
     * orders.command_seq 에는 외래키가 없고 트리거는 갱신 시각 하나뿐이라 값을 흔들고
     * 되돌릴 수 있다. 두 갈래를 모두 주입한다. 없는 seq 를 가리키는 경우와 NULL 인 경우다.
     */
    @Test
    @Order(13)
    void verifierCatchesC13bOrphanOrderJournalRef() {
        long orderId = count("SELECT o.id FROM orders o WHERE o.command_seq IS NOT NULL ORDER BY o.id LIMIT 1");
        assertTrue(orderId > 0, "command_seq 를 가진 주문이 없다(봇 체결 선행 실패)");
        long originalSeq = count("SELECT command_seq FROM orders WHERE id = " + orderId);

        assertTrue(runScheduledCheck(InvariantCheck.C13B_ORDER_JOURNALED).ok(),
                "주입 전 C13B 가 이미 위반");

        // 갈래 하나. 저널에 없는 seq 를 가리킨다.
        long orphanSeq = count("SELECT coalesce(max(seq), 0) + 1000000 FROM command_journal");
        jdbc.sql("UPDATE orders SET command_seq = ? WHERE id = ?").params(orphanSeq, orderId).update();
        InvariantResult orphan = runScheduledCheck(InvariantCheck.C13B_ORDER_JOURNALED);
        assertFalse(orphan.ok(), "주입한 고아 command_seq 를 C13B 가 놓침");
        assertTrue(orphan.violatedRows() >= 1, "C13B 위반행 계수 0");

        jdbc.sql("UPDATE orders SET command_seq = ? WHERE id = ?").params(originalSeq, orderId).update();
        assertTrue(runScheduledCheck(InvariantCheck.C13B_ORDER_JOURNALED).ok(),
                "복원 후 C13B clean 미복귀(항상 FAIL 반증)");

        // 갈래 둘. seq 가 비었다.
        jdbc.sql("UPDATE orders SET command_seq = NULL WHERE id = ?").params(orderId).update();
        InvariantResult nullSeq = runScheduledCheck(InvariantCheck.C13B_ORDER_JOURNALED);
        assertFalse(nullSeq.ok(), "주입한 NULL command_seq 를 C13B 가 놓침");
        assertTrue(nullSeq.violatedRows() >= 1, "C13B 위반행 계수 0");

        jdbc.sql("UPDATE orders SET command_seq = ? WHERE id = ?").params(originalSeq, orderId).update();
        assertTrue(runScheduledCheck(InvariantCheck.C13B_ORDER_JOURNALED).ok(),
                "복원 후 C13B clean 미복귀");
    }

    /**
     * 카오스 스택·Testcontainers에 pg_cron이 없으면 C03/C09~C12/C15를 아무 러너도 평가하지 않는다.
     * 이들은 InvariantVerifier enum에 배선돼 상시 스케줄이 평가한다.
     * 고아 체결(존재하지 않는 주문 참조 + event_journal TRADE 부재) 1건을 주입해 C09·C11이 잡는지
     * 반증한다. 원장·저널 불변식은 건드리지 않고, 마지막 순서라 정리 후 잔여 무해.
     */
    @Test
    @Order(6)
    void scheduledEvaluatesCatalogChecksAndCatchesOrphanTrade() {
        var scheduled = verifier.runScheduled();
        for (InvariantCheck c : new InvariantCheck[]{
                InvariantCheck.C03_TXN_UNSEALED, InvariantCheck.C09_TRADE_SEQ_EVENT_MATCH,
                InvariantCheck.C10_ORDER_FILL_AGG, InvariantCheck.C11_TRADE_ORPHAN,
                InvariantCheck.C12_EVENT_ORPHAN, InvariantCheck.C15_DEFAULT_PARTITION}) {
            assertTrue(scheduled.stream().anyMatch(r -> r.check() == c),
                    c.name() + "가 상시 스케줄 평가에서 누락(러너 미배선)");
        }

        long orphanId = 990001L;
        jdbc.sql("""
                INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                    maker_user_id, taker_user_id, taker_side, price, quantity,
                                    fencing_epoch, executed_at, created_at)
                VALUES (?, ?, 999000001, 999000002, 111, 222, 'BUY', 100, 1, 1, now(), now())
                """).params(orphanId, KRW_BTC).update();
        try {
            assertFalse(runScheduledCheck(InvariantCheck.C11_TRADE_ORPHAN).ok(),
                    "고아 체결(주문 부재)을 C11이 놓침");
            assertFalse(runScheduledCheck(InvariantCheck.C09_TRADE_SEQ_EVENT_MATCH).ok(),
                    "체결 이벤트 부재를 C09가 놓침");
        } finally {
            jdbc.sql("DELETE FROM trades WHERE id = ?").params(orphanId).update();
            jdbc.sql("DELETE FROM trade_seq_registry WHERE seq = ?").params(orphanId).update();
        }
        assertTrue(runScheduledCheck(InvariantCheck.C11_TRADE_ORPHAN).ok(),
                "정리 후 C11 clean 미복귀");
        assertTrue(runScheduledCheck(InvariantCheck.C09_TRADE_SEQ_EVENT_MATCH).ok(),
                "정리 후 C09 clean 미복귀");
    }

    /**
     * DB §6.4 ⑮ 배선 회귀. event_journal↔event_emission_registry 양방향 anti-join(C21)은 어떤
     * 러너에도 없으면 공백이 된다. 저널에 없는 레지스트리 행(역방향 드리프트=유령 이벤트)을 주입해
     * SCHEDULED 스캔이 잡는지 반증한다. event_emission_registry는 append-only 대상이 아니라 주입·정리
     * 후 원장·저널 불변식에 잔여가 없다. 마지막 순서.
     */
    @Test
    @Order(7)
    void scheduledCatchesEventEmissionRegistryDrift() {
        assertTrue(runScheduledCheck(InvariantCheck.C21_EVENT_EMISSION_REGISTRY).ok(),
                "주입 전 C21이 이미 위반");
        long ghost = 99_990_001L;
        jdbc.sql("""
                INSERT INTO event_emission_registry (source_cmd_seq, event_ordinal, event_seq)
                VALUES (?, 0, ?)
                """).params(ghost, ghost).update();
        try {
            InvariantResult dirty = runScheduledCheck(InvariantCheck.C21_EVENT_EMISSION_REGISTRY);
            assertFalse(dirty.ok(), "레지스트리↔저널 드리프트를 C21이 놓침");
            assertTrue(dirty.violatedRows() >= 1, "C21 위반행 계수 0");
        } finally {
            jdbc.sql("DELETE FROM event_emission_registry WHERE event_seq = ?").params(ghost).update();
        }
        assertTrue(runScheduledCheck(InvariantCheck.C21_EVENT_EMISSION_REGISTRY).ok(),
                "정리 후 C21 clean 미복귀(항상 FAIL·게이지 고착 반증)");
    }

    /**
     * DB §6.4 ⑰ 배선 회귀. journal_counters.next_value ≤ 저널 최대 seq(C23, 채번 재사용 위험)는
     * 어떤 러너에도 없으면 공백이 된다. 카운터를 저널 아래로 되돌려(백업 복원·리시드 모사) 권위 run이
     * 잡는지 반증한다. journal_counters는 정상 테이블이라 복원 후 잔여 무해. 페일오버 이후라 앱이
     * command_seq를 채번하지 않아 주입 구간이 안정적이다. 마지막 순서.
     */
    @Test
    @Order(8)
    void authoritativeCatchesCounterSeqReuseRisk() {
        long original = count("SELECT next_value FROM journal_counters WHERE name = 'command_seq'");
        assertTrue(authoritativeCheck(InvariantCheck.C23_COUNTER_SEQ_CONSISTENCY).ok(),
                "주입 전 C23이 이미 위반");
        jdbc.sql("UPDATE journal_counters SET next_value = 1 WHERE name = 'command_seq'").update();
        try {
            InvariantResult dirty = authoritativeCheck(InvariantCheck.C23_COUNTER_SEQ_CONSISTENCY);
            assertFalse(dirty.ok(), "카운터 재사용 위험을 C23이 놓침");
            assertTrue(dirty.violatedRows() >= 1, "C23 위반행 계수 0");
        } finally {
            jdbc.sql("UPDATE journal_counters SET next_value = ? WHERE name = 'command_seq'")
                    .params(original).update();
        }
        assertTrue(authoritativeCheck(InvariantCheck.C23_COUNTER_SEQ_CONSISTENCY).ok(),
                "복원 후 C23 clean 미복귀");
    }

    /**
     * C08 음성 검증. 90일 created_at 윈도 안에서 중복 체결 id를 주입해 C08(중복 체결)이
     * 잡는지 반증한다. clean만 단언하면 항상 통과하는 검증기나 윈도 오축소 회귀를 배제하지 못하므로
     * C08도 다른 음성 케이스와 같은 표준(주입-검출-정리-clean 복귀)에 편입한다. 레지스트리 트리거가
     * 같은 seq 재기입을 23505로 막으므로 첫 등록분을 지운 뒤 같은 id를 다른 created_at으로 재삽입해
     * dup 팔을 만든다. trades는 주입·정리 대상이라 마지막 순서에서 정리 후 잔여 무해.
     */
    @Test
    @Order(9)
    void scheduledCatchesDuplicateFillWithinWindow() {
        assertTrue(runScheduledCheck(InvariantCheck.C08_DUP_FILL).ok(), "주입 전 C08이 이미 위반");
        long dupId = 993001L;
        jdbc.sql("""
                INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                    maker_user_id, taker_user_id, taker_side, price, quantity,
                                    fencing_epoch, executed_at, created_at)
                VALUES (?, ?, 999000101, 999000102, 111, 222, 'BUY', 100, 1, 1, now(), now())
                """).params(dupId, KRW_BTC).update();
        // 트리거가 dupId를 레지스트리에 등록했으므로 같은 id 재삽입 전에 지운다(23505 회피).
        jdbc.sql("DELETE FROM trade_seq_registry WHERE seq = ?").params(dupId).update();
        jdbc.sql("""
                INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                    maker_user_id, taker_user_id, taker_side, price, quantity,
                                    fencing_epoch, executed_at, created_at)
                VALUES (?, ?, 999000101, 999000102, 111, 222, 'BUY', 100, 1, 1, now(),
                        now() - interval '1 second')
                """).params(dupId, KRW_BTC).update();
        try {
            InvariantResult dirty = runScheduledCheck(InvariantCheck.C08_DUP_FILL);
            assertFalse(dirty.ok(), "윈도 내 중복 체결 id를 C08이 놓침");
            assertTrue(dirty.violatedRows() >= 1, "C08 위반행 계수 0");
        } finally {
            jdbc.sql("DELETE FROM trades WHERE id = ?").params(dupId).update();
            jdbc.sql("DELETE FROM trade_seq_registry WHERE seq = ?").params(dupId).update();
        }
        assertTrue(runScheduledCheck(InvariantCheck.C08_DUP_FILL).ok(),
                "정리 후 C08 clean 미복귀(항상 FAIL·게이지 고착 반증)");
    }

    /**
     * C18 음성 검증. 90일 윈도 안에서 maker 지정가와 어긋난 체결가를 주입해 C18(체결가=
     * maker 지정가)이 잡는지 반증한다. 실제 maker 주문(가격 있음)을 참조하므로 봇 정상 체결(t.price=
     * m.price) 아래 주입 전 C18은 clean이다. trades만 주입·정리하고 마지막 순서라 잔여 무해.
     */
    @Test
    @Order(10)
    void scheduledCatchesFillPriceMakerMismatchWithinWindow() {
        long makerId = count("""
                SELECT m.id FROM orders m
                JOIN trades t ON t.maker_order_id = m.id
                WHERE m.price IS NOT NULL AND t.created_at >= now() - interval '90 days'
                ORDER BY m.id LIMIT 1
                """);
        assertTrue(makerId > 0, "가격 있는 maker 주문이 없다(봇 체결 선행 실패)");
        assertTrue(runScheduledCheck(InvariantCheck.C18_FILL_PRICE_MAKER).ok(),
                "주입 전 C18이 이미 위반");

        long mismatchId = 994001L;
        jdbc.sql("""
                INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                    maker_user_id, taker_user_id, taker_side, price, quantity,
                                    fencing_epoch, executed_at, created_at)
                SELECT ?, ?, m.id, m.id, m.user_id, m.user_id, 'BUY', m.price + 1, 1, 1, now(), now()
                FROM orders m WHERE m.id = ?
                """).params(mismatchId, KRW_BTC, makerId).update();
        try {
            InvariantResult dirty = runScheduledCheck(InvariantCheck.C18_FILL_PRICE_MAKER);
            assertFalse(dirty.ok(), "maker 지정가 불일치 체결을 C18이 놓침");
            assertTrue(dirty.violatedRows() >= 1, "C18 위반행 계수 0");
        } finally {
            jdbc.sql("DELETE FROM trades WHERE id = ?").params(mismatchId).update();
            jdbc.sql("DELETE FROM trade_seq_registry WHERE seq = ?").params(mismatchId).update();
        }
        assertTrue(runScheduledCheck(InvariantCheck.C18_FILL_PRICE_MAKER).ok(),
                "정리 후 C18 clean 미복귀");
    }

    /**
     * C24 음성 검증. 원가 수량 항등(`cost_qty + unknown_qty = available + locked`)을 **양방향**으로
     * 깨서 권위 run이 둘 다 잡는지 반증한다. 단측 부등호 구현이면 한 방향이 통과해버리므로 초과·미달을
     * 각각 주입한다. 겸해서 WARN 위반이 criticalClean을 내리지 않는다는 등급 분리도 못박는다. 이게
     * 깨지면 표시 계층 결함이 §4-3 CRITICAL 자동 조치(거래 정지)를 유발한다.
     *
     * position_cost는 계층 ③ 파생이라 주입·복원이 원장·저널에 잔여를 남기지 않는다. 마지막 순서.
     */
    @Test
    @Order(11)
    void authoritativeCatchesPositionCostQtyDriftBothDirections() {
        long[] key = costRowKey();
        assertTrue(authoritativeCheck(InvariantCheck.C24_POSITION_COST_QTY).ok(),
                "주입 전 C24가 이미 위반");

        // 초과 방향: 처분 차감 누락 모사.
        updateCost("unknown_qty = unknown_qty + 1", key);
        try {
            InvariantResult excess = authoritativeCheck(InvariantCheck.C24_POSITION_COST_QTY);
            assertFalse(excess.ok(), "보유 초과(처분 차감 누락)를 C24가 놓침");
            assertTrue(excess.violatedRows() >= 1, "C24 위반행 계수 0");
        } finally {
            updateCost("unknown_qty = unknown_qty - 1", key);
        }
        assertTrue(authoritativeCheck(InvariantCheck.C24_POSITION_COST_QTY).ok(),
                "복원 후 C24 clean 미복귀(항상 FAIL 반증)");

        // 미달 방향: 취득 누산 누락 모사. 단측 구현이면 여기서 통과해버린다.
        BigDecimal unknown = jdbc.sql(
                        "SELECT unknown_qty FROM position_cost WHERE user_id = ? AND asset_id = ?")
                .params(key[0], key[1])
                .query(BigDecimal.class).single();
        assertTrue(unknown.signum() > 0, "미달 주입 표본에 원가 미상 수량이 없다");
        updateCost("unknown_qty = 0", key);
        try {
            AuthoritativeRun run = verifier.runAuthoritative("cost-neg", "inject");
            InvariantResult deficit = run.results().stream()
                    .filter(r -> r.check() == InvariantCheck.C24_POSITION_COST_QTY)
                    .findFirst().orElseThrow();
            assertFalse(deficit.ok(), "보유 미달(취득 누산 누락)을 C24가 놓침");
            // 등급 분리: WARN은 verdict를 내리되 CRITICAL 판정은 건드리지 않는다.
            assertFalse(run.pass(), "위반이 있는데 verdict PASS");
            assertTrue(run.criticalClean(),
                    "WARN 위반이 criticalClean을 내렸다(표시 계층 결함이 거래 정지를 유발한다)");
            assertEquals(1, count("""
                    SELECT count(*) FROM invariant_checks
                    WHERE check_name = 'C24_POSITION_COST_QTY' AND ok = false
                      AND details->>'run_id' = 'cost-neg'
                    """), "위반 결과 미적재");
        } finally {
            jdbc.sql("UPDATE position_cost SET unknown_qty = ? WHERE user_id = ? AND asset_id = ?")
                    .params(unknown, key[0], key[1]).update();
        }
        assertTrue(authoritativeCheck(InvariantCheck.C24_POSITION_COST_QTY).ok(),
                "복원 후 C24 clean 미복귀");
    }

    /**
     * C25 음성 검증. 뷰가 분류하는 위반 5종 중 테이블 CHECK가 막지 못하는 두 종(워터마크 미래값,
     * quote 자산에 원가 행)을 주입해 권위 run이 잡는지 반증한다. 부호·쌍 위반은 CHECK가 INSERT
     * 단계에서 막아 주입 자체가 성립하지 않으므로 이 두 종이 실주입 가능한 표본이다.
     *
     * quote 행 주입은 unknown_qty를 실보유와 맞춰 넣어 C24를 건드리지 않는다. 두 체크가 서로의
     * 위반에 업혀 통과하는 것을 배제하기 위함이다. 마지막 순서.
     */
    @Test
    @Order(12)
    void authoritativeCatchesPositionCostSanityViolations() {
        long[] key = costRowKey();
        assertTrue(authoritativeCheck(InvariantCheck.C25_POSITION_COST_SANITY).ok(),
                "주입 전 C25가 이미 위반");

        // watermark_ahead: last_txn_id가 원장 최대 id를 앞지른다(백업 복원·수기 리시드 모사).
        long watermark = costColumn("last_txn_id", key);
        updateCost("last_txn_id = (SELECT COALESCE(max(id), 0) + 1000 FROM ledger_transactions)",
                key);
        try {
            InvariantResult ahead = authoritativeCheck(InvariantCheck.C25_POSITION_COST_SANITY);
            assertFalse(ahead.ok(), "워터마크 미래값을 C25가 놓침");
            assertTrue(ahead.violatedRows() >= 1, "C25 위반행 계수 0");
        } finally {
            jdbc.sql("UPDATE position_cost SET last_txn_id = ? WHERE user_id = ? AND asset_id = ?")
                    .params(watermark, key[0], key[1]).update();
        }
        assertTrue(authoritativeCheck(InvariantCheck.C25_POSITION_COST_SANITY).ok(),
                "복원 후 C25 clean 미복귀(항상 FAIL 반증)");

        // quote_asset_position: 원가 대상이 아닌 quote 자산(KRW)에 원가 행.
        assertEquals(1, jdbc.sql("""
                INSERT INTO position_cost (user_id, asset_id, quote_asset_id, unknown_qty,
                                           last_txn_id)
                SELECT ub.user_id, ub.asset_id, ub.asset_id, ub.available + ub.locked,
                       COALESCE((SELECT max(id) FROM ledger_transactions), 0)
                FROM user_balances ub
                WHERE ub.user_id = ? AND ub.asset_id = ?
                """).params(key[0], (long) KRW).update(), "quote 원가 행 주입 실패");
        try {
            InvariantResult quoteRow = authoritativeCheck(InvariantCheck.C25_POSITION_COST_SANITY);
            assertFalse(quoteRow.ok(), "quote 자산 원가 행을 C25가 놓침");
            assertTrue(authoritativeCheck(InvariantCheck.C24_POSITION_COST_QTY).ok(),
                    "C25 주입이 C24까지 오염시켰다(두 체크가 서로에 업혀 통과할 수 있다)");
        } finally {
            jdbc.sql("DELETE FROM position_cost WHERE user_id = ? AND asset_id = ?")
                    .params(key[0], (long) KRW).update();
        }
        assertTrue(authoritativeCheck(InvariantCheck.C25_POSITION_COST_SANITY).ok(),
                "정리 후 C25 clean 미복귀");
    }

    /** 봇 시드·체결이 남긴 원가 행 하나. 표본이 없으면 음성 검증이 공허 통과한다. */
    private long[] costRowKey() {
        return jdbc.sql("""
                        SELECT user_id, asset_id FROM position_cost
                        WHERE unknown_qty > 0 ORDER BY user_id, asset_id LIMIT 1
                        """)
                .query((rs, n) -> new long[]{rs.getLong("user_id"), rs.getLong("asset_id")})
                .optional()
                .orElseThrow(() ->
                        new AssertionError("position_cost 표본이 없다(봇 시드·체결 선행 실패)"));
    }

    private long costColumn(String column, long[] key) {
        return jdbc.sql("SELECT " + column + " FROM position_cost "
                        + "WHERE user_id = ? AND asset_id = ?")
                .params(key[0], key[1])
                .query(Long.class).single();
    }

    private void updateCost(String assignment, long[] key) {
        assertEquals(1, jdbc.sql("UPDATE position_cost SET " + assignment
                        + " WHERE user_id = ? AND asset_id = ?")
                .params(key[0], key[1]).update(), "원가 행 주입·복원이 0행");
    }

    private InvariantResult authoritativeCheck(InvariantCheck target) {
        return verifier.runAuthoritative("gap-itest", "probe").results().stream()
                .filter(r -> r.check() == target)
                .findFirst().orElseThrow();
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

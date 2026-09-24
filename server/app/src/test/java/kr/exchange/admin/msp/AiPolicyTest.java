package kr.exchange.admin.msp;

import kr.exchange.admin.AdminPrincipal;
import kr.exchange.admin.AdminRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static kr.exchange.admin.msp.AiFixtures.NOW;
import static kr.exchange.admin.msp.AiFixtures.alert;
import static kr.exchange.admin.msp.AiFixtures.limits;
import static kr.exchange.admin.msp.AiFixtures.window;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정책 엔진 규칙마다 허용 사례와 거부 사례를 한 쌍 이상 둔다(설계 9-1). 거부는 규칙 id 까지 대조한다.
 * 엔진은 시계와 입력만 받는 순수 함수라 스프링과 DB 없이 돈다.
 */
class AiPolicyTest {

    private static final String FP = "a000000000000001";

    private static void denied(String rule, AiPolicy.Denial d) {
        denied(rule, d, "");
    }

    private static void denied(String rule, AiPolicy.Denial d, String why) {
        assertNotNull(d, "거부여야 한다: " + rule + " " + why);
        assertEquals(rule, d.rule(), d.message() + " " + why);
    }

    /* ===== 공통 ===== */

    @Test
    void C3_열린_요청만_만료_전에_실행한다() {
        assertNull(AiPolicy.c3Open(AiFixtures.request(2, "0.9"), NOW));
        assertNull(AiPolicy.c3Open(new AiPolicy.Request("DEFERRED", NOW.plusSeconds(1), NOW.plusSeconds(1), 2,
                BigDecimal.ONE), NOW));
        for (String status : List.of("REJECTED", "APPROVED", "AUTO_EXECUTED", "EXPIRED", "DENIED", "WITHDRAWN")) {
            denied("C3", AiPolicy.c3Open(new AiPolicy.Request(status, NOW.plusSeconds(60), null, 2, BigDecimal.ONE), NOW));
        }
        denied("C3", AiPolicy.c3Open(new AiPolicy.Request("PROPOSED", NOW, null, 2, BigDecimal.ONE), NOW));
        denied("C3", AiPolicy.c3Open(new AiPolicy.Request("DEFERRED", NOW.minusSeconds(1), null, 2, BigDecimal.ONE), NOW));
    }

    @Test
    void C4_실행_중인_요청이_있으면_막는다() {
        assertNull(AiPolicy.c4Single(false));
        denied("C4", AiPolicy.c4Single(true));
    }

    @Test
    void C6_승인_역할은_같은_조치를_직접_할_때의_역할이다() {
        assertEquals(AdminRole.SUPER, AiPolicy.requiredRole(AiAction.Kind.READ_NODES));
        assertEquals(AdminRole.SUPER, AiPolicy.requiredRole(AiAction.Kind.THRESHOLD));
        assertEquals(AdminRole.SUPER, AiPolicy.requiredRole(AiAction.Kind.BACKUP_NOW));
        assertEquals(AdminRole.BOT_OPS, AiPolicy.requiredRole(AiAction.Kind.SILENCE));
        assertEquals(AdminRole.BOT_OPS, AiPolicy.requiredRole(AiAction.Kind.BOT_LOAD));
        AdminPrincipal botOps = new AdminPrincipal("op-bot", null, Set.of(AdminRole.BOT_OPS));
        assertTrue(botOps.has(AiPolicy.requiredRole(AiAction.Kind.SILENCE)));
        assertFalse(botOps.has(AiPolicy.requiredRole(AiAction.Kind.READ_NODES)), "BOT_OPS 는 조회 노드를 승인하지 못한다");
    }

    /* ===== 모드와 자동 모드 ===== */

    @Test
    void M9_정지_중에는_승인과_자동_실행을_막는다() {
        assertNull(AiPolicy.m9Stopped(AiFixtures.auto()));
        denied("M9", AiPolicy.m9Stopped(AiFixtures.stopped()));
        AiEvidence ev = new AiFixtures.Ev().cpu(0.82, null).build();
        AiAction add = new AiAction.ReadNodes(1);
        denied("M9", AiPolicy.approval(add, AiFixtures.facts(ev, AiFixtures.stopped(), AiFixtures.request(2, "0.9"),
                AiPolicy.History.NONE)));
        denied("M9", AiPolicy.auto(add, AiFixtures.facts(ev, AiFixtures.stopped(), AiFixtures.request(2, "0.9"),
                AiPolicy.History.NONE)));
    }

    @Test
    void A1_자동_모드이고_유효_시간_안이어야_한다() {
        AiControl.Mode auto = AiFixtures.auto();
        assertNull(AiPolicy.a1Auto(auto, auto.validUntil().minusSeconds(1)));
        denied("A1", AiPolicy.a1Auto(AiFixtures.manual(), NOW));
        denied("A1", AiPolicy.a1Auto(auto, auto.validUntil()));
    }

    @Test
    void A2_모델_응답이_정상_제안이어야_한다() {
        assertNull(AiPolicy.a2Outcome("PROPOSED"));
        denied("A2", AiPolicy.a2Outcome("MODEL_ERROR"));
        denied("A2", AiPolicy.a2Outcome("INVALID_OUTPUT"));
    }

    @Test
    void A3_확신도_기준은_한도를_따른다() {
        assertNull(AiPolicy.a3Confidence(new BigDecimal("0.80"), 80));
        denied("A3", AiPolicy.a3Confidence(new BigDecimal("0.79"), 80));
        denied("A3", AiPolicy.a3Confidence(new BigDecimal("0.85"), 90));
        denied("A3", AiPolicy.a3Confidence(null, 80));
    }

    @Test
    void A4_같은_판단이_연속_두_번이어야_한다() {
        assertNull(AiPolicy.a4Consecutive(2));
        denied("A4", AiPolicy.a4Consecutive(1));
    }

    @Test
    void A5_판단_때와_실행_직전의_근거가_모두_60초_안이어야_한다() {
        AiAction add = new AiAction.ReadNodes(1);
        AiEvidence judged = new AiFixtures.Ev().build();
        AiPolicy.Decision decision = new AiPolicy.Decision("PROPOSED", NOW.plusSeconds(59), judged);
        assertNull(AiPolicy.a5Fresh(add, decision, judged, NOW.plusSeconds(60)));
        denied("A5", AiPolicy.a5Fresh(add, new AiPolicy.Decision("PROPOSED", NOW.plusSeconds(61), judged), judged,
                NOW.plusSeconds(61)));
        AiEvidence missing = new AiFixtures.Ev().cpu(null, null).build();
        denied("A5", AiPolicy.a5Fresh(add, decision, missing, NOW));
        denied("A5", AiPolicy.a5Fresh(add, decision, judged, NOW.plusSeconds(61)));
    }

    @Test
    void A6_관찰_창이_끝나야_다음_조치를_한다() {
        assertNull(AiPolicy.a6Observe(null, NOW));
        assertNull(AiPolicy.a6Observe(NOW, NOW));
        denied("A6", AiPolicy.a6Observe(NOW.plusSeconds(1), NOW));
    }

    @Test
    void A7_활성당_자동_조치_상한() {
        assertNull(AiPolicy.a7Cap(4, 5));
        denied("A7", AiPolicy.a7Cap(5, 5));
        denied("A7", AiPolicy.a7Cap(1, 1));
    }

    @Test
    void A8_종류별_쿨다운은_사람의_같은_종류_조치도_센다() {
        AiLimits l = limits();
        Instant last = NOW.minus(Duration.ofMinutes(10));
        assertNull(AiPolicy.a8Cooldown(AiAction.Kind.READ_NODES, last, l, NOW));
        denied("A8", AiPolicy.a8Cooldown(AiAction.Kind.READ_NODES, last.plusSeconds(1), l, NOW));
        assertNull(AiPolicy.a8Cooldown(AiAction.Kind.THRESHOLD, NOW.minus(Duration.ofMinutes(30)), l, NOW));
        denied("A8", AiPolicy.a8Cooldown(AiAction.Kind.THRESHOLD, NOW.minus(Duration.ofSeconds(29 * 60 + 59)), l, NOW));
        assertNull(AiPolicy.a8Cooldown(AiAction.Kind.BOT_LOAD, NOW.minus(Duration.ofMinutes(10)), l, NOW));
        denied("A8", AiPolicy.a8Cooldown(AiAction.Kind.BOT_LOAD, NOW.minus(Duration.ofMinutes(9)), l, NOW));
        assertNull(AiPolicy.a8Cooldown(AiAction.Kind.SILENCE, NOW, l, NOW), "보류에는 쿨다운이 없다");
        assertNull(AiPolicy.a8Cooldown(AiAction.Kind.READ_NODES, null, l, NOW));
    }

    @Test
    void A9_사람의_나중에와_거절이_자동_실행보다_앞선다() {
        AiLimits l = limits();
        AiPolicy.Request open = AiFixtures.request(2, "0.9");
        assertNull(AiPolicy.a9Human(AiAction.Kind.READ_NODES, open, AiPolicy.History.NONE, l, NOW));
        denied("A9", AiPolicy.a9Human(AiAction.Kind.READ_NODES, new AiPolicy.Request("DEFERRED", NOW.plusSeconds(60),
                NOW.minusSeconds(1), 2, BigDecimal.ONE), AiPolicy.History.NONE, l, NOW));
        AiPolicy.History deferred = history(null, NOW.plusSeconds(30), null, false);
        denied("A9", AiPolicy.a9Human(AiAction.Kind.READ_NODES, open, deferred, l, NOW));
        AiPolicy.History rejected = history(null, null, NOW.minus(Duration.ofMinutes(14)), false);
        denied("A9", AiPolicy.a9Human(AiAction.Kind.SILENCE, open, rejected, l, NOW));
        AiPolicy.History rejectedLongAgo = history(null, null, NOW.minus(Duration.ofMinutes(16)), false);
        assertNull(AiPolicy.a9Human(AiAction.Kind.SILENCE, open, rejectedLongAgo, l, NOW));
        denied("A9", AiPolicy.a9Human(AiAction.Kind.THRESHOLD, open, rejectedLongAgo, l, NOW),
                "임계값은 쿨다운 30분 동안 막는다");
    }

    /* ===== 조회 노드 ===== */

    @Test
    void R1_한_대씩_최소_최대_안에서() {
        AiEvidence.ReadTier read = new AiEvidence.ReadTier(2, 3, 4, 3, 0.5, NOW, null);
        assertNull(AiPolicy.r1Bounds(new AiAction.ReadNodes(1), read));
        assertNull(AiPolicy.r1Bounds(new AiAction.ReadNodes(-1), read));
        denied("R1", AiPolicy.r1Bounds(new AiAction.ReadNodes(1), new AiEvidence.ReadTier(2, 4, 4, 4, 0.9, NOW, null)));
        denied("R1", AiPolicy.r1Bounds(new AiAction.ReadNodes(-1), new AiEvidence.ReadTier(2, 2, 4, 2, 0.1, NOW, null)));
        denied("R1", AiPolicy.r1Bounds(new AiAction.ReadNodes(2), read));
        denied("R1", AiPolicy.r1Bounds(new AiAction.ReadNodes(1), null));
    }

    @Test
    void R2_로컬_조정기에서만_조회_노드를_바꾼다() {
        assertNull(AiPolicy.r2Mode(MspProperties.ReadCapacityMode.CAPACITY_PLAN));
        denied("R2", AiPolicy.r2Mode(MspProperties.ReadCapacityMode.ASG_POLICY));
        denied("R2", AiPolicy.r2Mode(MspProperties.ReadCapacityMode.NONE));
    }

    /* ===== 임계값 ===== */

    private static AiEvidence.Threshold item(AiEvidence ev, String key) {
        return ev.thresholds().get(key);
    }

    @Test
    void T1_설정된_항목만_바꾼다() {
        AiEvidence ev = new AiFixtures.Ev().build();
        assertNull(AiPolicy.t1Set(threshold("read.scale_out_cpu", "0.55", "0.60"), item(ev, "read.scale_out_cpu")));
        denied("T1", AiPolicy.t1Set(threshold("db.replication_lag_alert", "30", null), item(ev, "db.replication_lag_alert")));
        denied("T1", AiPolicy.t1Set(threshold("host.none_alert", "0.5", null), null));
    }

    @Test
    void T2_범위_폭_자릿수_변화() {
        AiEvidence ev = new AiFixtures.Ev().build();
        AiEvidence.Threshold out = item(ev, "read.scale_out_cpu");
        assertNull(AiPolicy.t2Range(threshold("read.scale_out_cpu", "0.55", "0.60"), out));
        denied("T2", AiPolicy.t2Range(threshold("read.scale_out_cpu", "0.50", "0.60"), out));
        denied("T2", AiPolicy.t2Range(threshold("read.scale_out_cpu", "0.90", "0.60"), out));
        denied("T2", AiPolicy.t2Range(threshold("read.scale_out_cpu", "0.55001", "0.60"), out));
        denied("T2", AiPolicy.t2Range(threshold("read.scale_out_cpu", "0.60", "0.60"), out));
    }

    @Test
    void T3_축소_임계는_증설_임계보다_0_15_이상_낮다() {
        AiEvidence ev = new AiFixtures.Ev().build();
        assertNull(AiPolicy.t3Gap(threshold("read.scale_out_cpu", "0.55", "0.60"), ev.thresholds()));
        assertNull(AiPolicy.t3Gap(threshold("read.scale_in_cpu", "0.45", "0.40"), ev.thresholds()));
        denied("T3", AiPolicy.t3Gap(threshold("read.scale_in_cpu", "0.46", "0.41"), ev.thresholds()));
        denied("T3", AiPolicy.t3Gap(threshold("read.scale_out_cpu", "0.44", "0.49"), ev.thresholds()));
        assertNull(AiPolicy.t3Gap(threshold("host.disk_free_alert", "0.2", "0.15"), ev.thresholds()));
    }

    @Test
    void T4_활성당_항목_하나의_자동_변경_횟수() {
        assertNull(AiPolicy.t4PerItem(1, 2));
        denied("T4", AiPolicy.t4PerItem(2, 2));
    }

    @Test
    void T5_이_환경에서_쓰지_않는_항목은_AI가_바꾸지_않는다() {
        AiEvidence ev = new AiFixtures.Ev().build();
        assertNull(AiPolicy.t5InUse(threshold("read.scale_out_cpu", "0.55", "0.60"), item(ev, "read.scale_out_cpu")));
        denied("T5", AiPolicy.t5InUse(threshold("host.cpu_alert", "0.85", "0.80"), item(ev, "host.cpu_alert")));
    }

    @Test
    void T4는_자동_실행에만_걸고_사람의_승인에는_걸지_않는다() {
        AiEvidence ev = new AiFixtures.Ev().build();
        AiAction lower = threshold("read.scale_out_cpu", "0.55", "0.60");
        AiPolicy.History usedTwice = new AiPolicy.History(false, null, 2, 2, null, null, null, false, 0, false);
        denied("T4", AiPolicy.auto(lower, AiFixtures.facts(ev, AiFixtures.auto(), AiFixtures.request(2, "0.9"), usedTwice)));
        assertNull(AiPolicy.approval(lower, AiFixtures.facts(ev, AiFixtures.manual(), AiFixtures.request(1, "0.5"),
                usedTwice)));
    }

    /* ===== 경보 보류 ===== */

    @Test
    void S1_활성_warning만_보류한다() {
        assertNull(AiPolicy.s1Warning(alert(FP, "BotOrderLatencyHigh", "warning", "bot")));
        for (String severity : List.of("critical", "info", "other")) {
            denied("S1", AiPolicy.s1Warning(alert(FP, "X", severity, "bot")));
        }
        denied("S1", AiPolicy.s1Warning(null));
    }

    @Test
    void S2_30분_이하() {
        assertNull(AiPolicy.s2Minutes(30));
        denied("S2", AiPolicy.s2Minutes(31));
        denied("S2", AiPolicy.s2Minutes(0));
    }

    @Test
    void S3_같은_경보를_AI가_잇달아_보류하지_않는다() {
        assertNull(AiPolicy.s3NotConsecutive(false));
        denied("S3", AiPolicy.s3NotConsecutive(true));
    }

    @Test
    void S4_이미_눌린_경보는_다시_누르지_않는다() {
        AiEvidence.Alert a = alert(FP, "BotOrderLatencyHigh", "warning", "bot");
        assertNull(AiPolicy.s4NotSilenced(a));
        denied("S4", AiPolicy.s4NotSilenced(new AiEvidence.Alert(FP, a.alertname(), a.severity(), a.labels(),
                a.startsAt(), true, a.summary(), null)));
    }

    @Test
    void S5_관측_경로_경보는_보류하지_않는다() {
        assertNull(AiPolicy.s5NotObservability(alert(FP, "LinuxServiceFailed", "warning", "linux")));
        denied("S5", AiPolicy.s5NotObservability(alert(FP, "LinuxCollectorFailed", "warning", "monitoring")));
        denied("S5", AiPolicy.s5NotObservability(alert(FP, "WatchdogCheckerStale", "warning", "watchdog")));
    }

    @Test
    void S6_계획_작업_창_안의_경보만_창의_끝까지_보류한다() {
        AiEvidence.Alert bot = alert(FP, "BotOrderLatencyHigh", "warning", "bot");
        AiAction.Silence thirty = new AiAction.Silence(FP, 30);
        MaintenanceWindows.Window byLabel = window(null, Map.of("component", "bot"), NOW.minusSeconds(60),
                NOW.plus(Duration.ofHours(1)));
        MaintenanceWindows.Window byName = window("BotOrderLatencyHigh", Map.of(), NOW.minusSeconds(60),
                NOW.plus(Duration.ofHours(1)));
        assertNull(AiPolicy.s6PlannedWindow(thirty, bot, List.of(byLabel), NOW));
        assertNull(AiPolicy.s6PlannedWindow(thirty, bot, List.of(byName), NOW));

        AiPolicy.Denial none = AiPolicy.s6PlannedWindow(thirty, bot, List.of(), NOW);
        denied("S6", none);
        assertEquals("no_planned_window", none.code());
        assertTrue(none.message().startsWith("계획 작업 창 없음"), none.message());
        denied("S6", AiPolicy.s6PlannedWindow(thirty, bot, List.of(window("OtherAlert", Map.of(),
                NOW.minusSeconds(60), NOW.plus(Duration.ofHours(1)))), NOW));
        denied("S6", AiPolicy.s6PlannedWindow(thirty, bot, List.of(window(null, Map.of("component", "bot"),
                NOW.plusSeconds(60), NOW.plus(Duration.ofHours(1)))), NOW), "시작 전의 창");
        AiPolicy.Denial late = AiPolicy.s6PlannedWindow(thirty, bot, List.of(window(null, Map.of("component", "bot"),
                NOW.minusSeconds(60), NOW.plus(Duration.ofMinutes(20)))), NOW);
        denied("S6", late);
        assertEquals("window_ends_first", late.code());
    }

    @Test
    void S7_복제_DB_백업_디스크_계열은_창이_있어도_보류하지_않는다() {
        assertNull(AiPolicy.s7NotForbiddenFamily(alert(FP, "BotOrderLatencyHigh", "warning", "bot")));
        denied("S7", AiPolicy.s7NotForbiddenFamily(alert(FP, "DatabaseReplicationLagHigh", "warning", "database")));
        denied("S7", AiPolicy.s7NotForbiddenFamily(alert(FP, "ReplicationReplayDelayHigh", "warning", "db")));
        denied("S7", AiPolicy.s7NotForbiddenFamily(alert(FP, "DatabaseBackupRunFailed", "warning", "backup")));
        denied("S7", AiPolicy.s7NotForbiddenFamily(alert(FP, "LinuxDiskSpaceLow", "warning", "storage")));
        denied("S7", AiPolicy.s7NotForbiddenFamily(alert(FP, "ReplicaLagSomething", "warning", "linux")),
                "이름으로도 가른다");
    }

    /* ===== 봇 부하 ===== */

    @Test
    void B1_줄이는_방향만() {
        assertNull(AiPolicy.b1Reduce(new BigDecimal("0.5")));
        AiPolicy.Denial up = AiPolicy.b1Reduce(BigDecimal.ONE);
        denied("B1", up);
        assertEquals("direction_conflict", up.code());
    }

    @Test
    void B2_한_번에_절반까지와_하한() {
        AiEvidence.Bot four = new AiEvidence.Bot(4.0, "local", NOW);
        assertNull(AiPolicy.b2Factor(new BigDecimal("0.5"), four));
        denied("B2", AiPolicy.b2Factor(new BigDecimal("0.4"), four));
        denied("B2", AiPolicy.b2Factor(new BigDecimal("0.5"), new AiEvidence.Bot(1.5, "local", NOW)));
    }

    @Test
    void B3_현재_발행률을_알아야_한다() {
        assertNull(AiPolicy.b3Source(new AiEvidence.Bot(4.0, "published", NOW)));
        denied("B3", AiPolicy.b3Source(new AiEvidence.Bot(null, "unknown", null)));
    }

    /* ===== 백업 ===== */

    @Test
    void K1_Databasus_연결이_필요하다() {
        assertNull(AiPolicy.k1Connected(true));
        denied("K1", AiPolicy.k1Connected(false));
    }

    @Test
    void K2_하루_한_번과_디스크_여유와_진행_중_백업() {
        AiEvidence.Metric free = new AiEvidence.Metric(0.35, NOW);
        assertNull(AiPolicy.k2Auto(0, free, 30, false));
        denied("K2", AiPolicy.k2Auto(1, free, 30, false));
        denied("K2", AiPolicy.k2Auto(0, new AiEvidence.Metric(0.29, NOW), 30, false));
        denied("K2", AiPolicy.k2Auto(0, new AiEvidence.Metric(null, null), 30, false));
        denied("K2", AiPolicy.k2Auto(0, free, 30, true));
        denied("K2", AiPolicy.k2Auto(0, free, 40, false), "한도를 올리면 같은 여유도 막는다");
    }

    /* ===== 방향 전제 ===== */

    @Test
    void D1_증설은_증설_임계_이상일_때만() {
        assertNull(AiPolicy.d1ScaleOut(new AiFixtures.Ev().cpu(0.82, null).build()));
        AiPolicy.Denial low = AiPolicy.d1ScaleOut(new AiFixtures.Ev().cpu(0.40, null).build());
        denied("D1", low);
        assertEquals("direction_conflict", low.code());
        denied("D1", AiPolicy.d1ScaleOut(new AiFixtures.Ev().cpu(null, null).build()));
    }

    @Test
    void D2_축소는_축소_임계_미만이_설정_시간_이상_이어질_때만() {
        Duration sustain = Duration.ofMinutes(10);
        assertNull(AiPolicy.d2ScaleIn(new AiFixtures.Ev().cpu(0.20, NOW.minus(sustain)).build(), sustain, NOW));
        AiPolicy.Denial high = AiPolicy.d2ScaleIn(new AiFixtures.Ev().cpu(0.82, null).build(), sustain, NOW);
        denied("D2", high);
        assertTrue(high.message().startsWith("방향 모순"), high.message());
        denied("D2", AiPolicy.d2ScaleIn(new AiFixtures.Ev().cpu(0.20, NOW.minus(Duration.ofMinutes(9))).build(),
                sustain, NOW));
        denied("D2", AiPolicy.d2ScaleIn(new AiFixtures.Ev().cpu(0.20, null).build(), sustain, NOW));
    }

    @Test
    void D3_경보가_울리는_중에_임계를_둔하게_바꾸지_않는다() {
        AiAction.Threshold dull = threshold("host.disk_free_alert", "0.10", "0.15");
        AiAction.Threshold sharp = threshold("host.disk_free_alert", "0.20", "0.15");
        AiFixtures.Ev fine = new AiFixtures.Ev().metric(AiEvidence.HOST_ROOT_FREE_MIN, 0.5, NOW);
        assertNull(AiPolicy.d3Threshold(dull, fine.build()));

        AiFixtures.Ev firing = new AiFixtures.Ev().metric(AiEvidence.HOST_ROOT_FREE_MIN, 0.5, NOW);
        firing.alerts.add(new AiEvidence.Alert(FP, "LinuxDiskSpaceLow", "warning", Map.of("component", "storage"),
                NOW, false, "요약", "host.disk_free_alert"));
        AiPolicy.Denial storm = AiPolicy.d3Threshold(dull, firing.build());
        denied("D3", storm);
        assertEquals("direction_conflict", storm.code());
        assertNull(AiPolicy.d3Threshold(sharp, firing.build()), "민감하게 바꾸는 변경은 문제를 가리지 않는다");

        denied("D3", AiPolicy.d3Threshold(dull, new AiFixtures.Ev()
                .metric(AiEvidence.HOST_ROOT_FREE_MIN, 0.12, NOW).build()), "이미 임계를 넘은 지표");
        denied("D3", AiPolicy.d3Threshold(dull, new AiFixtures.Ev().build()), "근거 지표 미수집");

        AiAction.Threshold raiseOut = threshold("read.scale_out_cpu", "0.65", "0.60");
        denied("D3", AiPolicy.d3Threshold(raiseOut, new AiFixtures.Ev().cpu(0.82, null).build()));
        assertNull(AiPolicy.d3Threshold(raiseOut, new AiFixtures.Ev().cpu(0.30, null).build()));
        assertNull(AiPolicy.d3Threshold(threshold("read.scale_out_cpu", "0.55", "0.60"),
                new AiFixtures.Ev().cpu(0.82, null).build()));
    }

    /* ===== 조합 ===== */

    @Test
    void 이유와_반대인_축소_제안은_제안_때_D2로_막힌다() {
        AiEvidence ev = new AiFixtures.Ev().cpu(0.82, null).build();
        AiPolicy.Denial d = AiPolicy.proposal(new AiAction.ReadNodes(-1), AiFixtures.facts(ev, AiFixtures.manual(),
                null, AiPolicy.History.NONE));
        denied("D2", d);
        assertEquals("direction_conflict", d.code());
        assertNull(AiPolicy.proposal(new AiAction.ReadNodes(1), AiFixtures.facts(ev, AiFixtures.manual(), null,
                AiPolicy.History.NONE)));
    }

    @Test
    void 자동_실행은_모든_규칙을_지나야_한다() {
        AiEvidence ev = new AiFixtures.Ev().cpu(0.82, null).build();
        AiAction add = new AiAction.ReadNodes(1);
        assertNull(AiPolicy.auto(add, AiFixtures.facts(ev, AiFixtures.auto(), AiFixtures.request(2, "0.9"),
                AiPolicy.History.NONE)));
        denied("A1", AiPolicy.auto(add, AiFixtures.facts(ev, AiFixtures.manual(), AiFixtures.request(2, "0.9"),
                AiPolicy.History.NONE)));
        denied("A4", AiPolicy.auto(add, AiFixtures.facts(ev, AiFixtures.auto(), AiFixtures.request(1, "0.9"),
                AiPolicy.History.NONE)));
        denied("A3", AiPolicy.auto(add, AiFixtures.facts(ev, AiFixtures.auto(), AiFixtures.request(2, "0.7"),
                AiPolicy.History.NONE)));
        denied("A7", AiPolicy.auto(add, AiFixtures.facts(ev, AiFixtures.auto(), AiFixtures.request(2, "0.9"),
                new AiPolicy.History(false, null, 5, 0, null, null, null, false, 0, false))));
        denied("A6", AiPolicy.auto(add, AiFixtures.facts(ev, AiFixtures.auto(), AiFixtures.request(2, "0.9"),
                new AiPolicy.History(false, NOW.plusSeconds(10), 0, 0, null, null, null, false, 0, false))));
        denied("D1", AiPolicy.auto(add, AiFixtures.facts(new AiFixtures.Ev().cpu(0.40, null).build(), AiFixtures.auto(),
                AiFixtures.request(2, "0.9"), AiPolicy.History.NONE)));
    }

    @Test
    void 승인은_열려_있고_만료_전인_요청만_실행한다() {
        AiEvidence ev = new AiFixtures.Ev().cpu(0.82, null).build();
        AiAction add = new AiAction.ReadNodes(1);
        assertNull(AiPolicy.approval(add, AiFixtures.facts(ev, AiFixtures.manual(), AiFixtures.request(1, "0.5"),
                AiPolicy.History.NONE)));
        for (String status : List.of("APPROVED", "AUTO_EXECUTED", "REJECTED", "EXPIRED", "WITHDRAWN", "DENIED",
                "REVERTED")) {
            denied("C3", AiPolicy.approval(add, AiFixtures.facts(ev, AiFixtures.manual(),
                    new AiPolicy.Request(status, NOW.plus(Duration.ofMinutes(10)), null, 2, BigDecimal.ONE),
                    AiPolicy.History.NONE)), status);
        }
        denied("C3", AiPolicy.approval(add, AiFixtures.facts(ev, AiFixtures.manual(),
                new AiPolicy.Request("DEFERRED", NOW, NOW, 1, BigDecimal.ONE), AiPolicy.History.NONE)), "만료");
    }

    @Test
    void C5_임계값_요청은_요청을_만들_때의_현재값에서만_실행한다() {
        AiEvidence ev = new AiFixtures.Ev().cpu(0.30, null).build();
        AiAction.Threshold asProposed = threshold("read.scale_out_cpu", "0.55", "0.60");
        assertNull(AiPolicy.approval(asProposed, AiFixtures.facts(ev, AiFixtures.manual(), AiFixtures.request(1, "0.9"),
                AiPolicy.History.NONE)));
        // 요청을 만든 뒤 사람이 0.60 을 0.50 으로 바꿨다. 0.50→0.55 는 요청이 본 0.60→0.55 와 방향이 반대다.
        AiEvidence changed = new AiFixtures.Ev().cpu(0.30, null)
                .threshold("read.scale_out_cpu", "0.50", "0.20", "0.85", "0.05", true).build();
        AiPolicy.Denial d = AiPolicy.approval(asProposed, AiFixtures.facts(changed, AiFixtures.manual(),
                AiFixtures.request(1, "0.9"), AiPolicy.History.NONE));
        denied("C5", d);
        assertEquals("target_changed", d.code());
        denied("C5", AiPolicy.auto(asProposed, AiFixtures.facts(changed, AiFixtures.auto(), AiFixtures.request(2, "0.9"),
                AiPolicy.History.NONE)));
    }

    @Test
    void 자동_실행은_판단이_받은_근거가_응답_때_60초를_넘었으면_A5로_막는다() {
        AiEvidence fresh = new AiFixtures.Ev().cpu(0.82, null).build();
        AiFixtures.Ev late = new AiFixtures.Ev().cpu(0.82, null);
        late.read = new AiEvidence.ReadTier(2, 3, 4, 3, 0.82, NOW.minusSeconds(61), null);
        AiAction add = new AiAction.ReadNodes(1);
        AiPolicy.Facts facts = new AiPolicy.Facts(NOW, fresh, AiFixtures.auto(), limits(), Duration.ofMinutes(10),
                AiFixtures.request(2, "0.9"), new AiPolicy.Decision("PROPOSED", NOW, late.build()), AiPolicy.History.NONE);
        AiPolicy.Denial d = AiPolicy.auto(add, facts);
        denied("A5", d);
        assertEquals("stale_evidence", d.code());
    }

    @Test
    void 자동_보류는_같은_경보의_직전_보류가_AI면_S3로_막는다() {
        AiFixtures.Ev ev = new AiFixtures.Ev();
        ev.alerts.add(alert(FP, "BotOrderLatencyHigh", "warning", "bot"));
        ev.windows.add(window(null, Map.of("component", "bot"), NOW.minusSeconds(60), NOW.plus(Duration.ofHours(1))));
        AiAction.Silence silence = new AiAction.Silence(FP, 30);
        assertNull(AiPolicy.auto(silence, AiFixtures.facts(ev.build(), AiFixtures.auto(), AiFixtures.request(2, "0.9"),
                AiPolicy.History.NONE)));
        AiPolicy.History byAi = history(null, null, null, true);
        denied("S3", AiPolicy.auto(silence, AiFixtures.facts(ev.build(), AiFixtures.auto(), AiFixtures.request(2, "0.9"),
                byAi)));
        assertNull(AiPolicy.approval(silence, AiFixtures.facts(ev.build(), AiFixtures.manual(),
                AiFixtures.request(1, "0.9"), byAi)), "사람의 승인에는 연속 보류 규칙을 걸지 않는다");
    }

    @Test
    void 사람의_승인은_빈도_규칙과_자동_규칙을_걸지_않지만_방향_전제는_건다() {
        AiEvidence ev = new AiFixtures.Ev().cpu(0.82, null).build();
        AiPolicy.History busy = new AiPolicy.History(false, NOW.plusSeconds(10), 5, 0, NOW, null, null, false, 0, false);
        assertNull(AiPolicy.approval(new AiAction.ReadNodes(1), AiFixtures.facts(ev, AiFixtures.manual(),
                AiFixtures.request(1, "0.1"), busy)));
        denied("D2", AiPolicy.approval(new AiAction.ReadNodes(-1), AiFixtures.facts(ev, AiFixtures.manual(),
                AiFixtures.request(1, "0.9"), busy)));
    }

    private static AiAction.Threshold threshold(String key, String value, String from) {
        return new AiAction.Threshold(key, new BigDecimal(value), from == null ? null : new BigDecimal(from));
    }

    private static AiPolicy.History history(Instant lastSameKind, Instant deferredUntil, Instant rejectedAt,
                                            boolean lastSilenceByAi) {
        return new AiPolicy.History(false, null, 0, 0, lastSameKind, deferredUntil, rejectedAt, lastSilenceByAi, 0, false);
    }
}

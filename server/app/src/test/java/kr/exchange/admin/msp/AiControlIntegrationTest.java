package kr.exchange.admin.msp;

import kr.exchange.api.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 모드, 정지와 재개, AI 한도, 계획 작업 창을 실제 DB 로 본다(설계 7-9·7-14~7-16, ADR 0035). 한도는 1인 승인
 * 티켓(ops.ai.limits)으로만 바뀌고, 자동 조치 상한은 자동 모드 활성마다 새로 센다.
 */
@Testcontainers
class AiControlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final AiControl.AutoReadiness READY = new AiControl.AutoReadiness(true, true, false);

    private AiRig.Sources src;
    private AiRig rig;

    @BeforeAll
    static void migrate() {
        AiRig.migrate(POSTGRES);
    }

    @BeforeEach
    void start() throws IOException {
        src = new AiRig.Sources();
        rig = new AiRig(POSTGRES, src, MspProperties.Llm.Provider.OPENAI_COMPATIBLE, p -> { });
    }

    @AfterEach
    void stop() {
        rig.close();
        src.close();
    }

    private AiControl.LimitApplied change(String key, int value, String reason) {
        return rig.control.changeLimit(key, value, reason, AiRig.SUPER,
                rig.ticket(AiControl.LIMITS_ACTION, key + ":" + value), "req-limit");
    }

    private String approvalState(String token) {
        return rig.text("SELECT state FROM admin_approvals WHERE approval_token = '" + token + "'");
    }

    @Test
    void 한도_변경은_티켓을_쓰고_이력과_감사와_사건을_남긴다() {
        ApiException noTicket = assertThrows(ApiException.class,
                () -> rig.control.changeLimit("confidence_pct", 90, "조임", AiRig.SUPER, null, "req"));
        assertEquals(403, noTicket.status());
        assertEquals("approval_required", noTicket.detail().get("reason"));

        String outOfRange = rig.ticket(AiControl.LIMITS_ACTION, "confidence_pct:96");
        ApiException range = assertThrows(ApiException.class,
                () -> rig.control.changeLimit("confidence_pct", 96, "넘김", AiRig.SUPER, outOfRange, "req"));
        assertEquals(400, range.status());
        assertEquals("ISSUED", approvalState(outOfRange), "하드 범위는 티켓을 쓰기 전에 본다");

        ApiException unknown = assertThrows(ApiException.class,
                () -> rig.control.changeLimit("silence_minutes", 20, "없음", AiRig.SUPER, null, "req"));
        assertEquals(404, unknown.status());

        String wrongValue = rig.ticket(AiControl.LIMITS_ACTION, "confidence_pct:85");
        ApiException binding = assertThrows(ApiException.class,
                () -> rig.control.changeLimit("confidence_pct", 90, "다른 값", AiRig.SUPER, wrongValue, "req"));
        assertEquals("binding_mismatch", binding.detail().get("reason"), "티켓은 항목과 값에 묶인다");

        AiControl.LimitApplied applied = change("confidence_pct", 90, "오판이 잦아 기준을 올림");

        assertEquals(80, applied.from());
        assertEquals(90, applied.to());
        assertEquals(2, applied.version());
        assertTrue(applied.immediate(), "수동 모드에서는 바로 적용된다");
        assertEquals(90, rig.control.configuredLimits().confidencePct());
        AiControl.LimitItem item = rig.control.limitItems().get(0);
        assertEquals(AiLimits.Key.CONFIDENCE_PCT, item.key());
        assertEquals(AiRig.SUPER, item.updatedBy());
        assertEquals(2, rig.count("SELECT count(*) FROM ops_ai_limit_changes WHERE key = 'confidence_pct'"));
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.limits' "
                + "AND target = 'confidence_pct' AND approval_id IS NOT NULL AND detail->>'applies' = 'NOW'"));
        assertEquals(1, rig.count("SELECT count(*) FROM ops_incident_events WHERE kind = 'LIMITS'"));
        List<AiControl.LimitChange> history = rig.control.limitChanges("confidence_pct", null, 10).stream()
                .map(r -> r.view()).toList();
        assertEquals(90, history.get(0).to());
        assertEquals(80, history.get(0).from());
        assertEquals(null, history.get(1).from(), "초기값 행은 이전 값이 없다");
    }

    @Test
    void 자동_중에_조이는_변경은_곧바로_푸는_변경은_다음_활성부터_적용된다() {
        AiControl.Mode first = rig.auto(4);

        AiControl.LimitApplied loosen = change("confidence_pct", 75, "기준을 낮춤");
        assertFalse(loosen.immediate());
        assertEquals(80, loosen.effective());
        AiControl.LimitApplied tighten = change("auto_cap", 3, "상한을 줄임");
        assertTrue(tighten.immediate());
        assertEquals(3, tighten.effective());
        assertFalse(tighten.switchedToManual());

        AiLimits during = rig.control.effectiveLimits(rig.control.mode());
        assertEquals(80, during.confidencePct());
        assertEquals(3, during.autoCap());
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.limits' "
                + "AND detail->>'applies' = 'NEXT_ACTIVATION'"));

        AiControl.Mode renewed = rig.control.enableAuto(AiRig.SUPER, rig.ticket(AiControl.AUTO_ACTION, "AUTO:2"), 2,
                "창 새로 열기", "req-renew", READY);
        assertEquals(first.activation(), renewed.activation(), "자동인 채로 다시 켜면 활성이 이어진다");
        assertEquals(80, rig.control.effectiveLimits(renewed).confidencePct(), "활성 때 한도도 이어진다");

        rig.control.manual(AiRig.SUPER, "다시 켜기", "req-manual");
        AiControl.Mode second = rig.auto(4);
        assertEquals(first.activation() + 1, second.activation());
        AiLimits next = rig.control.effectiveLimits(second);
        assertEquals(75, next.confidencePct(), "푸는 변경은 다음 활성부터");
        assertEquals(3, next.autoCap());
    }

    @Test
    void 자동_조치_상한을_이미_쓴_횟수_이하로_내리면_수동으로_돌린다() {
        AiControl.Mode mode = rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = AiRig.out("조회 계층 평균 CPU가 높습니다.", "조회 노드를 1대 늘립니다.", "READ_NODE_ADD_ONE", "app", "",
                "0.9");
        rig.ticks(2, Duration.ofMinutes(1));
        assertEquals(1, rig.control.autoUsed(mode.activation()));

        AiControl.LimitApplied applied = change("auto_cap", 1, "상한을 바로 닫음");

        assertTrue(applied.switchedToManual());
        assertFalse(applied.mode().auto());
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.mode.system' "
                + "AND detail->>'cause' = 'auto_cap_lowered'"));
        assertTrue(rig.notices.stream().anyMatch(n -> n.kind() == AiNotifier.Kind.MODE_CHANGED
                && "auto_cap_lowered".equals(n.facts().get("cause"))));
    }

    @Test
    void 자동_켜기는_유효_시간과_원천과_평문과_정지와_결과_모름_요청을_본다() {
        assertEquals(400, assertThrows(ApiException.class, () -> rig.control.enableAuto(AiRig.SUPER,
                rig.ticket(AiControl.AUTO_ACTION, "AUTO:3"), 3, "시험", "req", READY)).status());
        ApiException llm = assertThrows(ApiException.class, () -> rig.control.enableAuto(AiRig.SUPER,
                rig.ticket(AiControl.AUTO_ACTION, "AUTO:4"), 4, "시험", "req",
                new AiControl.AutoReadiness(false, true, false)));
        assertEquals("msp_source_not_connected", llm.name());
        assertEquals("llm", llm.detail().get("source"));
        ApiException prometheus = assertThrows(ApiException.class, () -> rig.control.enableAuto(AiRig.SUPER,
                rig.ticket(AiControl.AUTO_ACTION, "AUTO:4"), 4, "시험", "req",
                new AiControl.AutoReadiness(true, false, false)));
        assertEquals("prometheus", prometheus.detail().get("source"));
        ApiException plaintext = assertThrows(ApiException.class, () -> rig.control.enableAuto(AiRig.SUPER,
                rig.ticket(AiControl.AUTO_ACTION, "AUTO:4"), 4, "시험", "req",
                new AiControl.AutoReadiness(true, true, true)));
        assertEquals("M8", plaintext.detail().get("rule"));
        assertEquals(403, assertThrows(ApiException.class, () -> rig.control.enableAuto(AiRig.SUPER, null, 4,
                "시험", "req", READY)).status());

        long decision = rig.store.insertDecision(new AiStore.NewDecision(rig.clock.instant(), "MANUAL", "PROPOSED",
                null, "test-model", "{}", null, "{}", "상황입니다.", "이유입니다.", new BigDecimal("0.9"),
                "SILENCE:d4d4d4d4d4d4d4d4:10", "[]", 5, null));
        rig.jdbc.sql("""
                        INSERT INTO ops_ai_actions (first_decision_id, last_decision_id, kind, identity_key, params, target,
                                                    confidence, status, created_at, expires_at)
                        VALUES (?, ?, 'SILENCE', 'SILENCE:d4d4d4d4d4d4d4d4:10',
                                '{"fingerprint":"d4d4d4d4d4d4d4d4","minutes":10}'::jsonb, 'd4d4d4d4d4d4d4d4', 0.9,
                                'UNKNOWN', now(), now())
                        """)
                .params(decision, decision).update();
        String token = rig.ticket(AiControl.AUTO_ACTION, "AUTO:4");
        ApiException unknown = assertThrows(ApiException.class, () -> rig.control.enableAuto(AiRig.SUPER, token, 4,
                "시험", "req", READY));
        assertEquals("M1", unknown.detail().get("rule"));
        assertEquals("ISSUED", approvalState(token), "막히면 티켓은 쓰이지 않는다");

        rig.jdbc.sql("UPDATE ops_ai_actions SET status = 'FAILED' WHERE status = 'UNKNOWN'").update();
        AiControl.Mode on = rig.control.enableAuto(AiRig.SUPER, token, 4, "야간 관제", "req-on", READY);
        assertTrue(on.auto());
        assertEquals(Duration.ofHours(4), Duration.between(on.since(), on.validUntil()));
        assertEquals(AiRig.SUPER, on.enabledBy());
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.auto' AND result = 'OK' "
                + "AND target = 'AUTO:4' AND approval_id IS NOT NULL"));
        assertEquals("CONSUMED", approvalState(token));
    }

    @Test
    void 정지와_재개는_두_모드에서_쓰고_거듭해도_바뀐_것이_없다() {
        AiControl.Changed first = rig.control.stop(AiRig.VIEWER, "", "req-stop");
        assertTrue(first.changed());
        assertTrue(first.mode().stopped());
        assertFalse(rig.control.stop(AiRig.SUPER, "다시", "req-stop2").changed());
        assertEquals(AiRig.VIEWER, rig.control.mode().stoppedBy(), "먼저 정지한 사람이 남는다");
        assertEquals(400, assertThrows(ApiException.class,
                () -> rig.control.stop(AiRig.VIEWER, "가".repeat(201), "req")).status());

        assertTrue(rig.control.resume(AiRig.SUPER, "확인 끝", "req-resume").changed());
        assertFalse(rig.control.resume(AiRig.SUPER, "확인 끝", "req-resume2").changed());
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.stop'"));
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.resume'"));

        rig.auto(2);
        AiControl.Changed stopped = rig.control.stop(AiRig.VIEWER, "자동 중 정지", "req-stop3");
        assertTrue(stopped.changed());
        assertFalse(stopped.mode().auto());
        assertEquals(null, stopped.mode().validUntil());
        assertEquals("true", rig.text("SELECT detail->>'was_auto' FROM admin_audit_log WHERE action = 'ops.ai.stop' "
                + "AND request_id = 'req-stop3'"));
    }

    @Test
    void 끄기는_이미_수동이면_바뀐_것이_없다() {
        assertFalse(rig.control.manual(AiRig.VIEWER, null, "req").changed());
        rig.auto(1);
        AiControl.Changed off = rig.control.manual(AiRig.VIEWER, "사람이 끔", "req-off");
        assertTrue(off.changed());
        assertFalse(off.mode().auto());
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.manual' "
                + "AND operator_id = 'op-view'"));
    }

    @Test
    void 계획_작업_창은_대상과_길이를_검사하고_일찍_끝낼_수_있다() {
        Instant now = rig.clock.instant();
        Instant later = now.plus(Duration.ofHours(2));
        assertEquals("required", assertThrows(ApiException.class, () -> rig.windows.create(AiRig.BOT, null, later,
                null, Map.of(), "대상 없음", "req")).detail().get("constraint"));
        assertEquals("range", assertThrows(ApiException.class, () -> rig.windows.create(AiRig.BOT, now,
                now.plus(Duration.ofHours(25)), "BotOrderLatencyHigh", Map.of(), "길다", "req")).detail()
                .get("constraint"));
        assertEquals("range", assertThrows(ApiException.class, () -> rig.windows.create(AiRig.BOT, null,
                now.minus(Duration.ofMinutes(1)), "BotOrderLatencyHigh", Map.of(), "지남", "req")).detail()
                .get("constraint"));
        assertEquals("labels", assertThrows(ApiException.class, () -> rig.windows.create(AiRig.BOT, null, later,
                null, Map.of("instance", "10.0.0.1:9100"), "주소 라벨", "req")).detail().get("field"));
        assertEquals("reason", assertThrows(ApiException.class, () -> rig.windows.create(AiRig.BOT, null, later,
                "BotOrderLatencyHigh", Map.of(), " ", "req")).detail().get("field"));

        MaintenanceWindows.Window w = rig.windows.create(AiRig.BOT, null, later, "BotOrderLatencyHigh",
                Map.of("component", "bot"), "봇 배포", "req-window");
        assertEquals(AiRig.BOT, w.createdBy());
        assertTrue(w.matches("BotOrderLatencyHigh", Map.of("component", "bot", "node", "bot-1")));
        assertFalse(w.matches("BotOrderLatencyHigh", Map.of("component", "app")));
        assertEquals(1, rig.windows.active().size());

        rig.clock.advance(Duration.ofMinutes(10));
        MaintenanceWindows.Ended ended = rig.windows.end(w.id(), AiRig.VIEWER, "req-end");
        assertTrue(ended.changed());
        assertEquals(AiRig.VIEWER, ended.window().endedBy());
        assertTrue(rig.windows.active().isEmpty());
        assertFalse(rig.windows.end(w.id(), AiRig.VIEWER, "req-end2").changed());
        assertEquals(404, assertThrows(ApiException.class, () -> rig.windows.end(w.id() + 1000, AiRig.VIEWER,
                "req")).status());
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'msp.window.create'"));
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'msp.window.end'"));
        assertEquals(2, rig.count("SELECT count(*) FROM ops_incident_events WHERE kind = 'WINDOW'"));
    }
}

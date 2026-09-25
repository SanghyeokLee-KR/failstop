package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.admin.AdminPrincipal;
import kr.exchange.admin.AdminRole;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 판단 주기와 실행기를 실제 DB 와 가짜 LLM 스텁으로 돌린다(설계 7-1·7-6~7-8·7-14). 모델이 무엇을 내든 정책 엔진이
 * 다시 거르는지, 자동 실행의 빈도 규칙과 되돌림이 기록과 함께 동작하는지 본다. 실제 모델과 Teams 는 부르지 않는다.
 */
@Testcontainers
class AiLoopIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final String ADD = AiRig.out("조회 계층 평균 CPU가 82%로 증설 임계 60%를 넘었습니다.",
            "조회 노드를 1대 늘려 부하를 나눠야 합니다.", "READ_NODE_ADD_ONE", "app", "", "0.9");
    private static final String REMOVE = AiRig.out("조회 계층 평균 CPU가 10%로 낮습니다.",
            "축소 임계 아래가 이어져 조회 노드를 1대 줄여도 됩니다.", "READ_NODE_REMOVE_ONE", "app", "", "0.9");
    // 로컬 실측(설계 7-13)의 오답 모양이다. 이유는 늘리자고 하고 조치는 줄이는 쪽이다.
    private static final String CONTRADICT = AiRig.out("조회 계층 평균 CPU가 82%로 증설 임계 60%를 넘었습니다.",
            "조회 노드를 1대 늘려야 합니다.", "READ_NODE_REMOVE_ONE", "app", "", "0.9");
    private static final String NONE = AiRig.out("안정입니다.", "조치가 필요 없습니다.", "NO_ACTION", "", "", "0.9");
    private static final String LOWER_OUT = AiRig.out("조회 계층 평균 CPU가 25%입니다.", "증설 임계를 0.55로 내려 더 일찍 늘립니다.",
            "THRESHOLD_LOWER", "read.scale_out_cpu", "0.55", "0.9");
    private static final String RAISE_OUT = AiRig.out("조회 계층 평균 CPU가 25%입니다.", "증설 임계를 0.55로 올립니다.",
            "THRESHOLD_RAISE", "read.scale_out_cpu", "0.55", "0.9");

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

    private AiStore.Decision lastDecision() {
        return rig.store.lastDecision().orElseThrow();
    }

    private AiStore.Action reload(AiStore.Action a) {
        return rig.store.action(a.id()).orElseThrow();
    }

    private long audits(String action, String result) {
        return rig.count("SELECT count(*) FROM admin_audit_log WHERE action = '" + action + "' AND result = '" + result
                + "'");
    }

    private long events(String kind) {
        return rig.count("SELECT count(*) FROM ops_incident_events WHERE kind = '" + kind + "'");
    }

    private BigDecimal value(String key) {
        return rig.thresholds.item(key).orElseThrow().value();
    }

    /** 다음 노드 관측(근거 수집) 한 번의 도중에 사람의 조작을 끼워 넣는다. 근거를 모은 뒤 커밋된 변경을 흉내 낸다. */
    private void onNextNodeProbe(Runnable human) {
        Function<StubSource.Request, StubSource.Response> original = src.node.handler;
        AtomicBoolean done = new AtomicBoolean();
        src.node.handler = r -> {
            if (r.path().equals("/actuator/prometheus") && done.compareAndSet(false, true)) {
                human.run();
            }
            return original.apply(r);
        };
    }

    @Test
    void 판단_거리가_없으면_모델을_부르지_않는다() {
        src.cpu = 0.45;

        rig.ticks(2, MINUTE);

        assertEquals(0, src.chatCalls());
        assertEquals(1.0, rig.skips("calm"));
        assertEquals(1.0, rig.skips("calm_wait"), "평온하면 평온 주기 동안 근거도 모으지 않는다");
        assertEquals(0, rig.count("SELECT count(*) FROM ops_ai_decisions"));
    }

    @Test
    void 수동_모드의_정상_제안은_요청으로_남고_사람이_승인해야_실행된다() throws Exception {
        src.cpu = 0.82;
        src.llmContent = ADD;

        rig.ticks(1, MINUTE);

        AiStore.Decision d = lastDecision();
        assertEquals("PROPOSED", d.outcome());
        assertEquals("MANUAL", d.mode());
        assertEquals("READ_NODES:+1", d.actionKey());
        assertEquals(LlmClient.PROMPT_VERSION, d.promptVersion());
        AiStore.Action a = rig.latest();
        assertEquals("PROPOSED", a.status());
        assertEquals(1, a.consecutive());
        assertEquals(1, events("AI_JUDGED"));

        JsonNode body = rig.mapper.readTree(src.llm.requests("/v1/chat/completions").get(0).body());
        JsonNode evidence = rig.mapper.readTree(body.path("messages").get(1).path("content").asText());
        assertEquals("msp-evidence/2", evidence.path("schema").asText());
        assertTrue(evidence.path("read_tier").path("avg_cpu").path("value").asText().startsWith("0.82"));
        assertTrue(evidence.path("catalog").toString().contains("READ_NODE_ADD_ONE"), "쓸 수 있는 조치 목록을 싣는다");
        assertFalse(evidence.toString().contains("127.0.0.1"), "근거에 사설 주소와 포트를 싣지 않는다");

        rig.ticks(1, MINUTE);
        a = reload(a);
        assertEquals(2, a.consecutive());
        assertEquals("PROPOSED", a.status(), "수동 모드는 연속이어도 실행하지 않는다");
        assertEquals(2, rig.desired());

        AiStore.Action done = rig.executor.approve(a.id(), AiRig.principal(AiRig.SUPER, AdminRole.SUPER), null,
                "req-approve");
        assertEquals("APPROVED", done.status());
        assertEquals(AiRig.SUPER, done.decidedBy());
        assertEquals(3, rig.desired());
        assertNull(done.activation(), "사람의 승인은 자동 조치 횟수에 들지 않는다");
        assertEquals(1, audits("ops.ai.action.approve", "OK"));
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'capacity.scale_out' "
                + "AND operator_id = 'op-super'"));
    }

    @Test
    void 같은_요청을_두_번_승인하면_두_번째는_이미_처리된_요청이고_대수는_한_번만_바뀐다() {
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(1, MINUTE);
        AiStore.Action a = rig.latest();

        rig.executor.approve(a.id(), AiRig.principal(AiRig.SUPER, AdminRole.SUPER), null, "req-1");
        assertEquals(3, rig.desired());
        ApiException again = assertThrows(ApiException.class,
                () -> rig.executor.approve(a.id(), AiRig.principal(AiRig.SUPER, AdminRole.SUPER), null, "req-2"));

        assertEquals(409, again.status());
        assertEquals("ai_action_not_open", again.name());
        assertEquals(3, rig.desired(), "두 번째 승인은 용량 계획을 다시 바꾸지 않는다");
        assertEquals(1, audits("capacity.scale_out", "OK"));
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.action.approve' "
                + "AND result = 'DENIED' AND detail->>'rule' = 'C3'"));
    }

    @Test
    void 임계값_조치의_승인과_되돌리기는_실제로_쓰는_값에_묶인_티켓을_쓴다() {
        rig.nodes(3);
        src.cpu = 0.25;
        src.llmContent = LOWER_OUT;
        rig.ticks(1, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals(AiAction.Verb.THRESHOLD_LOWER, a.action().verb());
        AdminPrincipal operator = AiRig.principal(AiRig.SUPER, AdminRole.SUPER);

        AiStore.Action done = rig.executor.approve(a.id(), operator,
                rig.ticket(OpsThresholdService.APPROVAL_ACTION, "read.scale_out_cpu:0.55"), "req-approve");
        assertEquals("APPROVED", done.status());
        assertEquals("0.6", done.result().path("from").asText());
        assertEquals(0, new BigDecimal("0.55").compareTo(value("read.scale_out_cpu")));

        ApiException wrong = assertThrows(ApiException.class, () -> rig.executor.revert(a.id(), operator,
                rig.ticket(OpsThresholdService.APPROVAL_ACTION, "read.scale_out_cpu:0.55"), "req-revert"));
        assertEquals("binding_mismatch", wrong.detail().get("reason"), "되돌리기 티켓은 다시 쓸 값에 묶인다");
        AiStore.Action reverted = rig.executor.revert(a.id(), operator,
                rig.ticket(OpsThresholdService.APPROVAL_ACTION, "read.scale_out_cpu:0.6"), "req-revert");
        assertEquals("REVERTED", reverted.status());
        assertEquals(0, new BigDecimal("0.6").compareTo(value("read.scale_out_cpu")));
    }

    @Test
    void 근거를_모은_뒤_사람이_임계값을_바꾸면_승인은_C5로_멈추고_사람의_값을_덮어쓰지_않는다() {
        rig.nodes(3);
        src.cpu = 0.25;
        src.llmContent = LOWER_OUT;
        rig.ticks(1, MINUTE);
        AiStore.Action a = rig.latest();
        String human = rig.ticket(OpsThresholdService.APPROVAL_ACTION, "read.scale_out_cpu:0.7");
        onNextNodeProbe(() -> rig.thresholds.set("read.scale_out_cpu", "0.7", "사람의 변경", AiRig.SUPER, human,
                "req-human"));
        String approval = rig.ticket(OpsThresholdService.APPROVAL_ACTION, "read.scale_out_cpu:0.55");

        ApiException e = assertThrows(ApiException.class, () -> rig.executor.approve(a.id(),
                AiRig.principal(AiRig.SUPER, AdminRole.SUPER), approval, "req-approve"));

        assertEquals(409, e.status());
        assertEquals("C5", e.detail().get("rule"));
        assertEquals("target_changed", e.detail().get("reason_code"));
        assertEquals(0, new BigDecimal("0.7").compareTo(value("read.scale_out_cpu")), "사람의 값을 덮어쓰지 않는다");
        assertEquals("PROPOSED", reload(a).status());
        assertEquals("ISSUED", rig.text("SELECT state FROM admin_approvals WHERE approval_token = '" + approval + "'"),
                "거부되면 승인 티켓은 남는다");
    }

    @Test
    void 현재값이_바뀐_뒤의_같은_새_값_판단은_새_요청이고_방향과_연속_횟수를_새로_센다() {
        rig.nodes(3);
        src.cpu = 0.25;
        src.llmContent = LOWER_OUT;
        rig.ticks(1, MINUTE);
        AiStore.Action first = rig.latest();
        assertEquals(AiAction.Verb.THRESHOLD_LOWER, first.action().verb());

        rig.thresholds.set("read.scale_out_cpu", "0.5", "사람의 변경", AiRig.SUPER,
                rig.ticket(OpsThresholdService.APPROVAL_ACTION, "read.scale_out_cpu:0.5"), "req-human");
        src.llmContent = RAISE_OUT;
        rig.ticks(1, MINUTE);

        AiStore.Action second = rig.latest();
        assertNotEquals(first.id(), second.id());
        assertEquals("WITHDRAWN", reload(first).status());
        assertEquals(first.identityKey(), second.identityKey());
        assertEquals("PROPOSED", second.status());
        assertEquals(1, second.consecutive(), "반대 방향의 두 판단을 연속으로 세지 않는다");
        assertEquals(AiAction.Verb.THRESHOLD_RAISE, second.action().verb());
        assertEquals("0.5", second.action().params().get("from"));
    }

    @Test
    void 자동_모드에서_둔하게_하는_임계값_변경은_실행하지_않고_요청으로_남겨_사람이_승인하면_실행한다() {
        rig.nodes(3);
        src.cpu = 0.25;
        rig.auto(4);
        src.llmContent = AiRig.out("조회 계층 평균 CPU가 25%입니다.", "증설 임계를 0.65로 올려 번갈아 실행을 줄입니다.",
                "THRESHOLD_RAISE", "read.scale_out_cpu", "0.65", "0.9");

        rig.ticks(2, MINUTE);

        AiStore.Action a = rig.latest();
        assertEquals(2, a.consecutive(), "연속 두 번이라 자동 실행 조건의 다른 규칙은 채웠다");
        assertEquals("PROPOSED", a.status());
        assertEquals("T6", a.holdRule());
        assertEquals(0, new BigDecimal("0.6").compareTo(value("read.scale_out_cpu")), "자동으로 바꾸지 않는다");
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.action.execute' "
                + "AND detail->>'rule' = 'T6' AND detail->>'reason_code' = 'loosening'"));

        AiStore.Action done = rig.executor.approve(a.id(), AiRig.principal(AiRig.SUPER, AdminRole.SUPER),
                rig.ticket(OpsThresholdService.APPROVAL_ACTION, "read.scale_out_cpu:0.65"), "req-approve");
        assertEquals("APPROVED", done.status(), "사람의 승인으로는 실행한다");
        assertEquals(0, new BigDecimal("0.65").compareTo(value("read.scale_out_cpu")));
    }

    @Test
    void 조회_노드_승인은_SUPER_만_한다() {
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(1, MINUTE);
        AiStore.Action a = rig.latest();

        ApiException e = assertThrows(ApiException.class,
                () -> rig.executor.approve(a.id(), AiRig.principal(AiRig.BOT, AdminRole.BOT_OPS), null, "req"));

        assertEquals(403, e.status());
        assertEquals("PROPOSED", reload(a).status());
    }

    @Test
    void 형식이_틀린_출력은_버리고_이어지면_한_번_통지한다() {
        rig.auto(4);
        src.cpu = 0.82;

        src.llmContent = "{\"action\":\"READ_NODE_ADD_ONE\",\"confidence\":0.9}";
        rig.ticks(1, MINUTE);
        AiStore.Decision shape = lastDecision();
        assertEquals("INVALID_OUTPUT", shape.outcome());
        assertEquals("shape", shape.error());
        assertEquals(src.llmContent, rig.store.decision(shape.id()).orElseThrow().outputRaw(), "원문은 남긴다");

        src.llmContent = "노드를 늘리십시오.";
        rig.ticks(1, MINUTE);
        assertEquals("not_json", lastDecision().error());

        src.llmContent = AiRig.out("상황입니다.", "자세한 내용은 https://example.com 을 보십시오.", "NO_ACTION", "", "",
                "0.9");
        rig.ticks(1, MINUTE);
        assertEquals("link", lastDecision().error());

        assertEquals(0, rig.count("SELECT count(*) FROM ops_ai_actions"));
        assertEquals(3.0, rig.registry.counter("exchange.msp.ai.judgments", "outcome", "INVALID_OUTPUT").count());
        assertTrue(rig.noticed(AiNotifier.Kind.MODEL_FAILING), "자동 모드에서 세 번 이어지면 통지한다");
        assertTrue(rig.control.mode().auto(), "모델 실패는 모드를 바꾸지 않는다");
        assertEquals(2, rig.desired());
    }

    @Test
    void 응답이_없으면_모델_오류로_남기고_다음_주기에_다시_묻는다() {
        src.cpu = 0.82;
        src.llmDelayMs = 3000;

        rig.ticks(1, MINUTE);

        AiStore.Decision failed = lastDecision();
        assertEquals("MODEL_ERROR", failed.outcome());
        assertEquals("timeout", failed.error());
        assertEquals("test-model", failed.model());
        assertEquals(0, rig.count("SELECT count(*) FROM ops_ai_actions"));

        src.llmDelayMs = 0;
        src.llmContent = ADD;
        rig.ticks(1, MINUTE);
        assertEquals("PROPOSED", lastDecision().outcome());
    }

    @Test
    void 목록_밖이거나_쓸_수_없는_조치는_버리고_사유를_남긴다() {
        src.cpu = 0.82;

        src.llmContent = AiRig.out("DB 복제가 늦습니다.", "스탠바이를 승격해야 합니다.", "DB_PROMOTE", "db-standby", "",
                "0.95");
        rig.ticks(1, MINUTE);
        AiStore.Decision promote = lastDecision();
        assertEquals("NO_ACTION", promote.outcome());
        assertTrue(promote.dropped().contains("UNKNOWN_KIND"), promote.dropped());
        assertTrue(promote.dropped().contains("DB_PROMOTE"));

        // 근거가 같으면 다시 묻지 않으므로(설계 7-1) 근거를 바꿔 가며 묻는다.
        src.cpu = 0.85;
        src.llmContent = AiRig.out("백업이 필요합니다.", "지금 백업합니다.", "BACKUP_RUN_NOW", "", "", "0.9");
        rig.ticks(1, MINUTE);
        assertTrue(lastDecision().dropped().contains("UNAVAILABLE_KIND"), "Databasus 가 없으면 백업 조치는 없다");

        src.cpu = 0.88;
        src.llmContent = AiRig.out("부하가 높습니다.", "증설 임계를 올립니다.", "THRESHOLD_RAISE", "read.scale_out_cpu", "0.55",
                "0.9");
        rig.ticks(1, MINUTE);
        assertTrue(lastDecision().dropped().contains("DIRECTION_MISMATCH"), "올린다면서 낮은 값을 내면 버린다");

        assertEquals(0, rig.count("SELECT count(*) FROM ops_ai_actions"));
    }

    @Test
    void 이유와_반대_방향의_조치는_방향_모순으로_막고_감사에_남긴다() {
        rig.nodes(3);
        rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = CONTRADICT;

        rig.ticks(1, MINUTE);
        // 근거가 같으면 다시 묻지 않으므로(설계 7-1) 근거를 바꿔 한 번 더 묻는다.
        src.cpu = 0.85;
        rig.ticks(1, MINUTE);

        List<AiStore.Action> rows = rig.actions();
        assertEquals(2, rows.size(), "막힌 제안은 열린 요청이 되지 않아 판단마다 거부로 남는다");
        for (AiStore.Action row : rows) {
            assertEquals("DENIED", row.status());
            assertEquals("D2", row.holdRule());
            assertTrue(row.holdReason().startsWith("방향 모순"), row.holdReason());
        }
        assertEquals("PROPOSED", lastDecision().outcome(), "형식은 맞았고 정책이 막았다");
        assertEquals(3, rig.desired());
        assertEquals(2, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.action.deny' "
                + "AND detail->>'rule' = 'D2' AND detail->>'reason_code' = 'direction_conflict'"));
        assertEquals(2, events("DENIED"));
        assertFalse(rig.noticed(AiNotifier.Kind.AUTO_EXECUTED));
    }

    @Test
    void 자동_실행은_같은_판단이_연속_두_번이어야_한다() {
        AiControl.Mode mode = rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = ADD;

        rig.ticks(1, MINUTE);
        AiStore.Action first = rig.latest();
        assertEquals("PROPOSED", first.status());
        assertEquals("A4", first.holdRule());
        assertEquals(2, rig.desired());

        src.llmContent = NONE;
        rig.ticks(1, MINUTE);
        assertEquals("WITHDRAWN", reload(first).status(), "사이에 다른 판단이 끼면 철회된다");

        src.llmContent = ADD;
        // 근거가 같으면 다시 묻지 않으므로(설계 7-1) 근거를 바꿔 새 판단을 받는다.
        src.cpu = 0.9;
        rig.ticks(1, MINUTE);
        AiStore.Action second = rig.latest();
        assertNotEquals(first.id(), second.id());
        assertEquals(1, second.consecutive());
        assertEquals("A4", second.holdRule());

        rig.ticks(1, MINUTE);
        second = reload(second);
        assertEquals("AUTO_EXECUTED", second.status());
        assertEquals(2, second.consecutive());
        assertEquals(mode.activation(), second.activation());
        assertEquals(AiControl.AI_OPERATOR, second.executedBy());
        assertEquals(3, rig.desired());
        assertTrue(rig.noticed(AiNotifier.Kind.AUTO_EXECUTED));
        assertEquals(1, events("AUTO_ACTED"));
        assertEquals(1, audits("ops.ai.action.execute", "OK"));
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'capacity.scale_out' "
                + "AND operator_id = 'ai-ops'"));
    }

    @Test
    void 사람이_거절하거나_나중에로_둔_조치는_자동으로_실행하지_않는다() {
        rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(1, MINUTE);
        AiStore.Action first = rig.latest();

        AiStore.Action rejected = rig.executor.reject(first.id(), AiRig.principal(AiRig.VIEWER, AdminRole.VIEWER),
                "지금은 늘리지 않습니다", "req-reject");
        assertEquals("REJECTED", rejected.status());
        assertEquals(AiRig.VIEWER, rejected.decidedBy());

        src.cpu = 0.86;
        rig.ticks(2, MINUTE);
        AiStore.Action again = rig.latest();
        assertNotEquals(first.id(), again.id());
        assertEquals(2, again.consecutive());
        assertEquals("A9", again.holdRule(), "거절한 같은 조치는 한동안 자동 실행하지 않는다");
        assertEquals(2, rig.desired());

        AiStore.Action deferred = rig.executor.defer(again.id(), AiRig.principal(AiRig.VIEWER, AdminRole.VIEWER), 5,
                "req-defer");
        assertEquals("DEFERRED", deferred.status());
        assertEquals(rig.clock.instant().plus(Duration.ofMinutes(5)), deferred.deferredUntil());
        // 나중에 시각이 지나도 만료 전이면 요청은 DEFERRED 로 남고 자동 실행에서 빠진다.
        rig.advance(Duration.ofMinutes(6));
        rig.ticks(2, MINUTE);
        assertEquals("DEFERRED", reload(again).status());
        assertEquals(2, rig.desired(), "나중에로 둔 요청은 사람의 승인으로만 실행한다");
        assertEquals(1, audits("ops.ai.action.reject", "OK"));
        assertEquals(1, audits("ops.ai.action.defer", "OK"));
        assertEquals(1, events("REJECTED"));
        assertEquals(1, events("DEFERRED"));
    }

    @Test
    void 응답이_늦어_판단에_쓴_근거가_60초를_넘었으면_자동_실행하지_않는다() {
        rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(1, MINUTE);
        assertEquals("A4", rig.latest().holdRule());
        // 모델 적재 등으로 응답이 61초 늦어진 판단이다. 근거는 호출 전에 모았고, 두 판단의 간격은 주기의 두 배 안이다.
        Function<StubSource.Request, StubSource.Response> original = src.llm.handler;
        src.llm.handler = r -> {
            if (r.path().equals("/v1/chat/completions")) {
                rig.clock.advance(Duration.ofSeconds(61));
            }
            return original.apply(r);
        };

        rig.advance(Duration.ofSeconds(1));
        rig.loop.tick();

        AiStore.Action a = rig.latest();
        assertEquals(2, a.consecutive(), "연속 두 번은 채웠다");
        assertEquals("PROPOSED", a.status());
        assertEquals("A5", a.holdRule());
        assertEquals(2, rig.desired());
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.action.execute' "
                + "AND result = 'DENIED' AND detail->>'rule' = 'A5'"));
    }

    @Test
    void 확신도가_기준보다_낮으면_자동_실행하지_않는다() {
        rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = AiRig.out("조회 계층 평균 CPU가 높습니다.", "조회 노드를 1대 늘립니다.", "READ_NODE_ADD_ONE", "app", "",
                "0.75");

        rig.ticks(2, MINUTE);

        AiStore.Action a = rig.latest();
        assertEquals("PROPOSED", a.status());
        assertEquals("A3", a.holdRule());
        assertEquals(2, rig.desired());
    }

    @Test
    void 사람의_같은_종류_조치도_쿨다운을_시작시킨다() {
        rig.capacity.change(1, AiRig.SUPER, "capacity.scale_out", "사람의 증설", "req-human");
        rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = ADD;

        rig.ticks(2, MINUTE);

        AiStore.Action a = rig.latest();
        assertEquals("PROPOSED", a.status());
        assertEquals("A8", a.holdRule());
        assertEquals(3, rig.desired());
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.action.execute' "
                + "AND result = 'DENIED' AND detail->>'rule' = 'A8'"));

        // 쿨다운(10분)이 지나면 같은 요청이 다시 연속 두 번을 채워 실행된다.
        rig.advance(Duration.ofMinutes(10));
        rig.ticks(2, MINUTE);
        a = reload(a);
        assertEquals("AUTO_EXECUTED", a.status());
        assertEquals(4, rig.desired());
    }

    @Test
    void 활성당_자동_조치_상한에_닿으면_수동으로_돌리고_다시_켜면_새로_센다() {
        AiControl.LimitApplied cap = rig.control.changeLimit("auto_cap", 1, "시험 상한", AiRig.SUPER,
                rig.ticket(AiControl.LIMITS_ACTION, "auto_cap:1"), "req-cap");
        assertTrue(cap.immediate());
        AiControl.Mode first = rig.auto(4);
        assertEquals(1, first.activationLimits().autoCap());
        src.cpu = 0.82;
        src.llmContent = ADD;

        rig.ticks(2, MINUTE);
        assertEquals(3, rig.desired());
        assertEquals(1, rig.control.autoUsed(first.activation()));

        // 관찰 창(5분)이 끝난 뒤의 다음 조치는 상한(A7)에 걸린다.
        rig.advance(Duration.ofMinutes(6));
        rig.ticks(2, MINUTE);
        AiStore.Action held = rig.latest();
        assertEquals("PROPOSED", held.status());
        assertEquals("A7", held.holdRule());
        assertFalse(rig.control.mode().auto(), "상한에 닿으면 수동으로 돌린다");
        assertTrue(rig.noticed(AiNotifier.Kind.CAP_REACHED));
        assertEquals(3, rig.desired());

        AiControl.Mode second = rig.auto(4);
        assertEquals(first.activation() + 1, second.activation(), "수동에서 다시 켜면 활성 번호가 오른다");
        assertEquals(0, rig.control.autoUsed(second.activation()));
        rig.advance(Duration.ofMinutes(2));
        rig.ticks(2, MINUTE);
        assertEquals("AUTO_EXECUTED", reload(held).status());
        assertEquals(4, rig.desired());
        assertEquals(1, rig.control.autoUsed(second.activation()));
    }

    @Test
    void 다시_켜도_관찰_창과_쿨다운은_이어진다() {
        AiControl.Mode first = rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(2, MINUTE);
        assertEquals(3, rig.desired());

        rig.control.manual(AiRig.SUPER, "다시 켜기 시험", "req-manual");
        AiControl.Mode second = rig.auto(4);
        assertEquals(first.activation() + 1, second.activation());

        rig.ticks(2, MINUTE);
        assertEquals("A6", rig.latest().holdRule(), "앞선 조치의 관찰 창은 새 활성에서도 이어진다");

        rig.advance(Duration.ofMinutes(3));
        rig.ticks(2, MINUTE);
        assertEquals("A8", rig.latest().holdRule(), "쿨다운도 새 활성에서 이어진다");
        assertEquals(3, rig.desired());
    }

    @Test
    void 관찰_창에서_악화하면_축소를_되돌리고_수동으로_돌린다() {
        rig.nodes(3);
        rig.auto(4);
        src.cpu = 0.10;
        src.llmContent = REMOVE;
        rig.collector.collect();

        rig.ticks(2, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals("AUTO_EXECUTED", a.status());
        assertEquals(2, rig.desired());

        src.cpu = 0.70;
        rig.advance(MINUTE);
        rig.loop.observeTick();

        a = reload(a);
        assertEquals("AUTO_REVERTED", a.status());
        assertEquals("REVERTED", a.observeOutcome());
        assertEquals(AiControl.AI_OPERATOR, a.revertedBy());
        assertTrue(a.revertInfo().path("cause").asText().contains("증설 임계"));
        assertEquals(3, rig.desired());
        assertFalse(rig.control.mode().auto(), "악화로 되돌리면 수동으로 돌린다");
        assertTrue(rig.noticed(AiNotifier.Kind.AUTO_REVERTED));
        assertEquals(1, audits("ops.ai.action.auto_revert", "OK"));
        assertEquals(1, events("AUTO_REVERTED"));
    }

    @Test
    void 악화가_없으면_관찰_창이_끝날_때_유지로_닫는다() {
        rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(2, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals("AUTO_EXECUTED", a.status());

        src.cpu = 0.55;
        rig.advance(Duration.ofMinutes(2));
        rig.loop.observeTick();
        assertNull(reload(a).observeOutcome(), "창이 끝나기 전에는 닫지 않는다");

        rig.advance(Duration.ofMinutes(4));
        rig.loop.observeTick();
        assertEquals("KEPT", reload(a).observeOutcome());
        assertTrue(rig.control.mode().auto());
    }

    @Test
    void 자동_모드는_유효_시간이_지나면_수동으로_돌아간다() {
        AiControl.Mode on = rig.auto(1);
        assertTrue(on.auto());

        rig.advance(Duration.ofMinutes(59));
        rig.loop.observeTick();
        assertTrue(rig.control.mode().auto());

        rig.advance(Duration.ofMinutes(2));
        rig.loop.observeTick();
        assertFalse(rig.control.mode().auto());
        assertTrue(rig.notices.stream().anyMatch(n -> n.kind() == AiNotifier.Kind.MODE_CHANGED
                && "expired".equals(n.facts().get("cause"))));
        assertEquals(1, audits("ops.ai.mode.expire", "OK"));

        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(2, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals("PROPOSED", a.status());
        assertEquals(2, a.consecutive());
        assertEquals(2, rig.desired(), "만료 뒤에는 연속 제안도 실행하지 않는다");
    }

    @Test
    void AI_정지는_대기_요청을_만료시키고_판단과_승인과_자동_실행을_막으며_재개는_수동으로만_돌아간다() {
        rig.auto(4);
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(1, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals("PROPOSED", a.status());

        AiControl.Changed stopped = rig.control.stop(AiRig.VIEWER, "판단 이상 의심", "req-stop");
        assertTrue(stopped.changed());
        assertEquals(1, stopped.expiredActions());
        AiControl.Mode mode = rig.control.mode();
        assertTrue(mode.stopped());
        assertFalse(mode.auto(), "정지는 자동을 곧바로 푼다");
        assertEquals(AiRig.VIEWER, mode.stoppedBy());
        a = reload(a);
        assertEquals("EXPIRED", a.status());
        assertEquals("M9", a.holdRule());

        int calls = src.chatCalls();
        long decisions = rig.count("SELECT count(*) FROM ops_ai_decisions");
        rig.ticks(2, MINUTE);
        assertEquals(calls, src.chatCalls(), "정지 중에는 모델을 부르지 않는다");
        assertEquals(decisions, rig.count("SELECT count(*) FROM ops_ai_decisions"));
        assertEquals(2.0, rig.skips("stopped"));

        long requestId = a.id();
        ApiException approve = assertThrows(ApiException.class, () -> rig.executor.approve(requestId,
                AiRig.principal(AiRig.SUPER, AdminRole.SUPER), null, "req-approve"));
        assertEquals(409, approve.status());
        assertEquals("M9", approve.detail().get("rule"));

        AiExecutor.AutoResult auto = rig.executor.autoExecute(requestId,
                new AiPolicy.Decision("PROPOSED", rig.clock.instant(), rig.collector.collect()));
        assertEquals("M9", auto.denial().rule(), "정지 직전에 끝난 판단도 자동 실행하지 않는다");
        assertEquals(2, rig.desired());

        ApiException enable = assertThrows(ApiException.class, () -> rig.auto(4));
        assertEquals("M9", enable.detail().get("rule"));
        assertEquals(400, assertThrows(ApiException.class,
                () -> rig.control.resume(AiRig.SUPER, " ", "req")).status(), "재개에는 사유가 필요하다");

        AiControl.Changed resumed = rig.control.resume(AiRig.SUPER, "원인 확인", "req-resume");
        assertTrue(resumed.changed());
        assertFalse(resumed.mode().stopped());
        assertEquals("MANUAL", resumed.mode().mode(), "재개는 수동으로만 돌아간다");
        assertTrue(rig.noticed(AiNotifier.Kind.STOPPED));
        assertTrue(rig.noticed(AiNotifier.Kind.RESUMED));
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.stop' "
                + "AND operator_id = 'op-view'"));
        assertEquals(1, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.resume' "
                + "AND operator_id = 'op-super'"));
        assertEquals(1, events("STOPPED"));
        assertEquals(1, events("RESUMED"));

        rig.ticks(2, MINUTE);
        assertEquals(calls + 2, src.chatCalls(), "재개하면 판단을 다시 한다");
        AiStore.Action after = rig.latest();
        assertEquals("PROPOSED", after.status());
        assertEquals(2, after.consecutive());
        assertEquals(2, rig.desired(), "재개 뒤에는 자동 실행이 없다");
    }

    @Test
    void 정지해도_관찰_창은_이어지고_악화하면_되돌린다() {
        rig.nodes(3);
        rig.auto(4);
        src.cpu = 0.10;
        src.llmContent = REMOVE;
        rig.collector.collect();
        rig.ticks(2, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals("AUTO_EXECUTED", a.status());

        rig.control.stop(AiRig.VIEWER, "정지 시험", "req-stop");
        src.cpu = 0.70;
        rig.advance(MINUTE);
        rig.loop.observeTick();

        assertEquals("AUTO_REVERTED", reload(a).status(), "되돌림은 안전 방향이라 정지 중에도 한다");
        assertEquals(3, rig.desired());
        assertTrue(rig.control.mode().stopped());
    }

    @Test
    void 관찰_중에_사람이_먼저_되돌린_조치는_되돌림_불가로_적거나_통지하지_않는다() {
        rig.nodes(3);
        rig.auto(4);
        src.cpu = 0.10;
        src.llmContent = REMOVE;
        rig.collector.collect();
        rig.ticks(2, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals("AUTO_EXECUTED", a.status());
        assertEquals(2, rig.desired());
        long id = a.id();

        onNextNodeProbe(() -> rig.executor.revert(id, AiRig.principal(AiRig.SUPER, AdminRole.SUPER), null,
                "req-human"));
        src.cpu = 0.70;
        rig.advance(MINUTE);
        rig.loop.observeTick();

        a = reload(a);
        assertEquals("REVERTED", a.status());
        assertEquals(AiRig.SUPER, a.revertedBy());
        assertNull(a.observeOutcome(), "사람이 되돌린 조치에는 관찰 결과를 적지 않는다");
        assertEquals(3, rig.desired(), "되돌림은 한 번만 일어난다");
        assertFalse(rig.noticed(AiNotifier.Kind.OBSERVE_FAILED));
        assertEquals(0, audits("ops.ai.action.auto_revert", "DENIED"));
        assertTrue(rig.control.mode().auto(), "사람의 되돌리기는 수동 전환 사유가 아니다");
    }

    @Test
    void 관찰_중에_사람이_먼저_되돌렸으면_근거를_잃어도_관찰_불가로_적지_않는다() {
        rig.nodes(3);
        rig.auto(4);
        src.cpu = 0.10;
        src.llmContent = REMOVE;
        rig.collector.collect();
        rig.ticks(2, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals("AUTO_EXECUTED", a.status());
        long id = a.id();

        Function<StubSource.Request, StubSource.Response> original = src.node.handler;
        AtomicBoolean reverted = new AtomicBoolean();
        src.node.handler = r -> {
            if (!r.path().equals("/actuator/prometheus")) {
                return original.apply(r);
            }
            if (reverted.compareAndSet(false, true)) {
                rig.executor.revert(id, AiRig.principal(AiRig.SUPER, AdminRole.SUPER), null, "req-human");
            }
            return StubSource.Response.status(503, "\"down\"");
        };
        rig.advance(MINUTE);
        rig.loop.observeTick();

        a = reload(a);
        assertTrue(reverted.get(), "관찰 주기가 근거를 모으는 동안 사람이 되돌렸다");
        assertEquals("REVERTED", a.status());
        assertNull(a.observeOutcome());
        assertFalse(rig.noticed(AiNotifier.Kind.OBSERVE_FAILED));
        assertTrue(rig.control.mode().auto());
    }

    @Test
    void 축소_전제의_지속은_표본_사이가_판단_주기의_두_배를_넘으면_처음부터_다시_잰다() {
        rig.nodes(3);
        src.cpu = 0.10;
        Instant start = rig.clock.instant();
        assertEquals(start, rig.collector.collect().read().lowSince());
        rig.advance(MINUTE);
        assertEquals(start, rig.collector.collect().read().lowSince(), "주기 안의 표본은 지속을 잇는다");

        rig.advance(Duration.ofMinutes(5));
        Instant restarted = rig.clock.instant();
        assertEquals(restarted, rig.collector.collect().read().lowSince(), "5분 공백 사이에 임계를 넘었는지 모른다");
        rig.advance(MINUTE);
        assertEquals(restarted, rig.collector.collect().read().lowSince());
        assertEquals(restarted, rig.collector.peek().read().lowSince(), "상담의 판독은 기록을 바꾸지 않는다");
    }

    @Test
    void 축소_후보인_동안은_평온_주기로_쉬지_않고_지속을_채운_주기에_한_번_더_묻는다() throws Exception {
        rig.nodes(3);
        src.cpu = 0.10;
        src.llmContent = NONE;
        rig.ticks(1, MINUTE);
        assertEquals(1, src.chatCalls());
        assertFalse(lastEvidence().path("catalog").toString().contains("READ_NODE_REMOVE_ONE"),
                "지속을 채우기 전에는 축소를 보이지 않는다");
        int probes = src.node.requests("/actuator/prometheus").size();

        rig.ticks(3, MINUTE);

        assertEquals(2, src.chatCalls(), "지속을 채우면 카탈로그에 축소가 나타나 근거가 바뀐다");
        assertTrue(lastEvidence().path("catalog").toString().contains("READ_NODE_REMOVE_ONE"));
        assertEquals(2.0, rig.skips("unchanged"), "그 뒤 근거가 같으면 모델은 다시 부르지 않는다");
        assertEquals(0.0, rig.skips("calm_wait"), "축소 후보인 동안은 평온 주기로 쉬지 않는다");
        assertTrue(src.node.requests("/actuator/prometheus").size() > probes, "주기마다 노드를 잰다");
    }

    private JsonNode lastEvidence() throws Exception {
        List<StubSource.Request> sent = src.llm.requests("/v1/chat/completions");
        JsonNode body = rig.mapper.readTree(sent.get(sent.size() - 1).body());
        return rig.mapper.readTree(body.path("messages").get(1).path("content").asText());
    }

    @Test
    void 최대_대수에서_울리는_CPU_경보를_창_없이_보류하려_하면_계획_작업_창_없음으로_거부한다() {
        rig.nodes(4);
        src.cpu = 0.88;
        String fp = "a1a1a1a1a1a1a1a1";
        src.alerts = List.of(AiRig.alert(fp, "ReadTierCpuHigh", "warning", "app"));
        rig.auto(4);
        src.llmContent = AiRig.out("조회 노드가 최대 4대인데 평균 CPU가 88%입니다.", "더 늘릴 수 없어 경보를 30분 보류합니다.",
                "ALERT_SILENCE", fp, "30", "0.9");

        rig.ticks(2, MINUTE);

        List<AiStore.Action> rows = rig.actions();
        assertEquals(2, rows.size());
        for (AiStore.Action row : rows) {
            assertEquals("DENIED", row.status());
            assertEquals("S6", row.holdRule());
            assertTrue(row.holdReason().startsWith("계획 작업 창 없음"), row.holdReason());
        }
        assertEquals(2, rig.count("SELECT count(*) FROM admin_audit_log WHERE action = 'ops.ai.action.deny' "
                + "AND detail->>'reason_code' = 'no_planned_window'"));
        assertTrue(src.silenceBodies.isEmpty(), "Alertmanager 에 사일런스를 만들지 않는다");
    }

    @Test
    void 복제_지연_경보는_계획_작업_창이_있어도_보류하지_않는다() {
        String fp = "b2b2b2b2b2b2b2b2";
        src.alerts = List.of(AiRig.alert(fp, "PostgresReplicationLagHigh", "warning", "database"));
        rig.windows.create(AiRig.BOT, null, rig.clock.instant().plus(Duration.ofHours(2)),
                "PostgresReplicationLagHigh", Map.of(), "DB 점검", "req-window");
        src.llmContent = AiRig.out("복제 지연이 45초입니다.", "점검 중이라 30분 보류합니다.", "ALERT_SILENCE", fp, "30", "0.9");

        rig.ticks(1, MINUTE);

        AiStore.Action a = rig.latest();
        assertEquals("DENIED", a.status());
        assertEquals("S7", a.holdRule());
        assertTrue(src.silenceBodies.isEmpty());
    }

    @Test
    void 주석과_라벨에_넣은_명령문은_모델에_닿지_않고_창이_있어도_그_경보는_보류하지_않는다() throws Exception {
        String fp = "f6f6f6f6f6f6f6f6";
        src.alerts = List.of(AiRig.injectedAlert(fp, "EdgeProbeSlow"));
        rig.windows.create(AiRig.BOT, null, rig.clock.instant().plus(Duration.ofHours(2)), "EdgeProbeSlow", Map.of(),
                "외부 프로브 교체", "req-window");
        rig.auto(4);
        src.llmContent = AiRig.out("외부 프로브 지연 경보가 계획 작업 중에 울립니다.", "작업 중이라 30분 보류합니다.",
                "ALERT_SILENCE", fp, "30", "0.9");

        rig.ticks(2, MINUTE);

        List<StubSource.Request> sent = src.llm.requests("/v1/chat/completions");
        assertEquals(2, sent.size(), "판단 대상 경보가 있어 주기마다 모델을 불렀다");
        for (StubSource.Request r : sent) {
            StringBuilder prompt = new StringBuilder();
            rig.mapper.readTree(r.body()).path("messages").forEach(m -> prompt.append(m.path("content").asText()));
            for (String phrase : AiRig.INJECTED) {
                assertFalse(prompt.toString().contains(phrase), "모델에 닿으면 안 된다: " + phrase);
            }
        }
        String stored = rig.text("SELECT string_agg(evidence::text, ' ') FROM ops_ai_decisions");
        AiRig.INJECTED.forEach(phrase -> assertFalse(stored.contains(phrase), "판단 기록의 근거에도 없다: " + phrase));

        JsonNode alert = lastEvidence().path("alerts").path(0);
        assertEquals(fp, alert.path("fingerprint").asText());
        assertEquals("EdgeProbeSlow", alert.path("alertname").asText());
        assertEquals("edge", alert.path("component").asText());
        assertFalse(alert.has("node") || alert.has("role") || alert.has("summary"));
        assertEquals("[\"node\",\"role\"]", alert.path("rejected").toString());
        assertEquals("read.scale_out_cpu", alert.path("threshold").path("key").asText());
        assertTrue(alert.path("planned_window").asBoolean(), "창은 경보명으로 겹친다");
        assertFalse(lastEvidence().path("catalog").toString().contains(fp), "보류 대상으로 보이지 않는다");

        List<AiStore.Action> rows = rig.actions();
        assertEquals(2, rows.size());
        for (AiStore.Action row : rows) {
            assertEquals("DENIED", row.status());
            assertEquals("S8", row.holdRule());
        }
        assertTrue(src.silenceBodies.isEmpty(), "Alertmanager 에 사일런스를 만들지 않는다");
    }

    @Test
    void 계획_작업_창_안의_봇_지연_경보는_자동으로_보류하고_원천에_요청_번호를_남긴다() throws Exception {
        String fp = "c3c3c3c3c3c3c3c3";
        src.alerts = List.of(AiRig.alert(fp, "BotOrderLatencyHigh", "warning", "bot"));
        rig.windows.create(AiRig.BOT, null, rig.clock.instant().plus(Duration.ofHours(2)), null,
                Map.of("component", "bot"), "봇 배포", "req-window");
        rig.auto(4);
        src.llmContent = AiRig.out("봇 주문 지연 경보가 계획 작업 중에 울립니다.", "배포 작업 중이라 30분 보류합니다.",
                "ALERT_SILENCE", fp, "30", "0.9");

        rig.ticks(2, MINUTE);

        AiStore.Action a = rig.latest();
        assertEquals("AUTO_EXECUTED", a.status());
        assertEquals(1, src.silenceBodies.size());
        JsonNode body = rig.mapper.readTree(src.silenceBodies.get(0));
        assertEquals(AiControl.AI_OPERATOR, body.path("createdBy").asText());
        assertTrue(body.path("comment").asText().endsWith(AiExecutor.COMMENT_TAG + a.id()));
        assertEquals(a.result().path("silence_id").asText(), src.silences.keySet().iterator().next());
        assertEquals("AI", rig.text("SELECT silence_actor FROM ops_incidents WHERE fingerprint = '" + fp + "'"));
        assertEquals(1, rig.count("SELECT count(*) FROM ops_incident_events WHERE kind = 'SILENCED' "
                + "AND actor_type = 'AI'"));
    }

    @Test
    void AI는_같은_경보를_잇달아_보류하지_않고_사람이_확인한_뒤에는_다시_보류할_수_있다() {
        String fp = "d4d4d4d4d4d4d4d4";
        src.alerts = List.of(AiRig.alert(fp, "BotOrderLatencyHigh", "warning", "bot"));
        rig.windows.create(AiRig.BOT, null, rig.clock.instant().plus(Duration.ofHours(2)), null,
                Map.of("component", "bot"), "봇 배포", "req-window");
        rig.auto(4);
        src.llmContent = AiRig.out("봇 주문 지연 경보가 계획 작업 중에 울립니다.", "배포 작업 중이라 30분 보류합니다.",
                "ALERT_SILENCE", fp, "30", "0.9");
        rig.ticks(2, MINUTE);
        AiStore.Action first = rig.latest();
        assertEquals("AUTO_EXECUTED", first.status());
        assertEquals(1, src.silenceBodies.size());

        // 첫 보류가 끝난 뒤에도 경보가 이어져 다시 판단 대상이 된다.
        rig.advance(Duration.ofMinutes(31));
        rig.ticks(2, MINUTE);
        AiStore.Action again = rig.latest();
        assertNotEquals(first.id(), again.id());
        assertEquals("PROPOSED", again.status());
        assertEquals(2, again.consecutive());
        assertEquals("S3", again.holdRule());
        assertEquals(1, src.silenceBodies.size(), "직전 보류가 AI 보류면 잇달아 보류하지 않는다");

        rig.alertActions.acknowledge(fp, AiRig.principal(AiRig.BOT, AdminRole.BOT_OPS), "req-ack");
        rig.ticks(1, MINUTE);

        assertEquals("AUTO_EXECUTED", reload(again).status(), "사람이 확인한 뒤에는 다시 보류할 수 있다");
        assertEquals(2, src.silenceBodies.size());
    }

    @Test
    void 모델을_적재하는_동안은_판단을_건너뛰고_다음_주기에_판단한다() throws IOException {
        rig.close();
        rig = new AiRig(POSTGRES, src, MspProperties.Llm.Provider.OLLAMA_NATIVE, p -> { });
        src.modelLoaded = false;
        src.cpu = 0.82;
        src.llmContent = ADD;

        rig.ticks(1, MINUTE);
        assertEquals(0, rig.count("SELECT count(*) FROM ops_ai_decisions"), "적재 중에는 판단 기록도 없다");
        assertEquals(1.0, rig.skips("model_loading"));
        assertEquals(1, src.llm.requests("/api/generate").size(), "적재를 요청해 둔다");
        assertEquals(0, src.chatCalls());

        rig.ticks(1, MINUTE);
        assertEquals("PROPOSED", lastDecision().outcome());
        assertEquals(1, src.llm.requests("/api/chat").size());
    }

    /** 실행 중에 관리 JVM 이 멈춰 EXECUTING 으로 남은 보류 조치를 만든다(설계 7-7 끊긴 실행). */
    private long stuckSilence(String fingerprint, Long activation) {
        String key = "SILENCE:" + fingerprint + ":30";
        long decision = rig.store.insertDecision(new AiStore.NewDecision(rig.clock.instant(), "AUTO", "PROPOSED", null,
                "test-model", "{}", null, "{}", "상황입니다.", "이유입니다.", new BigDecimal("0.9"), key, "[]", 5, null));
        return rig.jdbc.sql("""
                        INSERT INTO ops_ai_actions (first_decision_id, last_decision_id, consecutive, kind, identity_key,
                                                    params, target, confidence, status, executed_by, executed_at,
                                                    activation, created_at, expires_at)
                        VALUES (?, ?, 2, 'SILENCE', ?, ?::jsonb, ?, 0.9, 'EXECUTING', 'ai-ops', ?, ?, ?, ?)
                        RETURNING id
                        """)
                .params(decision, decision, key, "{\"fingerprint\":\"" + fingerprint + "\",\"minutes\":30}",
                        fingerprint, AiControl.at(rig.clock.instant()), activation, AiControl.at(rig.clock.instant()),
                        AiControl.at(rig.clock.instant().plus(Duration.ofMinutes(15))))
                .query(Long.class).single();
    }

    @Test
    void 기동_때_남은_보류_실행은_원천의_사일런스를_찾아_결과를_채우고_수동으로_돌린다() {
        AiControl.Mode mode = rig.auto(4);
        String fp = "e5e5e5e5e5e5e5e5";
        src.alerts = List.of(AiRig.alert(fp, "BotOrderLatencyHigh", "warning", "bot"));
        rig.advance(Duration.ofSeconds(1));
        long id = stuckSilence(fp, mode.activation());
        String silenceId = "8e0a2c56-0000-4000-8000-00000000aa01";
        src.addSilence(silenceId, AiControl.AI_OPERATOR, "AI 조치 보류 " + AiExecutor.COMMENT_TAG + id);

        rig.executor.reconcileAtStartup();

        AiStore.Action a = rig.store.action(id).orElseThrow();
        assertEquals("AUTO_EXECUTED", a.status());
        assertEquals(silenceId, a.result().path("silence_id").asText());
        assertTrue(a.result().path("reconciled").asBoolean());
        assertEquals(a.executedAt().plus(AiPolicy.OBSERVE), a.observeUntil(), "관찰 창은 실행 시각부터 잰다");
        assertFalse(rig.control.mode().auto(), "기동 때 끝나지 않은 실행이 있으면 수동으로 돌린다");
        assertEquals("AI", rig.text("SELECT silence_actor FROM ops_incidents WHERE fingerprint = '" + fp + "'"));
        assertEquals(silenceId, rig.text("SELECT silence_id FROM ops_incidents WHERE fingerprint = '" + fp + "'"));
    }

    @Test
    void 원천을_읽지_못하면_결과_모름으로_두고_자동_켜기를_막으며_다시_읽어_닫는다() {
        long id = stuckSilence("f6f6f6f6f6f6f6f6", null);
        src.silencesDown = true;

        rig.executor.reconcileAtStartup();

        assertEquals("UNKNOWN", rig.store.action(id).orElseThrow().status(), "모르는 결과를 실패로 적지 않는다");
        assertTrue(rig.noticed(AiNotifier.Kind.EXECUTION_UNKNOWN));
        ApiException blocked = assertThrows(ApiException.class, () -> rig.auto(4));
        assertEquals("M1", blocked.detail().get("rule"));

        src.silencesDown = false;
        rig.advance(Duration.ofMinutes(3));
        rig.loop.observeTick();

        AiStore.Action closed = rig.store.action(id).orElseThrow();
        assertEquals("FAILED", closed.status(), "성공한 조회에 없으면 호출이 닿지 않은 것이다");
        assertEquals("not_applied", closed.result().path("error").asText());
        assertTrue(rig.auto(4).auto(), "결과를 모르는 요청이 없으면 다시 켤 수 있다");
    }
}

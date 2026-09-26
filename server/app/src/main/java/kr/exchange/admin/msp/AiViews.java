package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 경로의 응답 모양(API 설계 §6-F). 한 판의 ai 칸과 세부 조회가 같은 모양을 쓴다. 모델의 문장(situation, reason)은
 * 평문으로만 싣는다.
 */
final class AiViews {

    private AiViews() {
    }

    static Map<String, Object> llmSource(LlmClient llm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "llm");
        if (llm == null) {
            m.put("state", "NOT_CONNECTED");
            return m;
        }
        m.put("state", llm.lastError() == null ? "READY" : "UNAVAILABLE");
        if (llm.lastError() != null) {
            m.put("reason", llm.lastError());
        }
        m.put("provider", provider(llm));
        m.put("model", llm.model());
        m.put("transport", llm.transport().name());
        return m;
    }

    static String provider(LlmClient llm) {
        return llm.provider().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** 한 판의 ai 칸. LLM 설정이 없어도 모드와 정지를 싣는다. 즉시 끄기와 정지는 모델 연결과 무관하다. */
    static Map<String, Object> section(AiControl control, AiStore store, LlmClient llm) {
        AiControl.Mode mode = control.mode();
        Map<String, Object> m = new LinkedHashMap<>();
        if (llm == null) {
            m.put("state", "NOT_CONNECTED");
            m.put("source", "llm");
        } else if (llm.lastError() != null) {
            m.put("state", "UNAVAILABLE");
            m.put("reason", llm.lastError());
        } else {
            m.put("state", "READY");
        }
        m.put("mode", mode.mode());
        m.put("stopped", mode.stopped());
        m.put("stop", stop(mode));
        m.put("auto", mode.auto() ? autoSummary(control, mode) : null);
        m.put("open_actions", store.openCount());
        m.put("last_decision", store.lastDecision().map(d -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", d.id());
            x.put("decided_at", ApiFormats.time(d.decidedAt()));
            x.put("outcome", d.outcome());
            return x;
        }).orElse(null));
        return m;
    }

    /** GET /ai/mode. */
    static Map<String, Object> mode(AiControl control, AiStore store, LlmClient llm, Instant now) {
        AiControl.Mode mode = control.mode();
        AiLimits limits = control.effectiveLimits(mode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("mode", mode.mode());
        out.put("stopped", mode.stopped());
        out.put("stop", stop(mode));
        if (mode.auto()) {
            Map<String, Object> auto = new LinkedHashMap<>();
            auto.put("since", ApiFormats.time(mode.since()));
            auto.put("valid_until", ApiFormats.time(mode.validUntil()));
            auto.put("enabled_by", mode.enabledBy());
            auto.put("reason", mode.reason());
            auto.put("approval_id", mode.approvalId());
            out.put("auto", auto);
            out.put("activation", activation(control, mode, limits));
        } else {
            out.put("auto", null);
            out.put("activation", null);
        }
        Map<String, Object> cooldowns = new LinkedHashMap<>();
        for (AiAction.Kind kind : List.of(AiAction.Kind.READ_NODES, AiAction.Kind.THRESHOLD, AiAction.Kind.BOT_LOAD)) {
            Instant last = store.lastSameKind(kind);
            Instant until = last == null ? null : last.plus(AiPolicy.cooldown(kind, limits));
            cooldowns.put(kind.name(), until == null || !until.isAfter(now) ? null : ApiFormats.time(until));
        }
        out.put("cooldowns", cooldowns);
        Instant observe = store.latestObserveUntil();
        out.put("observe_until", observe == null || !observe.isAfter(now) ? null : ApiFormats.time(observe));
        Map<String, Object> source = llmSource(llm);
        source.remove("name");
        out.put("llm", source);
        return out;
    }

    static Map<String, Object> limits(AiControl control) {
        AiControl.Mode mode = control.mode();
        AiLimits effective = control.effectiveLimits(mode);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiControl.LimitItem item : control.limitItems()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", item.key().id());
            m.put("value", item.value());
            m.put("effective", effective.get(item.key()));
            m.put("pending", effective.get(item.key()) != item.value());
            m.put("min", item.key().min());
            m.put("max", item.key().max());
            m.put("tighter", item.key().tighterUp() ? "UP" : "DOWN");
            m.put("unit", item.key().unit());
            m.put("version", item.version());
            m.put("updated_by", item.updatedBy());
            m.put("updated_at", item.updatedAt() == null ? null : ApiFormats.time(item.updatedAt()));
            m.put("reason", item.reason());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("items", items);
        out.put("activation", mode.auto() ? activation(control, mode, effective) : null);
        return out;
    }

    private static Map<String, Object> activation(AiControl control, AiControl.Mode mode, AiLimits limits) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("seq", mode.activation());
        a.put("started_at", ApiFormats.time(mode.activationAt()));
        a.put("auto_used", control.autoUsed(mode.activation()));
        a.put("auto_cap", limits.autoCap());
        a.put("threshold_used", control.thresholdUsed(mode.activation()));
        return a;
    }

    private static Map<String, Object> autoSummary(AiControl control, AiControl.Mode mode) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("since", ApiFormats.time(mode.since()));
        a.put("valid_until", ApiFormats.time(mode.validUntil()));
        a.put("enabled_by", mode.enabledBy());
        a.put("activation", mode.activation());
        a.put("auto_used", control.autoUsed(mode.activation()));
        a.put("auto_cap", control.effectiveLimits(mode).autoCap());
        return a;
    }

    private static Map<String, Object> stop(AiControl.Mode mode) {
        if (!mode.stopped()) {
            return null;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("by", mode.stoppedBy());
        s.put("at", ApiFormats.time(mode.stoppedAt()));
        s.put("reason", mode.stopReason());
        return s;
    }

    static Map<String, Object> action(AiStore.Action a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("kind", a.action().kind().name());
        m.put("verb", a.action().verb().name());
        m.put("target", a.action().target());
        m.put("identity_key", a.identityKey());
        m.put("params", a.action().params());
        m.put("confidence", decimal(a.confidence()));
        m.put("reason", a.reason());
        m.put("status", a.status());
        m.put("hold_rule", a.holdRule());
        m.put("hold_reason", a.holdReason());
        m.put("consecutive", a.consecutive());
        m.put("auto", a.activation() != null);
        m.put("deferred_until", time(a.deferredUntil()));
        m.put("decided_by", a.decidedBy());
        m.put("decided_at", time(a.decidedAt()));
        m.put("approval_id", a.approvalId());
        m.put("executed_by", a.executedBy());
        m.put("executed_at", time(a.executedAt()));
        m.put("result", a.result());
        m.put("observe_until", time(a.observeUntil()));
        m.put("observe_outcome", a.observeOutcome());
        m.put("reverted_by", a.revertedBy());
        m.put("reverted_at", time(a.revertedAt()));
        m.put("created_at", time(a.createdAt()));
        m.put("expires_at", time(a.expiresAt()));
        m.put("first_decision_id", a.firstDecisionId());
        m.put("last_decision_id", a.lastDecisionId());
        m.put("revertible", AiExecutor.revertible(a));
        return m;
    }

    static Map<String, Object> decisionSummary(AiStore.Decision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("decided_at", ApiFormats.time(d.decidedAt()));
        m.put("mode", d.mode());
        m.put("outcome", d.outcome());
        m.put("error", d.error());
        m.put("model", d.model());
        m.put("confidence", decimal(d.confidence()));
        m.put("latency_ms", d.latencyMs());
        return m;
    }

    static Map<String, Object> decision(AiStore.Decision d, List<AiStore.Action> actions, ObjectMapper mapper) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", ApiFormats.SCHEMA_VERSION);
        m.put("id", d.id());
        m.put("decided_at", ApiFormats.time(d.decidedAt()));
        m.put("mode", d.mode());
        m.put("outcome", d.outcome());
        m.put("error", d.error());
        m.put("model", d.model());
        m.put("prompt_version", d.promptVersion());
        m.put("evidence", tree(mapper, d.evidence()));
        m.put("evidence_sha256", d.evidenceSha256());
        m.put("oldest_sample_at", time(d.oldestSampleAt()));
        m.put("output_raw", d.outputRaw());
        m.put("output_sha256", d.outputSha256());
        m.put("output_truncated", d.outputTruncated());
        m.put("situation", d.situation());
        m.put("reason", d.reason());
        m.put("confidence", decimal(d.confidence()));
        m.put("latency_ms", d.latencyMs());
        m.put("incident_id", d.incidentId());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiStore.Action a : actions) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", a.id());
            x.put("kind", a.action().kind().name());
            x.put("verb", a.action().verb().name());
            x.put("params", a.action().params());
            x.put("status", a.status());
            x.put("consecutive", a.consecutive());
            x.put("hold_rule", a.holdRule());
            x.put("hold_reason", a.holdReason());
            rows.add(x);
        }
        m.put("actions", rows);
        m.put("dropped", tree(mapper, d.dropped()));
        return m;
    }

    static Map<String, Object> window(MaintenanceWindows.Window w, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("starts_at", ApiFormats.time(w.startsAt()));
        m.put("ends_at", ApiFormats.time(w.endsAt()));
        m.put("alertname", w.alertname());
        m.put("labels", w.labels());
        m.put("reason", w.reason());
        m.put("created_by", w.createdBy());
        m.put("ended_at", time(w.endedAt()));
        m.put("ended_by", w.endedBy());
        m.put("active", w.activeAt(now));
        return m;
    }

    private static Object tree(ObjectMapper mapper, String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장한 JSON 해석 실패", e);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : ApiFormats.amount(value);
    }

    private static String time(Instant instant) {
        return instant == null ? null : ApiFormats.time(instant);
    }
}

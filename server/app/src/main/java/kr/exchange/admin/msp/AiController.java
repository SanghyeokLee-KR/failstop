package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.admin.AdminPrincipal;
import kr.exchange.admin.AdminRequestContext;
import kr.exchange.admin.AdminRole;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.CursorPages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 관제센터 AI 경로(API 설계 §6-F). 모드, AI 즉시 정지와 재개, AI 한도, 계획 작업 창, 판단 기록, 조치 요청, 상담과
 * AI 서버 상태다.
 * 권한을 줄이는 조작(즉시 끄기, 정지, 창 조기 종료, 거절, 나중에)은 VIEWER 이상이고, operator_id 의 권위는 바디가
 * 아니라 인증 주체다(§3-6).
 */
@RestController
@RequestMapping("/admin/v1/msp")
@ConditionalOnProperty(name = {"exchange.admin.enabled", "exchange.msp.enabled"}, havingValue = "true")
public class AiController {

    private static final int MAX_PAGE = 200;

    public record AutoBody(String operator_id, String approval_token, Integer hours, String reason) {
    }

    public record ReasonBody(String operator_id, String reason) {
    }

    public record LimitBody(String operator_id, String approval_token, Integer value, String reason) {
    }

    public record WindowBody(String operator_id, String starts_at, String ends_at, String alertname,
                             Map<String, String> labels, String reason) {
    }

    public record ApproveBody(String operator_id, String approval_token) {
    }

    public record DeferBody(String operator_id, Integer minutes) {
    }

    public record ChatBody(String operator_id, String question, List<AiChat.Message> history) {
    }

    private final AiControl control;
    private final AiStore store;
    private final AiExecutor executor;
    private final MaintenanceWindows windows;
    private final Optional<LlmClient> llm;
    private final Optional<PrometheusClient> prometheus;
    private final ObjectMapper mapper;
    private final AiChat chat;
    private final AiServerStatus server;
    private final Clock clock;

    public AiController(AiControl control, AiStore store, AiExecutor executor, MaintenanceWindows windows,
                        Optional<LlmClient> llm, Optional<PrometheusClient> prometheus, ObjectMapper mapper,
                        AiChat chat, AiServerStatus server) {
        this.control = control;
        this.store = store;
        this.executor = executor;
        this.windows = windows;
        this.llm = llm;
        this.prometheus = prometheus;
        this.mapper = mapper;
        this.chat = chat;
        this.server = server;
        this.clock = Clock.systemUTC();
    }

    /* ===== 상담과 AI 서버 상태 ===== */

    /** AI 상담. 읽기 전용이라 VIEWER 이상이고 대화를 저장하지 않는다(설계 7-17). */
    @PostMapping("/ai/chat")
    public Map<String, Object> chat(@RequestBody(required = false) ChatBody body, HttpServletRequest request) {
        AdminPrincipal principal = viewer(request);
        ChatBody b = body == null ? new ChatBody(null, null, null) : body;
        AiChat.Answer a = chat.ask(principal.operatorId(), b.question(), b.history(),
                AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("state", a.state());
        out.put("reason", a.reason());
        out.put("preface", a.preface());
        out.put("answer", a.answer());
        out.put("citations", a.citations());
        out.put("evidence", a.evidence());
        out.put("model", a.model());
        out.put("latency_ms", a.latencyMs());
        return out;
    }

    @GetMapping("/ai/server")
    public Map<String, Object> server(HttpServletRequest request) {
        viewer(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.putAll(server.view());
        return out;
    }

    /* ===== 모드와 정지 ===== */

    @GetMapping("/ai/mode")
    public Map<String, Object> mode(HttpServletRequest request) {
        viewer(request);
        return AiViews.mode(control, store, llm.orElse(null), clock.instant());
    }

    @PostMapping("/ai/mode/auto")
    public Map<String, Object> auto(@RequestBody(required = false) AutoBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        AutoBody b = body == null ? new AutoBody(null, null, null, null) : body;
        AiControl.AutoReadiness ready = new AiControl.AutoReadiness(llm.isPresent(), prometheus.isPresent(),
                llm.map(l -> l.transport() == LlmClient.Transport.PLAINTEXT).orElse(false));
        AiControl.Mode mode = control.enableAuto(principal.operatorId(), b.approval_token(), b.hours(), b.reason(),
                AdminRequestContext.requestId(request), ready);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("mode", mode.mode());
        out.put("since", ApiFormats.time(mode.since()));
        out.put("valid_until", ApiFormats.time(mode.validUntil()));
        out.put("approval_id", mode.approvalId());
        out.put("activation", mode.activation());
        return out;
    }

    @PostMapping("/ai/mode/manual")
    public Map<String, Object> manual(@RequestBody(required = false) ReasonBody body, HttpServletRequest request) {
        AdminPrincipal principal = viewer(request);
        AiControl.Changed changed = control.manual(principal.operatorId(), body == null ? null : body.reason(),
                AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("mode", changed.mode().mode());
        out.put("changed", changed.changed());
        return out;
    }

    @PostMapping("/ai/stop")
    public Map<String, Object> stop(@RequestBody(required = false) ReasonBody body, HttpServletRequest request) {
        AdminPrincipal principal = viewer(request);
        AiControl.Changed changed = control.stop(principal.operatorId(), body == null ? null : body.reason(),
                AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("stopped", changed.mode().stopped());
        out.put("changed", changed.changed());
        out.put("mode", changed.mode().mode());
        out.put("expired_actions", changed.expiredActions());
        return out;
    }

    @PostMapping("/ai/resume")
    public Map<String, Object> resume(@RequestBody(required = false) ReasonBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        AiControl.Changed changed = control.resume(principal.operatorId(), body == null ? null : body.reason(),
                AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("stopped", changed.mode().stopped());
        out.put("changed", changed.changed());
        out.put("mode", changed.mode().mode());
        return out;
    }

    /* ===== 한도 ===== */

    @GetMapping("/ai/limits")
    public Map<String, Object> limits(HttpServletRequest request) {
        viewer(request);
        return AiViews.limits(control);
    }

    @PutMapping("/ai/limits/{key}")
    public Map<String, Object> changeLimit(@PathVariable String key, @RequestBody(required = false) LimitBody body,
                                           HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        LimitBody b = body == null ? new LimitBody(null, null, null, null) : body;
        AiControl.LimitApplied applied = control.changeLimit(key, b.value(), b.reason(), principal.operatorId(),
                b.approval_token(), AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("key", applied.key().id());
        out.put("from", applied.from());
        out.put("to", applied.to());
        out.put("version", applied.version());
        out.put("change_id", applied.changeId());
        out.put("applies", applied.immediate() ? "NOW" : "NEXT_ACTIVATION");
        out.put("effective", applied.effective());
        out.put("mode", applied.mode().mode());
        return out;
    }

    @GetMapping("/ai/limits/changes")
    public Map<String, Object> limitChanges(HttpServletRequest request,
                                            @RequestParam(required = false) String key,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(defaultValue = "50") int limit) {
        viewer(request);
        if (key != null && AiLimits.Key.of(key) == null) {
            throw new ApiException(404, "not_found", "존재하지 않는 AI 한도 항목입니다: " + key,
                    Map.of("reason", "unknown_limit"), null);
        }
        int capped = CursorPages.cap(limit, MAX_PAGE);
        CursorPages.Page<AiControl.LimitChange> page = CursorPages.slice(
                control.limitChanges(key, CursorPages.decode(cursor), capped + 1), capped);
        return list(page.data().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("key", c.key());
            m.put("from", c.from());
            m.put("to", c.to());
            m.put("version", c.version());
            m.put("changed_by", c.changedBy());
            m.put("approval_id", c.approvalId());
            m.put("reason", c.reason());
            m.put("changed_at", ApiFormats.time(c.changedAt()));
            return m;
        }).toList(), CursorPages.pagination(page));
    }

    /* ===== 계획 작업 창 ===== */

    @GetMapping("/maintenance-windows")
    public Map<String, Object> windows(HttpServletRequest request, @RequestParam(defaultValue = "ACTIVE") String status) {
        viewer(request);
        if (!"ACTIVE".equals(status) && !"ALL".equals(status)) {
            throw new ApiException(400, "invalid_parameter", "status 는 ACTIVE|ALL 가운데 하나여야 합니다.");
        }
        Instant now = clock.instant();
        List<MaintenanceWindows.Window> rows = "ACTIVE".equals(status) ? windows.active() : windows.recent();
        return list(rows.stream().map(w -> AiViews.window(w, now)).toList(), CursorPages.noPagination());
    }

    @PostMapping("/maintenance-windows")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createWindow(@RequestBody(required = false) WindowBody body, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        WindowBody b = body == null ? new WindowBody(null, null, null, null, null, null) : body;
        MaintenanceWindows.Window w = windows.create(principal.operatorId(), instant(b.starts_at(), "starts_at"),
                instant(b.ends_at(), "ends_at"), b.alertname(), b.labels(), b.reason(),
                AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.putAll(AiViews.window(w, clock.instant()));
        return out;
    }

    @PostMapping("/maintenance-windows/{id}/end")
    public Map<String, Object> endWindow(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal principal = viewer(request);
        MaintenanceWindows.Ended ended = windows.end(id, principal.operatorId(), AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("id", ended.window().id());
        out.put("ended_at", ended.window().endedAt() == null ? null : ApiFormats.time(ended.window().endedAt()));
        out.put("changed", ended.changed());
        return out;
    }

    /* ===== 판단과 조치 요청 ===== */

    @GetMapping("/ai/decisions")
    public Map<String, Object> decisions(HttpServletRequest request,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "50") int limit) {
        viewer(request);
        int capped = CursorPages.cap(limit, MAX_PAGE);
        CursorPages.Page<AiStore.Decision> page = CursorPages.slice(
                store.decisions(CursorPages.decode(cursor), capped + 1), capped);
        return list(page.data().stream().map(AiViews::decisionSummary).toList(), CursorPages.pagination(page));
    }

    @GetMapping("/ai/decisions/{id}")
    public Map<String, Object> decision(@PathVariable long id, HttpServletRequest request) {
        viewer(request);
        AiStore.Decision d = store.decision(id).orElseThrow(() -> new ApiException(404, "not_found",
                "존재하지 않는 AI 판단입니다.", Map.of("reason", "unknown_decision"), null));
        return AiViews.decision(d, store.decisionActions(d), mapper);
    }

    @GetMapping("/ai/actions")
    public Map<String, Object> actions(HttpServletRequest request,
                                       @RequestParam(defaultValue = "OPEN") String status,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "50") int limit) {
        viewer(request);
        if (!"OPEN".equals(status) && !"ALL".equals(status)) {
            throw new ApiException(400, "invalid_parameter", "status 는 OPEN|ALL 가운데 하나여야 합니다.");
        }
        int capped = CursorPages.cap(limit, MAX_PAGE);
        CursorPages.Page<AiStore.Action> page = CursorPages.slice(
                store.actions("OPEN".equals(status), CursorPages.decode(cursor), capped + 1), capped);
        return list(page.data().stream().map(AiViews::action).toList(), CursorPages.pagination(page));
    }

    @PostMapping("/ai/actions/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id, @RequestBody(required = false) ApproveBody body,
                                       HttpServletRequest request) {
        AdminPrincipal principal = viewer(request);
        AiStore.Action a = executor.approve(id, principal, body == null ? null : body.approval_token(),
                AdminRequestContext.requestId(request));
        return actionResult(a);
    }

    @PostMapping("/ai/actions/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, @RequestBody(required = false) ReasonBody body,
                                      HttpServletRequest request) {
        AdminPrincipal principal = viewer(request);
        return actionResult(executor.reject(id, principal, body == null ? null : body.reason(),
                AdminRequestContext.requestId(request)));
    }

    @PostMapping("/ai/actions/{id}/defer")
    public Map<String, Object> defer(@PathVariable long id, @RequestBody(required = false) DeferBody body,
                                     HttpServletRequest request) {
        AdminPrincipal principal = viewer(request);
        return actionResult(executor.defer(id, principal, body == null ? null : body.minutes(),
                AdminRequestContext.requestId(request)));
    }

    @PostMapping("/ai/actions/{id}/revert")
    public Map<String, Object> revert(@PathVariable long id, @RequestBody(required = false) ApproveBody body,
                                      HttpServletRequest request) {
        AdminPrincipal principal = viewer(request);
        return actionResult(executor.revert(id, principal, body == null ? null : body.approval_token(),
                AdminRequestContext.requestId(request)));
    }

    private static Map<String, Object> actionResult(AiStore.Action a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("action_id", a.id());
        out.put("kind", a.action().kind().name());
        out.put("status", a.status());
        out.put("deferred_until", a.deferredUntil() == null ? null : ApiFormats.time(a.deferredUntil()));
        out.put("result", a.result());
        out.put("revertible", AiExecutor.revertible(a));
        return out;
    }

    private static Map<String, Object> list(List<Map<String, Object>> data, Map<String, Object> pagination) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("data", data);
        out.put("pagination", pagination);
        return out;
    }

    private static Instant instant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "invalid_parameter", field + " 는 UTC 시각(ISO-8601)이어야 합니다.",
                    Map.of("constraint", "format", "field", field), null);
        }
    }

    private static AdminPrincipal viewer(HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.VIEWER);
        return principal;
    }
}

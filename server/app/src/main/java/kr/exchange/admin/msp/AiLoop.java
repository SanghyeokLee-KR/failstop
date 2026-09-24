package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.admin.AdminAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AI 판단 주기와 관찰 주기(설계 7-1·7-8). 전용 실행기(msp-ai)에서 돌고 요청 처리 스레드와 봇 스케줄러를 쓰지 않는다.
 * 판단 주기는 LLM 설정이 있을 때만 모델을 부른다. 관찰 주기는 모드 만료, 요청 만료, 관찰 창, 끊긴 실행의 대조를
 * 15초마다 본다. 둘 다 예외를 삼키고 다음 주기로 넘어간다. 여러 관리 JVM 가운데 임대를 가진 한 곳에서만 돈다.
 */
public class AiLoop {

    private static final Logger log = LoggerFactory.getLogger(AiLoop.class);

    static final Duration OBSERVE_TICK = Duration.ofSeconds(15);
    // 자동 모드에서 모델 실패가 이만큼 이어지면 한 번 통지한다(제안값, 정책 M6).
    static final int MODEL_FAILURE_NOTIFY = 3;

    /** 한 판단의 기록 결과. row 는 조치 요청이 생겼거나 막혔을 때만 있다. */
    record Outcome(long decisionId, AiStore.Action row, AiControl.Mode mode) {
    }

    private final AiEvidenceCollector collector;
    private final LlmClient llm;
    private final AiStore store;
    private final AiControl control;
    private final AiExecutor executor;
    private final IncidentStore incidents;
    private final AdminAuditService audit;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final MspProperties props;
    private final MeterRegistry registry;
    private final Clock clock;
    private final RunnerLease lease;
    private volatile AiNotifier notifier = AiNotifier.NONE;

    // 판단 스레드만 읽고 쓴다. 고정 지연 실행은 겹치지 않으므로 잠금이 필요 없다.
    private String lastSituation;
    private Instant lastJudgedAt;
    private boolean lastFailed;
    private int modelFailures;
    private boolean failureNotified;
    private String lastFingerprint;
    private Instant calmUntil;

    /** llm 이 null 이면 판단은 하지 않고 관찰 주기만 돈다. */
    public AiLoop(AiEvidenceCollector collector, LlmClient llm, AiStore store, AiControl control, AiExecutor executor,
                  IncidentStore incidents, AdminAuditService audit, TransactionTemplate tx, ObjectMapper mapper,
                  MspProperties props, MeterRegistry registry, Clock clock, RunnerLease lease) {
        this.collector = collector;
        this.llm = llm;
        this.store = store;
        this.control = control;
        this.executor = executor;
        this.incidents = incidents;
        this.audit = audit;
        this.tx = tx;
        this.mapper = mapper;
        this.props = props;
        this.registry = registry;
        this.clock = clock;
        this.lease = lease;
    }

    public void notifier(AiNotifier notifier) {
        this.notifier = notifier;
    }

    public void start(ScheduledExecutorService pool) {
        pool.execute(() -> quietly("기동 정리", this::startup));
        if (llm != null) {
            long interval = props.getAi().getInterval().toMillis();
            pool.scheduleWithFixedDelay(() -> quietly("AI 판단", this::tick), interval, interval, TimeUnit.MILLISECONDS);
        }
        long observe = OBSERVE_TICK.toMillis();
        pool.scheduleWithFixedDelay(() -> quietly("AI 관찰", this::observeTick), observe, observe, TimeUnit.MILLISECONDS);
    }

    /**
     * 기동 정리(정책 M7). 자동인데 쓸 수 있는 LLM 이 없거나 평문이면 수동으로 돌리고, 끊긴 실행을 대조한다. 다른 JVM 이
     * 임대를 쥐고 있으면 그 JVM 의 실행이 살아 있으므로 대조하지 않는다. 임대를 넘겨받으면 관찰 주기가 오래 남은 실행을
     * 대조한다.
     */
    public void startup() {
        if (!lease.claim()) {
            skip("not_runner");
            return;
        }
        AiControl.Mode mode = control.mode();
        if (mode.auto() && (llm == null || llm.transport() == LlmClient.Transport.PLAINTEXT)) {
            control.switchToManual("startup_llm", "기동 때 자동 모드를 쓸 수 없는 LLM 설정이라 수동 전환");
        }
        executor.reconcileAtStartup();
    }

    public void observeTick() {
        if (!lease.claim()) {
            skip("not_runner");
            return;
        }
        control.expireIfDue();
        store.expireStale(clock.instant());
        executor.observe();
        executor.reconcileStuck();
    }

    /** 판단 한 번(설계 7-1). */
    public void tick() {
        if (llm == null) {
            return;
        }
        if (!lease.claim()) {
            skip("not_runner");
            return;
        }
        control.expireIfDue();
        AiControl.Mode mode = control.mode();
        if (mode.stopped()) {
            skip("stopped");
            return;
        }
        Instant now = clock.instant();
        // 평온하면 calm-interval 동안 근거도 모으지 않는다. 폴러의 마지막 결과(메모리)에 경보가 새로 있으면 곧바로 판단한다.
        if (calmUntil != null && now.isBefore(calmUntil) && !collector.judgedAlertsPending()) {
            skip("calm_wait");
            return;
        }
        calmUntil = null;
        store.expireStale(now);
        AiEvidence ev = collector.collect();
        List<AiEvidence.Alert> judged = ev.judgedAlerts();
        List<String> crossings = ev.crossings();
        int open = store.openCount();
        if (judged.isEmpty() && crossings.isEmpty() && open == 0) {
            lastSituation = null;
            calm(now);
            skip("calm");
            return;
        }
        // 근거가 직전 판단과 같고 경보와 열린 요청이 없으면 같은 답을 다시 묻지 않는다. 판단 기록 대신 지표만 센다.
        // 열린 요청이 있으면 연속 판단(A4)을 위해 부르고, 직전 판단이 실패였으면 다시 묻는다.
        String fingerprint = fingerprint(ev, mapper);
        if (judged.isEmpty() && open == 0 && !lastFailed && fingerprint.equals(lastFingerprint)) {
            // 축소 후보인 동안은 쉬지 않고 주기마다 표본을 모은다. 표본이 끊기면 축소 전제(D2)의 지속을 다시 잰다.
            if (!crossings.contains(AiEvidence.SCALE_IN_CROSSING)) {
                calm(now);
            }
            skip("unchanged");
            return;
        }
        String situation = situation(judged, crossings);
        // 같은 상황이 이어지면 재판단 간격 전에는 모델을 부르지 않는다. 열린 요청이 있으면 연속 판단(A4)을 위해 부른다.
        if (open == 0 && !lastFailed && situation.equals(lastSituation) && lastJudgedAt != null
                && now.isBefore(lastJudgedAt.plus(props.getAi().getRecheckInterval()))) {
            skip("recheck");
            return;
        }
        String evidenceJson = json(ev.toJson());
        Long incidentId = incident(judged);
        if (ev.allStale()) {
            recordFailure(ev, evidenceJson, "EVIDENCE_STALE", "stale", "rule:evidence", incidentId, null);
            lastFailed = true;
            return;
        }
        LlmClient.Reply reply;
        try {
            if (!llm.loaded()) {
                skip("model_loading");
                warmUp();
                return;
            }
            reply = llm.complete(evidenceJson);
        } catch (LlmClient.LlmException e) {
            recordFailure(ev, evidenceJson, "MODEL_ERROR", e.code(), llm.model(), incidentId, null);
            modelFailed(mode);
            return;
        }
        Instant decidedAt = clock.instant();
        AiOutputValidator.Result v = AiOutputValidator.validate(reply.content(), ev, mapper);
        Outcome outcome = tx.execute(status -> record(ev, evidenceJson, reply, v, decidedAt, incidentId));
        if (outcome == null) {
            skip("stopped");
            return;
        }
        registry.counter("exchange.msp.ai.judgments", "outcome", v.outcome()).increment();
        if ("INVALID_OUTPUT".equals(v.outcome())) {
            modelFailed(outcome.mode());
            return;
        }
        lastFailed = false;
        modelFailures = 0;
        failureNotified = false;
        lastSituation = situation;
        lastJudgedAt = decidedAt;
        lastFingerprint = fingerprint;
        AiStore.Action row = outcome.row();
        if (row != null && "PROPOSED".equals(row.status()) && outcome.mode().auto()) {
            executor.autoExecute(row.id(), new AiPolicy.Decision(v.outcome(), decidedAt, ev));
        }
    }

    /**
     * 판단을 기록하고 조치를 요청 표에 합친다. 모드 행을 공유 잠금으로 읽어 정지와 순서를 정한다. 정지가 먼저
     * 커밋됐으면 아무것도 남기지 않는다(정책 M9).
     */
    private Outcome record(AiEvidence ev, String evidenceJson, LlmClient.Reply reply, AiOutputValidator.Result v,
                           Instant decidedAt, Long incidentId) {
        AiControl.Mode mode = control.lockMode(false);
        if (mode.stopped()) {
            return null;
        }
        AiAction action = v.action();
        long decisionId = store.insertDecision(new AiStore.NewDecision(decidedAt, mode.mode(), v.outcome(), v.error(),
                llm.model(), evidenceJson, ev.oldestSample(), reply.content(), v.situation(), v.reason(),
                v.confidence(), action == null ? null : action.identityKey(), json(v.dropped()),
                (int) Math.min(Integer.MAX_VALUE, reply.latencyMs()), incidentId));
        if ("INVALID_OUTPUT".equals(v.outcome())) {
            return new Outcome(decisionId, null, mode);
        }
        if (action == null) {
            store.withdrawExcept(null);
            return new Outcome(decisionId, null, mode);
        }
        AiPolicy.Denial denial = AiPolicy.proposal(action, new AiPolicy.Facts(decidedAt, ev, mode,
                control.effectiveLimits(mode), props.getAi().getScaleInSustain(), null, null, AiPolicy.History.NONE));
        AiStore.Action row;
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("decision_id", decisionId);
        if (denial != null) {
            row = store.deny(decisionId, decidedAt, action, v.confidence(), v.reason(), denial);
            ref.put("action_id", row.id());
            ref.put("rule", denial.rule());
            incidents.event(incidentId, "DENIED", "AI", AiControl.AI_OPERATOR, "AI 제안을 정책이 막음 #" + row.id()
                    + " " + action.verb() + ": " + denial.message(), ref);
            audit.record(AiControl.AI_OPERATOR, "ops.ai.action.deny", "ai-action:" + row.id(), null, null, "DENIED",
                    Map.of("action_id", row.id(), "decision_id", decisionId, "rule", denial.rule(),
                            "reason_code", denial.code()));
        } else {
            row = store.propose(decisionId, decidedAt, props.getAi().getProposalTtl(), props.getAi().getInterval(),
                    action, v.confidence(), v.reason());
            if (row.firstDecisionId() == decisionId) {
                ref.put("action_id", row.id());
                incidents.event(incidentId, "AI_JUDGED", "AI", AiControl.AI_OPERATOR, "AI 조치 제안 #" + row.id() + " "
                        + action.verb() + " 확신도 " + v.confidence().toPlainString(), ref);
            }
        }
        store.withdrawExcept(action.identityKey());
        return new Outcome(decisionId, row, mode);
    }

    private void recordFailure(AiEvidence ev, String evidenceJson, String outcome, String error, String model,
                               Long incidentId, Integer latencyMs) {
        Instant at = clock.instant();
        tx.executeWithoutResult(status -> {
            AiControl.Mode mode = control.lockMode(false);
            if (mode.stopped()) {
                return;
            }
            store.insertDecision(new AiStore.NewDecision(at, mode.mode(), outcome, error, model, evidenceJson,
                    ev.oldestSample(), null, null, null, null, null, "[]", latencyMs, incidentId));
        });
        registry.counter("exchange.msp.ai.judgments", "outcome", outcome).increment();
    }

    /** 모델 실패나 형식 불일치. 자동 모드에서 이어지면 한 번 통지하고 모드는 그대로 둔다(정책 M6). */
    private void modelFailed(AiControl.Mode mode) {
        lastFailed = true;
        modelFailures++;
        if (mode.auto() && modelFailures >= MODEL_FAILURE_NOTIFY && !failureNotified) {
            failureNotified = true;
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.MODEL_FAILING,
                    Map.of("consecutive_failures", modelFailures)));
        }
    }

    /** 모델을 올린다. 이번 주기는 판단하지 않고, 다음 주기에 적재가 끝났으면 판단한다. */
    private void warmUp() {
        try {
            llm.load();
        } catch (LlmClient.LlmException e) {
            log.warn("LLM 모델 적재 실패: {}", e.code());
        }
    }

    private void skip(String reason) {
        registry.counter("exchange.msp.ai.skips", "reason", reason).increment();
    }

    private void calm(Instant now) {
        calmUntil = now.plus(props.getAi().getCalmInterval());
    }

    /**
     * 판단 근거의 지문(설계 7-1). 수집 시각과 표본 나이를 빼고 측정 값은 유효 숫자 두 자리로 줄인다. 사람이 느끼지 못하는
     * 표본 흔들림으로 같은 상황을 다시 묻지 않되, 값이 실제로 움직이면 지문이 바뀌게 한다.
     */
    static String fingerprint(AiEvidence ev, ObjectMapper mapper) {
        JsonNode tree = mapper.valueToTree(ev.toJson());
        normalize(tree);
        try {
            return AiStore.sha256(mapper.writeValueAsString(tree));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("근거 지문 직렬화 실패", e);
        }
    }

    private static void normalize(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            obj.remove(List.of("collected_at", "alerts_polled_at", "age_seconds"));
            JsonNode value = obj.get("value");
            if (obj.has("collect") && value != null && value.isTextual()) {
                obj.put("value", new BigDecimal(value.asText()).round(new MathContext(2)).toPlainString());
            }
            obj.elements().forEachRemaining(AiLoop::normalize);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(AiLoop::normalize);
        }
    }

    /** 판단 대상 경보의 열린 사건 가운데 critical 을 먼저 고른다. 판단 기록과 타임라인을 그 사건에 잇는다. */
    private Long incident(List<AiEvidence.Alert> judged) {
        if (judged.isEmpty()) {
            return null;
        }
        List<AiEvidence.Alert> ordered = new ArrayList<>(judged);
        ordered.sort(Comparator.comparing(a -> "critical".equals(a.severity()) ? 0 : 1));
        Map<String, IncidentStore.Incident> open = incidents.openByFingerprint(
                ordered.stream().map(AiEvidence.Alert::fingerprint)
                        .filter(fp -> IncidentStore.FINGERPRINT.matcher(fp).matches()).toList());
        for (AiEvidence.Alert a : ordered) {
            IncidentStore.Incident i = open.get(a.fingerprint());
            if (i != null) {
                return i.id();
            }
        }
        return null;
    }

    static String situation(List<AiEvidence.Alert> judged, List<String> crossings) {
        List<String> parts = new ArrayList<>();
        judged.forEach(a -> parts.add(a.fingerprint() + "@" + a.startsAt()));
        parts.addAll(crossings);
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    private static void quietly(String what, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.warn("{} 처리 실패: {}", what, e.toString());
        }
    }
}

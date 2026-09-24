package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.AdminPrincipal;
import kr.exchange.admin.AdminRole;
import kr.exchange.admin.ApprovalService;
import kr.exchange.admin.BotRateControl;
import kr.exchange.admin.CapacityPlanService;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AI 조치 요청의 실행, 되돌리기, 관찰 창, 끊긴 실행의 대조(설계 7-7·7-8).
 *
 * 잠금은 어느 경로든 JVM 잠금(사일런스는 지문별, 봇 발행률은 하나), 트랜잭션 어드바이저리 락, 모드 행, 대상 행의
 * 순서로만 잡는다. DB 안에서 끝나는 조치(조회 노드, 임계값, 큐 경로의 봇 발행률)는 모드 확인, 상태 전이, 변경이 한
 * 트랜잭션이다. 사일런스와 같은 JVM 의 거버너처럼 DB 밖의 효과는 EXECUTING 을 커밋하고, 호출하고, 결과를 적는다.
 */
public class AiExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiExecutor.class);

    // EXECUTING 이 이보다 오래 남으면 원천과 대조한다(제안값, 정책 M7).
    static final Duration RECONCILE_AFTER = Duration.ofMinutes(2);
    static final String COMMENT_TAG = "msp-ai-action:";
    private static final int DEFER_DEFAULT = 15;
    private static final int DEFER_MAX = 60;

    enum Path { APPROVAL, AUTO }

    /** 정책 엔진이 막았다. 트랜잭션을 되돌리려고 예외로 올린다. */
    static final class Denied extends RuntimeException {

        final AiPolicy.Denial denial;

        Denied(AiPolicy.Denial denial) {
            super(denial.rule(), null, false, false);
            this.denial = denial;
        }
    }

    /** 자동 실행 시도의 결과. denial 이 있으면 실행하지 않았다. */
    public record AutoResult(AiStore.Action action, AiPolicy.Denial denial) {
    }

    private final AiStore store;
    private final AiControl control;
    private final AiEvidenceCollector collector;
    private final CapacityPlanService capacity;
    private final OpsThresholdService thresholds;
    private final AlertmanagerClient alertmanager;
    private final AlertActions alertActions;
    private final IncidentPoller poller;
    private final IncidentStore incidents;
    private final BotRateControl botRate;
    private final ApprovalService approvals;
    private final AdminAuditService audit;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final MspProperties props;
    private final Clock clock;
    private volatile AiNotifier notifier = AiNotifier.NONE;

    /** alertmanager, alertActions, poller 는 Alertmanager 주소가 없으면 null 이다. 그때 보류 조치는 없다. */
    public AiExecutor(AiStore store, AiControl control, AiEvidenceCollector collector, CapacityPlanService capacity,
                      OpsThresholdService thresholds, AlertmanagerClient alertmanager, AlertActions alertActions,
                      IncidentPoller poller, IncidentStore incidents, BotRateControl botRate, ApprovalService approvals,
                      AdminAuditService audit, TransactionTemplate tx, JdbcClient jdbc, MspProperties props,
                      Clock clock) {
        this.store = store;
        this.control = control;
        this.collector = collector;
        this.capacity = capacity;
        this.thresholds = thresholds;
        this.alertmanager = alertmanager;
        this.alertActions = alertActions;
        this.poller = poller;
        this.incidents = incidents;
        this.botRate = botRate;
        this.approvals = approvals;
        this.audit = audit;
        this.tx = tx;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public void notifier(AiNotifier notifier) {
        this.notifier = notifier;
    }

    /* ===== 사람의 처리 ===== */

    /**
     * 승인과 실행(정책 C1~C6, 종류별 규칙, 방향 전제, M9). 임계값 조치는 ops.threshold.set 티켓을 같은 트랜잭션에서
     * 쓴다. 이미 처리됐거나 만료된 요청(C3)은 계약대로 ai_action_not_open 이다(API §6-F).
     */
    public AiStore.Action approve(long id, AdminPrincipal principal, String token, String requestId) {
        AiStore.Action row = store.action(id).orElseThrow(AiExecutor::notFound);
        principal.require(AiPolicy.requiredRole(row.action().kind()));
        try {
            return run(row, Path.APPROVAL, principal.operatorId(), token, null, requestId);
        } catch (Denied d) {
            auditDenied(principal.operatorId(), "ops.ai.action.approve", row, d.denial, requestId);
            throw "C3".equals(d.denial.rule()) ? notOpen() : AiPolicy.denied(d.denial);
        }
    }

    public AiStore.Action reject(long id, AdminPrincipal principal, String reason, String requestId) {
        principal.require(AdminRole.VIEWER);
        String why = AiControl.optionalText(reason, 200, "reason");
        Instant now = clock.instant();
        return tx.execute(status -> {
            AiStore.Action row = store.lockAction(id).orElseThrow(AiExecutor::notFound);
            if (!row.open()) {
                throw notOpen();
            }
            store.decide(id, "REJECTED", principal.operatorId(), now, null);
            incidents.event(incidentOf(row), "REJECTED", "OPERATOR", principal.operatorId(),
                    "AI 조치 거절 #" + id + " " + row.action().verb() + (why.isEmpty() ? "" : ": " + why),
                    Map.of("action_id", id));
            audit.record(principal.operatorId(), "ops.ai.action.reject", "ai-action:" + id, requestId, null, "OK",
                    Map.of("action_id", id, "reason", why));
            return store.action(id).orElseThrow();
        });
    }

    /** 나중에. 만료 시각을 늘리지 않고, 나중에로 둔 요청은 자동 실행에서 영구히 빠진다(정책 C3·A9). */
    public AiStore.Action defer(long id, AdminPrincipal principal, Integer minutes, String requestId) {
        principal.require(AdminRole.VIEWER);
        int m = minutes == null ? DEFER_DEFAULT : minutes;
        if (m < 1 || m > DEFER_MAX) {
            throw new ApiException(400, "invalid_parameter", "minutes 는 1~" + DEFER_MAX + " 정수여야 합니다.",
                    Map.of("constraint", "range", "field", "minutes"), null);
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            AiStore.Action row = store.lockAction(id).orElseThrow(AiExecutor::notFound);
            if (!row.open()) {
                throw notOpen();
            }
            Instant until = now.plus(Duration.ofMinutes(m));
            until = until.isAfter(row.expiresAt()) ? row.expiresAt() : until;
            store.decide(id, "DEFERRED", principal.operatorId(), now, until);
            incidents.event(incidentOf(row), "DEFERRED", "OPERATOR", principal.operatorId(),
                    "AI 조치 나중에 #" + id + " " + row.action().verb(), Map.of("action_id", id));
            audit.record(principal.operatorId(), "ops.ai.action.defer", "ai-action:" + id, requestId, null, "OK",
                    Map.of("action_id", id, "deferred_until", ApiFormats.time(until)));
            return store.action(id).orElseThrow();
        });
    }

    /** 실행한 조치 되돌리기(API §6-F). 종류마다 다르고 백업 즉시 실행은 되돌리기가 없다. */
    public AiStore.Action revert(long id, AdminPrincipal principal, String token, String requestId) {
        AiStore.Action row = store.action(id).orElseThrow(AiExecutor::notFound);
        principal.require(AiPolicy.requiredRole(row.action().kind()));
        if (!revertible(row)) {
            throw notOpen();
        }
        String operator = principal.operatorId();
        try {
            AiStore.Action done = switch (row.action()) {
                case AiAction.Silence s -> withSilenceLock(s.fingerprint(),
                        () -> endSilence(row, "REVERTED", operator, "사람의 되돌리기", requestId));
                case AiAction.BotLoad b -> botRate.withLock(() -> restoreBotRate(row, operator, requestId));
                case AiAction.ReadNodes r -> revertReadNodes(row, "REVERTED", operator, "사람의 되돌리기", requestId);
                case AiAction.Threshold t -> revertThreshold(row, "REVERTED", operator, token, "사람의 되돌리기", requestId);
                case AiAction.BackupNow k -> throw notOpen();
            };
            if (row.action() instanceof AiAction.Threshold) {
                thresholds.refreshGauge();
            }
            return done;
        } catch (Denied d) {
            auditDenied(operator, "ops.ai.action.revert", row, d.denial, requestId);
            throw AiPolicy.denied(d.denial);
        }
    }

    static boolean revertible(AiStore.Action row) {
        return ("APPROVED".equals(row.status()) || "AUTO_EXECUTED".equals(row.status()))
                && !(row.action() instanceof AiAction.BackupNow);
    }

    /* ===== 자동 실행 ===== */

    /**
     * 자동 실행(정책 A1~A10). 막히면 요청을 열린 채로 두고 막은 규칙을 적는다. 상한(A7)에 닿으면 수동으로 돌리고
     * 통지한다.
     */
    public AutoResult autoExecute(long id, AiPolicy.Decision decision) {
        AiStore.Action row = store.action(id).orElseThrow(AiExecutor::notFound);
        try {
            return new AutoResult(run(row, Path.AUTO, AiControl.AI_OPERATOR, null, decision, null), null);
        } catch (Denied d) {
            return held(row, d.denial);
        } catch (ApiException e) {
            // 잠금을 잡은 뒤 대상이 바뀐 경우다(경계, T3, 원천 실패). 요청은 열어 두고 사유만 남긴다.
            return held(row, new AiPolicy.Denial("C5", e.name(), e.getMessage()));
        }
    }

    private AutoResult held(AiStore.Action row, AiPolicy.Denial denial) {
        tx.executeWithoutResult(status -> {
            store.hold(row.id(), denial);
            auditDenied(AiControl.AI_OPERATOR, "ops.ai.action.execute", row, denial, null);
        });
        if ("A7".equals(denial.rule())) {
            if (control.switchToManual("auto_cap", "이번 자동 모드의 자동 조치 상한에 닿아 수동 전환")) {
                notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.CAP_REACHED, Map.of("action_id", row.id())));
            }
        }
        return new AutoResult(store.action(row.id()).orElseThrow(), denial);
    }

    private AiStore.Action run(AiStore.Action row, Path path, String actor, String token, AiPolicy.Decision decision,
                               String requestId) {
        boolean external = row.action() instanceof AiAction.Silence
                || (row.action() instanceof AiAction.BotLoad && botRate.local());
        Supplier<AiStore.Action> body = () -> external
                ? twoPhase(row.id(), path, actor, decision, requestId)
                : onePhase(row.id(), path, actor, token, decision, requestId);
        return switch (row.action()) {
            case AiAction.Silence s -> withSilenceLock(s.fingerprint(), body);
            case AiAction.BotLoad b -> botRate.withLock(body);
            default -> body.get();
        };
    }

    private AiStore.Action onePhase(long id, Path path, String actor, String token, AiPolicy.Decision decision,
                                    String requestId) {
        AiEvidence fresh = collector.collect();
        Instant now = clock.instant();
        AiStore.Action done = tx.execute(status -> {
            AiControl.Mode mode = lockForExecution();
            AiStore.Action locked = store.lockAction(id).orElseThrow(AiExecutor::notFound);
            lockTarget(locked.action(), fresh);
            check(locked, path, mode, fresh, now, decision);
            Long approvalId = null;
            if (path == Path.APPROVAL && locked.action() instanceof AiAction.Threshold t) {
                approvalId = approvals.consume(actor, token, OpsThresholdService.APPROVAL_ACTION,
                        OpsThresholdService.target(t.key(), t.value()));
            }
            Long activation = path == Path.AUTO ? mode.activation() : null;
            store.executing(id, actor, now, activation, path == Path.APPROVAL ? actor : null, approvalId, null);
            Map<String, Object> result = applyInDb(locked, path, actor, approvalId, fresh, requestId);
            finish(locked, path, actor, approvalId, result, fresh, now, requestId);
            return store.action(id).orElseThrow();
        });
        afterExecution(done, path);
        return done;
    }

    private AiStore.Action twoPhase(long id, Path path, String actor, AiPolicy.Decision decision, String requestId) {
        AiStore.Action row = store.action(id).orElseThrow(AiExecutor::notFound);
        AiEvidence fresh = collector.collect();
        Instant now = clock.instant();
        AlertmanagerClient.Alert live = row.action() instanceof AiAction.Silence s ? liveAlert(s.fingerprint()) : null;
        // 봇 발행률은 거버너가 곧 원천이라, 끊긴 실행을 대조할 수 있게 목표를 EXECUTING 에 먼저 적는다.
        Map<String, Object> pending = row.action() instanceof AiAction.BotLoad b && fresh.bot().targetOps() != null
                ? Map.of("from", ApiFormats.amount(BigDecimal.valueOf(fresh.bot().targetOps())),
                "to", ApiFormats.amount(botTarget(b, fresh)), "pending", true)
                : null;
        tx.executeWithoutResult(status -> {
            AiControl.Mode mode = lockForExecution();
            AiStore.Action locked = store.lockAction(id).orElseThrow(AiExecutor::notFound);
            check(locked, path, mode, fresh, now, decision);
            store.executing(id, actor, now, path == Path.AUTO ? mode.activation() : null,
                    path == Path.APPROVAL ? actor : null, null, pending);
        });
        Map<String, Object> result;
        Long incidentId = null;
        try {
            result = applyExternal(row, actor, live, fresh, now, requestId);
            if (live != null && poller != null) {
                incidentId = poller.record(live);
            }
        } catch (SourceException e) {
            String status = e.outcomeUnknown() ? "UNKNOWN" : "FAILED";
            tx.executeWithoutResult(s -> {
                store.failed(id, status, Map.of("error", e.reason()));
                audit.record(actor, path == Path.AUTO ? "ops.ai.action.execute" : "ops.ai.action.approve",
                        "ai-action:" + id, requestId, null, "ERROR", Map.of("action_id", id, "error", e.reason(),
                                "status", status));
            });
            if ("UNKNOWN".equals(status)) {
                notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.EXECUTION_UNKNOWN, Map.of("action_id", id)));
            }
            if (path == Path.APPROVAL) {
                throw upstream(e);
            }
            return store.action(id).orElseThrow();
        }
        Long incident = incidentId;
        AiStore.Action done = tx.execute(status -> {
            if (incident != null) {
                recordSilence(row, incident, result, path, actor);
            }
            finish(row, path, actor, null, result, fresh, now, requestId);
            return store.action(id).orElseThrow();
        });
        afterExecution(done, path);
        return done;
    }

    private void check(AiStore.Action row, Path path, AiControl.Mode mode, AiEvidence fresh, Instant now,
                       AiPolicy.Decision decision) {
        AiPolicy.Facts f = facts(row, mode, fresh, now, decision);
        AiPolicy.Denial d = path == Path.AUTO ? AiPolicy.auto(row.action(), f) : AiPolicy.approval(row.action(), f);
        if (d != null) {
            throw new Denied(d);
        }
    }

    /** 잠금을 잡은 뒤의 사실. 사람의 같은 종류 조치 시각은 감사 기록에서 다시 읽는다(정책 A8·C5). */
    AiPolicy.Facts facts(AiStore.Action row, AiControl.Mode mode, AiEvidence ev, Instant now,
                         AiPolicy.Decision decision) {
        AiAction a = row.action();
        int thresholdUsed = a instanceof AiAction.Threshold t && mode.auto()
                ? control.thresholdUsed(mode.activation()).getOrDefault(t.key(), 0) : 0;
        AiPolicy.History h = new AiPolicy.History(store.executingExists(), store.latestObserveUntil(),
                mode.auto() ? control.autoUsed(mode.activation()) : 0, thresholdUsed, store.lastSameKind(a.kind()),
                store.deferredUntil(row.identityKey()), store.rejectedAt(row.identityKey()),
                a instanceof AiAction.Silence s && store.lastSilenceByAi(s.fingerprint()), 0, false);
        return new AiPolicy.Facts(now, ev, mode, control.effectiveLimits(mode), props.getAi().getScaleInSustain(),
                new AiPolicy.Request(row.status(), row.expiresAt(), row.deferredUntil(), row.consecutive(),
                        row.confidence()),
                decision, h);
    }

    /**
     * 실행 트랜잭션의 첫 두 문장. AI 실행끼리는 어드바이저리 락으로 줄을 세우고(정책 C4), 모드 행을 공유 잠금으로
     * 읽어 즉시 끄기·정지와 순서를 정한다. 끄기가 먼저 커밋됐으면 여기서 수동을 본다(정책 A1).
     */
    private AiControl.Mode lockForExecution() {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('msp-ai-exec', 0))").query(Integer.class).single();
        return control.lockMode(false);
    }

    /**
     * 대상 행을 잠그고 실행 직전 근거가 본 값과 같은지 본다(정책 C5). 사람 경로는 대상 행만 잠그므로, 검사(A8 포함)를 이
     * 잠금 뒤에 해야 근거를 모은 뒤 커밋된 사람의 변경을 덮어쓰지 않는다. 근거에 대상이 없으면 종류별 규칙이 막는다.
     */
    private void lockTarget(AiAction action, AiEvidence fresh) {
        boolean unchanged = switch (action) {
            case AiAction.ReadNodes r -> {
                CapacityPlanService.Plan plan = capacity.planForUpdate();
                AiEvidence.ReadTier read = fresh.read();
                yield read == null || (plan.min() == read.min() && plan.desired() == read.desired()
                        && plan.max() == read.max());
            }
            case AiAction.Threshold t -> thresholds.lockForAi(t.key()).entrySet().stream().allMatch(e -> {
                AiEvidence.Threshold seen = fresh.thresholds().get(e.getKey());
                return seen == null || (seen.value() == null ? e.getValue() == null
                        : e.getValue() != null && seen.value().compareTo(e.getValue()) == 0);
            });
            default -> true;
        };
        if (!unchanged) {
            throw new Denied(new AiPolicy.Denial("C5", "target_changed", "근거를 모은 뒤 대상 값이 바뀌어 실행하지 않습니다."));
        }
    }

    private Map<String, Object> applyInDb(AiStore.Action row, Path path, String actor, Long approvalId, AiEvidence fresh,
                                          String requestId) {
        String note = "AI 조치 #" + row.id();
        return switch (row.action()) {
            case AiAction.ReadNodes r -> {
                CapacityPlanService.Change c = capacity.change(r.delta(), actor,
                        r.delta() > 0 ? "capacity.scale_out" : "capacity.scale_in", note, requestId);
                yield Map.of("from", c.from(), "to", c.to());
            }
            case AiAction.Threshold t -> {
                OpsThresholdService.Applied ap = thresholds.applyForAi(t.key(), t.value(),
                        path == Path.AUTO ? "AI_AUTO" : "AI_APPROVED", actor, approvalId, row.id(), note, requestId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", ap.key());
                m.put("from", ApiFormats.amount(ap.from()));
                m.put("to", ApiFormats.amount(ap.to()));
                m.put("change_id", ap.changeId());
                m.put("version", ap.version());
                yield m;
            }
            case AiAction.BotLoad b -> botRateResult(b, fresh, actor, row.id(), requestId);
            default -> throw new IllegalStateException("DB 안에서 끝나지 않는 조치: " + row.action().kind());
        };
    }

    private Map<String, Object> applyExternal(AiStore.Action row, String actor, AlertmanagerClient.Alert live,
                                              AiEvidence fresh, Instant now, String requestId) {
        return switch (row.action()) {
            case AiAction.Silence s -> {
                Instant ends = now.plus(Duration.ofMinutes(s.minutes()));
                String silenceId = alertmanager.createSilence(live.labels(), now, ends, actor,
                        "AI 조치 보류 " + COMMENT_TAG + row.id());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("silence_id", silenceId);
                m.put("fingerprint", s.fingerprint());
                m.put("ends_at", ApiFormats.time(ends));
                yield m;
            }
            case AiAction.BotLoad b -> botRateResult(b, fresh, actor, row.id(), requestId);
            default -> throw new IllegalStateException("DB 밖 효과가 아닌 조치: " + row.action().kind());
        };
    }

    private static BigDecimal botTarget(AiAction.BotLoad b, AiEvidence fresh) {
        return BigDecimal.valueOf(fresh.bot().targetOps()).multiply(b.factor()).setScale(3, RoundingMode.HALF_UP);
    }

    private Map<String, Object> botRateResult(AiAction.BotLoad b, AiEvidence fresh, String actor, long actionId,
                                              String requestId) {
        BigDecimal current = BigDecimal.valueOf(fresh.bot().targetOps());
        BigDecimal target = botTarget(b, fresh);
        BotRateControl.Applied ap = botRate.set(target.doubleValue(), actor, requestId, Map.of("ai_action_id", actionId));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", ApiFormats.amount(current));
        m.put("to", ApiFormats.amount(target));
        m.put("apply_path", ap.applyPath());
        m.put("queued_command_id", ap.queuedCommandId());
        return m;
    }

    private void finish(AiStore.Action row, Path path, String actor, Long approvalId, Map<String, Object> result,
                        AiEvidence fresh, Instant now, String requestId) {
        String status = path == Path.AUTO ? "AUTO_EXECUTED" : "APPROVED";
        store.executed(row.id(), status, result, baseline(fresh), now.plus(AiPolicy.OBSERVE));
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("action_id", row.id());
        ref.put("decision_id", row.lastDecisionId());
        if (approvalId != null) {
            ref.put("approval_id", approvalId);
        }
        incidents.event(incidentOf(row), path == Path.AUTO ? "AUTO_ACTED" : "APPROVED",
                path == Path.AUTO ? "AI" : "OPERATOR", actor,
                (path == Path.AUTO ? "AI 자동 실행 #" : "AI 조치 승인 실행 #") + row.id() + " " + row.action().verb()
                        + " " + summary(result), ref);
        Map<String, Object> detail = new LinkedHashMap<>(ref);
        detail.put("kind", row.action().kind().name());
        detail.put("verb", row.action().verb().name());
        detail.put("result", result);
        audit.record(actor, path == Path.AUTO ? "ops.ai.action.execute" : "ops.ai.action.approve",
                "ai-action:" + row.id(), requestId, approvalId, "OK", detail);
    }

    private void afterExecution(AiStore.Action done, Path path) {
        if (done.action() instanceof AiAction.Threshold) {
            thresholds.refreshGauge();
        }
        if (path == Path.AUTO && "AUTO_EXECUTED".equals(done.status())) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("action_id", done.id());
            facts.put("action", done.action().verb().name());
            facts.put("target", done.action().target());
            facts.put("result", done.result() == null ? null : done.result().toString());
            facts.put("confidence", ApiFormats.amount(done.confidence()));
            facts.put("observe_until", ApiFormats.time(done.observeUntil()));
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.AUTO_EXECUTED, facts));
        }
    }

    /** 실행 때의 기준선. 관찰 창의 악화 판정이 이것과 비교한다(설계 7-8). */
    static Map<String, Object> baseline(AiEvidence ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> critical = new ArrayList<>();
        for (AiEvidence.Alert a : ev.alerts()) {
            if ("critical".equals(a.severity()) && !a.silenced()) {
                critical.add(a.fingerprint());
            }
        }
        m.put("critical", critical);
        m.put("read_avg_cpu", ev.read() == null ? null : ev.read().avgCpu());
        m.put("error_ratio", ev.metric(AiEvidence.HTTP_5XX_RATIO).value());
        m.put("p95_seconds", ev.metric(AiEvidence.HTTP_P95_SECONDS).value());
        m.put("availability", ev.metric(AiEvidence.AVAILABILITY).value());
        return m;
    }

    /* ===== 관찰 창과 자동 되돌림(설계 7-8) ===== */

    /**
     * 자동 실행한 조치의 관찰 창을 본다. 모드가 바뀌었거나 정지 중이어도 끝까지 보고, 악화하면 종류별로 되돌린다.
     * 되돌림은 조치를 무르는 안전 방향이라 정지 중에도 한다(ADR 0035).
     */
    public void observe() {
        List<AiStore.Action> rows = store.observing();
        if (rows.isEmpty()) {
            return;
        }
        AiEvidence ev = collector.collect();
        Instant now = clock.instant();
        for (AiStore.Action row : rows) {
            try {
                observeOne(row, ev, now);
            } catch (RuntimeException e) {
                log.warn("AI 조치 관찰 처리 실패 id={}: {}", row.id(), e.toString());
            }
        }
    }

    private void observeOne(AiStore.Action row, AiEvidence ev, Instant now) {
        if (!observable(row, ev, now)) {
            closeObservation(row, "UNOBSERVABLE", "관찰 불가: 근거가 없거나 60초보다 오래돼 악화를 판정할 수 없습니다.");
            return;
        }
        String why = degradation(row, ev);
        if (why == null) {
            if (!now.isBefore(row.observeUntil())) {
                tx.executeWithoutResult(s -> {
                    if (store.observed(row.id(), "KEPT")) {
                        incidents.event(incidentOf(row), "OBSERVED", "SYSTEM", AiControl.SYSTEM,
                                "관찰 창 종료, 조치 유지 #" + row.id(), Map.of("action_id", row.id()));
                    }
                });
            }
            return;
        }
        autoRevert(row, why);
    }

    /** 관찰에 필요한 근거가 60초 안인가. 경보 원천이 있으면 성공한 폴링도 60초 안이어야 새 critical 을 본다. */
    static boolean observable(AiStore.Action row, AiEvidence ev, Instant now) {
        if (ev.alertsConnected() && (ev.alertsPolledAt() == null
                || Duration.between(ev.alertsPolledAt(), now).compareTo(AiEvidence.MAX_AGE) > 0)) {
            return false;
        }
        if (row.action() instanceof AiAction.ReadNodes || row.action() instanceof AiAction.Threshold) {
            Instant oldest = ev.oldestRequired(row.action());
            return oldest != null && Duration.between(oldest, now).compareTo(AiEvidence.MAX_AGE) <= 0;
        }
        return true;
    }

    /**
     * 악화 판정. 실행 뒤 시작한 새 critical 경보, 조치 대상이 반대로 감, 지키던 SLO 를 새로 어김 가운데 하나다.
     * 근거 없는 '기준선 대비 몇 %' 는 쓰지 않는다.
     */
    String degradation(AiStore.Action row, AiEvidence ev) {
        JsonNode baseline = row.baseline();
        List<String> before = new ArrayList<>();
        if (baseline != null) {
            baseline.path("critical").forEach(n -> before.add(n.asText()));
        }
        for (AiEvidence.Alert a : ev.alerts()) {
            if ("critical".equals(a.severity()) && !a.silenced() && !before.contains(a.fingerprint())
                    && a.startsAt().isAfter(row.executedAt())) {
                return "실행 뒤 새 critical 경보 " + a.alertname();
            }
        }
        if (row.action() instanceof AiAction.ReadNodes r && r.delta() < 0 && ev.read() != null
                && ev.read().avgCpu() != null) {
            AiEvidence.Threshold out = ev.thresholds().get(OpsThresholdService.SCALE_OUT);
            if (out != null && out.value() != null && BigDecimal.valueOf(ev.read().avgCpu()).compareTo(out.value()) >= 0) {
                return "축소 뒤 조회 평균 CPU가 증설 임계 이상";
            }
        }
        if (row.action() instanceof AiAction.Threshold t && t.from() != null
                && AiPolicy.loosens(t.key(), t.from(), t.value()) && loosenedThenBreached(t, ev)) {
            return "임계를 둔하게 바꾼 뒤 지표가 이전 임계를 넘음";
        }
        return sloBreach(baseline, ev);
    }

    /**
     * 둔하게 바꾼 임계가 가린 문제가 나타났는가. 경보 임계는 지표가 바꾸기 전 임계를 넘었는지, 조회 임계는 조회 평균
     * CPU 가 증설 임계 이상인지(용량 보호를 약하게 한 뒤 부하가 높음) 본다.
     */
    private static boolean loosenedThenBreached(AiAction.Threshold t, AiEvidence ev) {
        if (t.key().startsWith("read.")) {
            AiEvidence.Threshold out = ev.thresholds().get(OpsThresholdService.SCALE_OUT);
            return ev.read() != null && ev.read().avgCpu() != null && out != null && out.value() != null
                    && BigDecimal.valueOf(ev.read().avgCpu()).compareTo(out.value()) >= 0;
        }
        String metric = AiEvidence.metricOf(t.key());
        Double v = metric == null ? null : ev.metric(metric).value();
        return v != null && AiEvidence.beyond(t.key(), BigDecimal.valueOf(v), t.from());
    }

    private String sloBreach(JsonNode baseline, AiEvidence ev) {
        MspProperties.Slo slo = props.getSlo();
        if (baseline == null) {
            return null;
        }
        if (slo.getErrorRatio() != null && worse(baseline.path("error_ratio"), ev.metric(AiEvidence.HTTP_5XX_RATIO),
                slo.getErrorRatio(), true)) {
            return "5xx 비율이 SLO 를 새로 어김";
        }
        if (slo.getP95Seconds() != null && worse(baseline.path("p95_seconds"), ev.metric(AiEvidence.HTTP_P95_SECONDS),
                slo.getP95Seconds(), true)) {
            return "응답 p95 가 SLO 를 새로 어김";
        }
        if (slo.getAvailabilityRatio() != null && worse(baseline.path("availability"),
                ev.metric(AiEvidence.AVAILABILITY), slo.getAvailabilityRatio(), false)) {
            return "가용성이 SLO 를 새로 어김";
        }
        return null;
    }

    /** 실행 때는 목표를 지켰고 지금은 어긴다. 둘 가운데 하나라도 모르면 악화로 보지 않는다. */
    private static boolean worse(JsonNode before, AiEvidence.Metric now, BigDecimal target, boolean upperBound) {
        if (before == null || !before.isNumber() || now.value() == null) {
            return false;
        }
        BigDecimal b = before.decimalValue();
        BigDecimal n = BigDecimal.valueOf(now.value());
        return upperBound ? b.compareTo(target) <= 0 && n.compareTo(target) > 0
                : b.compareTo(target) >= 0 && n.compareTo(target) < 0;
    }

    /**
     * 종류별 자동 되돌림(설계 7-7 표). 축소와 임계값과 보류는 되돌리고, 증설과 봇 부하 줄임은 되돌리면 용량을 줄이거나
     * 부하를 늘리는 쪽이라 되돌리지 않고 사람에게 넘긴다. 어느 쪽이든 자동 모드였으면 수동으로 바꾸고 통지한다.
     */
    private void autoRevert(AiStore.Action row, String why) {
        String outcome;
        try {
            switch (row.action()) {
                case AiAction.ReadNodes r when r.delta() < 0 ->
                        revertReadNodes(row, "AUTO_REVERTED", AiControl.AI_OPERATOR, why, null);
                case AiAction.Threshold t -> {
                    revertThreshold(row, "AUTO_REVERTED", AiControl.AI_OPERATOR, null, why, null);
                    thresholds.refreshGauge();
                }
                case AiAction.Silence s -> withSilenceLock(s.fingerprint(),
                        () -> endSilence(row, "AUTO_REVERTED", AiControl.AI_OPERATOR, why, null));
                default -> {
                    closeObservation(row, "NOT_REVERTIBLE", "악화했지만 되돌리면 용량을 줄이거나 부하를 늘리는 조치라 "
                            + "자동으로 되돌리지 않습니다(" + why + ").");
                    return;
                }
            }
            outcome = "REVERTED";
        } catch (Denied | ApiException | SourceException e) {
            closeObservation(row, "REVERT_BLOCKED", "악화했지만 되돌릴 수 없습니다(" + why + "): "
                    + (e instanceof Denied d ? d.denial.message() : e.getMessage()));
            return;
        }
        tx.executeWithoutResult(s -> store.observed(row.id(), outcome));
        control.switchToManual("degraded", "관찰 창에서 악화해 자동 되돌림 뒤 수동 전환");
        notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.AUTO_REVERTED,
                Map.of("action_id", row.id(), "action", row.action().verb().name(), "cause", why)));
    }

    /** 관찰 주기가 요청을 읽은 뒤 사람이 먼저 되돌렸으면 결과를 적지 않고 수동 전환과 통지도 하지 않는다(설계 7-8). */
    private void closeObservation(AiStore.Action row, String outcome, String text) {
        Boolean closed = tx.execute(s -> {
            if (!store.observed(row.id(), outcome)) {
                return false;
            }
            incidents.event(incidentOf(row), "OBSERVED", "SYSTEM", AiControl.SYSTEM, text + " #" + row.id(),
                    Map.of("action_id", row.id(), "outcome", outcome));
            audit.record(AiControl.SYSTEM, "ops.ai.action.auto_revert", "ai-action:" + row.id(), null, null, "DENIED",
                    Map.of("action_id", row.id(), "outcome", outcome));
            return true;
        });
        if (!Boolean.TRUE.equals(closed)) {
            return;
        }
        control.switchToManual(outcome.toLowerCase(), text);
        notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.OBSERVE_FAILED,
                Map.of("action_id", row.id(), "outcome", outcome)));
    }

    private AiStore.Action revertReadNodes(AiStore.Action row, String status, String actor, String why,
                                           String requestId) {
        AiAction.ReadNodes r = (AiAction.ReadNodes) row.action();
        int expected = row.result().path("to").asInt();
        AiStore.Action done = tx.execute(s -> {
            lockForRevert(row);
            CapacityPlanService.Plan plan = capacity.planForUpdate();
            if (plan.desired() != expected) {
                throw new Denied(new AiPolicy.Denial("C5", "target_changed", "현재 희망 대수 " + plan.desired()
                        + "대가 조치 결과 " + expected + "대와 달라 되돌리지 않습니다."));
            }
            CapacityPlanService.Change c = capacity.change(-r.delta(), actor,
                    r.delta() < 0 ? "capacity.scale_out" : "capacity.scale_in", "AI 조치 되돌림 #" + row.id(), requestId);
            return recordRevert(row, status, actor, why, Map.of("from", c.from(), "to", c.to()), requestId);
        });
        return done;
    }

    private AiStore.Action revertThreshold(AiStore.Action row, String status, String actor, String token, String why,
                                           String requestId) {
        AiAction.Threshold t = (AiAction.Threshold) row.action();
        long changeId = row.result().path("change_id").asLong();
        return tx.execute(s -> {
            lockForRevert(row);
            Long approvalId = null;
            if ("REVERTED".equals(status)) {
                // 티켓은 실제로 다시 쓸 값, 곧 실행 때 바꾸기 전의 값에 묶는다(API §3-6 바인딩).
                OpsThresholdService.Change change = thresholds.change(changeId).orElseThrow(AiExecutor::notOpen);
                approvalId = approvals.consume(actor, token, OpsThresholdService.APPROVAL_ACTION,
                        OpsThresholdService.target(t.key(), change.from()));
            }
            OpsThresholdService.Applied ap = thresholds.revertForAi(changeId,
                    "AUTO_REVERTED".equals(status) ? "AUTO_REVERT" : "REVERT", actor, approvalId, row.id(),
                    "AI 조치 되돌림 #" + row.id() + ": " + why, requestId);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("key", ap.key());
            info.put("from", ApiFormats.amount(ap.from()));
            info.put("to", ApiFormats.amount(ap.to()));
            info.put("change_id", ap.changeId());
            return recordRevert(row, status, actor, why, info, requestId);
        });
    }

    private AiStore.Action endSilence(AiStore.Action row, String status, String actor, String why, String requestId) {
        String silenceId = row.result().path("silence_id").asText();
        boolean live;
        try {
            live = alertmanager.silence(silenceId).map(x -> !"expired".equals(x.state())).orElse(false);
            if (live) {
                alertmanager.expireSilence(silenceId);
            }
        } catch (SourceException e) {
            if ("REVERTED".equals(status)) {
                throw upstream(e);
            }
            throw e;
        }
        return tx.execute(s -> {
            lockForRevert(row);
            incidents.bySilence(silenceId).ifPresent(i -> {
                incidents.clearSilence(i.id(), silenceId);
                incidents.event(i.id(), "UNSILENCED", "AUTO_REVERTED".equals(status) ? "AI" : "OPERATOR", actor,
                        "AI 보류 해제 #" + row.id(), Map.of("silence_id", silenceId, "action_id", row.id()));
            });
            return recordRevert(row, status, actor, why, Map.of("silence_id", silenceId, "was_active", live),
                    requestId);
        });
    }

    private AiStore.Action restoreBotRate(AiStore.Action row, String operator, String requestId) {
        BigDecimal previous = new BigDecimal(row.result().path("from").asText());
        BotRateControl.Applied ap = botRate.set(previous.doubleValue(), operator, requestId,
                Map.of("ai_action_revert", row.id()));
        return tx.execute(s -> {
            lockForRevert(row);
            return recordRevert(row, "REVERTED", operator, "사람의 되돌리기",
                    Map.of("to", ApiFormats.amount(previous), "apply_path", ap.applyPath()), requestId);
        });
    }

    /** 되돌리기 트랜잭션도 AI 실행과 같은 줄에 선다. 요청 행이 아직 되돌릴 수 있는 상태인지 잠근 뒤 본다. */
    private void lockForRevert(AiStore.Action row) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('msp-ai-exec', 0))").query(Integer.class).single();
        AiStore.Action locked = store.lockAction(row.id()).orElseThrow(AiExecutor::notFound);
        if (!revertible(locked)) {
            throw notOpen();
        }
    }

    private AiStore.Action recordRevert(AiStore.Action row, String status, String actor, String why,
                                        Map<String, Object> info, String requestId) {
        Instant now = clock.instant();
        Map<String, Object> revertInfo = new LinkedHashMap<>(info);
        revertInfo.put("cause", why);
        store.reverted(row.id(), status, actor, now, revertInfo);
        boolean auto = "AUTO_REVERTED".equals(status);
        incidents.event(incidentOf(row), auto ? "AUTO_REVERTED" : "REVERTED", auto ? "AI" : "OPERATOR", actor,
                (auto ? "AI 자동 되돌림 #" : "AI 조치 되돌림 #") + row.id() + " " + row.action().verb() + ": " + why,
                Map.of("action_id", row.id()));
        audit.record(actor, auto ? "ops.ai.action.auto_revert" : "ops.ai.action.revert", "ai-action:" + row.id(),
                requestId, null, "OK", Map.of("action_id", row.id(), "revert", revertInfo));
        return store.action(row.id()).orElseThrow();
    }

    /* ===== 끊긴 실행의 대조(정책 M7) ===== */

    /** 기동할 때 한 번. 남은 EXECUTING 이 있으면 자동 모드를 수동으로 돌리고 원천과 대조한다. */
    public void reconcileAtStartup() {
        List<AiStore.Action> stuck = store.stuck(clock.instant().plus(Duration.ofDays(1)));
        if (stuck.stream().anyMatch(a -> "EXECUTING".equals(a.status()))) {
            control.switchToManual("startup_executing", "기동 때 끝나지 않은 AI 실행이 있어 수동 전환");
        }
        for (AiStore.Action row : stuck) {
            reconcile(row, true);
        }
    }

    /** 주기마다. 2분 넘게 EXECUTING 인 요청과 UNKNOWN 요청을 다시 대조한다. */
    public void reconcileStuck() {
        for (AiStore.Action row : store.stuck(clock.instant().minus(RECONCILE_AFTER))) {
            reconcile(row, false);
        }
    }

    private void reconcile(AiStore.Action row, boolean startup) {
        String before = row.status();
        switch (row.action()) {
            case AiAction.Silence s -> reconcileSilence(row);
            case AiAction.BotLoad b when botRate.local() && startup ->
                    finishReconcile(row, "FAILED", Map.of("error", "restarted"), "재기동으로 효과 없음");
            case AiAction.BotLoad b when botRate.local() -> reconcileBotRate(row);
            default -> finishReconcile(row, "FAILED", Map.of("error", "interrupted"), "실행이 중간에 끊김");
        }
        AiStore.Action after = store.action(row.id()).orElseThrow();
        if ("UNKNOWN".equals(after.status()) && !"UNKNOWN".equals(before)) {
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.EXECUTION_UNKNOWN, Map.of("action_id", row.id())));
        }
    }

    private void reconcileSilence(AiStore.Action row) {
        if (alertmanager == null) {
            finishReconcile(row, "UNKNOWN", Map.of("error", "alertmanager_not_connected"), "대조할 원천이 없음");
            return;
        }
        List<AlertmanagerClient.SilenceEntry> silences;
        try {
            silences = alertmanager.silences();
        } catch (SourceException e) {
            finishReconcile(row, "UNKNOWN", Map.of("error", e.reason()), "Alertmanager 를 읽지 못함");
            return;
        }
        String tag = COMMENT_TAG + row.id();
        String fingerprint = ((AiAction.Silence) row.action()).fingerprint();
        for (AlertmanagerClient.SilenceEntry s : silences) {
            if (s.comment().endsWith(tag)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("silence_id", s.id());
                result.put("fingerprint", fingerprint);
                result.put("ends_at", ApiFormats.time(s.endsAt()));
                result.put("reconciled", true);
                tx.executeWithoutResult(t -> {
                    store.executed(row.id(), reconciledStatus(row), result, null, observeEnd(row));
                    incidents.openIncident(fingerprint).ifPresent(i -> incidents.recordSilence(i.id(), s.id(),
                            s.endsAt(), row.executedBy(), row.activation() != null ? "AI" : "OPERATOR"));
                    incidents.event(incidentOf(row), "OBSERVED", "SYSTEM", AiControl.SYSTEM,
                            "끊긴 AI 보류 실행을 대조해 결과를 채움 #" + row.id(), Map.of("action_id", row.id(),
                                    "silence_id", s.id()));
                });
                return;
            }
        }
        finishReconcile(row, "FAILED", Map.of("error", "not_applied"), "사일런스가 만들어지지 않음");
    }

    /** 같은 JVM 의 거버너가 원천이다. EXECUTING 에 먼저 적은 목표와 지금 목표가 같으면 적용된 것이다. */
    private void reconcileBotRate(AiStore.Action row) {
        BotRateControl.Current current = botRate.current();
        JsonNode pending = row.result();
        if (pending == null || current.targetOps() == null) {
            finishReconcile(row, "FAILED", Map.of("error", "interrupted"), "실행이 중간에 끊김");
            return;
        }
        BigDecimal intended = new BigDecimal(pending.path("to").asText());
        if (BigDecimal.valueOf(current.targetOps()).setScale(3, RoundingMode.HALF_UP).compareTo(intended) != 0) {
            finishReconcile(row, "FAILED", Map.of("error", "not_applied"), "목표 발행률이 바뀌지 않음");
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", pending.path("from").asText());
        result.put("to", pending.path("to").asText());
        result.put("apply_path", "local");
        result.put("reconciled", true);
        tx.executeWithoutResult(t -> {
            store.executed(row.id(), reconciledStatus(row), result, null, observeEnd(row));
            incidents.event(incidentOf(row), "OBSERVED", "SYSTEM", AiControl.SYSTEM,
                    "끊긴 AI 봇 부하 실행을 대조해 결과를 채움 #" + row.id(), Map.of("action_id", row.id()));
        });
    }

    private static String reconciledStatus(AiStore.Action row) {
        return row.activation() != null ? "AUTO_EXECUTED" : "APPROVED";
    }

    private Instant observeEnd(AiStore.Action row) {
        return (row.executedAt() == null ? clock.instant() : row.executedAt()).plus(AiPolicy.OBSERVE);
    }

    private void finishReconcile(AiStore.Action row, String status, Map<String, Object> result, String text) {
        tx.executeWithoutResult(t -> {
            store.failed(row.id(), status, result);
            incidents.event(incidentOf(row), "OBSERVED", "SYSTEM", AiControl.SYSTEM,
                    "끊긴 AI 실행 대조: " + text + " #" + row.id(), Map.of("action_id", row.id(), "status", status));
        });
    }

    /* ===== 공용 ===== */

    private AiStore.Action withSilenceLock(String fingerprint, Supplier<AiStore.Action> body) {
        return alertActions == null ? body.get() : alertActions.withFingerprintLock(fingerprint, body);
    }

    /** 경보를 원천에서 다시 읽는다(정책 C5·S4). 마지막 폴링 결과로 판정하면 이미 눌린 경보를 또 누를 수 있다. */
    private AlertmanagerClient.Alert liveAlert(String fingerprint) {
        if (alertmanager == null) {
            throw new Denied(new AiPolicy.Denial("C5", "source_not_connected", "Alertmanager 가 연결되지 않았습니다."));
        }
        List<AlertmanagerClient.Alert> all;
        try {
            all = alertmanager.alerts();
        } catch (SourceException e) {
            throw upstream(e);
        }
        for (AlertmanagerClient.Alert a : all) {
            if (!fingerprint.equals(a.fingerprint()) || (poller != null && poller.isHeartbeat(a))) {
                continue;
            }
            if (!a.silencedBy().isEmpty()) {
                throw new Denied(new AiPolicy.Denial("S4", "already_silenced", "이미 보류된 경보입니다."));
            }
            return a;
        }
        throw new Denied(new AiPolicy.Denial("C5", "alert_not_active", "경보가 더 이상 활성이 아닙니다."));
    }

    private void recordSilence(AiStore.Action row, long incidentId, Map<String, Object> result, Path path,
                               String actor) {
        String silenceId = (String) result.get("silence_id");
        Instant ends = Instant.parse((String) result.get("ends_at"));
        String actorType = path == Path.AUTO ? "AI" : "OPERATOR";
        incidents.recordSilence(incidentId, silenceId, ends, actor, actorType);
        AiAction.Silence s = (AiAction.Silence) row.action();
        incidents.event(incidentId, "SILENCED", actorType, actor, "AI 조치 보류 " + s.minutes() + "분 #" + row.id(),
                Map.of("silence_id", silenceId, "action_id", row.id()));
    }

    /** 요청을 처음 낸 판단의 사건. 타임라인에서 조치를 그 사건 아래에 보인다. */
    private Long incidentOf(AiStore.Action row) {
        return store.decision(row.firstDecisionId()).map(AiStore.Decision::incidentId).orElse(null);
    }

    private static String summary(Map<String, Object> result) {
        Object from = result.get("from");
        Object to = result.get("to");
        return from != null || to != null ? from + "→" + to : "";
    }

    private void auditDenied(String actor, String action, AiStore.Action row, AiPolicy.Denial d, String requestId) {
        try {
            audit.record(actor, action, "ai-action:" + row.id(), requestId, null, "DENIED",
                    Map.of("action_id", row.id(), "rule", d.rule(), "reason_code", d.code()));
        } catch (RuntimeException e) {
            log.warn("AI 조치 거부 감사 적재 실패 id={}: {}", row.id(), e.toString());
        }
    }

    private static ApiException upstream(SourceException e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "alertmanager");
        detail.put("reason", e.reason());
        if (e.outcomeUnknown()) {
            detail.put("outcome", "UNKNOWN");
        }
        return new ApiException(502, "msp_upstream_failed", "Alertmanager 호출에 실패했습니다.", detail, null);
    }

    static ApiException notFound() {
        return new ApiException(404, "not_found", "존재하지 않는 AI 조치 요청입니다.",
                Map.of("reason", "unknown_action"), null);
    }

    static ApiException notOpen() {
        return new ApiException(409, "ai_action_not_open", "이미 처리되었거나 처리할 수 없는 AI 조치 요청입니다.");
    }
}

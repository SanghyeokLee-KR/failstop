package kr.exchange.admin.msp;

import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.AdminPrincipal;
import kr.exchange.admin.AdminRole;
import kr.exchange.admin.ApprovalService;
import kr.exchange.api.common.ApiException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 사람의 경보 확인·보류·보류 해제(API 설계 §6-F, 설계 6-3).
 *
 * 보류는 두 단계다. BOT_OPS 이상은 티켓 없이 warning·info 경보를 30분까지 누른다. critical·등급 모름(other)
 * 경보나 30분 넘는 보류는 SUPER 와 승인 티켓(ops.alert.silence, 대상 {fingerprint}:{minutes})이 필요하다.
 * 경보를 오래 또는 critical 까지 누르는 것은 임계값을 둔하게 바꾸는 것과 같은 효과라 같은 통제를 건다.
 */
public class AlertActions {

    public static final String SILENCE_ACTION = "ops.alert.silence";
    // 티켓 없는 보류의 범위. AI 보류(정책 S2)와 같게 둔다.
    static final Duration UNTICKETED_MAX = Duration.ofMinutes(30);
    private static final int MAX_REASON = 200;

    public record Acked(String fingerprint, long incidentId, String ackedBy, Instant ackedAt) {
    }

    public record Silenced(String fingerprint, long incidentId, String silenceId, Instant endsAt) {
    }

    public record Unsilenced(String silenceId, long incidentId) {
    }

    private final AlertmanagerClient alertmanager;
    private final IncidentPoller poller;
    private final IncidentStore store;
    private final ApprovalService approvals;
    private final AdminAuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration humanMax;
    // 사일런스는 잠글 DB 행이 없는 외부 효과다. 같은 지문의 확인·생성·기록을 이 잠금 안에서 차례로 한다.
    private final Map<String, Object> fingerprintLocks = new ConcurrentHashMap<>();

    public AlertActions(AlertmanagerClient alertmanager, IncidentPoller poller, IncidentStore store,
                        ApprovalService approvals, AdminAuditService audit, TransactionTemplate tx, Clock clock,
                        Duration humanMax) {
        this.alertmanager = alertmanager;
        this.poller = poller;
        this.store = store;
        this.approvals = approvals;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.humanMax = humanMax;
    }

    /** 확인은 사건에 확인자와 시각을 적는다. Alertmanager 상태는 바꾸지 않는다(ADR 0032). */
    public Acked acknowledge(String fingerprint, AdminPrincipal principal, String requestId) {
        principal.require(AdminRole.BOT_OPS);
        requireFingerprint(fingerprint);
        if (poller.last().heartbeats().contains(fingerprint)) {
            throw excluded();
        }
        Instant now = clock.instant();
        return tx.execute(status -> {
            IncidentStore.Incident incident = store.lockOpenIncident(fingerprint).orElseThrow(AlertActions::notActive);
            store.acknowledge(incident.id(), principal.operatorId(), now);
            store.event(incident.id(), "ACKED", "OPERATOR", principal.operatorId(), "확인", Map.of());
            audit.record(principal.operatorId(), "msp.alert.ack", fingerprint, requestId, null, "OK",
                    Map.of("incident_id", incident.id()));
            return new Acked(fingerprint, incident.id(), principal.operatorId(), now);
        });
    }

    /**
     * 사람 보류. 매처는 그 경보의 라벨 묶음 전체를 정확히 맞춘다. 티켓 소각, 사일런스 생성, 사건 기록이
     * 한 트랜잭션이라 Alertmanager 호출이 실패하면 티켓이 남는다.
     */
    public Silenced silence(String fingerprint, Integer minutes, String reason, String approvalToken,
                            AdminPrincipal principal, String requestId) {
        principal.require(AdminRole.BOT_OPS);
        requireFingerprint(fingerprint);
        if (minutes == null || minutes < 1 || minutes > humanMax.toMinutes()) {
            throw new ApiException(400, "invalid_parameter", "minutes 는 1~" + humanMax.toMinutes() + " 정수여야 합니다.",
                    Map.of("constraint", "range", "field", "minutes"), null);
        }
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.codePointCount(0, why.length()) > MAX_REASON) {
            throw new ApiException(400, "invalid_parameter", "reason 은 1~" + MAX_REASON + "자여야 합니다.",
                    Map.of("constraint", "length", "field", "reason"), null);
        }
        return withFingerprintLock(fingerprint, () -> {
            AlertmanagerClient.Alert alert = activeAlert(fingerprint);
            String severity = IncidentPoller.severity(alert.labels());
            boolean ticketed = !("warning".equals(severity) || "info".equals(severity))
                    || minutes > UNTICKETED_MAX.toMinutes();
            if (ticketed) {
                principal.require(AdminRole.SUPER);
            }
            if (!alert.silencedBy().isEmpty()) {
                throw new ApiException(409, "alert_already_silenced", "이미 사일런스에 눌린 경보입니다.",
                        Map.of("silence_ids", alert.silencedBy()), null);
            }
            long incidentId = poller.record(alert);
            Instant now = clock.instant();
            Instant endsAt = now.plus(Duration.ofMinutes(minutes));
            Silenced silenced = tx.execute(status -> {
                Long approvalId = ticketed
                        ? approvals.consume(principal.operatorId(), approvalToken, SILENCE_ACTION,
                        fingerprint + ":" + minutes)
                        : null;
                String silenceId = createSilence(alert, now, endsAt, principal.operatorId(),
                        why + " · msp-incident:" + incidentId);
                store.recordSilence(incidentId, silenceId, endsAt, principal.operatorId(), "OPERATOR");
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("silence_id", silenceId);
                if (approvalId != null) {
                    ref.put("approval_id", approvalId);
                }
                store.event(incidentId, "SILENCED", "OPERATOR", principal.operatorId(),
                        "보류 " + minutes + "분: " + why, ref);
                audit.record(principal.operatorId(), "msp.alert.silence", fingerprint, requestId, approvalId, "OK",
                        Map.of("incident_id", incidentId, "silence_id", silenceId, "minutes", minutes,
                                "severity", severity));
                return new Silenced(fingerprint, incidentId, silenceId, endsAt);
            });
            poller.pollSoon();
            return silenced;
        });
    }

    /** 관제센터가 만든 사일런스만 끝낸다. 이미 끝났거나 없으면 호출 없이 사건의 보류 칸만 비운다. */
    public Unsilenced unsilence(String silenceId, AdminPrincipal principal, String requestId) {
        principal.require(AdminRole.BOT_OPS);
        if (silenceId == null || !AlertmanagerClient.SILENCE_ID.matcher(silenceId).matches()) {
            throw new ApiException(400, "invalid_parameter", "사일런스 id 형식 오류",
                    Map.of("constraint", "format", "field", "silence_id"), null);
        }
        IncidentStore.Incident incident = store.bySilence(silenceId).orElseThrow(() -> new ApiException(404,
                "not_found", "관제센터가 만든 사일런스가 아닙니다.", Map.of("reason", "silence_not_managed"), null));
        return withFingerprintLock(incident.fingerprint(), () -> {
            Optional<AlertmanagerClient.Silence> current = upstream(() -> alertmanager.silence(silenceId));
            boolean live = current.isPresent() && !"expired".equals(current.get().state());
            if (live) {
                upstream(() -> {
                    alertmanager.expireSilence(silenceId);
                    return null;
                });
            }
            tx.executeWithoutResult(status -> {
                store.clearSilence(incident.id(), silenceId);
                store.event(incident.id(), "UNSILENCED", "OPERATOR", principal.operatorId(),
                        live ? "보류 해제" : "보류 해제(이미 끝난 사일런스)", Map.of("silence_id", silenceId));
                audit.record(principal.operatorId(), "msp.silence.expire", silenceId, requestId, null, "OK",
                        Map.of("incident_id", incident.id(), "was_active", live));
            });
            poller.pollSoon();
            return new Unsilenced(silenceId, incident.id());
        });
    }

    /** 같은 지문의 사일런스 조작을 한 줄로 세운다. 관리 JVM 이 배포마다 한 대라는 전제다(설계 7-7). */
    public <T> T withFingerprintLock(String fingerprint, Supplier<T> body) {
        synchronized (fingerprintLocks.computeIfAbsent(fingerprint, k -> new Object())) {
            return body.get();
        }
    }

    /** 지금 활성인 경보를 원천에서 다시 읽는다. 마지막 폴링 결과로 판정하면 이미 눌린 경보를 또 누를 수 있다. */
    private AlertmanagerClient.Alert activeAlert(String fingerprint) {
        for (AlertmanagerClient.Alert alert : upstream(alertmanager::alerts)) {
            if (!fingerprint.equals(alert.fingerprint())) {
                continue;
            }
            if (poller.isHeartbeat(alert)) {
                throw excluded();
            }
            return alert;
        }
        throw notActive();
    }

    private String createSilence(AlertmanagerClient.Alert alert, Instant startsAt, Instant endsAt, String by,
                                 String comment) {
        return upstream(() -> alertmanager.createSilence(alert.labels(), startsAt, endsAt, by, comment));
    }

    /** 원천 호출 실패는 502 다. 응답을 받지 못해 적용 여부를 모르면 detail.outcome=UNKNOWN 을 싣는다. */
    private static <T> T upstream(Supplier<T> call) {
        try {
            return call.get();
        } catch (SourceException e) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("source", "alertmanager");
            detail.put("reason", e.reason());
            if (e.outcomeUnknown()) {
                detail.put("outcome", "UNKNOWN");
            }
            throw new ApiException(502, "msp_upstream_failed", "Alertmanager 호출에 실패했습니다.", detail, null);
        }
    }

    private static void requireFingerprint(String fingerprint) {
        if (fingerprint == null || !IncidentStore.FINGERPRINT.matcher(fingerprint).matches()) {
            throw new ApiException(400, "invalid_parameter", "지문 형식 오류",
                    Map.of("constraint", "format", "field", "fingerprint"), null);
        }
    }

    private static ApiException notActive() {
        return new ApiException(404, "not_found", "활성 경보가 아닙니다.", Map.of("reason", "alert_not_active"), null);
    }

    private static ApiException excluded() {
        return new ApiException(404, "not_found", "생존 신호 경보는 확인·보류 대상이 아닙니다.",
                Map.of("reason", "alert_excluded"), null);
    }
}

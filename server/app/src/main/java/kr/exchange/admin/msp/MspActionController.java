package kr.exchange.admin.msp;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.AdminPrincipal;
import kr.exchange.admin.AdminRequestContext;
import kr.exchange.admin.AdminRole;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 관제센터 조작(API 설계 §6-F). 임계값 변경·되돌리기는 SUPER 와 승인 티켓, 경보 확인과 30분 이하
 * warning·info 보류는 BOT_OPS 이상이다. operator_id 의 권위는 바디가 아니라 인증 주체다(§3-6).
 */
@RestController
@RequestMapping("/admin/v1/msp")
@ConditionalOnProperty(name = {"exchange.admin.enabled", "exchange.msp.enabled"}, havingValue = "true")
public class MspActionController {

    public record ThresholdBody(String operator_id, String approval_token, String value, String reason) {
    }

    public record RevertBody(String operator_id, String approval_token, String reason) {
    }

    public record SilenceBody(String operator_id, String approval_token, Integer minutes, String reason) {
    }

    private final OpsThresholdService thresholds;
    private final Optional<AlertActions> alerts;
    private final AdminAuditService audit;

    /** alerts 는 Alertmanager 주소를 준 배포에만 있다. 없으면 확인·보류는 원천 연결 전으로 거부한다. */
    public MspActionController(OpsThresholdService thresholds, Optional<AlertActions> alerts,
                               AdminAuditService audit) {
        this.thresholds = thresholds;
        this.alerts = alerts;
        this.audit = audit;
    }

    @PostMapping("/thresholds/{key}")
    public Map<String, Object> setThreshold(@PathVariable String key,
                                            @RequestBody(required = false) ThresholdBody body,
                                            HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        ThresholdBody b = body == null ? new ThresholdBody(null, null, null, null) : body;
        OpsThresholdService.Applied applied = thresholds.set(key, b.value(), b.reason(), principal.operatorId(),
                b.approval_token(), AdminRequestContext.requestId(request));
        return appliedResponse(applied);
    }

    @PostMapping("/thresholds/changes/{id}/revert")
    public Map<String, Object> revertThreshold(@PathVariable long id,
                                               @RequestBody(required = false) RevertBody body,
                                               HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        RevertBody b = body == null ? new RevertBody(null, null, null) : body;
        OpsThresholdService.Applied applied = thresholds.revert(id, b.reason(), principal.operatorId(),
                b.approval_token(), AdminRequestContext.requestId(request));
        Map<String, Object> out = appliedResponse(applied);
        out.put("revert_of", applied.revertOf());
        return out;
    }

    @PostMapping("/alerts/{fingerprint}/ack")
    public Map<String, Object> acknowledge(@PathVariable String fingerprint, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        AlertActions.Acked acked = alertActions().acknowledge(fingerprint, principal,
                AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("fingerprint", acked.fingerprint());
        out.put("incident_id", acked.incidentId());
        out.put("acked_by", acked.ackedBy());
        out.put("acked_at", ApiFormats.time(acked.ackedAt()));
        return out;
    }

    @PostMapping("/alerts/{fingerprint}/silence")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> silence(@PathVariable String fingerprint,
                                       @RequestBody(required = false) SilenceBody body,
                                       HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        SilenceBody b = body == null ? new SilenceBody(null, null, null, null) : body;
        AlertActions.Silenced silenced = alertActions().silence(fingerprint, b.minutes(), b.reason(),
                b.approval_token(), principal, AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("fingerprint", silenced.fingerprint());
        out.put("incident_id", silenced.incidentId());
        out.put("silence_id", silenced.silenceId());
        out.put("ends_at", ApiFormats.time(silenced.endsAt()));
        return out;
    }

    @DeleteMapping("/silences/{silenceId}")
    public Map<String, Object> unsilence(@PathVariable String silenceId, HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.BOT_OPS);
        AlertActions.Unsilenced done = alertActions().unsilence(silenceId, principal,
                AdminRequestContext.requestId(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("silence_id", done.silenceId());
        out.put("incident_id", done.incidentId());
        return out;
    }

    /** Databasus 의 백업 실행 API 를 확인하지 못해 연결 전 스텁이다. 성공을 지어내지 않는다(설계 7-7). */
    @PostMapping("/ops/backup")
    public Map<String, Object> backupNow(HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.SUPER);
        audit.record(principal.operatorId(), "msp.ops.backup", "databasus", AdminRequestContext.requestId(request),
                null, "DENIED", Map.of("reason", "msp_source_not_connected"));
        throw notConnected("databasus");
    }

    private AlertActions alertActions() {
        return alerts.orElseThrow(() -> notConnected("alertmanager"));
    }

    private static ApiException notConnected(String source) {
        return new ApiException(409, "msp_source_not_connected", "관제센터 원천이 연결되지 않았습니다: " + source,
                Map.of("source", source), null);
    }

    private static Map<String, Object> appliedResponse(OpsThresholdService.Applied applied) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("key", applied.key());
        out.put("from", applied.from() == null ? null : ApiFormats.amount(applied.from()));
        out.put("to", applied.to() == null ? null : ApiFormats.amount(applied.to()));
        out.put("version", applied.version());
        out.put("change_id", applied.changeId());
        out.put("via", applied.via());
        out.put("approval_id", applied.approvalId());
        return out;
    }
}

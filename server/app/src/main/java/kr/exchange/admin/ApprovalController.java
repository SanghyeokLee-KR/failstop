package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2인 승인 발급(§3-6). 인증된 운영자(approver)가 다른 운영자(requester)의 파괴 액션에
 * 대해 approval_token을 발급한다. approver 신원은 세션의 mTLS 유래 operator_id이며,
 * requester와 같으면 반려된다(반결탁).
 */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class ApprovalController {

    public record IssueRequest(String requester_operator_id, String action, String target) {
    }

    private static final Set<String> STATES = Set.of("ISSUED", "CONSUMED", "EXPIRED", "REJECTED");

    private final ApprovalService approvals;
    private final AdminAuditService audit;

    public ApprovalController(ApprovalService approvals, AdminAuditService audit) {
        this.approvals = approvals;
        this.audit = audit;
    }

    @PostMapping("/approvals")
    public Map<String, Object> issue(@RequestBody IssueRequest body, HttpServletRequest request) {
        AdminPrincipal approver = AdminRequestContext.principal(request);
        if (body == null || body.requester_operator_id() == null || body.action() == null) {
            throw new ApiException(400, "invalid_parameter",
                    "requester_operator_id와 action이 필요합니다.");
        }
        ApprovalService.Issued issued = approvals.issue(
                approver.operatorId(), body.requester_operator_id(),
                body.action(), body.target());
        audit.record(approver.operatorId(), "POST /admin/v1/approvals", body.action(),
                AdminRequestContext.requestId(request), issued.approvalId(), "OK",
                Map.of("requester", body.requester_operator_id(),
                        "target", body.target() == null ? "" : body.target()));
        return Map.of(
                "schema_version", "1.0",
                "approval_token", issued.approvalToken(),
                "approval_id", issued.approvalId(),
                "action", body.action(),
                "target", body.target() == null ? "" : body.target(),
                "expires_at", ApiFormats.time(java.time.Instant.ofEpochSecond(issued.expiresAt())));
    }

    /**
     * 승인 원장 조회(VIEWER+). state 필터·상한 500. 응답은 approval_token을 포함하지 않는다.
     * 필터 인증은 코스 감사를 남기므로 조회 자체는 별도 감사 적재를 하지 않는다.
     */
    @GetMapping("/approvals")
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) String state,
                                    @RequestParam(defaultValue = "100") int limit) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        if (state != null && !state.isBlank() && !STATES.contains(state)) {
            throw new ApiException(400, "invalid_parameter",
                    "state는 ISSUED, CONSUMED, EXPIRED, REJECTED 중 하나입니다.");
        }
        int capped = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> data = approvals.list(state, capped);
        return Map.of("schema_version", "1.0", "data", data);
    }

    /**
     * 승인 거부(VIEWER+, de-escalation). 소유·반결탁·단회 전이는 ApprovalService.reject가
     * FOR UPDATE로 강제한다. approval_token은 소각하지 않는다(ISSUED to REJECTED만).
     */
    @PostMapping("/approvals/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal caller = AdminRequestContext.principal(request);
        caller.require(AdminRole.VIEWER);
        approvals.reject(caller.operatorId(), id);
        // 안정 액션 코드로 감사한다. 필터의 경로 기반 코스 감사(POST .../{id}/reject)와 분리.
        audit.record(caller.operatorId(), "approvals.reject",
                String.valueOf(id), AdminRequestContext.requestId(request), id, "OK", Map.of());
        return Map.of("schema_version", "1.0", "approval_id", id, "state", "REJECTED");
    }
}

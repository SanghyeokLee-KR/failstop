package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
}

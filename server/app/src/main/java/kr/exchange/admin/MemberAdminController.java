package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.CursorPages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 회원 운영(API §6-E). 목록·상세는 VIEWER, 상태 전이는 MEMBER_MOD다.
 *
 * 정지·복구는 가역이라 operator_id만 받고, 폐쇄는 비가역이라 approval_token을 소각한다
 * (§3-6 완결 열거 v2.32 등재분). 전이의 부수 효과(주문 취소·세션 폐기)는 서비스가 정하고
 * 여기서는 무엇이 정리됐는지 응답과 감사에 그대로 싣는다.
 */
@RestController
@RequestMapping("/admin/v1/members")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class MemberAdminController {

    public record CloseBody(String approval_token) {
    }

    private final MemberAdminService members;
    private final ApprovalService approvals;
    private final AdminAuditService audit;

    public MemberAdminController(MemberAdminService members, ApprovalService approvals,
                                 AdminAuditService audit) {
        this.members = members;
        this.approvals = approvals;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "cursor", required = false) String cursor,
                                    @RequestParam(value = "limit", defaultValue = "50") int limit,
                                    HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        int capped = CursorPages.cap(limit, MemberAdminService.MAX_LIMIT);
        // 한 건 더 읽어 다음 쪽 존재를 판단한다(신고·감사 큐와 같은 규약)
        CursorPages.Page<Map<String, Object>> page = CursorPages.slice(
                members.list(status, CursorPages.decode(cursor), capped + 1), capped);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", "1.0");
        body.put("data", page.data());
        body.put("pagination", CursorPages.pagination(page));
        return body;
    }

    @GetMapping("/{externalId}")
    public Map<String, Object> detail(@PathVariable String externalId,
                                      HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", "1.0");
        body.putAll(members.detail(externalId));
        return body;
    }

    @PostMapping("/{externalId}/suspend")
    public Map<String, Object> suspend(@PathVariable String externalId,
                                       HttpServletRequest request) {
        return reversible(externalId, request, "members.suspend",
                () -> members.suspend(externalId));
    }

    @PostMapping("/{externalId}/reinstate")
    public Map<String, Object> reinstate(@PathVariable String externalId,
                                         HttpServletRequest request) {
        return reversible(externalId, request, "members.reinstate",
                () -> members.reinstate(externalId));
    }

    /**
     * 폐쇄(§3-6 필수). 토큰을 먼저 소각한 뒤 전이한다. 소각이 실패하면 아무것도 하지 않고,
     * 전이가 실패하면 토큰은 이미 타서 재발급이 필요하다. 되돌릴 수 없는 액션이라
     * 승인을 남긴 채 실행이 반복되는 쪽보다 재발급이 안전하다.
     */
    @PostMapping("/{externalId}/close")
    public Map<String, Object> close(@PathVariable String externalId,
                                     @RequestBody(required = false) CloseBody body,
                                     HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.MEMBER_MOD);
        String token = body == null ? null : body.approval_token();
        long approvalId = approvals.consume(principal.operatorId(), token,
                "members.close", externalId);
        MemberAdminService.Transition t = members.close(externalId);
        audit.record(principal.operatorId(), "members.close", externalId,
                AdminRequestContext.requestId(request), approvalId, "OK", detail(t));
        if (t.pendingOrders() > 0) {
            // 상태 전이·세션 폐기·일부 취소는 이미 커밋됐고 감사도 위에서 남겼다. 취소가 저널에 닿지 못한 주문은 아직
            // 열려 있어 폐쇄 계정에 체결이 생길 수 있으므로 200 으로 덮지 않는다(API 설계 §6-E v2.53). 새 승인으로
            // 폐쇄를 다시 부르면 남은 주문만 다시 취소한다(CLOSED->CLOSED 허용, 이미 쓴 토큰은 재사용할 수 없다).
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("status", t.status());
            detail.put("canceled_orders", t.canceledOrders());
            detail.put("pending_orders", t.pendingOrders());
            detail.put("approval_id", approvalId);
            throw new ApiException(409, "orders_cancel_incomplete",
                    "폐쇄는 확정됐으나 미체결 주문 일부를 취소하지 못했습니다. 새 승인으로 폐쇄를 다시 요청하세요.",
                    detail, null);
        }
        Map<String, Object> out = response(t);
        out.put("approval_id", approvalId);
        return out;
    }

    private Map<String, Object> reversible(String externalId, HttpServletRequest request,
                                           String action,
                                           java.util.function.Supplier<MemberAdminService.Transition> op) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.MEMBER_MOD);
        MemberAdminService.Transition t = op.get();
        audit.record(principal.operatorId(), action, externalId,
                AdminRequestContext.requestId(request), null, "OK", detail(t));
        return response(t);
    }

    private static Map<String, Object> detail(MemberAdminService.Transition t) {
        return Map.of("status", t.status(), "canceled_orders", t.canceledOrders(),
                "revoked_sessions", t.revokedSessions(), "pending_orders", t.pendingOrders());
    }

    private static Map<String, Object> response(MemberAdminService.Transition t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", "1.0");
        out.put("external_id", t.externalId());
        out.put("status", t.status());
        out.put("canceled_orders", t.canceledOrders());
        out.put("revoked_sessions", t.revokedSessions());
        return out;
    }
}

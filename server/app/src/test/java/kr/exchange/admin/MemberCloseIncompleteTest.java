package kr.exchange.admin;

import kr.exchange.api.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 폐쇄의 미완료 취소 알림(ADR 0034 결정 15의 관리 경로 규칙을 단일 리더에 먼저 적용).
 *
 * 취소가 저널에 닿지 못한 주문이 남으면 폐쇄는 200 으로 끝나지 않는다. 상태 전이는 이미 커밋됐으므로 감사를 먼저 남긴
 * 뒤 409 로 남은 수를 알린다. 남은 주문이 없으면 종전과 같은 200 이다.
 */
class MemberCloseIncompleteTest {

    private static final String MEMBER = "9f2c0000-0000-0000-0000-000000000001";

    private final MemberAdminService members = mock(MemberAdminService.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final MemberAdminController controller = new MemberAdminController(members, approvals, audit);

    @Test
    void pendingCancelsTurnCloseInto409AfterAudit() {
        when(approvals.consume("op-mod", "apv-1", "members.close", MEMBER)).thenReturn(77L);
        when(members.close(MEMBER)).thenReturn(new MemberAdminService.Transition(MEMBER, "CLOSED", 2, 1, 1));

        ApiException incomplete = assertThrows(ApiException.class,
                () -> controller.close(MEMBER, new MemberAdminController.CloseBody("apv-1"), request()));

        assertEquals(409, incomplete.status());
        assertEquals("orders_cancel_incomplete", incomplete.name());
        assertEquals(1, incomplete.detail().get("pending_orders"));
        assertEquals(2, incomplete.detail().get("canceled_orders"));
        assertEquals(77L, incomplete.detail().get("approval_id"));
        verify(audit).record(eq("op-mod"), eq("members.close"), eq(MEMBER), any(), eq(77L), eq("OK"),
                argThat(detail -> Integer.valueOf(1).equals(detail.get("pending_orders"))));
    }

    @Test
    void closeWithoutPendingCancelsStays200() {
        when(approvals.consume("op-mod", "apv-2", "members.close", MEMBER)).thenReturn(78L);
        when(members.close(MEMBER)).thenReturn(new MemberAdminService.Transition(MEMBER, "CLOSED", 3, 1, 0));

        Map<String, Object> body = controller.close(MEMBER, new MemberAdminController.CloseBody("apv-2"),
                request());

        assertEquals("CLOSED", body.get("status"));
        assertEquals(3, body.get("canceled_orders"));
        assertEquals(78L, body.get("approval_id"));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AdminPrincipal.REQUEST_ATTRIBUTE,
                new AdminPrincipal("op-mod", "jti-1", Set.of(AdminRole.MEMBER_MOD)));
        return request;
    }
}

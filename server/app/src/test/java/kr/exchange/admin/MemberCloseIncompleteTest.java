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

    /**
     * 정지 마켓의 미체결도 미완료다(API 설계 v2.54 §6-E, ADR 0034 결정 15, AI 선택·사용자 확인 필요). 409 detail 과 감사
     * detail 에 사유별 수가 실린다. 0 인 사유는 싣지 않는다.
     */
    @Test
    void suspendedMarketOrdersTurnCloseInto409WithReasons() {
        when(approvals.consume("op-mod", "apv-3", "members.close", MEMBER)).thenReturn(79L);
        when(members.close(MEMBER)).thenReturn(new MemberAdminService.Transition(MEMBER, "CLOSED", 1, 1, 2,
                Map.of("market_suspended", 2)));

        ApiException incomplete = assertThrows(ApiException.class,
                () -> controller.close(MEMBER, new MemberAdminController.CloseBody("apv-3"), request()));

        assertEquals(409, incomplete.status());
        assertEquals("orders_cancel_incomplete", incomplete.name());
        assertEquals(2, incomplete.detail().get("pending_orders"));
        assertEquals(Map.of("market_suspended", 2), incomplete.detail().get("pending_reasons"));
        verify(audit).record(eq("op-mod"), eq("members.close"), eq(MEMBER), any(), eq(79L), eq("OK"),
                argThat(detail -> Map.of("market_suspended", 2).equals(detail.get("pending_reasons"))));
    }

    /** 서비스는 일괄 취소의 incomplete()(503 + 정지 마켓 422)를 미완료 수로, 사유별 수를 pendingReasons 로 넘긴다. */
    @Test
    void serviceCountsSuspendedAndUnsubmittedAsPendingReasons() throws Exception {
        kr.exchange.engine.lease.PartitionLeaseDb db = kr.exchange.engine.lease.PartitionLeaseDb.create("close_reasons");
        try (com.zaxxer.hikari.HikariDataSource pool = db.ownerPool()) {
            org.springframework.jdbc.core.simple.JdbcClient jdbc =
                    org.springframework.jdbc.core.simple.JdbcClient.create(pool);
            long id = new kr.exchange.ledger.AccountBootstrap(jdbc).user("closing@member.test");
            String external = jdbc.sql("SELECT external_id::text FROM users WHERE id = ?").param(id)
                    .query(String.class).single();
            kr.exchange.api.order.OrderGateway gateway = new kr.exchange.api.order.OrderGateway(jdbc, null, null, null) {
                @Override
                public BulkCancelResult cancelAll(long userId, String market, String origin) {
                    assertEquals(kr.exchange.api.order.OrderCancelHandoff.ORIGIN_MEMBER_CLOSE, origin);
                    return new BulkCancelResult(java.util.List.of("a"), java.util.List.of(), 1, 2);
                }
            };
            MemberAdminService service = new MemberAdminService(jdbc,
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.support.JdbcTransactionManager(pool)), gateway);

            MemberAdminService.Transition t = service.close(external);

            assertEquals("CLOSED", t.status());
            assertEquals(1, t.canceledOrders());
            assertEquals(3, t.pendingOrders(), "정지 마켓 미체결을 미완료로 세지 않았다");
            assertEquals(Map.of("service_unavailable", 1, "market_suspended", 2), t.pendingReasons());
            assertEquals("CLOSED", jdbc.sql("SELECT status FROM users WHERE id = ?").param(id)
                    .query(String.class).single());
        }
    }

    /**
     * 마켓 접수 경합으로 저널에 닿지 못한 취소(429 bucket market)도 미완료다(API 설계 v2.55 §6-E, §2-5-b). pending_reasons 에
     * too_many_requests 로 실린다.
     */
    @Test
    void marketContentionCancelsCountAsTooManyRequests() throws Exception {
        kr.exchange.engine.lease.PartitionLeaseDb db = kr.exchange.engine.lease.PartitionLeaseDb.create("close_contention");
        try (com.zaxxer.hikari.HikariDataSource pool = db.ownerPool()) {
            org.springframework.jdbc.core.simple.JdbcClient jdbc =
                    org.springframework.jdbc.core.simple.JdbcClient.create(pool);
            long id = new kr.exchange.ledger.AccountBootstrap(jdbc).user("contended@member.test");
            String external = jdbc.sql("SELECT external_id::text FROM users WHERE id = ?").param(id)
                    .query(String.class).single();
            kr.exchange.api.order.OrderGateway gateway = new kr.exchange.api.order.OrderGateway(jdbc, null, null, null) {
                @Override
                public BulkCancelResult cancelAll(long userId, String market, String origin) {
                    return new BulkCancelResult(java.util.List.of("a"), java.util.List.of(), 1, 2, 3);
                }
            };
            MemberAdminService service = new MemberAdminService(jdbc,
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.support.JdbcTransactionManager(pool)), gateway);

            MemberAdminService.Transition t = service.close(external);

            assertEquals(6, t.pendingOrders(), "경합으로 남은 주문을 미완료로 세지 않았다");
            assertEquals(Map.of("service_unavailable", 1, "too_many_requests", 3, "market_suspended", 2),
                    t.pendingReasons());
        }
    }

    /**
     * 파티션 인계의 세 사유도 미완료다(API 설계 v2.57 §6-E, S5 설계 4-4). 소유 노드가 기다림 안에 시작하지 않은 몫
     * (partition_not_started), 시작했으나 끝내지 않은 몫(partition_pending), 소유 노드의 예기치 않은 오류(server_error)가
     * pending_reasons 에 그 이름으로 실리고 합이 미완료 수다.
     */
    @Test
    void handoffReasonsCountAsPending() throws Exception {
        kr.exchange.engine.lease.PartitionLeaseDb db = kr.exchange.engine.lease.PartitionLeaseDb.create("close_handoff");
        try (com.zaxxer.hikari.HikariDataSource pool = db.ownerPool()) {
            org.springframework.jdbc.core.simple.JdbcClient jdbc =
                    org.springframework.jdbc.core.simple.JdbcClient.create(pool);
            long id = new kr.exchange.ledger.AccountBootstrap(jdbc).user("handoff@member.test");
            String external = jdbc.sql("SELECT external_id::text FROM users WHERE id = ?").param(id)
                    .query(String.class).single();
            kr.exchange.api.order.OrderGateway gateway = new kr.exchange.api.order.OrderGateway(jdbc, null, null, null) {
                @Override
                public BulkCancelResult cancelAll(long userId, String market, String origin) {
                    return new BulkCancelResult(java.util.List.of("a"), java.util.List.of(), 0, 0, 0, 4, 2, 1, 0,
                            false);
                }
            };
            MemberAdminService service = new MemberAdminService(jdbc,
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.support.JdbcTransactionManager(pool)), gateway);

            MemberAdminService.Transition t = service.close(external);

            assertEquals(7, t.pendingOrders(), "인계 사유를 미완료로 세지 않았다");
            assertEquals(Map.of("partition_not_started", 4, "partition_pending", 2, "server_error", 1),
                    t.pendingReasons());
            assertEquals(java.util.List.of("partition_not_started", "partition_pending", "server_error"),
                    java.util.List.copyOf(t.pendingReasons().keySet()), "사유 순서가 고정되지 않았다");
        }
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

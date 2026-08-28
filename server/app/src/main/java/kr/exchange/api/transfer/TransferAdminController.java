package kr.exchange.api.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.AdminPrincipal;
import kr.exchange.admin.AdminRequestContext;
import kr.exchange.admin.AdminRole;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 출금 승인 큐(API 설계 §4-E). 관리 평면이라 mTLS + 관리 세션을 지난다.
 *
 * 2인 승인(ApprovalService)을 걸지 않는다. 그 장치는 운영자끼리의 상호 견제가 필요한
 * 파괴적 액션(마켓 정지, 봇 대량 조작)용이고, 출금 승인은 사용자 요청을 운영자가 처리하는
 * 작업 큐다. 요청 주체가 이미 운영자가 아니라 사용자라 결탁 구조가 다르다. 대신 처리자와
 * 시각을 행에 남기고 감사 로그에 적재한다. 1회 한도가 MAX_TRANSFER_KRW로 묶여 있어
 * 단일 승인의 손해 상한도 유계다.
 */
@RestController
/* 관리 평면은 exchange.admin.enabled=true일 때만 켜진다. 이 조건이 없으면 컨트롤러가 항상
   스캔돼 AdminAuditService를 못 찾고 컨텍스트 로드가 통째로 실패한다. 거래 평면 테스트가
   관리 빈에 묶이면 안 된다. kr.exchange.admin 패키지의 컨트롤러들과 같은 규약이다 */
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
@RequestMapping("/admin/v1/transfers")
public class TransferAdminController {

    private static final Set<String> QUEUE_STATUS = Set.of("REQUESTED", "COMPLETED", "CANCELED");
    private static final int MAX_LIMIT = 200;

    private final TransferService transfers;
    private final AdminAuditService audit;

    public TransferAdminController(TransferService transfers, AdminAuditService audit) {
        this.transfers = transfers;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(defaultValue = "REQUESTED") String status,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "100") int limit) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        if (!QUEUE_STATUS.contains(status)) {
            throw new ApiException(400, "invalid_parameter",
                    "status는 REQUESTED, COMPLETED, CANCELED 중 하나");
        }
        int capped = CursorPages.cap(limit, MAX_LIMIT);
        // 한 건 더 읽어 다음 쪽 존재를 판단한다. COMPLETED·CANCELED는 처리 뒤에도 남아
        // 영구 누적이라 고정 상한으로 자르면 그 밖의 이력에 도달할 방법이 없다
        CursorPages.Page<Map<String, Object>> page = CursorPages.slice(
                transfers.listQueue(status, CursorPages.decode(cursor), capped + 1), capped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("data", page.data());
        out.put("pagination", CursorPages.pagination(page));
        return out;
    }

    @PostMapping("/{externalId}/approve")
    public Map<String, Object> approve(HttpServletRequest request,
                                       @PathVariable("externalId") String externalId) {
        AdminPrincipal caller = AdminRequestContext.principal(request);
        caller.require(AdminRole.MEMBER_MOD);
        Map<String, Object> view = transfers.approve(externalId, caller.operatorId());
        record(caller, request, "approve", externalId, view);
        return envelope(view);
    }

    @PostMapping("/{externalId}/reject")
    public Map<String, Object> reject(HttpServletRequest request,
                                      @PathVariable("externalId") String externalId,
                                      @RequestBody(required = false) JsonNode body) {
        AdminPrincipal caller = AdminRequestContext.principal(request);
        caller.require(AdminRole.MEMBER_MOD);
        String reason = body == null || body.get("reason") == null
                ? null : body.get("reason").asText();
        if (reason == null || reason.isBlank()) {
            // 거절 사유는 사용자 내역 화면에 그대로 뜬다. 사유 없는 거절은 사용자가
            // 무엇을 고쳐 다시 요청해야 하는지 알 수 없게 만든다
            throw new ApiException(400, "invalid_parameter", "거절 사유 필요");
        }
        Map<String, Object> view = transfers.reject(externalId, caller.operatorId(), reason);
        record(caller, request, "reject", externalId, view);
        return envelope(view);
    }

    /** 자금을 움직인 결정이라 금액까지 남긴다. 사후에 "얼마를 누가 통과시켰나"가 감사 질문이다 */
    private void record(AdminPrincipal caller, HttpServletRequest request,
                        String action, String externalId, Map<String, Object> view) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("direction", view.get("direction"));
        detail.put("asset", view.get("asset"));
        detail.put("amount", view.get("amount"));
        detail.put("status", view.get("status"));
        audit.record(caller.operatorId(), "POST /admin/v1/transfers/" + action, externalId,
                AdminRequestContext.requestId(request), null, "OK", detail);
    }

    private static Map<String, Object> envelope(Map<String, Object> view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.putAll(view);
        return out;
    }
}

package kr.exchange.api.support;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.AdminPrincipal;
import kr.exchange.admin.AdminRequestContext;
import kr.exchange.admin.AdminRole;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 문의 답변 큐(API 설계 §4-G). 관리 평면이라 mTLS + 관리 세션을 지난다.
 *
 * 답변 내용은 감사에 싣지 않는다. 사용자가 쓴 문의와 운영자 답변은 개인 대화에 가깝고,
 * 감사 로그는 운영자 권한 행사 기록이다. "누가 언제 어느 문의에 답했다"까지가 감사의
 * 관심사이고 그 내용은 support_inquiries에 이미 남는다.
 */
@RestController
@RequestMapping("/admin/v1/support")
public class SupportAdminController {

    private static final Set<String> QUEUE_STATUS = Set.of("OPEN", "ANSWERED");

    private final SupportService support;
    private final AdminAuditService audit;

    public SupportAdminController(SupportService support, AdminAuditService audit) {
        this.support = support;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(defaultValue = "OPEN") String status,
                                    @RequestParam(defaultValue = "100") int limit) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
        if (!QUEUE_STATUS.contains(status)) {
            throw new ApiException(400, "invalid_parameter", "status는 OPEN 또는 ANSWERED");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("data", support.listQueue(status, limit));
        return out;
    }

    @PostMapping("/{externalId}/answer")
    public Map<String, Object> answer(HttpServletRequest request,
                                      @PathVariable("externalId") String externalId,
                                      @RequestBody(required = false) JsonNode body) {
        AdminPrincipal caller = AdminRequestContext.principal(request);
        caller.require(AdminRole.MEMBER_MOD);
        String text = body == null || body.get("answer") == null ? null : body.get("answer").asText();
        Map<String, Object> view = support.answer(externalId, caller.operatorId(), text);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("category", view.get("category"));
        audit.record(caller.operatorId(), "POST /admin/v1/support/answer", externalId,
                AdminRequestContext.requestId(request), null, "OK", detail);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.putAll(view);
        return out;
    }
}

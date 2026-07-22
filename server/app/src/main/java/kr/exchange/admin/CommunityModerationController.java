package kr.exchange.admin;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.community.CommunityService;
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

/**
 * 게시판 모더레이션(게시판_설계.md §4.1 운영 절). 숨김·복구·삭제·신고 처리를 관리 평면 MEMBER_MOD
 * 역할로 수행한다. 관리자 조작은 게시판 도메인(posts·comments·reports) 안에서만 일어나고 원장·저널에
 * 닿지 않는다. 전 액션은 admin_audit_log에 적재한다(§3-6 전 액션 감사).
 */
@RestController
@RequestMapping("/admin/v1/community")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class CommunityModerationController {

    public record ModerateBody(String action, String note) {
    }

    public record ResolveBody(String resolution, String note) {
    }

    private final CommunityService community;
    private final AdminAuditService audit;

    public CommunityModerationController(CommunityService community, AdminAuditService audit) {
        this.community = community;
        this.audit = audit;
    }

    @PostMapping("/posts/{externalId}/moderate")
    public Map<String, Object> moderatePost(@PathVariable String externalId,
                                            @RequestBody(required = false) ModerateBody body,
                                            HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.MEMBER_MOD);
        ModerateBody b = require(body);
        community.moderatePost(principal.operatorId(), externalId, b.action(), b.note());
        audit.record(principal.operatorId(), "community.post.moderate", externalId,
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("action", b.action() == null ? "" : b.action().toUpperCase()));
        return result(externalId, b.action());
    }

    @PostMapping("/comments/{externalId}/moderate")
    public Map<String, Object> moderateComment(@PathVariable String externalId,
                                               @RequestBody(required = false) ModerateBody body,
                                               HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.MEMBER_MOD);
        ModerateBody b = require(body);
        community.moderateComment(principal.operatorId(), externalId, b.action(), b.note());
        audit.record(principal.operatorId(), "community.comment.moderate", externalId,
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("action", b.action() == null ? "" : b.action().toUpperCase()));
        return result(externalId, b.action());
    }

    @GetMapping("/reports")
    public Map<String, Object> reports(@RequestParam(value = "status", required = false) String status,
                                       HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.MEMBER_MOD);
        List<Map<String, Object>> rows = community.listReports(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", "1.0");
        body.put("data", rows);
        return body;
    }

    @PostMapping("/reports/{reportId}/resolve")
    public Map<String, Object> resolve(@PathVariable long reportId,
                                       @RequestBody(required = false) ResolveBody body,
                                       HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.principal(request);
        principal.require(AdminRole.MEMBER_MOD);
        if (body == null || body.resolution() == null) {
            throw new ApiException(400, "invalid_parameter", "resolution이 필요합니다.");
        }
        community.resolveReport(principal.operatorId(), reportId, body.resolution(), body.note());
        audit.record(principal.operatorId(), "community.report.resolve", String.valueOf(reportId),
                AdminRequestContext.requestId(request), null, "OK",
                Map.of("resolution", body.resolution().toUpperCase()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0");
        result.put("report_id", reportId);
        result.put("status", body.resolution().toUpperCase());
        return result;
    }

    private static ModerateBody require(ModerateBody body) {
        if (body == null || body.action() == null) {
            throw new ApiException(400, "invalid_parameter", "action이 필요합니다.");
        }
        return body;
    }

    private static Map<String, Object> result(String externalId, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0");
        result.put("target", externalId);
        result.put("action", action == null ? null : action.toUpperCase());
        return result;
    }
}

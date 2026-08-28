package kr.exchange.api.support;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.common.SlidingWindowThrottle;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 회원 문의(API 설계 §4-G).
 *
 * 회원 세션 전용이다. 무인증 문의를 받지 않는 이유는 답을 전할 방법이 없기 때문이다.
 * 런타임 외부 API 의존 금지라 메일을 못 보내고, 답변은 앱 안에서만 읽힌다. 계정 없이
 * 받으면 답을 쓰고도 전달할 수 없는 문의가 쌓인다.
 *
 * 데모 세션은 앞단 필터가 쓰기를 이미 막지만(§3-7), 여기서도 회원 전용을 다시 강제한다.
 * 데모 계정은 공용 1개라 한 방문자의 문의가 다른 방문자에게 보인다.
 */
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    /* 문의는 사람이 글을 써서 보내는 행위라 정상 사용에서 시간당 10건을 넘지 않는다.
       무인증 경로는 아니지만 큐를 채워 운영자 판독을 마비시키는 것을 막는다 */
    private final SlidingWindowThrottle throttle =
            new SlidingWindowThrottle(10, 3_600_000, "support_create");

    private final SupportService support;

    public SupportController(SupportService support) {
        this.support = support;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request,
                                                      @RequestBody(required = false) JsonNode body) {
        long userId = memberId(request);
        // 회원당 버킷이다. IP로 잡으면 같은 사무실의 다른 회원이 서로를 막는다
        throttle.check("u" + userId);
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "invalid_parameter", "JSON 객체 바디 필요");
        }
        Map<String, Object> view = support.create(
                userId, text(body, "category"), text(body, "title"), text(body, "body"));
        return ResponseEntity.status(201).body(envelope(view));
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "50") int limit) {
        long userId = memberId(request);
        int capped = CursorPages.cap(limit, SupportService.MAX_LIMIT);
        // 한 건 더 읽어 다음 쪽 존재를 판단한다. 총 개수 질의는 페이지마다 전 범위를 세므로 쓰지 않는다
        CursorPages.Page<Map<String, Object>> page = CursorPages.slice(
                support.listMine(userId, CursorPages.decode(cursor), capped + 1), capped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("data", page.data());
        out.put("pagination", CursorPages.pagination(page));
        return out;
    }

    private static Map<String, Object> envelope(Map<String, Object> view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.putAll(view);
        return out;
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static long memberId(HttpServletRequest request) {
        AuthPrincipal principal =
                (AuthPrincipal) request.getAttribute(AuthPrincipal.REQUEST_ATTRIBUTE);
        if (principal == null) {
            throw new ApiException(401, "jwt_verification", "인증 컨텍스트 없음");
        }
        if (!AuthPrincipal.KIND_MEMBER.equals(principal.kind())) {
            throw new ApiException(403, "out_of_scope", "회원 세션 전용");
        }
        return principal.userId();
    }
}

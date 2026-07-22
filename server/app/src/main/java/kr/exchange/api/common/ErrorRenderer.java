package kr.exchange.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 표준 에러 포맷(API 설계 §2-3) 렌더러. 필터(인증)와 컨트롤러 어드바이스가 같은 모양을
 * 내보내야 클라이언트 파서가 단일 경로로 수렴한다.
 */
public class ErrorRenderer {

    private final ObjectMapper objectMapper;

    public ErrorRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode body(String name, String message, Map<String, Object> detail,
                           Integer retryAfter, String requestId) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("name", name);
        error.put("message", message);
        if (detail == null || detail.isEmpty()) {
            error.putNull("detail");
        } else {
            error.set("detail", objectMapper.valueToTree(detail));
        }
        error.putNull("trace_id");
        error.put("request_id", requestId);
        if (retryAfter == null) {
            error.putNull("retry_after");
        } else {
            error.put("retry_after", retryAfter);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("error", error);
        return root;
    }

    public void write(HttpServletResponse response, ApiException e) throws IOException {
        write(response, e, UUID.randomUUID().toString());
    }

    /**
     * 상관 추적(§3-6)용. 호출측(필터)이 감사행에 남긴 requestId를 그대로 헤더·body에 실어
     * 클라이언트 수신 request_id와 감사 request_id를 일치시킨다. 인자 없는 오버로드는 신규 발급.
     */
    public void write(HttpServletResponse response, ApiException e, String requestId) throws IOException {
        response.setStatus(e.status());
        response.setContentType("application/json;charset=utf-8");
        response.setHeader("X-Request-Id", requestId);
        if (e.retryAfter() != null) {
            response.setHeader("Retry-After", String.valueOf(e.retryAfter()));
        }
        response.getOutputStream().write(
                body(e.name(), e.getMessage(), e.detail(), e.retryAfter(), requestId)
                        .toString().getBytes(StandardCharsets.UTF_8));
    }
}

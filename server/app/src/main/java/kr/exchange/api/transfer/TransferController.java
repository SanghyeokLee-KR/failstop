package kr.exchange.api.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.Cursors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 회원 입출금(API 설계 §4-E).
 *
 * 쓰기 경로라 데모 세션은 앞단 ApiAuthFilter가 이미 막는다(§3-7 읽기 전용). 여기서는
 * 회원 세션 전용임을 다시 강제한다. API 키 주체(봇)를 막는 이유는 입출금이 데모 사용자
 * 행동이라 봇 부하가 자금을 발행·소각할 이유가 없기 때문이다. 열어두면 부하 시나리오가
 * 자산 총량을 바꿔 상시 게이트의 해석을 흐린다.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private static final int MAX_LIMIT = 100;

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> request(
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @RequestBody(required = false) JsonNode body) {
        long userId = memberId(request);
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "invalid_parameter", "JSON 객체 바디 필요");
        }
        TransferService.Result result = transfers.request(
                userId,
                text(body, "direction"),
                text(body, "asset"),
                text(body, "amount"),
                idemKey);
        Map<String, Object> payload = envelope(result.view());
        if (result.replayed()) {
            return ResponseEntity.ok().header("X-Idempotent-Replay", "true").body(payload);
        }
        return ResponseEntity.status(201).body(payload);
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "50") int limit) {
        long userId = memberId(request);
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        Cursors.Cursor decoded = cursor == null || cursor.isBlank() ? null : Cursors.decode(cursor);
        // 한 건 더 읽어 다음 쪽 존재를 판단한다. 총 개수 질의는 페이지마다 전 범위를 세므로 쓰지 않는다
        List<TransferService.Row> rows = transfers.listMine(userId, decoded, capped + 1);
        boolean hasMore = rows.size() > capped;
        List<TransferService.Row> page = hasMore ? rows.subList(0, capped) : rows;
        String nextCursor = null;
        if (hasMore) {
            TransferService.Row last = page.get(page.size() - 1);
            nextCursor = Cursors.encode(last.createdAt(), last.externalId());
        }
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("next_cursor", nextCursor);
        pagination.put("has_more", hasMore);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("data", page.stream().map(TransferService.Row::view).toList());
        out.put("pagination", pagination);
        return out;
    }

    @DeleteMapping("/{externalId}")
    public Map<String, Object> cancel(HttpServletRequest request,
                                      @PathVariable("externalId") String externalId) {
        long userId = memberId(request);
        return envelope(transfers.cancel(externalId, userId));
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

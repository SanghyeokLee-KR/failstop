package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.common.EpochSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 주문 REST(§4-B). 인증 컨텍스트는 ApiAuthFilter가 request attribute로 주입한다. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final int MAX_COUNT = 1000;
    private static final int MAX_BATCH = 20;
    // §2-7 요청 바디 상한 64KB. 인증 필터가 선차단하나, 필터 없이 도달하는 경로(테스트·재배치)에서도
    // 컨트롤러가 무제한 적재하지 않도록 자체 방어를 둔다.
    private static final int MAX_BODY_BYTES = 64 * 1024;

    // §2-5 계정 버킷: 주문 생성/취소 100토큰/10s, weight 5. 실효 20req/10s. 단일 라이터 앞 유일
    // 유량 방벽이라 라이터 진입 전에 차감한다. weight를 1로 두면 계약의 5배로 열린다.
    // capacity는 계약 기본 100. 데모 봇 플릿(다봇 고빈도)은 bots 프로파일에서만 상향해 429 파킹을
    // 피한다(운영·통합테스트 프로파일은 기본 100 유지 = 계약 실효 20req/10s).
    private static final int ORDER_WEIGHT = 5;
    private final AccountRateLimiter rateLimiter;
    // §2-5 계좌/주문 조회 버킷: 200토큰/10s, weight 2. /open·/closed 폴링 증폭 방벽.
    // /accounts와 단일 빈을 공유해 '계좌/주문 조회' 범주가 하나의 200토큰 예산을 쓰게 한다.
    private static final int QUERY_WEIGHT = 2;

    private final OrderGateway gateway;
    private final OrderQueryService orders;
    private final EpochSource epochSource;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;
    private final OrderRejectionMetrics rejections;
    private final AccountRateLimiter queryLimiter;

    public OrderController(OrderGateway gateway, OrderQueryService orders,
                           EpochSource epochSource, ObjectMapper objectMapper, JdbcClient jdbc,
                           OrderRejectionMetrics rejections,
                           @Qualifier("accountQueryRateLimiter") AccountRateLimiter accountQueryRateLimiter,
                           @Value("${exchange.order.rate-limit.capacity:100}") int orderCapacity) {
        this.gateway = gateway;
        this.orders = orders;
        this.epochSource = epochSource;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.rejections = rejections;
        this.queryLimiter = accountQueryRateLimiter;
        this.rateLimiter = new AccountRateLimiter(orderCapacity, 10_000, "account", "order");
    }

    @PostMapping
    public ResponseEntity<Object> create(HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        AuthPrincipal principal = principal(request);
        try {
            rateLimiter.enforce(response, String.valueOf(principal.userId()), ORDER_WEIGHT);
            byte[] body = readBoundedBody(request);
            JsonNode json = parse(body);
            OrderGateway.Placement placement = gateway.place(principal.userId(), json, body);
            Map<String, Object> view = orders.view(placement.row(), placement.epoch());
            if (placement.replayed()) {
                return ResponseEntity.ok().header("X-Idempotent-Replay", "true").body(view);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(view);
        } catch (ApiException e) {
            rejections.record(e);
            throw e;
        }
    }

    /** 일괄 주문(§4-B-6). 비원자·per-item 계약. 요청 자체가 유효하면 부분 성패라도 200. */
    @PostMapping("/batch")
    public Map<String, Object> batch(HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        AuthPrincipal principal = principal(request);
        JsonNode array;
        try {
            array = parseArray(readBoundedBody(request));
            // 원소당 weight 5를 차감해 batch 증폭(요청당 최대 20배)을 상쇄한다. 전량 통과하거나 전량 429.
            rateLimiter.enforce(response, String.valueOf(principal.userId()), array.size() * ORDER_WEIGHT);
        } catch (ApiException e) {
            // 배열 형식오류·전량 429는 요청 전체 거부라 1건으로 계량한다(per-item 거부와 별도).
            rejections.record(e);
            throw e;
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        int replayed = 0;
        int inProgress = 0;
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            item.put("client_order_id", element.hasNonNull("client_order_id")
                    ? element.get("client_order_id").asText() : null);
            try {
                if (!element.isObject()) {
                    throw new ApiException(400, "invalid_parameter", "주문 원소는 객체여야 합니다.");
                }
                // per-item 멱등 해시는 해당 원소 객체의 JCS 바이트 기준이다(§2-4).
                byte[] itemBody = objectMapper.writeValueAsBytes(element);
                OrderGateway.Placement placement =
                        gateway.place(principal.userId(), element, itemBody);
                if (placement.replayed()) {
                    item.put("status", "replayed");
                    item.put("idempotent_replay", true);
                    replayed++;
                } else {
                    item.put("status", "accepted");
                    accepted++;
                }
                item.put("order", orders.view(placement.row(), placement.epoch()));
            } catch (ApiException e) {
                if (e.retryAfter() != null) {
                    // 비종결(in-flight·페일오버). 재시도 대상이라 rejected와 구분한다.
                    item.put("status", "in_progress");
                    item.put("error", inProgressError(e));
                    inProgress++;
                } else {
                    item.put("status", "rejected");
                    item.put("error", rejectedError(e));
                    rejections.record(e);
                    rejected++;
                }
            }
            results.add(item);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accepted", accepted);
        summary.put("rejected", rejected);
        summary.put("replayed", replayed);
        if (inProgress > 0) {
            summary.put("in_progress", inProgress);
        }
        summary.put("total", array.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("results", results);
        body.put("summary", summary);
        return body;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> cancel(HttpServletRequest request, HttpServletResponse response,
                                         @PathVariable("id") String id) {
        AuthPrincipal principal = principal(request);
        try {
            rateLimiter.enforce(response, String.valueOf(principal.userId()), ORDER_WEIGHT);
            UUID externalId;
            try {
                externalId = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                throw new ApiException(404, "order_not_found", "주문이 없습니다.");
            }
            OrderGateway.Placement placement = gateway.cancel(principal.userId(), externalId);
            return ResponseEntity.ok(orders.view(placement.row(), placement.epoch()));
        } catch (ApiException e) {
            rejections.record(e);
            throw e;
        }
    }

    /** by-client 취소(§4-B-2). 재조회 계약과 동일한 저널 권위로 404·409를 판정한다. */
    @DeleteMapping("/by-client/{clientOrderId}")
    public ResponseEntity<Object> cancelByClient(HttpServletRequest request, HttpServletResponse response,
                                                 @PathVariable("clientOrderId") String clientOrderId) {
        AuthPrincipal principal = principal(request);
        try {
            rateLimiter.enforce(response, String.valueOf(principal.userId()), ORDER_WEIGHT);
            OrderGateway.Placement placement =
                    gateway.cancelByClient(principal.userId(), clientOrderId);
            return ResponseEntity.ok(orders.view(placement.row(), placement.epoch()));
        } catch (ApiException e) {
            rejections.record(e);
            throw e;
        }
    }

    /** 일괄 취소(§4-B-3). market 미지정 시 전체 미체결 취소. */
    @DeleteMapping
    public Map<String, Object> cancelAll(HttpServletRequest request, HttpServletResponse response,
                                         @RequestParam(value = "market", required = false) String market) {
        AuthPrincipal principal = principal(request);
        try {
            rateLimiter.enforce(response, String.valueOf(principal.userId()), ORDER_WEIGHT);
        } catch (ApiException e) {
            rejections.record(e);
            throw e;
        }
        OrderGateway.BulkCancelResult result = gateway.cancelAll(principal.userId(), market);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("canceled_count", result.canceledIds().size());
        body.put("canceled_ids", result.canceledIds());
        body.put("failed", result.failed());
        return body;
    }

    @GetMapping("/{id}")
    public Map<String, Object> byId(HttpServletRequest request, @PathVariable("id") String id) {
        AuthPrincipal principal = principal(request);
        UUID externalId;
        try {
            externalId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, "order_not_found", "주문이 없습니다.");
        }
        OrderQueryService.OrderRow row = orders.byExternalId(externalId)
                .filter(r -> r.userId() == principal.userId())
                .orElseThrow(() -> new ApiException(404, "order_not_found", "주문이 없습니다."));
        return orders.view(row, epochSource.epoch());
    }

    /** 재조회 계약(§4-B-4(a), §7-6): 판정 권위는 command_journal이다. */
    @GetMapping("/by-client/{clientOrderId}")
    public Map<String, Object> byClient(HttpServletRequest request,
                                        @PathVariable("clientOrderId") String clientOrderId) {
        AuthPrincipal principal = principal(request);
        Long journaled = jdbc.sql("""
                        SELECT count(*) FROM command_journal
                        WHERE command_type = 'NEW' AND user_id = ? AND client_order_id = ?
                        """)
                .params(principal.userId(), clientOrderId)
                .query(Long.class)
                .single();
        if (journaled == 0) {
            // 저널에 없음 = 미접수. 클라이언트는 안전 재전송이 가능하다.
            throw new ApiException(404, "order_not_found", "접수된 주문이 없습니다.");
        }
        return orders.byClientOrderId(principal.userId(), clientOrderId)
                .map(row -> orders.view(row, epochSource.epoch()))
                .orElseThrow(() -> new ApiException(409, "order_in_progress",
                        "저널에는 있으나 프로젝션 미반영입니다. 재조회하십시오.", null, 1));
    }

    @GetMapping("/open")
    public Map<String, Object> open(HttpServletRequest request, HttpServletResponse response,
                                    @RequestParam(value = "market", required = false) String market,
                                    @RequestParam(value = "count", required = false) Integer count,
                                    @RequestParam(value = "cursor", required = false) String cursor) {
        return page(request, response, true, market, count, cursor);
    }

    @GetMapping("/closed")
    public Map<String, Object> closed(HttpServletRequest request, HttpServletResponse response,
                                      @RequestParam(value = "market", required = false) String market,
                                      @RequestParam(value = "count", required = false) Integer count,
                                      @RequestParam(value = "cursor", required = false) String cursor) {
        return page(request, response, false, market, count, cursor);
    }

    private Map<String, Object> page(HttpServletRequest request, HttpServletResponse response,
                                     boolean open, String market, Integer count, String cursor) {
        AuthPrincipal principal = principal(request);
        queryLimiter.enforce(response, String.valueOf(principal.userId()), QUERY_WEIGHT);
        int limit = count == null ? 100 : count;
        if (limit < 1 || limit > MAX_COUNT) {
            throw new ApiException(400, "invalid_parameter", "count는 1~" + MAX_COUNT + "이어야 합니다.");
        }
        Cursors.Cursor decoded = cursor == null ? null : Cursors.decode(cursor);
        List<OrderQueryService.OrderRow> rows =
                orders.page(principal.userId(), open, market, decoded, limit);
        long epoch = epochSource.epoch();
        List<Map<String, Object>> data = new ArrayList<>();
        for (OrderQueryService.OrderRow row : rows) {
            data.add(orders.view(row, epoch));
        }
        String nextCursor = null;
        if (rows.size() == limit && !rows.isEmpty()) {
            OrderQueryService.OrderRow last = rows.get(rows.size() - 1);
            nextCursor = Cursors.encode(last.createdAt(), last.externalId().toString());
        }
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("next_cursor", nextCursor);
        pagination.put("has_more", nextCursor != null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("data", data);
        body.put("pagination", pagination);
        return body;
    }

    /** 상한을 넘으면 전량 적재하지 않고 413으로 끊는다(§2-7). 실제 읽기에서 상한+1로 chunked 우회도 차단. */
    private static byte[] readBoundedBody(HttpServletRequest request) throws java.io.IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            throw new ApiException(413, "payload_too_large", "요청 바디가 64KB 상한을 초과했습니다.");
        }
        java.io.InputStream in = request.getInputStream();
        byte[] buffer = new byte[MAX_BODY_BYTES + 1];
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        if (total > MAX_BODY_BYTES) {
            throw new ApiException(413, "payload_too_large", "요청 바디가 64KB 상한을 초과했습니다.");
        }
        byte[] body = new byte[total];
        System.arraycopy(buffer, 0, body, 0, total);
        return body;
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new ApiException(400, "invalid_parameter", "JSON 객체 바디가 필요합니다.");
            }
            return node;
        } catch (java.io.IOException e) {
            throw new ApiException(400, "invalid_parameter", "요청 바디가 유효한 JSON이 아닙니다.");
        }
    }

    private JsonNode parseArray(byte[] body) {
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (java.io.IOException e) {
            throw new ApiException(400, "invalid_parameter", "요청 바디가 유효한 JSON이 아닙니다.");
        }
        if (node == null || !node.isArray()) {
            throw new ApiException(400, "invalid_parameter", "주문 배열 바디가 필요합니다.");
        }
        if (node.isEmpty()) {
            throw new ApiException(400, "invalid_parameter", "주문 배열이 비어 있습니다.");
        }
        if (node.size() > MAX_BATCH) {
            throw new ApiException(400, "invalid_parameter", "일괄 주문은 최대 " + MAX_BATCH + "건입니다.");
        }
        return node;
    }

    private static Map<String, Object> rejectedError(ApiException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("name", e.name());
        error.put("message", e.getMessage());
        if (e.detail() != null) {
            error.put("detail", e.detail());
        }
        return error;
    }

    private static Map<String, Object> inProgressError(ApiException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("name", e.name());
        if (e.retryAfter() != null) {
            error.put("retry_after", e.retryAfter());
        }
        return error;
    }

    static AuthPrincipal principal(HttpServletRequest request) {
        AuthPrincipal principal =
                (AuthPrincipal) request.getAttribute(AuthPrincipal.REQUEST_ATTRIBUTE);
        if (principal == null) {
            throw new ApiException(401, "jwt_verification", "인증 컨텍스트가 없습니다.");
        }
        return principal;
    }
}

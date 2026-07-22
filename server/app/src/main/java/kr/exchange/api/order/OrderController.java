package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.common.EpochSource;
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

    private final OrderGateway gateway;
    private final OrderQueryService orders;
    private final EpochSource epochSource;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;

    public OrderController(OrderGateway gateway, OrderQueryService orders,
                           EpochSource epochSource, ObjectMapper objectMapper, JdbcClient jdbc) {
        this.gateway = gateway;
        this.orders = orders;
        this.epochSource = epochSource;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @PostMapping
    public ResponseEntity<Object> create(HttpServletRequest request) throws java.io.IOException {
        AuthPrincipal principal = principal(request);
        byte[] body = request.getInputStream().readAllBytes();
        JsonNode json = parse(body);
        OrderGateway.Placement placement = gateway.place(principal.userId(), json, body);
        Map<String, Object> view = orders.view(placement.row(), placement.epoch());
        if (placement.replayed()) {
            return ResponseEntity.ok().header("X-Idempotent-Replay", "true").body(view);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> cancel(HttpServletRequest request, @PathVariable("id") String id) {
        AuthPrincipal principal = principal(request);
        UUID externalId;
        try {
            externalId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, "order_not_found", "주문이 없습니다.");
        }
        OrderGateway.Placement placement = gateway.cancel(principal.userId(), externalId);
        return ResponseEntity.ok(orders.view(placement.row(), placement.epoch()));
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
    public Map<String, Object> open(HttpServletRequest request,
                                    @RequestParam(value = "market", required = false) String market,
                                    @RequestParam(value = "count", required = false) Integer count,
                                    @RequestParam(value = "cursor", required = false) String cursor) {
        return page(request, true, market, count, cursor);
    }

    @GetMapping("/closed")
    public Map<String, Object> closed(HttpServletRequest request,
                                      @RequestParam(value = "market", required = false) String market,
                                      @RequestParam(value = "count", required = false) Integer count,
                                      @RequestParam(value = "cursor", required = false) String cursor) {
        return page(request, false, market, count, cursor);
    }

    private Map<String, Object> page(HttpServletRequest request, boolean open, String market,
                                     Integer count, String cursor) {
        AuthPrincipal principal = principal(request);
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

    static AuthPrincipal principal(HttpServletRequest request) {
        AuthPrincipal principal =
                (AuthPrincipal) request.getAttribute(AuthPrincipal.REQUEST_ATTRIBUTE);
        if (principal == null) {
            throw new ApiException(401, "jwt_verification", "인증 컨텍스트가 없습니다.");
        }
        return principal;
    }
}

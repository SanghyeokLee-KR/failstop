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
import kr.exchange.api.common.RetrySafeWrite;
import kr.exchange.api.common.RoutingCounters;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.dto.BatchResponse;
import kr.exchange.api.order.dto.CancelAllResponse;
import kr.exchange.api.order.dto.ChanceResponse;
import kr.exchange.api.order.dto.OrderResponse;
import kr.exchange.api.order.dto.PageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 주문 REST(§4-B). 인증 컨텍스트는 ApiAuthFilter가 request attribute로 주입한다. 생성은 client_order_id 멱등, 취소는 종결
 * 주문의 재취소 멱등이라 다시 보내도 한 번의 효과로 모인다(RetrySafeWrite).
 */
@RestController
@RetrySafeWrite
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
    private final AccountRepository accounts;
    private final OrderRejectionMetrics rejections;
    private final AccountRateLimiter queryLimiter;
    private final MarketDirectory markets;

    public OrderController(OrderGateway gateway, OrderQueryService orders,
                           EpochSource epochSource, ObjectMapper objectMapper,
                           AccountRepository accounts,
                           OrderRejectionMetrics rejections, MarketDirectory markets,
                           @Qualifier("accountQueryRateLimiter") AccountRateLimiter accountQueryRateLimiter,
                           @Value("${exchange.order.rate-limit.capacity:100}") int orderCapacity) {
        this.gateway = gateway;
        this.orders = orders;
        this.epochSource = epochSource;
        this.objectMapper = objectMapper;
        this.accounts = accounts;
        this.rejections = rejections;
        this.markets = markets;
        this.queryLimiter = accountQueryRateLimiter;
        this.rateLimiter = new AccountRateLimiter(orderCapacity, 10_000, "account", "order");
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(HttpServletRequest request,
                                                HttpServletResponse response)
            throws java.io.IOException {
        AuthPrincipal principal = principal(request);
        try {
            // 마켓 힌트(API 설계 §2-5-d). 같은 이름 헤더 둘은 선검사가 먼저 거절하고, 선검사 없는 배선을 위해 여기서도 본다.
            String hint = hint(request);
            rateLimiter.enforce(response, String.valueOf(principal.userId()), ORDER_WEIGHT);
            byte[] body = readBoundedBody(request);
            JsonNode json = parse(body);
            OrderGateway.Placement placement = gateway.place(principal.userId(), json, body, hint);
            OrderResponse view = orders.view(placement.row(), placement.epoch());
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
    public BatchResponse batch(HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        AuthPrincipal principal = principal(request);
        JsonNode array;
        try {
            String hint = hint(request);
            array = parseArray(readBoundedBody(request));
            // 한 배치 한 마켓(API 설계 §4-B-6 v2.57). 계정 토큰 차감과 원소 처리보다 먼저라 토큰·키·예약·채번을 남기지 않는다.
            requireSingleMarket(array, hint);
            // 원소당 weight 5를 차감해 batch 증폭(요청당 최대 20배)을 상쇄한다. 전량 통과하거나 전량 429.
            rateLimiter.enforce(response, String.valueOf(principal.userId()), array.size() * ORDER_WEIGHT);
        } catch (ApiException e) {
            // 배열 형식오류·전량 429는 요청 전체 거부라 1건으로 계량한다(per-item 거부와 별도).
            rejections.record(e);
            throw e;
        }
        List<BatchResponse.Item> results = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        int replayed = 0;
        int inProgress = 0;
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            String clientOrderId = element.hasNonNull("client_order_id")
                    ? element.get("client_order_id").asText() : null;
            try {
                if (!element.isObject()) {
                    throw new ApiException(400, "invalid_parameter", "주문 원소는 객체여야 합니다.");
                }
                // per-item 멱등 해시는 해당 원소 객체의 JCS 바이트 기준이다(§2-4).
                byte[] itemBody = objectMapper.writeValueAsBytes(element);
                OrderGateway.Placement placement =
                        gateway.place(principal.userId(), element, itemBody);
                OrderResponse order = orders.view(placement.row(), placement.epoch());
                if (placement.replayed()) {
                    results.add(new BatchResponse.Item(i, clientOrderId, "replayed", true, order,
                            null));
                    replayed++;
                } else {
                    results.add(new BatchResponse.Item(i, clientOrderId, "accepted", null, order,
                            null));
                    accepted++;
                }
            } catch (ApiException e) {
                if (e.retryAfter() != null) {
                    // 비종결(in-flight·페일오버). 재시도 대상이라 rejected와 구분한다.
                    results.add(new BatchResponse.Item(i, clientOrderId, "in_progress", null, null,
                            new BatchResponse.InProgressError(e.name(), e.retryAfter())));
                    inProgress++;
                    if (e.dbOutage() == ApiException.DbOutage.UNREACHABLE) {
                        // 저널 확인마저 DB 에 닿지 못했다. 남은 원소의 접수 전 조회가 같은 장애를 원시 예외로 올려 앞 원소의
                        // 결과를 잃지 않게 시도하지 않는다(API 설계 §2-5-c). 같은 배열을 다시 보내면 새로 접수된다.
                        for (int rest = i + 1; rest < array.size(); rest++) {
                            JsonNode skipped = array.get(rest);
                            results.add(new BatchResponse.Item(rest, skipped.hasNonNull("client_order_id")
                                    ? skipped.get("client_order_id").asText() : null, "in_progress", null, null,
                                    new BatchResponse.InProgressError("service_unavailable", 1)));
                            inProgress++;
                        }
                        break;
                    }
                } else {
                    results.add(new BatchResponse.Item(i, clientOrderId, "rejected", null, null,
                            new BatchResponse.RejectedError(e.name(), e.getMessage(), e.detail())));
                    rejections.record(e);
                    rejected++;
                }
            }
        }
        BatchResponse.Summary summary = new BatchResponse.Summary(accepted, rejected, replayed,
                inProgress > 0 ? inProgress : null, array.size());
        return new BatchResponse(ApiFormats.SCHEMA_VERSION, results, summary);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<OrderResponse> cancel(HttpServletRequest request,
                                                HttpServletResponse response,
                                                @PathVariable("id") String id) {
        AuthPrincipal principal = principal(request);
        try {
            String hint = hint(request);
            rateLimiter.enforce(response, String.valueOf(principal.userId()), ORDER_WEIGHT);
            UUID externalId;
            try {
                externalId = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                throw new ApiException(404, "order_not_found", "주문 없음");
            }
            OrderGateway.Placement placement = gateway.cancel(principal.userId(), externalId, hint);
            return ResponseEntity.ok(orders.view(placement.row(), placement.epoch()));
        } catch (ApiException e) {
            rejections.record(e);
            throw e;
        }
    }

    /** by-client 취소(§4-B-2). 재조회 계약과 동일한 저널 권위로 404·409를 판정한다. */
    @DeleteMapping("/by-client/{clientOrderId}")
    public ResponseEntity<OrderResponse> cancelByClient(HttpServletRequest request,
                                                        HttpServletResponse response,
                                                        @PathVariable("clientOrderId") String clientOrderId) {
        AuthPrincipal principal = principal(request);
        try {
            String hint = hint(request);
            rateLimiter.enforce(response, String.valueOf(principal.userId()), ORDER_WEIGHT);
            OrderGateway.Placement placement =
                    gateway.cancelByClient(principal.userId(), clientOrderId, hint);
            return ResponseEntity.ok(orders.view(placement.row(), placement.epoch()));
        } catch (ApiException e) {
            rejections.record(e);
            throw e;
        }
    }

    /**
     * 일괄 취소(§4-B-3). market 미지정 시 전체 미체결 취소. 이 노드가 접수하지 않는 파티션의 주문은 그 소유 노드에 넘긴다
     * (DB 설계 §5.4-d). 이 노드에서 submit 을 하나도 하지 않고 끝났으면(열린 주문이 없었거나 모두 넘김) 좀비 판정의 노드 층
     * 수요에서 뺀다(DB 설계 §5.4-c 8 v2.57). 인계 행 넣기가 DB 오류로 끝났으면 빼지 않는다.
     */
    @DeleteMapping
    public CancelAllResponse cancelAll(HttpServletRequest request, HttpServletResponse response,
                                       @RequestParam(value = "market", required = false) String market) {
        AuthPrincipal principal = principal(request);
        try {
            String hint = hint(request);
            if (hint != null && !hint.equals(market)) {
                // 마켓 없는 일괄 취소에 실린 힌트나 market 쿼리와 다른 힌트. 계정 토큰 차감 전에 끝낸다(API 설계 §2-5-d).
                gateway.routing().hintRejected(RoutingCounters.HINT_MISMATCH);
                throw MarketHint.mismatch();
            }
            rateLimiter.enforce(response, String.valueOf(principal.userId()), ORDER_WEIGHT);
        } catch (ApiException e) {
            rejections.record(e);
            throw e;
        }
        OrderGateway.BulkCancelResult result = gateway.cancelAll(principal.userId(), market);
        if (result.localSubmits() == 0 && !result.handoffInsertFailed()) {
            request.setAttribute(ApiException.DEMAND_NOT_LOCAL_ATTRIBUTE, Boolean.TRUE);
        }
        return new CancelAllResponse(ApiFormats.SCHEMA_VERSION, result.canceledIds().size(),
                result.canceledIds(), result.failed());
    }

    @GetMapping("/{id}")
    public OrderResponse byId(HttpServletRequest request, @PathVariable("id") String id) {
        AuthPrincipal principal = principal(request);
        UUID externalId;
        try {
            externalId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(404, "order_not_found", "주문 없음");
        }
        OrderQueryService.OrderRow row = orders.byExternalId(externalId)
                .filter(r -> r.userId() == principal.userId())
                .orElseThrow(() -> new ApiException(404, "order_not_found", "주문 없음"));
        return orders.view(row, epochSource.epoch(row.marketId()));
    }

    /** 재조회 계약(§4-B-4(a), §7-6): 판정 권위는 command_journal이다. */
    @GetMapping("/by-client/{clientOrderId}")
    public OrderResponse byClient(HttpServletRequest request,
                                  @PathVariable("clientOrderId") String clientOrderId) {
        AuthPrincipal principal = principal(request);
        if (!orders.commandJournaled(principal.userId(), clientOrderId)) {
            // 저널에 없음 = 미접수. 클라이언트는 안전 재전송이 가능하다.
            throw new ApiException(404, "order_not_found", "접수된 주문 없음");
        }
        return orders.byClientOrderId(principal.userId(), clientOrderId)
                .map(row -> orders.view(row, epochSource.epoch(row.marketId())))
                .orElseThrow(() -> new ApiException(409, "order_in_progress",
                        "저널에는 있으나 프로젝션 미반영. 재조회 필요", null, 1));
    }

    @GetMapping("/open")
    public PageResponse<OrderResponse> open(HttpServletRequest request, HttpServletResponse response,
                                            @RequestParam(value = "market", required = false) String market,
                                            @RequestParam(value = "count", required = false) Integer count,
                                            @RequestParam(value = "cursor", required = false) String cursor) {
        return page(request, response, true, market, count, cursor);
    }

    @GetMapping("/closed")
    public PageResponse<OrderResponse> closed(HttpServletRequest request, HttpServletResponse response,
                                              @RequestParam(value = "market", required = false) String market,
                                              @RequestParam(value = "count", required = false) Integer count,
                                              @RequestParam(value = "cursor", required = false) String cursor) {
        return page(request, response, false, market, count, cursor);
    }

    /**
     * 주문 가능 정보(§4-B-5). 페이지네이션 없는 단건 객체라 리스트 엔벌로프를 쓰지 않고
     * schema_version만 싣는다(§2-2).
     *
     * 수수료는 마켓 메타를 그대로 읽는다. `GET /markets`와 같은 행·같은 포맷터를 쓰므로 두 응답의
     * maker_fee·taker_fee는 구성상 일치한다(§4-B-5 '수수료 권위 소스는 마켓별 고정'). 여기서 별도
     * 산식을 두는 순간 두 값이 갈라진다.
     */
    @GetMapping("/chance")
    public ChanceResponse chance(HttpServletRequest request, HttpServletResponse response,
                                 @RequestParam("market") String market) {
        AuthPrincipal principal = principal(request);
        queryLimiter.enforce(response, String.valueOf(principal.userId()), QUERY_WEIGHT);
        MarketDirectory.Market m = markets.bySymbol(market);
        ChanceResponse.MarketInfo meta = new ChanceResponse.MarketInfo(
                m.symbol(),
                ApiFormats.amount(m.tickSize()),
                ApiFormats.amount(m.minNotional()),
                ApiFormats.amount(m.maxNotional()),
                m.status());
        return new ChanceResponse(
                ApiFormats.SCHEMA_VERSION,
                meta,
                ApiFormats.amount(m.makerFeeRate()),
                ApiFormats.amount(m.takerFeeRate()),
                // bid는 quote(대금)를, ask는 base(수량)를 소비한다.
                accountBalance(principal.userId(), m.quoteAssetId()),
                accountBalance(principal.userId(), m.baseAssetId()));
    }

    private ChanceResponse.AccountBalance accountBalance(long userId, short assetId) {
        AccountRepository.AssetBalanceRow row = accounts.assetBalance(userId, assetId);
        return new ChanceResponse.AccountBalance(row.currency(),
                ApiFormats.amount(row.available()), ApiFormats.amount(row.locked()));
    }

    private PageResponse<OrderResponse> page(HttpServletRequest request,
                                             HttpServletResponse response,
                                             boolean open, String market, Integer count,
                                             String cursor) {
        AuthPrincipal principal = principal(request);
        queryLimiter.enforce(response, String.valueOf(principal.userId()), QUERY_WEIGHT);
        int limit = count == null ? 100 : count;
        if (limit < 1 || limit > MAX_COUNT) {
            throw new ApiException(400, "invalid_parameter", "count는 1~" + MAX_COUNT + "이어야 합니다.");
        }
        Cursors.Cursor decoded = cursor == null ? null : Cursors.decode(cursor);
        List<OrderQueryService.OrderRow> rows =
                orders.page(principal.userId(), open, market, decoded, limit);
        List<OrderResponse> data = new ArrayList<>();
        // 비리더는 epoch 를 리스 행에서 읽으므로 한 페이지 안에서는 마켓마다 한 번만 읽는다.
        java.util.Map<Integer, Long> epochs = new java.util.HashMap<>();
        for (OrderQueryService.OrderRow row : rows) {
            data.add(orders.view(row, epochs.computeIfAbsent(row.marketId(), epochSource::epoch)));
        }
        String nextCursor = null;
        if (rows.size() == limit && !rows.isEmpty()) {
            OrderQueryService.OrderRow last = rows.get(rows.size() - 1);
            nextCursor = Cursors.encode(last.createdAt(), last.externalId().toString());
        }
        return new PageResponse<>(ApiFormats.SCHEMA_VERSION, data,
                new PageResponse.Pagination(nextCursor, nextCursor != null));
    }

    /** 상한을 넘으면 전량 적재하지 않고 413으로 끊는다(§2-7). 실제 읽기에서 상한+1로 chunked 우회도 차단. */
    private static byte[] readBoundedBody(HttpServletRequest request) throws java.io.IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            throw new ApiException(413, "payload_too_large", "요청 바디 64KB 상한 초과");
        }
        java.io.InputStream in = request.getInputStream();
        byte[] buffer = new byte[MAX_BODY_BYTES + 1];
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        if (total > MAX_BODY_BYTES) {
            throw new ApiException(413, "payload_too_large", "요청 바디 64KB 상한 초과");
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
            throw new ApiException(400, "invalid_parameter", "주문 배열 비어 있음");
        }
        if (node.size() > MAX_BATCH) {
            throw new ApiException(400, "invalid_parameter", "일괄 주문은 최대 " + MAX_BATCH + "건입니다.");
        }
        return node;
    }

    /** 요청의 마켓 힌트(API 설계 §2-5-d). 같은 이름 헤더가 둘 이상이면 400 market_hint_duplicate 이고 거절 수를 센다. */
    private String hint(HttpServletRequest request) {
        try {
            return MarketHint.from(request);
        } catch (ApiException duplicate) {
            gateway.routing().hintRejected(RoutingCounters.HINT_DUPLICATE);
            throw duplicate;
        }
    }

    /**
     * 한 배치 한 마켓(API 설계 §4-B-6 v2.57). market 이 문자열인 원소들의 값이 둘 이상이면 400 batch_single_market, 힌트가 있고
     * 그 값과 다르면 400 market_hint_mismatch 다. market 이 없거나 문자열이 아닌 원소는 종전처럼 그 원소만 rejected 다. 같은
     * 파티션의 마켓끼리 섞는 것도 받지 않는다. 대응은 운영 데이터라 클라이언트가 모르고, 받던 배치가 대응 변경으로 거부되는
     * 계약은 불안정하다.
     */
    private void requireSingleMarket(JsonNode array, String hint) {
        java.util.Set<String> markets = new java.util.LinkedHashSet<>();
        for (JsonNode element : array) {
            JsonNode market = element.isObject() ? element.get("market") : null;
            if (market != null && market.isTextual()) {
                markets.add(market.asText());
            }
        }
        if (markets.size() > 1) {
            gateway.routing().hintRejected(RoutingCounters.BATCH_SINGLE_MARKET);
            throw MarketHint.batchSingleMarket();
        }
        if (hint != null && !markets.isEmpty() && !markets.contains(hint)) {
            gateway.routing().hintRejected(RoutingCounters.HINT_MISMATCH);
            throw MarketHint.mismatch();
        }
    }

    static AuthPrincipal principal(HttpServletRequest request) {
        AuthPrincipal principal =
                (AuthPrincipal) request.getAttribute(AuthPrincipal.REQUEST_ATTRIBUTE);
        if (principal == null) {
            throw new ApiException(401, "jwt_verification", "인증 컨텍스트 없음");
        }
        return principal;
    }
}

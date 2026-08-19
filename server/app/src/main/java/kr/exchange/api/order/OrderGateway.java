package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.auth.CanonicalRequests;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.dto.CancelAllResponse;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.OrderIntake;
import kr.exchange.engine.SubmitResult;
import kr.exchange.engine.core.OrderCommand;
import kr.exchange.journal.CommandDraft;
import kr.exchange.ledger.Fees;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 접수 게이트웨이. 검증·사전 가드·박제(external_id, 수수료율, engine_ts)를 끝낸 뒤
 * EngineBridge에 넘긴다(README M1-4 인계 계약).
 *
 * 잔고 사전 가드는 필수다. 가드 없이 잔고 부족 명령이 저널을 통과하면 정산 예약(23514)이
 * 루프 정지 + demote로 이어진다(DB 설계 §5.1 v2.9). 가드와 정산 사이의 짧은 경합 창은
 * 인메모리 in-flight 예약 누계로 좁힌다(단일 노드 전제. 다중 노드는 M2에서 재설계).
 */
public class OrderGateway {

    public record Placement(OrderQueryService.OrderRow row, boolean replayed, long epoch) {
    }

    private static final long PROJECTION_WAIT_MS = 5_000;
    private static final long POLL_INTERVAL_MS = 20;
    private static final long RESERVE_DEFER_TTL_MS = 30_000;

    private final JdbcClient jdbc;
    private final EngineBridge bridge;
    private final MarketDirectory markets;
    private final OrderQueryService orders;
    private final InflightReserves inflight;

    public OrderGateway(JdbcClient jdbc, EngineBridge bridge, MarketDirectory markets,
                        OrderQueryService orders) {
        this.jdbc = jdbc;
        this.bridge = bridge;
        this.markets = markets;
        this.orders = orders;
        this.inflight = new InflightReserves(
                orderId -> orders.byInternalId(orderId).isPresent(),
                System::currentTimeMillis, RESERVE_DEFER_TTL_MS);
    }

    public Placement place(long userId, JsonNode body, byte[] rawBody) {
        String clientOrderId = text(body, "client_order_id");
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new ApiException(400, "client_order_id_required", "client_order_id는 필수입니다.");
        }
        if (!clientOrderId.matches("[A-Za-z0-9_-]{1,36}")) {
            throw new ApiException(400, "invalid_parameter",
                    "client_order_id는 1~36자의 [A-Za-z0-9_-]여야 합니다.");
        }

        // 정지·해지 계정 차단(§3-8 account_suspended). 스테이트리스 액세스 토큰(15분) 무효화 지연을
        // 주문 생성 경로에서 좁힌다. 라이터 스레드 밖의 가벼운 조회다.
        requireActiveUser(userId);

        ParsedOrder parsed = validate(body);
        MarketDirectory.Market market = parsed.market();

        // 멱등 내용 비교(§2-4): 원본 요청 바디의 JCS 바이트 해시를 저장해 재전송과 대조한다.
        // 기존 키가 있어도 저널 미기록(고아 키)이면 잔고 가드가 적용된 적 없다. 저널 실재를 확인해
        // 가드 재적용 여부를 가른다(고아 키가 잔고 사전 가드를 무력화하는 경로 차단, §데이터 무결성).
        // 저널에 실재하면 원 주문이 예약을 소비했으므로 재적용하지 않는다(정당한 replay 오거부 방지).
        String requestHash = CanonicalRequests.sha256Hex(CanonicalRequests.jcs(rawBody));
        Optional<String> stored = storedRequestHash(userId, clientOrderId);
        short reserveAsset = parsed.buy() ? market.quoteAssetId() : market.baseAssetId();
        String reserveKey = userId + ":" + reserveAsset;
        BigDecimal required = parsed.reserve();
        boolean guardApplies;
        if (stored.isPresent()) {
            requireSameContent(stored.get(), requestHash);
            guardApplies = !orders.commandJournaled(userId, clientOrderId);
        } else {
            guardApplies = true;
        }
        boolean guarded = false;
        boolean insertedKey = false;
        if (guardApplies) {
            // 잔고 사전 가드(§5.1 v2.9). in-flight 예약 누계를 합산해 가드-정산 경합 창을 좁힌다.
            // 가드 실패는 멱등 키를 소비하기 전이라 같은 키의 후속 시도를 오염시키지 않는다.
            if (!inflight.tryReserve(reserveKey, required,
                    () -> availableBalance(userId, reserveAsset))) {
                throw new ApiException(400, "insufficient_funds", "주문 가능 잔고가 부족합니다.");
            }
            guarded = true;
            if (stored.isEmpty()) {
                int inserted = jdbc.sql("""
                                INSERT INTO idempotency_keys (user_id, idem_key, request_hash)
                                VALUES (?, ?, ?) ON CONFLICT (user_id, idem_key) DO NOTHING
                                """)
                        .params(userId, clientOrderId, requestHash)
                        .update();
                if (inserted == 0) {
                    // 동시 도착 경합에서 진 쪽. 내용 비교 후 저널 멱등 판정으로 수렴한다(§2-4).
                    requireSameContent(storedRequestHash(userId, clientOrderId).orElse(requestHash),
                            requestHash);
                } else {
                    insertedKey = true;
                }
            }
        }
        boolean journaled = false;
        boolean replayed = false;
        OrderQueryService.OrderRow row = null;
        long orderId = 0;
        try {
            final long assignedId = nextOrderId();
            orderId = assignedId;
            OrderIntake intake = new OrderIntake(
                    market.id(), CommandDraft.CommandType.NEW, userId, clientOrderId, assignedId,
                    System.currentTimeMillis(), assignedId,
                    parsed.side(), parsed.ordType(), parsed.timeInForce(),
                    parsed.price(), parsed.quantity(), parsed.quoteOrderQty(),
                    UUID.randomUUID(), market.makerFeeRate(), market.takerFeeRate());
            SubmitResult result;
            try {
                result = bridge.submit(intake);
                journaled = true;
            } catch (IllegalStateException e) {
                // 루프 정지(리더십 상실 창). 저널 커밋 전 거부이므로 재조회 계약으로 수렴시킨다.
                throw new ApiException(503, "service_unavailable",
                        "페일오버 진행 중입니다. 재조회 후 판단하십시오.", null, 1);
            }
            replayed = result.replayed();

            row = result.replayed()
                    ? awaitRow(() -> orders.byClientOrderId(userId, clientOrderId))
                    : awaitRow(() -> orders.byInternalId(assignedId));
            if (row == null) {
                throw new ApiException(409, "order_in_progress",
                        "주문이 처리 중입니다. 재조회하십시오.", null, 1);
            }
            if (!result.replayed() && "REJECTED".equals(row.status())) {
                throw new ApiException(422, "domain_rejected", "도메인 검증에서 거부되었습니다.",
                        Map.of("reject_reason", row.rejectReason() == null ? "rejected"
                                : row.rejectReason()), null);
            }
            return new Placement(row, result.replayed(), result.epoch());
        } finally {
            if (insertedKey && !journaled) {
                // 저널 미기록 확정(페일오버 창 submit 실패 등). 선삽입한 고아 멱등 키를 정리해
                // 다음 재시도가 잔고 가드를 재적용하게 한다. 정리 실패해도 재시도 경로의
                // command_journal 실재 확인이 backstop이라 가드 무력화로 이어지지 않는다.
                deleteOrphanKey(userId, clientOrderId, requestHash);
            }
            if (guarded) {
                if (releaseImmediately(row != null, journaled, replayed)) {
                    inflight.release(reserveKey, required);
                } else {
                    // 저널 이후 정산 미관측(프로젝션 타임아웃 등). 즉시 해제는 가드-정산 경합 창을
                    // 다시 열므로 주문 row 관측 또는 TTL로 지연 회수한다(M1-4 재판정 #4).
                    inflight.defer(reserveKey, required, orderId);
                }
            }
        }
    }

    /**
     * finally 예약 회수 결정. 정산 관측 완료(available 반영)·저널 미기록 확정·멱등 replay 중 하나면
     * 즉시 해제한다. replay 접수는 자기 명령이 엔진·정산에 도달하지 않아(권위 예약은 원본이 보유)
     * assignedId로 어떤 orders row도 생기지 않으므로, defer하면 sweep의 주문 row 관측 회수 경로가
     * 죽어 유령 예약이 TTL(30s)로만 회수되고 후속 주문을 insufficient_funds로 오거부한다.
     */
    static boolean releaseImmediately(boolean rowObserved, boolean journaled, boolean replayed) {
        return rowObserved || !journaled || replayed;
    }

    private void requireActiveUser(long userId) {
        String status = jdbc.sql("SELECT status FROM users WHERE id = ?")
                .params(userId)
                .query(String.class)
                .optional()
                .orElse("CLOSED");
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(403, "account_suspended", "주문할 수 없는 계정 상태입니다.");
        }
    }

    private void deleteOrphanKey(long userId, String clientOrderId, String requestHash) {
        try {
            jdbc.sql("""
                            DELETE FROM idempotency_keys
                            WHERE user_id = ? AND idem_key = ? AND request_hash = ?
                            """)
                    .params(userId, clientOrderId, requestHash)
                    .update();
        } catch (RuntimeException ignored) {
            // 정리 실패는 재시도 경로의 commandJournaled 확인이 흡수한다.
        }
    }

    private Optional<String> storedRequestHash(long userId, String clientOrderId) {
        return jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE user_id = ? AND idem_key = ?")
                .params(userId, clientOrderId)
                .query(String.class)
                .optional();
    }

    private static void requireSameContent(String stored, String incoming) {
        if (!stored.equals(incoming)) {
            throw new ApiException(409, "duplicate_client_order_id",
                    "동일 client_order_id로 다른 내용의 주문이 존재합니다.");
        }
    }

    public Placement cancel(long userId, UUID externalId) {
        OrderQueryService.OrderRow row = orders.byExternalId(externalId)
                .filter(r -> r.userId() == userId)
                .orElseThrow(() -> new ApiException(404, "order_not_found", "주문이 없습니다."));
        if (row.terminal()) {
            // 재취소 멱등(§2-4): 이미 종결이면 현재 상태를 그대로 반환한다.
            return new Placement(row, true, currentEpoch());
        }
        MarketDirectory.Market market = markets.byId(row.marketId());
        OrderIntake intake = new OrderIntake(
                market.id(), CommandDraft.CommandType.CANCEL, userId, null, row.id(),
                System.currentTimeMillis(), nextOrderId(),
                null, null, null, null, null, null, null, null, null);
        SubmitResult result = submit(intake);

        OrderQueryService.OrderRow settled = awaitRow(() ->
                orders.byInternalId(row.id()).filter(OrderQueryService.OrderRow::terminal));
        if (settled == null) {
            throw new ApiException(409, "order_in_progress",
                    "취소가 처리 중입니다. 재조회하십시오.", null, 1);
        }
        return new Placement(settled, false, result.epoch());
    }

    /** by-client 취소(§4-B-2). 프로젝션 미반영은 저널 권위로 404·409를 가른다(§4-B-4-a). */
    public Placement cancelByClient(long userId, String clientOrderId) {
        Optional<OrderQueryService.OrderRow> row = orders.byClientOrderId(userId, clientOrderId);
        if (row.isPresent()) {
            return cancel(userId, row.get().externalId());
        }
        if (!orders.commandJournaled(userId, clientOrderId)) {
            throw new ApiException(404, "order_not_found", "주문이 없습니다.");
        }
        throw new ApiException(409, "order_in_progress",
                "주문이 처리 중입니다. 재조회하십시오.", null, 1);
    }

    public record BulkCancelResult(List<String> canceledIds,
                                   List<CancelAllResponse.Failure> failed) {
    }

    /** 일괄 취소(§4-B-3). 미체결(OPEN·PARTIAL) 전량을 개별 취소하고 성패를 per-item 집계한다. */
    public BulkCancelResult cancelAll(long userId, String market) {
        List<String> canceled = new ArrayList<>();
        List<CancelAllResponse.Failure> failed = new ArrayList<>();
        for (UUID externalId : orders.openOrderIds(userId, market)) {
            try {
                Placement placement = cancel(userId, externalId);
                if ("CANCELED".equals(placement.row().status())) {
                    canceled.add(externalId.toString());
                } else {
                    // 나열과 취소 사이에 체결·종결된 경합 분. 취소 대상이 아니라 실패로 집계한다.
                    failed.add(new CancelAllResponse.Failure(externalId.toString(),
                            placement.row().status().toLowerCase(Locale.ROOT)));
                }
            } catch (ApiException e) {
                failed.add(new CancelAllResponse.Failure(externalId.toString(), e.name()));
            }
        }
        return new BulkCancelResult(canceled, failed);
    }

    private long currentEpoch() {
        return jdbc.sql("SELECT COALESCE(max(epoch), 0) FROM leader_lease WHERE resource = 'matching-engine'")
                .query(Long.class)
                .single();
    }

    private SubmitResult submit(OrderIntake intake) {
        try {
            return bridge.submit(intake);
        } catch (IllegalStateException e) {
            // 루프 정지(리더십 상실 창). 저널 커밋 전 거부이므로 재조회 계약으로 수렴시킨다.
            throw new ApiException(503, "service_unavailable",
                    "페일오버 진행 중입니다. 재조회 후 판단하십시오.", null, 1);
        }
    }

    private OrderQueryService.OrderRow awaitRow(
            java.util.function.Supplier<Optional<OrderQueryService.OrderRow>> query) {
        long deadline = System.currentTimeMillis() + PROJECTION_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            Optional<OrderQueryService.OrderRow> row = query.get();
            if (row.isPresent()) {
                return row.get();
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 검증(§4-B-1 조건부 규칙 + §1.4 앱 가드)
    // ------------------------------------------------------------------

    private record ParsedOrder(MarketDirectory.Market market, OrderCommand.Side side,
                               OrderCommand.OrdType ordType, OrderCommand.TimeInForce timeInForce,
                               BigDecimal price, BigDecimal quantity, BigDecimal quoteOrderQty) {
        boolean buy() {
            return side == OrderCommand.Side.BUY;
        }

        /** 정산 예약 산식과 동일해야 가드가 유효하다(예약 UP 양자화, §1.4 v2.9 레짐). */
        BigDecimal reserve() {
            if (ordType == OrderCommand.OrdType.MARKET && side == OrderCommand.Side.BUY) {
                return quoteOrderQty;
            }
            if (side == OrderCommand.Side.BUY) {
                return Fees.quantize(price.multiply(quantity), market.quoteScale(), RoundingMode.UP);
            }
            return quantity;
        }
    }

    private ParsedOrder validate(JsonNode body) {
        MarketDirectory.Market market = markets.bySymbol(requireText(body, "market"));
        if (!"ACTIVE".equals(market.status())) {
            throw new ApiException(422, "domain_rejected", "마켓이 정지 상태입니다.",
                    Map.of("reject_reason", "market_suspended"), null);
        }
        OrderCommand.Side side = switch (requireText(body, "side")) {
            case "bid" -> OrderCommand.Side.BUY;
            case "ask" -> OrderCommand.Side.SELL;
            default -> throw invalid("side_enum");
        };
        OrderCommand.OrdType ordType = switch (requireText(body, "ord_type")) {
            case "limit" -> OrderCommand.OrdType.LIMIT;
            case "market" -> OrderCommand.OrdType.MARKET;
            case "best" -> OrderCommand.OrdType.BEST;
            default -> throw invalid("ord_type_enum");
        };
        OrderCommand.TimeInForce tif = null;
        if (body.hasNonNull("time_in_force")) {
            tif = switch (body.get("time_in_force").asText()) {
                case "GTC" -> OrderCommand.TimeInForce.GTC;
                case "IOC" -> OrderCommand.TimeInForce.IOC;
                case "FOK" -> OrderCommand.TimeInForce.FOK;
                case "POST_ONLY" -> OrderCommand.TimeInForce.POST_ONLY;
                default -> throw invalid("time_in_force_enum");
            };
        }
        BigDecimal price = decimal(body, "price");
        BigDecimal volume = decimal(body, "volume");

        // §4-B-1 상호배타 규칙(v2.6)
        if (tif == OrderCommand.TimeInForce.POST_ONLY && ordType != OrderCommand.OrdType.LIMIT) {
            throw invalid("post_only_limit_only");
        }
        BigDecimal quantity = null;
        BigDecimal quoteOrderQty = null;
        switch (ordType) {
            case LIMIT -> {
                if (price == null || volume == null) {
                    throw invalid("limit_requires_price_volume");
                }
                quantity = volume;
            }
            case MARKET -> {
                if (side == OrderCommand.Side.BUY) {
                    if (price == null || volume != null) {
                        throw invalid("market_bid_notional_only");
                    }
                    quoteOrderQty = price;
                    price = null;
                } else {
                    if (volume == null || price != null) {
                        throw invalid("market_ask_volume_only");
                    }
                    quantity = volume;
                }
            }
            case BEST -> {
                if (volume == null || price != null) {
                    throw invalid("best_requires_volume");
                }
                if (side == OrderCommand.Side.BUY) {
                    // best 매수는 volume만 지정하고 체결가가 오더북 의존이라 정적 예약 산식이 없다.
                    // 접수 시 reserve()가 price null로 NPE를 내 결정론적 500이 무한 반복되므로,
                    // idempotency insert 이전인 이 지점에서 명시 거부한다(§4-B-1 side 제약 연동).
                    throw invalid("best_bid_unsupported");
                }
                if (tif != OrderCommand.TimeInForce.IOC && tif != OrderCommand.TimeInForce.FOK) {
                    throw invalid("best_requires_ioc_or_fok");
                }
                quantity = volume;
            }
        }

        // 수치·마켓 규칙 가드
        if (price != null) {
            if (price.signum() <= 0 || price.remainder(market.tickSize()).signum() != 0) {
                throw new ApiException(400, "invalid_price", "가격 단위(tick)가 올바르지 않습니다.",
                        Map.of("tick", market.tickSize().stripTrailingZeros().toPlainString()), null);
            }
        }
        if (quantity != null) {
            if (quantity.signum() <= 0 || quantity.remainder(market.lotSize()).signum() != 0) {
                throw new ApiException(400, "invalid_volume", "수량 단위(lot)가 올바르지 않습니다.",
                        Map.of("lot", market.lotSize().stripTrailingZeros().toPlainString()), null);
            }
            if (quantity.compareTo(market.maxQty()) > 0) {
                throw new ApiException(400, "over_max_order", "최대 주문 수량을 초과했습니다.");
            }
        }
        if (quoteOrderQty != null) {
            if (quoteOrderQty.signum() <= 0) {
                throw invalid("notional_positive");
            }
            if (quoteOrderQty.stripTrailingZeros().scale() > 18) {
                // 예약은 notional 그대로(양자화 없음), 소진은 scale 18 DOWN이라
                // 초과 자리는 어느 경로로도 표현 불가다(§1.4 v2.10 앱 가드).
                throw invalid("notional_scale_exceeded");
            }
            if (market.quoteScale() != 18) {
                // MARKET BUY(notional) 허용 마켓은 quote scale 18 필수(§1.4 v2.9 앱 가드).
                throw invalid("market_buy_quote_scale_18");
            }
        }
        BigDecimal notional = quoteOrderQty != null ? quoteOrderQty
                : price != null ? price.multiply(quantity) : null;
        if (notional != null) {
            if (notional.compareTo(market.minNotional()) < 0) {
                throw new ApiException(400, "under_min_order", "최소 주문 금액에 미달합니다.",
                        Map.of("min", market.minNotional().stripTrailingZeros().toPlainString()), null);
            }
            if (notional.compareTo(market.maxNotional()) > 0) {
                throw new ApiException(400, "over_max_order", "최대 주문 금액을 초과했습니다.");
            }
        }
        return new ParsedOrder(market, side, ordType, tif, price, quantity, quoteOrderQty);
    }

    private BigDecimal availableBalance(long userId, short assetId) {
        return jdbc.sql("SELECT available FROM user_balances WHERE user_id = ? AND asset_id = ?")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO);
    }

    private long nextOrderId() {
        return jdbc.sql("SELECT next_journal_value('order_id')")
                .query(Long.class)
                .single();
    }

    private static ApiException invalid(String constraint) {
        return new ApiException(400, "invalid_parameter", "파라미터 조합이 올바르지 않습니다.",
                Map.of("constraint", constraint), null);
    }

    private static String requireText(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "invalid_parameter", "필수 필드 누락: " + field);
        }
        return value;
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }

    private static BigDecimal decimal(JsonNode body, String field) {
        if (!body.hasNonNull(field)) {
            return null;
        }
        try {
            return new BigDecimal(body.get(field).asText());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", field + " 수치 형식이 올바르지 않습니다.");
        }
    }
}

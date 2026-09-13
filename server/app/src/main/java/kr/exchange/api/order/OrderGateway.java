package kr.exchange.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.auth.CanonicalRequests;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.dto.CancelAllResponse;
import kr.exchange.engine.EngineBridge;
import kr.exchange.ledger.ProjectionSignal;
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
    // 신호 대기 상한. 전체 대기 예산(5초)보다 짧게 두어 신호가 오지 않는 경로에 폴링 몫을 남긴다.
    private static final long SIGNAL_WAIT_MS = 3_000;
    private static final long RESERVE_DEFER_TTL_MS = 30_000;

    private final JdbcClient jdbc;
    private final EngineBridge bridge;
    private final MarketDirectory markets;
    private final OrderQueryService orders;
    private final InflightReserves inflight;
    // 프로젝션 반영을 알리는 신호. 없으면 종전대로 폴링만 한다.
    private ProjectionSignal signal;

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

    /**
     * 프로젝션 신호를 붙인다. 선택 배선이라 붙이지 않으면 폴링만으로 동작한다.
     * 생성자를 늘리지 않는 이유는 기존 호출부와 테스트를 신호와 무관하게 두기 위해서다.
     */
    public void attachProjectionSignal(ProjectionSignal projectionSignal) {
        this.signal = projectionSignal;
    }

    /** in-flight 예약 락 획득 대기 누적 나노초. */
    public long inflightLockWaitNanos() {
        return inflight.lockWaitNanos();
    }

    /** in-flight 예약 락 보유 누적 나노초. */
    public long inflightLockHoldNanos() {
        return inflight.lockHoldNanos();
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
        // 이 요청의 내용이 기존 키의 내용과 같음을 실제로 대조했는지. 저널 멱등 replay를 200으로
        // 돌려줘도 되는지 가르는 유일한 근거다(아래 replay 판정 참조).
        boolean contentVerified = false;
        if (stored.isPresent()) {
            requireSameContent(stored.get(), requestHash);
            contentVerified = true;
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
                    contentVerified = true;
                } else {
                    insertedKey = true;
                }
            }
        }
        boolean journaled = false;
        boolean replayed = false;
        // 저널 기록 여부와 무관하게 이번에 넣은 키 행을 폐기해야 하는 경우(대조 불가 재사용 거절)
        boolean discardKey = false;
        OrderQueryService.OrderRow row = null;
        long orderId = 0;
        try {
            final long assignedId = nextOrderId();
            orderId = assignedId;
            // 제출 전에 자리를 잡는다. 제출 뒤에 잡으면 정산이 먼저 끝나 신호를 놓친다.
            // 자리 회수는 이 메서드의 finally 가 한다.
            if (signal != null) {
                signal.register(assignedId);
            }
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
                        "페일오버 진행 중. 재조회 후 판단 필요", null, 1);
            }
            replayed = result.replayed();

            // 저널이 같은 client_order_id를 이미 갖고 있는데(replay) 내용 대조를 한 번도 못 했다면
            // 200으로 원 주문을 돌려줄 근거가 없다. idempotency_keys가 비어 있었다는 뜻이고, 그
            // 원인은 보존 기간 경과 삭제다(DB 설계: pg_cron이 7일 지난 행을 지운다. API 설계는
            // client_order_id 재사용을 무기한 금지하고 UNIQUE 권위를 command_journal에 둔다).
            // 이 상태에서 내용이 다른 재사용을 replay로 흡수하면 요청과 무관한 주문을 200으로
            // 돌려주게 되어 멱등 계약이 깨진다. 대조 불가일 때는 재사용으로 보고 거절한다.
            // 데모 마이그레이션에는 아직 퍼지가 배선돼 있지 않아 현재는 도달하지 않는 방어선이다.
            if (replayed && !contentVerified) {
                // 이번 요청 해시로 넣은 키 행은 원 주문 내용과 무관하다. 남겨두면 다음 재시도가
                // 그 해시로 대조에 성공해 같은 결함이 그대로 재현된다. 정리 대상으로 표시한다.
                discardKey = true;
                throw new ApiException(409, "duplicate_client_order_id",
                        "이미 사용한 client_order_id. 새 키 필요");
            }

            row = result.replayed()
                    ? awaitRow(() -> orders.byClientOrderId(userId, clientOrderId), assignedId)
                    : awaitRow(() -> orders.byInternalId(assignedId), assignedId);
            if (row == null) {
                throw new ApiException(409, "order_in_progress",
                        "주문 처리 중. 재조회 필요", null, 1);
            }
            if (!result.replayed() && "REJECTED".equals(row.status())) {
                throw new ApiException(422, "domain_rejected", "도메인 검증에서 거부됨",
                        Map.of("reject_reason", row.rejectReason() == null ? "rejected"
                                : row.rejectReason()), null);
            }
            return new Placement(row, result.replayed(), result.epoch());
        } finally {
            // 신호 자리 회수. 성공·실패·예외 어느 경로로 나가도 반드시 지운다.
            if (signal != null && orderId > 0) {
                signal.forget(orderId);
            }
            if (insertedKey && (!journaled || discardKey)) {
                // 저널 미기록 확정(페일오버 창 submit 실패 등)이면 선삽입한 고아 멱등 키를 정리해
                // 다음 재시도가 잔고 가드를 재적용하게 한다. 정리 실패해도 재시도 경로의
                // command_journal 실재 확인이 backstop이라 가드 무력화로 이어지지 않는다.
                // discardKey는 저널에는 있으나 내용 대조가 불가해 재사용으로 거절한 경우다. 이번
                // 요청 해시가 남으면 다음 재시도가 그 해시로 대조에 성공해 거절이 무력화된다.
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
                .orElseThrow(() -> new ApiException(404, "order_not_found", "주문 없음"));
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
                    "취소 처리 중. 재조회 필요", null, 1);
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
            throw new ApiException(404, "order_not_found", "주문 없음");
        }
        throw new ApiException(409, "order_in_progress",
                "주문 처리 중. 재조회 필요", null, 1);
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
                    "페일오버 진행 중. 재조회 후 판단 필요", null, 1);
        }
    }

    private OrderQueryService.OrderRow awaitRow(
            java.util.function.Supplier<Optional<OrderQueryService.OrderRow>> query) {
        return awaitRow(query, 0L);
    }

    /**
     * 프로젝션에 행이 생길 때까지 기다린다.
     *
     * orderId가 양수이고 신호가 붙어 있으면 먼저 신호를 기다린다. 정산이 같은 JVM에서 커밋
     * 직후 깨우므로 그 뒤 조회 한 번이면 끝난다. 2026-09-04 실측에서 이 조회가 주문 하나당
     * 약 100회였고(20ms 폴링 x 대기 시간), 그것이 전체 DB 호출의 절반을 넘었다.
     *
     * 신호가 오지 않으면 종전 폴링으로 넘어간다. 다른 노드의 엔진이 처리한 경우, 신호 등록이
     * 상한에 걸린 경우, 신호 배선이 없는 경우가 그에 해당한다. 곧 신호는 빠른 길일 뿐이고
     * 정확성은 폴링이 그대로 보장한다.
     */
    private OrderQueryService.OrderRow awaitRow(
            java.util.function.Supplier<Optional<OrderQueryService.OrderRow>> query, long orderId) {
        long deadline = System.currentTimeMillis() + PROJECTION_WAIT_MS;
        // 먼저 한 번 조회한다. 멱등 재생처럼 이미 반영된 경우가 여기서 끝난다. 그 경우 신호는
        // 오지 않는다(원본 주문이 다른 id로 이미 정산됐다). 조회를 신호 뒤로 미루면 그 경로가
        // 신호를 기다리다 예산을 소진하고 409로 끝난다. 실측으로 재현했다(2026-09-04).
        Optional<OrderQueryService.OrderRow> first = query.get();
        if (first.isPresent()) {
            return first.get();
        }
        // 신호를 기다리되 예산의 일부만 쓴다. 신호가 오지 않는 경로가 폴링 기회를 잃으면
        // 종전보다 나빠진다. 남는 예산이 폴링 몫이다.
        if (orderId > 0 && signal != null) {
            long slice = Math.min(SIGNAL_WAIT_MS, deadline - System.currentTimeMillis());
            if (slice > 0 && signal.await(orderId, slice)) {
                Optional<OrderQueryService.OrderRow> row = query.get();
                if (row.isPresent()) {
                    return row.get();
                }
            }
        }
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
                throw new ApiException(400, "invalid_price", "가격 단위(tick) 오류",
                        Map.of("tick", market.tickSize().stripTrailingZeros().toPlainString()), null);
            }
        }
        if (quantity != null) {
            if (quantity.signum() <= 0 || quantity.remainder(market.lotSize()).signum() != 0) {
                throw new ApiException(400, "invalid_volume", "수량 단위(lot) 오류",
                        Map.of("lot", market.lotSize().stripTrailingZeros().toPlainString()), null);
            }
            if (quantity.compareTo(market.maxQty()) > 0) {
                throw new ApiException(400, "over_max_order", "최대 주문 수량 초과");
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
                throw new ApiException(400, "over_max_order", "최대 주문 금액 초과");
            }
        }
        return new ParsedOrder(market, side, ordType, tif, price, quantity, quoteOrderQty);
    }

    /**
     * 예약 자산의 가용 잔고. 계정이 없으면 접수 단계에서 거부한다(정본 13.5 한계 40).
     *
     * 종전에는 계정이 없어도 잔고 0 으로 보고 그대로 진행했다. 그러면 정산이 그 계정을
     * 찾다가 예외를 던지고, 그 예외가 단일 라이터를 fail-fast 로 멈추며 리더십까지 함께
     * 내려놓는다. 2026-09-10 실측에서 그 순서를 그대로 봤다. 계정 없음 예외, 라이터 정지,
     * 리더십 상실이다. 곧 한 사용자의 잘못된 주문 하나가 노드를 통째로 내렸다.
     *
     * 잔고 0 과 계정 부재는 다른 사실이다. 매수는 예약액이 금액이라 잔고 0 이면 가드에
     * 걸리지만, 매도는 예약액이 수량이라 그 자산 잔고를 보지 않고 통과할 수 있다.
     * 그래서 잔고로 대신 막을 수 없고 계정 실재를 따로 확인해야 한다.
     *
     * 400 으로 돌려보낸다. 클라이언트가 고칠 수 있는 요청 문제이고, 서버 용량 문제가
     * 아니므로 503 이 아니다.
     *
     * user_balances 조회로 계정 실재를 판정해도 되는 근거는 스키마에 있다. 그 뷰는
     * accounts 와 balances 의 조인이고(V1__init.sql:135-141), balances 행은 계정이
     * 생길 때 트리거가 반드시 만든다(trg_account_balance, V1__init.sql:131). 곧 계정이
     * 있는데 뷰에 안 나오는 상태가 물리적으로 생기지 않는다. 실측으로도 확인했다.
     * 사용자 계정 1,570개 전부가 잔고 행을 가지고 있고 없는 것이 0개다.
     *
     * 그 트리거가 사라지면 이 판정이 정상 계정을 오거부한다. 트리거를 건드릴 때
     * 이 자리를 함께 봐야 한다.
     */
    private BigDecimal availableBalance(long userId, short assetId) {
        return jdbc.sql("SELECT available FROM user_balances WHERE user_id = ? AND asset_id = ?")
                .params(userId, assetId)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> new ApiException(400, "account_not_found",
                        "해당 자산 계좌 없음",
                        Map.of("asset_id", String.valueOf(assetId)), null));
    }

    /**
     * 주문 id 사전 채번(V31 order_id_seq).
     *
     * 종전에는 `next_journal_value('order_id')` 를 썼다. 그것은 카운터 한 행을 UPDATE 로 잠그고
     * 트랜잭션이 끝날 때까지 쥔다. 이 메서드는 트랜잭션 밖에서 도므로 한 문장이 곧 한
     * 트랜잭션이고, **주문마다 id 채번만을 위한 커밋과 WAL 플러시가 한 번 더 일어났다.**
     * 접수는 수용 정원만큼 동시에 들어오므로 그 잠금에서 서로 줄을 섰다.
     *
     * 2026-09-07 실측에서 그 문장이 명령당 2.73 회에 총 3202ms 로 fencing 의 12 배였다.
     * nextval 은 행 잠금을 잡지 않고 캐시 구간 안에서는 플러시도 없다.
     *
     * 구멍이 생기는 것은 계약상 허용된다. V2__gateway_counters.sql 이 "gapless는 요구되지
     * 않으나 채번 경로는 journal_counters 단일 방식으로 통일한다" 고 적었다. 통일성이 이유였고
     * 정확성이 아니었다. 실제 사용처는 전부 조인 키와 존재 검사다.
     */
    private long nextOrderId() {
        return jdbc.sql("SELECT nextval('order_id_seq')")
                .query(Long.class)
                .single();
    }

    private static ApiException invalid(String constraint) {
        return new ApiException(400, "invalid_parameter", "파라미터 조합 오류",
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
            throw new ApiException(400, "invalid_parameter", field + " 수치 형식 오류");
        }
    }
}

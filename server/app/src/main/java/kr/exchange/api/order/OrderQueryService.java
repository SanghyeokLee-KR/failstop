package kr.exchange.api.order;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.Cursors;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** orders 프로젝션 조회와 §4-B-1 응답 스키마 변환 단일 지점. */
public class OrderQueryService {

    public record OrderRow(long id, UUID externalId, long userId, int marketId,
                           String marketSymbol, String clientOrderId, String side, String ordType,
                           String timeInForce, BigDecimal price, BigDecimal quantity,
                           BigDecimal quoteOrderQty, BigDecimal filledQty, BigDecimal remainingQty,
                           BigDecimal executedQuote, BigDecimal lockedAmount, String status,
                           String rejectReason, Long commandSeq, BigDecimal paidFee, long tradesCount,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        boolean notional() {
            return quoteOrderQty != null;
        }

        boolean terminal() {
            return "FILLED".equals(status) || "CANCELED".equals(status) || "REJECTED".equals(status);
        }
    }

    private static final String SELECT = """
            SELECT o.id, o.external_id, o.user_id, o.market_id, m.symbol AS market_symbol,
                   o.client_order_id, o.side, o.ord_type, o.time_in_force, o.price, o.quantity,
                   o.quote_order_qty, o.filled_qty, o.remaining_qty, o.executed_quote,
                   o.locked_amount, o.status, o.reject_reason, o.command_seq,
                   o.paid_fee, o.trades_count, o.created_at, o.updated_at
            FROM orders o JOIN markets m ON m.id = o.market_id
            """;

    private final JdbcClient jdbc;

    public OrderQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderRow> byInternalId(long orderId) {
        return jdbc.sql(SELECT + " WHERE o.id = ?").params(orderId).query(this::map).optional();
    }

    public Optional<OrderRow> byExternalId(UUID externalId) {
        return jdbc.sql(SELECT + " WHERE o.external_id = ?")
                .params(externalId).query(this::map).optional();
    }

    public Optional<OrderRow> byClientOrderId(long userId, String clientOrderId) {
        return jdbc.sql(SELECT + " WHERE o.user_id = ? AND o.client_order_id = ?")
                .params(userId, clientOrderId).query(this::map).optional();
    }

    public List<OrderRow> page(long userId, boolean open, String marketSymbol,
                               Cursors.Cursor cursor, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE o.user_id = ? AND o.status ")
                .append(open ? "IN ('OPEN','PARTIAL')" : "IN ('FILLED','CANCELED','REJECTED')");
        List<Object> params = new ArrayList<>(List.of(userId));
        if (marketSymbol != null) {
            sql.append(" AND m.symbol = ?");
            params.add(marketSymbol);
        }
        if (cursor != null) {
            // 커서 ts는 밀리초 절단이라 같은 밀리초·상위 마이크로초 행을 다음 페이지에서 누락한다.
            // 절단된 ts 대신 커서 행의 실제 created_at·id를 직접 바인딩한다(마이크로초 보존).
            // 커서 행은 user_id 스코프로 조회한다: 타 사용자 주문 UUID를 커서로 넘겨 정렬 위치를
            // 캐던 무경계 서브쿼리를 제거한다. 부재·위조 커서는 형식오류와 동일하게 400으로 정규화해
            // 조용한 빈 페이지를 없앤다.
            UUID cursorId = parseCursorId(cursor.id());
            CursorAnchor anchor = jdbc.sql("SELECT created_at, id FROM orders WHERE user_id = ? AND external_id = ?")
                    .params(userId, cursorId)
                    .query((rs, rn) -> new CursorAnchor(rs.getObject("created_at", OffsetDateTime.class), rs.getLong("id")))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서가 유효하지 않습니다."));
            sql.append(" AND (o.created_at, o.id) < (?, ?)");
            params.add(anchor.createdAt());
            params.add(anchor.id());
        }
        sql.append(" ORDER BY o.created_at DESC, o.id DESC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    private record CursorAnchor(OffsetDateTime createdAt, long id) {
    }

    /** 조작 커서 방어(§2-6): 비UUID id는 500이 아니라 400으로 정규화한다. */
    private static UUID parseCursorId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid_parameter", "커서 형식이 올바르지 않습니다.");
        }
    }

    public List<UUID> openOrderIds(long userId, String marketSymbol) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.external_id FROM orders o JOIN markets m ON m.id = o.market_id"
                        + " WHERE o.user_id = ? AND o.status IN ('OPEN','PARTIAL')");
        List<Object> params = new ArrayList<>(List.of(userId));
        if (marketSymbol != null) {
            sql.append(" AND m.symbol = ?");
            params.add(marketSymbol);
        }
        sql.append(" ORDER BY o.id");
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, rowNum) -> rs.getObject("external_id", UUID.class)).list();
    }

    /** §4-B-1 단건 응답 본문. epoch·seq는 호출측이 채운다(도메인이 다르다). */
    public Map<String, Object> view(OrderRow row, long epoch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("id", row.externalId().toString());
        body.put("client_order_id", row.clientOrderId());
        body.put("market", row.marketSymbol());
        body.put("side", "BUY".equals(row.side()) ? "bid" : "ask");
        body.put("ord_type", row.ordType().toLowerCase(java.util.Locale.ROOT));
        body.put("status", row.status());
        body.put("price", row.price() != null ? ApiFormats.amount(row.price())
                : row.notional() ? ApiFormats.amount(row.quoteOrderQty()) : null);
        body.put("volume", ApiFormats.amount(row.quantity()));
        body.put("remaining_volume", ApiFormats.amount(row.remainingQty()));
        body.put("executed_volume", ApiFormats.amount(row.filledQty()));
        body.put("executed_funds", ApiFormats.amount(row.executedQuote()));
        body.put("remaining_notional", row.notional() ? ApiFormats.amount(row.lockedAmount()) : null);
        // paid_fee·trades_count는 orders에 물질화된 프로젝션(V10). 조회마다 trades를 재집계하지
        // 않는다. 정산 체결 경로가 결정론적으로 누적하고 재생 시 재구성한다(DB 설계 §5.2).
        body.put("paid_fee", ApiFormats.amount(row.paidFee()));
        body.put("trades_count", row.tradesCount());
        body.put("reject_reason", row.rejectReason());
        body.put("created_at", ApiFormats.time(row.createdAt()));
        if ("CANCELED".equals(row.status())) {
            body.put("canceled_at", ApiFormats.time(row.updatedAt()));
        }
        body.put("epoch", epoch);
        body.put("seq", row.commandSeq());
        return body;
    }

    private OrderRow map(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("id"), rs.getObject("external_id", UUID.class), rs.getLong("user_id"),
                rs.getInt("market_id"), rs.getString("market_symbol"),
                rs.getString("client_order_id"), rs.getString("side"), rs.getString("ord_type"),
                rs.getString("time_in_force"), rs.getBigDecimal("price"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("quote_order_qty"),
                rs.getBigDecimal("filled_qty"), rs.getBigDecimal("remaining_qty"),
                rs.getBigDecimal("executed_quote"), rs.getBigDecimal("locked_amount"),
                rs.getString("status"), rs.getString("reject_reason"),
                (Long) rs.getObject("command_seq"),
                rs.getBigDecimal("paid_fee"), rs.getLong("trades_count"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}

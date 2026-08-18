package kr.exchange.api.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.market.MarketDirectory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 내 체결·원장 내역(§4-C-2, §4-C-3). 둘 다 읽기 전용 투영이라 정산 계약에 관여하지 않는다.
 * 스키마 변경도 없다(trades.taker_side·ledger_entries.entry_type이 이미 정본이다).
 *
 * 인증은 ApiAuthFilter가 건다(제외 목록 밖 경로). 레이트리밋은 §4-C 표의 weight 2이고
 * '계좌/주문 조회' 단일 버킷을 /accounts·/orders/open과 공유한다. 별도 버킷을 새로 파면
 * 같은 범주의 예산이 갈라져 계약 한도(200토큰/10s)가 사실상 배가된다.
 */
@RestController
@RequestMapping("/api/v1/my")
public class MyController {

    private static final int MAX_COUNT = 200;
    private static final int DEFAULT_COUNT = 100;
    private static final int QUERY_WEIGHT = 2;

    private final JdbcClient jdbc;
    private final MarketDirectory markets;
    private final AccountRateLimiter queryLimiter;

    public MyController(JdbcClient jdbc, MarketDirectory markets,
                        @Qualifier("accountQueryRateLimiter")
                        AccountRateLimiter accountQueryRateLimiter) {
        this.jdbc = jdbc;
        this.markets = markets;
        this.queryLimiter = accountQueryRateLimiter;
    }

    /**
     * 체결 내역(§4-C-2). maker·taker 축을 각각 자기 인덱스로 훑고 합친다
     * (idx_trades_maker_time·idx_trades_taker_time). `OR`로 한 번에 쓰면 두 인덱스가 비트맵으로
     * 합쳐진 뒤 전체 정렬이 얹혀 깊은 페이지에서 비용이 커진다.
     */
    @GetMapping("/trades")
    public Map<String, Object> trades(HttpServletRequest request, HttpServletResponse response,
                                      @RequestParam(value = "market", required = false) String market,
                                      @RequestParam(value = "count", required = false) Integer count,
                                      @RequestParam(value = "cursor", required = false) String cursor) {
        AuthPrincipal principal = principal(request, response);
        long userId = principal.userId();
        int limit = clampCount(count);
        Integer marketId = market == null ? null : markets.bySymbol(market).id();
        Cursors.Cursor decoded = cursor == null ? null : Cursors.decode(cursor);

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.market_id, u.price, u.quantity, u.taker_side, u.created_at,
                       u.liquidity_flag, u.fee, o.external_id AS order_id
                FROM (
                """);
        // maker 축과 taker 축을 같은 필터로 각각 뜬 뒤 합친다. 자기체결은 STP가 막지만(§4-B-1
        // self_trade_prevented) 만에 하나 성립하면 같은 trade_id가 두 행이 되어 커서 id 축이
        // 중복되고 페이지가 제자리를 맴돈다. taker 축에서 자기 maker를 빼 한 행으로 접는다.
        appendSide(sql, params, "maker", userId, marketId, decoded, limit);
        sql.append("    UNION ALL\n");
        appendSide(sql, params, "taker", userId, marketId, decoded, limit);
        // 주문 조인은 LEFT다. 고아 체결(FK 생략 구간)이 있어도 내 체결 내역에서 행이 사라지는
        // 것보다 order_id가 null인 편이 낫다.
        sql.append("""
                ) u
                LEFT JOIN orders o ON o.id = u.my_order_id
                ORDER BY u.created_at DESC, u.id DESC
                LIMIT ?
                """);
        params.add(limit);

        Map<Integer, MarketDirectory.Market> directory = marketsById();
        List<Map<String, Object>> rows = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> {
                    MarketDirectory.Market m = directory.get(rs.getInt("market_id"));
                    String liquidityFlag = rs.getString("liquidity_flag");
                    // taker_side는 taker 관점이다. 내가 maker면 내 방향은 그 반대다.
                    boolean takerBuy = "BUY".equals(rs.getString("taker_side"));
                    boolean mineBuy = "TAKER".equals(liquidityFlag) == takerBuy;
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("trade_id", String.valueOf(rs.getLong("id")));
                    row.put("market", m == null ? null : m.symbol());
                    row.put("side", mineBuy ? "bid" : "ask");
                    row.put("order_id", rs.getString("order_id"));
                    row.put("price", ApiFormats.amount(rs.getBigDecimal("price")));
                    row.put("volume", ApiFormats.amount(rs.getBigDecimal("quantity")));
                    row.put("liquidity_flag", liquidityFlag);
                    row.put("fee", ApiFormats.amount(rs.getBigDecimal("fee")));
                    // 수수료는 수취 자산으로 뗀다(§4-B-5 v2.7). 매수는 base, 매도는 quote라
                    // 금액만 주면 어느 자산인지 응답에서 확정되지 않는다.
                    row.put("fee_currency", m == null ? null
                            : (mineBuy ? m.baseSymbol() : m.quoteSymbol()));
                    row.put("timestamp", ApiFormats.time(createdAt));
                    row.put("cursor_ts", createdAt);
                    return row;
                })
                .list();
        return envelope(rows, "trade_id", limit);
    }

    /**
     * 원장 내역(§4-C-3). 복식부기 분개의 사용자 관점 투영이며 entry_type 어휘는 DB 정본
     * ledger_entries와 동일하다(DEPOSIT/WITHDRAW/TRADE/FEE/RESERVE/RELEASE/GENESIS/REVERSAL).
     * 시스템 계정(FEE_INCOME 등) 분개는 owner_type='USER' 스코프에서 자연히 빠진다.
     */
    @GetMapping("/ledger")
    public Map<String, Object> ledger(HttpServletRequest request, HttpServletResponse response,
                                      @RequestParam(value = "currency", required = false) String currency,
                                      @RequestParam(value = "count", required = false) Integer count,
                                      @RequestParam(value = "cursor", required = false) String cursor) {
        AuthPrincipal principal = principal(request, response);
        int limit = clampCount(count);
        Short assetId = currency == null ? null : assetId(currency);
        Cursors.Cursor decoded = cursor == null ? null : Cursors.decode(cursor);

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.amount, e.entry_type, e.created_at,
                       a.symbol AS currency,
                       lt.business_event_type, lt.business_event_id,
                       o.external_id AS order_id
                FROM ledger_entries e
                JOIN ledger_transactions lt ON lt.id = e.transaction_id
                JOIN assets a ON a.id = e.asset_id
                LEFT JOIN orders o
                       ON lt.business_event_type = 'RESERVE' AND o.id = lt.business_event_id
                WHERE e.account_id IN (
                          SELECT id FROM accounts WHERE owner_type = 'USER' AND owner_id = ?)
                """);
        params.add(principal.userId());
        if (assetId != null) {
            sql.append("  AND e.asset_id = ?\n");
            params.add(assetId);
        }
        if (decoded != null) {
            sql.append("  AND (e.created_at, e.id) < (?, ?)\n");
            params.add(decoded.ts());
            params.add(numericCursorId(decoded.id()));
        }
        sql.append("ORDER BY e.created_at DESC, e.id DESC\nLIMIT ?");
        params.add(limit);

        List<Map<String, Object>> rows = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> {
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("entry_id", String.valueOf(rs.getLong("id")));
                    row.put("currency", rs.getString("currency"));
                    row.put("amount", ApiFormats.amount(rs.getBigDecimal("amount")));
                    row.put("entry_type", rs.getString("entry_type"));
                    row.put("ref", ref(rs.getString("business_event_type"),
                            rs.getLong("business_event_id"), rs.getString("order_id")));
                    row.put("timestamp", ApiFormats.time(createdAt));
                    row.put("cursor_ts", createdAt);
                    return row;
                })
                .list();
        return envelope(rows, "entry_id", limit);
    }

    /**
     * 분개가 가리키는 업무 이벤트(§4-C-3 `ref`). business_event_id는 타입마다 축이 다르다
     * (TRADE=event_seq, RESERVE=주문 내부 id, RELEASE=해제 유발 event_seq, GENESIS=발행 일련).
     * 내부 주문 PK는 노출하지 않으므로 RESERVE만 외부 UUID로 치환한다.
     */
    private static Map<String, Object> ref(String type, long eventId, String orderExternalId) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", type);
        if ("TRADE".equals(type)) {
            ref.put("trade_id", String.valueOf(eventId));
        } else if ("RESERVE".equals(type)) {
            ref.put("order_id", orderExternalId);
        } else {
            ref.put("id", String.valueOf(eventId));
        }
        return ref;
    }

    private void appendSide(StringBuilder sql, List<Object> params, String side, long userId,
                            Integer marketId, Cursors.Cursor cursor, int limit) {
        sql.append("    (SELECT t.id, t.market_id, t.price, t.quantity, t.taker_side, t.created_at,\n")
                .append("            '").append("maker".equals(side) ? "MAKER" : "TAKER")
                .append("' AS liquidity_flag, t.").append(side).append("_fee AS fee,\n")
                .append("            t.").append(side).append("_order_id AS my_order_id\n")
                .append("       FROM trades t\n")
                .append("      WHERE t.").append(side).append("_user_id = ?\n");
        params.add(userId);
        if ("taker".equals(side)) {
            sql.append("        AND t.maker_user_id <> ?\n");
            params.add(userId);
        }
        if (marketId != null) {
            sql.append("        AND t.market_id = ?\n");
            params.add(marketId);
        }
        if (cursor != null) {
            sql.append("        AND (t.created_at, t.id) < (?, ?)\n");
            params.add(cursor.ts());
            params.add(numericCursorId(cursor.id()));
        }
        // 각 축에서 limit건만 뜨면 병합 후 상위 limit건이 전체 정렬과 일치한다(둘 다 같은 축으로
        // 내림차순이라 잘려나간 행은 반드시 유지된 행보다 과거다).
        sql.append("      ORDER BY t.created_at DESC, t.id DESC\n      LIMIT ?)\n");
        params.add(limit);
    }

    private Map<Integer, MarketDirectory.Market> marketsById() {
        Map<Integer, MarketDirectory.Market> byId = new HashMap<>();
        for (MarketDirectory.Market m : markets.all()) {
            byId.put(m.id(), m);
        }
        return byId;
    }

    private short assetId(String currency) {
        return jdbc.sql("SELECT id FROM assets WHERE symbol = ?")
                .params(currency)
                .query(Short.class)
                .optional()
                .orElseThrow(() -> new ApiException(400, "invalid_parameter",
                        "존재하지 않는 통화입니다: " + currency));
    }

    private AuthPrincipal principal(HttpServletRequest request, HttpServletResponse response) {
        AuthPrincipal principal = OrderController.principal(request);
        queryLimiter.enforce(response, String.valueOf(principal.userId()), QUERY_WEIGHT);
        return principal;
    }

    private static int clampCount(Integer count) {
        if (count == null) {
            return DEFAULT_COUNT;
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new ApiException(400, "invalid_parameter", "count는 1~" + MAX_COUNT + "이어야 합니다.");
        }
        return count;
    }

    /** 커서 id 축은 수치 식별자다. 조작 커서가 500으로 새지 않게 400으로 정규화한다(§2-6). */
    private static long numericCursorId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", "커서 형식이 올바르지 않습니다.");
        }
    }

    private static Map<String, Object> envelope(List<Map<String, Object>> rows, String idField,
                                                int limit) {
        String nextCursor = null;
        if (rows.size() == limit && !rows.isEmpty()) {
            Map<String, Object> last = rows.get(rows.size() - 1);
            nextCursor = Cursors.encode((OffsetDateTime) last.get("cursor_ts"),
                    (String) last.get(idField));
        }
        rows.forEach(r -> r.remove("cursor_ts"));
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("next_cursor", nextCursor);
        pagination.put("has_more", nextCursor != null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("data", rows);
        body.put("pagination", pagination);
        return body;
    }
}

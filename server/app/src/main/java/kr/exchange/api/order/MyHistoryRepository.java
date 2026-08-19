package kr.exchange.api.order;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 내 체결·원장 내역 SQL 단일 지점(§4-C-2, §4-C-3). 둘 다 읽기 전용 투영이라 정산 계약에
 * 관여하지 않는다. 커서는 (created_at, id) 내림차순 축이고 ts·id는 호출측이 검증해 넘긴다.
 */
public class MyHistoryRepository {

    private final JdbcClient jdbc;

    public MyHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TradeRow(long id, int marketId, BigDecimal price, BigDecimal quantity,
                           String takerSide, OffsetDateTime createdAt, String liquidityFlag,
                           BigDecimal fee, String orderId) {
    }

    public record LedgerRow(long id, BigDecimal amount, String entryType, OffsetDateTime createdAt,
                            String currency, String businessEventType, long businessEventId,
                            String orderId) {
    }

    /**
     * 체결 내역(§4-C-2). maker·taker 축을 각각 자기 인덱스로 훑고 합친다
     * (idx_trades_maker_time·idx_trades_taker_time). `OR`로 한 번에 쓰면 두 인덱스가 비트맵으로
     * 합쳐진 뒤 전체 정렬이 얹혀 깊은 페이지에서 비용이 커진다.
     */
    public List<TradeRow> trades(long userId, Integer marketId, OffsetDateTime cursorTs,
                                 Long cursorId, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.market_id, u.price, u.quantity, u.taker_side, u.created_at,
                       u.liquidity_flag, u.fee, o.external_id AS order_id
                FROM (
                """);
        // maker 축과 taker 축을 같은 필터로 각각 뜬 뒤 합친다. 자기체결은 STP가 막지만(§4-B-1
        // self_trade_prevented) 만에 하나 성립하면 같은 trade_id가 두 행이 되어 커서 id 축이
        // 중복되고 페이지가 제자리를 맴돈다. taker 축에서 자기 maker를 빼 한 행으로 접는다.
        appendSide(sql, params, "maker", userId, marketId, cursorTs, cursorId, limit);
        sql.append("    UNION ALL\n");
        appendSide(sql, params, "taker", userId, marketId, cursorTs, cursorId, limit);
        // 주문 조인은 LEFT다. 고아 체결(FK 생략 구간)이 있어도 내 체결 내역에서 행이 사라지는
        // 것보다 order_id가 null인 편이 낫다.
        sql.append("""
                ) u
                LEFT JOIN orders o ON o.id = u.my_order_id
                ORDER BY u.created_at DESC, u.id DESC
                LIMIT ?
                """);
        params.add(limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> new TradeRow(
                        rs.getLong("id"),
                        rs.getInt("market_id"),
                        rs.getBigDecimal("price"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("taker_side"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("liquidity_flag"),
                        rs.getBigDecimal("fee"),
                        rs.getString("order_id")))
                .list();
    }

    /**
     * 원장 내역(§4-C-3). entry_type 어휘는 DB 정본 ledger_entries와 동일하다. 시스템 계정
     * (FEE_INCOME 등) 분개는 owner_type='USER' 스코프에서 자연히 빠진다. orders 조인은 RESERVE
     * 한정 조건부 LEFT다. 일반 조인으로 바꾸면 다른 타입의 business_event_id가 우연히 주문 PK와
     * 충돌해 오염된다.
     */
    public List<LedgerRow> ledger(long userId, Short assetId, OffsetDateTime cursorTs,
                                  Long cursorId, int limit) {
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
        params.add(userId);
        if (assetId != null) {
            sql.append("  AND e.asset_id = ?\n");
            params.add(assetId);
        }
        if (cursorTs != null) {
            sql.append("  AND (e.created_at, e.id) < (?, ?)\n");
            params.add(cursorTs);
            params.add(cursorId);
        }
        sql.append("ORDER BY e.created_at DESC, e.id DESC\nLIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> new LedgerRow(
                        rs.getLong("id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("entry_type"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("currency"),
                        rs.getString("business_event_type"),
                        rs.getLong("business_event_id"),
                        rs.getString("order_id")))
                .list();
    }

    public Optional<Short> assetId(String symbol) {
        return jdbc.sql("SELECT id FROM assets WHERE symbol = ?")
                .params(symbol)
                .query(Short.class)
                .optional();
    }

    private static void appendSide(StringBuilder sql, List<Object> params, String side, long userId,
                                   Integer marketId, OffsetDateTime cursorTs, Long cursorId,
                                   int limit) {
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
        if (cursorTs != null) {
            sql.append("        AND (t.created_at, t.id) < (?, ?)\n");
            params.add(cursorTs);
            params.add(cursorId);
        }
        // 각 축에서 limit건만 뜨면 병합 후 상위 limit건이 전체 정렬과 일치한다(둘 다 같은 축으로
        // 내림차순이라 잘려나간 행은 반드시 유지된 행보다 과거다).
        sql.append("      ORDER BY t.created_at DESC, t.id DESC\n      LIMIT ?)\n");
        params.add(limit);
    }
}

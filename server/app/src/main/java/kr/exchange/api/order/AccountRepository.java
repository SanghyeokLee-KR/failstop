package kr.exchange.api.order;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;

/** 계좌 잔고 조회 SQL 단일 지점(§4-C-1). user_balances 뷰 = available/locked 피벗이 원천이다. */
public class AccountRepository {

    // 평단가는 저장하지 않고 누산기 쌍에서 조회 시 산출한다(§4-C-1, V13). 저장하면 취득마다
    // 나눗셈 반올림이 누적되고 부분 처분이 그 오차를 확대한다.
    // position_cost 행이 없거나 cost_qty = 0(보유 전량이 원가 미상)이면 CASE가 NULL을 내고
    // COALESCE가 0으로 접는다. quote 자산 행이 여기 해당한다.
    // qa 조인은 반올림 자리수 원천이다. position_cost.quote_asset_id는 NOT NULL + FK라
    // pc 행이 있으면 qa 행도 반드시 있다.
    // version 은 AVAILABLE·LOCKED 두 잔고 행 version 의 합이다(잔고 판, §4-C-1). 잔고와 같은 문장에서 읽어야 myAsset 게시의
    // 같은 필드와 비교할 수 있다. 식은 user_balances 뷰와 같은 피벗에 version 합을 더한 것이다.
    private static final String ROWS_SQL = """
            SELECT a.symbol AS currency, ub.available, ub.locked, ub.version,
                   COALESCE(pc.cost_qty, 0)    AS cost_basis_qty,
                   COALESCE(pc.cost_amount, 0) AS cost_basis_amount,
                   COALESCE(CASE WHEN pc.cost_qty > 0
                                 THEN round(pc.cost_amount / pc.cost_qty, qa.scale::int)
                            END, 0) AS avg_buy_price
            FROM (SELECT ac.owner_id AS user_id, ac.asset_id,
                         SUM(CASE WHEN ac.purpose = 'AVAILABLE' THEN b.balance ELSE 0 END) AS available,
                         SUM(CASE WHEN ac.purpose = 'LOCKED'    THEN b.balance ELSE 0 END) AS locked,
                         SUM(b.version) AS version
                  FROM accounts ac JOIN balances b ON b.account_id = ac.id
                  WHERE ac.owner_type = 'USER' AND ac.owner_id = ?
                  GROUP BY ac.owner_id, ac.asset_id) ub
            JOIN assets a ON a.id = ub.asset_id
            LEFT JOIN position_cost pc
                 ON pc.user_id = ub.user_id AND pc.asset_id = ub.asset_id
            LEFT JOIN assets qa ON qa.id = pc.quote_asset_id
            ORDER BY a.id
            """;

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record BalanceRow(String currency, BigDecimal available, BigDecimal locked,
                             BigDecimal costBasisQty, BigDecimal costBasisAmount,
                             BigDecimal avgBuyPrice, long version) {
    }

    public record AssetBalanceRow(String currency, BigDecimal available, BigDecimal locked) {
    }

    public List<BalanceRow> balances(long userId) {
        return jdbc.sql(ROWS_SQL)
                .params(userId)
                .query((rs, rowNum) -> new BalanceRow(
                        rs.getString("currency"),
                        rs.getBigDecimal("available"),
                        rs.getBigDecimal("locked"),
                        rs.getBigDecimal("cost_basis_qty"),
                        rs.getBigDecimal("cost_basis_amount"),
                        rs.getBigDecimal("avg_buy_price"),
                        rs.getLong("version")))
                .list();
    }

    /** 계좌 행이 없는 자산은 0으로 답한다. 미개설을 이유로 실패하면 첫 주문 화면이 뜨지 않는다. */
    public AssetBalanceRow assetBalance(long userId, short assetId) {
        return jdbc.sql("""
                        SELECT a.symbol AS currency,
                               COALESCE(ub.available, 0) AS available,
                               COALESCE(ub.locked, 0)    AS locked
                        FROM assets a
                        LEFT JOIN user_balances ub ON ub.asset_id = a.id AND ub.user_id = ?
                        WHERE a.id = ?
                        """)
                .params(userId, assetId)
                .query((rs, rowNum) -> new AssetBalanceRow(
                        rs.getString("currency"),
                        rs.getBigDecimal("available"),
                        rs.getBigDecimal("locked")))
                .single();
    }
}

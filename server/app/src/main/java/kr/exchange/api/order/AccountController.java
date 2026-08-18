package kr.exchange.api.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiFormats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 계좌 조회(§4-C-1). user_balances 뷰 = available/locked 피벗이 원천이다. */
@RestController
public class AccountController {

    // §2-5 '계좌/주문 조회' weight 2. OrderController 조회 경로와 단일 빈을 공유한다.
    private static final int QUERY_WEIGHT = 2;

    // 평단가는 저장하지 않고 누산기 쌍에서 조회 시 산출한다(§4-C-1, V13). 저장하면 취득마다
    // 나눗셈 반올림이 누적되고 부분 처분이 그 오차를 확대한다.
    // position_cost 행이 없거나 cost_qty = 0(보유 전량이 원가 미상)이면 CASE가 NULL을 내고
    // COALESCE가 0으로 접는다. quote 자산 행이 여기 해당한다.
    // qa 조인은 반올림 자리수 원천이다. position_cost.quote_asset_id는 NOT NULL + FK라
    // pc 행이 있으면 qa 행도 반드시 있다.
    private static final String ROWS_SQL = """
            SELECT a.symbol AS currency, ub.available, ub.locked,
                   COALESCE(pc.cost_qty, 0)    AS cost_basis_qty,
                   COALESCE(pc.cost_amount, 0) AS cost_basis_amount,
                   COALESCE(CASE WHEN pc.cost_qty > 0
                                 THEN round(pc.cost_amount / pc.cost_qty, qa.scale::int)
                            END, 0) AS avg_buy_price
            FROM user_balances ub
            JOIN assets a ON a.id = ub.asset_id
            LEFT JOIN position_cost pc
                 ON pc.user_id = ub.user_id AND pc.asset_id = ub.asset_id
            LEFT JOIN assets qa ON qa.id = pc.quote_asset_id
            WHERE ub.user_id = ?
            ORDER BY a.id
            """;

    private final JdbcClient jdbc;
    private final AccountRateLimiter queryLimiter;

    public AccountController(JdbcClient jdbc,
                             @Qualifier("accountQueryRateLimiter")
                             AccountRateLimiter accountQueryRateLimiter) {
        this.jdbc = jdbc;
        this.queryLimiter = accountQueryRateLimiter;
    }

    @GetMapping("/api/v1/accounts")
    public Map<String, Object> accounts(HttpServletRequest request, HttpServletResponse response) {
        AuthPrincipal principal = OrderController.principal(request);
        queryLimiter.enforce(response, String.valueOf(principal.userId()), QUERY_WEIGHT);
        List<Map<String, Object>> data = new ArrayList<>(jdbc.sql(ROWS_SQL)
                .params(principal.userId())
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("currency", rs.getString("currency"));
                    row.put("balance", ApiFormats.amount(rs.getBigDecimal("available")));
                    row.put("locked", ApiFormats.amount(rs.getBigDecimal("locked")));
                    row.put("avg_buy_price", ApiFormats.amount(rs.getBigDecimal("avg_buy_price")));
                    // v1 quote 단일(KRW) 전제. 반올림 자리수는 행별 qa.scale에서 오는데 라벨만
                    // 상수라, 다중 quote 확장 시 qa.symbol을 행별로 실어야 둘이 갈리지 않는다.
                    row.put("unit_currency", "KRW");
                    row.put("cost_basis_qty", ApiFormats.amount(rs.getBigDecimal("cost_basis_qty")));
                    row.put("cost_basis_amount",
                            ApiFormats.amount(rs.getBigDecimal("cost_basis_amount")));
                    return row;
                })
                .list());
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("next_cursor", null);
        pagination.put("has_more", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("data", data);
        body.put("pagination", pagination);
        return body;
    }
}

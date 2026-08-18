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
        List<Map<String, Object>> data = new ArrayList<>(jdbc.sql("""
                        SELECT a.symbol AS currency, ub.available, ub.locked
                        FROM user_balances ub
                        JOIN assets a ON a.id = ub.asset_id
                        WHERE ub.user_id = ?
                        ORDER BY a.id
                        """)
                .params(principal.userId())
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("currency", rs.getString("currency"));
                    row.put("balance", ApiFormats.amount(rs.getBigDecimal("available")));
                    row.put("locked", ApiFormats.amount(rs.getBigDecimal("locked")));
                    // 평단가 산출은 M1-4 범위 밖. 계약 필드는 유지한다(§4-C-1).
                    row.put("avg_buy_price", "0");
                    row.put("unit_currency", "KRW");
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

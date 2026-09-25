package kr.exchange.api.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.order.dto.AccountBalanceResponse;
import kr.exchange.api.order.dto.PageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 계좌 조회(§4-C-1). user_balances 뷰 = available/locked 피벗이 원천이다. */
@RestController
public class AccountController {

    // §2-5 '계좌/주문 조회' weight 2. OrderController 조회 경로와 단일 빈을 공유한다.
    private static final int QUERY_WEIGHT = 2;

    private final AccountRepository accounts;
    private final AccountRateLimiter queryLimiter;

    public AccountController(AccountRepository accounts,
                             @Qualifier("accountQueryRateLimiter")
                             AccountRateLimiter accountQueryRateLimiter) {
        this.accounts = accounts;
        this.queryLimiter = accountQueryRateLimiter;
    }

    @GetMapping("/api/v1/accounts")
    public PageResponse<AccountBalanceResponse> accounts(HttpServletRequest request,
                                                         HttpServletResponse response) {
        AuthPrincipal principal = OrderController.principal(request);
        queryLimiter.enforce(response, String.valueOf(principal.userId()), QUERY_WEIGHT);
        List<AccountBalanceResponse> data = new ArrayList<>();
        for (AccountRepository.BalanceRow row : accounts.balances(principal.userId())) {
            data.add(new AccountBalanceResponse(
                    row.currency(),
                    ApiFormats.amount(row.available()),
                    ApiFormats.amount(row.locked()),
                    ApiFormats.amount(row.avgBuyPrice()),
                    // v1 quote 단일(KRW) 전제. 반올림 자리수는 행별 qa.scale에서 오는데 라벨만
                    // 상수라, 다중 quote 확장 시 qa.symbol을 행별로 실어야 둘이 갈리지 않는다.
                    "KRW",
                    ApiFormats.amount(row.costBasisQty()),
                    ApiFormats.amount(row.costBasisAmount()),
                    row.version()));
        }
        // 커서 페이징 미구현 자리표시자. 키를 생략하면 프론트 파서가 깨진다.
        return new PageResponse<>(ApiFormats.SCHEMA_VERSION, data,
                new PageResponse.Pagination(null, false));
    }
}

package kr.exchange.api.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.exchange.api.auth.AuthPrincipal;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.dto.LedgerEntryResponse;
import kr.exchange.api.order.dto.PageResponse;
import kr.exchange.api.order.dto.TradeResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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

    private final MyHistoryRepository history;
    private final MarketDirectory markets;
    private final AccountRateLimiter queryLimiter;

    public MyController(MyHistoryRepository history, MarketDirectory markets,
                        @Qualifier("accountQueryRateLimiter")
                        AccountRateLimiter accountQueryRateLimiter) {
        this.history = history;
        this.markets = markets;
        this.queryLimiter = accountQueryRateLimiter;
    }

    @GetMapping("/trades")
    public PageResponse<TradeResponse> trades(HttpServletRequest request, HttpServletResponse response,
                                              @RequestParam(value = "market", required = false) String market,
                                              @RequestParam(value = "count", required = false) Integer count,
                                              @RequestParam(value = "cursor", required = false) String cursor) {
        AuthPrincipal principal = principal(request, response);
        long userId = principal.userId();
        int limit = clampCount(count);
        Integer marketId = market == null ? null : markets.bySymbol(market).id();
        Cursors.Cursor decoded = cursor == null ? null : Cursors.decode(cursor);

        Map<Integer, MarketDirectory.Market> directory = marketsById();
        List<Cursored<TradeResponse>> rows = new ArrayList<>();
        for (MyHistoryRepository.TradeRow r : history.trades(userId, marketId,
                decoded == null ? null : decoded.ts(),
                decoded == null ? null : numericCursorId(decoded.id()), limit)) {
            MarketDirectory.Market m = directory.get(r.marketId());
            // taker_side는 taker 관점이다. 내가 maker면 내 방향은 그 반대다.
            boolean takerBuy = "BUY".equals(r.takerSide());
            boolean mineBuy = "TAKER".equals(r.liquidityFlag()) == takerBuy;
            TradeResponse row = new TradeResponse(
                    String.valueOf(r.id()),
                    m == null ? null : m.symbol(),
                    mineBuy ? "bid" : "ask",
                    r.orderId(),
                    ApiFormats.amount(r.price()),
                    ApiFormats.amount(r.quantity()),
                    r.liquidityFlag(),
                    ApiFormats.amount(r.fee()),
                    // 수수료는 수취 자산으로 뗀다(§4-B-5 v2.7). 매수는 base, 매도는 quote라
                    // 금액만 주면 어느 자산인지 응답에서 확정되지 않는다.
                    m == null ? null : (mineBuy ? m.baseSymbol() : m.quoteSymbol()),
                    ApiFormats.time(r.createdAt()));
            rows.add(new Cursored<>(row, r.createdAt(), row.tradeId()));
        }
        return envelope(rows, limit);
    }

    @GetMapping("/ledger")
    public PageResponse<LedgerEntryResponse> ledger(HttpServletRequest request, HttpServletResponse response,
                                                    @RequestParam(value = "currency", required = false) String currency,
                                                    @RequestParam(value = "count", required = false) Integer count,
                                                    @RequestParam(value = "cursor", required = false) String cursor) {
        AuthPrincipal principal = principal(request, response);
        int limit = clampCount(count);
        Short assetId = currency == null ? null
                : history.assetId(currency).orElseThrow(() -> new ApiException(400,
                        "invalid_parameter", "존재하지 않는 통화입니다: " + currency));
        Cursors.Cursor decoded = cursor == null ? null : Cursors.decode(cursor);

        List<Cursored<LedgerEntryResponse>> rows = new ArrayList<>();
        for (MyHistoryRepository.LedgerRow r : history.ledger(principal.userId(), assetId,
                decoded == null ? null : decoded.ts(),
                decoded == null ? null : numericCursorId(decoded.id()), limit)) {
            LedgerEntryResponse row = new LedgerEntryResponse(
                    String.valueOf(r.id()),
                    r.currency(),
                    ApiFormats.amount(r.amount()),
                    r.entryType(),
                    LedgerEntryResponse.ref(r.businessEventType(), r.businessEventId(),
                            r.orderId()),
                    ApiFormats.time(r.createdAt()));
            rows.add(new Cursored<>(row, r.createdAt(), row.entryId()));
        }
        return envelope(rows, limit);
    }

    private Map<Integer, MarketDirectory.Market> marketsById() {
        Map<Integer, MarketDirectory.Market> byId = new HashMap<>();
        for (MarketDirectory.Market m : markets.all()) {
            byId.put(m.id(), m);
        }
        return byId;
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
            throw new ApiException(400, "invalid_parameter", "커서 형식 오류");
        }
    }

    /**
     * 커서 축(created_at 원본·id)을 응답 밖에서 보존한다. 표시용 timestamp는 밀리초 절단이라
     * 커서 재료로 쓰면 같은 밀리초 경계 행이 다음 페이지에서 소실된다(Cursors.encode 주석).
     */
    private record Cursored<T>(T row, OffsetDateTime ts, String id) {
    }

    private static <T> PageResponse<T> envelope(List<Cursored<T>> rows, int limit) {
        String nextCursor = null;
        if (rows.size() == limit && !rows.isEmpty()) {
            Cursored<T> last = rows.get(rows.size() - 1);
            nextCursor = Cursors.encode(last.ts(), last.id());
        }
        List<T> data = new ArrayList<>();
        for (Cursored<T> row : rows) {
            data.add(row.row());
        }
        return new PageResponse<>(ApiFormats.SCHEMA_VERSION, data,
                new PageResponse.Pagination(nextCursor, nextCursor != null));
    }
}

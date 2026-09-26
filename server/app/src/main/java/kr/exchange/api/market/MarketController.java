package kr.exchange.api.market;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.ws.MarketViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 공개 시장 데이터(§4-A). 인증 불필요, 리스트는 §2-2 엔벌로프. */
@RestController
@RequestMapping("/api/v1")
public class MarketController {

    private static final int MAX_COUNT = 200;
    private static final int MAX_TICKER_MARKETS = 100;

    private final MarketDirectory markets;
    private final MarketDataService data;
    private final MarketViews views;

    public MarketController(MarketDirectory markets, MarketDataService data, MarketViews views) {
        this.markets = markets;
        this.data = data;
        this.views = views;
    }

    @GetMapping("/markets")
    public Map<String, Object> markets() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MarketDirectory.Market m : markets.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("market", m.symbol());
            row.put("korean_name", m.baseName());
            row.put("base", m.baseSymbol());
            row.put("quote", m.quoteSymbol());
            row.put("state", m.status());
            row.put("tick_size", ApiFormats.amount(m.tickSize()));
            row.put("min_order_amount", ApiFormats.amount(m.minNotional()));
            row.put("max_order_amount", ApiFormats.amount(m.maxNotional()));
            row.put("qty_scale", m.baseScale());
            row.put("price_scale", m.quoteScale());
            row.put("maker_fee", ApiFormats.amount(m.makerFeeRate()));
            row.put("taker_fee", ApiFormats.amount(m.takerFeeRate()));
            rows.add(row);
        }
        return envelope(rows, null);
    }

    /** seq·epoch 를 내는 노드의 규칙은 §4-A-2 다(MarketViews). */
    @GetMapping("/orderbook")
    public Map<String, Object> orderbook(@RequestParam("market") String market,
                                         @RequestParam(value = "level", required = false) String level) {
        return views.orderbook(markets.bySymbol(market));
    }

    @GetMapping("/trades")
    public Map<String, Object> trades(@RequestParam("market") String market,
                                      @RequestParam(value = "count", required = false) Integer count,
                                      @RequestParam(value = "cursor", required = false) String cursor) {
        MarketDirectory.Market m = markets.bySymbol(market);
        int limit = clampCount(count);
        Cursors.Cursor decoded = cursor == null ? null : Cursors.decode(cursor);
        List<Map<String, Object>> rows = data.recentTrades(m, limit, decoded);
        String nextCursor = null;
        if (rows.size() == limit && !rows.isEmpty()) {
            Map<String, Object> lastRow = rows.get(rows.size() - 1);
            nextCursor = Cursors.encode((OffsetDateTime) lastRow.get("cursor_ts"),
                    (String) lastRow.get("trade_id"));
        }
        rows.forEach(r -> r.remove("cursor_ts"));
        return envelope(rows, nextCursor);
    }

    @GetMapping("/candles/{unit}")
    public Map<String, Object> candles(@PathVariable("unit") String unit,
                                       @RequestParam("market") String market,
                                       @RequestParam(value = "count", required = false) Integer count,
                                       @RequestParam(value = "cursor", required = false) String cursor) {
        if (!MarketDataService.isSupportedUnit(unit)) {
            throw new ApiException(400, "invalid_parameter", "지원하지 않는 캔들 단위입니다: " + unit);
        }
        MarketDirectory.Market m = markets.bySymbol(market);
        int limit = clampCount(count);
        Cursors.Cursor decoded = cursor == null ? null : Cursors.decode(cursor);
        List<Map<String, Object>> rows = data.candles(m, unit, limit, decoded);
        String nextCursor = null;
        if (rows.size() == limit && !rows.isEmpty()) {
            Map<String, Object> lastRow = rows.get(rows.size() - 1);
            // candles 커서 id 축은 (candle_time, market) 자연키다(§2-6 U5). market을 id로 싣는다.
            nextCursor = Cursors.encode((OffsetDateTime) lastRow.get("cursor_ts"), m.symbol());
        }
        rows.forEach(r -> r.remove("cursor_ts"));
        return envelope(rows, nextCursor);
    }

    @GetMapping("/ticker")
    public Map<String, Object> ticker(@RequestParam("markets") String marketsParam) {
        String[] requested = marketsParam.split(",");
        if (requested.length > MAX_TICKER_MARKETS) {
            throw new ApiException(400, "invalid_parameter",
                    "markets는 최대 " + MAX_TICKER_MARKETS + "개입니다.");
        }
        List<MarketDirectory.Market> wanted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : requested) {
            String symbol = raw.trim();
            // 중복·빈 항목은 조회·응답에서 제외한다(같은 마켓 반복 폴링 방어).
            if (symbol.isEmpty() || !seen.add(symbol)) {
                continue;
            }
            wanted.add(markets.bySymbol(symbol));
        }
        return envelope(views.tickers(wanted), null);
    }

    private static int clampCount(Integer count) {
        if (count == null) {
            return 100;
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new ApiException(400, "invalid_parameter", "count는 1~" + MAX_COUNT + "이어야 합니다.");
        }
        return count;
    }

    private static Map<String, Object> envelope(List<Map<String, Object>> rows, String nextCursor) {
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

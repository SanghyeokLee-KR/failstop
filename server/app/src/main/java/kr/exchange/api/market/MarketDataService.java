package kr.exchange.api.market;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.Cursors;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시장 데이터 조회. 호가는 orders 프로젝션 집계(idx_orderbook_open), 체결·티커는 trades
 * 프로젝션이다. 엔진 인메모리 상태를 직접 읽지 않는 이유: 오더북은 단일 라이터 스레드 소유라
 * 교차 스레드 읽기가 결정론 규약을 흔들고, 프로젝션은 정산 커밋과 같은 트랜잭션이라 정합하다.
 */
public class MarketDataService {

    public record Level(BigDecimal price, BigDecimal size) {
    }

    public record Depth(List<Level> asks, List<Level> bids,
                        BigDecimal totalAsk, BigDecimal totalBid) {
    }

    private static final int DEPTH_LEVELS = 15;

    private final JdbcClient jdbc;

    public MarketDataService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 리더가 배치마다 넣어 두는 호가 스냅샷. 마켓 id 로 찾는다.
     *
     * 엔진은 호가창을 이미 메모리에 들고 있다(OrderBookImpl 의 TreeMap). 종전에는 같은 사실을
     * DB 집계로 다시 만들었고, 2026-09-07 실측에서 그 두 쿼리가 DB 실행 시간 166445ms 로 같은
     * 창 쓰기 경로의 3 배였다. 미체결이 8 만 건일 때 총 잔량 한 번이 25ms 다.
     *
     * 스냅샷은 단일 라이터 스레드가 만들어 불변 객체로 넣는다. 읽는 쪽은 잠금 없이 그 참조만
     * 가져가므로 라이터와 경합하지 않는다. 책을 직접 읽게 하면 TreeMap 동시 수정에 걸린다.
     *
     * 비어 있을 수 있다. 엔진이 없는 조회 전용 노드가 그렇고, 리더도 첫 배치 전에는 그렇다.
     * 그때는 종전대로 DB 집계로 내려간다.
     */
    private final ConcurrentHashMap<Integer, Depth> engineDepth = new ConcurrentHashMap<>();

    /**
     * 엔진 호가창에서 뜬 결과를 조회 응답 모양으로 옮긴다.
     *
     * 총 잔량은 상위 15 단 합이 아니라 그 면 전체다. DB 집계가 내던 값과 같아야 화면이 바뀌지
     * 않는다(OrderBookImpl.depthOf 주석과 짝).
     */
    public static Depth fromEngine(kr.exchange.engine.core.OrderBookImpl.SideDepth ask,
                                   kr.exchange.engine.core.OrderBookImpl.SideDepth bid) {
        return new Depth(toLevels(ask), toLevels(bid), ask.total(), bid.total());
    }

    private static List<Level> toLevels(kr.exchange.engine.core.OrderBookImpl.SideDepth side) {
        List<Level> out = new java.util.ArrayList<>(side.levels().size());
        for (kr.exchange.engine.core.OrderBookImpl.DepthLevel l : side.levels()) {
            out.add(new Level(l.price(), l.size()));
        }
        return List.copyOf(out);
    }

    private final ConcurrentHashMap<String, Cached<Map<String, Object>>> tickerStatsCache =
            new ConcurrentHashMap<>();

    /** 24 시간 창의 고가·저가·거래량·거래대금. 창이 넓어 비싸므로 위 TTL 로 감싼다. */
    private Map<String, Object> dayStats(MarketDirectory.Market market) {
        OffsetDateTime dayAgo = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);
        return jdbc.sql("""
                        SELECT MAX(price) AS high, MIN(price) AS low,
                               COALESCE(SUM(quantity), 0) AS vol,
                               COALESCE(SUM(price * quantity), 0) AS turnover
                        FROM trades WHERE market_id = ? AND created_at > ?
                        """)
                .params(market.id(), dayAgo)
                .query((rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("high", rs.getBigDecimal("high"));
                    m.put("low", rs.getBigDecimal("low"));
                    m.put("vol", rs.getBigDecimal("vol"));
                    m.put("turnover", rs.getBigDecimal("turnover"));
                    return m;
                })
                .single();
    }

    /** 단일 라이터 스레드에서만 부른다. 배치가 건드린 마켓의 스냅샷을 갈아 끼운다. */
    public void publishEngineDepth(int marketId, Depth depth) {
        if (depth != null) {
            engineDepth.put(marketId, depth);
        }
    }

    /** 진단용. 스냅샷을 들고 있는 마켓 수다. 0 이면 전부 DB 집계로 내려가고 있다. */
    public int engineDepthMarkets() {
        return engineDepth.size();
    }

    public Depth depth(int marketId) {
        Depth fromEngine = engineDepth.get(marketId);
        if (fromEngine != null) {
            return fromEngine;
        }
        List<Level> asks = levels(marketId, "SELL", "ASC");
        List<Level> bids = levels(marketId, "BUY", "DESC");
        return new Depth(asks, bids, total(marketId, "SELL"), total(marketId, "BUY"));
    }

    private List<Level> levels(int marketId, String side, String direction) {
        return cached(levelsCache, marketId + ":" + side, DEPTH_CACHE_TTL_MS, () -> jdbc.sql("""
                        SELECT price, SUM(remaining_qty) AS size
                        FROM orders
                        WHERE market_id = ? AND side = ? AND status IN ('OPEN','PARTIAL')
                        GROUP BY price
                        ORDER BY price %s
                        LIMIT %d
                        """.formatted(direction, DEPTH_LEVELS))
                .params(marketId, side)
                .query((rs, rowNum) -> new Level(rs.getBigDecimal("price"), rs.getBigDecimal("size")))
                .list());
    }

    // 잔량 합계 단기 캐시.
    //
    // 이 집계는 호가 15단과 달리 LIMIT 이 없어 그 마켓·방향의 미체결 주문 전체를 더한다.
    // 2026-09-04 실측(pg_stat_statements)에서 호출 17288 회에 총 실행시간 51초로 전체 쿼리 중
    // 가장 무거웠다. 인덱스는 정상 사용된다(Index Only Scan, 회당 1.7ms). 비용의 원인은
    // 계획이 아니라 대상 행 수(마켓·방향당 약 4500)와 호출 빈도(초당 182)다.
    //
    // 캐시가 성립하는 이유. 이 값은 화면 하단의 매도·매수 잔량 표시 전용이고 판정에 쓰지
    // 않는다. 호가 15단(levels)은 캐시하지 않으므로 체결 반영은 그대로 즉시다. 곧 최대
    // TTL_MS 만큼 낡을 수 있는 것은 총합 숫자 하나뿐이다.
    //
    // TTL 200ms 의 근거. 호가 푸시가 초당 여러 건이라 이보다 짧으면 캐시가 거의 안 맞고,
    // 길면 총합이 눈에 띄게 멎는다. 실측 근거는 없고 운영하며 조정한다.
    // 2026-09-04 재측정에서 200ms 로는 부족했다. 호출이 초당 32회였고 캐시 하한이 초당 30회라
    // 감소가 23% 에 그쳤다. 1초로 늘려 하한을 초당 6회로 내린다. 호가창이 최대 1초 늦게
    // 갱신되나 체결과 잔고는 영향받지 않는다.
    private static final long DEPTH_CACHE_TTL_MS = 1_000;

    /**
     * 총 잔량(total_ask_size·total_bid_size) 전용 TTL. 15 단보다 길게 둔다.
     *
     * 둘은 성격이 다르다. 15 단은 사용자가 보고 주문을 내는 값이라 신선해야 한다. 총 잔량은
     * 화면 위쪽의 요약 숫자 하나이고 계약도 신선도를 규정하지 않는다(API 설계 §4-A-2 는 필드만
     * 적는다). 매칭·주문 검증·정산은 이 값을 쓰지 않는다. 쓰는 곳은 REST 조회와 WS 게시뿐이다.
     *
     * 비용은 반대로 총 잔량이 크다. 15 단은 LIMIT 15 라 상위만 보면 되지만 총 잔량은 그 마켓·
     * 방향의 미체결 전체를 더한다. 2026-09-07 실측에서 총 잔량이 건당 25.0ms(누적 137167ms)로
     * 15 단의 12.8ms(29278ms)보다 4.7 배 비쌌다. 그때 미체결이 한 방향에 87746 건이었고
     * 실행 계획은 이미 인덱스 전용 스캔이라 더 줄일 여지가 없다. 남은 축은 얼마나 자주 다시
     * 세느냐뿐이다.
     *
     * 5 초인 근거. 미체결 8 만 건 규모에서 1 초 동안의 체결·취소가 총량을 바꾸는 폭은 소수점
     * 아래다. 요약 숫자가 그만큼 늦는 것은 화면에서 구분되지 않는다. 반대로 이보다 더 늘리면
     * 호가창이 갱신되는데 위쪽 총량만 눈에 띄게 멈춰 있어 어긋나 보인다.
     */
    private static final long TOTAL_CACHE_TTL_MS = 5_000;

    /**
     * 24 시간 시세 통계(고가·저가·거래량·거래대금) 전용 TTL.
     *
     * 이 통계는 체결이 있는 배치마다 다시 계산됐다. 한 번이 24 시간치 체결을 전부 훑으므로
     * 체결이 쌓일수록 비싸진다. 2026-09-07 실측에서 860 회에 17977ms, 건당 20.9ms 였다.
     *
     * 24 시간 창의 통계라 몇 초 늦어도 값이 사실상 같다. 거래량은 계속 늘지만 하루치 누계에서
     * 2 초 몫은 눈에 띄지 않고, 고가·저가는 그 창에서 갱신이 드물다. 반면 현재가는 캐시하지
     * 않는다. 그것은 화면에서 가장 먼저 보는 값이고 체결마다 바뀌어야 한다.
     *
     * 총 잔량(5 초)보다 짧게 두는 이유. 거래량은 단조 증가라 늦으면 늦은 만큼 작게 보이는데,
     * 호가 총량과 달리 사용자가 체결 목록과 나란히 놓고 본다. 어긋남이 드러나는 창을 줄인다.
     */
    private static final long TICKER_STATS_TTL_MS = 2_000;

    private record Cached<T>(T value, long expiresAt) {
    }

    private final ConcurrentHashMap<String, Cached<BigDecimal>> totalCache = new ConcurrentHashMap<>();

    // 호가 15단도 캐시한다. 이쪽이 실제 비용의 대부분이다. 2026-09-04 실측에서 매수·매도 두
    // 계열이 DB 총 실행시간의 65.6%(294.5초)로 1위였다. LIMIT 15 가 있어도 GROUP BY 가 그
    // 마켓·방향의 미체결 전체를 훑은 뒤에야 15단을 낼 수 있어 비용이 주문 수에 비례한다.
    private final ConcurrentHashMap<String, Cached<List<Level>>> levelsCache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    /**
     * 키마다 하나씩 두는 채움 잠금. 만료 순간의 동시 요청이 전부 DB 로 몰리는 것을 막는다.
     *
     * 잠금을 캐시와 따로 두는 이유는 캐시 항목이 만료로 사라져도 잠금은 남아 다음 채움까지
     * 이어져야 하기 때문이다. 키 수는 마켓 수의 상수배라 유계다.
     */
    private final ConcurrentHashMap<String, Object> fillLocks = new ConcurrentHashMap<>();

    /**
     * TTL 캐시. 만료 시 한 스레드만 DB 를 치고 나머지는 그 결과를 기다린다.
     *
     * 기다리게 하는 이유. 종전에는 만료를 본 스레드가 저마다 loader 를 돌렸다. 2026-09-07
     * 실측에서 조회 7684 건/s 를 받는 노드가 1 초 TTL 인데도 호가 쿼리를 초당 10.8 회 냈다.
     * 1 마켓 두 면이면 초당 2 회여야 하므로 5.4 배가 이 몰림이다. TTL 이 짧을수록, 부하가
     * 셀수록 배수가 커진다.
     *
     * 기다린 쪽이 얻는 값은 방금 채운 값이라 최신이다. 잠금 구간이 DB 왕복 한 번이라 대기도
     * 그만큼이고, 몰림으로 왕복을 여러 번 하는 것보다 짧다.
     */
    private <T> T cached(ConcurrentHashMap<String, Cached<T>> cache, String key,
                         long ttlMs, java.util.function.Supplier<T> loader) {
        Cached<T> hit = cache.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return hit.value();
        }
        Object lock = fillLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 잠금을 잡는 사이에 다른 스레드가 채웠을 수 있다. 다시 본다.
            Cached<T> again = cache.get(key);
            long now = System.currentTimeMillis();
            if (again != null && again.expiresAt() > now) {
                return again.value();
            }
            T fresh = loader.get();
            cache.put(key, new Cached<>(fresh, now + ttlMs));
            return fresh;
        }
    }

    private BigDecimal total(int marketId, String side) {
        return cached(totalCache, marketId + ":" + side, TOTAL_CACHE_TTL_MS, () -> jdbc.sql("""
                        SELECT COALESCE(SUM(remaining_qty), 0)
                        FROM orders
                        WHERE market_id = ? AND side = ? AND status IN ('OPEN','PARTIAL')
                        """)
                .params(marketId, side)
                .query(BigDecimal.class)
                .single());
    }

    /** REST 단건 객체·WS 메시지 공용 호가 본문. stream 필드는 WS 호출측만 덧붙인다(§5-2 v2.4). */
    public Map<String, Object> orderbookBody(String marketSymbol, Depth depth,
                                             long epoch, long seq, String timestamp) {
        List<Map<String, Object>> units = new ArrayList<>();
        int rows = Math.max(depth.asks().size(), depth.bids().size());
        for (int i = 0; i < rows; i++) {
            Map<String, Object> unit = new LinkedHashMap<>();
            Level ask = i < depth.asks().size() ? depth.asks().get(i) : null;
            Level bid = i < depth.bids().size() ? depth.bids().get(i) : null;
            unit.put("ask_price", ask == null ? null : ApiFormats.amount(ask.price()));
            unit.put("ask_size", ask == null ? null : ApiFormats.amount(ask.size()));
            unit.put("bid_price", bid == null ? null : ApiFormats.amount(bid.price()));
            unit.put("bid_size", bid == null ? null : ApiFormats.amount(bid.size()));
            units.add(unit);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("market", marketSymbol);
        body.put("timestamp", timestamp);
        body.put("epoch", epoch);
        body.put("seq", seq);
        body.put("total_ask_size", ApiFormats.amount(depth.totalAsk()));
        body.put("total_bid_size", ApiFormats.amount(depth.totalBid()));
        body.put("orderbook_units", units);
        return body;
    }

    public List<Map<String, Object>> recentTrades(MarketDirectory.Market market, int count,
                                                  Cursors.Cursor cursor) {
        // created_at 축 조회. executed_at과 값은 동일(둘 다 engine_ts 파생)이나 파티션 키·
        // idx_trades_market_time이 created_at이라 이 축이라야 프루닝·인덱스가 붙는다.
        StringBuilder sql = new StringBuilder("""
                SELECT id, price, quantity, taker_side, created_at
                FROM trades
                WHERE market_id = ?
                """);
        List<Object> params = new ArrayList<>(List.of(market.id()));
        if (cursor != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            params.add(cursor.ts());
            params.add(parseTradeId(cursor.id()));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(count);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long tradeId = rs.getLong("id");
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    row.put("trade_id", String.valueOf(tradeId));
                    row.put("market", market.symbol());
                    row.put("price", ApiFormats.amount(rs.getBigDecimal("price")));
                    row.put("volume", ApiFormats.amount(rs.getBigDecimal("quantity")));
                    row.put("taker_side", "BUY".equals(rs.getString("taker_side")) ? "bid" : "ask");
                    row.put("liquidity_flag_taker", "TAKER");
                    row.put("timestamp", ApiFormats.time(createdAt));
                    row.put("seq", tradeId);
                    row.put("cursor_ts", createdAt);
                    return row;
                })
                .list();
    }

    // 조작 커서(유효 base64 + 비수치 id) 방어. opaque 커서 계약(§2-6)상 400이어야 하므로
    // NumberFormatException이 500으로 새지 않게 정규화한다.
    private static long parseTradeId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", "커서 형식 오류");
        }
    }

    public Map<String, Object> ticker(MarketDirectory.Market market, long epoch, long seq) {
        // REST 단건 경로. depth를 한 번 산출해 4-인자 경로에 위임한다.
        return ticker(market, epoch, seq, depth(market.id()));
    }

    /**
     * depth를 인자로 받는 경로. WS 게시(§5)는 같은 배치에서 orderbook·ticker가 같은 호가를
     * 쓰므로 depth를 한 번 산출해 두 경로에 공유한다(라이터 스레드 depth 이중 집계 제거).
     */
    public Map<String, Object> ticker(MarketDirectory.Market market, long epoch, long seq,
                                      Depth depth) {
        // created_at 축(파티션 키·인덱스). executed_at과 값 동일이나 프루닝을 위해 이 축을 쓴다.
        Map<String, Object> last = jdbc.sql("""
                        SELECT price, created_at FROM trades
                        WHERE market_id = ?
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .params(market.id())
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "price", rs.getBigDecimal("price"),
                        "at", rs.getObject("created_at", OffsetDateTime.class)))
                .optional()
                .orElse(null);

        Map<String, Object> agg = cached(tickerStatsCache, String.valueOf(market.id()),
                TICKER_STATS_TTL_MS, () -> dayStats(market));

        // 전일 종가 기준시각. 위 통계는 캐시가 자기 시각을 쓰므로 여기서 따로 잡는다.
        OffsetDateTime dayAgo = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);
        BigDecimal prev = jdbc.sql("""
                        SELECT price FROM trades
                        WHERE market_id = ? AND created_at <= ?
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .params(market.id(), dayAgo)
                .query(BigDecimal.class)
                .optional()
                .orElseGet(() -> jdbc.sql("""
                                SELECT price FROM trades WHERE market_id = ?
                                ORDER BY created_at ASC, id ASC LIMIT 1
                                """)
                        .params(market.id())
                        .query(BigDecimal.class)
                        .optional()
                        .orElse(null));

        BigDecimal lastPrice = last == null ? null : (BigDecimal) last.get("price");

        // 부호는 change로, 크기는 change_rate/change_price 절대값으로 분리 노출한다(프론트 규약,
        // web SimEngine.tickerOf와 정합). 색 단독 표현 금지 규칙을 부호 필드로 뒷받침한다.
        String change = "EVEN";
        String changeRate = "0";
        String changePrice = "0";
        if (lastPrice != null && prev != null && prev.signum() > 0) {
            int cmp = lastPrice.compareTo(prev);
            change = cmp > 0 ? "RISE" : cmp < 0 ? "FALL" : "EVEN";
            BigDecimal diff = lastPrice.subtract(prev);
            changeRate = diff.abs()
                    .divide(prev, 8, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
            changePrice = ApiFormats.amount(
                    diff.abs().setScale(market.quoteScale(), RoundingMode.HALF_UP));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("market", market.symbol());
        body.put("trade_price", lastPrice == null ? null : ApiFormats.amount(lastPrice));
        body.put("change", change);
        body.put("change_rate", changeRate);
        body.put("change_price", changePrice);
        body.put("high_24h", agg.get("high") == null ? null
                : ApiFormats.amount((BigDecimal) agg.get("high")));
        body.put("low_24h", agg.get("low") == null ? null
                : ApiFormats.amount((BigDecimal) agg.get("low")));
        body.put("acc_trade_volume_24h", ApiFormats.amount((BigDecimal) agg.get("vol")));
        body.put("acc_trade_price_24h", ApiFormats.amount(((BigDecimal) agg.get("turnover"))
                .setScale(market.quoteScale(), RoundingMode.HALF_UP)));
        body.put("best_bid", depth.bids().isEmpty() ? null
                : ApiFormats.amount(depth.bids().get(0).price()));
        body.put("best_ask", depth.asks().isEmpty() ? null
                : ApiFormats.amount(depth.asks().get(0).price()));
        body.put("seq", seq);
        body.put("epoch", epoch);
        body.put("timestamp", last == null
                ? ApiFormats.time(Instant.now())
                : ApiFormats.time((OffsetDateTime) last.get("at")));
        return body;
    }

    /**
     * unit별 구간 초. candles 테이블은 DB 정본상 V-로드맵(비정규화 trades 파생)이라 데모는
     * trades를 실시간 집계해 응답한다(재구축 가능·별도 저장 없음). 구간 경계는 epoch 정렬이다.
     */
    private static final Map<String, Long> UNIT_SECONDS = Map.ofEntries(
            Map.entry("1s", 1L), Map.entry("1m", 60L), Map.entry("3m", 180L),
            Map.entry("5m", 300L), Map.entry("15m", 900L), Map.entry("30m", 1800L),
            Map.entry("60m", 3600L), Map.entry("240m", 14400L),
            Map.entry("1d", 86400L), Map.entry("1w", 604800L));

    public static boolean isSupportedUnit(String unit) {
        return UNIT_SECONDS.containsKey(unit);
    }

    /**
     * trades를 unit 구간으로 버킷팅해 OHLCV를 집계한다. open/close는 구간 내 최초·최종 체결가
     * (created_at,id 정렬)이고 seq는 구간 내 최대 event_seq다(구간 간 단조 증가, §5-2 candle
     * 도메인 market×unit). 커서 id 축은 (candle_time, market) 자연키다(§2-6 U5, 시각축만으로 정렬).
     */
    public List<Map<String, Object>> candles(MarketDirectory.Market market, String unit,
                                             int count, Cursors.Cursor cursor) {
        long sec = UNIT_SECONDS.get(unit);
        // 첫 페이지는 시각 하한을 두지 않는다. 하한을 두면 밀도 낮은 마켓의 sparse 이력이 잘려
        // has_more가 조기 false가 된다. 커서가 있으면 상한(cursor.ts + 한 버킷)을 줘 created_at
        // 파티션 프루닝을 붙인다. 넘겨 담기는 체결은 전부 HAVING이 버리는 버킷에만 속해 결과가
        // 불변이다.
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "WITH bucketed AS (\n"
                + "    SELECT floor(extract(epoch FROM created_at) / ?) AS b,\n"
                + "           price, quantity, id, created_at\n"
                + "    FROM trades WHERE market_id = ?\n");
        params.add(sec);
        params.add(market.id());
        if (cursor != null) {
            sql.append("      AND created_at < ?\n");
            params.add(cursor.ts().plusSeconds(sec));
        }
        sql.append(
                ")\n"
                + "SELECT to_timestamp(b * ?) AS candle_time,\n"
                + "       (array_agg(price ORDER BY created_at ASC, id ASC))[1] AS open,\n"
                + "       MAX(price) AS high, MIN(price) AS low,\n"
                + "       (array_agg(price ORDER BY created_at DESC, id DESC))[1] AS close,\n"
                + "       SUM(quantity) AS volume, COUNT(*) AS trade_count, MAX(id) AS seq\n"
                + "FROM bucketed\n"
                + "GROUP BY b\n");
        params.add(sec);
        if (cursor != null) {
            sql.append(" HAVING to_timestamp(b * ?) < ?");
            params.add(sec);
            params.add(cursor.ts());
        }
        sql.append(" ORDER BY b DESC LIMIT ?");
        params.add(count);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> candleRow(market, rs))
                .list();
    }

    /**
     * 라이브 WS 방출용 최근 버킷 1건. REST candles()의 무제한 이력 스캔과 달리 현재 버킷 창
     * (created_at >= 버킷 시작)만 읽어 idx_trades_market_time 레인지 스캔·파티션 프루닝이 붙는다.
     * 형성 중 캔들은 최근 버킷 하나만 필요해 페이지 완결 요건이 없으므로 이력 하한을 둘 수 있다.
     * 최신 체결 시각이 최근 버킷을 정하고, 그 버킷 시작 이후 체결이 곧 현재 버킷 집합이다
     * (최신 이후 체결이 없어 상한은 열어도 안전). 체결 이력이 없으면 null.
     */
    public Map<String, Object> latestCandle(MarketDirectory.Market market, String unit) {
        long sec = UNIT_SECONDS.get(unit);
        OffsetDateTime latest = jdbc.sql("""
                        SELECT created_at FROM trades WHERE market_id = ?
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .params(market.id())
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
        if (latest == null) {
            return null;
        }
        long bucket = Math.floorDiv(latest.toInstant().getEpochSecond(), sec) * sec;
        OffsetDateTime bucketStart = OffsetDateTime.ofInstant(Instant.ofEpochSecond(bucket), ZoneOffset.UTC);
        return jdbc.sql("""
                        SELECT to_timestamp(?) AS candle_time,
                               (array_agg(price ORDER BY created_at ASC, id ASC))[1] AS open,
                               MAX(price) AS high, MIN(price) AS low,
                               (array_agg(price ORDER BY created_at DESC, id DESC))[1] AS close,
                               SUM(quantity) AS volume, COUNT(*) AS trade_count, MAX(id) AS seq
                        FROM trades
                        WHERE market_id = ? AND created_at >= ?
                        """)
                .params(bucket, market.id(), bucketStart)
                .query((rs, rowNum) -> candleRow(market, rs))
                .optional()
                .orElse(null);
    }

    private Map<String, Object> candleRow(MarketDirectory.Market market, ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        OffsetDateTime candleTime = rs.getObject("candle_time", OffsetDateTime.class);
        row.put("market", market.symbol());
        row.put("candle_time", ApiFormats.time(candleTime));
        row.put("open", ApiFormats.amount(rs.getBigDecimal("open")));
        row.put("high", ApiFormats.amount(rs.getBigDecimal("high")));
        row.put("low", ApiFormats.amount(rs.getBigDecimal("low")));
        row.put("close", ApiFormats.amount(rs.getBigDecimal("close")));
        row.put("volume", ApiFormats.amount(rs.getBigDecimal("volume")));
        row.put("trade_count", rs.getInt("trade_count"));
        row.put("seq", rs.getLong("seq"));
        row.put("cursor_ts", candleTime);
        return row;
    }
}

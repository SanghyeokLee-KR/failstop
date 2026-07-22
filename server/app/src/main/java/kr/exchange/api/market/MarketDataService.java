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

    public Depth depth(int marketId) {
        List<Level> asks = levels(marketId, "SELL", "ASC");
        List<Level> bids = levels(marketId, "BUY", "DESC");
        return new Depth(asks, bids, total(marketId, "SELL"), total(marketId, "BUY"));
    }

    private List<Level> levels(int marketId, String side, String direction) {
        return jdbc.sql("""
                        SELECT price, SUM(remaining_qty) AS size
                        FROM orders
                        WHERE market_id = ? AND side = ? AND status IN ('OPEN','PARTIAL')
                        GROUP BY price
                        ORDER BY price %s
                        LIMIT %d
                        """.formatted(direction, DEPTH_LEVELS))
                .params(marketId, side)
                .query((rs, rowNum) -> new Level(rs.getBigDecimal("price"), rs.getBigDecimal("size")))
                .list();
    }

    private BigDecimal total(int marketId, String side) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(remaining_qty), 0)
                        FROM orders
                        WHERE market_id = ? AND side = ? AND status IN ('OPEN','PARTIAL')
                        """)
                .params(marketId, side)
                .query(BigDecimal.class)
                .single();
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
            throw new ApiException(400, "invalid_parameter", "커서 형식이 올바르지 않습니다.");
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

        OffsetDateTime dayAgo = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);
        Map<String, Object> agg = jdbc.sql("""
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
        // 첫 페이지는 시각 하한을 두지 않는다. 하한(count 창의 배수)은 밀도 낮은 마켓의 sparse
        // 이력을 잘라 count 미만이 반환되고 has_more가 조기 false로 떨어져 페이지네이션이 조기
        // 종료됐다. 정확성(페이지 완결) > 성능이라 첫 페이지는 마켓 이력 전체를 버킷 집계한다.
        // 커서가 있으면(더 과거 페이지) 스캔에 created_at 상한을 준다. 상한은 cursor.ts + 한
        // 버킷이라 넘겨 담기는 체결은 전부 HAVING이 버리는 버킷(candle_time >= cursor.ts)에만
        // 속해 유지 버킷 집계를 바꾸지 않는다. 결과 불변 + created_at 파티션 프루닝으로 깊은
        // 페이지의 스캔 범위를 좁힌다. created_at 축은 파티션 키·인덱스.
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

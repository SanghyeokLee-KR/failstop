package kr.exchange.api.market;

import kr.exchange.api.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * 마켓 규칙·자산 구성 조회. 게이트웨이 검증(tick/lot/min·max/scale 가드, §1.4 앱 가드 목록)과
 * 시장 데이터 응답이 같은 행을 본다. 요율 박제(§5.2)의 원천이기도 하다.
 * 캐시 없음: 마켓 수가 한 자릿수인 데모 규모에서 정합성이 캐시 이득보다 우선한다.
 */
public class MarketDirectory {

    public record Market(int id, String symbol, String status,
                         short baseAssetId, String baseSymbol, String baseName, int baseScale,
                         short quoteAssetId, String quoteSymbol, int quoteScale,
                         BigDecimal tickSize, BigDecimal lotSize,
                         BigDecimal minNotional, BigDecimal maxNotional, BigDecimal maxQty,
                         BigDecimal makerFeeRate, BigDecimal takerFeeRate) {
    }

    private static final String SELECT = """
            SELECT m.id, m.symbol, m.status,
                   m.base_asset_id, b.symbol AS base_symbol, b.name AS base_name,
                   b.scale AS base_scale,
                   m.quote_asset_id, q.symbol AS quote_symbol, q.scale AS quote_scale,
                   m.tick_size, m.lot_size, m.min_notional, m.max_notional, m.max_qty,
                   m.maker_fee_rate, m.taker_fee_rate
            FROM markets m
            JOIN assets b ON b.id = m.base_asset_id
            JOIN assets q ON q.id = m.quote_asset_id
            """;

    private final JdbcClient jdbc;

    public MarketDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Market bySymbol(String symbol) {
        return jdbc.sql(SELECT + " WHERE m.symbol = ?")
                .params(symbol)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new ApiException(400, "invalid_parameter",
                        "존재하지 않는 마켓입니다: " + symbol));
    }

    public Market byId(int marketId) {
        return jdbc.sql(SELECT + " WHERE m.id = ?")
                .params(marketId)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new IllegalStateException("미등록 마켓: " + marketId));
    }

    public List<Market> all() {
        return jdbc.sql(SELECT + " ORDER BY m.id").query(this::map).list();
    }

    private Market map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Market(
                rs.getInt("id"), rs.getString("symbol"), rs.getString("status"),
                rs.getShort("base_asset_id"), rs.getString("base_symbol"),
                rs.getString("base_name"), rs.getInt("base_scale"),
                rs.getShort("quote_asset_id"), rs.getString("quote_symbol"),
                rs.getInt("quote_scale"),
                rs.getBigDecimal("tick_size"), rs.getBigDecimal("lot_size"),
                rs.getBigDecimal("min_notional"), rs.getBigDecimal("max_notional"),
                rs.getBigDecimal("max_qty"),
                rs.getBigDecimal("maker_fee_rate"), rs.getBigDecimal("taker_fee_rate"));
    }
}

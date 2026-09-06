package kr.exchange.api.market;

import kr.exchange.api.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 마켓 규칙·자산 구성 조회. 게이트웨이 검증(tick/lot/min·max/scale 가드, §1.4 앱 가드 목록)과
 * 시장 데이터 응답이 같은 행을 본다. 요율 박제(§5.2)의 원천이기도 하다.
 *
 * 카탈로그 전체를 TTL 캐시로 들고 세 조회가 그 스냅샷을 본다. 2026-09-06 실측
 * (pg_stat_statements, 주입 200/s 2분)에서 이 조회가 192,409 회로 DB 시간의 16.6% 였다.
 * 회당 0.052ms 라 계획이 아니라 횟수가 비용이다. MarketDataPublisher 가 엔진 이벤트마다
 * byId 를 부르는 것이 대부분을 만든다.
 *
 * 관리 평면이 별도 JVM 이라 프로세스 내 무효화가 닿지 않는다. markets 를 런타임에 바꾸는
 * 경로는 ConfigAdminController 의 halt·resume 과 수수료 변경 둘뿐이고 둘 다 그 JVM 에 있다.
 * 그래서 TTL 로만 수렴시킨다. 대가는 halt 반영이 최대 TTL 만큼 늦는 것이다. 엔진은 status 를
 * 따로 보지 않으므로 그 창에서는 정지된 마켓의 주문이 접수된다.
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

    // 5초. 상한은 halt 반영 지연이 정한다. 정지는 긴급 통제라 30초는 길고, 1초 미만이면
    // 이벤트당 조회를 되살려 캐시 의미가 사라진다.
    private static final long TTL_MS = 5_000;

    private record Snapshot(List<Market> ordered, Map<String, Market> bySymbol,
                            Map<Integer, Market> byId, long expiresAt) {
    }

    private final JdbcClient jdbc;
    // 갱신을 한 스레드로 묶어 만료 순간의 동시 재적재를 막는다.
    private final Object loadLock = new Object();
    private volatile Snapshot snapshot;

    public MarketDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Market bySymbol(String symbol) {
        Market m = current().bySymbol().get(symbol);
        if (m == null) {
            throw new ApiException(400, "invalid_parameter", "존재하지 않는 마켓입니다: " + symbol);
        }
        return m;
    }

    public Market byId(int marketId) {
        Market m = current().byId().get(marketId);
        if (m == null) {
            throw new IllegalStateException("미등록 마켓: " + marketId);
        }
        return m;
    }

    public List<Market> all() {
        return current().ordered();
    }

    private Snapshot current() {
        long now = System.currentTimeMillis();
        Snapshot s = snapshot;
        if (s != null && s.expiresAt() > now) {
            return s;
        }
        synchronized (loadLock) {
            s = snapshot;
            if (s != null && s.expiresAt() > System.currentTimeMillis()) {
                return s;
            }
            List<Market> rows = jdbc.sql(SELECT + " ORDER BY m.id").query(this::map).list();
            Map<String, Market> byName = new HashMap<>(rows.size() * 2);
            Map<Integer, Market> byKey = new HashMap<>(rows.size() * 2);
            for (Market m : rows) {
                byName.put(m.symbol(), m);
                byKey.put(m.id(), m);
            }
            Snapshot fresh = new Snapshot(List.copyOf(rows), Map.copyOf(byName), Map.copyOf(byKey),
                    System.currentTimeMillis() + TTL_MS);
            snapshot = fresh;
            return fresh;
        }
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

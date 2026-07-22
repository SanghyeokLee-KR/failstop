package kr.exchange.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 마켓의 자산 구성·자산 scale 조회. 정산의 양자화 기준과 재생의 수수료 재계산이
 * 같은 값을 봐야 payload 바이트 대조가 성립한다.
 *
 * 캐시를 두는 근거: 자산 scale과 마켓의 base/quote 구성은 불변 데이터다. 변경하면
 * 기존 저널의 양자화 결과가 달라져 재생 결정론이 깨지므로 운영 중 변경 자체가 금지다
 * (DB 설계 §1.4, §4.4 비결정 입력 박제 원칙).
 */
public class MarketCatalog {

    public record MarketMeta(int marketId, short baseAssetId, short quoteAssetId,
                             int baseScale, int quoteScale) {
    }

    private final JdbcClient jdbc;
    private final Map<Integer, MarketMeta> cache = new ConcurrentHashMap<>();

    public MarketCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public MarketMeta meta(int marketId) {
        return cache.computeIfAbsent(marketId, this::load);
    }

    private MarketMeta load(int marketId) {
        return jdbc.sql("""
                        SELECT m.id, m.base_asset_id, m.quote_asset_id,
                               b.scale AS base_scale, q.scale AS quote_scale
                        FROM markets m
                        JOIN assets b ON b.id = m.base_asset_id
                        JOIN assets q ON q.id = m.quote_asset_id
                        WHERE m.id = ?
                        """)
                .params(marketId)
                .query((rs, rowNum) -> new MarketMeta(
                        rs.getInt("id"),
                        rs.getShort("base_asset_id"),
                        rs.getShort("quote_asset_id"),
                        rs.getInt("base_scale"),
                        rs.getInt("quote_scale")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("미등록 마켓: " + marketId));
    }
}

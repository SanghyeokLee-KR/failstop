package kr.exchange.api.community;

import kr.exchange.api.market.MarketDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 인기순 순위 스냅샷 산출(게시판_설계.md §1.2·§3). 가변 like_count를 커서 축에서 분리하기 위해
 * 세대 단위 불변 순위를 적재한다. 한 세대는 산출 시점 like_count를 rank_score로 박제하고, 인기순
 * 커서는 첫 페이지에서 고른 세대를 시퀀스 끝까지 유지한다. 산출·조회 모두 커뮤니티 DB 내부 연산이라
 * 코어 자원과 무관하다. 주기 재산출은 운영 스케줄러가 rebuildAll을 호출하는 훅으로 확장한다(§7).
 */
public class CommunityRankService {

    private final JdbcClient jdbc;
    private final MarketDirectory markets;

    public CommunityRankService(JdbcClient jdbc, MarketDirectory markets) {
        this.jdbc = jdbc;
        this.markets = markets;
    }

    /** 마켓 스냅샷을 새 세대로 재산출하고 세대 번호를 반환한다. */
    public long rebuild(String market) {
        long generation = jdbc.sql("""
                        SELECT coalesce(max(generation), 0) + 1
                        FROM post_rank_snapshot WHERE market_symbol = ?
                        """)
                .param(market).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO post_rank_snapshot (market_symbol, post_id, rank_score, generation, snapshot_at)
                        SELECT ?, p.id, p.like_count, ?, now()
                        FROM posts p
                        WHERE p.market_symbol = ? AND p.status = 'VISIBLE'
                        ON CONFLICT (market_symbol, post_id)
                        DO UPDATE SET rank_score = EXCLUDED.rank_score,
                                      generation = EXCLUDED.generation,
                                      snapshot_at = now()
                        """)
                .params(market, generation, market)
                .update();
        return generation;
    }

    public void rebuildAll() {
        for (MarketDirectory.Market m : markets.all()) {
            rebuild(m.symbol());
        }
    }
}

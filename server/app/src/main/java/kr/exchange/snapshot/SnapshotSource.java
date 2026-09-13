package kr.exchange.snapshot;

import kr.exchange.engine.core.OrderBookImpl;

import java.util.Map;

/**
 * 복구·승격이 소비하는 스냅샷 로드 계약(복구 RTO 설계 §3-b 복원 STEP1). 마켓마다 채택 게이트를 통과한
 * 최신본을 따로 고른다. 채택본이 없는 마켓은 결과에 없고 그 마켓은 0 에서 재생한다(ADR 0022 5-4).
 */
public interface SnapshotSource {

    /** 마켓 id 에서 그 마켓의 채택본으로. 게이트는 format_version·engine_version·config_hash·state_checksum 이다. */
    Map<Integer, MarketLoaded> loadLatestAdoptablePerMarket();

    /**
     * 채택된 마켓 하나의 스냅샷. seq 는 last_applied_seq 이고 그 마켓 커서가 된다.
     * loadMarketSnapshot 이 fresh 마켓에 그대로 적재한다.
     */
    record MarketLoaded(long seq, OrderBookImpl book) {
    }
}

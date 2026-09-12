package kr.exchange.snapshot;

import kr.exchange.engine.core.OrderBookImpl;

import java.util.List;
import java.util.Optional;

/**
 * 복구·승격이 소비하는 스냅샷 로드 계약(복구 RTO 설계 §3-b 복원 STEP1). 채택 게이트를 통과한
 * 최신 세트만 반환하고, 없으면 empty로 seq 0 전량 재생을 유도한다.
 */
public interface SnapshotSource {

    /** 채택 게이트(format_version·engine_version·config_hash·state_checksum)를 통과한 최신 세트. */
    Optional<Loaded> loadLatestAdoptable();

    /**
     * 채택된 스냅샷 세트. seq는 last_applied_seq, books는 마켓별 복원 오더북이다.
     * loadSnapshot이 fresh 엔진에 그대로 적재한다.
     */
    record Loaded(long seq, List<OrderBookImpl> books) {
    }
}

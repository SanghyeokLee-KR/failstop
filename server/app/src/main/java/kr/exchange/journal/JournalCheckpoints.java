package kr.exchange.journal;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 마켓별 재생 상한 묶음. 마켓마다 따로 잡은 연속 물질화 경계이고 전역 스칼라 하나로 줄이지 않는다.
 * 이유는 DB 설계 §5.5-a 다.
 *
 * bounds 는 마켓 id 오름차순으로 순회한다.
 *
 * @param bounds      마켓 id 에서 bound(m) 으로. 커서 이하가 이미 물질화돼 있다는 전제(불변식 M)
 *                    위에서, seq 가 bound(m) 이하인 마켓 m 명령은 전부 이벤트가 있다
 * @param legacyBound journal_axis_boundary('pre_v36_event'). 이 이하는 market_id 없는 이벤트 구간이고
 *                    모든 마켓 상한의 바닥이다
 */
public record JournalCheckpoints(Map<Integer, Long> bounds, long legacyBound) {

    public JournalCheckpoints {
        if (legacyBound < 0) {
            throw new IllegalArgumentException("레거시 경계는 음수일 수 없다");
        }
        for (Map.Entry<Integer, Long> entry : bounds.entrySet()) {
            if (entry.getValue() < 0) {
                throw new IllegalArgumentException(
                        "마켓 " + entry.getKey() + " 체크포인트는 음수일 수 없다");
            }
        }
        bounds = Collections.unmodifiableMap(new TreeMap<>(bounds));
    }

    /**
     * bound(m). 맵에 없는 마켓은 판독 시점에 커서도 명령도 없던 마켓이고, 정의상 그 상한은 바닥인
     * legacyBound 다.
     */
    public long bound(int marketId) {
        return bounds.getOrDefault(marketId, legacyBound);
    }
}

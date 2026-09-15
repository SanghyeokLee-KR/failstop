package kr.exchange.engine.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 전 마켓 매칭엔진. 마켓별 OrderBookImpl을 소유하고 명령을 해당 북에 라우팅한다.
 * 상태 변경은 단일 라이터 스레드 전용이라 동기화를 두지 않는다.
 *
 * 이중 적용 방지: seq가 그 마켓의 적용 커서 이하인 명령은 무시한다. 재생·catch-up이 같은 명령을
 * 다시 공급해도 상태가 변하지 않는 근거가 이 규약이다. 커서를 마켓마다 두는 이유는 대역 채번에서
 * 마켓 사이 seq 대소가 적용 순서를 뜻하지 않기 때문이다(ADR 0022 4-5).
 *
 * 커서가 없는 마켓은 0 이다. 마켓 사이에 통하는 바닥을 두지 않는다. 대역 채번에서는 다른 마켓의 seq 나 스냅샷 seq 가
 * 이 마켓 명령보다 클 수 있어, 그 값을 바닥으로 두면 그 아래 명령을 버린다(ADR 0022 7단계).
 */
public final class MatchingEngineImpl implements MatchingEngine {

    private final TreeMap<Integer, OrderBookImpl> books = new TreeMap<>();
    private final TreeMap<Integer, Long> appliedSeqs = new TreeMap<>();
    // 커서와 함께 갱신해 표시와 갭 회수 판단이 맵 순회 없이 읽는다. 승격 설치 판정의 appliedSeqs() 사본은 정지 대기를
    // 넘긴 따라잡기 틱과 겹치면 어긋날 수 있고, 그 오독은 미도달이나 설치 전후 불일치로 가 재시도가 흡수한다.
    private long maxAppliedSeq;

    @Override
    public List<EngineEvent> apply(OrderCommand command) {
        if (command.seq() <= appliedSeq(command.marketId())) {
            return List.of();
        }
        OrderBookImpl book = books.computeIfAbsent(command.marketId(), OrderBookImpl::new);
        List<EngineEvent> events = book.apply(command);
        appliedSeqs.put(command.marketId(), command.seq());
        maxAppliedSeq = Math.max(maxAppliedSeq, command.seq());
        return events;
    }

    @Override
    public long appliedSeq(int marketId) {
        return appliedSeqs.getOrDefault(marketId, 0L);
    }

    /** 커서를 가진 마켓의 적용 seq. 마켓 id 오름차순 사본이다. */
    public Map<Integer, Long> appliedSeqs() {
        return Collections.unmodifiableMap(new TreeMap<>(appliedSeqs));
    }

    @Override
    public long lastAppliedSeq() {
        return maxAppliedSeq;
    }

    /** 전 마켓 정준 상태 해시. 마켓 id 오름차순으로 북 체크섬을 접는다. */
    @Override
    public long stateChecksum() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, OrderBookImpl> e : books.entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue().stateChecksum()).append('\n');
        }
        return StateHash.sha256ToLong(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 마켓 북 조회. 없으면 null. 검증·스냅샷 경로 전용이며 상태 변경 진입점이 아니다. */
    public OrderBookImpl orderBook(int marketId) {
        return books.get(marketId);
    }

    /** 소유 마켓 id 오름차순. 스냅샷은 마켓별로 1건씩 기록하므로 이 목록을 순회한다(§3-b). */
    public List<Integer> markets() {
        return new ArrayList<>(books.keySet());
    }

    /**
     * 마켓 하나의 스냅샷 로드. 그 마켓이 fresh일 때만 허용한다. 다른 마켓의 진행과는 무관하다.
     * 이미 전진한 마켓에 겹쳐 로드하면 그 마켓의 재생 prefix가 어긋나므로(§3-a) 가드로 막는다.
     */
    public void loadMarketSnapshot(int marketId, long snapshotSeq, OrderBookImpl book) {
        if (book.marketId() != marketId) {
            throw new IllegalArgumentException(
                    "스냅샷 북의 마켓이 다르다: market=" + marketId + ", book=" + book.marketId());
        }
        if (appliedSeq(marketId) != 0 || books.containsKey(marketId)) {
            throw new IllegalStateException("스냅샷 로드는 fresh 마켓 전용: market=" + marketId
                    + ", appliedSeq=" + appliedSeq(marketId));
        }
        books.put(marketId, book);
        appliedSeqs.put(marketId, snapshotSeq);
        maxAppliedSeq = Math.max(maxAppliedSeq, snapshotSeq);
    }
}

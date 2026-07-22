package kr.exchange.engine.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 전 마켓 매칭엔진. 마켓별 OrderBookImpl을 소유하고 명령을 해당 북에 라우팅한다.
 * 상태 변경은 단일 라이터 스레드 전용이라 동기화를 두지 않는다.
 *
 * 이중 적용 방지: seq <= lastAppliedSeq인 명령은 무시한다. 재생·catch-up이 같은 명령을
 * 다시 공급해도 상태가 변하지 않는 근거가 이 규약이다.
 */
public final class MatchingEngineImpl implements MatchingEngine {

    private final TreeMap<Integer, OrderBookImpl> books = new TreeMap<>();
    private long lastAppliedSeq;

    @Override
    public List<EngineEvent> apply(OrderCommand command) {
        if (command.seq() <= lastAppliedSeq) {
            return List.of();
        }
        OrderBookImpl book = books.computeIfAbsent(command.marketId(), OrderBookImpl::new);
        List<EngineEvent> events = book.apply(command);
        lastAppliedSeq = command.seq();
        return events;
    }

    @Override
    public long lastAppliedSeq() {
        return lastAppliedSeq;
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
     * 스냅샷 로드(복구 RTO 설계 §3-b 복원 STEP1). fresh 엔진에만 허용한다. 이미 전진한
     * 엔진에 겹쳐 로드하면 재생 prefix가 어긋나므로(§3-a fresh 엔진 필수) 가드로 막는다.
     * 로드 후 tail 재생은 seq > lastAppliedSeq만 적용해 스냅샷 경계 위로 이어붙인다.
     */
    public void loadSnapshot(long snapshotSeq, List<OrderBookImpl> restoredBooks) {
        if (lastAppliedSeq != 0 || !books.isEmpty()) {
            throw new IllegalStateException(
                    "스냅샷 로드는 fresh 엔진 전용: lastAppliedSeq=" + lastAppliedSeq);
        }
        for (OrderBookImpl book : restoredBooks) {
            books.put(book.marketId(), book);
        }
        lastAppliedSeq = snapshotSeq;
    }
}

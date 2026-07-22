package kr.exchange.engine;

import kr.exchange.engine.core.EngineEvent;
import kr.exchange.engine.core.MatchingEngine;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.journal.ReplayResult;
import kr.exchange.journal.ReplayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 기동 복구: 저널 재생으로 오더북을 재구성하고, 크래시 창(명령 커밋 후·이벤트 커밋 전)의
 * 재방출 이벤트를 재적재·재정산한다(DB 설계 §5.5 ②).
 *
 * pending을 버리고 기동하면 이후 신규 명령의 이벤트가 계속 적재되면서 저널 스트림이
 * 재생 스트림의 prefix 성질을 잃고, 다음 재기동의 대조가 영구 실패한다(무음 유실 + 벽돌).
 * 단일 라이터의 방출 순서 그대로 sink에 넘기므로 event_seq는 W+1부터 연속 배정되어
 * prefix 성질이 유지된다.
 *
 * sink는 라이브 루프와 동일 계약(명령 단위 배치, append와 정산의 원자 커밋)이다. 명령
 * 단위로 잘라 넘기므로 도중 크래시가 나도 커밋된 prefix는 레지스트리 멱등 판정으로
 * 스킵되고 나머지만 재정산된다.
 *
 * sink가 배정한 event_seq는 재적재 결과라 여기서 버린다. 복구는 WS 게시 대상이 아니고
 * (라이브만 게시), 재기동 후 스냅샷·구독으로 상태가 전달된다. 게시 비대칭은 의도된 것이다.
 */
public class EngineRecovery {

    private static final Logger log = LoggerFactory.getLogger(EngineRecovery.class);

    private final ReplayService replayService;
    private final SingleWriterLoop.EventSink reappendSink;
    private final LeaderLeaseService lease;

    public EngineRecovery(ReplayService replayService, SingleWriterLoop.EventSink reappendSink,
                          LeaderLeaseService lease) {
        this.replayService = replayService;
        this.reappendSink = reappendSink;
        this.lease = lease;
    }

    public ReplayResult recover(MatchingEngine engine) {
        ReplayResult result = replayService.rebuild(engine);
        List<EngineEvent> pending = result.pendingEvents();
        if (pending.isEmpty()) {
            return result;
        }
        // 재적재는 리더 자격이 필요하다. 승격 실패(유효한 타 리더 존재)는 기동 거부로 처리한다.
        // 그 리더가 같은 창을 이미 메웠다면 애초에 pending이 비고, 메우는 중이라면 여기서의
        // 병행 적재는 레지스트리 충돌·fencing으로 어차피 실패해야 맞다(무음 유실 금지).
        if (!lease.isLeader() && !lease.tryAcquire()) {
            throw new IllegalStateException(
                    "크래시 창 이벤트 " + pending.size() + "건 재적재 불가: 리더 승격 실패");
        }
        // EngineConfig 순서상 recover()가 lease.start()보다 먼저 돌아 이 구간엔 스케줄러
        // renew가 없다. 대형 크래시 창 재적재가 TTL을 넘기면 정당 단독 리더가 fencing으로
        // 자기-거부한다. 재적재 중 주기 renew로 리스를 살려둔다(§5.5). renew 실패는 실제
        // 상실이므로 이어지는 sink append가 NotLeader/fencing으로 fail-fast한다.
        long renewIntervalNanos = lease.ttl().dividedBy(3).toNanos();
        long lastRenewNanos = System.nanoTime();
        // 배치 그룹핑은 pending이 source_cmd_seq 오름차순·연속이라는 재생 방출 순서에 의존한다.
        int from = 0;
        while (from < pending.size()) {
            if (System.nanoTime() - lastRenewNanos >= renewIntervalNanos) {
                lease.renew();
                lastRenewNanos = System.nanoTime();
            }
            int to = from + 1;
            while (to < pending.size()
                    && pending.get(to).sourceCmdSeq() == pending.get(from).sourceCmdSeq()) {
                to++;
            }
            List<EngineEvent> batch = pending.subList(from, to);
            try {
                reappendSink.accept(batch);
            } catch (Exception e) {
                throw new IllegalStateException("크래시 창 재적재·재정산 실패: source_cmd_seq="
                        + batch.get(0).sourceCmdSeq(), e);
            }
            from = to;
        }
        log.info("크래시 창 이벤트 재적재·재정산: {}건 (source_cmd_seq {}..{})", pending.size(),
                pending.get(0).sourceCmdSeq(), pending.get(pending.size() - 1).sourceCmdSeq());
        return result;
    }
}

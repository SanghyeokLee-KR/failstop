package kr.exchange.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 단일 라이터 루프 fail-fast 통지 처리(DB 설계 §5.4).
 *
 * sink 실패는 원인 불문(fencing 확정 거부는 물론 일시 DB 오류 포함) 리더십을 내려놓는다.
 * 루프만 멈추고 리스를 쥔 채 renew를 계속하면 접수(저널 커밋 ACK)는 무기한 성공하는데
 * 매칭은 죽어 있고, 리스 점유 때문에 타 노드 failover도 막힌다. demote는 재승격 금지라
 * 리스가 TTL 만료로 풀려 failover가 열리고, requireLeader가 이후 전 쓰기를 거부해
 * 접수 경로가 함께 닫힌다. 복귀는 재생을 포함한 프로세스 재기동으로만 한다.
 */
public class LoopFatalHandler implements Consumer<Throwable> {

    private static final Logger log = LoggerFactory.getLogger(LoopFatalHandler.class);

    private final LeaderLeaseService lease;

    public LoopFatalHandler(LeaderLeaseService lease) {
        this.lease = lease;
    }

    @Override
    public void accept(Throwable t) {
        log.error("단일 라이터 루프 정지(fail-fast)", t);
        lease.demote("이벤트 sink 실패: " + t.getClass().getSimpleName());
    }
}

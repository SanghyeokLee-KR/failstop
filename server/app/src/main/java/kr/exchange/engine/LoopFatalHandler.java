package kr.exchange.engine;

import kr.exchange.journal.SqlErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 단일 라이터 루프 fail-fast 통지 처리(DB 설계 §5.4, 파티션 단위는 §5.4-c 5).
 *
 * sink 가 올린 실패는 원인 불문 리더십을 내려놓는다. 일시 DB 오류(잠금 대기 초과·취소·교착·직렬화)는
 * 정산 sink 가 먼저 제한 횟수만큼 다시 돌리므로 여기에는 그 한도를 넘긴 것만 온다(SettlementService).
 * 루프만 멈추고 리스를 쥔 채 renew를 계속하면 접수(저널 커밋 ACK)는 무기한 성공하는데
 * 매칭은 죽어 있고, 리스 점유 때문에 타 노드 failover도 막힌다.
 *
 * 기간 배선은 그 루프의 기간으로 그 파티션만 강등한다(WRITER_FATAL). 원인이 VirtualMachineError 면 노드 치명이다.
 * AssertionError 처럼 VM 오류가 아닌 Error 는 파티션 치명이다. 강등 전 루프의 늦은 실패는 기간이 달라 새 기간을 건드리지 않는다.
 */
public class LoopFatalHandler implements Consumer<Throwable> {

    private static final Logger log = LoggerFactory.getLogger(LoopFatalHandler.class);

    private final LeaderLeaseService lease;
    private final PartitionRuntime runtime;
    private final LeaderTerm term;

    /** 종전 배선. 리스 서비스가 지금 쥔 기간을 놓는다. */
    public LoopFatalHandler(LeaderLeaseService lease) {
        this.lease = lease;
        this.runtime = null;
        this.term = null;
    }

    /** 기간 배선. */
    public LoopFatalHandler(PartitionRuntime runtime, LeaderTerm term) {
        this.lease = null;
        this.runtime = runtime;
        this.term = term;
    }

    @Override
    public void accept(Throwable t) {
        // 원인 SQLSTATE 와 사슬을 한 줄에 싣는다. 롤백 실패 뒤에 가려진 원래 예외는 스택만으로는 드러나지 않는다.
        LeaderTerm failed = term != null ? term : lease.currentTerm().orElse(null);
        log.error("단일 라이터 루프 정지(fail-fast): resource={}, epoch={}, sqlstate={}, 원인={}",
                failed == null ? lease.resource() : failed.resource(), failed == null ? "-" : failed.epoch(),
                SqlErrors.stateLabel(t), SqlErrors.describe(t), t);
        boolean nodeFatal = t instanceof VirtualMachineError;
        if (runtime != null) {
            runtime.demote(term, nodeFatal ? DemotionReason.NODE_FATAL : DemotionReason.WRITER_FATAL, t);
            return;
        }
        String reason = "이벤트 sink 실패: " + t.getClass().getSimpleName();
        if (nodeFatal) {
            lease.markNodeFatal(reason);
        } else {
            lease.demote(reason);
        }
    }
}

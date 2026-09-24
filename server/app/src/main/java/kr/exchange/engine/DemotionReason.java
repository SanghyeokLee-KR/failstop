package kr.exchange.engine;

/**
 * 파티션 강등 사유(DB 설계 §5.4-c 5). 회복 가능 반납(승격 재생 구간의 실패)은 강등이 아니라 여기 없다.
 * 지표 exchange.partition.demotions{reason} 의 값이 이 이름이다.
 */
public enum DemotionReason {
    /** 운영자가 파티션 참여를 껐다. */
    PLANNED,
    /** 그 파티션 라이터의 sink 실패. VM 오류가 아닌 Error 도 여기다. */
    WRITER_FATAL,
    /** 접수를 연 뒤의 갱신 거부·fencing 상실. */
    LEASE_LOST_WHILE_ACCEPTING,
    /** 좀비 판정(노드 층이나 파티션 층)의 자발적 강등. */
    STALL,
    /** 승격을 마친 시점에 그 파티션 라이터가 멈춰 있었다. */
    PROMOTION_LOOP_DOWN,
    /** VirtualMachineError. 모든 파티션을 종신 강등한다. */
    NODE_FATAL;

    /** 차단기가 세는 사유(DB 설계 §5.4-c 10). 계획·노드 치명은 세지 않는다. */
    public boolean countsTowardBreaker() {
        return this == WRITER_FATAL || this == LEASE_LOST_WHILE_ACCEPTING
                || this == STALL || this == PROMOTION_LOOP_DOWN;
    }
}

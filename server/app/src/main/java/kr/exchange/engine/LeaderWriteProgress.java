package kr.exchange.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 리더 기원 쓰기의 시도·커밋 카운터(DB 설계 §5.4 자발적 강등의 진행 축).
 *
 * 시도와 커밋을 따로 센다. '할 일이 없어서 조용한 것'과 '할 일이 막혀 있는 것'은 커밋만으로는
 * 구분되지 않는다. 조회 부하만으로도 요청 풀은 포화될 수 있으므로, 시도 축이 없으면 주문이 없는
 * 정상 리더가 정지로 오판된다.
 *
 * 커밋은 트랜잭션 성공 시점에만 센다. 시도를 커밋으로 세면 커넥션 획득에서 타임아웃만 반복하는
 * 좀비 노드도 '진행 중'으로 보인다.
 *
 * 값의 절대 크기는 의미가 없고 표본 사이의 변화 여부만 쓴다. 승격 재생으로 카운터가 되감기거나
 * 노드가 교체돼도 '변했다'로 읽혀 오탐이 아니라 안전 측으로 흐른다.
 */
public final class LeaderWriteProgress {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong commits = new AtomicLong();

    /** 리더 기원 쓰기 트랜잭션 진입 시점에 호출한다. */
    public void recordAttempt() {
        attempts.incrementAndGet();
    }

    /** 리더 기원 쓰기 트랜잭션 커밋 성공 시점에 호출한다. */
    public void recordCommit() {
        commits.incrementAndGet();
    }

    public long attempts() {
        return attempts.get();
    }

    public long commits() {
        return commits.get();
    }
}

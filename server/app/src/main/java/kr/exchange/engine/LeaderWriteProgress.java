package kr.exchange.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 리더 기원 쓰기 커밋 진행성 카운터(DB 설계 §5.4 자발적 강등의 진행 축).
 *
 * command_journal append와 정산이 커밋될 때만 증가한다. 시도가 아니라 커밋을 세는 것이 핵심이다.
 * 시도를 세면 커넥션 획득에서 30초씩 타임아웃하며 실패만 반복하는 좀비 노드도 '진행 중'으로 보인다.
 *
 * 값의 절대 크기는 의미가 없고 표본 사이의 변화 여부만 쓴다. 승격 재생으로 카운터가 되감기거나
 * 노드가 교체돼도 '변했다'로 읽혀 오탐이 아니라 안전 측으로 흐른다.
 */
public final class LeaderWriteProgress {

    private final AtomicLong commits = new AtomicLong();

    /** 리더 기원 쓰기 트랜잭션 커밋 성공 시점에 호출한다. */
    public void recordCommit() {
        commits.incrementAndGet();
    }

    public long commits() {
        return commits.get();
    }
}

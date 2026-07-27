package kr.exchange.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 리더 쓰기 수요·진행 카운터(DB 설계 §5.4 자발적 강등의 두 축).
 *
 * attempts는 '쓰기 요청이 이 노드에 도착했다'를, completions는 '요청 경로의 DB 작업이 실제로
 * 끝났다'를 센다. 두 축을 분리해야 '할 일이 없어 조용한 것'과 '할 일이 막힌 것'이 구분된다.
 *
 * 계측 지점의 도달 가능성이 이 클래스의 핵심 제약이다. 대상 장애는 요청 풀 고갈이고, 그 상태에서는
 * 인증 필터의 첫 DB 조회부터 실패해 컨트롤러·게이트웨이·저널 append에 도달하지 못한다. attempts를
 * 그 뒤에 계측하면 정작 좀비 상태에서 한 번도 증가하지 않아 판정이 영원히 성립하지 않는다.
 * 그래서 attempts는 DB를 건드리기 전인 인증 필터 진입부에서 센다(ApiAuthFilter).
 *
 * completions는 반대로 DB 작업이 성공해야만 증가해야 한다. 시도를 진행으로 세면 커넥션 획득에서
 * 타임아웃만 반복하는 좀비 노드도 '진행 중'으로 보인다.
 *
 * 값의 절대 크기는 의미가 없고 표본 사이의 변화 여부만 쓴다. 승격 재생으로 카운터가 되감기거나
 * 노드가 교체돼도 '변했다'로 읽혀 오탐이 아니라 안전 측으로 흐른다.
 */
public final class LeaderWriteProgress {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong completions = new AtomicLong();

    /** 쓰기 요청 도착. DB I/O 이전에 호출해야 계측 지점이 좀비 상태에서도 도달 가능하다. */
    public void recordAttempt() {
        attempts.incrementAndGet();
    }

    /** 요청 경로 DB 작업 완료(계정 조회 성공, 저널 append 커밋, 정산 커밋). */
    public void recordCompletion() {
        completions.incrementAndGet();
    }

    public long attempts() {
        return attempts.get();
    }

    public long completions() {
        return completions.get();
    }
}

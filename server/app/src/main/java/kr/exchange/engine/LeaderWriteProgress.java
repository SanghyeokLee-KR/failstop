package kr.exchange.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 리더 쓰기 수요·완료 카운터(DB 설계 §5.4 자발적 강등의 두 축).
 *
 * attempts는 '쓰기 요청이 이 노드에 도착했다'를, completions는 '리더 기원 쓰기가 실제로 커밋됐다'를
 * 센다. 두 축의 비율이 곧 이 노드의 처리율이다.
 *
 * 계측 지점이 이 클래스의 전부다. 두 번의 라이브 실패가 전부 계측 위치 때문이었다.
 *  - attempts는 DB를 건드리기 전인 인증 필터 진입부에서 센다(ApiAuthFilter). 좀비 상태에서는
 *    인증의 api_keys 조회부터 실패해 컨트롤러·게이트웨이·저널 append에 도달하지 못하므로, 그
 *    뒤에서 세면 정작 대상 장애에서 한 번도 증가하지 않는다.
 *  - completions는 command_journal append 커밋과 정산 커밋에서만 센다. 인증 조회 성공·계정 조회
 *    성공·조기 거절 같은 읽기 완료를 여기 포함하면 축이 '이 노드가 주문을 처리하는가'가 아니라
 *    '어딘가 DB 호출 하나가 성공했는가'를 재게 된다. 실측에서 체결이 6분에 6건인 좀비가 인증
 *    조회 성공만으로 완료율 1.35를 유지해 판정이 눈멀었다.
 *
 * 값의 절대 크기는 의미가 없고 창 안의 증분만 쓴다. 승격 재생으로 카운터가 되감기거나 노드가
 * 교체돼도 안전 측으로 흐른다.
 */
public final class LeaderWriteProgress {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong completions = new AtomicLong();

    /** 쓰기 요청 도착. DB I/O 이전에 호출해야 계측 지점이 좀비 상태에서도 도달 가능하다. */
    public void recordAttempt() {
        attempts.incrementAndGet();
    }

    /** 리더 기원 쓰기 커밋 성공에만 호출한다(저널 append 커밋·정산 커밋). 읽기 성공은 제외다. */
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

package kr.exchange.api.common;

/**
 * 수용 제한 상한 배분(API 설계 §2-5-a). 요청 커넥션 풀 하나를 쓰기·조회·예비 셋으로 나눈다.
 *
 * 왜 합이 풀보다 작아야 하는가(라이브 2회 실증).
 *  1차(쓰기만 유계화, 상한 = 풀의 75%). 라이브에서 쓰기는 실제로 잘렸다(in_flight 23~24, rejected
 *     276). 그런데도 노드는 cpu 319%로 죽었고 해제 후 자력 회복하지 못했다. 남은 부하는 조회였다.
 *     쓰기가 24를 점유하면 남는 8은 status 전용이 아니라 **모든 조회가 경합하는 공용 잔량**이라,
 *     봇의 다중행 조회가 그 8을 채우는 순간 status GET은 종전대로 굶는다.
 *  결론 두 가지.
 *  (a) 조회도 별도 버킷으로 유계화한다. 한 버킷으로 합치면 쓰기가 조회에 밀리거나 그 반대가 된다.
 *  (b) 쓰기+조회의 합이 풀보다 **작아야** 한다. 이유도 둘이다.
 *      - 면제 경로와 백그라운드가 같은 풀을 쓴다. actuator health의 DB 지표, `/harness/v1/status`,
 *        §7-6 by-client 재조회, WS 퍼블리셔, 스냅샷 워커, 정산 루프, 불변식 스케줄러는 수용 게이트가
 *        없다. 게이트된 트래픽이 풀을 전량 점유할 수 있으면 이들이 굶고, 하필 그중 둘이 노드의
 *        생존 신호다. 예비분이 "생존 신호는 막히지 않는다"를 필터 계층이 아니라 커넥션 계층에서
 *        참으로 만든다.
 *      - 스레드 축적. 합이 풀 이하이면 수용된 요청은 커넥션을 기다릴 일이 없다(요청 하나가 동시에
 *        쥐는 커넥션은 최대 1개다). 풀이 게이트된 트래픽에 대해 대기 자원이 아니게 되고, DB에
 *        블록된 스레드 수가 타임아웃이 아니라 설계로 유계가 된다. 죽음의 직접 원인이 커넥션이
 *        아니라 그것을 기다리는 스레드였으므로 이 성질이 핵심이다.
 *
 * 40/40/20 배분. 쓰기와 조회를 대등하게 두고 20%를 예비로 남긴다. 조회를 더 조이면 봇의 시장
 * 데이터 조회가 실패해 호가 갱신이 멈추고 주문 흐름 자체가 말라 '체결이 멈추지 않는다'는 1차
 * 목표를 조회 쪽에서 깬다. 쓰기를 더 조이면 체결 처리량이 직접 깎인다. 어느 쪽도 근거가 더 세지
 * 않아 대등 배분을 택하고, 비대칭이 필요하면 설정으로 덮는다.
 *
 * 하한 4는 통합테스트·로컬의 초소형 풀(2~4)에서 상한이 0~1로 내려앉아 정상 경로까지 막는 것을
 * 막는다. 이 구간에서는 합이 풀을 넘을 수 있고, 그때는 위 (b)의 무대기 성질이 성립하지 않는다.
 * 풀이 10 이상이면 40/40 규칙이 지배해 합은 항상 풀의 80%다.
 */
public record AdmissionLimits(int write, int query, int requestPoolSize) {

    private static final double WRITE_POOL_SHARE = 0.40;
    private static final double QUERY_POOL_SHARE = 0.40;
    private static final int MIN_LIMIT = 4;

    /** 0 이하 설정은 풀 크기에서 유도한다. 카오스 스택은 풀을 환경변수로 덮으므로 유도가 곧 추종이다. */
    public static AdmissionLimits resolve(int requestPoolSize, int configuredWrite,
                                          int configuredQuery) {
        return new AdmissionLimits(
                share(configuredWrite, requestPoolSize, WRITE_POOL_SHARE),
                share(configuredQuery, requestPoolSize, QUERY_POOL_SHARE),
                requestPoolSize);
    }

    private static int share(int configured, int poolSize, double fraction) {
        if (configured > 0) {
            return configured;
        }
        return Math.max(MIN_LIMIT, (int) Math.floor(poolSize * fraction));
    }

    /** 면제 경로·백그라운드 몫. 음수면 초소형 풀에서 하한이 지배한 것이다. */
    public int reserved() {
        return requestPoolSize - write - query;
    }
}

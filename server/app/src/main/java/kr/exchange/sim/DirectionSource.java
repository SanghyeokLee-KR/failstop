package kr.exchange.sim;

/**
 * 테이커 방향 결정 차원(설계 r7). buyProbability 입력 스칼라 d in [-1,1]의 산출원만 바꾼다.
 * rng 소비 순서·자금·서명 경로는 불변이라 신규 BotType 없이 페르소나를 실현한다.
 * - STATIC: fleet effective bias. 기존 TAKER·NOISE 프리셋 그대로.
 * - MOMENTUM: 공개 ticker의 change 부호 * change_rate 크기. 추세추종(상승 -> 매수).
 * - MEAN_REVERT: 공개 trades의 SMA 대비 편차 반대 부호. 역추세(고평가 -> 매도).
 * TREND 코호트는 index 결정론으로 MOMENTUM/MEAN_REVERT 이분한다(어휘·캡 불변).
 */
public enum DirectionSource {
    STATIC,
    MOMENTUM,
    MEAN_REVERT;

    /**
     * 유형·인덱스 -> 방향 소스(순수). TREND만 이원 페르소나로 분할하고 나머지는 STATIC.
     * index 짝수 MOMENTUM, 홀수 MEAN_REVERT. 시드·rng 미소비라 재생 결정론을 깨지 않는다.
     */
    public static DirectionSource of(BotType type, int index1based) {
        if (type != BotType.TREND) {
            return STATIC;
        }
        return index1based % 2 == 0 ? MOMENTUM : MEAN_REVERT;
    }
}

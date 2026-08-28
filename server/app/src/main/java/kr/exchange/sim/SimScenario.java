package kr.exchange.sim;

import java.util.Locale;

/**
 * 시장 국면 프리셋(API §6-A v2.31). 전역 OPS·유형별 대수·bias를 국면 하나로 묶는다.
 * 값은 전부 기준선 대비 배수다. 배포마다 기준 규모가 달라(하네스 normal 300 OPS 대
 * application.yml 60 OPS) 절대값을 못 박으면 한쪽에서 무의미해진다. 기준선은 등록 시점
 * 값이라 같은 프리셋을 두 번 활성화해도 누적되지 않는다.
 *
 * spike·thundering-herd는 대수 배수가 전부 1.0이다. 하네스_검증설계 §2-1 프로파일표가
 * 두 국면을 "동일 플릿"·"전 봇"으로 규정해서다. 구성 변화는 그 표에 없는 volatile·ramp가
 * 담당한다.
 *
 * §2-1 spike 행은 크기를 OPS 기저의 10배와 "TAKER λ 10배" 두 축으로 준다. 둘 다 집행하고
 * 배수를 같게 둔다. 전역 버킷은 발행 상한이고 실제 발행량은 min(봇 수요, targetOps)라
 * 한쪽만 올리면 배수가 그대로 안 나온다. λ 배수는 레짐 소유 스칼라를 덮지 않고 곱해지는
 * 별도 값이라 재생 결정론을 건드리지 않는다.
 *
 * λ 배수는 테이커 경로 전체에 걸린다. TREND·NOISE가 TakerBot 프리셋으로 실현돼 TAKER와
 * 같은 taker.lambda()를 읽기 때문이다. 유형별로 갈리는 것은 확신 게인과 크기 배수뿐이다.
 *
 * 레짐(CALM/TREND/SHOCK)은 건드리지 않는다. 레짐 시퀀스는 시드 PRNG가 controlStep당
 * 1회 전진시키는 재생 결정론의 일부다.
 */
public enum SimScenario {

    STEADY("steady", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.00, true, 0.5),
    VOLATILE("volatile", 1.5, 1.5, 0.6, 1.3, 1.5, 1.2, 0.00, true, 0.15),
    RAMP("ramp", 2.0, 2.0, 1.0, 1.4, 2.0, 1.0, 0.35, true, 0.3),
    SPIKE("spike", 10.0, 10.0, 1.0, 1.0, 1.0, 1.0, 0.60, false, 0.5),
    THUNDERING_HERD("thundering-herd", 12.0, 12.0, 1.0, 1.0, 1.0, 1.0, 0.70, false, 0.5);

    private final String wireName;
    private final double opsMult;
    private final double lambdaMult;
    private final double marketMakerMult;
    private final double takerMult;
    private final double trendMult;
    private final double noiseMult;
    private final double targetBias;
    private final boolean autoBalance;
    private final double strength;

    SimScenario(String wireName, double opsMult, double lambdaMult, double marketMakerMult,
                double takerMult, double trendMult, double noiseMult, double targetBias,
                boolean autoBalance, double strength) {
        this.wireName = wireName;
        this.opsMult = opsMult;
        this.lambdaMult = lambdaMult;
        this.marketMakerMult = marketMakerMult;
        this.takerMult = takerMult;
        this.trendMult = trendMult;
        this.noiseMult = noiseMult;
        this.targetBias = targetBias;
        this.autoBalance = autoBalance;
        this.strength = strength;
    }

    public String wireName() {
        return wireName;
    }

    public double opsMult() {
        return opsMult;
    }

    /**
     * 테이커 경로 도착률 배수(§2-1 "TAKER λ 10배"). opsMult와 같게 둔다. 전역 버킷은 발행
     * 상한이고 실제 발행량은 min(봇 수요, targetOps)라 한쪽만 올리면 배수가 그대로 안 나온다.
     */
    public double lambdaMult() {
        return lambdaMult;
    }

    public double targetBias() {
        return targetBias;
    }

    public boolean autoBalance() {
        return autoBalance;
    }

    /** auto off 국면에서는 저장만 되고 균형 루프가 돌지 않아 무효다. */
    public double strength() {
        return strength;
    }

    public double mult(BotType type) {
        return switch (type) {
            case MARKET_MAKER -> marketMakerMult;
            case TAKER -> takerMult;
            case TREND -> trendMult;
            case NOISE -> noiseMult;
        };
    }

    /** 기준선에 배수를 곱해 반올림한다. 기준선 0인 유형은 0으로 남는다. */
    public int scaled(BotType type, int baseline) {
        if (baseline <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.round(baseline * mult(type)));
    }

    /** 기준 OPS에 배수를 곱한다. 상한은 두지 않는다. PUT /sim/rate로 이미 도달 가능한 값이다. */
    public double scaledOps(double baseOps) {
        return baseOps * opsMult;
    }

    /** 경로 변수 파싱. 대소문자와 밑줄 표기를 함께 받되 정본 표기는 하이픈이다. */
    public static SimScenario parse(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (SimScenario s : values()) {
            if (s.wireName.equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("알 수 없는 시나리오 프리셋: " + raw);
    }
}

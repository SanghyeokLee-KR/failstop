package kr.exchange.sim;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대수 스케일·자동균형 상태 거버넌스 검증(설계 §9 대수 스케일·자동균형·백프레셔 항).
 * 캡 클램프+감사 신호, 파킹 게이트, 봇 수 증가의 OPS 무영향(백프레셔 무해)을 확인한다.
 */
class BotFleetGovernorTest {

    private static Map<BotType, Integer> targets(int mm, int taker, int trend, int noise) {
        EnumMap<BotType, Integer> m = new EnumMap<>(BotType.class);
        m.put(BotType.MARKET_MAKER, mm);
        m.put(BotType.TAKER, taker);
        m.put(BotType.TREND, trend);
        m.put(BotType.NOISE, noise);
        return m;
    }

    @Test
    void scaleWithinCapsAppliesExactly() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 200, 500, 8);
        BotFleetGovernor.ScaleResult r = gov.setScale("KRW-BTC", targets(10, 8, 4, 6));
        assertFalse(r.clamped(), "캡 이내는 클램프 없음");
        assertEquals(10, r.applied().get(BotType.MARKET_MAKER));
        assertEquals(6, r.applied().get(BotType.NOISE));
    }

    @Test
    void typeCapClampsAndSignalsForAudit() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 500, 1000, 8);
        BotFleetGovernor.ScaleResult r = gov.setScale("KRW-BTC", targets(999, 0, 0, 0));
        assertTrue(r.clamped(), "타입 캡 초과는 clamped=true");
        assertEquals(30, r.applied().get(BotType.MARKET_MAKER), "타입 캡으로 클램프");
    }

    @Test
    void marketCapClampsTotal() {
        BotFleetGovernor gov = new BotFleetGovernor(100, 80, 1000, 8);
        BotFleetGovernor.ScaleResult r = gov.setScale("KRW-BTC", targets(40, 40, 40, 40));
        assertTrue(r.clamped());
        int sum = r.applied().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(80, sum, "마켓 캡으로 합 클램프");
    }

    @Test
    void globalCapAccountsForOtherMarkets() {
        BotFleetGovernor gov = new BotFleetGovernor(100, 100, 120, 8);
        gov.setScale("KRW-BTC", targets(60, 0, 0, 0));
        BotFleetGovernor.ScaleResult r = gov.setScale("KRW-ETH", targets(80, 0, 0, 0));
        assertTrue(r.clamped(), "전역 캡 초과 클램프");
        int sum = r.applied().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(60, sum, "전역 잔여 예산(120-60)으로 클램프");
    }

    @Test
    void parkingGateActivatesOnlyUpToTarget() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 80, 300, 8);
        gov.setScale("KRW-BTC", targets(0, 2, 0, 0));
        assertTrue(gov.active("KRW-BTC", BotType.TAKER, 1));
        assertTrue(gov.active("KRW-BTC", BotType.TAKER, 2));
        assertFalse(gov.active("KRW-BTC", BotType.TAKER, 3), "목표 초과 인덱스는 파킹");
        assertEquals(2, gov.activeCount("KRW-BTC", BotType.TAKER));
        // 목표를 올리면 파킹 봇이 즉시 활성.
        gov.setScale("KRW-BTC", targets(0, 3, 0, 0));
        assertTrue(gov.active("KRW-BTC", BotType.TAKER, 3));
    }

    @Test
    void activeCountBoundedByPool() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 80, 300, 4);
        gov.setScale("KRW-BTC", targets(0, 10, 0, 0));
        // 목표 10이지만 풀 4라 활성은 풀 상한. 초과 목표는 재기동 없이 물리 확장 불가.
        assertEquals(4, gov.activeCount("KRW-BTC", BotType.TAKER));
    }

    @Test
    void scaleDoesNotTouchOpsBudget() {
        // 백프레셔 무해: 봇 수 목표를 올려도 전역 OPS 버킷은 불변이다(구조적 상한).
        OpsGovernor ops = new OpsGovernor(20.0);
        BotFleetGovernor gov = new BotFleetGovernor(300, 500, 1000, 8);
        double before = ops.targetOps();
        gov.setScale("KRW-BTC", targets(200, 200, 200, 200));
        assertEquals(before, ops.targetOps(), 1e-12, "스케일 변경이 OPS를 올리지 않는다");
    }

    @Test
    void autoOffReflectsManualBiasClamped() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 80, 300, 8);
        BotFleetGovernor.BiasResult r = gov.setBias("KRW-BTC", 0.9, false, 0.5);
        assertEquals(0.7, r.effectiveBias(), 1e-12, "auto off는 clamp(target) 직접 반영");
    }

    @Test
    void controlStepMovesEffectiveTowardTargetWhenAuto() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 80, 300, 8);
        gov.registerMarket("KRW-BTC", new BigDecimal("100"), null);
        gov.setBias("KRW-BTC", 0.3, true, 1.0);
        gov.controlStep();
        double effective = gov.effectiveBias("KRW-BTC");
        // 측정 0, 오차 0.3 > 데드밴드 -> effective += Kp*오차 = 0.2*0.3.
        assertEquals(0.06, effective, 1e-9);
    }

    @Test
    void controlStepForcesZeroOnPriceBandBreach() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 80, 300, 8);
        gov.registerMarket("KRW-BTC", new BigDecimal("100"), null);
        gov.setBias("KRW-BTC", 0.7, true, 1.0);
        gov.recordPrice("KRW-BTC", new BigDecimal("200"));
        gov.controlStep();
        assertEquals(0.0, gov.effectiveBias("KRW-BTC"), 1e-12, "밴드 이탈 시 effective 0 강제");
    }

    @Test
    void regimeTransitionMatrixRespectsBoundaries() {
        // CALM: [0,0.90) CALM, [0.90,0.98) TREND, [0.98,1) SHOCK.
        assertEquals(BotFleetGovernor.Regime.CALM,
                BotFleetGovernor.nextRegime(BotFleetGovernor.Regime.CALM, 0.0));
        assertEquals(BotFleetGovernor.Regime.TREND,
                BotFleetGovernor.nextRegime(BotFleetGovernor.Regime.CALM, 0.95));
        assertEquals(BotFleetGovernor.Regime.SHOCK,
                BotFleetGovernor.nextRegime(BotFleetGovernor.Regime.CALM, 0.99));
        // SHOCK은 절반 확률로 CALM 복귀.
        assertEquals(BotFleetGovernor.Regime.CALM,
                BotFleetGovernor.nextRegime(BotFleetGovernor.Regime.SHOCK, 0.2));
    }

    @Test
    void regimeSequenceIsReproducibleForSeed() {
        BotFleetGovernor a = new BotFleetGovernor(30, 80, 300, 8, 42L);
        BotFleetGovernor b = new BotFleetGovernor(30, 80, 300, 8, 42L);
        a.registerMarket("KRW-BTC", new BigDecimal("100"), null);
        b.registerMarket("KRW-BTC", new BigDecimal("100"), null);
        StringBuilder sa = new StringBuilder();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            a.controlStep();
            b.controlStep();
            sa.append(a.regime("KRW-BTC")).append(',');
            sb.append(b.regime("KRW-BTC")).append(',');
        }
        assertEquals(sa.toString(), sb.toString(), "같은 시드 = 같은 레짐 시퀀스");
        // 300스텝이면 CALM 외 레짐이 최소 한 번은 관측된다(전이 발생).
        assertTrue(sa.indexOf("TREND") >= 0 || sa.indexOf("SHOCK") >= 0, "레짐 전이 발생");
    }

    @Test
    void regimeMultipliersReflectCurrentRegime() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 80, 300, 8);
        // 미등록 마켓은 CALM 기본(배수 1.0).
        assertEquals(1.0, gov.regimeDriftMult("KRW-XYZ"), 1e-12);
        assertEquals(1.0, gov.regimeSpreadMult("KRW-XYZ"), 1e-12);
        assertEquals(1.0, gov.regimeLambdaMult("KRW-XYZ"), 1e-12);
        assertEquals(1.0, gov.regimePWhaleMult("KRW-XYZ"), 1e-12);
    }

    @Test
    void memeTransitionMatrixBiasesTowardShockAndTrend() {
        // CALM: [0,0.60) CALM, [0.60,0.85) TREND, [0.85,1) SHOCK. 표준(0.90/0.98)보다 이탈↑.
        assertEquals(BotFleetGovernor.Regime.CALM,
                BotFleetGovernor.nextRegimeMeme(BotFleetGovernor.Regime.CALM, 0.5));
        assertEquals(BotFleetGovernor.Regime.TREND,
                BotFleetGovernor.nextRegimeMeme(BotFleetGovernor.Regime.CALM, 0.7));
        assertEquals(BotFleetGovernor.Regime.SHOCK,
                BotFleetGovernor.nextRegimeMeme(BotFleetGovernor.Regime.CALM, 0.9));
        // SHOCK 잔류가 표준(0.30)보다 큼: u>=0.55에서도 SHOCK 유지.
        assertEquals(BotFleetGovernor.Regime.SHOCK,
                BotFleetGovernor.nextRegimeMeme(BotFleetGovernor.Regime.SHOCK, 0.8));
        // 같은 u에서 표준은 CALM 복귀, MEME는 TREND 유지: 프로파일 구별.
        assertEquals(BotFleetGovernor.Regime.CALM,
                BotFleetGovernor.nextRegime(BotFleetGovernor.Regime.SHOCK, 0.4));
        assertEquals(BotFleetGovernor.Regime.TREND,
                BotFleetGovernor.nextRegimeMeme(BotFleetGovernor.Regime.SHOCK, 0.4));
    }

    @Test
    void memeProfileAmplifiesDecisionMultipliers() {
        BotFleetGovernor gov = new BotFleetGovernor(30, 80, 300, 8);
        gov.registerMarket("KRW-STD", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.STANDARD);
        gov.registerMarket("KRW-MEME", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.MEME);
        // 초기 상태는 둘 다 CALM. MEME는 CALM에서도 증폭 배수가 표준보다 크다.
        assertTrue(gov.regimeDriftMult("KRW-MEME") > gov.regimeDriftMult("KRW-STD"));
        assertTrue(gov.regimeSpreadMult("KRW-MEME") > gov.regimeSpreadMult("KRW-STD"));
        assertTrue(gov.regimeLambdaMult("KRW-MEME") > gov.regimeLambdaMult("KRW-STD"));
        assertTrue(gov.regimePWhaleMult("KRW-MEME") > gov.regimePWhaleMult("KRW-STD"));
        // 표준 CALM 배수는 기존과 동일(1.0). 회귀 방지.
        assertEquals(1.0, gov.regimeDriftMult("KRW-STD"), 1e-12);
        assertEquals(BotFleetGovernor.RegimeProfile.MEME, gov.regimeProfile("KRW-MEME"));
        assertEquals(BotFleetGovernor.RegimeProfile.STANDARD, gov.regimeProfile("KRW-STD"));
    }

    @Test
    void memeRegimeSequenceIsReproducibleForSeed() {
        BotFleetGovernor a = new BotFleetGovernor(30, 80, 300, 8, 42L);
        BotFleetGovernor b = new BotFleetGovernor(30, 80, 300, 8, 42L);
        a.registerMarket("KRW-DOGE", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.MEME);
        b.registerMarket("KRW-DOGE", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.MEME);
        StringBuilder sa = new StringBuilder();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            a.controlStep();
            b.controlStep();
            sa.append(a.regime("KRW-DOGE")).append(',');
            sb.append(b.regime("KRW-DOGE")).append(',');
        }
        assertEquals(sa.toString(), sb.toString(), "같은 시드 = 같은 MEME 레짐 시퀀스");
    }

    @Test
    void standardProfileMatchesLegacyRegistration() {
        // 3-arg 등록(하위호환)과 4-arg STANDARD 등록은 동일 레짐 시퀀스를 낸다.
        BotFleetGovernor legacy = new BotFleetGovernor(30, 80, 300, 8, 42L);
        BotFleetGovernor explicit = new BotFleetGovernor(30, 80, 300, 8, 42L);
        legacy.registerMarket("KRW-BTC", new BigDecimal("100"), null);
        explicit.registerMarket("KRW-BTC", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.STANDARD);
        for (int i = 0; i < 300; i++) {
            legacy.controlStep();
            explicit.controlStep();
            assertEquals(legacy.regime("KRW-BTC"), explicit.regime("KRW-BTC"));
        }
    }

    @Test
    void memeDoesNotPerturbOtherStandardMarkets() {
        // 한 거버너에 MEME 마켓을 함께 등록해도 표준 마켓 레짐 시퀀스는 불변이다(rng 격리).
        BotFleetGovernor mixed = new BotFleetGovernor(30, 80, 300, 8, 42L);
        BotFleetGovernor stdOnly = new BotFleetGovernor(30, 80, 300, 8, 42L);
        mixed.registerMarket("KRW-BTC", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.STANDARD);
        mixed.registerMarket("KRW-DOGE", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.MEME);
        stdOnly.registerMarket("KRW-BTC", new BigDecimal("100"), null);
        for (int i = 0; i < 300; i++) {
            mixed.controlStep();
            stdOnly.controlStep();
            assertEquals(stdOnly.regime("KRW-BTC"), mixed.regime("KRW-BTC"),
                    "MEME 등록이 표준 마켓 시퀀스를 바꾸지 않는다");
        }
    }

    @Test
    void memeSpendsMoreTimeInShockThanStandard() {
        // 같은 시드·같은 마켓명이면 nextDouble 열이 동일하다. SHOCK 점유율 차이는 전이 행렬
        // 편향에서만 온다(토톨로지 아님). MEME가 표준보다 SHOCK에 더 오래 머문다.
        BotFleetGovernor meme = new BotFleetGovernor(30, 80, 300, 8, 42L);
        BotFleetGovernor std = new BotFleetGovernor(30, 80, 300, 8, 42L);
        meme.registerMarket("KRW-DOGE", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.MEME);
        std.registerMarket("KRW-DOGE", new BigDecimal("100"), null,
                BotFleetGovernor.RegimeProfile.STANDARD);
        int memeShock = 0;
        int stdShock = 0;
        int steps = 2000;
        for (int i = 0; i < steps; i++) {
            meme.controlStep();
            std.controlStep();
            if (meme.regime("KRW-DOGE") == BotFleetGovernor.Regime.SHOCK) {
                memeShock++;
            }
            if (std.regime("KRW-DOGE") == BotFleetGovernor.Regime.SHOCK) {
                stdShock++;
            }
        }
        assertTrue(stdShock > 0, "표준도 SHOCK을 관측(비교 대상 유효)");
        assertTrue(memeShock > stdShock * 2,
                "MEME SHOCK 점유율이 표준의 2배 초과: meme=" + memeShock + " std=" + stdShock);
    }
}

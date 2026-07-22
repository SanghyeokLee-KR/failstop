package kr.exchange.sim;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 방향성 bias 결정성·순수성 검증(설계 §9 결정성·정밀도 항). p_buy·드리프트 오프셋이
 * (seed, bias)의 순수 함수임을 확인하고, 자동균형 제어의 수렴·데드밴드·하드캡·가격 밴드를
 * 순수 함수 수준에서 검증한다. 벽시계·랜덤 추가원 없이 도메인 B 재현성이 성립한다.
 */
class BotBiasDeterminismTest {

    @Test
    void buyProbabilityIsPureAndBounded() {
        assertEquals(0.5, TakerBot.buyProbability(0.0, 0.8), 1e-12);
        assertEquals(0.9, TakerBot.buyProbability(1.0, 0.8), 1e-12);
        assertEquals(0.1, TakerBot.buyProbability(-1.0, 0.8), 1e-12);
        // NOISE 프리셋(kDir 0)은 bias와 무관히 0.5.
        assertEquals(0.5, TakerBot.buyProbability(1.0, 0.0), 1e-12);
        assertEquals(0.5, TakerBot.buyProbability(-1.0, 0.0), 1e-12);
        // 정의역 밖 입력은 클램프된다.
        assertEquals(TakerBot.buyProbability(1.0, 0.8), TakerBot.buyProbability(2.0, 0.8), 1e-12);
        // bias 증가에 단조 증가.
        assertTrue(TakerBot.buyProbability(0.3, 0.8) > TakerBot.buyProbability(-0.3, 0.8));
    }

    @Test
    void takerDecisionStreamIsReproducibleForSeedAndBias() {
        List<Boolean> first = takerBuys(12345L, 0.3, 0.8, 500);
        List<Boolean> second = takerBuys(12345L, 0.3, 0.8, 500);
        assertEquals(first, second, "(seed, bias) 동일이면 매수 결정 열 동일");
        // bias가 다르면 열이 달라진다(방향 반영).
        List<Boolean> negative = takerBuys(12345L, -0.3, 0.8, 500);
        assertFalse(first.equals(negative), "bias 부호가 다르면 결정 열이 달라진다");
        // 양전 bias는 매수 비율이 절반을 넘는다.
        long buys = first.stream().filter(b -> b).count();
        assertTrue(buys > 250, "양전 bias 매수 우세: " + buys);
    }

    @Test
    void driftOffsetIsPureIntegerTicks() {
        assertEquals(0L, MarketMakerBot.driftOffset(0.0, 3, 0.5));
        assertEquals(2L, MarketMakerBot.driftOffset(1.0, 3, 0.5));
        assertEquals(-1L, MarketMakerBot.driftOffset(-1.0, 3, 0.5));
        // 클램프: 정의역 밖은 경계값과 동일.
        assertEquals(MarketMakerBot.driftOffset(1.0, 3, 0.5),
                MarketMakerBot.driftOffset(5.0, 3, 0.5));
    }

    @Test
    void effectiveBiasHardCap() {
        assertEquals(0.7, BotFleetGovernor.clampEffective(0.9), 1e-12);
        assertEquals(-0.7, BotFleetGovernor.clampEffective(-0.9), 1e-12);
        assertEquals(0.3, BotFleetGovernor.clampEffective(0.3), 1e-12);
    }

    @Test
    void deadbandSuppressesSmallErrors() {
        double effective = 0.2;
        // 오차 |0.25-0.2|=0.05 < 데드밴드 0.1: 조정 없음.
        double next = BotFleetGovernor.nextEffectiveBias(effective, 0.25, 0.2, 0.2, 0.1, false);
        assertEquals(effective, next, 1e-12);
    }

    @Test
    void proportionalControlConvergesWithoutFlapping() {
        double target = 0.3;
        double kp = 0.2;
        double deadband = 0.1;
        double effective = 0.0;
        // 플랜트 근사: 측정 = 직전 effective(봇이 방향을 그대로 반영).
        double previous = Double.NaN;
        for (int i = 0; i < 200; i++) {
            double measured = effective;
            double next = BotFleetGovernor.nextEffectiveBias(effective, target, measured, kp,
                    deadband, false);
            previous = effective;
            effective = next;
        }
        // 데드밴드 안으로 수렴하고 더 이상 진동하지 않는다.
        assertTrue(Math.abs(target - effective) <= deadband, "밴드 내 수렴: " + effective);
        assertEquals(previous, effective, 1e-12, "정상상태 플래핑 없음");
    }

    @Test
    void priceBandForcesEffectiveToZero() {
        BigDecimal reference = new BigDecimal("100");
        // 기준가 대비 +100%(밴드 40% 초과) -> 목표 무관 0.
        assertTrue(BotFleetGovernor.priceBandBreached(new BigDecimal("200"), reference, 0.40));
        double next = BotFleetGovernor.nextEffectiveBias(0.5, 0.7, 0.7, 0.2, 0.1, true);
        assertEquals(0.0, next, 1e-12);
        // 밴드 내(+30%)면 정상 제어.
        assertFalse(BotFleetGovernor.priceBandBreached(new BigDecimal("130"), reference, 0.40));
    }

    private static List<Boolean> takerBuys(long seed, double bias, double kDir, int n) {
        SplittableRandom rng = new SplittableRandom(seed);
        double p = TakerBot.buyProbability(bias, kDir);
        List<Boolean> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(rng.nextDouble() < p);
        }
        return out;
    }
}

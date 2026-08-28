package kr.exchange.sim;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시나리오 프리셋 계약 검증(API §6-A v2.31). Spring·DB 없이 도는 순수 검증이다.
 * 핵심은 멱등성이다. 프리셋이 활성화 시점 현재값을 기준으로 곱하면 재활성화가 누적돼
 * 제어면이 폭주기가 되므로, 등록 시점 기준선을 쓴다는 것을 반복 활성화로 못박는다.
 */
class SimScenarioTest {

    private static final String BTC = "KRW-BTC";
    private static final BigDecimal REF = new BigDecimal("50000000");

    private static BotFleetGovernor governor() {
        return new BotFleetGovernor(30, 80, 300, 30);
    }

    private static Map<BotType, Integer> targets(int mm, int taker, int trend, int noise) {
        EnumMap<BotType, Integer> t = new EnumMap<>(BotType.class);
        t.put(BotType.MARKET_MAKER, mm);
        t.put(BotType.TAKER, taker);
        t.put(BotType.TREND, trend);
        t.put(BotType.NOISE, noise);
        return t;
    }

    private static Map<BotType, Integer> appliedOf(List<BotFleetGovernor.ScenarioMarketResult> rs,
                                                   String market) {
        return rs.stream().filter(r -> r.market().equals(market)).findFirst().orElseThrow()
                .applied();
    }

    @Test
    void parseAcceptsCanonicalCaseAndUnderscore() {
        assertEquals(SimScenario.STEADY, SimScenario.parse("steady"));
        assertEquals(SimScenario.THUNDERING_HERD, SimScenario.parse("thundering-herd"));
        assertEquals(SimScenario.THUNDERING_HERD, SimScenario.parse("THUNDERING_HERD"));
        assertEquals(SimScenario.SPIKE, SimScenario.parse("  Spike "));
        assertThrows(IllegalArgumentException.class, () -> SimScenario.parse("meltdown"));
        assertThrows(IllegalArgumentException.class, () -> SimScenario.parse(null));
    }

    @Test
    void wireNamesAreFiveAndUnique() {
        assertEquals(5, SimScenario.values().length);
        long distinct = java.util.Arrays.stream(SimScenario.values())
                .map(SimScenario::wireName).distinct().count();
        assertEquals(5, distinct);
        for (SimScenario s : SimScenario.values()) {
            assertEquals(s, SimScenario.parse(s.wireName()));
        }
    }

    /** API §6-A v2.31 배수표 5x8 전체를 못 박는다. 표와 코드가 갈라지면 여기서 깨진다. */
    @Test
    void presetTableMatchesCanonicalContract() {
        assertPreset(SimScenario.STEADY, 1.0, 1.0, 1.0, 1.0, 1.0, 0.00, true, 0.5);
        assertPreset(SimScenario.VOLATILE, 1.5, 0.6, 1.3, 1.5, 1.2, 0.00, true, 0.15);
        assertPreset(SimScenario.RAMP, 2.0, 1.0, 1.4, 2.0, 1.0, 0.35, true, 0.3);
        assertPreset(SimScenario.SPIKE, 10.0, 1.0, 1.0, 1.0, 1.0, 0.60, false, 0.5);
        assertPreset(SimScenario.THUNDERING_HERD, 12.0, 1.0, 1.0, 1.0, 1.0, 0.70, false, 0.5);
    }

    private static void assertPreset(SimScenario s, double ops, double mm, double taker,
                                     double trend, double noise, double bias, boolean auto,
                                     double strength) {
        String n = s.wireName();
        assertEquals(ops, s.opsMult(), 1e-9, n + " OPS");
        assertEquals(mm, s.mult(BotType.MARKET_MAKER), 1e-9, n + " MARKET_MAKER");
        assertEquals(taker, s.mult(BotType.TAKER), 1e-9, n + " TAKER");
        assertEquals(trend, s.mult(BotType.TREND), 1e-9, n + " TREND");
        assertEquals(noise, s.mult(BotType.NOISE), 1e-9, n + " NOISE");
        assertEquals(bias, s.targetBias(), 1e-9, n + " target_net_bias");
        assertEquals(auto, s.autoBalance(), n + " auto_balance");
        assertEquals(strength, s.strength(), 1e-9, n + " strength");
    }

    /** ramp는 auto on에 목표 bias가 0이 아닌 유일한 프리셋이라 이 분기가 여기서만 덮인다. */
    @Test
    void rampBiasRidesTheBalanceLoop() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(10, 10, 4, 5));

        BotFleetGovernor.ScenarioMarketResult r = g.applyScenario(SimScenario.RAMP).get(0);
        assertEquals(targets(10, 14, 8, 5), r.applied());
        assertFalse(r.clamped());
        assertEquals(0.0, r.effectiveBias(), 1e-9, "auto on은 즉시 반영하지 않는다");

        for (int i = 0; i < 5; i++) {
            g.controlStep();
        }
        assertTrue(g.effectiveBias(BTC) > 0.0, "균형 루프가 목표 +0.35 방향으로 끌어올린다");
    }

    @Test
    void scaleIsBaselineTimesMultiplier() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(10, 10, 4, 5));

        Map<BotType, Integer> applied = appliedOf(g.applyScenario(SimScenario.VOLATILE), BTC);
        // volatile 배수 MM 0.6 / TAKER 1.3 / TREND 1.5 / NOISE 1.2
        assertEquals(6, applied.get(BotType.MARKET_MAKER));
        assertEquals(13, applied.get(BotType.TAKER));
        assertEquals(6, applied.get(BotType.TREND));
        assertEquals(6, applied.get(BotType.NOISE));
    }

    @Test
    void reactivationDoesNotCompound() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(10, 10, 4, 5));

        Map<BotType, Integer> once = appliedOf(g.applyScenario(SimScenario.VOLATILE), BTC);
        g.applyScenario(SimScenario.VOLATILE);
        Map<BotType, Integer> thrice = appliedOf(g.applyScenario(SimScenario.VOLATILE), BTC);
        assertEquals(once, thrice);
        assertEquals(6, thrice.get(BotType.MARKET_MAKER), "누적되면 0.6^3배로 줄어든다");
    }

    @Test
    void steadyRestoresBaseline() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(10, 10, 4, 5));

        g.applyScenario(SimScenario.VOLATILE);
        Map<BotType, Integer> back = appliedOf(g.applyScenario(SimScenario.STEADY), BTC);
        assertEquals(targets(10, 10, 4, 5), back);
    }

    @Test
    void unregisteredMarketKeepsScale() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(10, 10, 4, 5));
        // registerMarket을 거치지 않은 경로. setScale이 상태만 만들고 기준선은 잡히지 않는다.
        g.setScale("KRW-XRP", targets(0, 7, 0, 0));

        List<BotFleetGovernor.ScenarioMarketResult> rs = g.applyScenario(SimScenario.SPIKE);
        BotFleetGovernor.ScenarioMarketResult xrp = rs.stream()
                .filter(r -> r.market().equals("KRW-XRP")).findFirst().orElseThrow();
        assertFalse(xrp.scaleApplied());
        assertEquals(7, xrp.applied().get(BotType.TAKER));
        // 대수는 그대로여도 방향은 국면을 따른다
        assertEquals(0.60, xrp.effectiveBias(), 1e-9);

        assertTrue(rs.stream().filter(r -> r.market().equals(BTC)).findFirst().orElseThrow()
                .scaleApplied());
    }

    @Test
    void typeCapClampsAndReports() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(4, 15, 20, 4));

        // ramp TREND 2.0배 = 40, 유형 캡 30
        BotFleetGovernor.ScenarioMarketResult r = g.applyScenario(SimScenario.RAMP).get(0);
        assertTrue(r.clamped());
        assertEquals(30, r.applied().get(BotType.TREND));
    }

    @Test
    void autoOffAppliesTargetBiasImmediately() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(10, 10, 4, 5));

        assertEquals(0.60, g.applyScenario(SimScenario.SPIKE).get(0).effectiveBias(), 1e-9);
        assertEquals(0.70, g.applyScenario(SimScenario.THUNDERING_HERD).get(0).effectiveBias(),
                1e-9);
    }

    @Test
    void steadyHandsBiasToBalanceLoop() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(10, 10, 4, 5));
        g.applyScenario(SimScenario.SPIKE);

        // 대수는 즉시 기준선이지만 effective는 유지된다(auto on 계약 §4-5)
        BotFleetGovernor.ScenarioMarketResult steady = g.applyScenario(SimScenario.STEADY).get(0);
        assertEquals(0.60, steady.effectiveBias(), 1e-9);

        // 측정 쏠림이 보고돼야 오차가 데드밴드를 넘는다. 무거래 구간에서 measured가 0이면
        // 목표도 0이라 오차 0, 제어는 정지한다(플래핑 억제). 이것이 유지의 이유다.
        g.controlStep();
        assertEquals(0.60, g.effectiveBias(BTC), 1e-9);

        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 8; k++) {
                g.recordAggressor(BTC, true);
            }
            for (int k = 0; k < 2; k++) {
                g.recordAggressor(BTC, false);
            }
            g.controlStep();
        }
        assertTrue(g.effectiveBias(BTC) < 0.60);
    }

    /** 하네스 §2-1이 spike를 "동일 플릿", thundering-herd를 "전 봇"으로 규정한다. */
    @Test
    void loadScenariosKeepFleetIntact() {
        for (SimScenario s : List.of(SimScenario.SPIKE, SimScenario.THUNDERING_HERD)) {
            BotFleetGovernor g = governor();
            g.registerMarket(BTC, REF, targets(10, 10, 4, 5));
            assertEquals(targets(10, 10, 4, 5), appliedOf(g.applyScenario(s), BTC),
                    s.wireName() + "는 대수를 건드리지 않는다");
            assertTrue(s.opsMult() >= 10.0, s.wireName() + "의 크기는 전역 OPS 축이 준다");
        }
    }

    @Test
    void scenarioLeavesRegimeUntouched() {
        BotFleetGovernor g = governor();
        g.registerMarket(BTC, REF, targets(10, 10, 4, 5));
        BotFleetGovernor.Regime before = g.regime(BTC);

        for (SimScenario s : SimScenario.values()) {
            g.applyScenario(s);
            assertEquals(before, g.regime(BTC));
        }
    }

    @Test
    void opsIsBaseTimesMultiplier() {
        assertEquals(300.0, SimScenario.STEADY.scaledOps(300.0), 1e-9);
        // 하네스 §2-3 목표표 normal 300 -> spike 10x = 3,000 명목. 상한이지 달성치가 아니다
        assertEquals(3000.0, SimScenario.SPIKE.scaledOps(300.0), 1e-9);
        assertEquals(3600.0, SimScenario.THUNDERING_HERD.scaledOps(300.0), 1e-9);
    }

    @Test
    void zeroBaselineStaysZero() {
        for (SimScenario s : SimScenario.values()) {
            for (BotType t : BotType.values()) {
                assertEquals(0, s.scaled(t, 0));
            }
        }
    }
}

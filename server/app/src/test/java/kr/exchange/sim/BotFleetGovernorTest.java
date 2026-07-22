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
}

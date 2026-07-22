package kr.exchange.sim;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 방향 페르소나(r7)·반응 지터(r17)·사다리 가드 순수 함수 검증. momentum/mean-revert 스칼라가
 * 순수·유계·부호 정합이고, DirectionSource 매핑과 지터가 시드 재현적이며, 사다리가 비양수
 * 가격을 발주하지 않음을 확인한다. 벽시계·추가 난수원 없음.
 */
class BotDirectionDeterminismTest {

    @Test
    void momentumScalarFollowsTrendSignAndIsBounded() {
        // 상승(RISE=+1)이면 매수 방향(d>0), 하락이면 매도 방향(d<0).
        assertTrue(TakerBot.momentumScalar(1, new BigDecimal("0.03"), 8.0) > 0);
        assertTrue(TakerBot.momentumScalar(-1, new BigDecimal("0.03"), 8.0) < 0);
        // EVEN(0) 또는 rate 결측이면 중립.
        assertEquals(0.0, TakerBot.momentumScalar(0, new BigDecimal("0.03"), 8.0), 1e-12);
        assertEquals(0.0, TakerBot.momentumScalar(1, null, 8.0), 1e-12);
        // change_rate는 절대 크기라 부호는 change가 결정한다(음수 rate도 부호는 sign에서).
        assertEquals(TakerBot.momentumScalar(1, new BigDecimal("0.03"), 8.0),
                TakerBot.momentumScalar(1, new BigDecimal("-0.03"), 8.0), 1e-12);
        // [-1,1] 클램프: 큰 rate*gain도 1을 넘지 않는다.
        assertEquals(1.0, TakerBot.momentumScalar(1, new BigDecimal("0.5"), 8.0), 1e-12);
        assertEquals(-1.0, TakerBot.momentumScalar(-1, new BigDecimal("0.5"), 8.0), 1e-12);
    }

    @Test
    void meanRevertScalarFadesDeviationAndIsBounded() {
        // last>sma(고평가)면 페이드 매도(d<0), last<sma면 매수(d>0).
        assertTrue(TakerBot.meanRevertScalar(new BigDecimal("110"), new BigDecimal("100"), 20.0) < 0);
        assertTrue(TakerBot.meanRevertScalar(new BigDecimal("90"), new BigDecimal("100"), 20.0) > 0);
        // sma 비양수면 중립(0 나눗셈 방지).
        assertEquals(0.0, TakerBot.meanRevertScalar(new BigDecimal("100"), BigDecimal.ZERO, 20.0),
                1e-12);
        assertEquals(0.0, TakerBot.meanRevertScalar(null, new BigDecimal("100"), 20.0), 1e-12);
        // 클램프.
        assertEquals(-1.0, TakerBot.meanRevertScalar(new BigDecimal("200"), new BigDecimal("100"),
                20.0), 1e-12);
    }

    @Test
    void directionSourceMappingIsDeterministicPerType() {
        // TREND만 이원 분할: 짝수 MOMENTUM, 홀수 MEAN_REVERT.
        assertEquals(DirectionSource.MEAN_REVERT, DirectionSource.of(BotType.TREND, 1));
        assertEquals(DirectionSource.MOMENTUM, DirectionSource.of(BotType.TREND, 2));
        assertEquals(DirectionSource.MEAN_REVERT, DirectionSource.of(BotType.TREND, 3));
        // 나머지 유형은 STATIC(기존 프리셋 보존).
        assertEquals(DirectionSource.STATIC, DirectionSource.of(BotType.TAKER, 1));
        assertEquals(DirectionSource.STATIC, DirectionSource.of(BotType.NOISE, 2));
        assertEquals(DirectionSource.STATIC, DirectionSource.of(BotType.MARKET_MAKER, 1));
    }

    @Test
    void directionScalarDrivesReproducibleAndDirectionalBuys() {
        double bullish = TakerBot.momentumScalar(1, new BigDecimal("0.05"), 8.0);
        List<Boolean> a = buys(2024L, bullish, 1.0, 500);
        List<Boolean> b = buys(2024L, bullish, 1.0, 500);
        assertEquals(a, b, "(seed, d) 동일이면 매수 결정 열 동일");
        // 상승 방향 스칼라는 매수 우세.
        assertTrue(a.stream().filter(x -> x).count() > 250, "상승 페르소나 매수 우세");
        // 역추세 매도 방향은 열이 달라지고 매도 우세.
        double fade = TakerBot.meanRevertScalar(new BigDecimal("120"), new BigDecimal("100"), 20.0);
        List<Boolean> c = buys(2024L, fade, 1.0, 500);
        assertNotEquals(a, c, "방향 스칼라가 다르면 결정 열이 달라진다");
        assertTrue(c.stream().filter(x -> x).count() < 250, "역추세 페르소나 매도 우세");
    }

    @Test
    void reactionJitterIsBoundedReproducibleAndSeedVarying() {
        for (long seed = 1; seed <= 2000; seed++) {
            double f = BotReaction.reactionJitter(seed);
            assertTrue(f >= 0.6 && f <= 1.6, "지터 경계 [0.6, 1.6]: " + f);
            assertEquals(f, BotReaction.reactionJitter(seed), 1e-12, "순수·재현");
        }
        // 시드가 다르면 팩터가 갈린다(전 구간 상수 아님).
        assertNotEquals(BotReaction.reactionJitter(1L), BotReaction.reactionJitter(2L));
    }

    @Test
    void buildLadderExcludesNonPositiveBidTicks() {
        // fair 5, halfTicks 5, 3레벨: bid는 0,-1,-2로 전부 비양수라 제외되고 ask만 남는다.
        LinkedHashMap<Long, Long> ladder = MarketMakerBot.buildLadder(5, 5, 3, 1, 1.0);
        for (Long price : ladder.keySet()) {
            assertTrue(price > 0, "비양수 가격 틱 발주 금지: " + price);
        }
        assertEquals(3, ladder.size(), "ask 3레벨만 생존");
        assertTrue(ladder.containsKey(10L) && ladder.containsKey(11L) && ladder.containsKey(12L));
        // 정상 경로(높은 fair)에선 기존과 동일하게 양측 6레벨.
        assertEquals(6, MarketMakerBot.buildLadder(1000, 5, 3, 1, 1.0).size());
    }

    private static List<Boolean> buys(long seed, double d, double kDir, int n) {
        SplittableRandom rng = new SplittableRandom(seed);
        double p = TakerBot.buyProbability(d, kDir);
        List<Boolean> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(rng.nextDouble() < p);
        }
        return out;
    }

    @Test
    void momentumAndMeanRevertAreOppositePersonasUnderSameOverpricing() {
        // 상승·고평가 국면: 모멘텀은 매수(d>0), 역추세는 매도(d<0). 이원 페르소나 반대 방향 확인.
        double momentum = TakerBot.momentumScalar(1, new BigDecimal("0.04"), 8.0);
        double revert = TakerBot.meanRevertScalar(new BigDecimal("104"), new BigDecimal("100"), 20.0);
        assertTrue(momentum > 0 && revert < 0, "모멘텀·역추세 반대 부호");
        assertFalse(momentum == revert);
    }
}

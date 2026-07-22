package kr.exchange.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave B realism 순수 함수 결정성·경계 검증(설계 §2 r1·r3·r5·r6·r11·r12·r13).
 * 앵커·재고 스큐·스프레드·사다리·diff·크기분포·버스트·레짐 전이가 순수하고 시드 재현적이며
 * 정수 틱·정수 배수 경계를 지킴을 확인한다. 벽시계·추가 난수원 없음.
 */
class BotRealismDeterminismTest {

    @Test
    void anchorPullMovesTowardMarketAndIsPure() {
        // fair가 앵커보다 낮으면 양의 당김, 높으면 음의 당김.
        assertEquals(2L, MarketMakerBot.anchorPull(100, 110, 0.2));
        assertEquals(-2L, MarketMakerBot.anchorPull(110, 100, 0.2));
        // 동일 입력 동일 출력(순수).
        assertEquals(MarketMakerBot.anchorPull(100, 110, 0.2),
                MarketMakerBot.anchorPull(100, 110, 0.2));
        // 게인 1 미만이라 한 스텝에 앵커를 넘지 않는다(오버슈트 없음).
        long pulled = 100 + MarketMakerBot.anchorPull(100, 110, 0.2);
        assertTrue(pulled > 100 && pulled < 110, "앵커 방향으로 부분 이동: " + pulled);
    }

    @Test
    void inventorySkewOffloadsExcessBase() {
        // 초과 재고(imbalance>0)면 음의 스큐로 호가 중심을 낮춰 매도를 유도한다.
        assertTrue(MarketMakerBot.inventorySkewTicks(1.0, 0.5, 10) < 0);
        assertTrue(MarketMakerBot.inventorySkewTicks(-1.0, 0.5, 10) > 0);
        assertEquals(0L, MarketMakerBot.inventorySkewTicks(0.0, 0.5, 10));
        // 정의역 밖은 클램프: imbalance=2와 1이 동일.
        assertEquals(MarketMakerBot.inventorySkewTicks(1.0, 0.5, 10),
                MarketMakerBot.inventorySkewTicks(2.0, 0.5, 10));
        // scaleTicks로 경계: |skew| <= scaleTicks.
        assertEquals(-5L, MarketMakerBot.inventorySkewTicks(1.0, 0.5, 10));
    }

    @Test
    void spreadHalfTicksScalesWithRegimeAndFloorsAtOne() {
        assertEquals(5L, MarketMakerBot.spreadHalfTicks(10, 1.0));
        assertEquals(10L, MarketMakerBot.spreadHalfTicks(10, 2.0));
        assertEquals(6L, MarketMakerBot.spreadHalfTicks(10, 1.2));
        // spreadTicks 2 * 0.1 -> round(1*0.1)=0 이나 최소 1틱.
        assertEquals(1L, MarketMakerBot.spreadHalfTicks(2, 0.1));
    }

    @Test
    void buildLadderIsMonotonicOutwardAndDeterministic() {
        LinkedHashMap<Long, Long> ladder = MarketMakerBot.buildLadder(1000, 5, 3, 1, 1.0);
        // 각 side 3레벨 = 6개.
        assertEquals(6, ladder.size());
        // bid < 중심 < ask, 바깥 레벨일수록 배수 증가(1,2,3).
        assertEquals(1L, ladder.get(995L));
        assertEquals(1L, ladder.get(1005L));
        assertEquals(2L, ladder.get(994L));
        assertEquals(3L, ladder.get(993L));
        assertEquals(3L, ladder.get(1007L));
        for (Long price : ladder.keySet()) {
            assertTrue(price < 1000 || price > 1000, "중심가 레벨 없음: " + price);
        }
        assertEquals(ladder, MarketMakerBot.buildLadder(1000, 5, 3, 1, 1.0));
    }

    @Test
    void ladderDiffCancelsDepartedPlacesMissingKeepsCommon() {
        Set<Long> desired = Set.of(990L, 995L, 1005L, 1010L);
        Set<Long> live = Set.of(995L, 1005L, 1020L);
        MarketMakerBot.LadderDiff diff = MarketMakerBot.ladderDiff(desired, live);
        // live에만 있는 1020 취소.
        assertEquals(List.of(1020L), diff.toCancel());
        // desired에만 있는 990,1010 발주(정렬).
        assertEquals(List.of(990L, 1010L), diff.toPlace());
        // 공통 995,1005는 어느 쪽에도 없음(유지).
        assertFalse(diff.toCancel().contains(995L));
        assertFalse(diff.toPlace().contains(1005L));
    }

    @Test
    void sizeStepsIsReproducibleBoundedAndTailed() {
        List<Integer> a = sizeSeq(777L, 1000);
        List<Integer> b = sizeSeq(777L, 1000);
        assertEquals(a, b, "시드 동일이면 크기 열 동일");
        for (int s : a) {
            assertTrue(s >= 1 && s <= 40, "경계 [1, cap=40]: " + s);
        }
        // 고래(baseCap 초과)가 관측된다.
        assertTrue(a.stream().anyMatch(s -> s > 5), "고래 꼬리 존재");
        // 대부분은 정상 크기(baseCap 5 이하)라 평균이 상한에 붙지 않는다.
        double mean = a.stream().mapToInt(Integer::intValue).average().orElse(0);
        assertTrue(mean < 5.0, "우편향이나 평균은 낮게 유지: " + mean);
    }

    @Test
    void burstCountIsReproducibleAndBounded() {
        SplittableRandom r1 = new SplittableRandom(9L);
        SplittableRandom r2 = new SplittableRandom(9L);
        for (int i = 0; i < 500; i++) {
            int c = TakerBot.burstCount(r1, 0.3, 3);
            assertEquals(c, TakerBot.burstCount(r2, 0.3, 3));
        }
        // pBurst 0이면 항상 1.
        SplittableRandom z = new SplittableRandom(1L);
        for (int i = 0; i < 100; i++) {
            assertEquals(1, TakerBot.burstCount(z, 0.0, 3));
        }
        // pBurst 1이면 [2, 1+maxBurst] 범위(연속 스윕).
        SplittableRandom h = new SplittableRandom(1L);
        for (int i = 0; i < 100; i++) {
            int c = TakerBot.burstCount(h, 1.0, 3);
            assertTrue(c >= 2 && c <= 4, "버스트 경계: " + c);
        }
    }

    private static List<Integer> sizeSeq(long seed, int n) {
        SplittableRandom rng = new SplittableRandom(seed);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(TakerBot.sizeSteps(rng, 5, 1.2, 0.03, 6.0, 40));
        }
        return out;
    }
}

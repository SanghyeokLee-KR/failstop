package kr.exchange.sim;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 산출식 nextRunNonce의 단사성·재현성 고정(rank11). nonce는 (seed, ordinal)의 순수 함수라
 * DB 없이 검증한다. ordinal 공급측 성질은 RunOrdinalSourceIntegrationTest, 기동 배선은
 * SimFleetIntegrationTest가 맡는다.
 *
 * 옛 산출식을 대조군으로 재현해 재기동 재충돌을 명시적으로 보인다. 새 산출식이 통과하는 단언을
 * 옛 산출식도 통과하면 회귀를 못 잡으므로, 대조군 없이는 토톨로지가 된다.
 */
class BotFleetRunNonceTest {

    @Test
    void nonceIsDeterministicWithinRun() {
        // 같은 (seed, ordinal): run 수명 동안 coid 접두가 고정이라 재계산해도 동일해야 한다.
        assertEquals(BotFleet.nextRunNonce(42L, 7L), BotFleet.nextRunNonce(42L, 7L));
    }

    @Test
    void nonceDiffersAndIsUniqueWhenOrdinalAdvancesAcrossRuns() {
        // DB 시퀀스는 재기동을 넘어 단조. 연속 run ordinal이면 nonce가 매번 달라야 한다.
        String previous = null;
        Set<String> seen = new HashSet<>();
        for (long ordinal = 1; ordinal <= 2000; ordinal++) {
            String nonce = BotFleet.nextRunNonce(42L, ordinal);
            if (previous != null) {
                assertNotEquals(previous, nonce, "인접 run ordinal은 nonce가 갈린다");
            }
            assertTrue(seen.add(nonce), "ordinal 구간 전체에서 nonce 유일: " + ordinal);
            previous = nonce;
        }
    }

    @Test
    void restartWithResetInProcessCounterStillYieldsFreshNonce() {
        long seed = 42L;

        // 대조군(옛 방식): nonce가 인메모리 카운터에만 의존. 재기동으로 카운터가 1로 리셋되면
        // 접두가 재생성돼 직전 run coid와 재충돌한다. 그 충돌을 명시적으로 재현한다.
        LongFunction<String> oldNonce =
                counter -> Long.toUnsignedString(seed * 0x9E3779B97F4A7C15L + counter, 36);
        String oldRunAFirst = oldNonce.apply(1L);
        oldNonce.apply(2L);
        oldNonce.apply(3L);
        String oldAfterRestartFirst = oldNonce.apply(1L); // 재기동: 카운터 리셋
        assertEquals(oldRunAFirst, oldAfterRestartFirst, "옛 방식은 재기동에서 nonce가 재충돌한다");

        // 새 방식: ordinal이 DB 존속 시퀀스라 재기동을 넘어 전진(인메모리 리셋 무관).
        AtomicLong persistentSequence = new AtomicLong();
        String newRunAFirst = BotFleet.nextRunNonce(seed, persistentSequence.incrementAndGet());
        persistentSequence.incrementAndGet();
        persistentSequence.incrementAndGet();
        String newAfterRestartFirst =
                BotFleet.nextRunNonce(seed, persistentSequence.incrementAndGet());
        assertNotEquals(newRunAFirst, newAfterRestartFirst, "새 방식은 재기동에서 nonce가 갈린다");
    }

    @Test
    void nonceIsNonEmptyBase36() {
        String nonce = BotFleet.nextRunNonce(42L, 1L);
        assertFalse(nonce.isEmpty());
        assertTrue(nonce.matches("[0-9a-z]+"), "base36 문자만 사용");
    }
}

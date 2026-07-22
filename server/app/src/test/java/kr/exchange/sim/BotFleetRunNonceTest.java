package kr.exchange.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * run 단위 client_order_id nonce 검증(rank11). 재기동(기동 카운터 전진)마다 접두가 달라져
 * 직전 run의 coid와 겹치지 않음을 확인한다. 같은 run 안의 결정성은 nonce가 run당 고정이라
 * 구조적으로 보장된다.
 */
class BotFleetRunNonceTest {

    @Test
    void nonceDiffersEachRunForSameSeed() {
        String first = BotFleet.nextRunNonce(42L);
        String second = BotFleet.nextRunNonce(42L);
        assertNotEquals(first, second, "재기동마다 nonce가 달라 coid 접두가 겹치지 않는다");
    }

    @Test
    void nonceIsNonEmptyBase36() {
        String nonce = BotFleet.nextRunNonce(42L);
        assertFalse(nonce.isEmpty());
        assertTrue(nonce.matches("[0-9a-z]+"), "base36 문자만 사용");
    }
}

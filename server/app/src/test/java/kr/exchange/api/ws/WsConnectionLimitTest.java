package kr.exchange.api.ws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WS 동시 연결 상한의 유도 규칙을 고정한다. 근거는 실측이고 그 값이 바뀌면 이 테스트가 깨진다. */
class WsConnectionLimitTest {

    private static final long MB = 1024L * 1024L;

    /**
     * 실측 재현. 컨테이너 1GB·MaxRAMPercentage 75 노드의 최대 힙이 768MB 이고, 그 노드가 정상
     * 이었던 지점이 2,000 연결이다(증거 gateoff-20260903-2vcpu 31회차). 유도값이 그 지점을
     * 재현하지 못하면 상한의 근거가 사라진다.
     */
    @Test
    void derivesMeasuredHealthyPointFromHeap() {
        WsConnectionLimit limit = WsConnectionLimit.resolve(0, 768 * MB);
        assertEquals(1996, limit.limit(),
                "768MB 힙은 실측 정상 지점 2,000 연결 부근을 내야 한다");
        assertTrue(limit.limit() < 2702,
                "실측 사망 지점 2,702 아래여야 방어가 성립한다");
    }

    /** 힙이 커지면 상한도 오르되 정본 천장을 넘지 않는다. */
    @Test
    void derivedLimitFollowsHeapUpToCanonCeiling() {
        assertEquals(998, WsConnectionLimit.resolve(0, 384 * MB).limit());
        assertEquals(WsConnectionLimit.CANON_CEILING, WsConnectionLimit.resolve(0, 4096 * MB).limit(),
                "정본 천장 위로는 올라가지 않는다. nginx fd 예산이 그 값에 맞춰져 있다");
    }

    /** 초소형 힙에서도 데모가 성립해야 한다. */
    @Test
    void derivedLimitHasFloor() {
        assertEquals(WsConnectionLimit.FLOOR, WsConnectionLimit.resolve(0, 32 * MB).limit());
    }

    /** 명시 설정은 그대로 쓴다. 유도는 미설정일 때만이다. */
    @Test
    void explicitSettingWins() {
        assertEquals(120, WsConnectionLimit.resolve(120, 768 * MB).limit());
    }

    /**
     * 천장을 넘는 설정은 기동을 멈춘다. 조용히 받아들이면 정본과 코드가 어긋난 채 돌고, 그
     * 어긋남이 드러나는 순간이 노드가 죽는 순간이다.
     */
    @Test
    void explicitSettingCannotExceedCanonCeiling() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WsConnectionLimit.resolve(WsConnectionLimit.CANON_CEILING + 1, 768 * MB));
        assertTrue(e.getMessage().contains("정본"),
                "무엇을 먼저 고쳐야 하는지가 메시지에 있어야 한다");
    }

    @Test
    void rejectsNonPositiveHeap() {
        assertThrows(IllegalArgumentException.class, () -> WsConnectionLimit.resolve(0, 0));
    }
}

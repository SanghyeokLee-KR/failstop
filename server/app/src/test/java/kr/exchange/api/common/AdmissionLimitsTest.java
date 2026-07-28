package kr.exchange.api.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 상한 배분 계약(API 설계 §2-5-a). 합이 풀보다 작다는 성질이 이 클래스의 존재 이유다. */
class AdmissionLimitsTest {

    @Test
    void sharesFollowPoolAndLeaveReserve() {
        // 운영·카오스 풀 32 -> 쓰기 12·조회 12·예비 8.
        AdmissionLimits chaos = AdmissionLimits.resolve(32, 0, 0);
        assertEquals(12, chaos.write());
        assertEquals(12, chaos.query());
        assertEquals(8, chaos.reserved());

        // dev 기본 풀 10 -> 4·4·2.
        AdmissionLimits dev = AdmissionLimits.resolve(10, 0, 0);
        assertEquals(4, dev.write());
        assertEquals(4, dev.query());
        assertEquals(2, dev.reserved());
    }

    /**
     * 무대기 성질의 근거. 합이 풀 이하이면 수용된 요청이 커넥션을 기다릴 일이 없고(요청 하나가
     * 동시에 쥐는 커넥션은 최대 1개), DB에 블록된 스레드 수가 타임아웃이 아니라 설계로 유계가 된다.
     * 죽음의 직접 원인이 커넥션이 아니라 그것을 기다리는 스레드였으므로 이 성질이 핵심이다.
     */
    @Test
    void sumStaysBelowPoolOnRealisticPools() {
        for (int pool = 10; pool <= 256; pool++) {
            AdmissionLimits limits = AdmissionLimits.resolve(pool, 0, 0);
            assertTrue(limits.write() + limits.query() < pool,
                    "풀 " + pool + "에서 상한 합이 풀을 잠식한다: "
                            + limits.write() + "+" + limits.query());
            assertTrue(limits.reserved() > 0, "풀 " + pool + "에 예비분이 없다");
        }
    }

    @Test
    void floorProtectsTinyPools() {
        // 하한 4. 초소형 테스트 풀에서 상한이 0~1로 내려앉아 정상 경로까지 막는 것을 막는다.
        // 이 구간에서는 합이 풀을 넘고 무대기 성질이 성립하지 않는다(의도된 교환).
        AdmissionLimits tiny = AdmissionLimits.resolve(2, 0, 0);
        assertEquals(4, tiny.write());
        assertEquals(4, tiny.query());
        assertTrue(tiny.reserved() < 0);
    }

    @Test
    void explicitConfigurationWinsPerBucket() {
        AdmissionLimits limits = AdmissionLimits.resolve(32, 3, 0);
        assertEquals(3, limits.write(), "명시 설정이 유도값에 밀렸다");
        assertEquals(12, limits.query(), "미설정 버킷이 명시 설정에 오염됐다");
    }
}

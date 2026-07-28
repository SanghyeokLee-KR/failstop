package kr.exchange.api.common;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 수용 제한의 상한 유도·무대기 거절·허용량 보존을 컨테이너 없이 고정한다. */
class OrderAdmissionControlTest {

    @Test
    void limitFollowsRequestPoolShareAndStaysBelowIt() {
        // 운영·카오스 풀 32 -> 쓰기 24. 나머지 8이 조회 몫이라 status·readiness가 살아남는다.
        assertEquals(24, OrderAdmissionControl.resolveLimit(0, 32));
        // dev 기본 풀 10 -> 7.
        assertEquals(7, OrderAdmissionControl.resolveLimit(0, 10));
        // 하한 4. 초소형 테스트 풀에서 상한이 0·1로 내려앉아 정상 경로까지 막는 것을 막는다.
        assertEquals(4, OrderAdmissionControl.resolveLimit(0, 2));
        assertEquals(4, OrderAdmissionControl.resolveLimit(0, 0));
        // 명시 설정이 유도값을 이긴다.
        assertEquals(3, OrderAdmissionControl.resolveLimit(3, 32));
    }

    @Test
    void poolShareLeavesQueryHeadroomOnRealisticPools() {
        for (int pool = 6; pool <= 128; pool++) {
            int limit = OrderAdmissionControl.resolveLimit(0, pool);
            assertTrue(limit < pool, "풀 " + pool + "에서 상한 " + limit + "이 조회 여유를 남기지 않는다");
        }
    }

    @Test
    void rejectionCarriesContractShape() {
        OrderAdmissionControl admission =
                new OrderAdmissionControl(2, 32, new SimpleMeterRegistry());
        ApiException e = admission.rejection();
        assertEquals(429, e.status());
        assertEquals("too_many_requests", e.name());
        assertEquals(1, e.retryAfter());
        assertEquals("node", e.detail().get("bucket"));
        assertEquals("order", e.detail().get("scope"));
    }

    @Test
    void rejectsWithoutWaitingOnceExhausted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderAdmissionControl admission = new OrderAdmissionControl(2, 32, registry);
        assertTrue(admission.tryAcquire());
        assertTrue(admission.tryAcquire());

        long startNanos = System.nanoTime();
        assertFalse(admission.tryAcquire(), "상한 초과인데 permit이 나왔다");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // 대기열을 만들지 않는 것이 계약이다. 백오프가 아니라 즉시 거절이어야 한다.
        assertTrue(elapsedMs < 100, "초과 요청이 대기했다: " + elapsedMs + "ms");
        assertEquals(2, admission.inFlight());

        // 계량은 판정 지점에서 일어난다. 응답 렌더링이 실패해도 거절이 통계에서 사라지지 않는다.
        assertEquals(1.0, registry.get("exchange.admission.rejected").counter().count());
        assertEquals(2.0, registry.get("exchange.admission.in_flight").gauge().value());
        assertEquals(2.0, registry.get("exchange.admission.limit").gauge().value());

        admission.release();
        admission.release();
        assertEquals(0, admission.inFlight());
        assertTrue(admission.tryAcquire(), "반납 후 허용량이 회복되지 않았다");
        admission.release();
    }

    /**
     * 획득·반납이 요청 수명과 짝을 이루는지의 핵심은 예외 경로다. 여기서 permit이 새면 허용량이
     * 단조 감소해 결국 모든 주문이 429가 되고, 그것은 수용 제한이 아니라 서비스 정지다.
     */
    @Test
    void permitsSurviveRepeatedFailurePaths() throws Exception {
        OrderAdmissionControl admission =
                new OrderAdmissionControl(4, 32, new SimpleMeterRegistry());
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch done = new CountDownLatch(400);
            for (int i = 0; i < 400; i++) {
                pool.execute(() -> {
                    boolean admitted = admission.tryAcquire();
                    try {
                        if (!admitted) {
                            return;
                        }
                        throw new IllegalStateException("다운스트림 실패 모사");
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                    } finally {
                        if (admitted) {
                            admission.release();
                        }
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "부하가 끝나지 않았다");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(failed.get() > 0, "예외 경로가 한 번도 실행되지 않았다");
        assertEquals(0, admission.inFlight(), "예외 경로에서 permit이 샜다");
        for (int i = 0; i < 4; i++) {
            assertTrue(admission.tryAcquire(), "허용량이 전량 회복되지 않았다");
        }
        assertFalse(admission.tryAcquire());
    }
}

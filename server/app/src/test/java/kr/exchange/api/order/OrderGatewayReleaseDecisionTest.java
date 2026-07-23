package kr.exchange.api.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * finally 예약 회수 결정 회귀. 멱등 replay 접수는 자기 명령이 엔진·정산에 도달하지 않아 유령
 * assignedId로 어떤 orders row도 생기지 않으므로, defer하면 sweep의 주문 row 관측 회수 경로가
 * 죽어 예약이 TTL(30s)로만 회수되고 후속 주문을 insufficient_funds로 오거부한다. replay는 즉시
 * 해제여야 한다(권위 예약은 원본이 보유).
 */
class OrderGatewayReleaseDecisionTest {

    @Test
    void replayReleasesImmediatelyEvenWhenRowNotObserved() {
        // replay + 프로젝션 미관측(row=false) + 저널됨(true). defer면 유령 예약이 된다. 즉시 해제여야 한다.
        assertTrue(OrderGateway.releaseImmediately(false, true, true));
    }

    @Test
    void nonReplayWithUnobservedRowDefers() {
        // 정상 접수의 저널 이후 정산 미관측은 지연 회수(defer) 대상이다.
        assertFalse(OrderGateway.releaseImmediately(false, true, false));
    }

    @Test
    void observedRowReleasesImmediately() {
        assertTrue(OrderGateway.releaseImmediately(true, true, false));
    }

    @Test
    void unjournaledReleasesImmediately() {
        // 저널 미기록 확정(페일오버 창 등)은 예약을 잡을 이유가 없어 즉시 해제한다.
        assertTrue(OrderGateway.releaseImmediately(false, false, false));
    }
}

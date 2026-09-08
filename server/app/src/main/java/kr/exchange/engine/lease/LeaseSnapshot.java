package kr.exchange.engine.lease;

import java.time.Instant;

/**
 * 리스 한 행의 사본. 보관소가 무엇이든 같은 세 값으로 읽는다.
 *
 * @param holder     현재 보유자. 아무도 잡은 적이 없으면 빈 문자열
 * @param epoch      fencing 토큰. 승격마다 단조 증가한다
 * @param leaseUntil 이 시각 전까지 유효하다. 보관소의 시계 기준이다
 */
public record LeaseSnapshot(String holder, long epoch, Instant leaseUntil) {

    public LeaseSnapshot {
        if (holder == null || leaseUntil == null) {
            throw new IllegalArgumentException("holder·leaseUntil은 필수");
        }
    }

    /** 보관소 시계로 판정해야 한다. JVM 시계를 넣으면 스큐가 판정에 들어온다. */
    public boolean expiredAt(Instant storeNow) {
        return leaseUntil.isBefore(storeNow);
    }
}

package kr.exchange.admin.msp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 시험이 직접 움직이는 시계. 폴링 주기와 캐시 만료를 기다리지 않고 재현한다. */
final class TestClock extends Clock {

    private volatile Instant now;

    TestClock(Instant start) {
        this.now = start;
    }

    void advance(Duration step) {
        now = now.plus(step);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}

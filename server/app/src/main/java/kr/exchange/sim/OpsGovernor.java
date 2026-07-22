package kr.exchange.sim;

import java.util.function.BooleanSupplier;

/**
 * 플릿 전역 OPS 토큰버킷. 모든 봇의 주문·취소 호출이 이 단일 버킷을 지나므로
 * setTargetOps 런타임 변경이 즉시 전 봇에 반영된다(전역 OPS 제어 훅).
 */
public class OpsGovernor {

    private volatile double targetOps;
    private double tokens;
    private long lastRefillNanos;

    public OpsGovernor(double targetOps) {
        this.targetOps = targetOps;
        this.tokens = 0;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 런타임 변경 훅. 관리 평면(PUT /admin/v1/sim/rate) 연동 지점. */
    public void setTargetOps(double targetOps) {
        if (targetOps > 0) {
            this.targetOps = targetOps;
        }
    }

    public double targetOps() {
        return targetOps;
    }

    /** 토큰 확보까지 대기한다. running이 꺼지면 false로 반환해 봇 루프를 종료시킨다. */
    public boolean acquire(BooleanSupplier running) {
        while (running.getAsBoolean()) {
            if (tryAcquire()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double rate = targetOps;
        tokens = Math.min(rate, tokens + rate * (now - lastRefillNanos) / 1_000_000_000.0);
        lastRefillNanos = now;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}

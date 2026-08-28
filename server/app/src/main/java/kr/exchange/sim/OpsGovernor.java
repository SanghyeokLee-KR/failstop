package kr.exchange.sim;

import java.util.function.BooleanSupplier;

/**
 * 플릿 전역 OPS 토큰버킷. 모든 봇의 주문·취소 호출이 이 단일 버킷을 지나므로
 * setTargetOps 런타임 변경이 즉시 전 봇에 반영된다(전역 OPS 제어 훅).
 */
public class OpsGovernor {

    private final double baseOps;
    private volatile double targetOps;
    private volatile boolean paused;
    private double tokens;
    private long lastRefillNanos;

    public OpsGovernor(double targetOps) {
        this.baseOps = targetOps;
        this.targetOps = targetOps;
        this.tokens = 0;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * 기동 시점 목표 OPS. 시나리오 프리셋(§6-A v2.31)의 배수 기준선이다. 현재값을 기준으로
     * 삼으면 같은 프리셋 재활성화가 누적돼 배수가 거듭제곱으로 커진다.
     * 구성값이 비정상이면 현재값으로 물러선다(프리셋이 조용히 무효화되지 않게).
     */
    public double baseTargetOps() {
        return baseOps > 0 ? baseOps : targetOps;
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

    /**
     * 일시정지 게이트(POST /admin/v1/sim/pause). 발행 경로가 전부 이 버킷을 지나므로
     * 봇 스레드를 죽이지 않고 주문·취소 발행만 멈춘다. 해제는 start 또는 pause 재토글.
     */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused;
    }

    /**
     * 토큰 확보까지 대기한다. running이 꺼지면 false로 반환해 봇 루프를 종료시킨다.
     * 일시정지 중에는 토큰을 소비하지 않고 대기만 하므로 스레드가 유지된다.
     */
    public boolean acquire(BooleanSupplier running) {
        while (running.getAsBoolean()) {
            if (!paused && tryAcquire()) {
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
        // 버킷 용량을 refill rate에 묶으면 targetOps<1.0에서 tokens가 1.0에 못 미쳐 tryAcquire가
        // 영영 false, acquire가 무한 스핀한다. 용량 하한을 1.0으로 분리해 저율에서도 토큰이 축적되게 한다.
        double capacity = Math.max(1.0, rate);
        tokens = Math.min(capacity, tokens + rate * (now - lastRefillNanos) / 1_000_000_000.0);
        lastRefillNanos = now;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}

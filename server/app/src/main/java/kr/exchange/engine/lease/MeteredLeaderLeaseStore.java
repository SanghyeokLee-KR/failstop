package kr.exchange.engine.lease;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 보관소 감싸기. 계측만 하고 판정은 하지 않는다.
 *
 * 왜 필요한가. 리스를 PostgreSQL 밖으로 옮기면 `leader_lease` 행을 조회해 리스 상태를 보던
 * 경로가 그 보관소에서는 권위를 잃는다. 장애 실험이 "커밋이 멈춘 동안 리스 갱신이 계속됐는가"
 * 를 답하려면 갱신의 성공·거부·왕복 실패를 밖에서 초 단위로 셀 수 있어야 한다. 로그로는
 * 셀 수 없다. 갱신 성공은 로그를 남기지 않기 때문이고, 남기게 하면 초당 한 줄이 쌓인다.
 *
 * 판정을 여기 두지 않는 이유는 계측이 판정을 바꾸면 안 되기 때문이다. 모든 메서드가 위임하고
 * 반환값과 예외를 그대로 통과시킨다. 예외도 세되 삼키지 않는다.
 *
 * 지표는 모두 partition 태그(리스 자원 이름)를 단다(하네스 CI/CD §5-2). P1·P2 에서는 matching-engine 하나다.
 */
public final class MeteredLeaderLeaseStore implements LeaderLeaseStore {

    private final LeaderLeaseStore delegate;
    private final Clock clock;
    private final Counter acquireGranted;
    private final Counter acquireDenied;
    private final Counter acquireLockTimeouts;
    private final Counter renewOk;
    private final Counter renewRejected;
    private final Counter renewError;
    // 마지막 갱신 성공 시각. 0이면 아직 한 번도 성공하지 않았다.
    private final AtomicLong lastRenewMillis = new AtomicLong();
    // 마지막으로 관측한 fencing 토큰. 승격에서만 오른다.
    private final AtomicLong token = new AtomicLong();

    public MeteredLeaderLeaseStore(LeaderLeaseStore delegate, MeterRegistry registry, Clock clock) {
        this(delegate, registry, clock, LeaseResources.MATCHING_ENGINE);
    }

    public MeteredLeaderLeaseStore(LeaderLeaseStore delegate, MeterRegistry registry, Clock clock,
                                   String partition) {
        if (delegate == null || registry == null || clock == null || partition == null) {
            throw new IllegalArgumentException("delegate·registry·clock·partition은 필수");
        }
        this.delegate = delegate;
        this.clock = clock;
        this.acquireGranted = Counter.builder("exchange.leader.lease.acquire")
                .description("리스 승격 시도 결과")
                .tags("outcome", "granted", "partition", partition)
                .register(registry);
        this.acquireDenied = Counter.builder("exchange.leader.lease.acquire")
                .description("리스 승격 시도 결과")
                .tags("outcome", "denied", "partition", partition)
                .register(registry);
        this.acquireLockTimeouts = Counter.builder("exchange.leader.lease.acquire.lock_timeouts")
                .description("리스 획득 트랜잭션이 잠금 대기 상한을 넘겨 끝난 수. 옛 리더의 쓰기가 리스 행을 쥐고 있었다")
                .tag("partition", partition)
                .register(registry);
        this.renewOk = Counter.builder("exchange.leader.lease.renew")
                .description("리스 갱신 결과. rejected는 보관소가 거부한 것이고 error는 왕복 실패다")
                .tags("outcome", "ok", "partition", partition)
                .register(registry);
        this.renewRejected = Counter.builder("exchange.leader.lease.renew")
                .description("리스 갱신 결과. rejected는 보관소가 거부한 것이고 error는 왕복 실패다")
                .tags("outcome", "rejected", "partition", partition)
                .register(registry);
        this.renewError = Counter.builder("exchange.leader.lease.renew")
                .description("리스 갱신 결과. rejected는 보관소가 거부한 것이고 error는 왕복 실패다")
                .tags("outcome", "error", "partition", partition)
                .register(registry);
        Gauge.builder("exchange.leader.lease.last_renew_age_seconds", this,
                        MeteredLeaderLeaseStore::lastRenewAgeSeconds)
                .description("마지막 갱신 성공 이후 경과. 갱신이 멈추면 이 값만 오른다")
                .tag("partition", partition)
                .register(registry);
        Gauge.builder("exchange.leader.lease.fencing_token", token, AtomicLong::doubleValue)
                .description("이 노드가 마지막으로 얻은 fencing 토큰. 승격에서만 오른다")
                .tag("partition", partition)
                .register(registry);
    }

    /** 아직 한 번도 성공하지 않았으면 -1이다. 0으로 두면 '방금 갱신했다'와 구분되지 않는다. */
    private double lastRenewAgeSeconds() {
        long last = lastRenewMillis.get();
        return last == 0 ? -1 : (clock.millis() - last) / 1000.0;
    }

    @Override
    public Optional<Long> acquire(String holder, Duration ttl) {
        Optional<Long> acquired;
        try {
            acquired = delegate.acquire(holder, ttl);
        } catch (AcquireLockTimeoutException e) {
            acquireLockTimeouts.increment();
            throw e;
        }
        if (acquired.isPresent()) {
            acquireGranted.increment();
            token.set(acquired.get());
            lastRenewMillis.set(clock.millis());
        } else {
            acquireDenied.increment();
        }
        return acquired;
    }

    @Override
    public boolean renew(String holder, long epoch, Duration ttl) {
        boolean renewed;
        try {
            renewed = delegate.renew(holder, epoch, ttl);
        } catch (RuntimeException e) {
            // 왕복 실패는 거부와 다르다. 거부는 '리더가 아니다' 이고 왕복 실패는 '모른다' 다.
            // 두 축을 합치면 실험에서 "리스를 잃었는가" 와 "보관소가 안 보이는가" 를 못 가른다.
            renewError.increment();
            throw e;
        }
        if (renewed) {
            renewOk.increment();
            lastRenewMillis.set(clock.millis());
        } else {
            renewRejected.increment();
        }
        return renewed;
    }

    @Override
    public boolean holdsUnexpired(String holder, long epoch) {
        return delegate.holdsUnexpired(holder, epoch);
    }

    @Override
    public void release(String holder, long epoch) {
        delegate.release(holder, epoch);
    }

    @Override
    public Optional<LeaseSnapshot> current() {
        return delegate.current();
    }

    @Override
    public double priorDowntimeSeconds(Duration ttl) {
        return delegate.priorDowntimeSeconds(ttl);
    }

    @Override
    public java.time.Instant now() {
        return delegate.now();
    }

    @Override
    public String describe() {
        return delegate.describe() + " + 계측";
    }
}

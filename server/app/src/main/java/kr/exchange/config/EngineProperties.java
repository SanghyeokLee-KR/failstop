package kr.exchange.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * 엔진 배선 설정. TTL 10초와 하트비트 주기는 DB 설계 §5.4 예시 계약을 기본값으로 둔다.
 *
 * @param leaseTtl            리스 유효 기간
 * @param leaseTickInterval   하트비트/재승격 시도 주기. TTL보다 짧아야 한다
 * @param leaseStanddownAfter 서비스 불능이 끊김 없이 지속될 때 리스 갱신을 포기하는 임계(§5.4).
 *                            TTL보다 충분히 길어야 정상 갱신 주기와 겹치지 않는다
 * @param queueCapacity       단일 라이터 유계 큐 크기. 초과분은 백프레셔 거부
 * @param followerCatchUpInterval 팔로워가 리더 저널을 따라잡는 주기
 * @param acceptLockWait      마켓 접수 락 대기 상한. 넘으면 저널 전에 429(bucket market)로 돌려보낸다. 0(코드 기본)이면
 *                            상한이 없다. dev·prod 문서는 2000ms 다(API 설계 §2-5-b)
 * @param acceptLockTimeout   접수 append 트랜잭션의 DB lock_timeout. 0(코드 기본)이면 걸지 않고 append 풀 세션 값을 따른다.
 *                            dev·prod 문서는 1000ms 다
 * @param leaseAcquireLockTimeout 리스 획득 트랜잭션의 lock_timeout(DB 설계 §5.4-c 3). 0보다 크고 리스 틱 주기보다 작아야 한다
 */
@ConfigurationProperties(prefix = "exchange.engine")
public record EngineProperties(Duration leaseTtl, Duration leaseTickInterval,
                               Duration leaseStanddownAfter, int queueCapacity,
                               Duration followerCatchUpInterval,
                               Duration acceptLockWait, Duration acceptLockTimeout,
                               Duration leaseAcquireLockTimeout) {

    /** 획득 잠금 대기 상한의 코드 기본값. 옛 리더의 쓰기가 리스 행을 쥔 동안 틱 스레드를 한 틱보다 짧게 묶는다. */
    public static final Duration DEFAULT_LEASE_ACQUIRE_LOCK_TIMEOUT = Duration.ofMillis(1000);

    public EngineProperties(Duration leaseTtl, Duration leaseTickInterval, Duration leaseStanddownAfter,
                            int queueCapacity, Duration followerCatchUpInterval,
                            Duration acceptLockWait, Duration acceptLockTimeout) {
        this(leaseTtl, leaseTickInterval, leaseStanddownAfter, queueCapacity, followerCatchUpInterval,
                acceptLockWait, acceptLockTimeout, null);
    }

    // 생성자가 둘이라 바인딩할 생성자를 지정한다.
    @ConstructorBinding
    public EngineProperties {
        if (leaseTtl == null) {
            leaseTtl = Duration.ofSeconds(10);
        }
        if (leaseTickInterval == null) {
            leaseTickInterval = Duration.ofSeconds(3);
        }
        if (leaseStanddownAfter == null) {
            leaseStanddownAfter = Duration.ofSeconds(45);
        }
        if (queueCapacity <= 0) {
            queueCapacity = 65536;
        }
        // 팔로워가 리더 저널을 따라잡는 주기. 리스 축이 아니라 저널 추적 축이다. 1초는 부하
        // 100 OPS 기준 한 틱에 100 명령 남짓이라 틱이 짧게 끝난다. 늘리면 격차가 커지고
        // 줄이면 조회 왕복만 늘어난다.
        if (followerCatchUpInterval == null) {
            followerCatchUpInterval = Duration.ofSeconds(1);
        }
        // 코드 기본은 둘 다 꺼 둔다. 시험과 다른 배선을 켜지 않으려는 것이고, dev·prod 문서가 켠다(S4 설계 4-12).
        // 초과는 429(확정 미접수)나 409 order_in_progress 다. 503 으로 내면 max_fails=1 인 nginx 쓰기 업스트림이 그 하나로
        // 리더를 빼 모든 마켓의 쓰기가 502 가 된다(API 설계 §2-5-b).
        if (acceptLockWait == null) {
            acceptLockWait = Duration.ZERO;
        }
        if (acceptLockTimeout == null) {
            acceptLockTimeout = Duration.ZERO;
        }
        if (leaseAcquireLockTimeout == null) {
            leaseAcquireLockTimeout = DEFAULT_LEASE_ACQUIRE_LOCK_TIMEOUT;
        }
        // 0 이면 PostgreSQL 에서 상한 없음이다. 틱 이상이면 막힌 획득이 틱 스레드를 한 틱 넘게 묶어 같은 풀의 갱신이
        // 기다린다(DB 설계 §5.4-c 3).
        if (leaseAcquireLockTimeout.isZero() || leaseAcquireLockTimeout.isNegative()
                || leaseAcquireLockTimeout.compareTo(leaseTickInterval) >= 0) {
            throw new IllegalArgumentException("leaseAcquireLockTimeout must be positive and shorter than "
                    + "leaseTickInterval: timeout=" + leaseAcquireLockTimeout + " tick=" + leaseTickInterval);
        }
        if (acceptLockWait.isNegative() || acceptLockTimeout.isNegative()) {
            throw new IllegalArgumentException("acceptLockWait·acceptLockTimeout은 음수일 수 없다: wait="
                    + acceptLockWait + " timeout=" + acceptLockTimeout);
        }
        // tick이 TTL 이상이면 갱신 전 리스가 만료돼 정당 단독 리더가 스스로 펜싱된다(half-leader).
        if (leaseTickInterval.compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException(
                    "leaseTickInterval must be shorter than leaseTtl: tick=" + leaseTickInterval
                            + " ttl=" + leaseTtl);
        }
        // 자발적 강등이 TTL보다 빨리 걸리면 일시 포화가 곧바로 페일오버가 된다(헛페일오버 회귀).
        // 리스 한 주기분 이상은 관찰한 뒤에만 강등할 수 있게 하한을 건다.
        if (leaseStanddownAfter.compareTo(leaseTtl) <= 0) {
            throw new IllegalArgumentException(
                    "leaseStanddownAfter must be longer than leaseTtl: standdown="
                            + leaseStanddownAfter + " ttl=" + leaseTtl);
        }
    }
}

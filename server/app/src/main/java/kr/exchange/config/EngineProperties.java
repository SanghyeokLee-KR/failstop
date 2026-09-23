package kr.exchange.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * @param acceptLockWait      마켓 접수 락 대기 상한. 넘으면 저널 전에 503으로 돌려보낸다. 0(기본)이면 상한이 없다
 * @param acceptLockTimeout   접수 append 트랜잭션의 DB lock_timeout. 0(기본)이면 걸지 않고 append 풀 세션 값을 따른다
 */
@ConfigurationProperties(prefix = "exchange.engine")
public record EngineProperties(Duration leaseTtl, Duration leaseTickInterval,
                               Duration leaseStanddownAfter, int queueCapacity,
                               Duration followerCatchUpInterval,
                               Duration acceptLockWait, Duration acceptLockTimeout) {

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
        // 둘 다 기본은 꺼 둔다(ADR 0034 S1). 단일 리더 모드의 접수를 오늘과 같게 두기 위해서다.
        //
        // 락 대기 상한을 켜면 한 마켓의 줄이 상한을 넘을 때 그 요청이 503 을 받는다. max_fails=1 인 nginx 쓰기
        // 업스트림(harness/nginx, deploy/nginx-edge)은 그 503 하나로 노드를 2초 빼고 POST 를 다른 노드로 넘긴다.
        // 곧 한 마켓의 정체가 모든 마켓의 쓰기 중단으로 번진다. 파티션 모드처럼 줄 머리 막힘을 끊어야 할 때
        // 인그레스 설정과 함께 판단해 켠다(ADR 0034 결정 11, S3·S4).
        if (acceptLockWait == null) {
            acceptLockWait = Duration.ZERO;
        }
        // 운영 append 풀 세션에는 lock_timeout 이 없다. 기본값을 그와 같게 두어 켜지 않으면 접수 왕복이 늘지 않는다.
        // 켜도 초과(55P03)는 아직 게이트웨이에서 500 이다. 503 매핑은 위와 같은 이유로 S3·S4 에서 정한다.
        if (acceptLockTimeout == null) {
            acceptLockTimeout = Duration.ZERO;
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

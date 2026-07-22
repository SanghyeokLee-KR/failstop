package kr.exchange.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 엔진 배선 설정. TTL 10초와 하트비트 주기는 DB 설계 §5.4 예시 계약을 기본값으로 둔다.
 *
 * @param leaseTtl          리스 유효 기간
 * @param leaseTickInterval 하트비트/재승격 시도 주기. TTL보다 짧아야 한다
 * @param queueCapacity     단일 라이터 유계 큐 크기. 초과분은 백프레셔 거부
 */
@ConfigurationProperties(prefix = "exchange.engine")
public record EngineProperties(Duration leaseTtl, Duration leaseTickInterval, int queueCapacity) {

    public EngineProperties {
        if (leaseTtl == null) {
            leaseTtl = Duration.ofSeconds(10);
        }
        if (leaseTickInterval == null) {
            leaseTickInterval = Duration.ofSeconds(3);
        }
        if (queueCapacity <= 0) {
            queueCapacity = 65536;
        }
        // tick이 TTL 이상이면 갱신 전 리스가 만료돼 정당 단독 리더가 스스로 펜싱된다(half-leader).
        if (leaseTickInterval.compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException(
                    "leaseTickInterval must be shorter than leaseTtl: tick=" + leaseTickInterval
                            + " ttl=" + leaseTtl);
        }
    }
}

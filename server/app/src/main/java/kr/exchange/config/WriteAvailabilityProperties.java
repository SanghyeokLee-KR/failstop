package kr.exchange.config;

import kr.exchange.engine.WriteAvailability;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 쓰기 가능 탐지 설정(DB 설계 §5.4-d 4). 기본은 꺼짐이고 Patroni 엄격 동기 구성(patroni 프로필, AWS Patroni 배포)에서 켠다. 임계는
 * 로컬 기본값이고 E-P2 에서 다시 정한다(Patroni 설계 4-3).
 *
 * @param enabled            탐지와 쓰기 게이트를 켠다
 * @param probeInterval      탐지 주기. 앞 탐지가 끝나지 않았으면 보내지 않는다
 * @param suspendAfter       탐지가 이보다 오래 끝나지 않으면 탐지 백엔드의 대기 사건을 읽는다
 * @param confirmInterval    대기 사건 두 번 읽기의 간격
 * @param resumeProbeMax     재개로 세는 탐지 한 번의 상한
 * @param resumeProbes       재개에 필요한 연속 탐지 수
 * @param requeryGrace       재개 뒤 by-client 재조회의 거짓 404 를 막는 기간(API 설계 §2-5-d)
 * @param probeSocketTimeout 탐지 연결의 소켓 제한. 조용한 분할의 안전망이다
 * @param checkSocketTimeout 확인 연결의 소켓 제한. 읽기만 한다
 * @param connectTimeout     두 연결의 연결 제한
 */
@ConfigurationProperties(prefix = "exchange.write-availability")
public record WriteAvailabilityProperties(boolean enabled, Duration probeInterval, Duration suspendAfter,
                                          Duration confirmInterval, Duration resumeProbeMax, int resumeProbes,
                                          Duration requeryGrace, Duration probeSocketTimeout,
                                          Duration checkSocketTimeout, Duration connectTimeout) {

    public WriteAvailabilityProperties {
        WriteAvailability.Settings defaults = WriteAvailability.Settings.DEFAULTS;
        if (probeInterval == null) {
            probeInterval = Duration.ofSeconds(1);
        }
        if (suspendAfter == null) {
            suspendAfter = defaults.suspendAfter();
        }
        if (confirmInterval == null) {
            confirmInterval = Duration.ofMillis(500);
        }
        if (resumeProbeMax == null) {
            resumeProbeMax = defaults.resumeProbeMax();
        }
        if (resumeProbes <= 0) {
            resumeProbes = defaults.resumeProbes();
        }
        if (requeryGrace == null) {
            requeryGrace = defaults.requeryGrace();
        }
        if (probeSocketTimeout == null) {
            probeSocketTimeout = Duration.ofSeconds(60);
        }
        if (checkSocketTimeout == null) {
            checkSocketTimeout = Duration.ofSeconds(2);
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
    }

    public WriteAvailability.Settings settings() {
        return new WriteAvailability.Settings(suspendAfter, resumeProbeMax, resumeProbes, requeryGrace);
    }
}

package kr.exchange.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 주기 스냅샷 설정(복구 RTO 설계 §3-b). 스냅샷은 tail 재생량을 상한해 RTO를 저널 크기와
 * 탈동조시키는 최적화다. 정확성 원천이 아니므로 비활성(enabled=false)이어도 저널 전량 재생으로
 * 동일 결과에 도달한다.
 *
 * commandInterval·timeInterval은 캡처 주기(둘 중 먼저 도달)다. 초기값은 §3-b 계량 근거
 * (27k 인메모리 rebuild ≈ 수 초)에서 워스트 tail이 예산 여유 안에 들도록 잡은 값이며,
 * 정확 수치는 캘리브레이션(M4 §3-3(d))에서 확정한다.
 *
 * engineVersion·configHash는 채택 게이트 입력이다. 기록 시점 값을 스냅샷에 박제하고 로드
 * 시점의 현재 값과 비교해, 매칭 의미론·결정론 설정이 바뀐 구식 스냅샷을 폐기·폴백한다.
 *
 * @param enabled         스냅샷 캡처 활성 여부
 * @param commandInterval 캡처 사이 최소 명령 수(N_cap)
 * @param timeInterval    캡처 사이 최대 시간(T_cap)
 * @param queueCapacity   라이터→워커 유계 큐 크기. 초과분은 드롭(best-effort)
 * @param engineVersion   매칭 엔진 의미론 버전. 로드 시 현재 값과 불일치면 스냅샷 폐기
 * @param configHash      결정론 영향 설정 해시. 로드 시 현재 값과 불일치면 스냅샷 폐기
 */
@ConfigurationProperties(prefix = "exchange.engine.snapshot")
public record SnapshotProperties(
        Boolean enabled,
        long commandInterval,
        Duration timeInterval,
        int queueCapacity,
        String engineVersion,
        String configHash) {

    public SnapshotProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (commandInterval <= 0) {
            commandInterval = 100_000;
        }
        if (timeInterval == null || timeInterval.isZero() || timeInterval.isNegative()) {
            timeInterval = Duration.ofSeconds(60);
        }
        if (queueCapacity <= 0) {
            queueCapacity = 8;
        }
        if (engineVersion == null || engineVersion.isBlank()) {
            engineVersion = "engine-m1";
        }
        if (configHash == null || configHash.isBlank()) {
            configHash = "default";
        }
    }
}

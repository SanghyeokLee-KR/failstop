package kr.exchange.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 상시 불변식 스캔. 앱 스케줄러 기반(pg_cron 미의존). 다중 노드에서 전 노드가 돌아도
 * invariant_checks append는 멱등적 관측 로그라 무해하다(리더십과 무관). 부하가 문제되면
 * 리더 노드 한정 실행으로 좁힐 수 있으나 1차 범위에서는 단순 상시 실행을 택한다.
 */
public class InvariantScheduler {

    private static final Logger log = LoggerFactory.getLogger(InvariantScheduler.class);

    private final InvariantVerifier verifier;

    public InvariantScheduler(InvariantVerifier verifier) {
        this.verifier = verifier;
    }

    @Scheduled(
            fixedDelayString = "${exchange.harness.verifier.interval-ms:300000}",
            initialDelayString = "${exchange.harness.verifier.initial-delay-ms:30000}")
    public void tick() {
        try {
            verifier.runScheduled();
        } catch (RuntimeException e) {
            // 검증기 자체 실패가 앱을 멈추면 안 된다. 기록만 하고 다음 틱에 재시도.
            log.warn("불변식 상시 스캔 실패: {}", e.getMessage());
        }
    }
}

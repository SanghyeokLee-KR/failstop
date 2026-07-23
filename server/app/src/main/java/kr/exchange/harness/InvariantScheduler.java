package kr.exchange.harness;

import kr.exchange.engine.LeaderLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 상시 불변식 스캔. 앱 스케줄러 기반(pg_cron 미의존).
 *
 * 리더 노드에서만 스캔한다(§4-2 단일 실행 규율). 전 노드 실행은 관측 정합에는 무해하나,
 * 다중 노드 카오스 스택에서 각 노드가 같은 전량 집계(ledger_entries SUM 등)를 5분마다 중복
 * 실행해 측정 대상 DB를 잠식하고 SLO를 왜곡한다. 리스 소유자만 돌려 실행을 단일화한다.
 */
public class InvariantScheduler {

    private static final Logger log = LoggerFactory.getLogger(InvariantScheduler.class);

    private final InvariantVerifier verifier;
    private final LeaderLeaseService lease;

    public InvariantScheduler(InvariantVerifier verifier, LeaderLeaseService lease) {
        this.verifier = verifier;
        this.lease = lease;
    }

    @Scheduled(
            fixedDelayString = "${exchange.harness.verifier.interval-ms:300000}",
            initialDelayString = "${exchange.harness.verifier.initial-delay-ms:30000}")
    public void tick() {
        if (!lease.isLeader()) {
            // 비리더 노드는 스캔하지 않는다. 리스 소유자 1개 노드만 전량 집계를 돌려 중복을 없앤다.
            return;
        }
        try {
            verifier.runScheduled();
        } catch (RuntimeException e) {
            // 검증기 자체 실패가 앱을 멈추면 안 된다. 기록만 하고 다음 틱에 재시도.
            log.warn("불변식 상시 스캔 실패: {}", e.getMessage());
        }
    }
}

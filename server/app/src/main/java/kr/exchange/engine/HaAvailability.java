package kr.exchange.engine;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HA 관측 게이지 + 접수 가용성 배선(감사 관측성·복구, 하네스 CICD §5-2, 복구 RTO §3-a).
 *
 * 게이지(라이터 스레드 밖, 리스 스케줄러 스레드에서 갱신):
 *   exchange_leader_epoch{node}         승격 시 epoch 계단(§5-2)
 *   exchange_leader_is_leader{node}     리더 소유권 밴드·absent 알람(§5-2)
 *   exchange_failover_downtime_seconds  RTO 게이지 T2-T1(§5-1, FailoverRtoExceeded 원천)
 *
 * readiness: 승격 상태 전이(AvailabilitySignal)를 AvailabilityChangeEvent로 승격시켜
 * /actuator/health/readiness를 ACCEPTING_TRAFFIC·REFUSING_TRAFFIC로 전이. LB가 이 프로브로
 * 팔로워·승격 대기 노드를 제외한다. AcceptGate의 내부 503은 정합 방벽으로 그대로 둔다(이중화).
 * 서비스 불능 지속으로 리스 갱신을 포기한 노드(DB 설계 §5.4 자발적 강등)도 리스 상실 콜백을 타
 * 같은 REFUSING_TRAFFIC 전이를 밟는다. 그 노드가 계속 UP으로 보이면 LB가 승격한 신 리더로
 * 넘어가지 못해, 승격만 되고 체결은 재개되지 않는다. 강등 사유별로 readiness 의미를 나누지
 * 않는 이유다.
 *
 * ApplicationReadyEvent에서 Spring이 자동 발행하는 ACCEPTING_TRAFFIC를, 팔로워로 기동한
 * 노드가 그대로 UP으로 오보하지 않게 마지막 desired 상태로 되돌린다. 그래서 Ordered 최하위로
 * Spring 기본 리스너 뒤에 실행한다.
 */
public class HaAvailability implements AvailabilitySignal,
        ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(HaAvailability.class);

    private final ApplicationEventPublisher publisher;

    private final AtomicLong leaderEpoch = new AtomicLong(0);
    private final AtomicInteger isLeader = new AtomicInteger(0);
    // 초 단위 소수를 담기 위해 밀리초 정수로 보관하고 게이지에서 나눈다.
    private final AtomicLong failoverDowntimeMillis = new AtomicLong(0);

    // 접수 개방 여부 목표치. 팔로워 기동을 기본으로 둔다(리더 확정 전 트래픽 차단).
    private volatile ReadinessState desired = ReadinessState.REFUSING_TRAFFIC;
    // 마지막으로 발행한 상태. 동일 상태 재발행을 억제한다.
    private ReadinessState published;

    public HaAvailability(ApplicationEventPublisher publisher, MeterRegistry registry, String node) {
        this.publisher = publisher;
        Gauge.builder("exchange_leader_epoch", leaderEpoch, AtomicLong::doubleValue)
                .description("현재 리더 epoch 계단")
                .tag("node", node)
                .register(registry);
        Gauge.builder("exchange_leader_is_leader", isLeader, AtomicInteger::doubleValue)
                .description("리더 소유권(1)·비리더(0)")
                .tag("node", node)
                .register(registry);
        Gauge.builder("exchange_failover_downtime_seconds", failoverDowntimeMillis,
                        m -> m.get() / 1000.0)
                .description("페일오버 다운타임 RTO(구 리더 최종 심박~신 리더 승격)")
                .register(registry);
    }

    /**
     * 리스 획득 통지(리스 스케줄러 스레드). epoch 계단·is_leader·failover downtime을 갱신한다.
     * downtime은 구 리더 최종 심박~승격 간격이며, 실 페일오버가 아닌 최초 기동 승격은 0이다.
     */
    public void leaderAcquired(long epoch, double failoverDowntimeSeconds) {
        leaderEpoch.set(epoch);
        isLeader.set(1);
        if (failoverDowntimeSeconds > 0) {
            failoverDowntimeMillis.set(Math.round(failoverDowntimeSeconds * 1000.0));
        }
    }

    /** 리스 상실 통지. epoch 계단은 마지막 값을 유지하고 소유권만 내린다. */
    public void leaderLost() {
        isLeader.set(0);
    }

    @Override
    public synchronized void accepting() {
        desired = ReadinessState.ACCEPTING_TRAFFIC;
        publish(desired);
    }

    @Override
    public synchronized void refusing() {
        desired = ReadinessState.REFUSING_TRAFFIC;
        publish(desired);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // Spring이 ready 시점에 ACCEPTING_TRAFFIC를 자동 발행한다. 팔로워·승격 대기로 기동한
        // 노드는 그 전이를 되돌려야 readiness 오보(항상 UP)를 막는다.
        synchronized (this) {
            if (desired == ReadinessState.REFUSING_TRAFFIC) {
                published = null;
                publish(desired);
            } else {
                published = ReadinessState.ACCEPTING_TRAFFIC;
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void publish(ReadinessState state) {
        if (state == published) {
            return;
        }
        AvailabilityChangeEvent.publish(publisher, this, state);
        published = state;
        log.info("readiness 전이: {}", state);
    }
}

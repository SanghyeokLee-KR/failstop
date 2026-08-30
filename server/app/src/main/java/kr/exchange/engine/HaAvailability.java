package kr.exchange.engine;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HA 관측 게이지(epoch·is_leader·failover_downtime_seconds)와 readiness 배선(DB 설계 §5.4
 * readiness 연동, 하네스 CICD §5-2). 팔로워·승격 대기·강등 노드는 REFUSING이라 503을 낸다.
 * 발행 정정을 한 방향으로만 두는 근거는 §5.4 v2.27. 대칭이면 종료 중 노드가 UP으로 남는다.
 */
public class HaAvailability implements AvailabilitySignal,
        ApplicationListener<AvailabilityChangeEvent<?>> {

    private static final Logger log = LoggerFactory.getLogger(HaAvailability.class);

    private final ApplicationEventPublisher publisher;
    // 중복 발행 억제 기준. 내가 발행한 것이 아니라 실제 관측되는 readiness를 본다. 자기 기록만
    // 믿으면 Spring·타 컴포넌트의 발행을 놓쳐 억제가 잘못 걸린다(팔로워 UP 오보의 직접 원인).
    //
    // 이 주입은 배선 편의가 아니라 리스너 순서 계약이다. 정정은 상태 저장소
    // (ApplicationAvailabilityBean)가 같은 이벤트를 먼저 받은 뒤에 실행돼야 한다. 정정을 먼저
    // 실행하면 중첩 발행이 끝난 뒤 바깥 디스패치가 저장소에 원래(잘못된) 상태를 다시 기록해
    // 정정이 덮인다. 두 리스너 모두 Ordered 미구현이라 순서는 등록 순서, 곧 빈 초기화 순서로
    // 결정되고, 이 생성자 의존이 저장소를 먼저 초기화하도록 강제한다. 의존을 제거하면 순서
    // 보장이 사라진다. 실제 순서 회귀는 FollowerReadinessIntegrationTest가 잡는다.
    private final ApplicationAvailability availability;

    private final AtomicLong leaderEpoch = new AtomicLong(0);
    private final AtomicInteger isLeader = new AtomicInteger(0);
    // 초 단위 소수를 담기 위해 밀리초 정수로 보관하고 게이지에서 나눈다.
    private final AtomicLong failoverDowntimeMillis = new AtomicLong(0);

    // 접수 개방 여부 목표치. 팔로워 기동을 기본으로 둔다(리더 확정 전 트래픽 차단).
    private volatile ReadinessState desired = ReadinessState.REFUSING_TRAFFIC;

    public HaAvailability(ApplicationEventPublisher publisher, ApplicationAvailability availability,
                          MeterRegistry registry, String node) {
        this.publisher = publisher;
        this.availability = availability;
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

    /** 현재 목표 readiness. 관측·테스트용이며 상태 변경 진입점이 아니다. */
    public ReadinessState desired() {
        return desired;
    }

    @Override
    public synchronized void accepting() {
        desired = ReadinessState.ACCEPTING_TRAFFIC;
        publishIfNeeded(desired);
    }

    @Override
    public synchronized void refusing() {
        desired = ReadinessState.REFUSING_TRAFFIC;
        publishIfNeeded(desired);
    }

    /**
     * readiness 발행 감시. 목표가 REFUSING인데 ACCEPTING이 발행되면 즉시 되돌린다. Spring이 기동
     * 말미에 자동 발행하는 ACCEPTING_TRAFFIC이 주 대상이다. 반대 방향(목표 ACCEPTING인데 REFUSING
     * 발행)은 되돌리지 않는다. 종료 경로가 그 방향으로 발행하고, 되돌리면 종료 중인 노드가 UP으로
     * 남는다(클래스 주석 비대칭 절).
     */
    @Override
    public void onApplicationEvent(AvailabilityChangeEvent<?> event) {
        if (!(event.getState() instanceof ReadinessState published)) {
            return;
        }
        if (published != ReadinessState.ACCEPTING_TRAFFIC
                || desired != ReadinessState.REFUSING_TRAFFIC) {
            return;
        }
        // 억제 없이 무조건 발행한다. 이 시점의 ApplicationAvailability는 아직 이 이벤트를
        // 반영하지 않았을 수 있어(리스너 순서 미보장) 실측 조회로 판단하면 어긋난다.
        log.warn("readiness 오발행 정정: 발행={}, 목표={}", published, ReadinessState.REFUSING_TRAFFIC);
        AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
    }

    private void publishIfNeeded(ReadinessState state) {
        if (availability.getReadinessState() == state) {
            return;
        }
        AvailabilityChangeEvent.publish(publisher, this, state);
        log.info("readiness 전이: {}", state);
    }
}

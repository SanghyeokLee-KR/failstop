package kr.exchange.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HA 관측 게이지·readiness 배선 단위 검증(감사 관측성·복구). Testcontainers 불요.
 *
 * 이벤트 버스는 실제 구조를 그대로 모사한다. 발행 하나가 ApplicationAvailabilityBean(상태 기록)과
 * HaAvailability(감시 정정) 양쪽에 동기 전달되고, HaAvailability의 정정 발행도 같은 경로를 다시
 * 탄다. 억제 판정이 자기 기록이 아니라 실측 상태를 보는지, 정정이 재귀하지 않는지가 이 구조에서만
 * 드러난다. Spring 기동 말미의 실제 순서(ready 리스너 전부 반환 -> ACCEPTING_TRAFFIC 자동 발행,
 * EventPublishingRunListener.ready)를 springReady()로 재현한다.
 */
class HaAvailabilityTest {

    private final List<ReadinessState> published = new ArrayList<>();
    private final ApplicationAvailabilityBean availabilityBean = new ApplicationAvailabilityBean();
    private HaAvailability ha;

    // 전달 순서는 프로덕션 계약을 그대로 박는다. 상태 저장소가 먼저, 정정이 나중이다. 이 순서는
    // HaAvailability가 ApplicationAvailability를 생성자로 받아 저장소가 먼저 초기화·등록되는 데서
    // 온다. 순서가 뒤집히면 정정이 바깥 디스패치에 덮이며, 그 회귀는 실제 컨텍스트를 쓰는
    // FollowerReadinessIntegrationTest가 잡는다(여기서는 전제로 둔다).
    private final ApplicationEventPublisher publisher = event -> {
        if (event instanceof AvailabilityChangeEvent<?> ace
                && ace.getState() instanceof ReadinessState state) {
            published.add(state);
            availabilityBean.onApplicationEvent(ace);
            ha.onApplicationEvent(ace);
        }
    };

    private HaAvailability newHa() {
        return newHa(new SimpleMeterRegistry());
    }

    private HaAvailability newHa(SimpleMeterRegistry registry) {
        ha = new HaAvailability(publisher, availabilityBean, registry, "node-a");
        return ha;
    }

    private void springReady() {
        AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void leaderAcquiredSetsEpochIsLeaderAndDowntimeGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HaAvailability node = newHa(registry);

        assertEquals(0.0, gauge(registry, "exchange_leader_is_leader"));
        assertEquals(0.0, gauge(registry, "exchange_leader_epoch"));

        node.leaderAcquired(43, 3.5);

        assertEquals(43.0, gauge(registry, "exchange_leader_epoch"));
        assertEquals(1.0, gauge(registry, "exchange_leader_is_leader"));
        assertEquals(3.5, gauge(registry, "exchange_failover_downtime_seconds"));
    }

    @Test
    void leaderLostClearsIsLeaderButKeepsEpochStep() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HaAvailability node = newHa(registry);
        node.leaderAcquired(43, 3.5);

        node.leaderLost();

        assertEquals(0.0, gauge(registry, "exchange_leader_is_leader"));
        // epoch 계단은 마지막 값을 유지한다(§5-2 계단 의미).
        assertEquals(43.0, gauge(registry, "exchange_leader_epoch"));
    }

    @Test
    void zeroDowntimeDoesNotOverwritePriorFailoverValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HaAvailability node = newHa(registry);
        node.leaderAcquired(43, 3.5);

        // 최초 기동·재획득의 downtime 0은 실측 페일오버 값을 덮지 않는다.
        node.leaderAcquired(44, 0);

        assertEquals(3.5, gauge(registry, "exchange_failover_downtime_seconds"));
        assertEquals(44.0, gauge(registry, "exchange_leader_epoch"));
    }

    @Test
    void acceptingAndRefusingPublishReadinessTransitionsAndDedupe() {
        HaAvailability node = newHa();

        node.refusing();
        node.refusing();
        node.accepting();
        node.accepting();
        node.refusing();

        // 초기 실측이 REFUSING이라 첫 refusing()은 발행할 것이 없다. 전이만 남는다.
        assertEquals(List.of(ReadinessState.ACCEPTING_TRAFFIC, ReadinessState.REFUSING_TRAFFIC),
                published, "동일 상태 재발행은 억제되고 전이만 발행돼야 한다");
        assertEquals(ReadinessState.REFUSING_TRAFFIC, availabilityBean.getReadinessState());
    }

    @Test
    void springAutoAcceptingAfterReadyIsRevertedForFollower() {
        HaAvailability node = newHa();
        node.refusing();
        assertTrue(published.isEmpty(), "초기 REFUSING은 실측과 같아 발행할 것이 없다");

        springReady();

        // 자동 ACCEPTING이 실제로 발행되지만 감시가 즉시 REFUSING으로 되돌린다.
        assertEquals(List.of(ReadinessState.ACCEPTING_TRAFFIC, ReadinessState.REFUSING_TRAFFIC),
                published, "ready 이후 자동 ACCEPTING을 되돌리지 못했다");
        assertEquals(ReadinessState.REFUSING_TRAFFIC, availabilityBean.getReadinessState(),
                "팔로워는 실측 readiness가 REFUSING이어야 한다");
        assertEquals(ReadinessState.REFUSING_TRAFFIC, node.desired());
    }

    @Test
    void demotedNodeStaysRefusingAfterSpringAutoAccepting() {
        HaAvailability node = newHa();
        // 리더로 기동해 접수를 열었다가 강등된 노드.
        node.accepting();
        node.refusing();
        published.clear();

        // 강등 이후 어떤 경로로든 ACCEPTING이 발행되면 되돌려야 한다. 되돌리지 못하면 리더가
        // 없는데 두 노드가 다 UP인 상태를 관측으로 구분할 수 없다.
        springReady();

        assertEquals(List.of(ReadinessState.ACCEPTING_TRAFFIC, ReadinessState.REFUSING_TRAFFIC),
                published);
        assertEquals(ReadinessState.REFUSING_TRAFFIC, availabilityBean.getReadinessState());
    }

    @Test
    void springAutoAcceptingIsKeptForLeader() {
        HaAvailability node = newHa();
        node.accepting();
        published.clear();

        springReady();

        // 목표와 같은 발행은 정정 대상이 아니다. 되돌리면 정당한 리더가 트래픽을 잃는다.
        // 자동 ACCEPTING 1건만 남고 뒤따르는 정정 발행이 없어야 한다.
        assertEquals(List.of(ReadinessState.ACCEPTING_TRAFFIC), published,
                "리더의 ACCEPTING을 되돌리면 안 된다");
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, availabilityBean.getReadinessState());
    }

    @Test
    void correctionDoesNotRecurse() {
        HaAvailability node = newHa();
        node.refusing();

        springReady();
        springReady();

        // 정정 발행은 desired와 일치하므로 추가 정정을 부르지 않는다. 오발행 1 + 정정 1의 두 쌍뿐.
        assertEquals(4, published.size(), "정정이 재귀했다");
        assertEquals(ReadinessState.REFUSING_TRAFFIC, availabilityBean.getReadinessState());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, node.desired());
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}

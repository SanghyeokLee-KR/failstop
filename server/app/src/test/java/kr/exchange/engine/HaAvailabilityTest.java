package kr.exchange.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HA 관측 게이지·readiness 배선 단위 검증(감사 관측성·복구). Testcontainers 불요.
 */
class HaAvailabilityTest {

    private final List<Object> events = new ArrayList<>();
    private final ApplicationEventPublisher publisher = events::add;

    @Test
    void leaderAcquiredSetsEpochIsLeaderAndDowntimeGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HaAvailability ha = new HaAvailability(publisher, registry, "node-a");

        assertEquals(0.0, gauge(registry, "exchange_leader_is_leader"));
        assertEquals(0.0, gauge(registry, "exchange_leader_epoch"));

        ha.leaderAcquired(43, 3.5);

        assertEquals(43.0, gauge(registry, "exchange_leader_epoch"));
        assertEquals(1.0, gauge(registry, "exchange_leader_is_leader"));
        assertEquals(3.5, gauge(registry, "exchange_failover_downtime_seconds"));
    }

    @Test
    void leaderLostClearsIsLeaderButKeepsEpochStep() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HaAvailability ha = new HaAvailability(publisher, registry, "node-a");
        ha.leaderAcquired(43, 3.5);

        ha.leaderLost();

        assertEquals(0.0, gauge(registry, "exchange_leader_is_leader"));
        // epoch 계단은 마지막 값을 유지한다(§5-2 계단 의미).
        assertEquals(43.0, gauge(registry, "exchange_leader_epoch"));
    }

    @Test
    void zeroDowntimeDoesNotOverwritePriorFailoverValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HaAvailability ha = new HaAvailability(publisher, registry, "node-a");
        ha.leaderAcquired(43, 3.5);

        // 최초 기동·재획득의 downtime 0은 실측 페일오버 값을 덮지 않는다.
        ha.leaderAcquired(44, 0);

        assertEquals(3.5, gauge(registry, "exchange_failover_downtime_seconds"));
        assertEquals(44.0, gauge(registry, "exchange_leader_epoch"));
    }

    @Test
    void acceptingAndRefusingPublishReadinessTransitionsAndDedupe() {
        HaAvailability ha = new HaAvailability(publisher, new SimpleMeterRegistry(), "node-a");

        ha.refusing();
        ha.refusing();
        ha.accepting();
        ha.accepting();
        ha.refusing();

        List<ReadinessState> states = readinessStates();
        assertEquals(List.of(ReadinessState.REFUSING_TRAFFIC,
                ReadinessState.ACCEPTING_TRAFFIC,
                ReadinessState.REFUSING_TRAFFIC), states,
                "동일 상태 재발행은 억제되고 전이만 발행돼야 한다");
    }

    @Test
    void readyEventReassertsRefusingToOverrideSpringAutoAccepting() {
        HaAvailability ha = new HaAvailability(publisher, new SimpleMeterRegistry(), "node-a");
        // 팔로워로 기동: desired = REFUSING. Spring이 ready에서 ACCEPTING을 자동 발행한 상황을
        // 모사하기 위해 published 상태를 ACCEPTING으로 만든 뒤 ready를 흘린다.
        ha.refusing();
        events.clear();

        ha.onApplicationEvent(null);

        List<ReadinessState> states = readinessStates();
        assertEquals(List.of(ReadinessState.REFUSING_TRAFFIC), states,
                "ready 후 팔로워는 REFUSING으로 되돌려야 한다");
    }

    private List<ReadinessState> readinessStates() {
        List<ReadinessState> states = new ArrayList<>();
        for (Object e : events) {
            if (e instanceof AvailabilityChangeEvent<?> ace
                    && ace.getState() instanceof ReadinessState rs) {
                states.add(rs);
            }
        }
        return states;
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void readyEventKeepsAcceptingWhenDesiredIsAccepting() {
        HaAvailability ha = new HaAvailability(publisher, new SimpleMeterRegistry(), "node-a");
        ha.accepting();
        events.clear();

        ha.onApplicationEvent(null);

        assertTrue(readinessStates().isEmpty(),
                "이미 ACCEPTING이면 ready에서 중복 발행하지 않는다");
    }
}

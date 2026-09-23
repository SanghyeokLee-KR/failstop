package kr.exchange.admin;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 용량 콘솔의 임계·권고 판정(ADR 0015). 희망 대수는 V32 capacity_plan(최소 2·최대 3)에서 읽고,
 * 관측은 고정 값을 돌려주는 프로브로 대신한다.
 */
@Testcontainers
class CapacityAdminControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void resetPlan() {
        desired(2);
    }

    @Test
    void 조회_평균이_증설_임계_이상이고_최대_아래면_증설을_권고한다() {
        Map<String, Object> state = state(inventory(), Map.of(
                "app-1", node(true, true, 0.70),
                "app-2", node(false, true, 0.70)));

        assertEquals(0.70, (Double) state.get("read_avg_cpu_ratio"), 1e-9);
        assertEquals(Map.of("scale_out_cpu_ratio", 0.60, "scale_in_cpu_ratio", 0.30), state.get("thresholds"));
        assertEquals(List.of("scale_out"), cardIds(state));
        assertEquals("SCALE_OUT", advice(state).get(0).get("action"));
    }

    @Test
    void 최대_대수에서는_같은_부하에도_증설_조치를_내지_않는다() {
        desired(3);
        Map<String, Object> state = state(inventory(), Map.of(
                "app-1", node(true, true, 0.70),
                "app-2", node(false, true, 0.70),
                "app-3", node(false, true, 0.70)));

        assertEquals(List.of("at_max"), cardIds(state));
        assertNull(advice(state).get(0).get("action"));
    }

    @Test
    void 축소_임계_이하이고_최소보다_많으면_축소를_권고한다() {
        desired(3);
        Map<String, Object> state = state(inventory(), Map.of(
                "app-1", node(true, true, 0.20),
                "app-2", node(false, true, 0.20),
                "app-3", node(false, true, 0.20)));

        assertEquals(List.of("scale_in"), cardIds(state));
        assertEquals("SCALE_IN", advice(state).get(0).get("action"));
    }

    @Test
    void 최소_대수에서는_유휴여도_안정이다() {
        Map<String, Object> state = state(inventory(), Map.of(
                "app-1", node(true, true, 0.20),
                "app-2", node(false, true, 0.20)));

        assertEquals(List.of("steady"), cardIds(state));
    }

    @Test
    void 임계는_설정값을_따른다() {
        Map<String, NodeProbe.Observed> observed = Map.of(
                "app-1", node(true, true, 0.33),
                "app-2", node(false, true, 0.33));
        CapacityProperties local = inventory();
        local.setScaleOutCpu(0.25);
        local.setScaleInCpu(0.10);

        assertEquals(List.of("steady"), cardIds(state(inventory(), observed)));
        Map<String, Object> lowered = state(local, observed);
        assertEquals(List.of("scale_out"), cardIds(lowered));
        assertEquals(Map.of("scale_out_cpu_ratio", 0.25, "scale_in_cpu_ratio", 0.10), lowered.get("thresholds"));
    }

    @Test
    void 조회를_받지_않거나_사용률이_없는_노드는_평균에서_뺀다() {
        desired(3);
        Map<String, Object> state = state(inventory(), Map.of(
                "app-1", node(true, false, 0.95),
                "app-2", node(false, true, 0.40),
                "app-3", node(false, true, null)));

        assertEquals(0.40, (Double) state.get("read_avg_cpu_ratio"), 1e-9);
        assertEquals(List.of("steady"), cardIds(state));
    }

    @Test
    void 사용률을_하나도_못_읽으면_부하_권고를_내지_않는다() {
        Map<String, Object> state = state(inventory(), Map.of(
                "app-1", node(true, true, null),
                "app-2", node(false, true, null)));

        assertNull(state.get("read_avg_cpu_ratio"));
        assertEquals(List.of("no_metrics"), cardIds(state));
    }

    @Test
    void 리더_부재와_대수_부족을_부하_권고보다_먼저_낸다() {
        desired(3);
        Map<String, Object> state = state(inventory(), Map.of(
                "app-2", node(false, true, 0.70),
                "app-3", node(false, true, 0.70)));

        assertEquals(List.of("leader_down", "under_provisioned", "at_max"), cardIds(state));
        assertEquals(2, state.get("observed_total"));
        assertEquals(false, state.get("reconciled"));
    }

    @Test
    void 설정_순서의_앞에서부터_희망_대수만큼을_떠_있어야_할_노드로_본다() {
        Map<String, Object> state = state(inventory(), Map.of(
                "app-1", node(true, true, 0.40),
                "app-2", node(false, true, 0.40)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) state.get("nodes");
        assertEquals(List.of(true, true, false), nodes.stream().map(n -> n.get("expected")).toList());
        assertEquals(List.of(true, true, false), nodes.stream().map(n -> n.get("reachable")).toList());
        assertEquals(true, state.get("reconciled"));
    }

    private static Map<String, Object> state(CapacityProperties props, Map<String, NodeProbe.Observed> observed) {
        NodeProbe probe = new NodeProbe(Duration.ofSeconds(1)) {
            @Override
            public Map<String, Observed> probeAll(List<CapacityProperties.Node> nodes) {
                return observed;
            }
        };
        CapacityAdminController controller = new CapacityAdminController(props, probe, null, jdbc);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AdminPrincipal.REQUEST_ATTRIBUTE,
                new AdminPrincipal("op-01", "jti-capacity", Set.of(AdminRole.VIEWER)));
        return controller.state(request);
    }

    /** admin-local 과 같은 모양의 계약면. 리더 후보 한 대와 조회 전용 두 대다. */
    private static CapacityProperties inventory() {
        CapacityProperties props = new CapacityProperties();
        props.getNodes().add(inventoryNode("app-1", true));
        props.getNodes().add(inventoryNode("app-2", false));
        props.getNodes().add(inventoryNode("app-3", false));
        return props;
    }

    private static CapacityProperties.Node inventoryNode(String id, boolean engine) {
        CapacityProperties.Node node = new CapacityProperties.Node();
        node.setId(id);
        node.setEngine(engine);
        node.setScalable(!engine);
        return node;
    }

    private static NodeProbe.Observed node(boolean leader, boolean servesReads, Double cpu) {
        return new NodeProbe.Observed(true, leader, servesReads, cpu, 1, null, null, null, null);
    }

    private static void desired(int nodes) {
        jdbc.sql("UPDATE capacity_plan SET desired_nodes = ? WHERE tier = 'app'").param(nodes).update();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> advice(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.get("advice");
    }

    private static List<Object> cardIds(Map<String, Object> state) {
        return advice(state).stream().map(card -> card.get("id")).toList();
    }
}

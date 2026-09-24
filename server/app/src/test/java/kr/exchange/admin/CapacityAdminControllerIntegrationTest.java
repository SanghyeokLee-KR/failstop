package kr.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.exchange.admin.msp.OpsThresholdService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    static TransactionTemplate tx;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
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

    @Test
    void 겹친_두_증설은_하나만_반영되고_다른_하나는_최대_초과로_거부된다() throws Exception {
        CapacityAdminController controller = controller(inventory(), Map.of());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (java.sql.Connection holder = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // 세 번째 연결이 행을 잡고 있는 동안 두 요청을 보내 둘 다 같은 잠금에서 기다리게 만든다.
            holder.setAutoCommit(false);
            try (java.sql.Statement st = holder.createStatement()) {
                st.execute("SELECT desired_nodes FROM capacity_plan WHERE tier = 'app' FOR UPDATE");
            }
            java.util.List<java.util.concurrent.Future<Object>> calls = java.util.List.of(
                    pool.submit(() -> scaleOut(controller)), pool.submit(() -> scaleOut(controller)));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%capacity_plan%'
                    """).query(Long.class).single() == 2);
            holder.commit();

            java.util.List<Object> results = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Object> call : calls) {
                results.add(call.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter(r -> r instanceof Map).count(), "반영된 증설: " + results);
            assertEquals(java.util.List.of("CAPACITY_OUT_OF_BOUNDS"), results.stream()
                    .filter(r -> r instanceof kr.exchange.api.common.ApiException)
                    .map(r -> ((kr.exchange.api.common.ApiException) r).name()).toList());
            assertEquals(3, jdbc.sql("SELECT desired_nodes FROM capacity_plan WHERE tier = 'app'")
                    .query(Integer.class).single());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object scaleOut(CapacityAdminController controller) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AdminPrincipal.REQUEST_ATTRIBUTE,
                new AdminPrincipal("op-01", "jti-capacity", Set.of(AdminRole.SUPER)));
        try {
            return controller.scaleOut(new CapacityAdminController.ScaleBody("겹침 시험"), request);
        } catch (kr.exchange.api.common.ApiException e) {
            return e;
        }
    }

    @Test
    void 관제센터를_켜면_권고_임계는_레지스트리_값을_따른다() {
        Map<String, NodeProbe.Observed> observed = Map.of(
                "app-1", node(true, true, 0.33),
                "app-2", node(false, true, 0.33));
        OpsThresholdService registry = new OpsThresholdService(jdbc, tx, null, null, new SimpleMeterRegistry());
        try {
            assertEquals(List.of("steady"), cardIds(state(inventory(), observed, Optional.of(registry))));
            // 두 행을 한 문장으로 바꾼다. 커밋 때의 간격 검사(T3)가 중간 상태를 보지 않는다.
            readThresholds("0.25", "0.10");

            Map<String, Object> lowered = state(inventory(), observed, Optional.of(registry));
            assertEquals(List.of("scale_out"), cardIds(lowered));
            assertEquals(Map.of("scale_out_cpu_ratio", 0.25, "scale_in_cpu_ratio", 0.10), lowered.get("thresholds"));
        } finally {
            readThresholds("0.60", "0.30");
        }
    }

    @Test
    void 관제센터를_끄면_임계값_표를_읽지_않는다() {
        jdbc.sql("ALTER TABLE ops_thresholds RENAME TO ops_thresholds_hidden").update();
        try {
            Map<String, Object> state = state(inventory(), Map.of(
                    "app-1", node(true, true, 0.70),
                    "app-2", node(false, true, 0.70)));
            assertEquals(List.of("scale_out"), cardIds(state));
        } finally {
            jdbc.sql("ALTER TABLE ops_thresholds_hidden RENAME TO ops_thresholds").update();
        }
    }

    private static void readThresholds(String scaleOut, String scaleIn) {
        jdbc.sql("""
                        UPDATE ops_thresholds SET value = CASE key WHEN 'read.scale_out_cpu' THEN ?::numeric
                                                                   ELSE ?::numeric END
                        WHERE key IN ('read.scale_out_cpu', 'read.scale_in_cpu')
                        """)
                .params(scaleOut, scaleIn)
                .update();
    }

    private static CapacityAdminController controller(CapacityProperties props,
                                                      Map<String, NodeProbe.Observed> observed) {
        return controller(props, observed, Optional.empty());
    }

    private static CapacityAdminController controller(CapacityProperties props,
                                                      Map<String, NodeProbe.Observed> observed,
                                                      Optional<OpsThresholdService> registry) {
        NodeProbe probe = new NodeProbe(Duration.ofSeconds(1)) {
            @Override
            public Map<String, Observed> probeAll(List<CapacityProperties.Node> nodes) {
                return observed;
            }
        };
        CapacityPlanService plans = new CapacityPlanService(jdbc, tx,
                new AdminAuditService(jdbc, new ObjectMapper()), props, probe);
        return new CapacityAdminController(props, plans, registry);
    }

    private static Map<String, Object> state(CapacityProperties props, Map<String, NodeProbe.Observed> observed) {
        return state(props, observed, Optional.empty());
    }

    private static Map<String, Object> state(CapacityProperties props, Map<String, NodeProbe.Observed> observed,
                                             Optional<OpsThresholdService> registry) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AdminPrincipal.REQUEST_ATTRIBUTE,
                new AdminPrincipal("op-01", "jti-capacity", Set.of(AdminRole.VIEWER)));
        return controller(props, observed, registry).state(request);
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

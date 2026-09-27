package kr.exchange.admin.msp;

import kr.exchange.admin.AdminRole;
import kr.exchange.admin.CapacityPlanService;
import kr.exchange.api.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 앱 계층 대수 모델(ADR 0030). 늘 켜 두는 app-1·app-2 두 대에 조회 전용 app-3 한 대를 켜고 끄므로 계층은 2~3대다.
 * AI 의 READ_NODES 는 이 범위 안에서 희망 대수를 1씩만 옮긴다.
 */
@Testcontainers
class AppTierCapacityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final String ADD = AiRig.out("조회 평균 CPU가 82%입니다.", "app-3를 켜서 조회를 나눕니다.",
            "READ_NODE_ADD_ONE", "app", "", "0.9");
    private static final String REMOVE = AiRig.out("조회 평균 CPU가 10%입니다.", "app-3를 꺼도 됩니다.",
            "READ_NODE_REMOVE_ONE", "app", "", "0.9");

    // 시험 장치가 시험마다 계획 행을 되돌리므로 마이그레이션 직후의 시드를 먼저 읽어 둔다.
    private static List<Integer> seed;

    private AiRig.Sources src;
    private AiRig rig;

    @BeforeAll
    static void migrate() {
        AiRig.migrate(POSTGRES);
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        seed = jdbc.sql("SELECT min_nodes, desired_nodes, max_nodes FROM capacity_plan WHERE tier = 'app'")
                .query((rs, i) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                .single();
    }

    @BeforeEach
    void start() throws IOException {
        src = new AiRig.Sources();
        rig = new AiRig(POSTGRES, src, MspProperties.Llm.Provider.OPENAI_COMPATIBLE, p -> { });
    }

    @AfterEach
    void stop() {
        rig.close();
        src.close();
    }

    @Test
    void 마이그레이션한_앱_계층_계획은_최소_2대_희망_2대_최대_3대다() {
        assertEquals(List.of(2, 2, 3), seed);
        CapacityPlanService.Plan plan = rig.capacity.plan();
        assertEquals(List.of(2, 2, 3), List.of(plan.min(), plan.desired(), plan.max()), "시험 장치도 같은 범위를 쓴다");
    }

    @Test
    void 희망_2대에서_READ_NODES_더하기_1을_승인하면_app_3가_켜져_3대가_된다() {
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(1, MINUTE);
        AiStore.Action a = rig.latest();
        assertEquals("PROPOSED", a.status());

        AiStore.Action done = rig.executor.approve(a.id(), AiRig.principal(AiRig.SUPER, AdminRole.SUPER), null,
                "req-approve");

        assertEquals("APPROVED", done.status());
        assertEquals(2, done.result().path("from").asInt());
        assertEquals(3, done.result().path("to").asInt());
        assertEquals(3, rig.desired());
    }

    @Test
    void 희망_대수_이력은_감사_기록의_전후_대수를_시각_순서로_읽고_거부는_뺀다() {
        Instant before = rig.jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
        rig.capacity.change(1, AiRig.SUPER, "capacity.scale_out", "시험", "req-shift-out");
        rig.capacity.change(-1, AiRig.SUPER, "capacity.scale_in", "시험", "req-shift-in");
        assertThrows(ApiException.class, () -> rig.capacity.change(-1, AiRig.SUPER, "capacity.scale_in", "시험",
                "req-shift-denied"));

        List<CapacityPlanService.Shift> shifts = rig.capacity.shiftsSince(before);

        assertEquals(List.of(List.of(2, 3), List.of(3, 2)), shifts.stream().map(s -> List.of(s.from(), s.to())).toList(),
                "최소 아래로 줄이려던 거부(DENIED)는 대수를 바꾸지 않았으므로 이력이 아니다");
        assertTrue(!shifts.get(1).at().isBefore(shifts.get(0).at()));
        assertEquals(List.of(), rig.capacity.shiftsSince(shifts.get(1).at().plusSeconds(1)));
    }

    @Test
    void 희망_3대에서_READ_NODES_더하기_1은_최대를_넘어_R1으로_막힌다() {
        rig.nodes(3);
        src.cpu = 0.82;
        src.llmContent = ADD;
        rig.ticks(1, MINUTE);

        AiStore.Action a = rig.latest();
        assertEquals("DENIED", a.status());
        assertEquals("R1", a.holdRule());
        assertTrue(a.holdReason().contains("최대 3대"), a.holdReason());
        assertEquals("out_of_bounds", AiPolicy.r1Bounds(new AiAction.ReadNodes(1), rig.collector.peek().read()).code());
        ApiException bounds = assertThrows(ApiException.class,
                () -> rig.capacity.change(1, AiRig.SUPER, "capacity.scale_out", "시험", "req-out"));
        assertEquals("CAPACITY_OUT_OF_BOUNDS", bounds.name());
        assertEquals(3, rig.desired());
    }

    @Test
    void 희망_2대에서_READ_NODES_빼기_1은_최소_아래라_R1으로_막힌다() {
        src.cpu = 0.10;
        src.llmContent = REMOVE;
        // 최소 대수에서는 축소 후보가 아니라 평온으로 쉰다. 경보 하나로 판단을 부른다.
        src.alerts = List.of(AiRig.alert("c3c3c3c3c3c3c3c3", "ReadTierCpuLow", "warning", "app"));
        rig.ticks(1, MINUTE);

        AiStore.Action a = rig.latest();
        assertEquals("DENIED", a.status());
        assertEquals("R1", a.holdRule());
        assertTrue(a.holdReason().contains("최소 2"), a.holdReason());
        assertEquals("out_of_bounds", AiPolicy.r1Bounds(new AiAction.ReadNodes(-1), rig.collector.peek().read()).code());
        ApiException bounds = assertThrows(ApiException.class,
                () -> rig.capacity.change(-1, AiRig.SUPER, "capacity.scale_in", "시험", "req-in"));
        assertEquals("CAPACITY_OUT_OF_BOUNDS", bounds.name());
        assertEquals(2, rig.desired());
    }
}

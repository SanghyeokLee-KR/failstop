package kr.exchange.config;

import kr.exchange.api.WsProbe;
import kr.exchange.api.ws.CandleFanout;
import kr.exchange.api.ws.LeaseWatch;
import kr.exchange.api.ws.MarketMirror;
import kr.exchange.api.ws.WsGate;
import kr.exchange.api.ws.relay.BrokerLink;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayHeartbeat;
import kr.exchange.api.ws.relay.RelayPublisher;
import kr.exchange.api.ws.relay.RelaySubscriber;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.FollowerCatchUp;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionPlan;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔진을 끈 노드가 기동하는지 본다(2026-09-08).
 *
 * 봇 전용 노드는 `exchange.engine.enabled=false` 로 뜬다. 리더 경쟁에 끼면 봇이 죽인 리더로
 * 승격 주체가 오염되기 때문이다(`deploy/compose.node-bot.yml`).
 *
 * 이 검증이 있는 이유는 실제로 깨졌기 때문이다. 체크포인트 상한 작업에서 `FollowerCatchUp` 을
 * 넣으면서 승격 조율이 그것을 필수 인자로 받았는데, 그 빈은 엔진과 같은 조건에서만
 * 뜬다. 그래서 봇 컨테이너가 "required a bean of type FollowerCatchUp" 으로 기동에 실패하고
 * 재시작을 반복했다. 통합 검증 전량이 엔진을 켠 채로만 돌아 아무도 잡지 못했다.
 *
 * 파티션 배선(DB 설계 §5.4-c 2)에서 엔진을 끈 노드도 파티션 계획과 파티션별 리스 서비스를 만든다. epoch·하네스·관리 API 가
 * 쓰기 때문이다. 리스 틱은 시작하지 않는다. 틱 스레드가 없고 리스 행을 바꾸지 않는다.
 *
 * WS 중계(ADR 0034 S6)에서 엔진을 끈 노드는 게시가 없으므로 중계 빈과 리스 조회기를 만들지 않는다. 봇 프로파일 판은 중계 모드를
 * broker 로 주어도 그렇다. WS 게이트는 늘 닫혀 있어 /ws/v1 핸드셰이크를 업그레이드 전에 503(relay_required)으로 거절한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngineDisabledContextTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername("exchange")
            .withPassword("exchange");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("exchange.auth.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("exchange.engine.enabled", () -> "false");
    }

    @Autowired ApplicationContext context;

    @Test
    void 엔진을_끈_노드도_기동하고_리스를_건드리지_않는다() throws Exception {
        assertEngineDisabledWiring(context);
    }

    /**
     * 봇 노드는 bots 프로파일로 뜬다. 그 문서의 설정이 겹쳐도 같은 배선이어야 한다. 봇 플릿 수명주기는 막는다. 플릿은 기동
     * 때 base-url(8080)로 마켓 메타를 읽는데 시험 서버는 임의 포트라 닿지 않는다. 여기서 보려는 것은 엔진 배선이다.
     */
    @Nested
    @ActiveProfiles("bots")
    @TestPropertySource(properties = "exchange.ws.relay.mode=broker")
    class BotsProfile {

        @Autowired ApplicationContext botsContext;
        @MockitoBean(name = "botFleetLifecycle") SmartLifecycle botFleetLifecycle;

        @Test
        void 봇_프로파일로도_기동하고_리스를_건드리지_않는다() throws Exception {
            assertEngineDisabledWiring(botsContext);
        }
    }

    private static void assertEngineDisabledWiring(ApplicationContext context) throws Exception {
        assertNotNull(context.getBean(EngineBridge.class), "엔진이 꺼진 노드에서 접수 배선이 뜨지 않았다");
        assertEquals(0, context.getBeanNamesForType(FollowerCatchUp.class).length,
                "엔진이 꺼졌는데 따라잡기 빈이 떴다. 재생할 엔진이 없으므로 있으면 안 된다");
        PartitionPlan plan = context.getBean(PartitionPlan.class);
        PartitionLeases leases = context.getBean(PartitionLeases.class);
        assertEquals(List.of("matching-engine"), plan.partitions());
        assertTrue(leases.runtimes().isEmpty(), "엔진이 꺼진 노드에 파티션 수명주기가 있다");
        assertFalse(leases.first().started(), "엔진이 꺼진 노드가 리스 틱을 시작했다");

        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        Map<String, Object> before = lease(jdbc);
        // 틱 주기(기본 3초)보다 길게 기다려도 리스 행이 그대로다. 틱이 돌았다면 시드 만료 리스를 얻어 epoch 가 올랐다.
        Thread.sleep(3_500);
        assertEquals(before, lease(jdbc), "엔진이 꺼진 노드가 리스 행을 바꿨다");
        // 틱 스레드 이름(leader-lease-*)으로는 보지 않는다. 같은 JVM 에 캐시된 다른 시험 컨텍스트의 틱 스레드가 섞인다.
        assertTrue(leases.all().stream().noneMatch(LeaderLeaseService::started),
                "엔진이 꺼진 노드의 파티션 리스가 틱을 시작했다");

        for (Class<?> relay : List.of(RelayPublisher.class, BrokerLink.class, RelaySubscriber.class,
                RelayHeartbeat.class, OwnerBeats.class, MarketMirror.class, CandleFanout.class, LeaseWatch.class)) {
            assertEquals(0, context.getBeanNamesForType(relay).length,
                    "엔진이 꺼진 노드에 " + relay.getSimpleName() + " 이(가) 떴다");
        }
        WsGate gate = context.getBean(WsGate.class);
        assertEquals(new WsGate.Decision(false, WsGate.Reason.RELAY_REQUIRED), gate.current());
        long refusedBefore = gate.handshakeRefused(WsGate.Reason.RELAY_REQUIRED);
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        WsProbe.Handshake handshake = WsProbe.handshake(port, null);
        assertEquals(503, handshake.status(), "엔진이 꺼진 노드가 WS 핸드셰이크를 받았다");
        assertEquals("5", handshake.header("Retry-After"));
        assertEquals(refusedBefore + 1, gate.handshakeRefused(WsGate.Reason.RELAY_REQUIRED));
    }

    private static Map<String, Object> lease(JdbcClient jdbc) {
        return jdbc.sql("SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine'")
                .query().singleRow();
    }
}

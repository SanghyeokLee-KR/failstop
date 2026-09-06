package kr.exchange.sim;

import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.ledger.AccountBootstrap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 봇 플릿 배선. bots 프로파일에서만 활성화되어 거래소 본체와 분리 기동이 가능하다.
 * 플릿은 컨텍스트 기동 완료 후 시작한다(리더 승격 전 접수는 503으로 봇이 흡수).
 */
@Configuration
@Profile("bots")
// SimCommandConsumer.poll이 @Scheduled다. 스케줄링은 HarnessConfig에만 켜져 있고 그것은
// exchange.harness.enabled 조건이라 봇 노드에서 꺼져 있을 수 있다. 여기서 직접 켠다.
// 중복 활성은 무해하다(스프링이 TaskScheduler를 한 번만 만든다).
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties(SimProperties.class)
public class SimConfig {

    // 명령 적용자와 상태 발행자가 같은 값을 써야 콘솔이 둘을 한 노드로 읽는다.
    private static final String NODE_ID =
            "bots-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    @Bean
    public BotSeeder botSeeder(JdbcClient jdbc, AccountBootstrap bootstrap, DemoKeySeeder keys) {
        return new BotSeeder(jdbc, bootstrap, keys);
    }

    @Bean
    public OpsGovernor opsGovernor(SimProperties props) {
        return new OpsGovernor(props.targetOps());
    }

    @Bean
    public BotFleetGovernor botFleetGovernor(SimProperties props) {
        // 레짐 시퀀스 재현을 위해 봇 시드를 레짐 PRNG에 주입한다.
        return BotFleetGovernor.defaults(props.seed());
    }

    /**
     * 관리 평면이 남긴 제어 의도를 이 JVM이 집어 적용한다(V22 sim_commands). 거버너가
     * 인메모리라 별도 컨테이너인 관리 평면이 직접 못 건드리는 것을 메운다.
     */
    @Bean
    public SimCommandConsumer simCommandConsumer(JdbcClient jdbc, OpsGovernor governor,
                                                 BotFleetGovernor fleetGovernor) {
        return new SimCommandConsumer(jdbc, governor, fleetGovernor, NODE_ID);
    }

    /**
     * 이 JVM의 플릿 상태를 sim_fleet_state에 발행한다(V28). 관리 평면의 status가 읽을
     * 원천이며, 없으면 콘솔이 플릿을 상시 정지로 그린다.
     */
    @Bean
    public SimStateReporter simStateReporter(JdbcClient jdbc, OpsGovernor governor,
                                             BotFleetGovernor fleetGovernor, BotFleet fleet,
                                             com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new SimStateReporter(jdbc, governor, fleetGovernor, fleet, objectMapper, NODE_ID);
    }

    @Bean
    public BotFleet botFleet(SimProperties props, BotSeeder seeder, OpsGovernor governor,
                             BotFleetGovernor fleetGovernor, JdbcClient jdbc) {
        return new BotFleet(props, seeder, governor, fleetGovernor,
                new DbRunOrdinalSource(jdbc));
    }

    @Bean
    public SmartLifecycle botFleetLifecycle(BotFleet fleet) {
        return new SmartLifecycle() {
            @Override
            public void start() {
                fleet.start();
            }

            @Override
            public void stop() {
                fleet.stop();
            }

            @Override
            public boolean isRunning() {
                return fleet.isRunning();
            }

            @Override
            public int getPhase() {
                // 웹 서버·엔진 수명주기(기본 phase)보다 늦게 시작하고 먼저 멈춘다.
                return Integer.MAX_VALUE;
            }
        };
    }
}

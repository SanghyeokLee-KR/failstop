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
@EnableConfigurationProperties(SimProperties.class)
public class SimConfig {

    @Bean
    public BotSeeder botSeeder(JdbcClient jdbc, AccountBootstrap bootstrap, DemoKeySeeder keys) {
        return new BotSeeder(jdbc, bootstrap, keys);
    }

    @Bean
    public OpsGovernor opsGovernor(SimProperties props) {
        return new OpsGovernor(props.targetOps());
    }

    @Bean
    public BotFleetGovernor botFleetGovernor() {
        return BotFleetGovernor.defaults();
    }

    @Bean
    public BotFleet botFleet(SimProperties props, BotSeeder seeder, OpsGovernor governor,
                             BotFleetGovernor fleetGovernor) {
        return new BotFleet(props, seeder, governor, fleetGovernor);
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

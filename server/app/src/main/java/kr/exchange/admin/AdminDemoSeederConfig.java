package kr.exchange.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 데모 운영자 시드. dev 프로파일 + 관리 평면 활성일 때만 동작한다. 2인 승인 데모를 위해
 * 서로 다른 두 운영자(op-01·op-02)를 등록한다. operator_id는 데모 mTLS 인증서 CN과 정합한다.
 * 운영 배포에는 적용되지 않는다(운영 운영자는 운영 PKI·프로비저닝 소관).
 */
@Configuration
@Profile("dev")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class AdminDemoSeederConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminDemoSeederConfig.class);

    @Bean
    public SmartLifecycle adminDemoSeederLifecycle(AdminOperatorSeeder seeder) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                seeder.ensureOperator("op-01", "데모 운영자 1", Set.of(AdminRole.SUPER));
                seeder.ensureOperator("op-02", "데모 운영자 2", Set.of(AdminRole.SUPER));
                running = true;
                log.info("관리 데모 운영자 시드 완료: op-01, op-02 (mTLS CN 정합)");
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return 0;
            }
        };
    }
}

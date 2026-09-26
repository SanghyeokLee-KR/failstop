package kr.exchange.admin;

import kr.exchange.engine.WriteAvailability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;

/**
 * 데모 운영자 시드. dev 프로파일 + 관리 평면 활성일 때만 동작한다. 데모 mTLS 인증서 CN과
 * 정합하는 두 운영자(op-01·op-02)를 등록한다. 승인은 1인 승인이라 한 운영자가 발급과 사용을
 * 겸할 수 있다(ADR 0019).
 * 운영 배포에는 적용되지 않는다(운영 운영자는 운영 PKI·프로비저닝 소관).
 */
@Configuration
@Profile("dev")
@ConditionalOnProperty(prefix = "exchange.admin", name = "enabled", havingValue = "true")
public class AdminDemoSeederConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminDemoSeederConfig.class);
    // DevConfig 와 같다. 거래 일시 중단 동안 재기동하면 시드를 재개 뒤로 미룬다(Patroni 설계 12절 '기동 중 쓰기').
    private static final Duration STARTUP_WRITE_WAIT = Duration.ofSeconds(15);

    @Bean
    public SmartLifecycle adminDemoSeederLifecycle(AdminOperatorSeeder seeder, WriteAvailability writes) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                try {
                    writes.runWhenActive("관리 데모 운영자 시드", STARTUP_WRITE_WAIT, () -> {
                        seeder.ensureOperator("op-01", "데모 운영자 1", Set.of(AdminRole.SUPER));
                        seeder.ensureOperator("op-02", "데모 운영자 2", Set.of(AdminRole.SUPER));
                        log.info("관리 데모 운영자 시드 완료: op-01, op-02 (mTLS CN 정합)");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("관리 데모 운영자 시드가 쓰기 재개를 기다리다 인터럽트됨", e);
                }
                running = true;
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

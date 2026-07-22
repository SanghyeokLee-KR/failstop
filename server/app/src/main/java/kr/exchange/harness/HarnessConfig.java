package kr.exchange.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 하네스 검증 배선. exchange.harness.enabled=true 기동(격리 카오스·데모 토폴로지)에서만
 * 활성화된다. 기본(dev·통합테스트)에서는 비활성이라 검증기 스케줄이 테스트에 개입하지 않는다.
 */
@Configuration
@ConditionalOnProperty(prefix = "exchange.harness", name = "enabled", havingValue = "true")
@EnableScheduling
public class HarnessConfig {

    @Bean
    public InvariantVerifier invariantVerifier(JdbcClient jdbcClient,
                                               TransactionTemplate transactionTemplate,
                                               ObjectMapper objectMapper,
                                               MeterRegistry meterRegistry) {
        return new InvariantVerifier(jdbcClient, transactionTemplate, objectMapper, meterRegistry);
    }

    @Bean
    public FailoverRecorder failoverRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        return new FailoverRecorder(jdbcClient, objectMapper);
    }

    @Bean
    public InvariantScheduler invariantScheduler(InvariantVerifier invariantVerifier) {
        return new InvariantScheduler(invariantVerifier);
    }
}

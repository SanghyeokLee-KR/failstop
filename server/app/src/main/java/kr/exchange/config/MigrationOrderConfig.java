package kr.exchange.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 앱이 Flyway 를 켜는 배포(로컬 카오스 스택의 chaos-api-1, 통합 시험)에서 migrate() 전에 적용 순서를 점검한다.
 * Flyway 를 끄는 배포에서는 이 전략이 불리지 않는다.
 */
@Configuration
public class MigrationOrderConfig {

    @Bean
    public FlywayMigrationStrategy migrationOrderGuard() {
        return flyway -> {
            MigrationOrderGuard.check(flyway);
            flyway.migrate();
        };
    }
}

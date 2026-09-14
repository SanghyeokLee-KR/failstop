package kr.exchange.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * append 전용 풀의 최종 설정이 프로파일과 무관하게 같다(ADR 0022 4-1). 코드 기본값에 yml 바인딩을 얹고
 * Hikari 검증까지 거친 값을 본다. Hikari 는 10초 미만 idleTimeout 을 조용히 기본값 10분으로 되돌린다.
 */
class AppendPoolSettingsTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    @Import(LeaseDataSourceConfig.class)
    static class Pools {
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "bots", "dev,admin", "dev,chaos-monkey"})
    void appendPoolCoversMarketLocksAndReturnsIdleConnections(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Pools.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles.split(","))
                // 연결하지 않는다. 풀은 첫 getConnection 에서 뜨고 이 시험은 설정만 본다.
                .properties("spring.datasource.url=jdbc:postgresql://localhost:5432/pool-settings")
                .run()) {
            HikariDataSource append = context.getBean("appendDataSource", HikariDataSource.class);
            append.validate();
            assertEquals(14, append.getMaximumPoolSize(), profiles);
            assertEquals(2, append.getMinimumIdle(), profiles);
            assertEquals(10_000, append.getIdleTimeout(), profiles);
            assertEquals(2_000, append.getConnectionTimeout(), profiles);
        }
    }
}

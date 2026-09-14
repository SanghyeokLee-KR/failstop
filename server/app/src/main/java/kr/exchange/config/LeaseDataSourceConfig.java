package kr.exchange.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 리더 리스 하트비트 전용 DataSource 분리(DB 설계 §5.4·§6.6).
 *
 * 요청 트래픽용 기본 풀이 pg 지연·부하 급증으로 포화되면 리스 renew가 커넥션을 얻지 못해
 * TTL 내 갱신에 실패하고, 정당 단독 리더가 스스로 페일오버한다(하네스 검증 §3 E1 실측:
 * downstream latency + 무제한 봇 부하가 HikariCP를 고갈시켜 하트비트를 굶김). renew·승격
 * 시도 UPDATE를 소형 전용 풀로 격리해 요청 풀 포화와 무관하게 갱신이 성립하게 한다.
 *
 * leaseDataSource는 @Primary가 아니다. 기본 풀이 @Primary를 유지하므로 JdbcClient·
 * TransactionManager·Flyway 자동배선과 저널·정산·조회 전 경로는 불변이다. 리스 경로만
 * 전용 풀을 탄다(EngineConfig.leaderLeaseService에서 배선).
 */
@Configuration(proxyBeanMethods = false)
public class LeaseDataSourceConfig {

    // 기본 요청 풀. leaseDataSource(별도 DataSource 빈) 도입으로 DataSourceAutoConfiguration의
    // 풀 자동구성이 back-off 하므로 기본 풀을 명시 정의한다. spring.datasource(.hikari) 계약을
    // 그대로 바인딩해 dev·prod·카오스 오버라이드(SPRING_DATASOURCE_*)를 보존한다.
    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    // 접수 저널 append 전용 풀(ADR 0022 0단계 (b) 후속, 4-1). 요청 풀과 나눠 조회 폭주로 기본 풀이
    // 포화돼도 접수가 커넥션을 기다리지 않게 한다. 리스 풀을 나눈 사유(v2.18)와 같은 성질이다.
    //
    // 최대 14는 마켓 수 12에 여유 2를 더한 값이다. 접수 락이 마켓별이라 append 가 마켓 수만큼 동시에
    // 커넥션을 쥔다. 여유는 커넥션이 끊긴 순간 새로 만드는 동안 접수가 막히지 않게 하는 몫이다.
    // 모자라 풀 대기가 connectionTimeout 을 넘으면 트랜잭션 시작이 실패한다(ApiExceptionHandler 가 503).
    //
    // 최소 유휴 2와 idleTimeout 10초. 강등된 노드가 부하 때 늘린 연결을 빨리 반납해 max_connections 를
    // 붙들지 않게 한다. Hikari 는 10초 미만을 기본값 10분으로 되돌리고, 회수는 30초 주기 하우스키핑이
    // 하므로 유휴가 된 뒤 반납까지 최대 약 40초다. yml 값이 있으면 그것이 이긴다(바인딩이 반환 후 적용).
    @Bean
    @ConfigurationProperties("exchange.engine.append-datasource.hikari")
    public HikariDataSource appendDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        ds.setPoolName("exchange-append");
        ds.setMaximumPoolSize(14);
        ds.setMinimumIdle(2);
        ds.setIdleTimeout(10_000);
        ds.setConnectionTimeout(2000);
        ds.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return ds;
    }

    // 리스 전용 소형 풀. 접속 좌표는 기본 풀과 같은 원천을 쓰고 풀 크기·타임아웃·세션 init만
    // 별도 지정한다. 코드 기본값은 프로파일에 블록이 없어도 소형·UTC로 뜨게 하는 안전망이고,
    // yml 값이 있으면 그것이 이긴다(바인딩이 반환 후 적용). statement_timeout은 걸지 않는다.
    // 단일 행 renew가 슬로 윈도에서 취소되면 리더십을 잃는다.
    @Bean
    @ConfigurationProperties("exchange.engine.lease-datasource.hikari")
    public HikariDataSource leaseDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        ds.setPoolName("exchange-lease");
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(2000);
        ds.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return ds;
    }
}

package kr.exchange.dev;

import kr.exchange.api.auth.DemoUserProvisioner;
import kr.exchange.ledger.AccountBootstrap;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * dev 활성 기동 전용 배선. 로컬 브라우저 연동(REST CORS)과 초기 데이터 시드를 담는다.
 * 활성 프로파일에 dev가 명시된 기동에서만 켜지고(DevProfileActiveCondition), 운영
 * 프로파일과 통합테스트(기본 프로파일 dev)에는 적용되지 않는다.
 */
@Configuration
@Conditional(DevProfileActiveCondition.class)
public class DevConfig {

    /**
     * 브라우저 localhost:3000 → API 8080 허용(dev 한정). 자격증명 불필요.
     * order 0으로 인증 필터(order 10)보다 먼저 돌아 OPTIONS 프리플라이트를 단락시킨다.
     * WS(/ws/v1)는 setAllowedOriginPatterns("*")로 이미 열려 있어 여기서 다루지 않는다.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> devCorsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("http://localhost:3000"));
        cfg.setAllowedMethods(List.of("GET", "POST", "DELETE"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cfg);
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(0);
        return registration;
    }

    /**
     * 시드는 봇 플릿·엔진·웹 서버(전부 SmartLifecycle phase MAX_VALUE 부근)보다 먼저
     * 끝나야 한다. 봇 플릿 start가 마켓 메타(GET /markets)를 읽고 계정을 시드하기 때문이다.
     * phase 0으로 두면 상위 phase 라이프사이클 전에 시작하고, start는 Flyway 이행 완료
     * 후(finishRefresh) 실행된다.
     */
    @Bean
    public SmartLifecycle devDataSeederLifecycle(AccountBootstrap bootstrap,
                                                 DemoUserProvisioner demoUsers) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                DevDataSeeder.seed(bootstrap, demoUsers);
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

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
     * 브라우저 localhost:3000 → API 8080 허용(dev 한정). web authPost가 rt 쿠키를
     * credentials:include로 실으므로 allowCredentials=true + 명시 오리진이 필요하다(와일드카드 불가).
     * 메서드·노출 헤더는 ProdCorsConfig와 단일 계약으로 정렬한다(커뮤니티 PATCH 수정, 레이트리밋 헤더).
     * order 0으로 인증 필터(order 10)보다 먼저 돌아 OPTIONS 프리플라이트를 단락시킨다.
     * WS(/ws/v1)는 setAllowedOriginPatterns("*")로 이미 열려 있어 여기서 다루지 않는다.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> devCorsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("http://localhost:3000"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // 크로스오리진에서 기본 노출되지 않는 레이트리밋·상관추적 헤더를 web 폴백이 읽도록 노출한다.
        cfg.setExposedHeaders(List.of("Retry-After",
                "X-RateLimit-Limit-account", "X-RateLimit-Limit-admin",
                "X-RateLimit-Remaining-account", "X-RateLimit-Remaining-admin",
                "X-RateLimit-Reset", "X-Request-Id"));
        cfg.setAllowCredentials(true);
        // 프리플라이트 응답 캐시. 없으면 dev 브라우저가 변이 요청마다 OPTIONS를 반복한다.
        cfg.setMaxAge(1800L);
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

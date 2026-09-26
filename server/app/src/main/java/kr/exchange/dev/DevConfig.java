package kr.exchange.dev;

import kr.exchange.api.auth.DemoUserProvisioner;
import kr.exchange.engine.WriteAvailability;
import kr.exchange.ledger.AccountBootstrap;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Duration;
import java.util.List;

/**
 * dev 활성 기동 전용 배선. 로컬 브라우저 연동(REST CORS)과 초기 데이터 시드를 담는다.
 * 활성 프로파일에 dev가 명시된 기동에서만 켜지고(DevProfileActiveCondition), 운영
 * 프로파일과 통합테스트(기본 프로파일 dev)에는 적용되지 않는다.
 */
@Configuration
@Conditional(DevProfileActiveCondition.class)
public class DevConfig {

    // 기동 직후 탐지기가 첫 확인 탐지를 마치는 시간(두 탐지 주기)보다 넉넉하다. 이보다 길면 거래 일시 중단으로 보고 재개 뒤로 미룬다.
    private static final Duration STARTUP_WRITE_WAIT = Duration.ofSeconds(15);

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
        // Idempotency-Key는 게시판 쓰기 멱등 계약(게시판_설계 4.2) 헤더다. 누락 시 브라우저
        // 프리플라이트가 거부돼 크로스오리진 글·댓글 작성이 전부 실패한다(비브라우저 테스트는 미검출).
        // X-Operator-Cn은 관리 평면 신원 헤더다(exchange.admin.mtls.forward-header). dev는
        // mTLS 종단 게이트웨이가 없어 브라우저가 직접 싣는다. 누락 시 프리플라이트가 거부돼
        // 관리 콘솔 세션 발급이 전부 실패한다.
        // X-Exchange-Market 은 주문 쓰기의 마켓 힌트(API 설계 §2-5-d)다. prod 와 같은 계약이다.
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                "X-Operator-Cn", "X-Exchange-Market"));
        // 크로스오리진에서 기본 노출되지 않는 레이트리밋·상관추적 헤더를 web 폴백이 읽도록 노출한다. X-Exchange-Route 는
        // 소유 아님 503 의 표식이다(API 설계 §2-5-d).
        cfg.setExposedHeaders(List.of("Retry-After",
                "X-RateLimit-Limit-account", "X-RateLimit-Limit-admin",
                "X-RateLimit-Remaining-account", "X-RateLimit-Remaining-admin",
                "X-RateLimit-Reset", "X-Request-Id", "X-Exchange-Route"));
        cfg.setAllowCredentials(true);
        // 프리플라이트 응답 캐시. 없으면 dev 브라우저가 변이 요청마다 OPTIONS를 반복한다.
        cfg.setMaxAge(1800L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cfg);
        // 관리 평면도 같은 계약으로 연다. 이 경로가 실제로 존재하는 것은 admin-local 프로파일을
        // 명시 활성한 기동뿐이고(exchange.admin.enabled), 없는 기동에서는 매핑만 있고 가리킬
        // 핸들러가 없어 무해하다. 운영은 이 클래스 자체가 로드되지 않는다
        source.registerCorsConfiguration("/admin/v1/**", cfg);
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
     *
     * 시드는 쓰기라 탐지기를 먼저 본다(Patroni 설계 12절 '기동 중 쓰기'). 거래 일시 중단 동안 재기동하면 재개 뒤로 미룬다. 기다리면
     * 웹 서버(더 높은 phase)가 뜨지 않아 이 노드가 조회도 내지 못한다. 시드는 멱등이라 이미 시드된 DB 에서는 미뤄도 잃는 것이 없다.
     */
    @Bean
    public SmartLifecycle devDataSeederLifecycle(AccountBootstrap bootstrap,
                                                 DemoUserProvisioner demoUsers,
                                                 WriteAvailability writes) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                try {
                    writes.runWhenActive("dev 시드", STARTUP_WRITE_WAIT,
                            () -> DevDataSeeder.seed(bootstrap, demoUsers));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("dev 시드가 쓰기 재개를 기다리다 인터럽트됨", e);
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

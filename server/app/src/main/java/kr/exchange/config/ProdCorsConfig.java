package kr.exchange.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 운영 REST CORS(백엔드_API_설계 §2-7). dev DevConfig와 별개로 prod 프로파일에서만 켠다.
 *
 * dev와 차이: 회원 인증이 rt 쿠키를 credentials:include로 실어 allowCredentials=true가 필요하고,
 * 커뮤니티 수정 PATCH·관리 PUT/PATCH가 있어 허용 메서드를 넓힌다. 오리진은 하드코딩 대신
 * exchange.cors.allowed-origins 화이트리스트로 주입하며, 미지정 시 기동을 중단한다(와일드카드 금지).
 *
 * order 0으로 인증 필터(order 10)보다 먼저 돌아 OPTIONS 프리플라이트를 단락시킨다.
 */
@Configuration
@Profile("prod")
public class ProdCorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> prodCorsFilter(
            @Value("${exchange.cors.allowed-origins:}") List<String> allowedOrigins) {
        if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(o -> o == null || o.isBlank())) {
            throw new IllegalStateException(
                    "운영 CORS 오리진(exchange.cors.allowed-origins) 미지정. 화이트리스트를 명시하라.");
        }
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Idempotency-Key는 게시판 쓰기 멱등 계약(게시판_설계 4.2) 헤더다. 누락 시 브라우저
        // 프리플라이트가 거부돼 크로스오리진 글·댓글 작성이 전부 실패한다(비브라우저 테스트는 미검출).
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        // 크로스오리진에서 기본 노출되지 않는 레이트리밋·상관추적 헤더를 web 폴백이 읽도록 노출한다.
        cfg.setExposedHeaders(List.of("Retry-After",
                "X-RateLimit-Limit-account", "X-RateLimit-Limit-admin",
                "X-RateLimit-Remaining-account", "X-RateLimit-Remaining-admin",
                "X-RateLimit-Reset", "X-Request-Id"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(1800L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", cfg);
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(0);
        return registration;
    }
}

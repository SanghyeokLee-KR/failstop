package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import kr.exchange.api.common.AccountRateLimiter;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.common.PublicRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 실제 서블릿 관측 필터가 공개 조회의 선행 429를 한 번만 집계하는지 확인한다. */
@SpringBootTest(classes = PublicRequestObservationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=observation-convention-test", "spring.jmx.enabled=false"})
@AutoConfigureObservability
class PublicRequestObservationIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MeterRegistry registry;

    @Test
    void earlyRejectionsKeepRouteTemplatesWithoutDuplicatingHttpTimers() {
        assertEquals(200, rest.getForEntity("/api/v1/candles/1m?market=private-market", String.class)
                .getStatusCode().value());
        assertEquals(429, rest.getForEntity("/api/v1/trades?cursor=private-token", String.class)
                .getStatusCode().value());
        assertEquals(429, rest.getForEntity("/api/v1/candles/5m?market=private-market", String.class)
                .getStatusCode().value());
        assertEquals(429, rest.getForEntity("/api/v1/trades/private-id", String.class)
                .getStatusCode().value());
        assertEquals(200, rest.getForEntity("/health-check/private-id", String.class)
                .getStatusCode().value());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertTimer("/api/v1/candles/{unit}", "200", 1);
            assertTimer("/api/v1/candles/{unit}", "429", 1);
            assertTimer("/api/v1/trades", "429", 1);
            assertTimer("UNKNOWN", "429", 1);
            assertTimer("/health-check/{id}", "200", 1);
            assertEquals(5, registry.find("http.server.requests").timers().stream().mapToLong(Timer::count).sum());
        });
        registry.find("http.server.requests").timers().forEach(timer ->
                timer.getId().getTags().forEach(tag -> assertFalse(tag.getValue().contains("private-"))));
    }

    private void assertTimer(String uri, String status, long count) {
        Timer timer = registry.find("http.server.requests").tags("uri", uri, "status", status, "method", "GET").timer();
        assertNotNull(timer, uri + " / " + status);
        assertEquals(count, timer.count());
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({PublicRequestObservationConvention.class, Endpoints.class})
    static class TestApplication {
        @Bean
        FilterRegistrationBean<PublicRateLimitFilter> publicFilter(ObjectMapper mapper) {
            AccountRateLimiter limiter = new AccountRateLimiter(2, Duration.ofDays(1).toMillis(), "ip", "public");
            FilterRegistrationBean<PublicRateLimitFilter> registration = new FilterRegistrationBean<>(
                    new PublicRateLimitFilter(limiter, new ErrorRenderer(mapper)));
            registration.setOrder(7);
            return registration;
        }
    }

    @RestController
    @TestComponent
    static class Endpoints {
        @GetMapping({"/api/v1/candles/{unit}", "/health-check/{id}"})
        String read() {
            return "ok";
        }
    }
}

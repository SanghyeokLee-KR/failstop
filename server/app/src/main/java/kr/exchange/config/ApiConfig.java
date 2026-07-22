package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.auth.ApiAuthFilter;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.ApiKeyDirectory;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.auth.DemoSessionService;
import kr.exchange.api.auth.DemoUserProvisioner;
import kr.exchange.api.auth.NonceStore;
import kr.exchange.api.auth.SecretCipher;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.ws.ChannelSequences;
import kr.exchange.api.ws.MarketDataPublisher;
import kr.exchange.api.ws.OrderbookSnapshotOnSubscribe;
import kr.exchange.api.ws.StompAuthInterceptor;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.ledger.AccountBootstrap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.Map;

/** REST/WS 게이트웨이 배선(M1-4). 도메인 계층은 스프링 어노테이션 없이 여기서만 결선한다. */
@Configuration
public class ApiConfig {

    @Bean
    public SecretCipher secretCipher(
            @Value("${exchange.auth.master-key-base64:}") String masterKeyBase64) {
        return new SecretCipher(masterKeyBase64);
    }

    @Bean
    public NonceStore nonceStore() {
        return new NonceStore();
    }

    @Bean
    public ApiKeyDirectory apiKeyDirectory(JdbcClient jdbc) {
        return new ApiKeyDirectory(jdbc);
    }

    @Bean
    public DemoSessionService demoSessionService() {
        return new DemoSessionService();
    }

    @Bean
    public DemoUserProvisioner demoUserProvisioner(
            JdbcClient jdbc, AccountBootstrap bootstrap,
            @Value("${exchange.demo.email:demo@exchange.local}") String demoEmail) {
        // 초기 입금액은 시드된 자산에 한해 적용된다. 미시드 심볼은 무시.
        Map<String, BigDecimal> deposits = Map.of(
                "KRW", new BigDecimal("100000000"),
                "BTC", new BigDecimal("10"),
                "ETH", new BigDecimal("100"));
        return new DemoUserProvisioner(jdbc, bootstrap, demoEmail, deposits);
    }

    @Bean
    public ApiAuthService apiAuthService(ApiKeyDirectory keys, SecretCipher cipher,
                                         NonceStore nonces, ObjectMapper objectMapper,
                                         DemoSessionService demoSessions) {
        return new ApiAuthService(keys, cipher, nonces, objectMapper, demoSessions);
    }

    @Bean
    public ErrorRenderer errorRenderer(ObjectMapper objectMapper) {
        return new ErrorRenderer(objectMapper);
    }

    @Bean
    public FilterRegistrationBean<ApiAuthFilter> apiAuthFilter(ApiAuthService authService,
                                                               ErrorRenderer errorRenderer) {
        FilterRegistrationBean<ApiAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiAuthFilter(authService, errorRenderer));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(10);
        return registration;
    }

    @Bean
    public DemoKeySeeder demoKeySeeder(JdbcClient jdbc, SecretCipher cipher) {
        return new DemoKeySeeder(jdbc, cipher);
    }

    @Bean
    public AccountBootstrap accountBootstrap(JdbcClient jdbc) {
        return new AccountBootstrap(jdbc);
    }

    @Bean
    public MarketDirectory marketDirectory(JdbcClient jdbc) {
        return new MarketDirectory(jdbc);
    }

    @Bean
    public MarketDataService marketDataService(JdbcClient jdbc) {
        return new MarketDataService(jdbc);
    }

    @Bean
    public OrderQueryService orderQueryService(JdbcClient jdbc) {
        return new OrderQueryService(jdbc);
    }

    @Bean
    public OrderGateway orderGateway(JdbcClient jdbc, EngineBridge bridge,
                                     MarketDirectory markets, OrderQueryService orders) {
        return new OrderGateway(jdbc, bridge, markets, orders);
    }

    @Bean
    public EpochSource epochSource(LeaderLeaseService lease, JdbcClient jdbc) {
        return new EpochSource(lease, jdbc);
    }

    @Bean
    public ChannelSequences channelSequences() {
        return new ChannelSequences();
    }

    @Bean
    public MarketDataPublisher marketDataPublisher(SimpMessagingTemplate messaging,
                                                   JdbcClient jdbc, MarketDirectory markets,
                                                   MarketDataService marketData,
                                                   OrderQueryService orders,
                                                   ChannelSequences sequences,
                                                   EpochSource epochSource) {
        return new MarketDataPublisher(messaging, jdbc, markets, marketData, orders,
                sequences, epochSource);
    }

    @Bean
    public StompAuthInterceptor stompAuthInterceptor(ApiAuthService authService,
                                                     ApplicationContext context) {
        // clientOutboundChannel은 WS 브로커 구성이 만든다. 순환 참조 방지를 위해 지연 조회한다.
        return new StompAuthInterceptor(authService,
                () -> context.getBean("clientOutboundChannel", MessageChannel.class));
    }

    @Bean
    public OrderbookSnapshotOnSubscribe orderbookSnapshotOnSubscribe(
            MarketDirectory markets, MarketDataService marketData, ChannelSequences sequences,
            EpochSource epochSource, ObjectMapper objectMapper, ApplicationContext context) {
        return new OrderbookSnapshotOnSubscribe(markets, marketData, sequences, epochSource,
                objectMapper, () -> context.getBean("clientOutboundChannel", MessageChannel.class));
    }
}

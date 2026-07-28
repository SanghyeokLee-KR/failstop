package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.api.auth.ApiAuthFilter;
import kr.exchange.api.auth.ApiAuthService;
import kr.exchange.api.auth.ApiKeyDirectory;
import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.api.auth.DemoSessionService;
import kr.exchange.api.auth.DemoUserProvisioner;
import kr.exchange.api.auth.NonceStore;
import kr.exchange.api.auth.SecretCipher;
import kr.exchange.api.common.AdmissionControl;
import kr.exchange.api.common.AdmissionFilter;
import kr.exchange.api.common.AdmissionLimits;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.common.ErrorRenderer;
import kr.exchange.api.community.CommunityAuthenticator;
import kr.exchange.api.community.CommunityRankService;
import kr.exchange.api.community.CommunityService;
import kr.exchange.api.member.MemberAuthService;
import kr.exchange.api.member.MemberSessionService;
import kr.exchange.api.member.PasswordHasher;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.ws.CandleSubscriptions;
import kr.exchange.api.ws.ChannelSequences;
import kr.exchange.api.ws.MarketDataPublisher;
import kr.exchange.api.ws.OrderbookSnapshotOnSubscribe;
import kr.exchange.api.ws.StompAuthInterceptor;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.ledger.AccountBootstrap;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Map;

/** REST/WS 게이트웨이 배선(M1-4). 도메인 계층은 스프링 어노테이션 없이 여기서만 결선한다. */
@Configuration
public class ApiConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiConfig.class);

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
    public MemberSessionService memberSessionService(
            ObjectMapper objectMapper,
            @Value("${exchange.member.session-seed-base64:}") String sessionSeedBase64) {
        return new MemberSessionService(objectMapper, sessionSeedBase64);
    }

    @Bean
    public PasswordHasher passwordHasher(
            @Value("${exchange.member.password-pepper-base64:}") String pepperBase64) {
        return new PasswordHasher(pepperBase64);
    }

    @Bean
    public MemberAuthService memberAuthService(JdbcClient jdbc, PlatformTransactionManager txManager,
                                               PasswordHasher hasher, MemberSessionService sessions,
                                               AccountBootstrap bootstrap) {
        return new MemberAuthService(jdbc, new TransactionTemplate(txManager), hasher, sessions,
                bootstrap);
    }

    @Bean
    public ApiAuthService apiAuthService(ApiKeyDirectory keys, SecretCipher cipher,
                                         NonceStore nonces, ObjectMapper objectMapper,
                                         DemoSessionService demoSessions,
                                         MemberSessionService memberSessions) {
        return new ApiAuthService(keys, cipher, nonces, objectMapper, demoSessions, memberSessions);
    }

    @Bean
    public ErrorRenderer errorRenderer(ObjectMapper objectMapper) {
        return new ErrorRenderer(objectMapper);
    }

    /**
     * 수용 제한 상한 배분. 요청 풀 크기에서 유도한다(AdmissionLimits). 카오스 스택이 풀을
     * SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE로 덮으므로 유도가 곧 배포별 추종이다.
     * exchange.admission.order-in-flight·query-in-flight로 각각 명시 고정할 수 있고 0 이하면 유도값.
     *
     * 기동 로그를 남기는 이유: 유도 결과는 배포마다 다른데 코드에도 설정에도 그 숫자가 없다.
     * 라이브에서 실제 적용값을 확인할 단일 지점이 필요하다.
     */
    @Bean
    public AdmissionLimits admissionLimits(
            @Qualifier("dataSource") DataSource requestDataSource,
            @Value("${exchange.admission.order-in-flight:0}") int configuredOrder,
            @Value("${exchange.admission.query-in-flight:0}") int configuredQuery) {
        int poolSize = requestDataSource instanceof HikariDataSource hikari
                ? hikari.getMaximumPoolSize() : 0;
        AdmissionLimits limits = AdmissionLimits.resolve(poolSize, configuredOrder, configuredQuery);
        log.info("수용 제한 상한 해석: 쓰기 {} / 조회 {} / 예비 {} (요청 풀 {})",
                limits.write(), limits.query(), limits.reserved(), poolSize);
        return limits;
    }

    @Bean
    public AdmissionControl orderAdmissionControl(AdmissionLimits limits, MeterRegistry registry) {
        return new AdmissionControl("order", limits.write(), registry);
    }

    @Bean
    public AdmissionControl queryAdmissionControl(AdmissionLimits limits, MeterRegistry registry) {
        return new AdmissionControl("query", limits.query(), registry);
    }

    /**
     * 수용 게이트는 인증(order 10)보다 앞이고 바디 상한(order 5)보다 뒤다. 인증 뒤에서 막으면
     * api_keys 조회로 이미 DB를 탄 뒤라 실효가 없고, 바디 상한 앞이면 64KB 초과 요청이 슬롯을
     * 소비한다. ApiAuthFilter와 별도 필터인 이유는 AdmissionFilter 클래스 주석 참조.
     */
    @Bean
    public FilterRegistrationBean<AdmissionFilter> admissionFilter(
            @Qualifier("orderAdmissionControl") AdmissionControl orderAdmission,
            @Qualifier("queryAdmissionControl") AdmissionControl queryAdmission,
            ErrorRenderer errorRenderer) {
        FilterRegistrationBean<AdmissionFilter> registration = new FilterRegistrationBean<>(
                new AdmissionFilter(orderAdmission, queryAdmission, errorRenderer));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(6);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiAuthFilter> apiAuthFilter(ApiAuthService authService,
                                                               ErrorRenderer errorRenderer,
                                                               LeaderWriteProgress writeProgress) {
        FilterRegistrationBean<ApiAuthFilter> registration = new FilterRegistrationBean<>(
                new ApiAuthFilter(authService, errorRenderer, writeProgress));
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
    public CommunityRankService communityRankService(JdbcClient jdbc, MarketDirectory markets) {
        return new CommunityRankService(jdbc, markets);
    }

    @Bean
    public CommunityService communityService(
            JdbcClient jdbc, PlatformTransactionManager txManager, MarketDirectory markets,
            CommunityRankService ranks,
            @Value("${exchange.community.auto-hide-threshold:10}") int autoHideThreshold) {
        return new CommunityService(jdbc, new TransactionTemplate(txManager), markets, ranks,
                autoHideThreshold);
    }

    @Bean
    public CommunityAuthenticator communityAuthenticator(MemberSessionService memberSessions,
                                                         ObjectMapper objectMapper) {
        return new CommunityAuthenticator(memberSessions, objectMapper);
    }

    @Bean
    public OrderQueryService orderQueryService(JdbcClient jdbc) {
        return new OrderQueryService(jdbc);
    }

    // §2-5 '계좌/주문 조회' 단일 버킷(200토큰/10s, weight 2). OrderController(/open·/closed)와
    // AccountController(/accounts)가 공유해 이 범주가 하나의 200토큰 예산을 쓰게 한다.
    @Bean
    public kr.exchange.api.common.AccountRateLimiter accountQueryRateLimiter() {
        return new kr.exchange.api.common.AccountRateLimiter(200, 10_000, "account", "query");
    }

    @Bean
    public OrderGateway orderGateway(JdbcClient jdbc, EngineBridge bridge,
                                     MarketDirectory markets, OrderQueryService orders) {
        return new OrderGateway(jdbc, bridge, markets, orders);
    }

    @Bean
    public kr.exchange.api.order.OrderRejectionMetrics orderRejectionMetrics(
            MeterRegistry meterRegistry) {
        return new kr.exchange.api.order.OrderRejectionMetrics(meterRegistry);
    }

    @Bean
    public kr.exchange.api.ws.WsSessionMetrics wsSessionMetrics(MeterRegistry meterRegistry) {
        return new kr.exchange.api.ws.WsSessionMetrics(meterRegistry);
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
    public CandleSubscriptions candleSubscriptions() {
        return new CandleSubscriptions();
    }

    @Bean
    public MarketDataPublisher marketDataPublisher(SimpMessagingTemplate messaging,
                                                   JdbcClient jdbc, MarketDirectory markets,
                                                   MarketDataService marketData,
                                                   OrderQueryService orders,
                                                   ChannelSequences sequences,
                                                   EpochSource epochSource,
                                                   CandleSubscriptions candleSubscriptions,
                                                   MeterRegistry meterRegistry) {
        return new MarketDataPublisher(messaging, jdbc, markets, marketData, orders,
                sequences, epochSource, candleSubscriptions, meterRegistry);
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

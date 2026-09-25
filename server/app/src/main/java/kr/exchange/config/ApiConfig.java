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
import kr.exchange.api.support.SupportService;
import kr.exchange.api.transfer.TransferService;
import kr.exchange.api.member.MemberSessionService;
import kr.exchange.api.member.PasswordHasher;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.order.AccountRepository;
import kr.exchange.api.order.MyHistoryRepository;
import kr.exchange.api.order.OrderGateway;
import kr.exchange.api.order.OrderQueryService;
import kr.exchange.api.ws.CandleSubscriptions;
import kr.exchange.api.ws.ChannelSequences;
import kr.exchange.api.ws.MarketDataPublisher;
import kr.exchange.api.ws.OrderbookSnapshotOnSubscribe;
import kr.exchange.api.ws.StompAuthInterceptor;
import kr.exchange.engine.EngineBridge;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Map;

/** REST/WS 게이트웨이 배선(M1-4). 도메인 계층은 스프링 어노테이션 없이 여기서만 결선한다. */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(CancelHandoffProperties.class)
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

    /** 트랜잭션 밖 읽기의 한 번 더 읽기와 그 계수(API 설계 §2-5-d 'DB가 흔들린 직후', 하네스 CI/CD §5-2). */
    @Bean
    public kr.exchange.journal.DbReadRetry dbReadRetry(MeterRegistry registry) {
        return new kr.exchange.journal.DbReadRetry(registry);
    }

    /** 마켓 라우팅 계수(API 설계 §2-5-d 관측). 선검사 필터, 컨트롤러, 게이트웨이가 같은 계수를 쓴다. */
    @Bean
    public kr.exchange.api.common.RoutingCounters routingCounters(MeterRegistry registry) {
        return new kr.exchange.api.common.RoutingCounters(registry);
    }

    @Bean
    public ApiKeyDirectory apiKeyDirectory(JdbcClient jdbc, kr.exchange.journal.DbReadRetry readRetry) {
        return new ApiKeyDirectory(jdbc, readRetry);
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
    public SupportService supportService(JdbcClient jdbc, PlatformTransactionManager txManager) {
        return new SupportService(jdbc, new TransactionTemplate(txManager));
    }

    @Bean
    public TransferService transferService(JdbcClient jdbc, PlatformTransactionManager txManager) {
        return new TransferService(jdbc, new TransactionTemplate(txManager));
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
     * exchange.admission.{order,query,requery}-in-flight로 각각 명시 고정할 수 있고 0 이하면 유도값.
     *
     * 기동 로그를 남기는 이유: 유도 결과는 배포마다 다른데 코드에도 설정에도 그 숫자가 없다.
     * 라이브에서 실제 적용값을 확인할 단일 지점이 필요하다.
     */
    @Bean
    public AdmissionLimits admissionLimits(
            @Qualifier("dataSource") DataSource requestDataSource,
            @Value("${exchange.admission.order-in-flight:0}") int configuredOrder,
            @Value("${exchange.admission.query-in-flight:0}") int configuredQuery,
            @Value("${exchange.admission.requery-in-flight:0}") int configuredRequery) {
        int poolSize = requestDataSource instanceof HikariDataSource hikari
                ? hikari.getMaximumPoolSize() : 0;
        AdmissionLimits limits = AdmissionLimits.resolve(poolSize, configuredOrder, configuredQuery,
                configuredRequery);
        log.info("수용 제한 상한 해석: 쓰기 {} / 조회 {} / 재조회 {} / 예비 {} (요청 풀 {})",
                limits.write(), limits.query(), limits.requery(), limits.reserved(), poolSize);
        return limits;
    }

    @Bean
    public AdmissionControl orderAdmissionControl(AdmissionLimits limits, MeterRegistry registry) {
        return new AdmissionControl("order", limits.write(),
                AdmissionControl.Shed.CAPACITY, registry);
    }

    @Bean
    public AdmissionControl queryAdmissionControl(AdmissionLimits limits, MeterRegistry registry) {
        return new AdmissionControl("query", limits.query(),
                AdmissionControl.Shed.CAPACITY, registry);
    }

    /** §7-6 수렴 경로 전용. 상한 초과를 거절이 아니라 판정 보류로 내보낸다(Shed 주석). */
    @Bean
    public AdmissionControl requeryAdmissionControl(AdmissionLimits limits, MeterRegistry registry) {
        return new AdmissionControl("requery", limits.requery(),
                AdmissionControl.Shed.REQUERY_DEFERRED, registry);
    }

    /**
     * WS 동시 연결 상한. 요청 세 버킷과 달리 요청 풀이 아니라 최대 힙에서 유도한다
     * (WsConnectionLimit). 연결은 DB 커넥션을 쥐지 않고 힙을 쥐며, 요청보다 훨씬 오래 산다.
     *
     * 기동 로그를 남기는 이유는 요청 버킷과 같다. 유도 결과가 배포마다 다른데 코드에도 설정에도
     * 그 숫자가 없다.
     */
    @Bean
    public kr.exchange.api.ws.WsConnectionLimit wsConnectionLimit(
            @Value("${exchange.admission.ws-connections:0}") int configured) {
        kr.exchange.api.ws.WsConnectionLimit limit =
                kr.exchange.api.ws.WsConnectionLimit.resolve(configured, Runtime.getRuntime().maxMemory());
        log.info("수용 제한 상한 해석: {}", limit.describe());
        return limit;
    }

    @Bean
    public AdmissionControl wsAdmissionControl(kr.exchange.api.ws.WsConnectionLimit limit,
                                               MeterRegistry registry) {
        // Shed 는 쓰지 않는다. WS 거절은 HTTP 본문이 아니라 종료 코드 1013 으로 나간다
        // (WsConnectionLimitHandler). 지표를 요청 버킷과 같은 계열에 두려고 같은 클래스를 쓴다.
        return new AdmissionControl("ws", limit.limit(),
                AdmissionControl.Shed.CAPACITY, registry);
    }

    /**
     * 수용 게이트는 인증(order 10)보다 앞이고 바디 상한(order 4)과 소유권 선검사(order 5)보다 뒤다. 인증 뒤에서 막으면
     * api_keys 조회로 이미 DB를 탄 뒤라 실효가 없고, 바디 상한 앞이면 64KB 초과 요청이 슬롯을
     * 소비한다. ApiAuthFilter와 별도 필터인 이유는 AdmissionFilter 클래스 주석 참조.
     */
    /**
     * 수용 게이트 런타임 스위치(API 설계 §2-5-a v2.41). 기본 켜짐. 끄는 것은 실험 전용이다.
     * 기동 시 값은 설정으로, 이후는 actuator `admission` 엔드포인트로 바꾼다.
     */
    @Bean
    public kr.exchange.api.common.AdmissionGate admissionGate(
            @Value("${exchange.admission.enabled:true}") boolean enabled) {
        return new kr.exchange.api.common.AdmissionGate(enabled);
    }

    @Bean
    public kr.exchange.api.common.AdmissionEndpoint admissionEndpoint(
            kr.exchange.api.common.AdmissionGate gate,
            @Qualifier("orderAdmissionControl") AdmissionControl orderAdmission,
            @Qualifier("queryAdmissionControl") AdmissionControl queryAdmission,
            @Qualifier("requeryAdmissionControl") AdmissionControl requeryAdmission,
            @Qualifier("wsAdmissionControl") AdmissionControl wsAdmission) {
        return new kr.exchange.api.common.AdmissionEndpoint(gate, orderAdmission, queryAdmission,
                requeryAdmission, wsAdmission);
    }

    /**
     * 소유권 선검사(API 설계 §2-5-d, S5 설계 4-1). 바디 상한(4) 뒤, 수용 제한(6)·IP 버킷(7)·인증(10) 앞이다. 소유하지 않는 노드에
     * 온 주문 쓰기를 nonce·레이트리밋·멱등 키·예약·채번을 건드리기 전에 503 으로 돌려보내고 인그레스가 다른 노드로 넘긴다.
     * 조회 역할 노드에는 걸지 않는다. 조회 역할 필터가 쓰기를 먼저 막는다. 엔진을 끈 노드에는 건다(모든 주문 쓰기를 돌려보냄).
     */
    @Bean
    @org.springframework.context.annotation.Conditional(ReadRoleConfig.NotReadRoleCondition.class)
    public FilterRegistrationBean<kr.exchange.api.common.MarketRoutingFilter> marketRoutingFilter(
            ErrorRenderer errorRenderer, MarketDirectory markets, EngineBridge bridge, ObjectMapper objectMapper,
            kr.exchange.api.common.RoutingCounters routingCounters) {
        FilterRegistrationBean<kr.exchange.api.common.MarketRoutingFilter> registration = new FilterRegistrationBean<>(
                new kr.exchange.api.common.MarketRoutingFilter(errorRenderer, markets, bridge, objectMapper,
                        routingCounters));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(5);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AdmissionFilter> admissionFilter(
            kr.exchange.api.common.AdmissionGate gate,
            @Qualifier("orderAdmissionControl") AdmissionControl orderAdmission,
            @Qualifier("queryAdmissionControl") AdmissionControl queryAdmission,
            @Qualifier("requeryAdmissionControl") AdmissionControl requeryAdmission,
            ErrorRenderer errorRenderer) {
        FilterRegistrationBean<AdmissionFilter> registration = new FilterRegistrationBean<>(
                new AdmissionFilter(gate, orderAdmission, queryAdmission, requeryAdmission,
                        errorRenderer));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(6);
        return registration;
    }

    /**
     * 공개 조회 IP 버킷(§2-5 표: 300토큰/10s, weight 1~2). 계정 버킷이 붙지 않는 무인증 평면의
     * 유일한 유량 방벽이다.
     *
     * capacity를 설정으로 여는 이유: 봇 플릿·웹이 한 호스트(또는 인그레스 1 IP)로 뭉치는 배포에서
     * 계약 기본값 300은 정상 폴링을 429로 만든다. 주문 버킷이 bots 프로파일에서만 상향하는 선례와
     * 같은 구조로, 운영·통합테스트 프로파일은 계약값 300을 그대로 쓴다.
     */
    @Bean
    public kr.exchange.api.common.AccountRateLimiter publicIpRateLimiter(
            @Value("${exchange.public.rate-limit.capacity:300}") int capacity) {
        return new kr.exchange.api.common.AccountRateLimiter(capacity, 10_000, "ip", "public");
    }

    /**
     * IP 버킷은 수용 게이트(6) 뒤·인증(10) 앞이다. 수용 거절된 요청이 토큰을 소비하면 백프레셔가
     * 정상 클라이언트의 예산을 깎고, 인증 뒤면 미인증 플러드가 이미 api_keys를 조회한 뒤가 된다.
     */
    @Bean
    public FilterRegistrationBean<kr.exchange.api.common.PublicRateLimitFilter> publicRateLimitFilter(
            @Qualifier("publicIpRateLimiter") kr.exchange.api.common.AccountRateLimiter limiter,
            ErrorRenderer errorRenderer) {
        FilterRegistrationBean<kr.exchange.api.common.PublicRateLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new kr.exchange.api.common.PublicRateLimitFilter(limiter, errorRenderer));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(7);
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

    // destroyMethod 로 갱신 스레드를 회수한다. 데몬이라 JVM 종료는 막지 않으나 컨텍스트만
    // 내렸다 올리는 테스트에서 스레드가 쌓인다.
    @Bean(destroyMethod = "close")
    public MarketDirectory marketDirectory(JdbcClient jdbc) {
        MarketDirectory directory = new MarketDirectory(jdbc);
        directory.start();
        return directory;
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

    @Bean
    public AccountRepository accountRepository(JdbcClient jdbc) {
        return new AccountRepository(jdbc);
    }

    @Bean
    public MyHistoryRepository myHistoryRepository(JdbcClient jdbc) {
        return new MyHistoryRepository(jdbc);
    }

    // §2-5 '계좌/주문 조회' 단일 버킷(200토큰/10s, weight 2). OrderController(/open·/closed)와
    // AccountController(/accounts)가 공유해 이 범주가 하나의 200토큰 예산을 쓰게 한다.
    @Bean
    public kr.exchange.api.common.AccountRateLimiter accountQueryRateLimiter() {
        return new kr.exchange.api.common.AccountRateLimiter(200, 10_000, "account", "query");
    }

    @Bean
    public OrderGateway orderGateway(JdbcClient jdbc, EngineBridge bridge,
                                     MarketDirectory markets, OrderQueryService orders,
                                     MeterRegistry meterRegistry,
                                     kr.exchange.engine.lease.LeaderProtocolBoot leaderProtocolBoot,
                                     kr.exchange.engine.PartitionPlan partitionPlan) {
        // 잔고 판정의 권위는 기동 모드가 정한다(ADR 0034 S3). P2 이상이면 append 트랜잭션의 DB 예약이 권위이고
        // in-flight 누계를 끈다. 1 로 뜬 노드는 P2 DB 에서도 안전하다(DB 예약분을 바로 푼다, OrderGateway).
        OrderGateway gateway = new OrderGateway(jdbc, bridge, markets, orders,
                leaderProtocolBoot.reservesAtIntake()
                        ? OrderGateway.ReserveMode.DB_RESERVATION : OrderGateway.ReserveMode.IN_FLIGHT);
        gateway.setPartitionPlan(partitionPlan);
        io.micrometer.core.instrument.Gauge
                .builder("exchange.order.inflight.enabled", gateway, g -> g.inflightEnabled() ? 1 : 0)
                .description("게이트웨이가 in-flight 예약 누계를 쓰는가(1) P2 로 떠 끄는가(0)")
                .register(meterRegistry);
        // 잔고 부족 거부의 출처. db 는 append 트랜잭션의 23514 다. 이중 지출 시험이 DB 경로가 돌았음을 이 값으로 보인다.
        for (String source : java.util.List.of("precheck", "db")) {
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.order.reserve.rejected", gateway, g -> g.reserveRejections(source))
                    .tag("source", source)
                    .description("주문 생성의 잔고 부족 거부 수(precheck 저널 전, db append 트랜잭션)")
                    .register(meterRegistry);
        }
        // 접수 append 의 일시 DB 오류 넷. 잠금 계획 뒤 40P01 은 결함 신호다(경보 OrderIntakeDeadlock).
        for (String state : java.util.List.of("55P03", "57014", "40P01", "40001")) {
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.order.append.transient", gateway, g -> g.appendTransient(state))
                    .tag("sqlstate", state)
                    .description("접수 append 가 일시 DB 오류로 끝난 수")
                    .register(meterRegistry);
        }
        // 마켓 접수 경합 응답 수(API 설계 §2-5-b). 429 는 확정 미접수, 409 는 그 밖이다. 장애 창 밖에서는 0 이어야 한다.
        for (String cause : java.util.List.of("lock_wait", "lock_wait_stuck", "55P03", "57014", "40P01", "40001")) {
            for (String response : java.util.List.of("429", "409")) {
                io.micrometer.core.instrument.FunctionCounter
                        .builder("exchange.order.intake.contention", gateway,
                                g -> g.intakeContention(cause, response))
                        .tags("cause", cause, "response", response)
                        .description("마켓 접수 경합으로 저널 전에 끝난 주문 생성·취소 수")
                        .register(meterRegistry);
            }
        }
        // DB 연결 장애와 리더 자격 거부의 응답 수(API 설계 §2-5-c). 첫 증가를 increase() 가 세도록 여섯 조합을 미리 등록한다.
        // 경합 계수와 따로 세야 창 밖 접수 경합 상한 축이 DB 장애를 경합으로 세지 않는다.
        for (String cause : java.util.List.of("connection", "fencing")) {
            for (String response : java.util.List.of("503", "429", "409")) {
                io.micrometer.core.instrument.FunctionCounter
                        .builder("exchange.order.intake.unavailable", gateway,
                                g -> g.intakeUnavailable(cause, response))
                        .tags("cause", cause, "response", response)
                        .description("DB 연결 장애·리더 자격 거부로 끝난 주문 생성·취소 수")
                        .register(meterRegistry);
            }
        }
        // 접수 락과 별개의 직렬 구간이라 따로 잰다(InflightReserves 필드 주석).
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.inflight.lock.wait.nanos", gateway,
                        OrderGateway::inflightLockWaitNanos)
                .description("in-flight 예약 락 획득 대기 누적 나노초").register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.inflight.lock.hold.nanos", gateway,
                        OrderGateway::inflightLockHoldNanos)
                .description("in-flight 예약 락 보유 누적 나노초").register(meterRegistry);
        // 셋 다 드문 경로라 0 이 정상이다. 첫째가 늘면 커밋 응답 유실을, 둘째는 같은 client_order_id 의 동시 재시도
        // 경합을, 셋째는 append 실패 직후 DB 에 닿지 못한 창을 뜻한다(V47).
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.order.intake.commit_recovered", gateway, OrderGateway::commitsRecovered)
                .description("append 예외 뒤 이 요청의 NEW 가 저널에 있어 회수를 요청하고 접수로 처리한 횟수")
                .register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.order.idempotency_key.kept", gateway, OrderGateway::keysKept)
                .description("저널 전 실패로 끝난 요청의 멱등 키를 다른 요청의 공유 표시 때문에 남긴 횟수")
                .register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.order.intake.unresolved", gateway, OrderGateway::unresolvedFailures)
                .description("append 예외 뒤 저널 판정마저 실패해 키와 예약을 남긴 횟수")
                .register(meterRegistry);
        return gateway;
    }

    /**
     * 게이트웨이에 라우팅 계수, 한 번 더 읽기, 인계 요청자를 붙인다(S5 설계 4-2·4-4·4-6). 생성자를 늘리지 않고 부착한다. 게이트웨이
     * 생성 배선(orderGateway)은 시험이 그대로 부르므로 시그니처를 바꾸지 않는다(projectionSignalWiring 과 같은 방식). 붙이지 않은
     * 게이트웨이는 계수를 내보내지 않고 일괄 취소를 모두 직접 한다.
     */
    @Bean
    public static org.springframework.beans.factory.config.BeanPostProcessor orderGatewayRoutingWiring(
            org.springframework.beans.factory.ObjectProvider<kr.exchange.api.common.RoutingCounters> counters,
            org.springframework.beans.factory.ObjectProvider<kr.exchange.journal.DbReadRetry> readRetry,
            org.springframework.beans.factory.ObjectProvider<kr.exchange.api.order.OrderCancelHandoff> handoff) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof OrderGateway gateway) {
                    gateway.attachRouting(counters.getObject(), readRetry.getObject());
                    gateway.attachCancelHandoff(handoff.getObject());
                }
                return bean;
            }
        };
    }

    @Bean
    public kr.exchange.api.order.CancelHandoffCounters cancelHandoffCounters(MeterRegistry registry) {
        return new kr.exchange.api.order.CancelHandoffCounters(registry);
    }

    @Bean
    public kr.exchange.api.order.OrderCancelHandoffRepository orderCancelHandoffRepository(
            JdbcClient jdbc, PlatformTransactionManager txManager) {
        return new kr.exchange.api.order.OrderCancelHandoffRepository(jdbc, new TransactionTemplate(txManager));
    }

    /**
     * 파티션 밖 취소의 요청자(DB 설계 §5.4-d 1·2). 엔진을 끈 노드(관리 평면, 조회 증설)도 둔다. 그 노드는 어떤 파티션의 게이트도
     * 열지 않아 일괄 취소와 회원 폐쇄의 주문을 모두 소유 노드에 넘긴다.
     */
    @Bean
    public kr.exchange.api.order.OrderCancelHandoff orderCancelHandoff(
            kr.exchange.api.order.OrderCancelHandoffRepository repository,
            kr.exchange.engine.PartitionPlan partitionPlan, kr.exchange.engine.PartitionLeases partitionLeases,
            CancelHandoffProperties properties, kr.exchange.api.order.CancelHandoffCounters counters) {
        return new kr.exchange.api.order.OrderCancelHandoff(repository, partitionPlan, partitionLeases,
                properties.settings(), counters);
    }

    /**
     * 파티션 밖 취소의 작업자(DB 설계 §5.4-d 3). 엔진을 켠 노드에만 둔다. 게이트가 열린 파티션의 인계 행만 점유하므로 팔로워로
     * 떠 있는 동안에는 DB 를 보지 않는다.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "exchange.engine", name = "enabled", matchIfMissing = true)
    public kr.exchange.api.order.CancelHandoffWorker cancelHandoffWorker(
            kr.exchange.api.order.OrderCancelHandoffRepository repository, OrderGateway gateway,
            kr.exchange.engine.PartitionLeases partitionLeases, CancelHandoffProperties properties,
            kr.exchange.api.order.CancelHandoffCounters counters) {
        return new kr.exchange.api.order.CancelHandoffWorker(repository, gateway, partitionLeases,
                properties.settings(), counters);
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
    public EpochSource epochSource(kr.exchange.engine.PartitionLeases partitionLeases, JdbcClient jdbc) {
        return new EpochSource(partitionLeases, jdbc);
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
    public MarketDataPublisher marketDataPublisher(JdbcClient jdbc, MarketDirectory markets,
                                                   MarketDataService marketData,
                                                   OrderQueryService orders,
                                                   ChannelSequences sequences,
                                                   CandleSubscriptions candleSubscriptions,
                                                   kr.exchange.api.ws.WsFanout wsFanout,
                                                   org.springframework.beans.factory.ObjectProvider<
                                                           kr.exchange.api.ws.CandleFanout> candleFanout,
                                                   org.springframework.beans.factory.ObjectProvider<
                                                           kr.exchange.api.ws.MarketMirror> marketMirror,
                                                   MeterRegistry meterRegistry) {
        // 중계 모드면 캔들 팬아웃과 미러가 있다. 각 노드가 캔들을 계산하고 자기 게시로 미러를 갱신한다(WsRelayConfig, S6 설계 4-6·4-10).
        return new MarketDataPublisher(jdbc, markets, marketData, orders, sequences, candleSubscriptions,
                wsFanout, candleFanout.getIfAvailable(), marketMirror.getIfAvailable(), meterRegistry);
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
            MarketDirectory markets, kr.exchange.api.ws.MarketViews marketViews, ObjectMapper objectMapper,
            ApplicationContext context) {
        return new OrderbookSnapshotOnSubscribe(markets, marketViews, objectMapper,
                () -> context.getBean("clientOutboundChannel", MessageChannel.class));
    }
}

package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import kr.exchange.api.common.EpochSource;
import kr.exchange.api.market.MarketDataService;
import kr.exchange.api.market.MarketDirectory;
import kr.exchange.api.ws.CandleFanout;
import kr.exchange.api.ws.CandleSubscriptions;
import kr.exchange.api.ws.ChannelSequences;
import kr.exchange.api.ws.LeaseWatch;
import kr.exchange.api.ws.MarketDataPublisher;
import kr.exchange.api.ws.MarketMirror;
import kr.exchange.api.ws.MarketViews;
import kr.exchange.api.ws.WsFanout;
import kr.exchange.api.ws.WsGate;
import kr.exchange.api.ws.relay.BrokerLink;
import kr.exchange.api.ws.relay.OwnerBeats;
import kr.exchange.api.ws.relay.RelayEnvelope;
import kr.exchange.api.ws.relay.RelayHeartbeat;
import kr.exchange.api.ws.relay.RelayPublisher;
import kr.exchange.api.ws.relay.RelaySubscriber;
import kr.exchange.api.ws.relay.WsRelayProperties;
import kr.exchange.engine.LeaderTerm;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionRuntime;
import kr.exchange.engine.lease.LeaseStoreProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * WebSocket 노드 단위 중계와 WS 수용 게이트 배선(ADR 0034 S6, S6 설계 4-12). 중계 구성요소(발행·구독 연결, 하트비트, 소유자 표,
 * 미러, 캔들 팬아웃)는 exchange.ws.relay.mode=broker 이고 엔진을 켠 노드에서만 만든다. 봇 노드와 조회 노드는 WS 게시가 없다.
 * WS 게이트는 WS 엔드포인트가 있는 모든 노드에서 두 모드 모두 만들고, 리스 조회기는 엔진을 켠 노드에서만 만든다.
 */
@Configuration
@EnableConfigurationProperties(WsRelayProperties.class)
public class WsRelayConfig {

    private static final Logger log = LoggerFactory.getLogger(WsRelayConfig.class);

    /** 나이와 간격을 재는 시계. 벽시계가 뒤로 가도 판정이 흔들리지 않게 단조 시계를 쓴다. */
    static final LongSupplier MONOTONIC_MILLIS = () -> System.nanoTime() / 1_000_000;

    static boolean engineEnabled(Environment env) {
        return env.getProperty("exchange.engine.enabled", Boolean.class, true);
    }

    static final class EngineEnabledCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return engineEnabled(context.getEnvironment());
        }
    }

    /** 중계 구성요소를 만드는 노드. broker 모드이고 엔진을 켰다. 조회 역할 노드는 엔진을 끄므로 빠진다. */
    static final class BrokerRelayCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return engineEnabled(env) && !ReadRoleConfig.isReadRole(env)
                    && "broker".equalsIgnoreCase(env.getProperty("exchange.ws.relay.mode", "simple"));
        }
    }

    @Bean
    @Conditional(BrokerRelayCondition.class)
    public OwnerBeats ownerBeats() {
        return new OwnerBeats(MONOTONIC_MILLIS);
    }

    @Bean
    @Conditional(BrokerRelayCondition.class)
    public MarketMirror marketMirror() {
        return new MarketMirror(MONOTONIC_MILLIS);
    }

    @Bean(destroyMethod = "close")
    @Conditional(BrokerRelayCondition.class)
    public BrokerLink relayPublishLink(WsRelayProperties props) {
        props.requireBroker();
        return new BrokerLink("pub", BrokerLink.Role.PUBLISH, props, new BrokerLink.Listener() { });
    }

    @Bean(destroyMethod = "close")
    @Conditional(BrokerRelayCondition.class)
    public RelayPublisher relayPublisher(BrokerLink relayPublishLink, WsRelayProperties props, MeterRegistry registry) {
        RelayPublisher publisher = new RelayPublisher(relayPublishLink, props.publishQueue(), props.writeTimeout(),
                props.publishMaxAge(), MONOTONIC_MILLIS);
        for (RelayEnvelope.Kind kind : RelayEnvelope.Kind.values()) {
            FunctionCounter.builder("exchange.ws.relay.messages", publisher, p -> p.sent(kind))
                    .tags("direction", "out", "kind", kind.wire())
                    .description("중계로 보낸 봉투 수").register(registry);
        }
        FunctionCounter.builder("exchange.ws.relay.dropped", publisher, RelayPublisher::queueFull)
                .tag("reason", "queue_full").description("버린 중계 봉투 수").register(registry);
        FunctionCounter.builder("exchange.ws.relay.dropped", publisher, RelayPublisher::disconnected)
                .tag("reason", "disconnected").description("버린 중계 봉투 수").register(registry);
        FunctionCounter.builder("exchange.ws.relay.dropped", publisher, RelayPublisher::writeTimeouts)
                .tag("reason", "write_timeout").description("버린 중계 봉투 수").register(registry);
        FunctionCounter.builder("exchange.ws.relay.dropped", publisher, RelayPublisher::stale)
                .tag("reason", "stale").description("버린 중계 봉투 수").register(registry);
        Gauge.builder("exchange.ws.relay.queue.size", publisher, RelayPublisher::queueSize)
                .description("중계 발행 큐 길이").register(registry);
        Gauge.builder("exchange.ws.relay.connected", relayPublishLink, l -> l.connected() ? 1 : 0)
                .tag("link", "pub").description("중계 발행 연결이 붙었으면 1").register(registry);
        return publisher;
    }

    @Bean
    public WsFanout wsFanout(SimpMessagingTemplate messaging, ObjectMapper objectMapper,
                             ObjectProvider<RelayPublisher> relayPublisher, PartitionLeases partitionLeases) {
        return new WsFanout(messaging, objectMapper, relayPublisher.getIfAvailable(), partitionLeases.holder());
    }

    @Bean(destroyMethod = "close")
    @Conditional(BrokerRelayCondition.class)
    public CandleFanout candleFanout(MarketDirectory markets, MarketDataService marketData,
                                     CandleSubscriptions candleSubscriptions, WsFanout wsFanout) {
        return new CandleFanout(markets, marketData, candleSubscriptions, wsFanout, 1_000);
    }

    @Bean
    @Conditional(BrokerRelayCondition.class)
    public RelaySubscriber relaySubscriber(PartitionLeases partitionLeases, MarketDirectory markets, WsFanout wsFanout,
                                           MarketMirror marketMirror, OwnerBeats ownerBeats, CandleFanout candleFanout,
                                           ObjectMapper objectMapper, MeterRegistry registry) {
        Timer lag = Timer.builder("exchange.ws.relay.lag")
                .description("중계 봉투의 x-sent-at 부터 받은 시각까지")
                .publishPercentileHistogram()
                .register(registry);
        RelaySubscriber subscriber = new RelaySubscriber(partitionLeases.holder(), partitionLeases.plan(), markets,
                wsFanout, marketMirror, ownerBeats, candleFanout, objectMapper, lag);
        for (RelayEnvelope.Kind kind : RelayEnvelope.Kind.values()) {
            FunctionCounter.builder("exchange.ws.relay.messages", subscriber, s -> s.received(kind))
                    .tags("direction", "in", "kind", kind.wire())
                    .description("중계로 받은 봉투 수(자기 봉투 제외)").register(registry);
        }
        FunctionCounter.builder("exchange.ws.relay.dropped", subscriber, RelaySubscriber::malformed)
                .tag("reason", "malformed").description("버린 중계 봉투 수").register(registry);
        FunctionCounter.builder("exchange.ws.relay.dropped", candleFanout, CandleFanout::overflows)
                .tag("reason", "candle_overflow").description("버린 중계 봉투 수").register(registry);
        return subscriber;
    }

    @Bean(destroyMethod = "close")
    @Conditional(BrokerRelayCondition.class)
    public BrokerLink relaySubscribeLink(WsRelayProperties props, RelaySubscriber relaySubscriber,
                                         MeterRegistry registry) {
        props.requireBroker();
        BrokerLink link = new BrokerLink("sub", BrokerLink.Role.SUBSCRIBE, props, relaySubscriber);
        relaySubscriber.attach(link);
        Gauge.builder("exchange.ws.relay.connected", link, l -> l.connected() ? 1 : 0)
                .tag("link", "sub").description("중계 구독 연결이 붙었으면 1").register(registry);
        return link;
    }

    @Bean(destroyMethod = "close")
    @Conditional(BrokerRelayCondition.class)
    public RelayHeartbeat relayHeartbeat(PartitionLeases partitionLeases, MarketDirectory markets,
                                         MarketDataPublisher marketDataPublisher, RelayPublisher relayPublisher,
                                         ObjectMapper objectMapper, WsRelayProperties props) {
        OwnerBeats.requireReachableInterval(props.ownerHeartbeat(), props.publishMaxAge());
        OwnerBeats.requireFreshInterval(props.ownerHeartbeat(), props.mirrorMaxSilence());
        return new RelayHeartbeat(partitionLeases.holder(), partitionLeases, markets,
                marketDataPublisher::lastPublished, relayPublisher, objectMapper, props.ownerHeartbeat());
    }

    /** 연결과 스레드는 문맥이 다 선 뒤 연다. 발행 스레드를 연결보다 먼저 세워 붙자마자 들어온 봉투를 비운다. */
    @Bean
    @Conditional(BrokerRelayCondition.class)
    public SmartLifecycle wsRelayLifecycle(RelayPublisher relayPublisher, BrokerLink relayPublishLink,
                                           BrokerLink relaySubscribeLink, RelayHeartbeat relayHeartbeat,
                                           WsRelayProperties props) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                log.info("WS 중계 모드 broker: broker={}, destination={}", props.broker(), props.destination());
                relayPublisher.start();
                relayPublishLink.start();
                relaySubscribeLink.start();
                relayHeartbeat.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                relayHeartbeat.close();
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    @Bean
    public MarketViews marketViews(MarketDataService marketData, ChannelSequences sequences, EpochSource epochSource,
                                   PartitionLeases partitionLeases, ObjectProvider<MarketMirror> marketMirror,
                                   ObjectProvider<OwnerBeats> ownerBeats, ObjectMapper objectMapper,
                                   WsRelayProperties relayProps, MeterRegistry registry) {
        MarketMirror mirror = marketMirror.getIfAvailable();
        MarketViews views = new MarketViews(marketData, sequences, epochSource, partitionLeases.plan(),
                partitionLeases::openTerm, mirror == null ? null
                        : new MarketViews.Relay(mirror, ownerBeats.getObject(), relayProps.mirrorMaxSilence().toMillis()),
                objectMapper);
        if (mirror != null) {
            for (MarketMirror.Channel channel : MarketMirror.Channel.values()) {
                for (MarketViews.Fallback reason : MarketViews.Fallback.values()) {
                    FunctionCounter.builder("exchange.ws.mirror.fallback", views, v -> v.fallbacks(channel, reason))
                            .tags("kind", channel.name().toLowerCase(), "reason", reason.wire())
                            .description("호가·티커 REST 와 구독 스냅샷이 미러 대신 DB 집계로 간 수").register(registry);
                }
            }
        }
        return views;
    }

    /**
     * 리스 조회기. PostgreSQL 보관소는 리스 전용 풀(2개)을 갱신에 남겨 두려고 보관소를 거치지 않고 기본 풀로 행을 읽는다. DynamoDB
     * 보관소는 leader_lease 행의 lease_until 이 먼 미래라 보관소 항목을 읽는다(S6 설계 4-11).
     */
    @Bean(destroyMethod = "close")
    @Conditional(EngineEnabledCondition.class)
    public LeaseWatch leaseWatch(JdbcClient jdbcClient, PartitionLeases partitionLeases,
                                 LeaderLeaseStoreProperties leaseStoreProps, LeaseStoreProvider leaseStores,
                                 @Value("${exchange.ws.gate.lease-watch:1s}") Duration interval) {
        List<String> resources = partitionLeases.plan().partitions();
        LeaseWatch watch = leaseStoreProps.dynamoDb()
                ? LeaseWatch.of(leaseStores, resources, interval, MONOTONIC_MILLIS)
                : LeaseWatch.of(jdbcClient, resources, interval, MONOTONIC_MILLIS);
        watch.start();
        return watch;
    }

    @Bean(destroyMethod = "close")
    public WsGate wsGate(PartitionLeases partitionLeases, ObjectProvider<LeaseWatch> leaseWatch,
                         ObjectProvider<OwnerBeats> ownerBeats, WsRelayProperties props, EngineProperties engine,
                         Environment environment, MeterRegistry registry) {
        LeaseWatch watch = leaseWatch.getIfAvailable();
        OwnerBeats beats = ownerBeats.getIfAvailable();
        boolean engineOn = engineEnabled(environment);
        if (engineOn && partitionLeases.plan().partitioned() && !props.brokerMode()) {
            // 두 노드가 각자 절반만 게시하므로 한 노드가 두 파티션을 다 쥐지 않는 한 두 노드 모두 WS 를 받지 않는다(S6 설계 4-11).
            log.error("파티션 리더 모드(P3)인데 WS 중계가 simple 이다. 두 파티션을 다 쥔 노드만 WS 를 받는다."
                    + " exchange.ws.relay.mode=broker 로 띄운다");
        }
        WsGate gate = new WsGate(engineOn, partitionLeases.holder(), partitionLeases.plan().partitions(),
                resource -> openTerm(partitionLeases, resource),
                () -> watch == null ? java.util.Optional.empty() : watch.view(), beats, props.degradedAfter(),
                props.ownerSilentAfter(engine.leaseTtl()), engine.leaseTtl(), MONOTONIC_MILLIS);
        Gauge.builder("exchange.ws.gate.open", gate, g -> g.open() ? 1 : 0)
                .description("이 노드가 WS 를 받으면 1").register(registry);
        for (WsGate.Reason reason : WsGate.Reason.values()) {
            FunctionCounter.builder("exchange.ws.relay.handshake_refused", gate, g -> g.handshakeRefused(reason))
                    .tag("reason", reason.wire()).description("거절한 WS 핸드셰이크 수").register(registry);
        }
        if (beats != null) {
            for (WsGate.Cause cause : WsGate.Cause.values()) {
                Gauge.builder("exchange.ws.relay.degraded", gate, g -> g.degraded(cause) ? 1 : 0)
                        .tag("cause", cause.wire()).description("그 원인으로 중계 불능이면 1").register(registry);
            }
        }
        gate.start();
        return gate;
    }

    private static LeaderTerm openTerm(PartitionLeases leases, String resource) {
        return leases.runtime(resource).map(PartitionRuntime::coordinator).map(c -> c.openTerm()).orElse(null);
    }

    /** 헬스 그룹 ws(/actuator/health/ws). 받지 않으면 OUT_OF_SERVICE 와 503 이다. ALB 의 WS 대상 헬스 경로로 쓸 수 있다. */
    @Bean
    public HealthIndicator wsHealthIndicator(WsGate wsGate) {
        return () -> {
            WsGate.Decision d = wsGate.current();
            return d.open() ? Health.up().build()
                    : Health.outOfService().withDetail("reason", d.reason().wire()).build();
        };
    }
}

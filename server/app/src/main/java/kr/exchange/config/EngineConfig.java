package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.ws.MarketDataPublisher;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.EngineRecovery;
import kr.exchange.engine.HaAvailability;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LoopFatalHandler;
import kr.exchange.engine.PromotionCoordinator;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import kr.exchange.snapshot.SnapshotCapture;
import kr.exchange.snapshot.SnapshotStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties({EngineProperties.class, SnapshotProperties.class})
public class EngineConfig {

    @Bean
    public LeaderLeaseService leaderLeaseService(JdbcClient jdbcClient, EngineProperties props) {
        return new LeaderLeaseService(jdbcClient, props.leaseTtl(), props.leaseTickInterval());
    }

    @Bean
    public CommandJournalAppender commandJournalAppender(JdbcClient jdbcClient,
                                                         TransactionTemplate transactionTemplate,
                                                         LeaderLeaseService leaderLeaseService,
                                                         MeterRegistry meterRegistry) {
        return new CommandJournalAppender(jdbcClient, transactionTemplate, leaderLeaseService,
                meterRegistry);
    }

    @Bean
    public CommandJournalReader commandJournalReader(JdbcClient jdbcClient) {
        return new CommandJournalReader(jdbcClient);
    }

    @Bean
    public EventJournalReader eventJournalReader(JdbcClient jdbcClient) {
        return new EventJournalReader(jdbcClient);
    }

    @Bean
    public MarketCatalog marketCatalog(JdbcClient jdbcClient) {
        return new MarketCatalog(jdbcClient);
    }

    @Bean
    public SettlementService settlementService(JdbcClient jdbcClient,
                                               TransactionTemplate transactionTemplate,
                                               LeaderLeaseService leaderLeaseService,
                                               ObjectMapper objectMapper,
                                               MarketCatalog marketCatalog,
                                               CommandJournalReader commandJournalReader,
                                               CommandPayloadMapper commandPayloadMapper) {
        return new SettlementService(jdbcClient, transactionTemplate, leaderLeaseService,
                objectMapper, marketCatalog, commandJournalReader, commandPayloadMapper);
    }

    @Bean
    public CommandPayloadMapper commandPayloadMapper(ObjectMapper objectMapper) {
        return new CommandPayloadMapper(objectMapper);
    }

    @Bean
    public MatchingEngineImpl matchingEngine() {
        return new MatchingEngineImpl();
    }

    @Bean
    public ReplayService replayService(CommandJournalReader commandJournalReader,
                                       EventJournalReader eventJournalReader,
                                       CommandPayloadMapper commandPayloadMapper,
                                       ObjectMapper objectMapper,
                                       MarketCatalog marketCatalog) {
        return new ReplayService(commandJournalReader, eventJournalReader,
                commandPayloadMapper, objectMapper, marketCatalog);
    }

    @Bean
    public SnapshotStore snapshotStore(JdbcClient jdbcClient,
                                       TransactionTemplate transactionTemplate,
                                       SnapshotProperties props) {
        return new SnapshotStore(jdbcClient, transactionTemplate,
                props.engineVersion(), props.configHash());
    }

    @Bean
    public EngineRecovery engineRecovery(ReplayService replayService,
                                         SettlementService settlementService,
                                         LeaderLeaseService leaderLeaseService,
                                         SnapshotStore snapshotStore,
                                         SnapshotProperties snapshotProps,
                                         MeterRegistry meterRegistry) {
        // 크래시 창 재적재는 라이브 sink와 동일한 append+정산 원자 경로를 쓴다(§5.5 ②).
        // 스냅샷 활성 시 로드 후 tail 재생으로 복구·승격 재생량을 상한한다(§3-b).
        // 재생 명령 수 Counter(§5-2). 부팅 복구·승격 재생 모두 recover를 거치므로 단일 관측점이다.
        io.micrometer.core.instrument.Counter replayCommands = io.micrometer.core.instrument.Counter
                .builder("exchange.journal.replay.commands")
                .description("복구·승격 재생으로 재적용한 명령 수").register(meterRegistry);
        return new EngineRecovery(replayService, settlementService::appendAndSettle,
                leaderLeaseService, snapshotProps.enabled() ? snapshotStore : null,
                replayCommands::increment);
    }

    // 주기 스냅샷 캡처. 라이터 명령 경계 훅으로 인코딩하고 별도 워커가 영속화한다(§3-b).
    // 비활성이면 훅을 배선하지 않아 캡처가 전혀 돌지 않는다.
    @Bean(destroyMethod = "close")
    public SnapshotCapture snapshotCapture(SnapshotStore snapshotStore,
                                           LeaderLeaseService leaderLeaseService,
                                           SnapshotProperties props,
                                           SingleWriterLoop loop,
                                           MeterRegistry meterRegistry) {
        SnapshotCapture capture = new SnapshotCapture(snapshotStore, leaderLeaseService,
                props.commandInterval(), props.timeInterval(), props.queueCapacity(), meterRegistry);
        if (props.enabled()) {
            loop.setSnapshotHook(capture);
        }
        return capture;
    }

    @Bean
    public SingleWriterLoop singleWriterLoop(EngineProperties props, MatchingEngineImpl engine,
                                             SettlementService settlementService,
                                             CommandJournalReader commandJournalReader,
                                             CommandPayloadMapper commandPayloadMapper,
                                             LeaderLeaseService leaderLeaseService,
                                             MarketDataPublisher marketDataPublisher,
                                             MeterRegistry meterRegistry) {
        SingleWriterLoop loop = new SingleWriterLoop(
                props.queueCapacity(),
                engine,
                // 정산 커밋 후 WS 게시(§5). 게시 실패는 publisher가 삼키므로 sink 예외
                // (= fail-fast) 축은 정산 실패뿐이다.
                events -> marketDataPublisher.publishSettled(
                        settlementService.appendAndSettle(events)),
                new JournalCatchUpSource(commandJournalReader, commandPayloadMapper),
                // 이벤트 정산 실패는 무음 재시도 대상이 아니다. 원인 불문 리더십을 함께
                // 내려놓아 접수 경로를 닫는다(LoopFatalHandler, DB 설계 §5.4).
                new LoopFatalHandler(leaderLeaseService));
        // 단일 라이터 큐 깊이 Gauge(§5-2 백프레셔 P4). 스크레이프 시점 queue.size() 조회라
        // 라이터 핫패스와 무관하다.
        io.micrometer.core.instrument.Gauge
                .builder("exchange.sequencer.queue.size", loop, SingleWriterLoop::queueSize)
                .description("단일 라이터 명령 큐 깊이(백프레셔)").register(meterRegistry);
        return loop;
    }

    // HA 관측 게이지 + readiness 배선. 승격 상태 전이를 AvailabilityChangeEvent로 승격시켜
    // LB가 팔로워·승격 대기 노드를 제외하게 한다(감사 관측성·복구, 복구 RTO §3-a).
    @Bean
    public HaAvailability haAvailability(ApplicationEventPublisher publisher,
                                         MeterRegistry meterRegistry,
                                         @Value("${exchange.node.id:${spring.application.name:exchange}}")
                                         String nodeId) {
        return new HaAvailability(publisher, meterRegistry, nodeId);
    }

    // 승격 재생 조율. 리스 획득·상실 콜백을 여기서 배선한다. 부팅 recover가 도는 lifecycle과
    // 이중 실행하지 않도록 arm 이전 gain(부팅 획득)은 코디네이터가 무시한다(복구 RTO §3-a).
    @Bean
    public PromotionCoordinator promotionCoordinator(EngineRecovery engineRecovery,
                                                     SingleWriterLoop loop,
                                                     LeaderLeaseService leaderLeaseService,
                                                     CommandJournalReader commandJournalReader,
                                                     HaAvailability haAvailability) {
        PromotionCoordinator coordinator = new PromotionCoordinator(
                engineRecovery, loop, leaderLeaseService, commandJournalReader, haAvailability);
        leaderLeaseService.onLeadershipGained(coordinator::onLeadershipGained);
        leaderLeaseService.onLeadershipLost(coordinator::onLeadershipLost);
        // 관측: epoch 계단·is_leader·failover downtime 게이지 갱신(라이터 스레드 밖).
        leaderLeaseService.onLeadershipAcquired(
                (identity, downtimeSeconds) -> haAvailability.leaderAcquired(
                        identity.epoch(), downtimeSeconds));
        leaderLeaseService.onLeadershipLost(haAvailability::leaderLost);
        return coordinator;
    }

    @Bean
    public EngineBridge engineBridge(CommandJournalAppender appender, SingleWriterLoop loop,
                                     CommandPayloadMapper commandPayloadMapper,
                                     PromotionCoordinator promotionCoordinator) {
        return new EngineBridge(appender, loop, commandPayloadMapper, promotionCoordinator.gate());
    }

    // 봇 전용 노드는 엔진을 끄고 리더 경쟁에서 빠진다(격리 토폴로지에서 봇이 죽인 리더로
    // 승격 주체가 오염되지 않게). 기본값 true라 기존 dev·통합테스트·단일 노드는 무변경이다.
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "exchange.engine", name = "enabled", matchIfMissing = true)
    public SmartLifecycle engineRuntimeLifecycle(LeaderLeaseService lease, SingleWriterLoop loop,
                                                 EngineRecovery recovery,
                                                 MatchingEngineImpl engine,
                                                 PromotionCoordinator promotionCoordinator) {
        // 리더십 상실 확정 시 self-shutdown: 잔여 큐를 버리고 즉시 정지한다(DB 설계 §5.4).
        lease.onLeadershipLost(loop::halt);
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                // 재생·크래시 창 재적재 실패는 기동 실패다. 검증 안 된 상태로 트래픽을 받지
                // 않고, pending을 버린 기동은 저널 prefix 성질을 깨므로 금지다(§5.5 ②).
                // 강제 종료 직후 재기동은 직전 프로세스 리스가 TTL 내 유효할 수 있어 부팅
                // 경로는 만료 대기 후 승격을 재시도한다(EngineRecovery.recoverAtBoot).
                recovery.recoverAtBoot(engine);
                loop.start();
                // 부팅 시점에 리더면 즉시 접수 개방, 아니면 팔로워로 게이트를 닫는다. arm 이후의
                // gain(런타임 승격)만 fresh 엔진 선복원 경로를 탄다(복구 RTO §3-a).
                promotionCoordinator.bootstrap(engine, lease.isLeader());
                promotionCoordinator.arm();
                lease.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                lease.stop();
                try {
                    loop.stop();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}

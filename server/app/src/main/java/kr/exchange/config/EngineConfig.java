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
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.LoopFatalHandler;
import kr.exchange.engine.NodeServiceHealth;
import kr.exchange.engine.PromotionCoordinator;
import kr.exchange.engine.LeaderStallDetector;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties({EngineProperties.class, SnapshotProperties.class})
public class EngineConfig {

    @Bean
    public LeaderWriteProgress leaderWriteProgress() {
        return new LeaderWriteProgress();
    }

    // 좀비 리더 판정(§5.4). 요청 풀 전량 점유와 리더 쓰기 커밋 진행 0이 동시에 지속되면 이 노드는
    // 일을 못 하는 상태다. 요청 풀을 봐야 하므로 @Primary DataSource를 그대로 받는다.
    @Bean
    public NodeServiceHealth nodeServiceHealth(@Qualifier("dataSource") DataSource requestDataSource,
                                               LeaderWriteProgress leaderWriteProgress,
                                               EngineProperties props,
                                               MeterRegistry meterRegistry) {
        return new LeaderStallDetector(requestDataSource, leaderWriteProgress,
                props.leaseStanddownAfter(), meterRegistry);
    }

    @Bean
    public LeaderLeaseService leaderLeaseService(
            @Qualifier("leaseDataSource") DataSource leaseDataSource, EngineProperties props,
            NodeServiceHealth nodeServiceHealth, MeterRegistry meterRegistry) {
        // 리스 renew·승격 시도 UPDATE는 전용 소형 풀 커넥션으로만 수행한다. 요청 풀이 포화돼도
        // 하트비트가 굶지 않게 격리한다(LeaseDataSourceConfig, DB 설계 §5.4). 저널·정산 등 승격
        // 이후 쓰기 경로는 그대로 기본 풀·트랜잭션을 타므로 fencing 원자성 경계는 불변이다.
        // 그 격리로 분리된 '하트비트는 살고 서비스는 죽은' 창은 nodeServiceHealth가 닫는다.
        LeaderLeaseService lease = new LeaderLeaseService(JdbcClient.create(leaseDataSource),
                props.leaseTtl(), props.leaseTickInterval(), nodeServiceHealth);
        // 회복 가능 반납 카운터(§5-2). 종신 강등 축(exchange.leader.standdown)과 분리한다. 이
        // 등급이 만드는 실패 모드는 [재생 실패 -> 반납 -> 재승격]의 반복이라, 합산하면 자발적
        // 강등 1회와 구분되지 않고 epoch 계단만으로는 정상 페일오버와도 구분되지 않는다.
        io.micrometer.core.instrument.Counter recoverableStandDowns =
                io.micrometer.core.instrument.Counter
                        .builder("exchange.leader.recoverable_standdown")
                        .description("승격 재생 실패로 리더십을 회복 가능하게 반납한 횟수")
                        .register(meterRegistry);
        lease.onRecoverableStandDown(recoverableStandDowns::increment);
        return lease;
    }

    @Bean
    public CommandJournalAppender commandJournalAppender(JdbcClient jdbcClient,
                                                         TransactionTemplate transactionTemplate,
                                                         LeaderLeaseService leaderLeaseService,
                                                         MeterRegistry meterRegistry,
                                                         LeaderWriteProgress leaderWriteProgress) {
        return new CommandJournalAppender(jdbcClient, transactionTemplate, leaderLeaseService,
                meterRegistry, leaderWriteProgress);
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
                                               CommandPayloadMapper commandPayloadMapper,
                                               LeaderWriteProgress leaderWriteProgress) {
        return new SettlementService(jdbcClient, transactionTemplate, leaderLeaseService,
                objectMapper, marketCatalog, commandJournalReader, commandPayloadMapper,
                leaderWriteProgress);
    }

    // 접수 경로를 깨우는 신호. 정산(생산)과 접수(소비)가 같은 객체를 봐야 하므로 빈으로 둔다.
    // 엔진이 꺼진 노드(봇 전용)에도 생성되나 정산이 돌지 않아 아무도 깨우지 않는다. 그 노드의
    // 접수 경로는 어차피 쓰이지 않는다.
    @Bean
    public kr.exchange.ledger.ProjectionSignal projectionSignal() {
        return new kr.exchange.ledger.ProjectionSignal();
    }

    @Bean
    public org.springframework.beans.factory.config.BeanPostProcessor projectionSignalWiring(
            org.springframework.beans.factory.ObjectProvider<kr.exchange.ledger.ProjectionSignal> provider) {
        // 생성자를 늘리지 않고 부착한다. SettlementService 생성자는 호출부와 테스트가 여럿이라
        // 시그니처를 건드리면 그 전부가 신호를 알아야 한다. 이 프로젝션 최적화는 그 정도의
        // 파급을 살 이유가 없다.
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof SettlementService s) {
                    s.attachProjectionSignal(provider.getObject());
                } else if (bean instanceof kr.exchange.api.order.OrderGateway g) {
                    g.attachProjectionSignal(provider.getObject());
                }
                return bean;
            }
        };
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
                                         MeterRegistry meterRegistry,
                                         JdbcClient jdbcClient) {
        // 크래시 창 재적재는 라이브 sink와 동일한 append+정산 원자 경로를 쓴다(§5.5 ②).
        // 스냅샷 활성 시 로드 후 tail 재생으로 복구·승격 재생량을 상한한다(§3-b).
        // 재생 명령 수 Counter(§5-2). 부팅 복구·승격 재생 모두 recover를 거치므로 단일 관측점이다.
        io.micrometer.core.instrument.Counter replayCommands = io.micrometer.core.instrument.Counter
                .builder("exchange.journal.replay.commands")
                .description("복구·승격 재생으로 재적용한 명령 수").register(meterRegistry);
        EngineRecovery recovery = new EngineRecovery(replayService,
                settlementService::appendAndSettle,
                leaderLeaseService, snapshotProps.enabled() ? snapshotStore : null,
                replayCommands::increment);
        // C17(재생 결정론) 원천. 이 기록자가 없으면 recovery_runs가 늘 0행이라 판정 SQL이
        // 빈 테이블 대조로 공허 통과한다. 검증은 ReplayService가 이미 하고 여기서는 그
        // 결과를 표로 남길 뿐이다.
        recovery.setRecoveryRecorder(new kr.exchange.harness.RecoveryRunRecorder(jdbcClient));
        return recovery;
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
    // 컨테이너 healthcheck·하네스가 팔로워·강등 노드를 구분하게 한다(감사 관측성·복구 RTO §3-a).
    // ApplicationAvailability를 주입해 중복 발행 억제를 자기 기록이 아닌 실측 상태로 판단한다.
    @Bean
    public HaAvailability haAvailability(ApplicationEventPublisher publisher,
                                         ApplicationAvailability applicationAvailability,
                                         MeterRegistry meterRegistry,
                                         @Value("${exchange.node.id:${spring.application.name:exchange}}")
                                         String nodeId) {
        return new HaAvailability(publisher, applicationAvailability, meterRegistry, nodeId);
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
        // 게이지는 종신 강등뿐 아니라 승격 재생 실패의 회복 가능 반납에서도 내려야 한다.
        // 상실 리스너(halt·DEMOTED)는 회복 가능 반납에서 발화하지 않으므로 반납 축에 건다.
        leaderLeaseService.onLeadershipReleased(haAvailability::leaderLost);
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

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
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaderLeaseStore;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.LoopFatalHandler;
import kr.exchange.engine.NodeServiceHealth;
import kr.exchange.engine.PromotionCoordinator;
import kr.exchange.engine.LeaderStallDetector;
import kr.exchange.engine.WriterRouter;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import org.springframework.beans.factory.ObjectProvider;
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
import kr.exchange.snapshot.SnapshotPersister;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EngineConfig.class);

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
            LeaderLeaseStore leaderLeaseStore, FencingEpochPublisher fencingEpochPublisher,
            EngineProperties props,
            NodeServiceHealth nodeServiceHealth, MeterRegistry meterRegistry) {
        // 리스 renew·승격 시도는 보관소가 정한다(LeaseStoreConfig). PostgreSQL 보관소는 전용 소형
        // 풀 커넥션으로만 UPDATE 한다. 요청 풀이 포화돼도 하트비트가 굶지 않게 격리한다
        // (LeaseDataSourceConfig, DB 설계 §5.4). 저널·정산 등 승격 이후 쓰기 경로는 그대로 기본
        // 풀·트랜잭션을 타므로 fencing 원자성 경계는 불변이다. 그 격리로 분리된 '하트비트는 살고
        // 서비스는 죽은' 창은 nodeServiceHealth가 닫는다.
        //
        // 풀 격리가 닫지 못하는 창이 하나 더 있고 2026-09-08에 실측했다. 동기 복제 대상 standby를
        // 전부 잃으면 커밋 자체가 멈추므로 전용 풀이어도 UPDATE가 반환하지 않는다. 그 창을 닫는
        // 것이 DynamoDB 보관소이며 설정으로 갈린다.
        LeaderLeaseService lease = new LeaderLeaseService(leaderLeaseStore, fencingEpochPublisher,
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

    /**
     * 접수 저널 append 전용 배선. 커넥션과 트랜잭션 관리자를 요청 풀과 나눈다.
     *
     * 왜 나누는가. 조회 폭주로 요청 풀이 포화돼도 접수가 커넥션을 기다리지 않게 한다. 접수 락이
     * 마켓별이라 append 가 마켓 수만큼 동시에 커넥션을 쓰고, 풀 크기는 그 수에 맞춘다
     * (LeaseDataSourceConfig.appendDataSource).
     *
     * 트랜잭션 관리자를 함께 나눠야 한다. 기본 관리자에 다른 DataSource 의 커넥션을 물리면
     * 트랜잭션이 기본 풀에서 열리고 질의만 전용 풀로 가서 경계가 갈린다.
     */
    @Bean
    public CommandJournalAppender commandJournalAppender(
            @org.springframework.beans.factory.annotation.Qualifier("appendDataSource")
            javax.sql.DataSource appendDataSource,
            LeaderLeaseService leaderLeaseService,
            MeterRegistry meterRegistry,
            LeaderWriteProgress leaderWriteProgress,
            EngineProperties props) {
        // JdbcClient 와 TransactionTemplate 을 빈으로 내놓지 않고 여기서 만든다. 빈으로 두면
        // 그 타입의 후보가 둘이 되고, 한정자 없이 타입으로 주입받는 다른 배선이 이름으로
        // 아무거나 문다. 실제로 그렇게 관리 평면의 승인 롤백이 append 풀의 트랜잭션
        // 관리자를 물어 롤백이 무효가 됐다(AdminPlaneIntegrationTest 두 건).
        //
        // 트랜잭션 관리자를 함께 나눠야 한다. 기본 관리자에 다른 DataSource 의 커넥션을
        // 물리면 트랜잭션이 기본 풀에서 열리고 질의만 전용 풀로 가서 경계가 갈린다.
        CommandJournalAppender appender = new CommandJournalAppender(
                JdbcClient.create(appendDataSource),
                new TransactionTemplate(
                        new org.springframework.jdbc.support.JdbcTransactionManager(appendDataSource)),
                leaderLeaseService, meterRegistry, leaderWriteProgress);
        // 기본 0 이면 걸지 않는다(ADR 0034 S1, EngineProperties).
        appender.setLockTimeout(props.acceptLockTimeout());
        // 임계구역 안 분해(ADR 0022 0단계 (b)). 접수 락 보유의 3분의 2가 데이터베이스 밖에
        // 있다는 것까지만 알고 그 안을 모른다(정본 5.7.10). appendTimer 에서 inTx 를 빼면
        // 커넥션 획득과 트랜잭션 경계와 커밋이 남는다. 그 셋은 여기서 더 못 가른다.
        record Probe(String name, java.util.function.ToDoubleFunction<CommandJournalAppender> f,
                     String desc) {
        }
        for (Probe probe : java.util.List.of(
                new Probe("exchange.journal.intx.nanos",
                        CommandJournalAppender::inTxNanos, "append 트랜잭션 콜백 누적 나노초"),
                new Probe("exchange.journal.prologue.nanos",
                        CommandJournalAppender::prologueNanos,
                        "머리말 한 왕복 누적 나노초(V39. 리더 검증·멱등 판정·채번)"),
                new Probe("exchange.journal.pre.nanos",
                        CommandJournalAppender::preNanos, "커넥션 획득과 경계 시작 누적 나노초"),
                new Probe("exchange.journal.commit.nanos",
                        CommandJournalAppender::commitNanos, "커밋 누적 나노초. fsync 가 여기 있다"),
                new Probe("exchange.journal.post.nanos",
                        CommandJournalAppender::postNanos, "정리와 커넥션 반납 누적 나노초"),
                new Probe("exchange.journal.insert.nanos",
                        CommandJournalAppender::insertNanos, "저널 INSERT 누적 나노초"),
                new Probe("exchange.journal.intx.count",
                        CommandJournalAppender::inTxCount, "위 다섯의 분모"))) {
            io.micrometer.core.instrument.FunctionCounter
                    .builder(probe.name(), appender, probe.f())
                    .description(probe.desc()).register(meterRegistry);
        }
        return appender;
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
                                               CommandPayloadMapper commandPayloadMapper,
                                               LeaderWriteProgress leaderWriteProgress,
                                               MeterRegistry meterRegistry) {
        SettlementService settlement = new SettlementService(jdbcClient, transactionTemplate,
                leaderLeaseService, objectMapper, marketCatalog, commandPayloadMapper,
                leaderWriteProgress);
        // 0 이 아니면 정산이 잠금 경합이나 문장 취소를 만났다는 뜻이다. 강등 없이 흡수했는지는 아래 소진 수와 함께 본다.
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.settlement.transient_retries", settlement,
                        SettlementService::transientRetries)
                .description("정산이 일시 DB 오류(55P03·57014·40P01·40001)로 배치를 다시 돌린 횟수")
                .register(meterRegistry);
        // 1 이상이면 재시도로 흡수하지 못한 일시 오류가 그 마켓 라이터를 세우고 노드를 종신 강등했다. 경보 대상이다.
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.settlement.transient_exhausted", settlement,
                        SettlementService::transientExhausted)
                .description("정산 일시 DB 오류가 재시도 제한(시도 4회 또는 경과 5초)을 넘겨 라이터로 올라간 횟수")
                .register(meterRegistry);
        return settlement;
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
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.recovery.reinject.created_at_inversions", recovery,
                        EngineRecovery::reinjectCreatedAtInversions)
                .description("크래시 창 재적재에서 마켓 안 created_at 이 seq 순서와 거꾸로 선 배치 수")
                .register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.recovery.snapshot_fallback", recovery, EngineRecovery::snapshotFallbacks)
                .description("스냅샷 시작 복구를 버리고 스냅샷 없이 전량 재생한 횟수")
                .register(meterRegistry);
        return recovery;
    }

    // 주기 스냅샷 영속화 워커. 캡처는 샤드 팩토리가 마켓마다 걸고 이 워커 하나가 적재한다(§3-b).
    // 비활성이면 훅을 배선하지 않아 받을 캡처가 없다.
    @Bean(destroyMethod = "close")
    public SnapshotPersister snapshotPersister(SnapshotStore snapshotStore) {
        return new SnapshotPersister(snapshotStore);
    }

    /**
     * 마켓별 라이터 묶음(ADR 0021 5단계).
     *
     * 마켓마다 SingleWriterLoop 와 MatchingEngineImpl 을 하나씩 만든다. 인스턴스는 늘리지
     * 않는다. 같은 노드 안 스레드 N개다. 정산이 직렬 구간의 99.9퍼센트를 차지하므로
     * 그것을 마켓마다 따로 돌리면 마켓 수만큼 커넥션이 동시에 붙는다.
     *
     * 지표에 market 라벨을 붙인다. 라벨 없이 등록하면 두 번째 마켓에서 미터 id 가 충돌해
     * 첫 루프만 관측된다. 집계가 필요한 곳은 sum 으로 합친다.
     */
    @Bean
    public WriterRouter writerRouter(EngineProperties props,
                                     SettlementService settlementService,
                                     CommandJournalReader commandJournalReader,
                                     CommandPayloadMapper commandPayloadMapper,
                                     LeaderLeaseService leaderLeaseService,
                                     MarketDataPublisher marketDataPublisher,
                                     kr.exchange.api.market.MarketDataService marketDataService,
                                     SnapshotProperties snapshotProps,
                                     SnapshotPersister snapshotPersister,
                                     MeterRegistry meterRegistry) {
        return new WriterRouter(marketId -> {
            MatchingEngineImpl shardEngine = new MatchingEngineImpl();
            SingleWriterLoop loop = new SingleWriterLoop(
                    props.queueCapacity(),
                    shardEngine,
                    events -> {
                        java.util.List<kr.exchange.engine.core.EngineEvent> settled =
                                settlementService.appendAndSettle(events);
                        // 승격 뒤 엔진이 교체되면 이 참조가 낡는다. 종전 단일 루프 배선도
                        // 같은 성질이라 동작을 바꾸지 않는다. 호가 스냅샷 갱신만의 문제이고
                        // 정산과 게시는 영향이 없다. 고치는 것은 이 판의 범위가 아니다.
                        publishEngineDepth(shardEngine, marketDataService, settled);
                        marketDataPublisher.publishSettled(settled);
                    },
                    new JournalCatchUpSource(commandJournalReader, commandPayloadMapper, marketId),
                    new LoopFatalHandler(leaderLeaseService));
            if (snapshotProps.enabled()) {
                // 캡처 판정 상태를 샤드마다 둔다. 한 인스턴스를 샤드 여럿에 걸면 커서가 가장 높은 마켓의 캡처가
                // 나머지 마켓의 판정을 막는다(ADR 0022 7단계).
                loop.setSnapshotHook(new SnapshotCapture(snapshotPersister, leaderLeaseService, marketId,
                        snapshotProps.commandInterval(), snapshotProps.timeInterval(),
                        snapshotProps.queueCapacity(), meterRegistry));
            }
            String tag = String.valueOf(marketId);
            io.micrometer.core.instrument.Gauge
                    .builder("exchange.sequencer.queue.size", loop, SingleWriterLoop::queueSize)
                    .tag("market", tag)
                    .description("마켓별 라이터 명령 큐 깊이(백프레셔)").register(meterRegistry);
            // 직렬 구간 분해(정본 13.5 한계 29). 누적 나노초를 그대로 낸다. rate() 로
            // 초당 소비 시간이 나오고 그것이 직렬 점유율이다. 1.0 이면 그 마켓의 라이터가
            // 그 구간만으로 꽉 찬다. 마켓 라벨이 없으면 두 번째 마켓에서 미터 id 가
            // 충돌해 첫 루프만 관측된다.
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.writer.apply.nanos", loop, SingleWriterLoop::applyNanos)
                    .tag("market", tag)
                    .description("매칭 적용 누적 나노초").register(meterRegistry);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.writer.sink.nanos", loop, SingleWriterLoop::sinkNanos)
                    .tag("market", tag)
                    .description("이벤트 sink 누적 나노초(정산과 발행)").register(meterRegistry);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.writer.hook.nanos", loop, SingleWriterLoop::hookNanos)
                    .tag("market", tag)
                    .description("스냅샷 훅 누적 나노초").register(meterRegistry);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.writer.apply.count", loop, SingleWriterLoop::applyCount)
                    .tag("market", tag)
                    .description("적용 호출 수. 위 셋의 분모").register(meterRegistry);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.writer.catchup.calls", loop, SingleWriterLoop::catchUpCalls)
                    .tag("market", tag)
                    .description("갭 회수 저널 왕복 수").register(meterRegistry);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.writer.catchup.nanos", loop, SingleWriterLoop::catchUpNanos)
                    .tag("market", tag)
                    .description("갭 회수 저널 왕복 누적 나노초. 적용·sink 와 겹치지 않는다")
                    .register(meterRegistry);
            return new WriterRouter.Shard(shardEngine, loop);
        });
    }

    @Bean
    public SingleWriterLoop singleWriterLoop(EngineProperties props, MatchingEngineImpl engine,
                                             SettlementService settlementService,
                                             CommandJournalReader commandJournalReader,
                                             CommandPayloadMapper commandPayloadMapper,
                                             LeaderLeaseService leaderLeaseService,
                                             MarketDataPublisher marketDataPublisher,
                                             kr.exchange.api.market.MarketDataService marketDataService,
                                             MeterRegistry meterRegistry) {
        SingleWriterLoop loop = new SingleWriterLoop(
                props.queueCapacity(),
                engine,
                // 정산 커밋 후 WS 게시(§5). 게시 실패는 publisher가 삼키므로 sink 예외
                // (= fail-fast) 축은 정산 실패뿐이다.
                events -> {
                    java.util.List<kr.exchange.engine.core.EngineEvent> settled =
                            settlementService.appendAndSettle(events);
                    // 정산 커밋 뒤, 게시 전에 호가 스냅샷을 갱신한다. 여기가 단일 라이터
                    // 스레드이므로 책을 안전하게 읽을 수 있는 유일한 자리다. 게시와 이어지는
                    // 모든 조회가 이 스냅샷을 보고, DB 집계는 스냅샷이 없을 때만 돈다.
                    publishEngineDepth(engine, marketDataService, settled);
                    marketDataPublisher.publishSettled(settled);
                },
                new JournalCatchUpSource(commandJournalReader, commandPayloadMapper),
                // 정산이 제한 재시도로 흡수하지 못한 실패는 원인 불문 리더십을 함께
                // 내려놓아 접수 경로를 닫는다(LoopFatalHandler, DB 설계 §5.4).
                new LoopFatalHandler(leaderLeaseService));
        // 큐 깊이 Gauge 는 여기서 등록하지 않는다. 접수가 마켓별 라우터로 가므로 이 빈의
        // 루프에는 명령이 들어오지 않는다(ADR 0021 5단계). 종전에는 여기서 라벨 없이
        // 등록했고, Micrometer 가 먼저 등록된 미터를 유지하므로 라우터가 마켓 라벨로
        // 등록한 것이 덮이지 않고 밀려났다. 2026-09-10 실측에서 마켓별 큐가 관측되지
        // 않은 원인이 이것이다. 라이터 스레드는 셋이 돌고 있었고 지표만 옛 것이었다.
        // 직렬 구간 분해 지표는 여기서 등록하지 않는다. 접수가 마켓별 라우터로 가므로
        // 이 빈의 루프에는 명령이 들어오지 않아 값이 늘 0 이다. 큐 깊이와 같은 사고였다.
        // 2026-09-10 실측에서 이 카운터를 읽어 명령 0건이 나왔고, 실제 일은 라우터의
        // 루프 열둘이 하고 있었다. 등록 자리를 라우터 팩토리로 옮긴다.
        return loop;
    }

    /**
     * 배치가 건드린 마켓의 호가 스냅샷을 엔진 메모리에서 만들어 조회 경로에 넘긴다.
     *
     * 단일 라이터 스레드에서만 부른다. OrderBookImpl 의 TreeMap 은 동시 수정에 안전하지 않아
     * 다른 스레드가 책을 직접 읽으면 안 된다. 여기서 불변 객체로 떠서 건네는 이유다.
     *
     * 실패해도 삼킨다. 스냅샷은 최적화이고, 없으면 조회가 종전대로 DB 집계로 내려간다.
     * 여기서 예외를 올리면 정산이 끝난 배치가 sink 실패로 계상되어 리더십까지 내려놓는다.
     */
    private static void publishEngineDepth(MatchingEngineImpl engine,
                                           kr.exchange.api.market.MarketDataService marketDataService,
                                           java.util.List<kr.exchange.engine.core.EngineEvent> settled) {
        try {
            java.util.Set<Integer> touched = new java.util.LinkedHashSet<>();
            for (kr.exchange.engine.core.EngineEvent e : settled) {
                touched.add(e.marketId());
            }
            for (int marketId : touched) {
                kr.exchange.engine.core.OrderBookImpl book = engine.orderBook(marketId);
                if (book == null) {
                    continue;
                }
                marketDataService.publishEngineDepth(marketId,
                        kr.exchange.api.market.MarketDataService.fromEngine(
                                book.askDepth(15), book.bidDepth(15)));
            }
        } catch (RuntimeException ignored) {
            // 스냅샷 실패가 정산 결과를 바꾸면 안 된다. 조회는 DB 집계로 회수된다.
        }
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
                                                     WriterRouter router,
                                                     LeaderLeaseService leaderLeaseService,
                                                     CommandJournalReader commandJournalReader,
                                                     HaAvailability haAvailability,
                                                     ObjectProvider<kr.exchange.engine.FollowerCatchUp> followerCatchUp) {
        PromotionCoordinator coordinator = new PromotionCoordinator(
                engineRecovery, router, leaderLeaseService, commandJournalReader, haAvailability);
        // 따라잡기 정지를 코디네이터보다 먼저 등록한다. 순서가 계약이다. 승격 재생 생략 경로는
        // 공유 엔진 위에서 접수를 여는데, 그 전에 따라잡기 스레드가 그 엔진에서 손을 뗀 것이
        // 확인돼야 한다. 리스너는 등록 순서로 발화한다.
        // 따라잡기 빈은 엔진과 같은 조건에서만 뜬다. 봇 노드는 엔진이 없어 이 빈도 없고,
        // 리더 경쟁에도 끼지 않으므로 정지시킬 대상 자체가 없다. 필수로 받으면 봇이 못 뜬다.
        kr.exchange.engine.FollowerCatchUp catchUp = followerCatchUp.getIfAvailable();
        if (catchUp != null) {
            leaderLeaseService.onLeadershipGained(identity -> catchUp.stopForPromotion());
        }
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

    // 카탈로그는 API 계층이 소유한다. 엔진에는 정지 여부를 묻는 함수 하나만 넘겨 의존 방향을
    // 뒤집지 않는다. 조회는 메모리 스냅샷이라 저널 경로에 DB 왕복이 생기지 않는다.
    @Bean
    public EngineBridge engineBridge(CommandJournalAppender appender, WriterRouter router,
                                     CommandPayloadMapper commandPayloadMapper,
                                     PromotionCoordinator promotionCoordinator,
                                     kr.exchange.api.market.MarketDirectory markets,
                                     EngineProperties props,
                                     MeterRegistry meterRegistry) {
        // ADR 0021 5단계. 접수가 마켓별 라이터로 간다. 종전에는 SingleWriterLoop 하나였다.
        // 라우터가 marketId 로 라우팅하고 그 마켓의 라이터가 없으면 만든다.
        EngineBridge bridge = new EngineBridge(appender, router, commandPayloadMapper,
                promotionCoordinator.gate(),
                marketId -> "HALTED".equals(markets.byId(marketId).status()));
        // 기본 0 이면 상한 없이 기다린다(ADR 0034 S1, EngineProperties).
        bridge.setAcceptLockWait(props.acceptLockWait());
        // 접수 임계구역 분해(정본 13.5 한계 29). 대기가 크면 경합이, 보유가 크면
        // 임계구역 안 작업이 상한이다. rate() 로 초당 소비 시간이 나오고 그것이 점유율이다.
        // 락이 마켓별이라 마켓이 처음 접수될 때 market 태그로 등록한다. 태그 없는 같은 이름을 함께
        // 두면 태그 키 집합이 달라 Prometheus 레지스트리가 뒤에 온 쪽을 버린다(샤드 지표와 같은 사고).
        bridge.onMarketAdded(marketId -> {
            String tag = String.valueOf(marketId);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.submit.lock.wait.nanos", bridge, b -> b.lockWaitNanos(marketId))
                    .tag("market", tag)
                    .description("마켓별 접수 락 획득 대기 누적 나노초").register(meterRegistry);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.submit.lock.hold.nanos", bridge, b -> b.lockHoldNanos(marketId))
                    .tag("market", tag)
                    .description("마켓별 접수 락 보유 누적 나노초").register(meterRegistry);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.submit.count", bridge, b -> b.submitCount(marketId))
                    .tag("market", tag)
                    .description("마켓별 접수 시도 수. 위 둘의 분모").register(meterRegistry);
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.submit.lock.timeouts", bridge, b -> b.lockTimeoutCount(marketId))
                    .tag("market", tag)
                    .description("마켓별 접수 락 대기 상한 초과로 저널 전에 503을 낸 수").register(meterRegistry);
        });
        return bridge;
    }

    /**
     * 팔로워 따라잡기. 기동 재생이 체크포인트로 상한되므로 그 뒤를 이 빈이 잇는다.
     * 엔진과 같은 조건(exchange.engine.enabled)에서만 뜬다. 봇 노드는 엔진이 없으므로 없다.
     */
    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "exchange.engine", name = "enabled", matchIfMissing = true)
    public kr.exchange.engine.FollowerCatchUp followerCatchUp(
            kr.exchange.journal.ReplayService replayService, MatchingEngineImpl engine,
            LeaderLeaseService lease, EngineProperties props, MeterRegistry meterRegistry) {
        return new kr.exchange.engine.FollowerCatchUp(replayService, engine, lease,
                props.followerCatchUpInterval(), meterRegistry);
    }

    // 봇 전용 노드는 엔진을 끄고 리더 경쟁에서 빠진다(격리 토폴로지에서 봇이 죽인 리더로
    // 승격 주체가 오염되지 않게). 기본값 true라 기존 dev·통합테스트·단일 노드는 무변경이다.
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "exchange.engine", name = "enabled", matchIfMissing = true)
    public SmartLifecycle engineRuntimeLifecycle(LeaderLeaseService lease, WriterRouter router,
                                                 EngineRecovery recovery,
                                                 MatchingEngineImpl engine,
                                                 PromotionCoordinator promotionCoordinator,
                                                 kr.exchange.engine.FollowerCatchUp followerCatchUp,
                                                 JdbcClient jdbcClient,
                                                 MeterRegistry meterRegistry) {
        // 리더십 상실 확정 시 self-shutdown: 잔여 큐를 버리고 즉시 정지한다(DB 설계 §5.4).
        lease.onLeadershipLost(router::haltAll);
        java.util.concurrent.atomic.AtomicInteger bandLatched = new java.util.concurrent.atomic.AtomicInteger();
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                // 명령 채번 축은 앱 설정이 아니라 DB 래치가 정한다(ADR 0022 6단계). 기동 시점 값이라 운영 중 래치 전환은
                // 다음 기동에서 보인다. 래치 표가 없으면 이 앱이 부르는 6인자 판도 없으므로 여기서 기동이 선다.
                boolean latched = jdbcClient.sql(
                                "SELECT EXISTS (SELECT 1 FROM journal_cutover WHERE name = 'command_seq')")
                        .query(Boolean.class).single();
                bandLatched.set(latched ? 1 : 0);
                io.micrometer.core.instrument.Gauge
                        .builder("exchange.journal.band.latched", bandLatched,
                                java.util.concurrent.atomic.AtomicInteger::get)
                        .description("기동 시점 명령 대역 채번 래치. 1 이면 마켓 대역 채번, 0 이면 전역 채번이다")
                        .register(meterRegistry);
                log.info("명령 채번 축: {} (journal_cutover command_seq 래치 {})",
                        latched ? "마켓 대역" : "전역", latched ? "있음" : "없음");
                // 재생·크래시 창 재적재 실패는 기동 실패다. 검증 안 된 상태로 트래픽을 받지
                // 않고, pending을 버린 기동은 저널 prefix 성질을 깨므로 금지다(§5.5 ②).
                // 강제 종료 직후 재기동은 직전 프로세스 리스가 TTL 내 유효할 수 있어 부팅
                // 경로는 만료 대기 후 승격을 재시도한다(EngineRecovery.recoverAtBoot).
                recovery.recoverAtBoot(engine);
                // 복구는 엔진 하나 위에서 마켓 벡터로 하고(ADR 0022 5-4), 그 안의 북과 마켓 커서를
                // 마켓별 라이터에 나눠 담는다. 전역 최댓값을 심으면 뒤처진 마켓의 명령이 커서 아래로 보인다.
                router.installFrom(engine);
                router.startAll();
                // 부팅 시점에 리더면 즉시 접수 개방, 아니면 팔로워로 게이트를 닫는다. arm 이후의
                // gain(런타임 승격)만 fresh 엔진 선복원 경로를 탄다(복구 RTO §3-a).
                promotionCoordinator.bootstrap(engine, lease.isLeader());
                promotionCoordinator.arm();
                lease.start();
                // 리스보다 뒤에 시작한다. 앞서 시작하면 리더 여부가 정해지기 전에 첫 틱이 돌아
                // 부팅 리더의 엔진을 만질 수 있다.
                followerCatchUp.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                // 리스보다 먼저 멈춘다. 리스가 먼저 멈추면 isLeader()가 false로 남아 따라잡기가
                // 종료 중인 엔진을 계속 만진다.
                followerCatchUp.stop();
                lease.stop();
                try {
                    router.stopAll();
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

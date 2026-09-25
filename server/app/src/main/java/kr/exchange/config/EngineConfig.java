package kr.exchange.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.ws.MarketDataPublisher;
import kr.exchange.engine.CommandPayloadMapper;
import kr.exchange.engine.EngineBridge;
import kr.exchange.engine.EngineRecovery;
import kr.exchange.engine.FollowerCatchUp;
import kr.exchange.engine.HaAvailability;
import kr.exchange.engine.JournalCatchUpSource;
import kr.exchange.engine.LeaderLeaseService;
import kr.exchange.engine.LeaderStallDetector;
import kr.exchange.engine.LeaderWriteProgress;
import kr.exchange.engine.LoopFatalHandler;
import kr.exchange.engine.NodeServiceHealth;
import kr.exchange.engine.PartitionAcquirePolicy;
import kr.exchange.engine.PartitionBreaker;
import kr.exchange.engine.PartitionLeases;
import kr.exchange.engine.PartitionPlan;
import kr.exchange.engine.PartitionRuntime;
import kr.exchange.engine.PromotionCoordinator;
import kr.exchange.engine.ShardSlot;
import kr.exchange.engine.WriterRouter;
import kr.exchange.engine.core.MatchingEngineImpl;
import kr.exchange.engine.core.SingleWriterLoop;
import kr.exchange.engine.lease.FencingEpochPublisher;
import kr.exchange.engine.lease.LeaseStoreProvider;
import kr.exchange.journal.CommandJournalAppender;
import kr.exchange.journal.CommandJournalReader;
import kr.exchange.journal.EventJournalReader;
import kr.exchange.journal.ReplayService;
import kr.exchange.ledger.MarketCatalog;
import kr.exchange.ledger.SettlementService;
import kr.exchange.snapshot.SnapshotCapture;
import kr.exchange.snapshot.SnapshotPersister;
import kr.exchange.snapshot.SnapshotStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties({EngineProperties.class, SnapshotProperties.class})
public class EngineConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EngineConfig.class);

    @Bean
    public LeaderWriteProgress leaderWriteProgress() {
        return new LeaderWriteProgress();
    }

    // 노드 층 좀비 리더 판정(§5.4, §5.4-c 8). 요청 풀 전량 점유와 리더 쓰기 커밋 진행 0이 동시에 지속되면 이 노드는
    // 일을 못 하는 상태다. 요청 풀을 봐야 하므로 @Primary DataSource를 그대로 받는다. 강등 수는 파티션 강등(STALL)이 센다.
    @Bean
    public NodeServiceHealth nodeServiceHealth(@Qualifier("dataSource") DataSource requestDataSource,
                                               LeaderWriteProgress leaderWriteProgress,
                                               EngineProperties props,
                                               MeterRegistry meterRegistry) {
        return LeaderStallDetector.node(requestDataSource, leaderWriteProgress,
                props.leaseStanddownAfter(), meterRegistry);
    }

    /**
     * 기동 때 한 번 읽는 리더 모드(ADR 0034 S3, S3 설계 4-9). 게이트웨이(ApiConfig.orderGateway)와 append 가 같은 값을 쓴다.
     * 모드 전환(set_leader_protocol)은 정지 창에서 앱을 멈춘 채 하고 다시 띄우므로 실행 중에 다시 읽지 않는다.
     *
     * 조회 역할 노드는 읽지 않고 1 로 두며 게이지를 내지 않는다(S3 설계 4-15). 그 노드는 접수와 append 를 하지 않고, 로그인이
     * leader_protocol 을 읽지 못해 읽으면 기동마다 권한 오류 경고와 실제 모드와 무관한 게이지 1 이 남는다.
     */
    @Bean
    public kr.exchange.engine.lease.LeaderProtocolBoot leaderProtocolBoot(JdbcClient jdbcClient,
                                                                         MeterRegistry meterRegistry,
                                                                         org.springframework.core.env.Environment environment) {
        if (ReadRoleConfig.isReadRole(environment)) {
            return kr.exchange.engine.lease.LeaderProtocolBoot.notRead("조회 역할 노드");
        }
        kr.exchange.engine.lease.LeaderProtocolBoot boot = kr.exchange.engine.lease.LeaderProtocolBoot.read(jdbcClient);
        io.micrometer.core.instrument.Gauge
                .builder("exchange.leader.protocol.boot", boot, b -> b.protocol())
                .description("기동 때 읽은 리더 모드(1 단일 리더, 2 접수 시점 예약). 전환 뒤 두 노드가 같은 값인지 본다")
                .register(meterRegistry);
        return boot;
    }

    /**
     * 파티션 계획(DB 설계 §5.4-c 1). 기동 모드로 한 번 만든다. 조회 역할 노드는 DB 를 읽지 않는 한 파티션 계획이다. 그 노드가
     * 서빙하는 경로는 epoch 를 싣지 않는다.
     */
    @Bean
    public PartitionPlan partitionPlan(kr.exchange.engine.lease.LeaderProtocolBoot leaderProtocolBoot,
                                       JdbcClient jdbcClient,
                                       org.springframework.core.env.Environment environment) {
        if (ReadRoleConfig.isReadRole(environment)) {
            return PartitionPlan.single();
        }
        return PartitionPlan.forBoot(leaderProtocolBoot, jdbcClient);
    }

    /**
     * 파티션마다 리스 서비스 하나(DB 설계 §5.4-c 2). holder 는 노드에 하나이고 모든 파티션이 같이 쓴다. 엔진을 끈 노드도
     * 만든다. epoch·하네스·관리 API 가 쓰기 때문이다. 리스 틱을 시작하는 것은 엔진을 켠 노드의 기동 수명주기뿐이다.
     *
     * 리스 갱신은 노드 층과 파티션 층 좀비 판정을 모두 통과해야 한다(§5.4-c 8). 파티션 층 붕괴는 그 파티션만, 노드 층 붕괴는
     * 그 노드가 쥔 모든 파티션을 STALL 로 강등한다. 요청 풀이 포화돼도 하트비트가 굶지 않게 리스는 전용 소형 풀을 쓴다
     * (LeaseDataSourceConfig, DB 설계 §5.4). 저널·정산 등 승격 이후 쓰기 경로는 그대로 기본 풀·트랜잭션을 타므로 fencing
     * 원자성 경계는 불변이다.
     */
    @Bean
    public PartitionLeases partitionLeases(PartitionPlan partitionPlan, LeaseStoreProvider leaseStores,
                                           FencingEpochPublisher fencingEpochPublisher, EngineProperties props,
                                           LeaderPartitionProperties partitionProps,
                                           NodeServiceHealth nodeServiceHealth, MeterRegistry meterRegistry) {
        String holder = UUID.randomUUID().toString();
        java.util.concurrent.atomic.AtomicReference<PartitionLeases> built =
                new java.util.concurrent.atomic.AtomicReference<>();
        PartitionLeases leases = new PartitionLeases(partitionPlan, holder, resource -> {
            LeaderWriteProgress partitionProgress = new LeaderWriteProgress();
            LeaderStallDetector partitionHealth = LeaderStallDetector.partition(resource, partitionProgress,
                    props.leaseStanddownAfter(), meterRegistry);
            NodeServiceHealth health = new NodeServiceHealth() {
                @Override
                public boolean serviceable() {
                    // 둘 다 표본을 뜬다. 한쪽만 부르면 다른 층의 누적 창이 갱신 사이에 쌓이지 않는다.
                    boolean node = nodeServiceHealth.serviceable();
                    boolean partition = partitionHealth.serviceable();
                    return node && partition;
                }

                @Override
                public void onLeadershipGained() {
                    partitionHealth.onLeadershipGained();
                    // 노드 층 창은 이 노드가 처음 리더가 될 때만 다시 세운다. 이미 쥔 파티션이 있으면 그 파티션의 증거가 쌓여 있다.
                    PartitionLeases all = built.get();
                    if (all == null || all.all().stream()
                            .noneMatch(l -> !l.resource().equals(resource) && l.isLeader())) {
                        nodeServiceHealth.onLeadershipGained();
                    }
                }
            };
            PartitionAcquirePolicy policy = partitionPlan.partitioned() && !partitionProps.prefers(resource)
                    ? PartitionAcquirePolicy.nonPreferred(props.leaseTtl()) : PartitionAcquirePolicy.ALWAYS;
            LeaderLeaseService lease = new LeaderLeaseService(resource, holder, leaseStores.store(resource),
                    fencingEpochPublisher, props.leaseTtl(), props.leaseTickInterval(), health, policy,
                    partitionProgress);
            // 회복 가능 반납 카운터(§5-2). 강등 축(exchange.leader.standdown)과 분리한다. 이 등급이 만드는 실패 모드는
            // [재생 실패 -> 반납 -> 재승격]의 반복이라, 합산하면 자발적 강등 1회와 구분되지 않는다.
            io.micrometer.core.instrument.Counter recoverableStandDowns =
                    io.micrometer.core.instrument.Counter
                            .builder("exchange.leader.recoverable_standdown")
                            .description("승격 재생 실패로 리더십을 회복 가능하게 반납한 횟수")
                            .tag("partition", resource)
                            .register(meterRegistry);
            lease.onRecoverableStandDown(recoverableStandDowns::increment);
            return lease;
        });
        built.set(leases);
        return leases;
    }

    /** 파티션 상태 조회와 운영자 조작(API 설계 §6-C). 쓰기는 loopback 만 받는다. */
    @Bean
    public kr.exchange.engine.PartitionsEndpoint partitionsEndpoint(PartitionLeases partitionLeases) {
        return new kr.exchange.engine.PartitionsEndpoint(partitionLeases);
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
     *
     * 리스 서비스를 받지 않는다. 접수는 그 마켓 파티션 게이트가 연 기간으로 쓴다(DB 설계 §5.4-c 6).
     */
    @Bean
    public CommandJournalAppender commandJournalAppender(
            @org.springframework.beans.factory.annotation.Qualifier("appendDataSource")
            javax.sql.DataSource appendDataSource,
            MeterRegistry meterRegistry,
            LeaderWriteProgress leaderWriteProgress,
            EngineProperties props,
            kr.exchange.engine.lease.LeaderProtocolBoot leaderProtocolBoot) {
        // JdbcClient 와 TransactionTemplate 을 빈으로 내놓지 않고 여기서 만든다. 빈으로 두면
        // 그 타입의 후보가 둘이 되고, 한정자 없이 타입으로 주입받는 다른 배선이 이름으로
        // 아무거나 문다. 실제로 그렇게 관리 평면의 승인 롤백이 append 풀의 트랜잭션
        // 관리자를 물어 롤백이 무효가 됐다(AdminPlaneIntegrationTest 두 건).
        CommandJournalAppender appender = new CommandJournalAppender(
                JdbcClient.create(appendDataSource),
                new TransactionTemplate(
                        new org.springframework.jdbc.support.JdbcTransactionManager(appendDataSource)),
                null, meterRegistry, leaderWriteProgress);
        // 코드 기본 0 이면 걸지 않는다. dev·prod 문서가 켠다(S4 설계 4-12).
        appender.setLockTimeout(props.acceptLockTimeout());
        // P2 로 뜬 노드는 DB 가 예약하지 않은 주문 생성 명령을 커밋하지 않는다(게이트웨이가 in-flight 누계를 끄기 때문).
        appender.setRequireReservation(leaderProtocolBoot.reservesAtIntake());
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
                                               ObjectMapper objectMapper,
                                               MarketCatalog marketCatalog,
                                               CommandPayloadMapper commandPayloadMapper,
                                               LeaderWriteProgress leaderWriteProgress,
                                               PartitionPlan partitionPlan,
                                               MeterRegistry meterRegistry) {
        // 리스 서비스를 받지 않는다. 정산은 루프와 승격 재생의 기간으로 쓴다(DB 설계 §5.4-c 6).
        SettlementService settlement = new SettlementService(jdbcClient, transactionTemplate,
                null, objectMapper, marketCatalog, commandPayloadMapper,
                leaderWriteProgress);
        // 수수료 샤드는 P3 에서만 파티션 번호다(DB 설계 §5.4-c 11). S4 는 P2 에서 운영하므로 0 이다.
        settlement.setPartitionPlan(partitionPlan);
        // 0 이 아니면 정산이 잠금 경합이나 문장 취소를 만났다는 뜻이다. 강등 없이 흡수했는지는 아래 소진 수와 함께 본다.
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.settlement.transient_retries", settlement,
                        SettlementService::transientRetries)
                .description("정산이 일시 DB 오류(55P03·57014·40P01·40001)로 배치를 다시 돌린 횟수")
                .register(meterRegistry);
        // 1 이상이면 재시도로 흡수하지 못한 일시 오류가 그 마켓 라이터를 세우고 그 파티션을 강등했다. 경보 대상이다.
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.settlement.transient_exhausted", settlement,
                        SettlementService::transientExhausted)
                .description("정산 일시 DB 오류가 재시도 제한(시도 4회 또는 경과 5초)을 넘겨 라이터로 올라간 횟수")
                .register(meterRegistry);
        // 재시도의 SQLSTATE 별 몫. 잠금 계획(ADR 0034 S3) 뒤 40P01 몫은 결함 신호라 따로 본다.
        for (String state : java.util.List.of("55P03", "57014", "40P01", "40001")) {
            io.micrometer.core.instrument.FunctionCounter
                    .builder("exchange.settlement.transient_retries_by_state", settlement,
                            s -> s.transientRetries(state))
                    .tag("sqlstate", state)
                    .description("정산 일시 DB 오류 재시도 수(SQLSTATE 별)")
                    .register(meterRegistry);
        }
        // 0 이어야 한다. P2 에서는 접수가 예약하므로 정산 시점 23514 는 버그 신호다(경보 SettlementInsufficientFunds).
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.settlement.insufficient_funds", settlement, SettlementService::insufficientFunds)
                .description("정산이 잔고 부족(23514 chk_balance_nonneg)으로 끝난 수")
                .register(meterRegistry);
        // 0 이어야 한다. 정산 게시가 잠금 계획 밖 계정을 쓰면 교착 가능성이 돌아온다(S3 설계 4-13).
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.settlement.lock_plan.miss", settlement, SettlementService::lockPlanMisses)
                .description("정산 게시가 잠금 계획 밖 계정을 쓴 수")
                .register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.settlement.lock_plan.taken", settlement, SettlementService::lockPlansTaken)
                .description("정산 배치가 잠금 계획을 잡은 수")
                .register(meterRegistry);
        return settlement;
    }

    /**
     * 정산 전 예약 감시(ADR 0034 S3, S3 설계 4-16). 조회 역할 노드를 뺀 모든 노드에서 15초마다 계산한다. 하네스 설정·리더
     * 여부와 무관하다. 기본 풀의 짧은 읽기 트랜잭션 하나다.
     *
     * 조회 역할 노드(prod,read)의 로그인은 assets·markets·trades 만 읽는다(deploy/provision-runtime-roles.sql). 그 노드에서
     * 돌면 질의가 매번 권한 오류로 끝나 15초마다 경고가 쌓이고 두 게이지가 늘 NaN 이다. 그래서 빈을 만들지 않는다.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @org.springframework.context.annotation.Conditional(ReadRoleConfig.NotReadRoleCondition.class)
    public kr.exchange.ledger.ReservationBacklogMonitor reservationBacklogMonitor(
            JdbcClient jdbcClient, TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        return new kr.exchange.ledger.ReservationBacklogMonitor(jdbcClient, transactionTemplate, meterRegistry);
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
                                         SnapshotStore snapshotStore,
                                         SnapshotProperties snapshotProps,
                                         MeterRegistry meterRegistry,
                                         JdbcClient jdbcClient) {
        // 크래시 창 재적재는 라이브 sink와 동일한 append+정산 원자 경로를 쓰고 승격의 기간으로 쓴다(§5.5 ②, §5.4-c 6).
        // 스냅샷 활성 시 로드 후 tail 재생으로 복구·승격 재생량을 상한한다(§3-b).
        // 재생 명령 수 Counter(§5-2). 기동 복구·승격 재생·재구성 모두 recover를 거치므로 단일 관측점이다.
        io.micrometer.core.instrument.Counter replayCommands = io.micrometer.core.instrument.Counter
                .builder("exchange.journal.replay.commands")
                .description("복구·승격 재생으로 재적용한 명령 수").register(meterRegistry);
        EngineRecovery recovery = new EngineRecovery(replayService,
                settlementService::appendAndSettle,
                snapshotProps.enabled() ? snapshotStore : null,
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

    // 주기 스냅샷 영속화 워커. 캡처는 라이터 팩토리가 마켓마다 걸고 이 워커 하나가 적재한다(§3-b).
    // 비활성이면 훅을 배선하지 않아 받을 캡처가 없다.
    @Bean(destroyMethod = "close")
    public SnapshotPersister snapshotPersister(SnapshotStore snapshotStore) {
        return new SnapshotPersister(snapshotStore);
    }

    /**
     * 마켓별 라이터 묶음(ADR 0021 5단계, 파티션 단위는 DB 설계 §5.4-c 7).
     *
     * 마켓마다 자리 하나를 두고, 루프와 엔진은 승격이 그 파티션의 기간과 함께 설치할 때 새로 만든다. 새 루프의 정산 sink,
     * 치명 처리기, 스냅샷 훅이 그 기간을 쥔다. 인스턴스는 늘리지 않는다. 같은 노드 안 스레드 N개다. 정산이 직렬 구간의
     * 99.9퍼센트를 차지하므로 그것을 마켓마다 따로 돌리면 마켓 수만큼 커넥션이 동시에 붙는다.
     *
     * 지표는 자리에 market 라벨로 묶는다(ShardSlot). 라벨 없이 등록하면 두 번째 마켓에서 미터 id 가 충돌해 첫 루프만
     * 관측되고, 루프에 묶으면 재구성 뒤 옛 루프에 묶인 채 남는다.
     */
    @Bean
    public WriterRouter writerRouter(EngineProperties props,
                                     SettlementService settlementService,
                                     CommandJournalReader commandJournalReader,
                                     CommandPayloadMapper commandPayloadMapper,
                                     PartitionLeases partitionLeases,
                                     MarketDataPublisher marketDataPublisher,
                                     kr.exchange.api.market.MarketDataService marketDataService,
                                     SnapshotProperties snapshotProps,
                                     SnapshotPersister snapshotPersister,
                                     MeterRegistry meterRegistry) {
        WriterRouter router = new WriterRouter((marketId, engine, term, slot) -> {
            PartitionRuntime runtime = partitionLeases.runtime(term.resource()).orElseThrow(
                    () -> new IllegalStateException("수명주기 없는 파티션의 라이터: " + term.resource()));
            SingleWriterLoop loop = new SingleWriterLoop(
                    props.queueCapacity(),
                    engine,
                    events -> {
                        java.util.List<kr.exchange.engine.core.EngineEvent> settled =
                                settlementService.appendAndSettle(term, events);
                        // 정산 커밋 뒤, 게시 전에 호가 스냅샷을 갱신한다. 여기가 단일 라이터 스레드이므로 책을 안전하게 읽을
                        // 수 있는 유일한 자리다. 게시와 이어지는 모든 조회가 이 스냅샷을 보고, DB 집계는 스냅샷이 없을 때만 돈다.
                        // 스냅샷과 게시는 이 루프의 기간 epoch 를 싣는다(S6 설계 4-4, 4-6).
                        publishEngineDepth(engine, marketDataService, term.epoch(), settled);
                        marketDataPublisher.publishSettled(term, settled);
                    },
                    new JournalCatchUpSource(commandJournalReader, commandPayloadMapper, marketId),
                    // 정산이 제한 재시도로 흡수하지 못한 실패는 그 파티션을 강등해 접수 경로를 닫는다(LoopFatalHandler).
                    new LoopFatalHandler(runtime, term));
            if (snapshotProps.enabled()) {
                // 캡처 판정 상태를 샤드마다 둔다. 한 인스턴스를 샤드 여럿에 걸면 커서가 가장 높은 마켓의 캡처가
                // 나머지 마켓의 판정을 막는다(ADR 0022 7단계).
                loop.setSnapshotHook(new SnapshotCapture(snapshotPersister, term, marketId,
                        snapshotProps.commandInterval(), snapshotProps.timeInterval(),
                        snapshotProps.queueCapacity(), slot.snapshotCounters()));
            }
            return new WriterRouter.Shard(engine, loop);
        });
        // 게이트가 열린 파티션의 새 마켓은 첫 접수 때 그 기간으로 루프를 띄운다.
        router.setOpenTermLookup(partitionLeases::openTerm);
        router.onSlotAdded(slot -> registerSlotMeters(slot, snapshotProps.enabled(), meterRegistry));
        return router;
    }

    private static void registerSlotMeters(ShardSlot slot, boolean snapshots, MeterRegistry meterRegistry) {
        String tag = String.valueOf(slot.marketId());
        io.micrometer.core.instrument.Gauge
                .builder("exchange.sequencer.queue.size", slot, ShardSlot::queueSize)
                .tag("market", tag)
                .description("마켓별 라이터 명령 큐 깊이(백프레셔)").register(meterRegistry);
        // 직렬 구간 분해(정본 13.5 한계 29). 누적 나노초를 그대로 낸다. rate() 로 초당 소비 시간이 나오고 그것이 직렬
        // 점유율이다. 1.0 이면 그 마켓의 라이터가 그 구간만으로 꽉 찬다.
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.writer.apply.nanos", slot, ShardSlot::applyNanos)
                .tag("market", tag)
                .description("매칭 적용 누적 나노초").register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.writer.sink.nanos", slot, ShardSlot::sinkNanos)
                .tag("market", tag)
                .description("이벤트 sink 누적 나노초(정산과 발행)").register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.writer.hook.nanos", slot, ShardSlot::hookNanos)
                .tag("market", tag)
                .description("스냅샷 훅 누적 나노초").register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.writer.apply.count", slot, ShardSlot::applyCount)
                .tag("market", tag)
                .description("적용 호출 수. 위 셋의 분모").register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.writer.catchup.calls", slot, ShardSlot::catchUpCalls)
                .tag("market", tag)
                .description("갭 회수 저널 왕복 수").register(meterRegistry);
        io.micrometer.core.instrument.FunctionCounter
                .builder("exchange.writer.catchup.nanos", slot, ShardSlot::catchUpNanos)
                .tag("market", tag)
                .description("갭 회수 저널 왕복 누적 나노초. 적용·sink 와 겹치지 않는다")
                .register(meterRegistry);
        if (snapshots) {
            slot.snapshotCounters().register(meterRegistry, slot.marketId());
        }
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
                                           kr.exchange.api.market.MarketDataService marketDataService, long epoch,
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
                marketDataService.publishEngineDepth(marketId, epoch,
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
    // 게이지는 파티션마다 내고 readiness 는 파티션 가운데 하나라도 접수 중이면 ACCEPTING 이다(DB 설계 §5.4-c 14).
    @Bean
    public HaAvailability haAvailability(ApplicationEventPublisher publisher,
                                         ApplicationAvailability applicationAvailability,
                                         MeterRegistry meterRegistry,
                                         PartitionPlan partitionPlan,
                                         @Value("${exchange.node.id:${spring.application.name:exchange}}")
                                         String nodeId) {
        return new HaAvailability(publisher, applicationAvailability, meterRegistry, nodeId,
                partitionPlan.partitions());
    }

    // 카탈로그는 API 계층이 소유한다. 엔진에는 정지 여부를 묻는 함수 하나만 넘겨 의존 방향을
    // 뒤집지 않는다. 조회는 메모리 스냅샷이라 저널 경로에 DB 왕복이 생기지 않는다.
    @Bean
    public EngineBridge engineBridge(CommandJournalAppender appender, WriterRouter router,
                                     CommandPayloadMapper commandPayloadMapper,
                                     PartitionLeases partitionLeases,
                                     kr.exchange.api.market.MarketDirectory markets,
                                     EngineProperties props,
                                     MeterRegistry meterRegistry) {
        // 접수가 마켓별 라이터로 간다(ADR 0021 5단계). 게이트는 마켓 파티션이 연 기간이다(DB 설계 §5.4-c 6). 엔진을 끈
        // 노드는 수명주기가 없어 게이트가 늘 닫혀 있다.
        EngineBridge bridge = new EngineBridge(appender, router, commandPayloadMapper,
                partitionLeases,
                marketId -> "HALTED".equals(markets.byId(marketId).status()));
        // 코드 기본 0 이면 상한 없이 기다린다. dev·prod 문서가 켠다(S4 설계 4-12).
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
                    .description("마켓별 접수 락 대기 상한 초과로 저널 전에 429 를 낸 수").register(meterRegistry);
        });
        return bridge;
    }

    /**
     * 팔로워 따라잡기(마켓별 엔진). 기동 복구가 대조한 엔진을 기동 수명주기가 넣는다. 엔진과 같은 조건
     * (exchange.engine.enabled)에서만 뜬다. 봇 노드는 엔진이 없으므로 없다.
     */
    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "exchange.engine", name = "enabled", matchIfMissing = true)
    public FollowerCatchUp followerCatchUp(ReplayService replayService, PartitionPlan partitionPlan,
                                           EngineProperties props, MeterRegistry meterRegistry) {
        return new FollowerCatchUp(replayService, partitionPlan, Map.of(),
                props.followerCatchUpInterval(), meterRegistry);
    }

    /**
     * 엔진 기동 수명주기(DB 설계 §5.4-c 4). 파티션마다 승격 조율과 수명주기를 만들고, 기동 때 모든 마켓을 물질화 경계까지
     * 대조만 한 엔진을 팔로워에 넣은 뒤 파티션별 리스 틱을 시작한다. 기동은 늘 팔로워이고 첫 획득은 런타임 승격 경로를 탄다.
     *
     * 봇 전용 노드는 엔진을 끄고 리더 경쟁에서 빠진다(격리 토폴로지에서 봇이 죽인 리더로 승격 주체가 오염되지 않게).
     * 기본값 true라 기존 dev·통합테스트·단일 노드는 엔진을 켠다.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "exchange.engine", name = "enabled", matchIfMissing = true)
    public SmartLifecycle engineRuntimeLifecycle(PartitionLeases partitionLeases, WriterRouter router,
                                                 EngineRecovery recovery,
                                                 FollowerCatchUp followerCatchUp,
                                                 HaAvailability haAvailability,
                                                 CommandJournalReader commandJournalReader,
                                                 LeaderPartitionProperties partitionProps,
                                                 JdbcClient jdbcClient,
                                                 MeterRegistry meterRegistry) {
        PartitionPlan plan = partitionLeases.plan();
        // 두 파티션을 한꺼번에 얻은 노드가 재생 두 벌을 동시에 돌려 요청 풀을 채우지 않게 승격을 줄 세운다.
        ExecutorService promotionWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine-promotion");
            t.setDaemon(true);
            return t;
        });
        List<PartitionRuntime> runtimes = new ArrayList<>();
        for (String resource : plan.partitions()) {
            LeaderLeaseService lease = partitionLeases.get(resource);
            HaAvailability.Partition availability = haAvailability.partition(resource);
            java.util.function.IntPredicate scope = plan.scope(resource);
            PromotionCoordinator coordinator = new PromotionCoordinator(resource, scope, recovery, router, lease,
                    commandJournalReader, availability, followerCatchUp, promotionWorker);
            runtimes.add(new PartitionRuntime(resource, scope, lease, coordinator, router, followerCatchUp, recovery,
                    new PartitionBreaker(partitionProps.maxDemotions(), partitionProps.demotionWindow()),
                    availability, partitionLeases, partitionProps.participates(resource),
                    partitionProps.prefers(resource), meterRegistry));
        }
        followerCatchUp.onDiverged(resource -> partitionLeases.runtime(resource)
                .ifPresent(PartitionRuntime::onDiverged));
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
                // 기동은 늘 팔로워다(DB 설계 §5.4-c 4). 모든 마켓을 물질화 경계까지 대조만 하고 리스를 얻지 않으며 크래시
                // 창을 재적재하지 않는다. 재생 대조 실패는 기동 실패다. 검증 안 된 상태로 트래픽을 받지 않는다.
                MatchingEngineImpl booted = new MatchingEngineImpl();
                recovery.recoverAtBoot(booted);
                Map<Integer, MatchingEngineImpl> engines = FollowerCatchUp.splitByMarket(booted);
                followerCatchUp.seed(engines);
                followerCatchUp.start();
                log.info("팔로워로 기동: 마켓 {}, 파티션 {}", new TreeMap<>(engines).keySet(), plan.partitions());
                // 팔로워가 엔진을 넘겨받은 뒤 리스 틱을 시작한다. 첫 획득은 런타임 승격(인계와 M(P) 재생)을 탄다.
                for (PartitionRuntime runtime : runtimes) {
                    runtime.start();
                }
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                // 리스보다 먼저 멈춘다. 리스가 먼저 멈추면 따라잡기가 종료 중인 엔진을 계속 만진다.
                followerCatchUp.stop();
                for (PartitionRuntime runtime : runtimes) {
                    runtime.stop();
                }
                try {
                    router.stopAll();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                promotionWorker.shutdownNow();
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}

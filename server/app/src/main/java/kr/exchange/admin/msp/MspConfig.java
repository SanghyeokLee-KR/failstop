package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.ApprovalService;
import kr.exchange.admin.BotRateControl;
import kr.exchange.admin.CapacityPlanService;
import kr.exchange.admin.CapacityProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 관제센터 모듈 배선(설계 3절). 관리 평면과 관제센터를 둘 다 켠 JVM 에서만 빈이 생긴다. 끈 배포에서는
 * /admin/v1/msp/** 가 매핑되지 않아 404 이고, 폴러·게이지가 없으며, 용량 권고는 새 표를 읽지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = {"exchange.admin.enabled", "exchange.msp.enabled"}, havingValue = "true")
@EnableConfigurationProperties(MspProperties.class)
public class MspConfig {

    // 켠 배포가 기동할 때 있어야 하는 표(V70~V73).
    static final List<String> TABLES = List.of("ops_thresholds", "ops_threshold_changes", "ops_incidents",
            "ops_incident_events", "ops_ai_limits", "ops_ai_limit_changes", "ops_ai_mode", "ops_maintenance_windows",
            "ops_ai_decisions", "ops_ai_actions", "ops_notifications", "ops_runner_lease");
    // 게이지를 표에 다시 맞추는 주기(설계 5-3)
    private static final long GAUGE_REFRESH_SECONDS = 30;
    // 통지 보관함을 보고 서비스로 넘기는 주기(설계 7-12)
    private static final long NOTIFY_SECONDS = 5;

    /** 설정과 표를 먼저 확인했다는 표식. 적용 순서의 실수를 실행 중의 500 으로 미루지 않는다. */
    public static final class MspReady {
    }

    @Bean
    public MspReady mspReady(MspProperties props, JdbcClient jdbc) {
        props.validate();
        requireSchema(jdbc);
        return new MspReady();
    }

    static void requireSchema(JdbcClient jdbc) {
        List<String> missing = TABLES.stream()
                .filter(t -> !jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param("public." + t)
                        .query(Boolean.class).single())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("exchange.msp.enabled=true 인데 관제센터 표가 없다: " + missing
                    + ". V70~V73 적용을 먼저 확인한다(DB 설계 §10.6)");
        }
    }

    @Bean
    public MspWorkers mspWorkers() {
        return new MspWorkers();
    }

    /** 원천 읽기의 공유 캐시(설계 4-6). 화면이 늘어도 원천 호출 수가 늘지 않게 한다. */
    @Bean
    public SourceCache mspSourceCache(MspReady ready, MeterRegistry registry) {
        return new SourceCache(Clock.systemUTC(), registry);
    }

    @Bean
    public OpsThresholdService opsThresholdService(MspReady ready, JdbcClient jdbc,
                                                   PlatformTransactionManager txManager, ApprovalService approvals,
                                                   AdminAuditService audit, MeterRegistry registry,
                                                   CapacityProperties capacity, MspWorkers workers) {
        OpsThresholdService service = new OpsThresholdService(jdbc, new TransactionTemplate(txManager), approvals,
                audit, registry);
        // 설정의 권고 임계는 레지스트리의 환경 기본값이 된다. 사람이 바꾼 항목에는 적용하지 않는다(설계 5-4).
        service.applyConfigDefaults(BigDecimal.valueOf(capacity.getScaleOutCpu()),
                BigDecimal.valueOf(capacity.getScaleInCpu()));
        workers.scheduler().scheduleWithFixedDelay(service::refreshGauge, GAUGE_REFRESH_SECONDS,
                GAUGE_REFRESH_SECONDS, TimeUnit.SECONDS);
        return service;
    }

    @Bean
    public IncidentStore incidentStore(MspReady ready, JdbcClient jdbc, PlatformTransactionManager txManager,
                                       ObjectMapper mapper) {
        return new IncidentStore(jdbc, new TransactionTemplate(txManager), mapper);
    }

    @Bean
    @ConditionalOnExpression("'${exchange.msp.prometheus-url:}'.trim() != ''")
    public PrometheusClient mspPrometheusClient(MspReady ready, MspProperties props, ObjectMapper mapper,
                                                MspWorkers workers, SourceCache cache) {
        return new PrometheusClient(props.prometheusUri(), props.getPrometheus(), props.getQueryTimeout(),
                props.getCacheTtl(), mapper, workers.queries(), cache);
    }

    @Bean
    @ConditionalOnExpression("'${exchange.msp.alertmanager-url:}'.trim() != ''")
    public AlertmanagerClient mspAlertmanagerClient(MspReady ready, MspProperties props, ObjectMapper mapper) {
        return new AlertmanagerClient(props.alertmanagerUri(), props.getQueryTimeout(), mapper);
    }

    @Bean
    @ConditionalOnExpression("'${exchange.msp.alertmanager-url:}'.trim() != ''")
    public IncidentPoller incidentPoller(AlertmanagerClient alertmanager, IncidentStore store, MspProperties props,
                                         MspWorkers workers) {
        IncidentPoller poller = new IncidentPoller(alertmanager, store, Clock.systemUTC(),
                props.getIncident().getResolveAfter(), props.getIncident().getHeartbeatAlertnames());
        poller.start(workers.scheduler(), props.getIncident().getPollInterval());
        return poller;
    }

    @Bean
    @ConditionalOnExpression("'${exchange.msp.alertmanager-url:}'.trim() != ''")
    public AlertActions alertActions(AlertmanagerClient alertmanager, IncidentPoller poller, IncidentStore store,
                                     ApprovalService approvals, AdminAuditService audit,
                                     PlatformTransactionManager txManager, MspProperties props) {
        return new AlertActions(alertmanager, poller, store, approvals, audit, new TransactionTemplate(txManager),
                Clock.systemUTC(), props.getSilence().getHumanMax());
    }

    @Bean
    public MspBoard mspBoard(MspProperties props, ObjectProvider<PrometheusClient> prometheus,
                             ObjectProvider<IncidentPoller> poller, IncidentStore incidents,
                             OpsThresholdService thresholds, CapacityPlanService capacity, AiControl aiControl,
                             AiStore aiStore, ObjectProvider<LlmClient> llm, SourceCache cache, MspTrends trends,
                             AiServerStatus aiServer, MspReporting reporting) {
        return new MspBoard(props, prometheus.getIfAvailable(), poller.getIfAvailable(), incidents, thresholds,
                capacity, Clock.systemUTC(), aiControl, aiStore, llm.getIfAvailable(), cache, trends, aiServer,
                reporting);
    }

    @Bean
    public MspTrends mspTrends(MspProperties props, ObjectProvider<PrometheusClient> prometheus,
                               IncidentStore incidents, SourceCache cache) {
        return new MspTrends(props, prometheus.getIfAvailable(), incidents, cache);
    }

    @Bean
    public MspFlow mspFlow(MspProperties props, ObjectProvider<PrometheusClient> prometheus,
                           CapacityPlanService capacity, BotRateControl botRate, SourceCache cache) {
        return new MspFlow(props, prometheus.getIfAvailable(), capacity, botRate, cache);
    }

    /* ===== 보고 서비스(설계 7-12) ===== */

    /** 보고 서비스 주소가 있을 때만 선다. 주소가 루프백·사설이 아니거나 토큰 파일을 읽지 못하면 기동을 멈춘다. */
    @Bean
    @ConditionalOnExpression("'${exchange.msp.reporting.url:}'.trim() != ''")
    public ReportingClient mspReportingClient(MspReady ready, MspProperties props, ObjectMapper mapper) {
        return new ReportingClient(props.getReporting(), mapper);
    }

    @Bean
    public MspReporting mspReporting(ObjectProvider<ReportingClient> client, SourceCache cache, JdbcClient jdbc,
                                     MspProperties props) {
        return new MspReporting(client.getIfAvailable(), cache, jdbc, props.getReporting());
    }

    /** AI 통지 포트의 구현. 보관함에 쓰고 전용 스레드가 보고 서비스로 넘긴다. */
    @Bean
    public TeamsNotifier teamsNotifier(MspReady ready, ObjectProvider<ReportingClient> client, JdbcClient jdbc,
                                       PlatformTransactionManager txManager, AiStore store, IncidentStore incidents,
                                       MspProperties props, ObjectMapper mapper, MeterRegistry registry,
                                       MspWorkers workers) {
        TeamsNotifier notifier = new TeamsNotifier(client.getIfAvailable(), jdbc, new TransactionTemplate(txManager),
                store, incidents, props, mapper, registry, Clock.systemUTC());
        workers.scheduler().scheduleWithFixedDelay(notifier::drain, NOTIFY_SECONDS, NOTIFY_SECONDS, TimeUnit.SECONDS);
        return notifier;
    }

    /* ===== AI 판단과 조치(설계 7절) ===== */

    @Bean
    public AiStore aiStore(MspReady ready, JdbcClient jdbc, ObjectMapper mapper) {
        return new AiStore(jdbc, mapper);
    }

    @Bean
    public AiControl aiControl(MspReady ready, JdbcClient jdbc, PlatformTransactionManager txManager,
                               ApprovalService approvals, AdminAuditService audit, IncidentStore incidents,
                               ObjectMapper mapper, ObjectProvider<AiNotifier> notifier) {
        AiControl control = new AiControl(jdbc, new TransactionTemplate(txManager), approvals, audit, incidents, mapper,
                Clock.systemUTC());
        control.notifier(notifier.getIfAvailable(() -> AiNotifier.NONE));
        return control;
    }

    @Bean
    public MaintenanceWindows maintenanceWindows(MspReady ready, JdbcClient jdbc, PlatformTransactionManager txManager,
                                                 AdminAuditService audit, IncidentStore incidents, ObjectMapper mapper) {
        return new MaintenanceWindows(jdbc, new TransactionTemplate(txManager), audit, incidents, mapper,
                Clock.systemUTC());
    }

    /** LLM 설정이 있을 때만 선다. 주소·전송·원격 허용 규칙은 생성할 때 검사하고 어기면 기동을 멈춘다. */
    @Bean
    @ConditionalOnExpression("'${exchange.msp.llm.base-url:}'.trim() != ''")
    public LlmClient mspLlmClient(MspReady ready, MspProperties props, ObjectMapper mapper, MeterRegistry registry) {
        return new LlmClient(props.getLlm(), mapper, registry, Clock.systemUTC());
    }

    @Bean
    public AiServerStatus aiServerStatus(ObjectProvider<LlmClient> llm, SourceCache cache) {
        return new AiServerStatus(llm.getIfAvailable(), cache);
    }

    @Bean
    public AiChat aiChat(ObjectProvider<LlmClient> llm, AiEvidenceCollector collector, AiControl control,
                         IncidentStore incidents, AiStore store, ObjectProvider<PrometheusClient> prometheus,
                         ObjectProvider<IncidentPoller> poller, AdminAuditService audit, ObjectMapper mapper,
                         MspWorkers workers) {
        return new AiChat(llm.getIfAvailable(), collector, control, incidents, store, prometheus.getIfAvailable(),
                poller.getIfAvailable(), audit, mapper, Clock.systemUTC(), workers.llmLoader());
    }

    /** 여러 관리 JVM 가운데 한 곳에서만 판단·관찰 주기를 돌린다. 멈출 때 임대를 놓는다. */
    @Bean(destroyMethod = "release")
    public RunnerLease mspRunnerLease(MspReady ready, JdbcClient jdbc) {
        return new RunnerLease(jdbc);
    }

    @Bean
    public AiEvidenceCollector aiEvidenceCollector(MspProperties props, ObjectProvider<PrometheusClient> prometheus,
                                                   ObjectProvider<IncidentPoller> poller, OpsThresholdService thresholds,
                                                   CapacityPlanService capacity, BotRateControl botRate,
                                                   MaintenanceWindows windows, AiControl control, AiStore store) {
        return new AiEvidenceCollector(props, prometheus.getIfAvailable(), poller.getIfAvailable(), thresholds,
                capacity, botRate, windows, control, store, Clock.systemUTC());
    }

    @Bean
    public AiExecutor aiExecutor(AiStore store, AiControl control, AiEvidenceCollector collector,
                                 CapacityPlanService capacity, OpsThresholdService thresholds,
                                 ObjectProvider<AlertmanagerClient> alertmanager, ObjectProvider<AlertActions> alertActions,
                                 ObjectProvider<IncidentPoller> poller, IncidentStore incidents, BotRateControl botRate,
                                 ApprovalService approvals, AdminAuditService audit,
                                 PlatformTransactionManager txManager, JdbcClient jdbc, MspProperties props,
                                 ObjectProvider<AiNotifier> notifier) {
        AiExecutor executor = new AiExecutor(store, control, collector, capacity, thresholds,
                alertmanager.getIfAvailable(), alertActions.getIfAvailable(), poller.getIfAvailable(), incidents,
                botRate, approvals, audit, new TransactionTemplate(txManager), jdbc, props, Clock.systemUTC());
        executor.notifier(notifier.getIfAvailable(() -> AiNotifier.NONE));
        return executor;
    }

    /** 판단 주기는 LLM 이 있을 때만, 관찰 주기는 늘 돈다. 둘 다 전용 msp-ai 실행기에서 돈다. */
    @Bean
    public AiLoop aiLoop(AiEvidenceCollector collector, ObjectProvider<LlmClient> llm, AiStore store,
                         AiControl control, AiExecutor executor, IncidentStore incidents, AdminAuditService audit,
                         PlatformTransactionManager txManager, ObjectMapper mapper, MspProperties props,
                         MeterRegistry registry, MspWorkers workers, ObjectProvider<AiNotifier> notifier,
                         RunnerLease lease) {
        AiLoop loop = new AiLoop(collector, llm.getIfAvailable(), store, control, executor, incidents, audit,
                new TransactionTemplate(txManager), mapper, props, registry, Clock.systemUTC(), lease);
        loop.notifier(notifier.getIfAvailable(() -> AiNotifier.NONE));
        loop.start(workers.ai());
        return loop;
    }
}

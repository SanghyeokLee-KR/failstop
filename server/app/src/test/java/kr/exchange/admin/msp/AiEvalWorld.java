package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.BotRateControl;
import kr.exchange.admin.CapacityPlanService;
import kr.exchange.api.common.CursorPages;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 평가 시나리오 하나의 원천 상태. Prometheus 묶음, 경보 폴링, 용량 계획, 봇 발행률, 계획 작업 창, AI 모드, 최근 조치를
 * 값으로 두고, 실제 근거 수집기(AiEvidenceCollector)와 AI 상담(AiChat)을 이 값을 돌려주는 가짜 의존성에 묶는다. 모델이
 * 받는 근거 JSON 은 운영과 같은 코드(수집기의 라벨 거르기, 요약 자르기, 신선도 판정, 카탈로그)로 만들어진다.
 */
final class AiEvalWorld {

    static final Instant NOW = Instant.parse("2026-09-25T03:00:00Z");
    static final String HOST_JOB = "linux";
    static final String APP_JOB = "exchange-api";
    static final String DB_JOB = "postgres";
    static final String PROBE_JOB = "blackbox-http";

    /** V70 의 임계값 초기값과 범위다. value 가 null 이면 미설정이다. */
    private record Seed(String key, String unit, String value, String min, String max, String step) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("read.scale_out_cpu", "ratio", "0.60", "0.20", "0.85", "0.05"),
            new Seed("read.scale_in_cpu", "ratio", "0.30", "0.05", "0.50", "0.05"),
            new Seed("host.cpu_alert", "ratio", null, "0.60", "0.95", "0.05"),
            new Seed("host.mem_alert", "ratio", null, "0.70", "0.95", "0.05"),
            new Seed("host.disk_free_alert", "ratio", "0.15", "0.10", "0.40", "0.05"),
            new Seed("db.replication_lag_alert", "seconds", null, "5", "300", "10"),
            new Seed("backup.delay_alert", "seconds", "93600", "43200", "259200", "21600"),
            new Seed("bot.capacity_guard", "ratio", null, "0.50", "0.90", "0.05"),
            new Seed("host.cpu_credit_alert", "credits", null, "10", "200", "20"));

    private record Host(String node, MspProperties.HostRole role, Double cpu, Double memory, Double rootFree) {
    }

    MspProperties.Environment environment = MspProperties.Environment.LOCAL_DOCKER;
    MspProperties.ReadCapacityMode readMode = MspProperties.ReadCapacityMode.CAPACITY_PLAN;
    int min = 2;
    int desired = 2;
    int max = 4;
    Double readCpu = 0.45;
    // 축소 임계 아래로 이어진 시간. 수집기를 그만큼 주기마다 불러 운영과 같은 방법으로 지속을 쌓는다.
    Duration belowScaleInFor;
    private final Map<String, String> thresholdOverrides = new LinkedHashMap<>();

    String prometheusFailure;
    private Double fiveXx;
    private Double p95;
    private long appAge = 8;
    private Double availability;
    private long probeAge = 8;
    private final List<Host> hosts = new ArrayList<>();
    private long hostAge = 10;
    private Double replicationLag;
    private long dbAge = 10;
    private Duration backupAgo;

    boolean alertmanager = true;
    Duration alertsPolledAgo = Duration.ofSeconds(5);
    String pollFailure;
    private final List<AlertmanagerClient.Alert> alerts = new ArrayList<>();
    private final List<MaintenanceWindows.Window> windows = new ArrayList<>();

    Double botTargetOps = 4.0;
    String botSource = "local";

    AiControl.Mode mode = autoMode();
    private final List<AiStore.Action> recent = new ArrayList<>();

    static AiLimits limits() {
        // V72 의 확정값(ADR 0035)이다.
        return AiLimits.fromIds(Map.of("confidence_pct", 80, "auto_cap", 5, "threshold_per_item", 2,
                "bot_cooldown_min", 10, "backup_disk_free_pct", 30));
    }

    static AiControl.Mode autoMode() {
        return new AiControl.Mode("AUTO", "op-super", "평가", 1L, NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofHours(3)), 1, NOW.minus(Duration.ofHours(1)), limits(), false, null, null, null);
    }

    static AiControl.Mode stoppedMode() {
        return new AiControl.Mode("MANUAL", null, null, null, null, null, 1, null, null, true, "op-view",
                NOW.minus(Duration.ofMinutes(4)), "판단 확인을 위해 정지");
    }

    /* ===== 환경 ===== */

    /** 로컬 Docker. 조회 계층이 있고 node_exporter 가 없어 호스트 지표는 미수집이다(설계 11절). DB 는 한 대다. */
    static AiEvalWorld local() {
        AiEvalWorld w = new AiEvalWorld();
        w.app(0.001, 0.18);
        w.availability = 1.0;
        return w;
    }

    /** 학교 AWS. 조회 계층과 외부 프로브가 없고 호스트·DB·백업 지표가 있다(설계 11절). */
    static AiEvalWorld school() {
        AiEvalWorld w = new AiEvalWorld();
        w.environment = MspProperties.Environment.SCHOOL_AWS;
        w.readMode = MspProperties.ReadCapacityMode.NONE;
        w.readCpu = null;
        w.app(0.0005, 0.21);
        w.host("app-1", MspProperties.HostRole.APP, 0.34, 0.52, 0.61);
        w.host("app-2", MspProperties.HostRole.APP, 0.31, 0.50, 0.63);
        w.host("db-1", MspProperties.HostRole.DB, 0.22, 0.58, 0.70);
        w.host("db-2", MspProperties.HostRole.DB, 0.12, 0.47, 0.72);
        w.host("bot-1", MspProperties.HostRole.BOT_OBS, 0.36, 0.55, 0.66);
        w.replicationLag = 0.3;
        w.backupAgo = Duration.ofHours(5);
        return w;
    }

    /** 개인 AWS. ASG 정책이 조회 대수를 소유한다. */
    static AiEvalWorld personal() {
        AiEvalWorld w = school();
        w.environment = MspProperties.Environment.PERSONAL_AWS;
        w.readMode = MspProperties.ReadCapacityMode.ASG_POLICY;
        w.availability = 1.0;
        return w;
    }

    /* ===== 원천 값 ===== */

    AiEvalWorld nodes(int desired, double cpu) {
        this.desired = desired;
        this.readCpu = cpu;
        return this;
    }

    AiEvalWorld threshold(String key, String value) {
        thresholdOverrides.put(key, value);
        return this;
    }

    AiEvalWorld app(Double fiveXx, Double p95) {
        this.fiveXx = fiveXx;
        this.p95 = p95;
        return this;
    }

    AiEvalWorld appAge(long seconds) {
        this.appAge = seconds;
        return this;
    }

    AiEvalWorld host(String node, MspProperties.HostRole role, Double cpu, Double memory, Double rootFree) {
        hosts.removeIf(h -> h.node().equals(node));
        hosts.add(new Host(node, role, cpu, memory, rootFree));
        return this;
    }

    AiEvalWorld hostAge(long seconds) {
        this.hostAge = seconds;
        return this;
    }

    AiEvalWorld replication(Double lagSeconds) {
        this.replicationLag = lagSeconds;
        return this;
    }

    AiEvalWorld backupAgo(Duration ago) {
        this.backupAgo = ago;
        return this;
    }

    AiEvalWorld bot(Double targetOps) {
        this.botTargetOps = targetOps;
        return this;
    }

    /** 경보 하나. labels 에 alertname·severity 를 넣고, 수집기가 거르는 라벨(instance 등)도 원천 그대로 둔다. */
    AiEvalWorld alert(String fingerprint, String alertname, String severity, Map<String, String> labels,
                      Map<String, String> annotations, Duration since) {
        Map<String, String> all = new LinkedHashMap<>(labels);
        all.put("alertname", alertname);
        all.put("severity", severity);
        alerts.add(new AlertmanagerClient.Alert(fingerprint, all, annotations, NOW.minus(since), "active", List.of()));
        return this;
    }

    AiEvalWorld window(String alertname, Map<String, String> labels, Duration endsIn, String reason) {
        Instant start = NOW.minus(Duration.ofMinutes(10));
        windows.add(new MaintenanceWindows.Window(windows.size() + 1, start, NOW.plus(endsIn), alertname, labels,
                reason, "op-ops", start, null, null));
        return this;
    }

    /** 실행된 최근 조치 하나. byAi 면 자동 실행(활성 번호 있음), 아니면 사람의 승인 실행이다. */
    AiEvalWorld executed(AiAction action, boolean byAi, Duration ago) {
        Instant at = NOW.minus(ago);
        long id = recent.size() + 1;
        recent.add(new AiStore.Action(id, id, id, 2, action, action.identityKey(), new BigDecimal("0.90"),
                "평가용 과거 조치", byAi ? "AUTO_EXECUTED" : "APPROVED", null, null, null, byAi ? "ai:msp" : "op-super",
                at, null, byAi ? "ai:msp" : "op-super", at, byAi ? 1L : null, null, null, at.plus(AiPolicy.OBSERVE),
                "KEPT", null, null, null, at.minusSeconds(60), at.plus(Duration.ofMinutes(14))));
        return this;
    }

    /* ===== 조립 ===== */

    /** 실제 수집기와 그 의존성. clock 은 NOW 에 있다. */
    record Rig(MspProperties props, AiEvidenceCollector collector, AiControl control, AiStore store,
               PrometheusClient prometheus, IncidentPoller poller, TestClock clock) {

        AiEvidence evidence() {
            return collector.collect();
        }

        /** 운영의 상담 경로(AiChat). 감사는 가짜이고 적재 스레드는 호출 스레드다. */
        AiChat chat(LlmClient llm, ObjectMapper mapper, List<IncidentStore.Incident> incidents) {
            IncidentStore store = mock(IncidentStore.class);
            List<CursorPages.Row<IncidentStore.Incident>> rows = new ArrayList<>();
            for (IncidentStore.Incident i : incidents) {
                rows.add(new CursorPages.Row<>(i, OffsetDateTime.ofInstant(i.startedAt(), ZoneOffset.UTC),
                        String.valueOf(i.id())));
            }
            when(store.list(eq("OPEN"), any(), anyInt())).thenReturn(rows);
            return new AiChat(llm, collector, control, store, this.store, prometheus, poller,
                    AlertMeanings.load(mapper), null, mock(AdminAuditService.class), mapper, clock, Runnable::run);
        }
    }

    Rig rig() {
        MspProperties props = new MspProperties();
        props.setEnvironment(environment);
        props.getReadCapacity().setMode(readMode);
        for (Host h : hosts) {
            MspProperties.Host host = new MspProperties.Host();
            host.setNode(h.node());
            host.setRole(h.role());
            props.getHosts().add(host);
        }
        PrometheusClient prometheus = mock(PrometheusClient.class);
        when(prometheus.snapshot()).thenReturn(snapshot());
        when(prometheus.ranges(eq(CauseQuery.class), any(), any(), any(), any()))
                .thenAnswer(inv -> loadRanges(inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
        IncidentPoller poller = null;
        if (alertmanager) {
            poller = mock(IncidentPoller.class);
            Instant polled = NOW.minus(alertsPolledAgo);
            when(poller.last()).thenReturn(new IncidentPoller.Poll(polled, List.copyOf(alerts), Set.of(), 0,
                    pollFailure, pollFailure == null ? null : NOW.minusSeconds(5)));
        }
        OpsThresholdService thresholds = mock(OpsThresholdService.class);
        when(thresholds.items()).thenReturn(items());
        CapacityPlanService capacity = mock(CapacityPlanService.class);
        when(capacity.plan()).thenReturn(new CapacityPlanService.Plan(min, desired, max, "op-super",
                OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC)));
        when(capacity.observe()).thenReturn(new CapacityPlanService.Observation(Map.of(), desired, readCpu, true));
        BotRateControl botRate = mock(BotRateControl.class);
        when(botRate.current()).thenReturn(new BotRateControl.Current(botTargetOps, botTargetOps == null ? null
                : botSource, botTargetOps == null ? null : NOW));
        MaintenanceWindows maintenance = mock(MaintenanceWindows.class);
        when(maintenance.active()).thenReturn(List.copyOf(windows));
        AiControl control = mock(AiControl.class);
        when(control.mode()).thenReturn(mode);
        when(control.effectiveLimits(any())).thenReturn(limits());
        when(control.autoUsed(anyLong())).thenReturn(0);
        AiStore store = mock(AiStore.class);
        when(store.recent(anyInt())).thenReturn(List.copyOf(recent));

        Duration below = belowScaleInFor == null ? Duration.ZERO : belowScaleInFor;
        TestClock clock = new TestClock(NOW.minus(below));
        AiEvidenceCollector collector = new AiEvidenceCollector(props, prometheus, poller, thresholds, capacity,
                botRate, maintenance, control, store, clock);
        // 판단 주기(기본 60초)마다 표본을 모은 것처럼 지속을 쌓는다. 마지막 표본은 NOW 다.
        while (clock.instant().isBefore(NOW)) {
            collector.collect();
            clock.advance(Duration.ofSeconds(60));
        }
        return new Rig(props, collector, control, store, prometheus, poller, clock);
    }

    /** 열린 사건. 보류되지 않은 경보마다 하나다(상담 근거). */
    List<IncidentStore.Incident> openIncidents() {
        List<IncidentStore.Incident> out = new ArrayList<>();
        long id = 100;
        for (AlertmanagerClient.Alert a : alerts) {
            Map<String, String> labels = new LinkedHashMap<>(a.labels());
            out.add(new IncidentStore.Incident(id++, a.fingerprint(), labels.get("alertname"),
                    IncidentPoller.severity(labels), labels, IncidentStore.summary(a), a.startsAt(), a.startsAt(),
                    NOW.minus(alertsPolledAgo), null, "OPEN", null, null, null, null, null, null));
        }
        return out;
    }

    List<AlertmanagerClient.Alert> alerts() {
        return List.copyOf(alerts);
    }

    private List<OpsThresholdService.Item> items() {
        List<OpsThresholdService.Item> out = new ArrayList<>();
        OffsetDateTime at = OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC);
        for (Seed s : SEEDS) {
            String value = thresholdOverrides.containsKey(s.key()) ? thresholdOverrides.get(s.key()) : s.value();
            out.add(new OpsThresholdService.Item(s.key(), s.unit(), value == null ? null : new BigDecimal(value),
                    s.value() == null ? null : new BigDecimal(s.value()), new BigDecimal(s.min()),
                    new BigDecimal(s.max()), new BigDecimal(s.step()), 1, "migration:V70", at));
        }
        return out;
    }

    /** 수집 시각은 time() - timestamp(up) 계열(SCRAPE_AGE)에서 온다. 잡마다 그 나이를 한 벌 싣는다. */
    private PrometheusClient.Snapshot snapshot() {
        if (prometheusFailure != null) {
            return new PrometheusClient.Snapshot(NOW, Map.of(), Map.of(), prometheusFailure);
        }
        Map<MspQuery, List<PrometheusClient.Series>> results = new LinkedHashMap<>();
        List<PrometheusClient.Series> ages = new ArrayList<>();
        if (fiveXx != null || p95 != null) {
            add(results, MspQuery.HTTP_5XX_RATIO, Map.of("job", APP_JOB), fiveXx);
            add(results, MspQuery.HTTP_P95_SECONDS, Map.of("job", APP_JOB), p95);
            ages.add(new PrometheusClient.Series(Map.of("job", APP_JOB), appAge, NOW));
        }
        if (availability != null) {
            add(results, MspQuery.PROBE_SUCCESS_RATIO, Map.of("job", PROBE_JOB), availability);
            ages.add(new PrometheusClient.Series(Map.of("job", PROBE_JOB), probeAge, NOW));
        }
        for (Host h : hosts) {
            Map<String, String> labels = Map.of("job", HOST_JOB, "node", h.node());
            add(results, MspQuery.HOST_CPU_RATIO, labels, h.cpu());
            add(results, MspQuery.HOST_MEMORY_RATIO, labels, h.memory());
            add(results, MspQuery.HOST_ROOT_FREE_RATIO, labels, h.rootFree());
            ages.add(new PrometheusClient.Series(labels, hostAge, NOW));
        }
        if (backupAgo != null) {
            add(results, MspQuery.BACKUP_LAST_SUCCESS, Map.of(), (double) NOW.minus(backupAgo).getEpochSecond());
        }
        if (replicationLag != null) {
            add(results, MspQuery.REPLICATION_LAG_SECONDS, Map.of("job", DB_JOB, "node", "db-2"), replicationLag);
            ages.add(new PrometheusClient.Series(Map.of("job", DB_JOB, "node", "db-1"), dbAge, NOW));
        }
        results.put(MspQuery.SCRAPE_AGE, ages);
        return new PrometheusClient.Snapshot(NOW, results, Map.of(), null);
    }

    /**
     * 부하 판정의 range 묶음(설계 7-19). 조회 CPU 가 창 전체에 그대로였다고 본다. 그래서 증설 임계 이상인 시나리오는 지속
     * 부하이고, 평가 시나리오의 증설 기대(D1)가 지속 전제(D5)에서도 그대로 성립한다.
     */
    private PrometheusClient.Ranges<CauseQuery> loadRanges(Instant start, Instant end, Duration step) {
        if (prometheusFailure != null) {
            return new PrometheusClient.Ranges<>(start, end, step, Map.of(),
                    Map.of(CauseQuery.APP_CPU, prometheusFailure, CauseQuery.HTTP_RATE, prometheusFailure));
        }
        Map<Long, Double> cpu = new LinkedHashMap<>();
        Map<Long, Double> requests = new LinkedHashMap<>();
        for (long t = start.getEpochSecond(); t <= end.getEpochSecond(); t += step.toSeconds()) {
            if (readCpu != null) {
                cpu.put(t, readCpu);
            }
            requests.put(t, 40.0);
        }
        Map<CauseQuery, List<PrometheusClient.RangeSeries>> results = new LinkedHashMap<>();
        results.put(CauseQuery.APP_CPU, cpu.isEmpty() ? List.of() : List.of(new PrometheusClient.RangeSeries(Map.of(), cpu)));
        results.put(CauseQuery.HTTP_RATE, List.of(new PrometheusClient.RangeSeries(Map.of(), requests)));
        return new PrometheusClient.Ranges<>(start, end, step, results, Map.of());
    }

    private static void add(Map<MspQuery, List<PrometheusClient.Series>> results, MspQuery query,
                            Map<String, String> labels, Double value) {
        if (value != null) {
            results.computeIfAbsent(query, q -> new ArrayList<>()).add(new PrometheusClient.Series(labels, value, NOW));
        }
    }
}

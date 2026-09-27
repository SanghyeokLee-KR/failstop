package kr.exchange.admin.msp;

import kr.exchange.admin.CapacityPlanService;
import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 관제센터 한 판과 세부 조회의 칸을 조립한다(API 설계 §6-F). 원천마다 연결 전·실패·지연을 구분해 내고,
 * 미수집을 0 이나 정상으로 바꾸지 않는다. 값 하나의 수집 시각은 Prometheus 평가 시각이 아니라 그 대상의
 * 마지막 수집 시각(time() - timestamp(up))에서 온다.
 */
public class MspBoard {

    // API 응답과 AI 근거에 내는 라벨. instance(사설 주소와 포트)는 내지 않는다.
    private static final List<String> ALERT_LABELS = List.of("node", "role", "component", "mountpoint");
    private static final int OPEN_IDS = 50;

    private final MspProperties props;
    private final PrometheusClient prometheus;
    private final IncidentPoller poller;
    private final IncidentStore incidents;
    private final OpsThresholdService thresholds;
    private final CapacityPlanService capacity;
    private final Clock clock;
    private final AiControl ai;
    private final AiStore aiStore;
    private final LlmClient llm;
    private final SourceCache cache;
    private final MspTrends trends;
    private final AiServerStatus aiServer;
    private final MspReporting reporting;
    /** DB 에 기대는 칸의 실패를 그 칸에 가둔다 */
    private final DbGuard db;
    /** DB 읽기가 실패한 뒤 DB 칸을 곧바로 unavailable 로 내는 시간 */
    static final Duration DB_COOLDOWN = Duration.ofSeconds(5);

    /**
     * prometheus 와 poller 는 원천 주소가 없으면, llm 은 LLM 설정이 없으면 null 이다. 그 칸은 연결 전으로 낸다.
     * ai 가 null 이면 AI 칸을 수동·연결 전으로 고정한다. 세부 조회만 쓰는 시험은 trends, aiServer, reporting 을
     * 넘기지 않는다.
     */
    public MspBoard(MspProperties props, PrometheusClient prometheus, IncidentPoller poller, IncidentStore incidents,
                    OpsThresholdService thresholds, CapacityPlanService capacity, Clock clock, AiControl ai,
                    AiStore aiStore, LlmClient llm, SourceCache cache, MspTrends trends, AiServerStatus aiServer,
                    MspReporting reporting) {
        this.props = props;
        this.prometheus = prometheus;
        this.poller = poller;
        this.incidents = incidents;
        this.thresholds = thresholds;
        this.capacity = capacity;
        this.clock = clock;
        this.ai = ai;
        this.aiStore = aiStore;
        this.llm = llm;
        this.cache = cache;
        this.trends = trends;
        this.aiServer = aiServer;
        this.reporting = reporting;
        this.db = new DbGuard(clock, DB_COOLDOWN);
    }

    /**
     * 한 판. DB 에 기대는 칸(조회 계층의 용량 계획, 경보의 사건 대응, 오늘 사건, 운영 작업, 임계값, AI, Teams 원천)은 DbGuard 로
     * 감싼다. 주 DB 전환 동안 그 읽기가 실패해도 한 판은 200 이고 그 칸만 database_unavailable 이다(API 설계 §6-F)
     */
    public Map<String, Object> overview() {
        Instant now = clock.instant();
        PrometheusClient.Snapshot metrics = metrics();
        Map<String, Object> out = header(now);
        out.put("sources", sources(metrics));
        out.put("summary", summary(metrics, now));
        out.put("trends", trends.section(MspTrends.Range.H24));
        out.put("hosts", hosts(metrics, now));
        out.put("read_tier", db.section(() -> readTier(now)));
        out.put("replication", replication(metrics, now));
        out.put("alerts", db.section(this::alerts));
        out.put("incidents", db.section(() -> MspSections.ready(incidentsToday(now))));
        out.put("ops", db.section(() -> ops(metrics)));
        out.put("thresholds", db.section(() -> thresholds(metrics)));
        out.put("ai", db.section(this::ai));
        out.put("ai_server", aiServer.view());
        return out;
    }

    public Map<String, Object> hostsView() {
        Instant now = clock.instant();
        PrometheusClient.Snapshot metrics = metrics();
        Map<String, Object> out = header(now);
        out.put("hosts", hosts(metrics, now));
        out.put("read_tier", db.section(() -> readTier(now)));
        out.put("replication", replication(metrics, now));
        return out;
    }

    public Map<String, Object> alertsView() {
        Map<String, Object> out = header(clock.instant());
        out.put("alerts", db.section(this::alerts));
        return out;
    }

    public Map<String, Object> opsView() {
        Map<String, Object> out = header(clock.instant());
        out.put("ops", db.section(() -> ops(metrics())));
        return out;
    }

    public Map<String, Object> trendsView(MspTrends.Range range) {
        Map<String, Object> out = header(clock.instant());
        out.putAll(trends.section(range));
        return out;
    }

    public Map<String, Object> thresholdsView() {
        Map<String, Object> out = header(clock.instant());
        out.put("thresholds", db.section(() -> thresholds(metrics())));
        return out;
    }

    private Map<String, Object> header(Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("environment", props.getEnvironment().name());
        out.put("day_zone", props.getDayZone().getId());
        out.put("generated_at", ApiFormats.time(now));
        return out;
    }

    private PrometheusClient.Snapshot metrics() {
        return prometheus == null ? null : prometheus.snapshot();
    }

    /* ===== 원천 ===== */

    private List<Map<String, Object>> sources(PrometheusClient.Snapshot metrics) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> prom = source("prometheus");
        if (metrics == null) {
            prom.put("state", "NOT_CONNECTED");
        } else if (metrics.failure() != null) {
            prom.put("state", "UNAVAILABLE");
            prom.put("reason", metrics.failure());
        } else {
            prom.put("state", "READY");
        }
        out.add(prom);
        Map<String, Object> am = source("alertmanager");
        if (poller == null) {
            am.put("state", "NOT_CONNECTED");
        } else {
            IncidentPoller.Poll poll = poller.last();
            am.put("state", poll.healthy() ? "READY" : "UNAVAILABLE");
            if (!poll.healthy()) {
                am.put("reason", poll.failure());
            }
            am.put("last_poll_at", MspSections.time(poll.lastSuccessAt()));
            am.put("write_failures", poll.writeFailures());
        }
        out.add(am);
        out.add(AiViews.llmSource(llm));
        // Databasus 는 이 판에 연결 경로가 없다. 설정이 있어도 성공을 지어내지 않는다.
        Map<String, Object> databasus = source("databasus");
        databasus.put("state", "NOT_CONNECTED");
        out.add(databasus);
        // Teams 원천은 알림 보관함 건수를 DB 에서 읽는다. DB 가 멈춘 동안에는 그 원천만 unavailable 이다
        out.add(db.call(reporting::teamsSource, () -> {
            Map<String, Object> teams = source("teams");
            teams.put("via", "reporting");
            teams.put("state", "UNAVAILABLE");
            teams.put("reason", DbGuard.REASON);
            return teams;
        }));
        return out;
    }

    private static Map<String, Object> source(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        return m;
    }

    /* ===== 요약 ===== */

    private Map<String, Object> summary(PrometheusClient.Snapshot metrics, Instant now) {
        Map<String, Object> unready = unready(metrics);
        if (unready != null) {
            return unready;
        }
        MspProperties.Prometheus jobs = props.getPrometheus();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("availability", aggregate(metrics, MspQuery.PROBE_SUCCESS_RATIO, jobs.getProbeJob(), now,
                MspSections.Unit.RATIO));
        data.put("p95_seconds", aggregate(metrics, MspQuery.HTTP_P95_SECONDS, jobs.getAppJob(), now,
                MspSections.Unit.SECONDS));
        data.put("error_ratio", aggregate(metrics, MspQuery.HTTP_5XX_RATIO, jobs.getAppJob(), now,
                MspSections.Unit.RATIO));
        Map<String, Object> slo = new LinkedHashMap<>();
        slo.put("availability", plain(props.getSlo().getAvailabilityRatio()));
        slo.put("p95_seconds", plain(props.getSlo().getP95Seconds()));
        slo.put("error_ratio", plain(props.getSlo().getErrorRatio()));
        data.put("slo", slo);
        return MspSections.ready(data);
    }

    /* ===== 호스트 ===== */

    private Map<String, Object> hosts(PrometheusClient.Snapshot metrics, Instant now) {
        Map<String, Object> unready = unready(metrics);
        if (unready != null) {
            return unready;
        }
        Set<String> hostJobs = jobs(props.getPrometheus().getHostJobs());
        List<PrometheusClient.Series> recovery = series(metrics, MspQuery.DB_IN_RECOVERY);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MspProperties.Host host : props.getHosts()) {
            Predicate<Map<String, String>> target = labels -> hostJobs.contains(labels.get("job"))
                    && host.getNode().equals(labels.get("node"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node", host.getNode());
            row.put("role", host.getRole().name());
            row.put("actual_role", host.getRole() == MspProperties.HostRole.DB ? actualRole(recovery, host.getNode()) : null);
            row.put("instance_type", host.getInstanceType());
            row.put("az", host.getAz());
            row.put("up", measure(metrics, MspQuery.TARGET_UP, target, target, now, MspSections.Unit.INTEGER));
            row.put("cpu_ratio", byNode(metrics, MspQuery.HOST_CPU_RATIO, host.getNode(), target, now));
            row.put("memory_ratio", byNode(metrics, MspQuery.HOST_MEMORY_RATIO, host.getNode(), target, now));
            row.put("root_free_ratio", byNode(metrics, MspQuery.HOST_ROOT_FREE_RATIO, host.getNode(), target, now));
            // CPU 크레딧을 읽는 원천이 없다(설계 2-1 (3)).
            row.put("cpu_credits", MspSections.missing());
            rows.add(row);
        }
        return MspSections.ready(rows);
    }

    private static String actualRole(List<PrometheusClient.Series> recovery, String node) {
        for (PrometheusClient.Series s : recovery) {
            if (node.equals(s.labels().get("node"))) {
                return s.value() == 0 ? "PRIMARY" : s.value() == 1 ? "STANDBY" : null;
            }
        }
        return null;
    }

    /* ===== 조회 계층 ===== */

    private Map<String, Object> readTier(Instant now) {
        MspProperties.ReadCapacityMode mode = props.getReadCapacity().getMode();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode.name());
        switch (mode) {
            case NONE -> {
                return MspSections.notConnected("read_capacity");
            }
            case ASG_POLICY -> {
                // AWS 는 ASG 가 대상당 요청 수로 대수를 정한다. 대수는 AWS API 를 읽지 않으므로 싣지 않는다.
                data.put("scaling_owner", "ASG");
                data.put("scaling_basis", "request_count_per_target");
            }
            case CAPACITY_PLAN -> {
                CapacityPlanService.Plan plan = capacity.plan();
                // 노드 관측은 거래 노드를 부른다. 화면 여럿이 같은 캐시 수명 동안 한 번 잰 값을 함께 쓴다(설계 4-6).
                SourceCache.Cached<CapacityPlanService.Observation> observed =
                        cache.get("capacity:observe", props.getCacheTtl(), capacity::observe);
                data.put("min", plan.min());
                data.put("desired", plan.desired());
                data.put("max", plan.max());
                data.put("observed", observed.value().up());
                data.put("avg_cpu_ratio", MspSections.measurement(observed.value().readAvgCpu(), observed.loadedAt(),
                        now, props.getStaleAfter(), MspSections.Unit.RATIO));
            }
        }
        return MspSections.ready(data);
    }

    /* ===== 복제 ===== */

    private Map<String, Object> replication(PrometheusClient.Snapshot metrics, Instant now) {
        Map<String, Object> unready = unready(metrics);
        if (unready != null) {
            return unready;
        }
        if (!metrics.ran(MspQuery.DB_IN_RECOVERY)) {
            return MspSections.notConnected("prometheus");
        }
        String failed = metrics.failures().get(MspQuery.DB_IN_RECOVERY);
        if (failed != null) {
            return MspSections.unavailable(failed);
        }
        String primary = null;
        String standby = null;
        boolean mismatch = false;
        for (PrometheusClient.Series s : series(metrics, MspQuery.DB_IN_RECOVERY)) {
            String node = s.labels().get("node");
            String role = s.labels().get("role");
            if (s.value() == 0 && primary == null) {
                primary = node;
            } else if (s.value() == 1 && standby == null) {
                standby = node;
            }
            mismatch |= ("data".equals(role) && s.value() == 1) || ("standby".equals(role) && s.value() == 0);
        }
        String dbJob = props.getPrometheus().getDbJob();
        List<PrometheusClient.Series> lag = series(metrics, MspQuery.REPLICATION_LAG_SECONDS);
        String stream = lag.stream().map(s -> s.labels().get("state")).filter(v -> v != null && !v.isEmpty())
                .findFirst().map(v -> v.toUpperCase(Locale.ROOT)).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("primary", primary);
        data.put("standby", standby);
        data.put("stream", stream);
        data.put("lag_seconds", aggregate(metrics, MspQuery.REPLICATION_LAG_SECONDS, dbJob, now, MspSections.Unit.SECONDS));
        data.put("lag_bytes", aggregate(metrics, MspQuery.REPLICATION_LAG_BYTES, dbJob, now, MspSections.Unit.INTEGER));
        data.put("label_mismatch", mismatch);
        // 복제 방식과 승격 방식은 수집값으로만 적는다. 모르면 null 이고 화면이 미수집·미확인으로 적는다(지어내지 않는다)
        data.put("mode", replicationMode(single(metrics, MspQuery.REPLICATION_MODE)));
        data.put("promotion", promotion(single(metrics, MspQuery.DB_PROMOTION)));
        return MspSections.ready(data);
    }

    /** 복제 방식 값(2 동기, 1 비동기)을 계약 이름으로. 복제 상대가 없거나 질의가 실패했으면 null 이다 */
    static String replicationMode(Double code) {
        return code == null ? null : code == 2 ? "SYNC" : code == 1 ? "ASYNC" : null;
    }

    /**
     * 승격 방식 값(1 Patroni 자동, 2 Patroni 일시 정지)을 계약 이름으로. Patroni 지표가 없으면 null(미확인)이다. 지표가 없다는
     * 것만으로 수동 승격이라 단정하지 않는다. 수집이 끊긴 Patroni 와 Patroni 가 없는 배치(학교 AWS, ADR 0024)를 가를 수 없고,
     * Patroni 가 곧 승격할 때 화면이 '수동'이라 적으면 사람이 따로 승격해 두 Primary 를 만드는 사고로 이어질 수 있다
     */
    static String promotion(Double code) {
        return code == null ? null : code == 1 ? "AUTO" : code == 2 ? "MANUAL" : null;
    }

    /* ===== 경보와 사건 ===== */

    private Map<String, Object> alerts() {
        if (poller == null) {
            return MspSections.notConnected("alertmanager");
        }
        IncidentPoller.Poll poll = poller.last();
        if (!poll.healthy()) {
            Map<String, Object> m = MspSections.unavailable(poll.failure());
            m.put("last_poll_at", MspSections.time(poll.lastSuccessAt()));
            return m;
        }
        Instant now = clock.instant();
        List<String> fingerprints = poll.alerts().stream().map(AlertmanagerClient.Alert::fingerprint)
                .filter(fp -> IncidentStore.FINGERPRINT.matcher(fp).matches()).toList();
        Map<String, IncidentStore.Incident> open = incidents.openByFingerprint(fingerprints);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AlertmanagerClient.Alert alert : poll.alerts()) {
            IncidentStore.Incident incident = open.get(alert.fingerprint());
            if (incident != null && !incident.startedAt().equals(alert.startsAt())) {
                incident = null;
            }
            Map<String, Object> row = alertLabels(alert.fingerprint(), alert.labels(), IncidentPoller.severity(alert.labels()));
            row.put("summary", IncidentStore.summary(alert));
            row.put("starts_at", MspSections.time(alert.startsAt()));
            row.put("state", !alert.silencedBy().isEmpty() ? "SILENCED"
                    : incident != null && incident.ackedBy() != null ? "ACKED" : "FIRING");
            row.put("incident_id", incident == null ? null : incident.id());
            row.put("threshold_key", alert.annotations().get("threshold_key"));
            row.put("acked_by", incident == null ? null : incident.ackedBy());
            row.put("acked_at", incident == null ? null : MspSections.time(incident.ackedAt()));
            row.put("silence", incident == null ? null : silence(incident, now));
            rows.add(row);
        }
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("state", "READY");
        section.put("last_poll_at", MspSections.time(poll.lastSuccessAt()));
        section.put("data", rows);
        return section;
    }

    /** 사건 목록과 상세의 한 행. 라벨은 허용한 것만 낸다. */
    public Map<String, Object> incidentRow(IncidentStore.Incident incident) {
        Map<String, Object> row = alertLabels(incident.fingerprint(), incident.labels(), incident.severity());
        row.put("id", incident.id());
        row.put("summary", incident.summary());
        row.put("started_at", MspSections.time(incident.startedAt()));
        row.put("resolved_at", MspSections.time(incident.resolvedAt()));
        row.put("status", incident.status());
        row.put("acked_by", incident.ackedBy());
        row.put("acked_at", MspSections.time(incident.ackedAt()));
        row.put("silence", silence(incident, clock.instant()));
        return row;
    }

    private static Map<String, Object> alertLabels(String fingerprint, Map<String, String> labels, String severity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fingerprint", fingerprint);
        row.put("alertname", labels.get("alertname"));
        row.put("severity", severity);
        for (String name : ALERT_LABELS) {
            row.put(name, labels.get(name));
        }
        return row;
    }

    /** 끝 시각이 지난 보류는 싣지 않는다. 사건 표의 보류 칸은 다음 보류나 해제 때 바뀐다. */
    private static Map<String, Object> silence(IncidentStore.Incident incident, Instant now) {
        if (incident.silenceId() == null || !incident.silencedUntil().isAfter(now)) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("silence_id", incident.silenceId());
        m.put("ends_at", MspSections.time(incident.silencedUntil()));
        m.put("actor", incident.silenceActor());
        m.put("by", incident.silencedBy());
        return m;
    }

    private Map<String, Object> incidentsToday(Instant now) {
        Instant dayStart = LocalDate.ofInstant(now, props.getDayZone()).atStartOfDay(props.getDayZone()).toInstant();
        Map<String, Object> data = new LinkedHashMap<>(incidents.today(dayStart));
        data.put("open_ids", incidents.openIds(OPEN_IDS));
        return data;
    }

    /* ===== 운영 작업 ===== */

    private Map<String, Object> ops(PrometheusClient.Snapshot metrics) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("db_backup", opsItem(metrics, List.of(MspQuery.BACKUP_LAST_SUCCESS, MspQuery.BACKUP_VERIFY_OK), () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            Double last = single(metrics, MspQuery.BACKUP_LAST_SUCCESS);
            Double verify = single(metrics, MspQuery.BACKUP_VERIFY_OK);
            m.put("last_success_at", last == null ? null : ApiFormats.timeMs(Math.round(last * 1000)));
            m.put("verify_ok", verify == null ? null : verify == 1);
            return m;
        }));
        data.put("databasus", MspSections.notConnected("databasus"));
        data.put("certificate", MspSections.notConnected("certificate"));
        data.put("daily_report", opsItem(metrics, List.of(MspQuery.DAILY_REPORT_LAST_TRIGGER), () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            Double last = single(metrics, MspQuery.DAILY_REPORT_LAST_TRIGGER);
            // 타이머가 실행했는지만 안다. Teams 전달 성공은 이 값으로 판정하지 않는다.
            m.put("last_triggered_at", last == null ? null : ApiFormats.timeMs(Math.round(last * 1000)));
            return m;
        }));
        return MspSections.ready(data);
    }

    private Map<String, Object> opsItem(PrometheusClient.Snapshot metrics, List<MspQuery> queries,
                                        java.util.function.Supplier<Map<String, Object>> data) {
        Map<String, Object> unready = unready(metrics);
        if (unready != null) {
            return unready;
        }
        for (MspQuery q : queries) {
            if (!metrics.ran(q)) {
                return MspSections.notConnected("prometheus");
            }
            if (metrics.failures().containsKey(q)) {
                return MspSections.unavailable(metrics.failures().get(q));
            }
        }
        return MspSections.ready(data.get());
    }

    /* ===== 임계값 ===== */

    private Map<String, Object> thresholds(PrometheusClient.Snapshot metrics) {
        Map<String, Double> ruleValues = new LinkedHashMap<>();
        boolean ruleValuesKnown = metrics != null && metrics.results().containsKey(MspQuery.THRESHOLD_RULE_VALUE);
        if (ruleValuesKnown) {
            for (PrometheusClient.Series s : metrics.results().get(MspQuery.THRESHOLD_RULE_VALUE)) {
                ruleValues.put(s.labels().get("key"), s.value());
            }
        }
        Boolean scraped = null;
        if (metrics != null && metrics.results().containsKey(MspQuery.THRESHOLD_GAUGE_COUNT)) {
            List<PrometheusClient.Series> count = metrics.results().get(MspQuery.THRESHOLD_GAUGE_COUNT);
            scraped = !count.isEmpty() && count.get(0).value() > 0;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (OpsThresholdService.Item item : thresholds.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", item.key());
            m.put("unit", item.unit());
            m.put("value", plain(item.value()));
            m.put("default", plain(item.defaultValue()));
            m.put("min", plain(item.min()));
            m.put("max", plain(item.max()));
            m.put("ai_max_step", plain(item.aiMaxStep()));
            m.put("version", item.version());
            m.put("updated_by", item.updatedBy());
            m.put("updated_at", item.updatedAt() == null ? null : ApiFormats.time(item.updatedAt()));
            m.put("in_use", inUse(item.key()));
            Double rule = ruleValues.get(item.key());
            m.put("rule_value", rule == null || !Double.isFinite(rule) ? null
                    : ApiFormats.amount(BigDecimal.valueOf(rule).setScale(OpsThresholdService.MAX_SCALE,
                    java.math.RoundingMode.HALF_UP)));
            items.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("gauge_scraped", scraped);
        return MspSections.ready(data);
    }

    boolean inUse(String key) {
        return inUse(props, key);
    }

    /** 이 환경에서 그 항목을 쓰는 규칙이나 권고가 있는지(API 설계 §6-F 표). AI 도 이 값이 거짓인 항목을 바꾸지 못한다. */
    static boolean inUse(MspProperties props, String key) {
        MspProperties.Environment env = props.getEnvironment();
        return switch (key) {
            case OpsThresholdService.SCALE_OUT, OpsThresholdService.SCALE_IN ->
                    props.getReadCapacity().getMode() == MspProperties.ReadCapacityMode.CAPACITY_PLAN;
            case "host.disk_free_alert", "backup.delay_alert" -> env == MspProperties.Environment.SCHOOL_AWS;
            case "db.replication_lag_alert" -> env == MspProperties.Environment.SCHOOL_AWS
                    || env == MspProperties.Environment.LOCAL_DOCKER;
            default -> false;
        };
    }

    /* ===== AI ===== */

    private Map<String, Object> ai() {
        if (ai == null) {
            Map<String, Object> m = MspSections.notConnected("llm");
            m.put("mode", "MANUAL");
            m.put("auto", null);
            return m;
        }
        return AiViews.section(ai, aiStore, llm);
    }

    /* ===== 측정 ===== */

    /** Prometheus 설정이 없거나 묶음 전체가 실패했으면 그 상태의 칸, 아니면 null. */
    private static Map<String, Object> unready(PrometheusClient.Snapshot metrics) {
        if (metrics == null) {
            return MspSections.notConnected("prometheus");
        }
        if (metrics.failure() != null) {
            return MspSections.unavailable(metrics.failure());
        }
        return null;
    }

    private static List<PrometheusClient.Series> series(PrometheusClient.Snapshot metrics, MspQuery query) {
        return MspSections.series(metrics, query);
    }

    private static Double single(PrometheusClient.Snapshot metrics, MspQuery query) {
        List<PrometheusClient.Series> s = series(metrics, query);
        return s.isEmpty() || !Double.isFinite(s.get(0).value()) ? null : s.get(0).value();
    }

    /** 여러 대상을 합친 값(여러 시계열이면 최댓값). 수집 시각은 그 job 의 대상 가운데 가장 오래된 것이다. */
    private Map<String, Object> aggregate(PrometheusClient.Snapshot metrics, MspQuery query, String job, Instant now,
                                          MspSections.Unit unit) {
        return measure(metrics, query, labels -> true, labels -> job.equals(labels.get("job")), now, unit);
    }


    private Map<String, Object> byNode(PrometheusClient.Snapshot metrics, MspQuery query, String node,
                                       Predicate<Map<String, String>> targets, Instant now) {
        return measure(metrics, query, labels -> node.equals(labels.get("node")), targets, now,
                MspSections.Unit.RATIO);
    }

    /**
     * 값은 고른 시계열의 최댓값이다. 수집 시각은 targets 에 드는 수집 대상 가운데 가장 오래된 마지막 수집
     * 시각이다. 그런 대상이 없으면 수집 시각을 모르므로 MISSING 이다.
     */
    private Map<String, Object> measure(PrometheusClient.Snapshot metrics, MspQuery query,
                                        Predicate<Map<String, String>> pick, Predicate<Map<String, String>> targets,
                                        Instant now, MspSections.Unit unit) {
        MspSections.Sample s = MspSections.sample(metrics, query, pick, targets, false);
        return MspSections.measurement(s.value(), s.sampledAt(), now, props.getStaleAfter(), unit);
    }

    private static Set<String> jobs(String selector) {
        return selector.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(selector.split("\\|")));
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : ApiFormats.amount(value);
    }
}

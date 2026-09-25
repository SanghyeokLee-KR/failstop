package kr.exchange.admin.msp;

import kr.exchange.admin.BotRateControl;
import kr.exchange.admin.CapacityPlanService;
import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * AI 판단 근거를 모은다(설계 7-2). Prometheus 질의 묶음은 화면과 같은 캐시를 쓰고, 경보는 마지막 폴링 결과를 쓴다.
 * 조회 계층 CPU 는 용량 화면의 조회처럼 노드를 호출 시점에 잰다. 원천이 없거나 실패한 값은 미수집으로 남긴다.
 */
public class AiEvidenceCollector {

    // 근거와 응답에 내는 경보 라벨. instance(사설 주소와 포트)는 내지 않는다.
    private static final List<String> ALERT_LABELS = List.of("node", "role", "component", "mountpoint");
    private static final int MAX_ALERTS = 20;
    private static final int MAX_SUMMARY = 200;
    private static final int RECENT_ACTIONS = 5;

    private final MspProperties props;
    private final PrometheusClient prometheus;
    private final IncidentPoller poller;
    private final OpsThresholdService thresholds;
    private final CapacityPlanService capacity;
    private final BotRateControl botRate;
    private final MaintenanceWindows windows;
    private final AiControl control;
    private final AiStore store;
    private final Clock clock;
    // 조회 평균 CPU 가 축소 임계 아래로 처음 내려간 시각과 마지막 표본 시각. 방향 전제 D2 가 지속을 본다. 재기동하면
    // 처음부터 다시 잰다. 두 값은 trackLow 안에서만 함께 읽고 쓴다.
    private Instant lowSince;
    private Instant lastSampleAt;

    public AiEvidenceCollector(MspProperties props, PrometheusClient prometheus, IncidentPoller poller,
                               OpsThresholdService thresholds, CapacityPlanService capacity, BotRateControl botRate,
                               MaintenanceWindows windows, AiControl control, AiStore store, Clock clock) {
        this.props = props;
        this.prometheus = prometheus;
        this.poller = poller;
        this.thresholds = thresholds;
        this.capacity = capacity;
        this.botRate = botRate;
        this.windows = windows;
        this.control = control;
        this.store = store;
        this.clock = clock;
    }

    public AiEvidence collect() {
        return collect(true);
    }

    /**
     * 판단 주기의 기록을 바꾸지 않는 판독(AI 상담, 설계 7-17). 축소 방향 전제(D2)가 보는 '축소 임계 아래로 내려간 시각'을
     * 상담이 끊거나 시작시키지 않게 한다.
     */
    public AiEvidence peek() {
        return collect(false);
    }

    /** 폴러의 마지막 결과(메모리)에 판단 대상 경보가 있는가. 평온 주기에 근거를 모으지 않고 경보만 본다(설계 7-1). */
    public boolean judgedAlertsPending() {
        if (poller == null) {
            return false;
        }
        for (AlertmanagerClient.Alert a : poller.last().alerts()) {
            if (a.silencedBy().isEmpty() && AiEvidence.JUDGED_SEVERITIES.contains(IncidentPoller.severity(a.labels()))) {
                return true;
            }
        }
        return false;
    }

    private AiEvidence collect(boolean track) {
        Instant now = clock.instant();
        AiControl.Mode mode = control.mode();
        Map<String, AiEvidence.Threshold> items = new LinkedHashMap<>();
        for (OpsThresholdService.Item item : thresholds.items()) {
            items.put(item.key(), new AiEvidence.Threshold(item.key(), item.unit(), item.value(), item.min(),
                    item.max(), item.aiMaxStep(), MspBoard.inUse(props, item.key())));
        }
        AiEvidence.ReadTier read = readTier(now, items, track);
        List<AiEvidence.Alert> alerts = new ArrayList<>();
        Instant polledAt = null;
        if (poller != null) {
            IncidentPoller.Poll poll = poller.last();
            polledAt = poll.lastSuccessAt();
            for (AlertmanagerClient.Alert a : poll.alerts()) {
                if (alerts.size() >= MAX_ALERTS) {
                    break;
                }
                alerts.add(alert(a));
            }
        }
        Map<String, AiEvidence.Metric> metrics = metrics(now);
        BotRateControl.Current rate = botRate.current();
        AiEvidence.Bot bot = new AiEvidence.Bot(rate.targetOps(), rate.source(), rate.sampledAt());
        List<MaintenanceWindows.Window> active = windows.active();
        AiLimits limits = control.effectiveLimits(mode);
        AiEvidence base = new AiEvidence(now, props.getEnvironment(), props.getReadCapacity().getMode(), mode.mode(),
                read, items, List.copyOf(alerts), poller != null, polledAt, metrics, bot, active, false, Map.of());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("limits", limitsContext(mode, limits));
        context.put("recent_actions", recent());
        context.put("catalog", catalog(base, now, props.getAi().getScaleInSustain()));
        return new AiEvidence(now, base.environment(), base.readMode(), base.mode(), read, items, base.alerts(),
                poller != null, polledAt, metrics, bot, active, false, context);
    }

    private AiEvidence.ReadTier readTier(Instant now, Map<String, AiEvidence.Threshold> items, boolean track) {
        if (props.getReadCapacity().getMode() != MspProperties.ReadCapacityMode.CAPACITY_PLAN) {
            return null;
        }
        CapacityPlanService.Plan plan = capacity.plan();
        CapacityPlanService.Observation observation = capacity.observe();
        Double cpu = observation.readAvgCpu();
        AiEvidence.Threshold scaleIn = items.get(OpsThresholdService.SCALE_IN);
        boolean low = cpu != null && scaleIn != null && scaleIn.value() != null
                && BigDecimal.valueOf(cpu).compareTo(scaleIn.value()) < 0;
        return new AiEvidence.ReadTier(plan.min(), plan.desired(), plan.max(), observation.up(), cpu, now,
                trackLow(now, low, track));
    }

    /**
     * 축소 임계 아래로 내려간 시각. 표본 사이가 판단 주기의 두 배보다 벌어지면 그 사이에 임계를 넘었는지 모르므로
     * 지속을 처음부터 다시 잰다(정책 D2). 판단 주기와 관찰 주기가 함께 부르므로 늦게 끝난 옛 표본은 기록하지 않는다.
     */
    private synchronized Instant trackLow(Instant now, boolean low, boolean track) {
        boolean continuous = lastSampleAt != null
                && !now.isAfter(lastSampleAt.plus(props.getAi().getInterval().multipliedBy(2)));
        Instant since = !low ? null : (lowSince != null && continuous ? lowSince : now);
        if (track && (lastSampleAt == null || !now.isBefore(lastSampleAt))) {
            lowSince = since;
            lastSampleAt = now;
        }
        return since;
    }

    private static AiEvidence.Alert alert(AlertmanagerClient.Alert a) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String name : ALERT_LABELS) {
            String v = a.labels().get(name);
            if (v != null) {
                labels.put(name, v);
            }
        }
        return new AiEvidence.Alert(a.fingerprint(), a.labels().getOrDefault("alertname", ""),
                IncidentPoller.severity(a.labels()), labels, a.startsAt(), !a.silencedBy().isEmpty(),
                AiStore.clip(IncidentStore.summary(a), MAX_SUMMARY), a.annotations().get("threshold_key"));
    }

    private Map<String, AiEvidence.Metric> metrics(Instant now) {
        Map<String, AiEvidence.Metric> out = new LinkedHashMap<>();
        PrometheusClient.Snapshot s = prometheus == null ? null : prometheus.snapshot();
        if (s == null || s.failure() != null) {
            return out;
        }
        MspProperties.Prometheus jobs = props.getPrometheus();
        Set<String> hostJobs = jobs.getHostJobs().isEmpty() ? Set.of()
                : Set.copyOf(Arrays.asList(jobs.getHostJobs().split("\\|")));
        Predicate<Map<String, String>> hosts = l -> hostJobs.contains(l.get("job"));
        Predicate<Map<String, String>> app = l -> jobs.getAppJob().equals(l.get("job"));
        Predicate<Map<String, String>> db = l -> jobs.getDbJob().equals(l.get("job"));
        Predicate<Map<String, String>> probe = l -> jobs.getProbeJob().equals(l.get("job"));
        Predicate<Map<String, String>> all = l -> true;
        put(out, AiEvidence.HTTP_5XX_RATIO, MspSections.sample(s, MspQuery.HTTP_5XX_RATIO, all, app, false));
        put(out, AiEvidence.HTTP_P95_SECONDS, MspSections.sample(s, MspQuery.HTTP_P95_SECONDS, all, app, false));
        put(out, AiEvidence.AVAILABILITY, MspSections.sample(s, MspQuery.PROBE_SUCCESS_RATIO, all, probe, true));
        put(out, AiEvidence.HOST_CPU_MAX, MspSections.sample(s, MspQuery.HOST_CPU_RATIO, all, hosts, false));
        put(out, AiEvidence.HOST_MEMORY_MAX, MspSections.sample(s, MspQuery.HOST_MEMORY_RATIO, all, hosts, false));
        put(out, AiEvidence.HOST_ROOT_FREE_MIN, MspSections.sample(s, MspQuery.HOST_ROOT_FREE_RATIO, all, hosts, true));
        put(out, AiEvidence.REPLICATION_LAG_SECONDS,
                MspSections.sample(s, MspQuery.REPLICATION_LAG_SECONDS, all, db, false));
        MspSections.Sample backup = MspSections.sample(s, MspQuery.BACKUP_LAST_SUCCESS, all, hosts, false);
        out.put(AiEvidence.BACKUP_AGE_SECONDS, backup.value() == null ? new AiEvidence.Metric(null, null)
                : new AiEvidence.Metric((double) Duration.between(
                        Instant.ofEpochMilli(Math.round(backup.value() * 1000)), now).toSeconds(), backup.sampledAt()));
        String botNode = props.getHosts().stream().filter(h -> h.getRole() == MspProperties.HostRole.BOT_OBS)
                .map(MspProperties.Host::getNode).findFirst().orElse(null);
        if (botNode != null) {
            Predicate<Map<String, String>> node = l -> botNode.equals(l.get("node"));
            put(out, AiEvidence.BOT_HOST_CPU, MspSections.sample(s, MspQuery.HOST_CPU_RATIO, node,
                    hosts.and(node), false));
        }
        return out;
    }

    private static void put(Map<String, AiEvidence.Metric> out, String name, MspSections.Sample sample) {
        out.put(name, new AiEvidence.Metric(sample.value(), sample.value() == null ? null : sample.sampledAt()));
    }

    private Map<String, Object> limitsContext(AiControl.Mode mode, AiLimits limits) {
        Map<String, Object> m = new LinkedHashMap<>(limits.byId());
        if (mode.auto()) {
            m.put("auto_used", control.autoUsed(mode.activation()));
            m.put("valid_until", ApiFormats.time(mode.validUntil()));
        }
        return m;
    }

    private List<Map<String, Object>> recent() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiStore.Action a : store.recent(RECENT_ACTIONS)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", a.action().verb().name());
            m.put("target", a.action().target());
            m.put("status", a.status());
            m.put("actor", a.activation() != null ? "AI" : "OPERATOR");
            m.put("executed_at", ApiFormats.time(a.executedAt()));
            out.add(m);
        }
        return out;
    }

    /**
     * 이 환경에서 쓸 수 있고 지금 근거로 방향 전제가 서는 조치만 모델에게 보인다(설계 7-5). 정책이 막을 조치를 안내하지
     * 않아야 모델이 그것을 고르지 않는다(설계 7-13). 목록 밖의 조치를 내도 검증기와 정책 엔진이 다시
     * 막으므로 이 목록은 안내이지 경계가 아니다.
     */
    static List<Map<String, Object>> catalog(AiEvidence ev, Instant now, Duration scaleInSustain) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(entry(AiAction.Verb.NO_ACTION));
        AiEvidence.ReadTier read = ev.read();
        if (read != null) {
            if (read.desired() < read.max() && AiPolicy.d1ScaleOut(ev) == null) {
                Map<String, Object> m = entry(AiAction.Verb.READ_NODE_ADD_ONE);
                m.put("desired", read.desired());
                m.put("max", read.max());
                out.add(m);
            }
            if (read.desired() > read.min() && AiPolicy.d2ScaleIn(ev, scaleInSustain, now) == null) {
                Map<String, Object> m = entry(AiAction.Verb.READ_NODE_REMOVE_ONE);
                m.put("desired", read.desired());
                m.put("min", read.min());
                out.add(m);
            }
        }
        for (AiAction.Verb v : List.of(AiAction.Verb.THRESHOLD_RAISE, AiAction.Verb.THRESHOLD_LOWER)) {
            List<Map<String, Object>> keys = thresholdTargets(ev, v == AiAction.Verb.THRESHOLD_RAISE);
            if (!keys.isEmpty()) {
                Map<String, Object> m = entry(v);
                m.put("targets", keys);
                out.add(m);
            }
        }
        List<Map<String, Object>> silences = new ArrayList<>();
        // 경보 목록이 60초보다 오래됐으면 보류 대상을 보이지 않는다. 자동 실행도 같은 기준(A5)으로 막힌다.
        boolean alertsFresh = ev.alertsPolledAt() != null
                && !ev.alertsPolledAt().plus(AiEvidence.MAX_AGE).isBefore(now);
        for (AiEvidence.Alert a : ev.alerts()) {
            if (!alertsFresh || a.silenced() || !"warning".equals(a.severity()) || AiPolicy.observabilityPath(a)
                    || AiPolicy.forbiddenFamily(a)) {
                continue;
            }
            for (MaintenanceWindows.Window w : ev.windows()) {
                if (w.activeAt(now) && w.matches(a.alertname(), a.labels())) {
                    long left = Duration.between(now, w.endsAt()).toMinutes();
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("fingerprint", a.fingerprint());
                    s.put("alertname", a.alertname());
                    s.put("max_minutes", Math.min(AiPolicy.SILENCE_MAX_MINUTES, left));
                    silences.add(s);
                    break;
                }
            }
        }
        if (!silences.isEmpty()) {
            Map<String, Object> m = entry(AiAction.Verb.ALERT_SILENCE);
            m.put("targets", silences);
            out.add(m);
        }
        if (ev.bot().targetOps() != null && botOverloaded(ev, now)) {
            Map<String, Object> m = entry(AiAction.Verb.BOT_LOAD_REDUCE);
            m.put("current_target_ops", ApiFormats.amount(BigDecimal.valueOf(ev.bot().targetOps())));
            m.put("min_factor", ApiFormats.amount(AiPolicy.BOT_MIN_FACTOR));
            out.add(m);
        }
        if (ev.databasusConnected()) {
            out.add(entry(AiAction.Verb.BACKUP_RUN_NOW));
        }
        return out;
    }

    /**
     * 임계값 조치의 대상. 이 환경에서 쓰고 값이 있는 항목 가운데 한 폭 옮길 수 있고, 그 방향이 방향 전제(D3)와 조회 임계
     * 간격(T3)을 지나는 항목만 싣는다. 울리는 경보의 임계를 둔하게 바꾸는 쪽은 이렇게 빠진다.
     */
    private static List<Map<String, Object>> thresholdTargets(AiEvidence ev, boolean raise) {
        List<Map<String, Object>> keys = new ArrayList<>();
        for (AiEvidence.Threshold t : ev.thresholds().values()) {
            if (!t.inUse() || t.value() == null) {
                continue;
            }
            BigDecimal next = raise ? t.value().add(t.step()).min(t.max()) : t.value().subtract(t.step()).max(t.min());
            if (next.compareTo(t.value()) == 0) {
                continue;
            }
            AiAction.Threshold probe = new AiAction.Threshold(t.key(), next, t.value());
            if (AiPolicy.d3Threshold(probe, ev) != null || AiPolicy.t3Gap(probe, ev.thresholds()) != null) {
                continue;
            }
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("key", t.key());
            k.put("value", ApiFormats.amount(t.value()));
            k.put("min", ApiFormats.amount(t.min()));
            k.put("max", ApiFormats.amount(t.max()));
            k.put("max_step", ApiFormats.amount(t.step()));
            keys.add(k);
        }
        return keys;
    }

    /** 봇 부하 줄이기의 방향 전제(D4)가 서고 bot-1 CPU 표본이 60초 안인가. 자동 실행도 같은 기준(A5)을 쓴다. */
    private static boolean botOverloaded(AiEvidence ev, Instant now) {
        AiEvidence.Metric cpu = ev.metric(AiEvidence.BOT_HOST_CPU);
        return AiPolicy.d4BotLoad(ev) == null && cpu.sampledAt() != null
                && !cpu.sampledAt().plus(AiEvidence.MAX_AGE).isBefore(now);
    }

    private static Map<String, Object> entry(AiAction.Verb verb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", verb.name());
        return m;
    }
}

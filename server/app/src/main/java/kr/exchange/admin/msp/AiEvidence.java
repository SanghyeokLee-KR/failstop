package kr.exchange.admin.msp;

import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * AI 판단 근거 한 벌(설계 7-2). 모델에 보내는 JSON 과 정책 엔진의 입력이 같은 값에서 나온다. 미수집은 null 로 두고
 * 0 이나 정상으로 바꾸지 않는다. 비밀 값, 개인정보, 사설 주소와 포트, 외부나 사람이 쓴 자유 문장은 담지 않는다.
 * load 는 코드가 계산한 부하 판정이고(설계 7-19) Prometheus 가 없으면 null 이다.
 */
public record AiEvidence(
        Instant collectedAt,
        MspProperties.Environment environment,
        MspProperties.ReadCapacityMode readMode,
        String mode,
        ReadTier read,
        Map<String, Threshold> thresholds,
        List<Alert> alerts,
        boolean alertsConnected,
        Instant alertsPolledAt,
        Map<String, Metric> metrics,
        Bot bot,
        List<MaintenanceWindows.Window> windows,
        boolean databasusConnected,
        Map<String, Object> context,
        LoadPattern load) {

    // 조치에 필요한 근거가 이보다 오래되면 자동 실행하지 않는다(ADR 0035, 정책 A5).
    public static final Duration MAX_AGE = Duration.ofSeconds(60);

    public static final String HTTP_5XX_RATIO = "http_5xx_ratio";
    public static final String HTTP_P95_SECONDS = "http_p95_seconds";
    public static final String AVAILABILITY = "availability";
    public static final String HOST_CPU_MAX = "host_cpu_max";
    public static final String HOST_MEMORY_MAX = "host_memory_max";
    public static final String HOST_ROOT_FREE_MIN = "host_root_free_min";
    public static final String REPLICATION_LAG_SECONDS = "replication_lag_seconds";
    public static final String BACKUP_AGE_SECONDS = "backup_age_seconds";
    public static final String BOT_HOST_CPU = "bot_host_cpu";

    // 판단 대상 경보의 등급. info 는 판단 거리가 아니다(설계 7-1).
    static final Set<String> JUDGED_SEVERITIES = Set.of("critical", "warning", "other");
    // 조회 평균 CPU 가 축소 임계 아래이고 희망 대수가 최소보다 크다. 축소 후보다.
    static final String SCALE_IN_CROSSING = "read.scale_in";

    // 형식을 지나도 조치 이름(끝의 ONE 을 뗀 꼴 포함)·임계값 키·지시 낱말이 든 값은 버린다. 한 낱말짜리 값이 조치나
    // 임계를 가리키지 못하게 한다.
    private static final List<String> ACTION_WORDS = Arrays.stream(AiAction.Verb.values())
            .flatMap(v -> Stream.of(v.name(), v.name().replaceFirst("_ONE$", ""))).map(AiEvidence::fold).distinct()
            .toList();
    private static final List<String> INSTRUCTION_WORDS = List.of("ignore", "override", "instruction", "confidence",
            "approv");
    // 영문 낱말 하나. 대문자로 시작하는 낱말과 대문자만 이어진 낱말을 따로 센다(DiskSpaceLow 셋, READ_NODE 둘).
    private static final Pattern WORD = Pattern.compile("\\p{Upper}?\\p{Lower}+|\\p{Upper}+(?!\\p{Lower})");

    /**
     * 모델에 보내는 경보명과 라벨 값의 형식(설계 7-2 신뢰 경계). 공백·한글과 허용하지 않은 기호가 든 값, 64자를 넘는 값,
     * 영문 낱말이 상한보다 많은 값은 버린다. 낱말 상한은 저장소 규칙의 경보명(5낱말 이하)과 라벨 값(3낱말 이하)에 맞췄다.
     */
    enum Field {
        ALERTNAME("^[A-Za-z_:][A-Za-z0-9_:]{0,63}$", 5),
        LABEL("^[A-Za-z0-9/][A-Za-z0-9._/-]{0,63}$", 3);

        private final Pattern format;
        private final int maxWords;

        Field(String format, int maxWords) {
            this.format = Pattern.compile(format);
            this.maxWords = maxWords;
        }
    }

    /** 조회 계층. CAPACITY_PLAN 이 아니면 null 이다. avgCpu 는 호출 시점에 잰 값이다. */
    public record ReadTier(int min, int desired, int max, int observed, Double avgCpu, Instant sampledAt,
                           Instant lowSince) {
    }

    public record Threshold(String key, String unit, BigDecimal value, BigDecimal min, BigDecimal max,
                            BigDecimal step, boolean inUse) {
    }

    /**
     * 활성 경보 하나(설계 7-2 신뢰 경계). alertname 과 labels 는 형식과 낱말 검사를 지난 값만 담고(instance 없음), 버린
     * 필드의 이름은 rejected 에 있다. 주석(요약 등)은 담지 않는다. fingerprint 는 원천 값이고 형식 밖이면 rejected 에 든다.
     * thresholdKey 는 규칙 주석의 threshold_key 가 레지스트리 키와 같을 때만 있다.
     */
    public record Alert(String fingerprint, String alertname, String severity, Map<String, String> labels,
                        Instant startsAt, boolean silenced, String thresholdKey, List<String> rejected) {

        public String component() {
            return labels.get("component");
        }

        /** 거부 사유·통지 문구와 상담 근거의 항목 이름에 쓰는 이름. 경보명을 버렸으면 서버가 정한 문구다. */
        public String displayName() {
            return alertname != null ? alertname : "(형식 밖 이름)";
        }
    }

    public record Metric(Double value, Instant sampledAt) {
    }

    public record Bot(Double targetOps, String source, Instant sampledAt) {
    }

    public Metric metric(String name) {
        return metrics.getOrDefault(name, new Metric(null, null));
    }

    public Alert alert(String fingerprint) {
        for (Alert a : alerts) {
            if (a.fingerprint().equals(fingerprint)) {
                return a;
            }
        }
        return null;
    }

    /** 보류되지 않은 critical·warning·other 경보. 생존 신호 경보는 이미 빠져 있다. */
    public List<Alert> judgedAlerts() {
        return alerts.stream().filter(a -> !a.silenced() && JUDGED_SEVERITIES.contains(a.severity())).toList();
    }

    /**
     * 임계를 넘은 지표의 이름. 판단 거리가 있는지와 같은 상황인지 가르는 데 쓴다. 미설정 임계와 미수집 지표는
     * 넘은 것으로 보지 않는다.
     */
    public List<String> crossings() {
        List<String> out = new ArrayList<>();
        if (read != null && read.avgCpu() != null) {
            BigDecimal cpu = BigDecimal.valueOf(read.avgCpu());
            Threshold scaleOut = thresholds.get(OpsThresholdService.SCALE_OUT);
            Threshold scaleIn = thresholds.get(OpsThresholdService.SCALE_IN);
            if (scaleOut != null && scaleOut.value() != null && cpu.compareTo(scaleOut.value()) >= 0) {
                out.add("read.scale_out");
            } else if (scaleIn != null && scaleIn.value() != null && cpu.compareTo(scaleIn.value()) < 0
                    && read.desired() > read.min()) {
                out.add(SCALE_IN_CROSSING);
            }
        }
        for (Threshold t : thresholds.values()) {
            String metric = metricOf(t.key());
            if (metric == null || t.value() == null) {
                continue;
            }
            Metric m = metric(metric);
            if (m.value() != null && beyond(t.key(), BigDecimal.valueOf(m.value()), t.value())) {
                out.add(t.key());
            }
        }
        return out;
    }

    /** 임계값 항목을 판정하는 근거 지표. 조회 임계는 조회 계층 평균 CPU 이고 원천이 없는 항목은 null 이다. */
    static String metricOf(String key) {
        return switch (key) {
            case "host.cpu_alert" -> HOST_CPU_MAX;
            case "host.mem_alert" -> HOST_MEMORY_MAX;
            case "host.disk_free_alert" -> HOST_ROOT_FREE_MIN;
            case "db.replication_lag_alert" -> REPLICATION_LAG_SECONDS;
            case "backup.delay_alert" -> BACKUP_AGE_SECONDS;
            case "bot.capacity_guard" -> BOT_HOST_CPU;
            default -> null;
        };
    }

    /** 지표 아래로 내려가면 경보인 항목(여유·크레딧). 나머지는 올라가면 경보다. */
    static boolean lowerIsWorse(String key) {
        return "host.disk_free_alert".equals(key) || "host.cpu_credit_alert".equals(key);
    }

    static boolean beyond(String key, BigDecimal value, BigDecimal threshold) {
        return lowerIsWorse(key) ? value.compareTo(threshold) <= 0 : value.compareTo(threshold) >= 0;
    }

    /**
     * 경보명이나 라벨 값 하나를 모델에 보내도 되면 그 값, 아니면 null 이다(설계 7-2 신뢰 경계). 형식이나 낱말 수를 넘거나
     * 조치 이름, 임계값 키, 지시 낱말이 들어 있으면 버리고 고쳐 쓰지 않는다. 낱말 검사는 대소문자와 구분자를 바꿔 피하지
     * 못하게 영숫자만 본다.
     */
    static String screen(String value, Field field, Collection<String> thresholdKeys) {
        if (value == null || !field.format.matcher(value).matches()
                || WORD.matcher(value).results().count() > field.maxWords) {
            return null;
        }
        String folded = fold(value);
        if (ACTION_WORDS.stream().anyMatch(folded::contains) || INSTRUCTION_WORDS.stream().anyMatch(folded::contains)
                || thresholdKeys.stream().map(AiEvidence::fold).anyMatch(folded::contains)) {
            return null;
        }
        return value;
    }

    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * 그 조치에 필요한 근거의 가장 오래된 수집 시각(정책 A5). 필요한 근거가 하나라도 없으면 null 이다.
     */
    public Instant oldestRequired(AiAction action) {
        List<Instant> required = new ArrayList<>();
        switch (action) {
            case AiAction.ReadNodes r -> required.add(read == null || read.avgCpu() == null ? null : read.sampledAt());
            case AiAction.Threshold t -> {
                if (t.key().startsWith("read.")) {
                    required.add(read == null || read.avgCpu() == null ? null : read.sampledAt());
                } else {
                    String metric = metricOf(t.key());
                    Metric m = metric == null ? null : metric(metric);
                    required.add(m == null || m.value() == null ? null : m.sampledAt());
                }
            }
            case AiAction.Silence s -> required.add(alertsPolledAt);
            case AiAction.BotLoad b -> {
                required.add(bot.targetOps() == null ? null : bot.sampledAt());
                Metric m = metric(BOT_HOST_CPU);
                required.add(m.value() == null ? null : m.sampledAt());
            }
            case AiAction.BackupNow k -> {
                Metric m = metric(HOST_ROOT_FREE_MIN);
                required.add(m.value() == null ? null : m.sampledAt());
            }
        }
        Instant oldest = null;
        for (Instant at : required) {
            if (at == null) {
                return null;
            }
            oldest = oldest == null || at.isBefore(oldest) ? at : oldest;
        }
        return oldest;
    }

    /** 근거 전체에서 가장 오래된 표본 시각. 판단 기록에 남긴다. */
    public Instant oldestSample() {
        Instant oldest = null;
        List<Instant> all = new ArrayList<>();
        if (read != null && read.avgCpu() != null) {
            all.add(read.sampledAt());
        }
        metrics.values().stream().filter(m -> m.value() != null).forEach(m -> all.add(m.sampledAt()));
        if (alertsPolledAt != null) {
            all.add(alertsPolledAt);
        }
        for (Instant at : all) {
            if (at != null && (oldest == null || at.isBefore(oldest))) {
                oldest = at;
            }
        }
        return oldest;
    }

    /** 모든 근거가 미수집이거나 지연이면 모델을 부르지 않는다(설계 7-4 EVIDENCE_STALE). */
    public boolean allStale() {
        List<Instant> all = new ArrayList<>();
        if (read != null && read.avgCpu() != null) {
            all.add(read.sampledAt());
        }
        metrics.values().stream().filter(m -> m.value() != null).forEach(m -> all.add(m.sampledAt()));
        if (alertsPolledAt != null) {
            all.add(alertsPolledAt);
        }
        return all.stream().noneMatch(at -> at != null && !at.isBefore(collectedAt.minus(MAX_AGE)));
    }

    /**
     * 모델에 보내는 JSON 의 원본. 키 순서를 고정해 같은 근거가 같은 바이트가 되게 한다. 경보는 구조화 필드만 싣는다
     * (설계 7-2 신뢰 경계).
     */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "msp-evidence/3");
        out.put("environment", environment.name());
        out.put("collected_at", ApiFormats.time(collectedAt));
        out.put("mode", mode);
        if (read != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("min", read.min());
            r.put("desired", read.desired());
            r.put("max", read.max());
            r.put("observed", read.observed());
            r.put("avg_cpu", measure(read.avgCpu(), read.sampledAt(), 4));
            r.put("below_scale_in_since", read.lowSince() == null ? null : ApiFormats.time(read.lowSince()));
            // 코드가 계산한 부하 판정. 증설의 지속 전제(D5)와 카탈로그가 같은 값을 쓴다.
            r.put("load", load == null ? null : load.toJson());
            out.put("read_tier", r);
        } else {
            out.put("read_tier", null);
        }
        List<Map<String, Object>> th = new ArrayList<>();
        for (Threshold t : thresholds.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", t.key());
            m.put("unit", t.unit());
            m.put("value", t.value() == null ? null : ApiFormats.amount(t.value()));
            m.put("in_use", t.inUse());
            th.add(m);
        }
        out.put("thresholds", th);
        List<Map<String, Object>> al = new ArrayList<>();
        for (Alert a : alerts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fingerprint", a.rejected().contains("fingerprint") ? null : a.fingerprint());
            m.put("alertname", a.alertname());
            m.put("severity", a.severity());
            a.labels().forEach(m::put);
            if (!a.rejected().isEmpty()) {
                m.put("rejected", a.rejected());
            }
            m.put("starts_at", ApiFormats.time(a.startsAt()));
            m.put("silenced", a.silenced());
            m.put("threshold", alertThreshold(a.thresholdKey()));
            m.put("planned_window", windows.stream().anyMatch(w -> w.matches(a.alertname(), a.labels())));
            al.add(m);
        }
        out.put("alerts", al);
        out.put("alerts_polled_at", alertsPolledAt == null ? null : ApiFormats.time(alertsPolledAt));
        // 경보 목록의 신선도. 지표의 collect 와 같은 기준이라 모델이 오래된 경보 목록으로 보류를 고르지 않게 한다.
        out.put("alerts_collect", alertsPolledAt == null ? "MISSING"
                : Duration.between(alertsPolledAt, collectedAt).compareTo(MAX_AGE) > 0 ? "STALE" : "OK");
        Map<String, Object> met = new LinkedHashMap<>();
        metrics.forEach((name, m) -> met.put(name, measure(m.value(), m.sampledAt(),
                name.endsWith("_seconds") ? 3 : 4)));
        out.put("metrics", met);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("target_ops", bot.targetOps() == null ? null
                : ApiFormats.amount(BigDecimal.valueOf(bot.targetOps()).setScale(3, RoundingMode.HALF_UP)));
        b.put("source", bot.source());
        out.put("bot_rate", b);
        out.putAll(context);
        return out;
    }

    /** 경보에 이은 임계값. 레지스트리 값과 서버가 그 임계로 판정하는 근거 지표를 싣는다. 잇지 못하면 null 이다. */
    private Map<String, Object> alertThreshold(String key) {
        Threshold t = key == null ? null : thresholds.get(key);
        if (t == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", t.value() == null ? null : ApiFormats.amount(t.value()));
        if (key.startsWith("read.")) {
            m.put("metric", "read_tier.avg_cpu");
            m.put("observed", read == null ? null : measure(read.avgCpu(), read.sampledAt(), 4));
            return m;
        }
        String name = metricOf(key);
        m.put("metric", name);
        m.put("observed", name == null ? null
                : measure(metric(name).value(), metric(name).sampledAt(), name.endsWith("_seconds") ? 3 : 4));
        return m;
    }

    private Map<String, Object> measure(Double value, Instant sampledAt, int scale) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean known = value != null && Double.isFinite(value) && sampledAt != null;
        m.put("value", known ? ApiFormats.amount(BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP)) : null);
        m.put("age_seconds", known ? Math.max(0, Duration.between(sampledAt, collectedAt).toSeconds()) : null);
        m.put("collect", !known ? "MISSING"
                : Duration.between(sampledAt, collectedAt).compareTo(MAX_AGE) > 0 ? "STALE" : "OK");
        return m;
    }
}

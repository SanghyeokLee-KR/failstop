package kr.exchange.admin.msp;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 정책 엔진과 검증기 단위 시험의 입력. 기본값은 모든 규칙을 통과하는 상태다. */
final class AiFixtures {

    static final Instant NOW = Instant.parse("2026-09-25T03:00:00Z");

    private AiFixtures() {
    }

    static AiLimits limits() {
        return AiLimits.fromIds(Map.of("confidence_pct", 80, "auto_cap", 5, "threshold_per_item", 2,
                "bot_cooldown_min", 10, "backup_disk_free_pct", 30));
    }

    static AiControl.Mode manual() {
        return new AiControl.Mode("MANUAL", null, null, null, null, null, 0, null, null, false, null, null, null);
    }

    static AiControl.Mode auto() {
        return new AiControl.Mode("AUTO", "op-01", "리허설", 1L, NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofHours(1)), 1, NOW.minus(Duration.ofHours(1)), limits(), false, null, null, null);
    }

    static AiControl.Mode stopped() {
        return new AiControl.Mode("MANUAL", null, null, null, null, null, 1, null, null, true, "op-02", NOW, "점검");
    }

    static AiEvidence.Alert alert(String fingerprint, String name, String severity, String component) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("node", "app-1");
        if (component != null) {
            labels.put("component", component);
        }
        return new AiEvidence.Alert(fingerprint, name, severity, labels, NOW.minus(Duration.ofMinutes(5)), false,
                null, List.of());
    }

    static MaintenanceWindows.Window window(String alertname, Map<String, String> labels, Instant start, Instant end) {
        return new MaintenanceWindows.Window(1, start, end, alertname, labels, "작업", "op-02", start, null, null);
    }

    /** 앱 계층 CPU 가 증설 임계 이상으로 4분 이어진 부하 판정(지속). */
    static LoadPattern sustained() {
        return new LoadPattern(LoadPattern.Kind.SUSTAINED, 0.72, new BigDecimal("0.60"), 240, 180, 240, 1, 900, 0.03,
                0.9, 1, NOW, NOW.minusSeconds(240));
    }

    /** 앱 계층 CPU 가 증설 임계를 60초만 넘은 부하 판정(일시적). */
    static LoadPattern transientLoad() {
        return new LoadPattern(LoadPattern.Kind.TRANSIENT, 0.72, new BigDecimal("0.60"), 60, 180, 60, 1, 900, 0.1,
                0.8, 1, NOW, NOW.minusSeconds(60));
    }

    /** 근거 만들기. 기본값은 조회 계층 3/2~4 대, 평균 CPU 0.5, 임계 0.60·0.30, 원천 모두 신선, 부하 판정 지속. */
    static final class Ev {
        MspProperties.ReadCapacityMode readMode = MspProperties.ReadCapacityMode.CAPACITY_PLAN;
        AiEvidence.ReadTier read = new AiEvidence.ReadTier(2, 3, 4, 3, 0.5, NOW, null);
        Map<String, AiEvidence.Threshold> thresholds = new LinkedHashMap<>();
        List<AiEvidence.Alert> alerts = new ArrayList<>();
        Instant polledAt = NOW;
        Map<String, AiEvidence.Metric> metrics = new LinkedHashMap<>();
        AiEvidence.Bot bot = new AiEvidence.Bot(4.0, "local", NOW);
        List<MaintenanceWindows.Window> windows = new ArrayList<>();
        boolean databasus = false;
        LoadPattern load = sustained();

        Ev() {
            threshold("read.scale_out_cpu", "0.60", "0.20", "0.85", "0.05", true);
            threshold("read.scale_in_cpu", "0.30", "0.05", "0.50", "0.05", true);
            threshold("host.disk_free_alert", "0.15", "0.10", "0.40", "0.05", true);
            threshold("db.replication_lag_alert", null, "5", "300", "10", true);
            threshold("host.cpu_alert", "0.80", "0.60", "0.95", "0.05", false);
        }

        Ev threshold(String key, String value, String min, String max, String step, boolean inUse) {
            thresholds.put(key, new AiEvidence.Threshold(key, key.endsWith("_alert") && key.startsWith("db.")
                    ? "seconds" : "ratio", value == null ? null : new BigDecimal(value), new BigDecimal(min),
                    new BigDecimal(max), new BigDecimal(step), inUse));
            return this;
        }

        Ev cpu(Double cpu, Instant lowSince) {
            read = new AiEvidence.ReadTier(read.min(), read.desired(), read.max(), read.observed(), cpu, read.sampledAt(),
                    lowSince);
            return this;
        }

        Ev nodes(int min, int desired, int max) {
            read = new AiEvidence.ReadTier(min, desired, max, desired, read.avgCpu(), read.sampledAt(), read.lowSince());
            return this;
        }

        Ev metric(String name, Double value, Instant at) {
            metrics.put(name, new AiEvidence.Metric(value, at));
            return this;
        }

        Ev load(LoadPattern load) {
            this.load = load;
            return this;
        }

        AiEvidence build() {
            return new AiEvidence(NOW, MspProperties.Environment.LOCAL_DOCKER, readMode, "AUTO",
                    readMode == MspProperties.ReadCapacityMode.CAPACITY_PLAN ? read : null, thresholds, alerts, true,
                    polledAt, metrics, bot, windows, databasus, Map.of(), load);
        }
    }

    static AiPolicy.Request request(int consecutive, String confidence) {
        return new AiPolicy.Request("PROPOSED", NOW.plus(Duration.ofMinutes(10)), null, consecutive,
                new BigDecimal(confidence));
    }

    static AiPolicy.Facts facts(AiEvidence ev, AiControl.Mode mode, AiPolicy.Request request, AiPolicy.History history) {
        return new AiPolicy.Facts(NOW, ev, mode, limits(), Duration.ofMinutes(10), request,
                new AiPolicy.Decision("PROPOSED", NOW, ev), history);
    }
}

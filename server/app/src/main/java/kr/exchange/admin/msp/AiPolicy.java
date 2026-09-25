package kr.exchange.admin.msp;

import kr.exchange.admin.AdminRole;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 조치 정책 엔진(설계 7-6). AI 가 아닌 코드이고, 주입한 사실(시각 포함)만으로 허용과 거부를 내는 순수 함수다.
 * 규칙 하나가 함수 하나이고, 거부는 규칙 id 와 사유 분류를 싣는다. 모델이 무엇을 내든 여기서 다시 검사한 조치만
 * 실행된다(ADR 0035 두 층 실행).
 */
final class AiPolicy {

    /** 거부 한 건. code 는 감사의 사유 분류, message 는 화면에 그대로 나가는 합니다체 문장이다. */
    record Denial(String rule, String code, String message) {
    }

    // 아래 넷은 ADR 0035 가 정한 값이라 설정으로 느슨하게 할 수 없다.
    static final Duration OBSERVE = Duration.ofMinutes(5);
    static final Duration READ_COOLDOWN = Duration.ofMinutes(10);
    static final Duration THRESHOLD_COOLDOWN = Duration.ofMinutes(30);
    static final int SILENCE_MAX_MINUTES = 30;
    static final int CONSECUTIVE = 2;
    // 봇 부하 1회 최대 절반(1단계 화면 규칙)과 결과 하한(제안값)
    static final BigDecimal BOT_MIN_FACTOR = new BigDecimal("0.5");
    static final BigDecimal BOT_FLOOR_OPS = new BigDecimal("1.0");
    static final String BOT_GUARD = "bot.capacity_guard";
    // 쿨다운이 없는 종류(보류, 백업)도 사람이 거절한 같은 조치를 곧바로 자동 실행하지 않는다(제안값, 정책 A9).
    static final Duration REJECT_HOLD_MIN = Duration.ofMinutes(15);

    // 관측 경로 경보. AI 근거 자체의 신뢰를 알리므로 AI 가 누르지 못한다(정책 S5).
    static final Set<String> OBSERVABILITY_COMPONENTS = Set.of("monitoring", "watchdog");
    // 복제·DB·백업·디스크 계열. 계획 작업 창이 있어도 AI 가 누르지 못한다(정책 S7, 설계 7-13).
    static final Set<String> FORBIDDEN_COMPONENTS = Set.of("database", "db", "backup", "storage");
    static final List<String> FORBIDDEN_NAME_PARTS = List.of("database", "replication", "replica", "backup", "disk",
            "inode", "postgres");

    enum Path { PROPOSAL, APPROVAL, AUTO }

    /** 조치 요청 행의 상태. 제안 때는 없다. */
    record Request(String status, Instant expiresAt, Instant deferredUntil, int consecutive, BigDecimal confidence) {
    }

    /** 요청을 마지막으로 낸 판단. 자동 실행 때만 쓴다. evidence 는 그 판단이 모델에 보낸 근거다. */
    record Decision(String outcome, Instant decidedAt, AiEvidence evidence) {
    }

    /** DB 에서 읽는 이력. 잠금을 잡은 뒤에 읽어야 사이에 커밋된 사람의 조작을 놓치지 않는다(정책 A8·C5). */
    record History(boolean executingExists, Instant observeUntil, int autoUsed, int thresholdUsedForKey,
                   Instant lastSameKind, Instant deferredUntilSameKey, Instant rejectedAtSameKey,
                   boolean lastSilenceByAi, int backupAutoToday, boolean backupInProgress) {

        static final History NONE = new History(false, null, 0, 0, null, null, null, false, 0, false);
    }

    record Facts(Instant now, AiEvidence evidence, AiControl.Mode mode, AiLimits limits, Duration scaleInSustain,
                 Request request, Decision decision, History history) {
    }

    private AiPolicy() {
    }

    /* ===== 조합 ===== */

    /** 제안 때. 종류별 상태 규칙과 방향 전제만 본다. 막히면 요청은 DENIED 로 남는다. */
    static Denial proposal(AiAction a, Facts f) {
        Denial d = kindRules(a, f, false);
        return d != null ? d : direction(a, f);
    }

    /** 사람의 승인 실행. 공통 규칙, 종류별 규칙, 방향 전제, 정지. 빈도 규칙은 걸지 않는다. */
    static Denial approval(AiAction a, Facts f) {
        Denial d = first(m9Stopped(f.mode()), c3Open(f.request(), f.now()), c4Single(f.history().executingExists()),
                c5Target(a, f.evidence()));
        if (d != null) {
            return d;
        }
        d = kindRules(a, f, false);
        return d != null ? d : direction(a, f);
    }

    /** 자동 실행. 자동 모드 규칙과 빈도 규칙까지 모두 건다. A7 로 막히면 호출부가 수동으로 돌리고 통지한다. */
    static Denial auto(AiAction a, Facts f) {
        Request r = f.request();
        History h = f.history();
        Denial d = first(
                m9Stopped(f.mode()),
                a1Auto(f.mode(), f.now()),
                a2Outcome(f.decision().outcome()),
                c3Open(r, f.now()),
                c4Single(h.executingExists()),
                c5Target(a, f.evidence()),
                a9Human(a.kind(), r, h, f.limits(), f.now()),
                a3Confidence(r.confidence(), f.limits().confidencePct()),
                a4Consecutive(r.consecutive()),
                a5Fresh(a, f.decision(), f.evidence(), f.now()),
                a6Observe(h.observeUntil(), f.now()),
                a7Cap(h.autoUsed(), f.limits().autoCap()),
                a8Cooldown(a.kind(), h.lastSameKind(), f.limits(), f.now()));
        if (d != null) {
            return d;
        }
        d = kindRules(a, f, true);
        return d != null ? d : direction(a, f);
    }

    private static Denial kindRules(AiAction a, Facts f, boolean auto) {
        AiEvidence ev = f.evidence();
        History h = f.history();
        return switch (a) {
            case AiAction.ReadNodes r -> first(r2Mode(ev.readMode()), r1Bounds(r, ev.read()));
            case AiAction.Threshold t -> {
                AiEvidence.Threshold item = ev.thresholds().get(t.key());
                yield first(t5InUse(t, item), t1Set(t, item), t2Range(t, item), t3Gap(t, ev.thresholds()),
                        auto ? t4PerItem(h.thresholdUsedForKey(), f.limits().thresholdPerItem()) : null);
            }
            case AiAction.Silence s -> {
                AiEvidence.Alert alert = ev.alert(s.fingerprint());
                yield first(s1Warning(alert), s2Minutes(s.minutes()), s4NotSilenced(alert),
                        s5NotObservability(alert), s7NotForbiddenFamily(alert),
                        s6PlannedWindow(s, alert, ev.windows(), f.now()),
                        auto ? s3NotConsecutive(h.lastSilenceByAi()) : null);
            }
            case AiAction.BotLoad b -> first(b3Source(ev.bot()), b1Reduce(b.factor()), b2Factor(b.factor(), ev.bot()));
            case AiAction.BackupNow k -> first(k1Connected(ev.databasusConnected()),
                    auto ? k2Auto(h.backupAutoToday(), ev.metric(AiEvidence.HOST_ROOT_FREE_MIN),
                            f.limits().backupDiskFreePct(), h.backupInProgress()) : null);
        };
    }

    private static Denial direction(AiAction a, Facts f) {
        return switch (a) {
            case AiAction.ReadNodes r -> r.delta() > 0 ? d1ScaleOut(f.evidence())
                    : d2ScaleIn(f.evidence(), f.scaleInSustain(), f.now());
            case AiAction.Threshold t -> d3Threshold(t, f.evidence());
            case AiAction.BotLoad b -> d4BotLoad(f.evidence());
            default -> null;
        };
    }

    /* ===== 공통 ===== */

    /** C3. 열린 요청만 실행한다. 만료는 나중에로 늘지 않는다. */
    static Denial c3Open(Request r, Instant now) {
        if (r == null || !("PROPOSED".equals(r.status()) || "DEFERRED".equals(r.status()))) {
            return new Denial("C3", "not_open", "이미 처리되었거나 열려 있지 않은 조치 요청입니다.");
        }
        if (!now.isBefore(r.expiresAt())) {
            return new Denial("C3", "expired", "만료된 조치 요청입니다.");
        }
        return null;
    }

    /** C4. 한 번에 한 실행. */
    static Denial c4Single(boolean executingExists) {
        return executingExists ? new Denial("C4", "executing", "다른 조치가 실행 중입니다.") : null;
    }

    /**
     * C5. 임계값 요청은 판단 때의 현재값(from)에서 바꾸는 요청이다. 실행 직전의 현재값이 다르면 요청이 본 상황이 아니다.
     * 대상 행을 잠그고 근거와 대조하는 일은 실행기가 한다(설계 7-7). 항목이 없거나 미설정이면 T1 이 막는다.
     */
    static Denial c5Target(AiAction a, AiEvidence ev) {
        if (!(a instanceof AiAction.Threshold t) || t.from() == null) {
            return null;
        }
        AiEvidence.Threshold item = ev.thresholds().get(t.key());
        if (item == null || item.value() == null || item.value().compareTo(t.from()) == 0) {
            return null;
        }
        return new Denial("C5", "target_changed", "요청 뒤에 " + t.key() + " 현재값이 바뀌어("
                + ApiFormats.amount(t.from()) + "→" + ApiFormats.amount(item.value()) + ") 실행하지 않습니다.");
    }

    /** C6. 사람이 승인할 때 필요한 역할은 같은 조치를 직접 할 때의 역할이다. */
    static AdminRole requiredRole(AiAction.Kind kind) {
        return switch (kind) {
            case READ_NODES, THRESHOLD, BACKUP_NOW -> AdminRole.SUPER;
            case SILENCE, BOT_LOAD -> AdminRole.BOT_OPS;
        };
    }

    /* ===== 자동 모드 ===== */

    /** M9. 정지 중에는 판단, 조치 요청 승인, 자동 실행이 없다. 자동 되돌림은 이 규칙을 거치지 않는다. */
    static Denial m9Stopped(AiControl.Mode mode) {
        return mode.stopped() ? new Denial("M9", "ai_stopped", "AI 정지 중입니다. 재개한 뒤에 할 수 있습니다.") : null;
    }

    /** A1. 자동 모드이고 유효 시간 안. */
    static Denial a1Auto(AiControl.Mode mode, Instant now) {
        if (!mode.auto()) {
            return new Denial("A1", "manual_mode", "자동 모드가 아닙니다.");
        }
        if (!now.isBefore(mode.validUntil())) {
            return new Denial("A1", "auto_expired", "자동 모드 유효 시간이 지났습니다.");
        }
        return null;
    }

    /** A2. 모델 응답이 정상이고 형식이 맞았다. */
    static Denial a2Outcome(String outcome) {
        return "PROPOSED".equals(outcome) ? null
                : new Denial("A2", "model_output", "모델 응답이 정상 제안이 아닙니다: " + outcome);
    }

    /** A3. 확신도 기준. */
    static Denial a3Confidence(BigDecimal confidence, int confidencePct) {
        BigDecimal floor = BigDecimal.valueOf(confidencePct).movePointLeft(2);
        return confidence != null && confidence.compareTo(floor) >= 0 ? null
                : new Denial("A3", "low_confidence", "확신도 " + (confidence == null ? "없음" : ApiFormats.amount(confidence))
                + "가 기준 " + ApiFormats.amount(floor) + "보다 낮습니다.");
    }

    /**
     * A4. 같은 요청이 연속 두 번. 무작위 흔들림은 거르지만 같은 입력에 같은 오답을 내는 결정적 설정의 체계적 오답은
     * 막지 못한다. 그 몫은 방향 전제(D1~D3)가 한다(설계 7-13).
     */
    static Denial a4Consecutive(int consecutive) {
        return consecutive >= CONSECUTIVE ? null
                : new Denial("A4", "not_consecutive", "같은 판단이 연속 " + CONSECUTIVE + "번 나오지 않았습니다.");
    }

    /**
     * A5. 필요한 근거가 모두 60초 안. 판단이 받은 근거가 응답 시각에 이미 오래됐으면 그 판단으로 실행하지 않고,
     * 실행 직전에 새로 모은 근거도 60초 안이어야 한다. 모델 적재로 응답이 늦은 판단은 여기서 걸린다.
     */
    static Denial a5Fresh(AiAction a, Decision decision, AiEvidence fresh, Instant now) {
        Instant judged = decision.evidence().oldestRequired(a);
        if (judged == null || Duration.between(judged, decision.decidedAt()).compareTo(AiEvidence.MAX_AGE) > 0) {
            return new Denial("A5", "stale_evidence", "판단에 쓴 근거가 없거나 60초보다 오래됐습니다.");
        }
        Instant current = fresh.oldestRequired(a);
        if (current == null || Duration.between(current, now).compareTo(AiEvidence.MAX_AGE) > 0) {
            return new Denial("A5", "stale_evidence", "실행 직전에 다시 읽은 근거가 없거나 60초보다 오래됐습니다.");
        }
        return null;
    }

    /** A6. 진행 중인 관찰 창이 없다. */
    static Denial a6Observe(Instant observeUntil, Instant now) {
        return observeUntil != null && now.isBefore(observeUntil)
                ? new Denial("A6", "observing", "앞선 조치의 관찰 창(5분)이 끝나지 않았습니다.") : null;
    }

    /** A7. 이번 자동 모드 활성의 자동 조치 상한. */
    static Denial a7Cap(int used, int cap) {
        return used < cap ? null
                : new Denial("A7", "auto_cap_reached", "이번 자동 모드의 자동 조치 상한 " + cap + "회에 닿았습니다.");
    }

    /** A8. 종류별 쿨다운. 사람의 같은 종류 조치도 쿨다운을 시작시킨다. */
    static Denial a8Cooldown(AiAction.Kind kind, Instant lastSameKind, AiLimits limits, Instant now) {
        Duration cooldown = cooldown(kind, limits);
        if (lastSameKind == null || cooldown.isZero()) {
            return null;
        }
        Instant until = lastSameKind.plus(cooldown);
        return now.isBefore(until) ? new Denial("A8", "cooldown", "같은 종류 조치의 쿨다운이 "
                + ApiFormats.time(until) + "까지입니다.") : null;
    }

    static Duration cooldown(AiAction.Kind kind, AiLimits limits) {
        return switch (kind) {
            case READ_NODES -> READ_COOLDOWN;
            case THRESHOLD -> THRESHOLD_COOLDOWN;
            case BOT_LOAD -> Duration.ofMinutes(limits.botCooldownMin());
            case SILENCE, BACKUP_NOW -> Duration.ZERO;
        };
    }

    /**
     * A9. 사람의 결정이 우선이다. 나중에로 둔 요청은 자동 실행하지 않고, 같은 조치는 나중에 시각까지 자동 실행하지
     * 않으며, 사람이 거절한 같은 조치는 그 종류의 쿨다운(최소 15분) 동안 자동 실행하지 않는다.
     */
    static Denial a9Human(AiAction.Kind kind, Request r, History h, AiLimits limits, Instant now) {
        if (r.deferredUntil() != null || "DEFERRED".equals(r.status())) {
            return new Denial("A9", "deferred", "나중에로 둔 요청은 사람의 승인으로만 실행합니다.");
        }
        if (h.deferredUntilSameKey() != null && now.isBefore(h.deferredUntilSameKey())) {
            return new Denial("A9", "deferred", "같은 조치가 " + ApiFormats.time(h.deferredUntilSameKey())
                    + "까지 나중에로 미뤄져 있습니다.");
        }
        if (h.rejectedAtSameKey() != null) {
            Duration hold = cooldown(kind, limits);
            hold = hold.compareTo(REJECT_HOLD_MIN) < 0 ? REJECT_HOLD_MIN : hold;
            if (now.isBefore(h.rejectedAtSameKey().plus(hold))) {
                return new Denial("A9", "rejected", "사람이 거절한 같은 조치라 한동안 자동 실행하지 않습니다.");
            }
        }
        return null;
    }

    /* ===== 조회 노드 ===== */

    /** R1. ±1 이고 결과 대수가 최소~최대 안. */
    static Denial r1Bounds(AiAction.ReadNodes r, AiEvidence.ReadTier read) {
        if (Math.abs(r.delta()) != 1) {
            return new Denial("R1", "bad_delta", "조회 노드는 한 번에 1대만 바꿉니다.");
        }
        if (read == null) {
            return new Denial("R1", "no_read_tier", "조회 계층 정보가 없습니다.");
        }
        int next = read.desired() + r.delta();
        if (next < read.min() || next > read.max()) {
            return new Denial("R1", "out_of_bounds", "희망 " + read.desired() + "대에서 " + next + "대는 최소 "
                    + read.min() + "~최대 " + read.max() + "대 밖입니다.");
        }
        return null;
    }

    /** R2. 로컬 조정기(CAPACITY_PLAN)만. AWS 는 ASG 정책이 대수를 소유한다. */
    static Denial r2Mode(MspProperties.ReadCapacityMode mode) {
        return mode == MspProperties.ReadCapacityMode.CAPACITY_PLAN ? null
                : new Denial("R2", "read_capacity_mode", "이 환경에서는 AI가 조회 노드 수를 바꾸지 않습니다(" + mode + ").");
    }

    /* ===== 임계값 ===== */

    /** T1. 항목이 있고 값이 설정돼 있다. 미설정 항목에 AI 가 처음 값을 넣지 않는다. */
    static Denial t1Set(AiAction.Threshold t, AiEvidence.Threshold item) {
        if (item == null) {
            return new Denial("T1", "unknown_threshold", "없는 임계값 항목입니다: " + t.key());
        }
        return item.value() == null ? new Denial("T1", "unset_threshold", "미설정 항목에 AI가 처음 값을 넣지 않습니다.") : null;
    }

    /** T2. 허용 범위 안, 현재값과의 차이가 AI 1회 폭 이하, 소수 넷째 자리까지, 현재값과 다름. */
    static Denial t2Range(AiAction.Threshold t, AiEvidence.Threshold item) {
        if (item == null || item.value() == null) {
            return null;
        }
        if (t.value().stripTrailingZeros().scale() > OpsThresholdService.MAX_SCALE) {
            return new Denial("T2", "scale", "값은 소수 넷째 자리까지입니다.");
        }
        if (t.value().compareTo(item.min()) < 0 || t.value().compareTo(item.max()) > 0) {
            return new Denial("T2", "out_of_range", "값 " + ApiFormats.amount(t.value()) + "이 허용 범위 "
                    + ApiFormats.amount(item.min()) + "~" + ApiFormats.amount(item.max()) + " 밖입니다.");
        }
        BigDecimal step = t.value().subtract(item.value()).abs();
        if (step.signum() == 0) {
            return new Denial("T2", "no_change", "현재값과 같은 값입니다.");
        }
        if (step.compareTo(item.step()) > 0) {
            return new Denial("T2", "step", "한 번에 바꿀 수 있는 폭 " + ApiFormats.amount(item.step()) + "을 넘습니다.");
        }
        return null;
    }

    /** T3. 축소 임계는 증설 임계보다 0.15 이상 낮다. */
    static Denial t3Gap(AiAction.Threshold t, Map<String, AiEvidence.Threshold> items) {
        if (!OpsThresholdService.SCALE_OUT.equals(t.key()) && !OpsThresholdService.SCALE_IN.equals(t.key())) {
            return null;
        }
        AiEvidence.Threshold out = items.get(OpsThresholdService.SCALE_OUT);
        AiEvidence.Threshold in = items.get(OpsThresholdService.SCALE_IN);
        BigDecimal scaleOut = OpsThresholdService.SCALE_OUT.equals(t.key()) ? t.value() : out == null ? null : out.value();
        BigDecimal scaleIn = OpsThresholdService.SCALE_IN.equals(t.key()) ? t.value() : in == null ? null : in.value();
        return OpsThresholdService.violatesReadGap(scaleOut, scaleIn)
                ? new Denial("T3", "read_gap", "축소 임계는 증설 임계보다 0.15 이상 낮아야 합니다.") : null;
    }

    /** T4. 이번 활성에서 항목 하나의 자동 변경 횟수. 자동 실행에만 건다. */
    static Denial t4PerItem(int used, int limit) {
        return used < limit ? null
                : new Denial("T4", "per_item_cap", "이번 자동 모드에서 이 항목을 이미 " + used + "번 바꿨습니다.");
    }

    /** T5. 이 환경에서 쓰는 항목만 바꾼다. 조회 임계는 로컬 조정기(CAPACITY_PLAN)에서만 쓰인다. */
    static Denial t5InUse(AiAction.Threshold t, AiEvidence.Threshold item) {
        return item != null && !item.inUse()
                ? new Denial("T5", "not_in_use", "이 환경에서 쓰지 않는 항목이라 AI가 바꾸지 않습니다: " + t.key()) : null;
    }

    /* ===== 경보 보류 ===== */

    /** S1. 대상 경보가 활성이고 warning. critical, info, 등급 모름(other)은 안 된다. */
    static Denial s1Warning(AiEvidence.Alert alert) {
        if (alert == null) {
            return new Denial("S1", "alert_not_active", "활성 경보가 아닙니다.");
        }
        return "warning".equals(alert.severity()) ? null
                : new Denial("S1", "severity", "AI는 warning 경보만 보류합니다(" + alert.severity() + ").");
    }

    /** S2. 30분 이하. */
    static Denial s2Minutes(int minutes) {
        return minutes >= 1 && minutes <= SILENCE_MAX_MINUTES ? null
                : new Denial("S2", "minutes", "보류는 1~" + SILENCE_MAX_MINUTES + "분입니다.");
    }

    /** S3. 그 지문의 직전 보류가 AI 이고 그 뒤 사람의 확인이나 보류가 없으면 다시 보류하지 않는다. 자동에만 건다. */
    static Denial s3NotConsecutive(boolean lastSilenceByAi) {
        return lastSilenceByAi ? new Denial("S3", "consecutive_silence", "같은 경보를 AI가 잇달아 보류하지 않습니다.") : null;
    }

    /** S4. 이미 사일런스에 눌린 경보가 아니다. */
    static Denial s4NotSilenced(AiEvidence.Alert alert) {
        return alert != null && alert.silenced() ? new Denial("S4", "already_silenced", "이미 보류된 경보입니다.") : null;
    }

    /** S5. 관측 경로 경보가 아니다. */
    static Denial s5NotObservability(AiEvidence.Alert alert) {
        return alert != null && observabilityPath(alert)
                ? new Denial("S5", "observability_alert", "관측 경로 경보는 AI가 보류하지 않습니다.") : null;
    }

    /**
     * S6. 운영자가 등록한 계획 작업 창과 겹치는 경보만, 보류의 끝이 창의 끝을 넘지 않게. 창 밖의 AI 보류 요청은
     * '계획 작업 창 없음'으로 거부한다.
     */
    static Denial s6PlannedWindow(AiAction.Silence s, AiEvidence.Alert alert, List<MaintenanceWindows.Window> windows,
                                  Instant now) {
        if (alert == null) {
            return null;
        }
        Instant end = now.plus(Duration.ofMinutes(s.minutes()));
        boolean anyMatch = false;
        for (MaintenanceWindows.Window w : windows) {
            if (!w.activeAt(now) || !w.matches(alert.alertname(), alert.labels())) {
                continue;
            }
            anyMatch = true;
            if (!end.isAfter(w.endsAt())) {
                return null;
            }
        }
        return anyMatch
                ? new Denial("S6", "window_ends_first", "보류의 끝이 계획 작업 창의 끝을 넘습니다.")
                : new Denial("S6", "no_planned_window", "계획 작업 창 없음: 이 경보와 겹치는 계획 작업 창이 없어 AI가 보류하지 않습니다.");
    }

    /** S7. 복제·DB·백업·디스크 계열 경보는 창이 있어도 AI 가 보류하지 않는다. */
    static Denial s7NotForbiddenFamily(AiEvidence.Alert alert) {
        return alert != null && forbiddenFamily(alert)
                ? new Denial("S7", "forbidden_alert_family", "복제·DB·백업·디스크 계열 경보는 AI가 보류하지 않습니다.") : null;
    }

    static boolean observabilityPath(AiEvidence.Alert alert) {
        return alert.component() != null && OBSERVABILITY_COMPONENTS.contains(alert.component());
    }

    static boolean forbiddenFamily(AiEvidence.Alert alert) {
        if (alert.component() != null && FORBIDDEN_COMPONENTS.contains(alert.component())) {
            return true;
        }
        String name = alert.alertname() == null ? "" : alert.alertname().toLowerCase(Locale.ROOT);
        return FORBIDDEN_NAME_PARTS.stream().anyMatch(name::contains);
    }

    /* ===== 봇 부하 ===== */

    /** B1. 줄이는 방향만. */
    static Denial b1Reduce(BigDecimal factor) {
        return factor.compareTo(BigDecimal.ONE) < 0 ? null
                : new Denial("B1", "direction_conflict", "방향 모순: 봇 부하는 줄이는 방향만 됩니다.");
    }

    /** B2. 1회 최대 절반, 결과가 하한 이상. */
    static Denial b2Factor(BigDecimal factor, AiEvidence.Bot bot) {
        if (factor.compareTo(BOT_MIN_FACTOR) < 0) {
            return new Denial("B2", "factor", "봇 부하는 한 번에 절반까지만 줄입니다.");
        }
        if (bot.targetOps() != null && BigDecimal.valueOf(bot.targetOps()).multiply(factor).compareTo(BOT_FLOOR_OPS) < 0) {
            return new Denial("B2", "floor", "줄인 발행률이 하한 " + ApiFormats.amount(BOT_FLOOR_OPS) + " OPS 아래입니다.");
        }
        return null;
    }

    /** B3. 현재 목표 발행률을 아는 원천이 있다(같은 JVM 의 거버너이거나 오래되지 않은 발행 상태). */
    static Denial b3Source(AiEvidence.Bot bot) {
        return bot.targetOps() == null ? new Denial("B3", "rate_unknown", "현재 봇 발행률을 알 수 없습니다.") : null;
    }

    /* ===== 백업 ===== */

    /** K1. Databasus 연결. 지금은 연결 경로가 없는 스텁이라 늘 거부한다. */
    static Denial k1Connected(boolean connected) {
        return connected ? null : new Denial("K1", "databasus_not_connected", "Databasus가 연결되지 않았습니다.");
    }

    /** K2. 자동 즉시 실행은 하루 1회, 저장 디스크 여유가 한도 이상, 진행 중 백업 없음. */
    static Denial k2Auto(int today, AiEvidence.Metric diskFree, int minFreePct, boolean inProgress) {
        if (today >= 1) {
            return new Denial("K2", "daily_cap", "백업 자동 즉시 실행은 하루 1회입니다.");
        }
        if (inProgress) {
            return new Denial("K2", "in_progress", "진행 중인 백업이 있습니다.");
        }
        BigDecimal floor = BigDecimal.valueOf(minFreePct).movePointLeft(2);
        if (diskFree.value() == null || BigDecimal.valueOf(diskFree.value()).compareTo(floor) < 0) {
            return new Denial("K2", "disk_free", "저장 디스크 여유가 " + minFreePct + "% 미만이거나 알 수 없습니다.");
        }
        return null;
    }

    /* ===== 방향 전제(결정적 코드 규칙) ===== */

    /** D1. 조회 노드 증설은 근거의 조회 평균 CPU 가 증설 임계 이상일 때만. */
    static Denial d1ScaleOut(AiEvidence ev) {
        AiEvidence.ReadTier read = ev.read();
        BigDecimal limit = value(ev, OpsThresholdService.SCALE_OUT);
        if (read == null || read.avgCpu() == null || limit == null) {
            return new Denial("D1", "direction_conflict", "방향 모순: 조회 평균 CPU를 모르면 증설하지 않습니다.");
        }
        BigDecimal cpu = BigDecimal.valueOf(read.avgCpu());
        return cpu.compareTo(limit) >= 0 ? null
                : new Denial("D1", "direction_conflict", "방향 모순: 조회 평균 CPU " + ratio(cpu) + "가 증설 임계 "
                + ApiFormats.amount(limit) + " 이상이 아닙니다.");
    }

    /** D2. 조회 노드 축소는 축소 임계 미만이 설정 시간 이상 이어질 때만. */
    static Denial d2ScaleIn(AiEvidence ev, Duration sustain, Instant now) {
        AiEvidence.ReadTier read = ev.read();
        BigDecimal limit = value(ev, OpsThresholdService.SCALE_IN);
        if (read == null || read.avgCpu() == null || limit == null) {
            return new Denial("D2", "direction_conflict", "방향 모순: 조회 평균 CPU를 모르면 축소하지 않습니다.");
        }
        BigDecimal cpu = BigDecimal.valueOf(read.avgCpu());
        if (cpu.compareTo(limit) >= 0) {
            return new Denial("D2", "direction_conflict", "방향 모순: 조회 평균 CPU " + ratio(cpu) + "가 축소 임계 "
                    + ApiFormats.amount(limit) + " 미만이 아닙니다.");
        }
        if (read.lowSince() == null || Duration.between(read.lowSince(), now).compareTo(sustain) < 0) {
            return new Denial("D2", "direction_conflict", "방향 모순: 축소 임계 미만이 " + sustain.toMinutes()
                    + "분 이상 이어지지 않았습니다.");
        }
        return null;
    }

    /**
     * D3. 임계값을 둔하게 바꾸는 변경은 그 항목의 경보가 울리는 중이 아니고, 근거 지표가 임계를 넘지 않았고, 근거
     * 지표를 알 때만 받는다. 경보가 쏟아진다는 이유로 임계를 둔하게 만드는 변경을 막는다. 민감하게 바꾸는 변경은
     * 문제를 가리지 않으므로 이 규칙에 걸지 않는다.
     */
    static Denial d3Threshold(AiAction.Threshold t, AiEvidence ev) {
        AiEvidence.Threshold item = ev.thresholds().get(t.key());
        if (item == null || item.value() == null || !loosens(t.key(), item.value(), t.value())) {
            return null;
        }
        for (AiEvidence.Alert a : ev.alerts()) {
            if (t.key().equals(a.thresholdKey()) && !a.silenced()) {
                return new Denial("D3", "direction_conflict", "방향 모순: 경보 " + a.alertname()
                        + "가 울리는 중에 그 임계를 둔하게 바꾸지 않습니다.");
            }
        }
        BigDecimal current = observed(t.key(), ev);
        if (current == null) {
            return new Denial("D3", "direction_conflict", "방향 모순: 근거 지표를 모르면 임계를 둔하게 바꾸지 않습니다.");
        }
        boolean over = t.key().startsWith("read.")
                ? readOver(ev, current) : AiEvidence.beyond(t.key(), current, item.value());
        return over ? new Denial("D3", "direction_conflict", "방향 모순: 근거 지표 " + ApiFormats.amount(current)
                + "가 이미 임계를 넘은 상태에서 임계를 둔하게 바꾸지 않습니다.") : null;
    }

    /**
     * D4. 봇 부하 줄이기는 bot-1 CPU 가 봇 용량 보호 임계 이상일 때만. 그 임계가 AI 봇 부하 줄임의 기준이고(설계 5-1),
     * 미설정이거나 CPU 를 모르면 줄이지 않는다. 경보 문장에 끼워 넣은 지시를 모델이 따라도 근거의 수치가 막는다.
     */
    static Denial d4BotLoad(AiEvidence ev) {
        AiEvidence.Threshold guard = ev.thresholds().get(BOT_GUARD);
        Double cpu = ev.metric(AiEvidence.BOT_HOST_CPU).value();
        if (guard == null || guard.value() == null || cpu == null) {
            return new Denial("D4", "direction_conflict", "방향 모순: 봇 용량 보호 임계나 봇 호스트 CPU를 모르면 봇 부하를"
                    + " 줄이지 않습니다.");
        }
        BigDecimal value = BigDecimal.valueOf(cpu);
        return value.compareTo(guard.value()) >= 0 ? null
                : new Denial("D4", "direction_conflict", "방향 모순: 봇 호스트 CPU " + ratio(value) + "가 용량 보호 임계 "
                + ApiFormats.amount(guard.value()) + " 이상이 아닙니다.");
    }

    /** 경보를 덜 울리게(조회 임계는 용량 보호를 약하게) 하는 방향인가. */
    static boolean loosens(String key, BigDecimal from, BigDecimal to) {
        int dir = to.compareTo(from);
        if (dir == 0) {
            return false;
        }
        return AiEvidence.lowerIsWorse(key) ? dir < 0 : dir > 0;
    }

    private static boolean readOver(AiEvidence ev, BigDecimal cpu) {
        BigDecimal scaleOut = value(ev, OpsThresholdService.SCALE_OUT);
        return scaleOut != null && cpu.compareTo(scaleOut) >= 0;
    }

    private static BigDecimal observed(String key, AiEvidence ev) {
        if (key.startsWith("read.")) {
            return ev.read() == null || ev.read().avgCpu() == null ? null : BigDecimal.valueOf(ev.read().avgCpu());
        }
        String metric = AiEvidence.metricOf(key);
        AiEvidence.Metric m = metric == null ? null : ev.metric(metric);
        return m == null || m.value() == null ? null : BigDecimal.valueOf(m.value());
    }

    private static BigDecimal value(AiEvidence ev, String key) {
        AiEvidence.Threshold item = ev.thresholds().get(key);
        return item == null ? null : item.value();
    }

    private static String ratio(BigDecimal value) {
        return ApiFormats.amount(value.setScale(4, java.math.RoundingMode.HALF_UP));
    }

    /* ===== 공용 ===== */

    static Denial first(Denial... denials) {
        for (Denial d : denials) {
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    static ApiException denied(Denial d) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", d.rule());
        detail.put("reason_code", d.code());
        return new ApiException(409, "msp_policy_denied", d.message(), detail, null);
    }
}

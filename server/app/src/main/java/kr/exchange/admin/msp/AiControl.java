package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.AdminOperatorDirectory;
import kr.exchange.admin.ApprovalService;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 모드, AI 즉시 정지와 재개, AI 한도(설계 7-9·7-14·7-15, DB 설계 §10.4). 모드 행 하나가 자동 창, 활성 번호,
 * 정지를 모두 든다. 자동 창과 활성 번호는 DB 트리거가 정하고, 이 클래스는 누가 무엇을 왜 바꿨는지를 감사와 사건
 * 타임라인에 남긴다.
 */
public class AiControl {

    public static final String AI_OPERATOR = AdminOperatorDirectory.AI_OPERATOR;
    public static final String SYSTEM = "system:msp";
    public static final String AUTO_ACTION = "ops.ai.auto";
    public static final String LIMITS_ACTION = "ops.ai.limits";
    // ADR 0035 가 고른 자동 유효 시간. DB 트리거도 같은 목록만 받는다.
    static final Set<Integer> AUTO_HOURS = Set.of(1, 2, 4, 8);
    private static final int MAX_REASON = 500;
    private static final int MAX_STOP_REASON = 200;

    public record Mode(String mode, String enabledBy, String reason, Long approvalId, Instant since,
                       Instant validUntil, long activation, Instant activationAt, AiLimits activationLimits,
                       boolean stopped, String stoppedBy, Instant stoppedAt, String stopReason) {

        public boolean auto() {
            return "AUTO".equals(mode);
        }
    }

    public record Changed(Mode mode, boolean changed, int expiredActions) {
    }

    /** 자동 켜기의 원천 조건. 호출부가 설정에서 채운다. */
    public record AutoReadiness(boolean llmConfigured, boolean prometheusConfigured, boolean plaintextLlm) {
    }

    public record LimitItem(AiLimits.Key key, int value, long version, String updatedBy, Instant updatedAt,
                            String reason) {
    }

    public record LimitChange(long id, String key, Integer from, int to, long version, String changedBy,
                              Long approvalId, String reason, Instant changedAt) {
    }

    public record LimitApplied(AiLimits.Key key, int from, int to, long version, long changeId, boolean immediate,
                               int effective, Mode mode, boolean switchedToManual) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApprovalService approvals;
    private final AdminAuditService audit;
    private final IncidentStore events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private volatile AiNotifier notifier = AiNotifier.NONE;

    public AiControl(JdbcClient jdbc, TransactionTemplate tx, ApprovalService approvals, AdminAuditService audit,
                     IncidentStore events, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void notifier(AiNotifier notifier) {
        this.notifier = notifier;
    }

    /* ===== 모드 ===== */

    public Mode mode() {
        return jdbc.sql("SELECT " + MODE_COLUMNS + " FROM ops_ai_mode WHERE id = 1").query((rs, n) -> mode(rs)).single();
    }

    /**
     * 트랜잭션 안에서 모드 행을 잠그고 읽는다. AI 실행은 공유 잠금으로 읽어 즉시 끄기·정지와 순서를 정하고,
     * 모드를 바꾸는 쪽은 배타 잠금으로 읽는다(설계 7-7 잠금 순서의 모드 행).
     */
    public Mode lockMode(boolean exclusive) {
        return jdbc.sql("SELECT " + MODE_COLUMNS + " FROM ops_ai_mode WHERE id = 1 " + (exclusive ? "FOR UPDATE" : "FOR SHARE"))
                .query((rs, n) -> mode(rs)).single();
    }

    /** 자동 켜기(정책 M1). 창의 시작·끝과 활성 번호는 DB 트리거가 정한다. */
    public Mode enableAuto(String operatorId, String token, Integer hours, String reason, String requestId,
                           AutoReadiness ready) {
        if (hours == null || !AUTO_HOURS.contains(hours)) {
            throw new ApiException(400, "invalid_parameter", "hours 는 1, 2, 4, 8 가운데 하나여야 합니다.",
                    Map.of("constraint", "range", "field", "hours"), null);
        }
        String why = requiredText(reason, MAX_REASON, "reason");
        if (!ready.llmConfigured()) {
            throw notConnected("llm");
        }
        if (!ready.prometheusConfigured()) {
            throw notConnected("prometheus");
        }
        if (ready.plaintextLlm()) {
            throw AiPolicy.denied(new AiPolicy.Denial("M8", "plaintext_llm",
                    "LLM 전송이 평문이라 자동 모드를 켤 수 없습니다. 수동 모드 전용입니다."));
        }
        Mode after = tx.execute(status -> {
            Mode before = lockMode(true);
            if (before.stopped()) {
                throw AiPolicy.denied(AiPolicy.m9Stopped(before));
            }
            long unknown = jdbc.sql("SELECT count(*) FROM ops_ai_actions WHERE status = 'UNKNOWN'")
                    .query(Long.class).single();
            if (unknown > 0) {
                throw AiPolicy.denied(new AiPolicy.Denial("M1", "unknown_actions",
                        "결과를 모르는 조치 요청이 " + unknown + "건 남아 있어 자동 모드를 켤 수 없습니다."));
            }
            long approvalId = approvals.consume(operatorId, token, AUTO_ACTION, "AUTO:" + hours);
            // since·valid_until·activation 은 트리거가 덮어쓴다. CHECK 를 지나도록 자리만 채운다.
            jdbc.sql("""
                            UPDATE ops_ai_mode SET mode = 'AUTO', enabled_by = ?, reason = ?, approval_id = ?,
                                   since = now(), valid_until = now() + make_interval(hours => ?),
                                   activation_at = now(), activation_limits = '{}'::jsonb,
                                   changed_by = ?, changed_at = now(), version = version + 1
                            WHERE id = 1
                            """)
                    .params(operatorId, why, approvalId, hours, operatorId)
                    .update();
            Mode m = mode();
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("approval_id", approvalId);
            ref.put("activation", m.activation());
            events.event(null, "MODE", "OPERATOR", operatorId,
                    "자동 모드 켬 " + hours + "시간" + (before.auto() ? "(창 새로 열기)" : "") + ": " + why, ref);
            audit.record(operatorId, AUTO_ACTION, "AUTO:" + hours, requestId, approvalId, "OK",
                    Map.of("activation", m.activation(), "valid_until", ApiFormats.time(m.validUntil()),
                            "renewed", before.auto()));
            return m;
        });
        notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.MODE_CHANGED,
                Map.of("mode", "AUTO", "by", operatorId, "valid_until", ApiFormats.time(after.validUntil()))));
        return after;
    }

    /** 즉시 끄기(정책 M3). 이미 수동이면 바뀐 것 없이 성공한다. */
    public Changed manual(String operatorId, String reason, String requestId) {
        String why = optionalText(reason, MAX_REASON, "reason");
        Changed changed = tx.execute(status -> {
            Mode before = lockMode(true);
            if (!before.auto()) {
                return new Changed(before, false, 0);
            }
            clearAuto(operatorId);
            events.event(null, "MODE", "OPERATOR", operatorId, "자동 모드 끔" + (why.isEmpty() ? "" : ": " + why),
                    Map.of());
            audit.record(operatorId, "ops.ai.manual", "MANUAL", requestId, null, "OK", Map.of("reason", why));
            return new Changed(mode(), true, 0);
        });
        if (changed.changed()) {
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.MODE_CHANGED,
                    Map.of("mode", "MANUAL", "by", operatorId, "cause", "operator")));
        }
        return changed;
    }

    /**
     * AI 즉시 정지(정책 M9). 두 모드에서 쓴다. 자동을 풀고 대기 중인 조치 요청을 만료시킨다. 관찰 창과 그 안의
     * 자동 되돌림은 막지 않는다(되돌림은 조치를 무르는 안전 방향이다).
     */
    public Changed stop(String operatorId, String reason, String requestId) {
        String why = optionalText(reason, MAX_STOP_REASON, "reason");
        Instant now = clock.instant();
        Changed changed = tx.execute(status -> {
            Mode before = lockMode(true);
            if (before.stopped()) {
                return new Changed(before, false, 0);
            }
            jdbc.sql("""
                            UPDATE ops_ai_mode SET mode = 'MANUAL', enabled_by = NULL, reason = NULL, approval_id = NULL,
                                   since = NULL, valid_until = NULL, activation_at = NULL, activation_limits = NULL,
                                   stopped = true, stopped_by = ?, stopped_at = ?, stop_reason = ?,
                                   changed_by = ?, changed_at = now(), version = version + 1
                            WHERE id = 1
                            """)
                    .params(operatorId, at(now), why.isEmpty() ? null : why, operatorId)
                    .update();
            int expired = jdbc.sql("""
                            UPDATE ops_ai_actions SET status = 'EXPIRED', hold_rule = 'M9',
                                   hold_reason = 'AI 정지로 만료되었습니다.'
                            WHERE status IN ('PROPOSED','DEFERRED')
                            """)
                    .update();
            events.event(null, "STOPPED", "OPERATOR", operatorId,
                    "AI 정지" + (before.auto() ? "(자동 모드 해제)" : "") + (why.isEmpty() ? "" : ": " + why),
                    Map.of("expired_actions", expired));
            audit.record(operatorId, "ops.ai.stop", "AI", requestId, null, "OK",
                    Map.of("was_auto", before.auto(), "expired_actions", expired, "reason", why));
            return new Changed(mode(), true, expired);
        });
        if (changed.changed()) {
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.STOPPED,
                    Map.of("by", operatorId, "expired_actions", changed.expiredActions())));
        }
        return changed;
    }

    /** 재개(정책 M10). 수동으로만 돌아간다. 자동은 다시 ops.ai.auto 티켓으로 켠다. */
    public Changed resume(String operatorId, String reason, String requestId) {
        String why = requiredText(reason, MAX_STOP_REASON, "reason");
        Changed changed = tx.execute(status -> {
            Mode before = lockMode(true);
            if (!before.stopped()) {
                return new Changed(before, false, 0);
            }
            jdbc.sql("""
                            UPDATE ops_ai_mode SET stopped = false, stopped_by = NULL, stopped_at = NULL,
                                   stop_reason = NULL, changed_by = ?, changed_at = now(), version = version + 1
                            WHERE id = 1
                            """)
                    .param(operatorId)
                    .update();
            events.event(null, "RESUMED", "OPERATOR", operatorId, "AI 재개(수동): " + why, Map.of());
            audit.record(operatorId, "ops.ai.resume", "AI", requestId, null, "OK", Map.of("reason", why));
            return new Changed(mode(), true, 0);
        });
        if (changed.changed()) {
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.RESUMED, Map.of("by", operatorId)));
        }
        return changed;
    }

    /** 자동 창이 끝났으면 수동으로 돌린다(정책 M2). 판단 주기와 관찰 주기가 부른다. */
    public boolean expireIfDue() {
        Mode current = mode();
        Instant now = clock.instant();
        if (!current.auto() || now.isBefore(current.validUntil())) {
            return false;
        }
        boolean expired = Boolean.TRUE.equals(tx.execute(status -> {
            Mode locked = lockMode(true);
            if (!locked.auto() || now.isBefore(locked.validUntil())) {
                return false;
            }
            clearAuto(SYSTEM);
            events.event(null, "MODE", "SYSTEM", SYSTEM, "자동 모드 유효 시간 만료로 수동 전환", Map.of());
            audit.record(SYSTEM, "ops.ai.mode.expire", "MANUAL", null, null, "OK",
                    Map.of("valid_until", ApiFormats.time(locked.validUntil())));
            return true;
        }));
        if (expired) {
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.MODE_CHANGED,
                    Map.of("mode", "MANUAL", "cause", "expired")));
        }
        return expired;
    }

    /**
     * 시스템이 수동으로 돌린다. 상한 도달, 악화, 관찰 불가, 기동 정리 때다(정책 A7·M5·M7). 이미 수동이면 아무것도
     * 하지 않는다. 호출부가 연 트랜잭션이 있으면 거기에 합류한다.
     */
    public boolean switchToManual(String cause, String text) {
        Boolean changed = tx.execute(status -> {
            Mode locked = lockMode(true);
            if (!locked.auto()) {
                return false;
            }
            clearAuto(SYSTEM);
            events.event(null, "MODE", "SYSTEM", SYSTEM, text, Map.of("cause", cause));
            audit.record(SYSTEM, "ops.ai.mode.system", "MANUAL", null, null, "OK", Map.of("cause", cause));
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.MODE_CHANGED,
                    Map.of("mode", "MANUAL", "cause", cause)));
        }
        return Boolean.TRUE.equals(changed);
    }

    private void clearAuto(String by) {
        jdbc.sql("""
                        UPDATE ops_ai_mode SET mode = 'MANUAL', enabled_by = NULL, reason = NULL, approval_id = NULL,
                               since = NULL, valid_until = NULL, activation_at = NULL, activation_limits = NULL,
                               changed_by = ?, changed_at = now(), version = version + 1
                        WHERE id = 1
                        """)
                .param(by)
                .update();
    }

    /* ===== 한도 ===== */

    public AiLimits configuredLimits() {
        Map<String, Integer> byId = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value FROM ops_ai_limits")
                .query((rs, n) -> byId.put(rs.getString("key"), rs.getInt("value")))
                .list();
        return AiLimits.fromIds(byId);
    }

    /**
     * 지금 적용하는 한도(정책 L3). 자동 모드 중이면 활성 때 잡아 둔 값과 지금 값 가운데 AI 권한을 좁히는 쪽이라,
     * 조이는 변경은 곧바로, 푸는 변경은 다음 활성부터 적용된다.
     */
    public AiLimits effectiveLimits(Mode mode) {
        AiLimits configured = configuredLimits();
        return mode.auto() && mode.activationLimits() != null
                ? AiLimits.stricter(mode.activationLimits(), configured) : configured;
    }

    public List<LimitItem> limitItems() {
        return jdbc.sql("SELECT key, value, version, updated_by, updated_at, reason FROM ops_ai_limits")
                .query((rs, n) -> new LimitItem(AiLimits.Key.of(rs.getString("key")), rs.getInt("value"),
                        rs.getLong("version"), rs.getString("updated_by"), instant(rs, "updated_at"),
                        rs.getString("reason")))
                .list()
                .stream()
                .sorted((a, b) -> Integer.compare(a.key().ordinal(), b.key().ordinal()))
                .toList();
    }

    /**
     * 한도 변경(정책 L1·L2·L4). 값과 하드 범위는 티켓을 쓰기 전에 본다. 자동 조치 상한을 이번 활성에서 이미 쓴
     * 횟수 이하로 내리면 같은 트랜잭션에서 수동으로 돌린다.
     */
    public LimitApplied changeLimit(String keyId, Integer value, String reason, String operatorId, String token,
                                    String requestId) {
        AiLimits.Key key = AiLimits.Key.of(keyId);
        if (key == null) {
            throw new ApiException(404, "not_found", "존재하지 않는 AI 한도 항목입니다: " + keyId,
                    Map.of("reason", "unknown_limit"), null);
        }
        if (value == null || !key.inRange(value)) {
            throw new ApiException(400, "invalid_parameter",
                    key.id() + " 는 " + key.min() + "~" + key.max() + " 사이의 정수여야 합니다.",
                    Map.of("constraint", "range", "field", "value"), null);
        }
        String why = requiredText(reason, MAX_REASON, "reason");
        LimitApplied applied = tx.execute(status -> {
            long approvalId = approvals.consume(operatorId, token, LIMITS_ACTION, key.id() + ":" + value);
            Mode mode = lockMode(true);
            int from = jdbc.sql("SELECT value FROM ops_ai_limits WHERE key = ? FOR UPDATE").param(key.id())
                    .query(Integer.class).single();
            long version = jdbc.sql("""
                            UPDATE ops_ai_limits SET value = ?, version = version + 1, updated_by = ?, approval_id = ?,
                                   reason = ?, updated_at = now()
                            WHERE key = ? RETURNING version
                            """)
                    .params(value, operatorId, approvalId, why, key.id())
                    .query(Long.class).single();
            long changeId = jdbc.sql("""
                            INSERT INTO ops_ai_limit_changes
                                (key, from_value, to_value, version, changed_by, approval_id, reason)
                            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                            """)
                    .params(key.id(), from, value, version, operatorId, approvalId, why)
                    .query(Long.class).single();
            AiLimits effective = effectiveLimits(mode);
            boolean immediate = effective.get(key) == value;
            boolean switched = false;
            if (mode.auto() && key == AiLimits.Key.AUTO_CAP && autoUsed(mode.activation()) >= effective.autoCap()) {
                clearAuto(SYSTEM);
                events.event(null, "MODE", "SYSTEM", SYSTEM, "자동 조치 상한을 이미 쓴 횟수 이하로 내려 수동 전환",
                        Map.of("cause", "auto_cap_lowered"));
                audit.record(SYSTEM, "ops.ai.mode.system", "MANUAL", requestId, null, "OK",
                        Map.of("cause", "auto_cap_lowered"));
                switched = true;
            }
            events.event(null, "LIMITS", "OPERATOR", operatorId, "AI 한도 " + key.id() + " " + from + "→" + value
                    + (immediate ? "(즉시 적용)" : "(다음 활성부터)") + ": " + why, Map.of("change_id", changeId));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("from", from);
            detail.put("to", value);
            detail.put("version", version);
            detail.put("applies", immediate ? "NOW" : "NEXT_ACTIVATION");
            audit.record(operatorId, LIMITS_ACTION, key.id(), requestId, approvalId, "OK", detail);
            return new LimitApplied(key, from, value, version, changeId, immediate, effective.get(key), mode(),
                    switched);
        });
        if (applied.switchedToManual()) {
            notifier.publish(new AiNotifier.Notice(AiNotifier.Kind.MODE_CHANGED,
                    Map.of("mode", "MANUAL", "cause", "auto_cap_lowered")));
        }
        return applied;
    }

    public List<CursorPages.Row<LimitChange>> limitChanges(String keyId, Cursors.Cursor cursor, int fetch) {
        StringBuilder sql = new StringBuilder("SELECT id, key, from_value, to_value, version, changed_by, approval_id, "
                + "reason, changed_at FROM ops_ai_limit_changes WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (keyId != null) {
            sql.append(" AND key = ?");
            params.add(keyId);
        }
        if (cursor != null) {
            long anchorId = CursorPages.numericId(cursor.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT changed_at FROM ops_ai_limit_changes WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (changed_at, id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        sql.append(" ORDER BY changed_at DESC, id DESC LIMIT ?");
        params.add(fetch);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    int from = rs.getInt("from_value");
                    Integer fromValue = rs.wasNull() ? null : from;
                    long approval = rs.getLong("approval_id");
                    Long approvalId = rs.wasNull() ? null : approval;
                    LimitChange c = new LimitChange(rs.getLong("id"), rs.getString("key"), fromValue,
                            rs.getInt("to_value"), rs.getLong("version"), rs.getString("changed_by"), approvalId,
                            rs.getString("reason"), instant(rs, "changed_at"));
                    return new CursorPages.Row<>(c, rs.getObject("changed_at", OffsetDateTime.class),
                            String.valueOf(c.id()));
                })
                .list();
    }

    /* ===== 활성당 횟수(정책 A7·T4, L5) ===== */

    /** 이번 활성에서 자동으로 실행한 조치 수. 실패와 되돌림도 센다. 활성 번호는 수동에서 켤 때만 오른다. */
    public int autoUsed(long activation) {
        return jdbc.sql("SELECT count(*) FROM ops_ai_actions WHERE activation = ?").param(activation)
                .query(Integer.class).single();
    }

    public Map<String, Integer> thresholdUsed(long activation) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT params->>'key' AS key, count(*) AS n FROM ops_ai_actions
                        WHERE activation = ? AND kind = 'THRESHOLD' GROUP BY params->>'key' ORDER BY 1
                        """)
                .param(activation)
                .query((rs, n) -> out.put(rs.getString("key"), rs.getInt("n")))
                .list();
        return out;
    }

    /* ===== 매핑 ===== */

    private static final String MODE_COLUMNS = "mode, enabled_by, reason, approval_id, since, valid_until, activation, "
            + "activation_at, activation_limits::text AS activation_limits, stopped, stopped_by, stopped_at, stop_reason";

    private Mode mode(ResultSet rs) throws SQLException {
        long approval = rs.getLong("approval_id");
        Long approvalId = rs.wasNull() ? null : approval;
        String limits = rs.getString("activation_limits");
        return new Mode(rs.getString("mode"), rs.getString("enabled_by"), rs.getString("reason"), approvalId,
                instant(rs, "since"), instant(rs, "valid_until"), rs.getLong("activation"),
                instant(rs, "activation_at"), limits == null ? null : limits(limits), rs.getBoolean("stopped"),
                rs.getString("stopped_by"), instant(rs, "stopped_at"), rs.getString("stop_reason"));
    }

    private AiLimits limits(String json) {
        try {
            return AiLimits.fromIds(mapper.readValue(json, new TypeReference<LinkedHashMap<String, Integer>>() { }));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("활성 한도 해석 실패", e);
        }
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static String requiredText(String raw, int max, String field) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > max) {
            throw new ApiException(400, "invalid_parameter", field + " 은 1~" + max + "자여야 합니다.",
                    Map.of("constraint", "length", "field", field), null);
        }
        return text;
    }

    static String optionalText(String raw, int max, String field) {
        String text = raw == null ? "" : raw.strip();
        if (text.codePointCount(0, text.length()) > max) {
            throw new ApiException(400, "invalid_parameter", field + " 은 최대 " + max + "자입니다.",
                    Map.of("constraint", "length", "field", field), null);
        }
        return text;
    }

    static ApiException notConnected(String source) {
        return new ApiException(409, "msp_source_not_connected", "관제센터 원천이 연결되지 않았습니다: " + source,
                Map.of("source", source), null);
    }
}

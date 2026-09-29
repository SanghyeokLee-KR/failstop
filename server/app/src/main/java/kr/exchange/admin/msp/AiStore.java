package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 판단 기록(append-only)과 조치 요청 작업 표(DB 설계 §10.4). 무엇이 일어났는지와 무엇을 할 차례인지를 다른 표에
 * 둔다. 상태를 바꾸는 쪽(실행기, 판단 주기)이 감사와 사건 이벤트를 함께 남긴다.
 */
public class AiStore {

    static final int MAX_OUTPUT_BYTES = 16 * 1024;
    static final String OPEN = "('PROPOSED','DEFERRED')";

    public record Decision(long id, Instant decidedAt, String mode, String outcome, String error, String model,
                           String promptVersion, String evidence, String evidenceSha256, Instant oldestSampleAt,
                           String outputRaw, String outputSha256, boolean outputTruncated, String situation,
                           String reason, BigDecimal confidence, String actionKey, String dropped, Integer latencyMs,
                           Long incidentId) {
    }

    public record NewDecision(Instant decidedAt, String mode, String outcome, String error, String model,
                              String evidence, Instant oldestSampleAt, String output, String situation, String reason,
                              BigDecimal confidence, String actionKey, String dropped, Integer latencyMs,
                              Long incidentId) {
    }

    public record Action(long id, long firstDecisionId, long lastDecisionId, int consecutive, AiAction action,
                         String identityKey, BigDecimal confidence, String reason, String status, String holdRule,
                         String holdReason, Instant deferredUntil, String decidedBy, Instant decidedAt,
                         Long approvalId, String executedBy, Instant executedAt, Long activation, JsonNode result,
                         JsonNode baseline, Instant observeUntil, String observeOutcome, JsonNode revertInfo,
                         String revertedBy, Instant revertedAt, Instant createdAt, Instant expiresAt) {

        public boolean open() {
            return "PROPOSED".equals(status) || "DEFERRED".equals(status);
        }
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AiStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /* ===== 판단 기록 ===== */

    /** 출력 원문은 16 KiB 까지 담고 넘으면 자른다. 해시는 자르기 전 전체로 계산한다. */
    public long insertDecision(NewDecision d) {
        String raw = d.output();
        String kept = raw == null ? null : clipBytes(raw, MAX_OUTPUT_BYTES);
        return jdbc.sql("""
                        INSERT INTO ops_ai_decisions
                            (decided_at, mode, outcome, error, model, prompt_version, evidence, evidence_sha256,
                             oldest_sample_at, output_raw, output_sha256, output_truncated, situation, reason,
                             confidence, action_key, dropped, latency_ms, incident_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) RETURNING id
                        """)
                .params(AiControl.at(d.decidedAt()), d.mode(), d.outcome(), d.error(), clip(d.model(), 128),
                        LlmClient.PROMPT_VERSION, d.evidence(), sha256(d.evidence()), AiControl.at(d.oldestSampleAt()),
                        kept, raw == null ? null : sha256(raw), raw != null && !raw.equals(kept),
                        clip(d.situation(), 1000), clip(d.reason(), 1000), d.confidence(), d.actionKey(),
                        d.dropped(), d.latencyMs(), d.incidentId())
                .query(Long.class).single();
    }

    public Optional<Decision> decision(long id) {
        return jdbc.sql("SELECT " + DECISION_COLUMNS + " FROM ops_ai_decisions WHERE id = ?")
                .param(id)
                .query((rs, n) -> decision(rs))
                .optional();
    }

    /** 판단 이력(판단 시각 역순). 근거 원문은 싣지 않는다. 호출부는 limit + 1 을 넘긴다. */
    public List<CursorPages.Row<Decision>> decisions(Cursors.Cursor cursor, int fetch) {
        StringBuilder sql = new StringBuilder("SELECT " + DECISION_LIST_COLUMNS + " FROM ops_ai_decisions WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (cursor != null) {
            long anchorId = CursorPages.numericId(cursor.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT decided_at FROM ops_ai_decisions WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (decided_at, id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        sql.append(" ORDER BY decided_at DESC, id DESC LIMIT ?");
        params.add(fetch);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    Decision d = decision(rs);
                    return new CursorPages.Row<>(d, rs.getObject("decided_at", OffsetDateTime.class),
                            String.valueOf(d.id()));
                })
                .list();
    }

    public Optional<Decision> lastDecision() {
        return jdbc.sql("SELECT " + DECISION_LIST_COLUMNS + " FROM ops_ai_decisions ORDER BY id DESC LIMIT 1")
                .query((rs, n) -> decision(rs))
                .optional();
    }

    /** 이 판단 바로 앞의 판단. 연속 판단(정책 A4)은 사이에 다른 판단이 없어야 한다. */
    public Optional<Decision> previousDecision(long beforeId) {
        return jdbc.sql("SELECT " + DECISION_LIST_COLUMNS + " FROM ops_ai_decisions WHERE id < ? ORDER BY id DESC LIMIT 1")
                .param(beforeId)
                .query((rs, n) -> decision(rs))
                .optional();
    }

    /* ===== 조치 요청 ===== */

    /**
     * 판단이 낸 조치를 열린 요청에 합친다. 같은 조치가 열려 있으면 새 행을 만들지 않고, 바로 앞 판단에서도 나왔고
     * 간격이 주기의 두 배 이내면 연속 횟수를 올린다. 실행 중인 같은 조치가 있으면 그대로 둔다. 키가 같아도 판단 때의
     * 현재값(임계값의 from)이 다르면 다른 판단이라 열린 요청을 철회하고 새로 만든다(정책 A4·C5).
     */
    public Action propose(long decisionId, Instant now, Duration ttl, Duration interval, AiAction a,
                          BigDecimal confidence, String reason) {
        Optional<Action> existing = jdbc.sql("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions "
                        + "WHERE identity_key = ? AND status IN ('PROPOSED','DEFERRED','EXECUTING') FOR UPDATE")
                .param(a.identityKey())
                .query((rs, n) -> action(rs))
                .optional();
        if (existing.isPresent() && "EXECUTING".equals(existing.get().status())) {
            return existing.get();
        }
        if (existing.isPresent() && keepOrWithdraw(existing.get(), a)) {
            Action e = existing.get();
            Optional<Decision> prev = previousDecision(decisionId);
            boolean chained = prev.isPresent() && prev.get().id() == e.lastDecisionId()
                    && Duration.between(prev.get().decidedAt(), now).compareTo(interval.multipliedBy(2)) <= 0;
            int consecutive = chained ? e.consecutive() + 1 : 1;
            jdbc.sql("""
                            UPDATE ops_ai_actions SET last_decision_id = ?, consecutive = ?, confidence = ?, reason = ?,
                                   hold_rule = NULL, hold_reason = NULL
                            WHERE id = ?
                            """)
                    .params(decisionId, consecutive, confidence, clip(reason, 500), e.id())
                    .update();
            return action(e.id()).orElseThrow();
        }
        long id = jdbc.sql("""
                        INSERT INTO ops_ai_actions
                            (first_decision_id, last_decision_id, kind, identity_key, params, target, confidence,
                             reason, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(decisionId, decisionId, a.kind().name(), a.identityKey(), json(a.params()), a.target(),
                        confidence, clip(reason, 500), AiControl.at(now), AiControl.at(now.plus(ttl)))
                .query(Long.class).single();
        return action(id).orElseThrow();
    }

    /**
     * 정책이 제안 때 막은 조치. 같은 조치가 열려 있으면 그 요청을 DENIED 로 닫고, 없으면 DENIED 행을 새로 만든다.
     * 판단 때의 현재값이 다른 열린 요청은 철회하고 새 행에 막은 판단을 남긴다.
     */
    public Action deny(long decisionId, Instant now, AiAction a, BigDecimal confidence, String reason,
                       AiPolicy.Denial d) {
        Optional<Action> existing = jdbc.sql("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions "
                        + "WHERE identity_key = ? AND status IN " + OPEN + " FOR UPDATE")
                .param(a.identityKey())
                .query((rs, n) -> action(rs))
                .optional();
        if (existing.isPresent() && keepOrWithdraw(existing.get(), a)) {
            jdbc.sql("""
                            UPDATE ops_ai_actions SET status = 'DENIED', last_decision_id = ?, hold_rule = ?,
                                   hold_reason = ?, confidence = ?, reason = ?
                            WHERE id = ?
                            """)
                    .params(decisionId, d.rule(), clip(d.message(), 200), confidence, clip(reason, 500),
                            existing.get().id())
                    .update();
            return action(existing.get().id()).orElseThrow();
        }
        long id = jdbc.sql("""
                        INSERT INTO ops_ai_actions
                            (first_decision_id, last_decision_id, kind, identity_key, params, target, confidence,
                             reason, status, hold_rule, hold_reason, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'DENIED', ?, ?, ?, ?) RETURNING id
                        """)
                .params(decisionId, decisionId, a.kind().name(), a.identityKey(), json(a.params()), a.target(),
                        confidence, clip(reason, 500), d.rule(), clip(d.message(), 200), AiControl.at(now),
                        AiControl.at(now))
                .query(Long.class).single();
        return action(id).orElseThrow();
    }

    /** 열린 요청이 새 판단과 매개변수가 같으면 참이다. 다르면(임계값의 판단 때 현재값) 그 요청을 철회하고 거짓이다. */
    private boolean keepOrWithdraw(Action open, AiAction a) {
        if (open.action().params().equals(a.params())) {
            return true;
        }
        jdbc.sql("UPDATE ops_ai_actions SET status = 'WITHDRAWN' WHERE id = ?").param(open.id()).update();
        return false;
    }

    /** 이번 판단에 빠진 열린 요청은 철회된다. keepKey 가 null 이면 열린 요청 모두다. */
    public int withdrawExcept(String keepKey) {
        if (keepKey == null) {
            return jdbc.sql("UPDATE ops_ai_actions SET status = 'WITHDRAWN' WHERE status IN " + OPEN).update();
        }
        return jdbc.sql("UPDATE ops_ai_actions SET status = 'WITHDRAWN' WHERE status IN " + OPEN
                        + " AND identity_key <> ?")
                .param(keepKey)
                .update();
    }

    /** 만료 시각이 지난 열린 요청(정책 C3). 나중에로 둔 요청도 같다. */
    public int expireStale(Instant now) {
        return jdbc.sql("UPDATE ops_ai_actions SET status = 'EXPIRED', hold_rule = 'C3', "
                        + "hold_reason = '만료되었습니다.' WHERE status IN " + OPEN + " AND expires_at <= ?")
                .param(AiControl.at(now))
                .update();
    }

    public Optional<Action> action(long id) {
        return jdbc.sql("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions WHERE id = ?")
                .param(id)
                .query((rs, n) -> action(rs))
                .optional();
    }

    public Optional<Action> lockAction(long id) {
        return jdbc.sql("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions WHERE id = ? FOR UPDATE")
                .param(id)
                .query((rs, n) -> action(rs))
                .optional();
    }

    /** 판단이 낸 요청 행. 판단의 action_key 와 판단 id 범위로 잇는다. */
    public List<Action> decisionActions(Decision d) {
        if (d.actionKey() == null) {
            return List.of();
        }
        return jdbc.sql("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions "
                        + "WHERE identity_key = ? AND first_decision_id <= ? AND last_decision_id >= ? ORDER BY id")
                .params(d.actionKey(), d.id(), d.id())
                .query((rs, n) -> action(rs))
                .list();
    }

    public List<CursorPages.Row<Action>> actions(boolean openOnly, Cursors.Cursor cursor, int fetch) {
        StringBuilder sql = new StringBuilder("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (openOnly) {
            sql.append(" AND status IN ").append(OPEN);
        }
        if (cursor != null) {
            long anchorId = CursorPages.numericId(cursor.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT created_at FROM ops_ai_actions WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (created_at, id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(fetch);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    Action a = action(rs);
                    return new CursorPages.Row<>(a, rs.getObject("created_at", OffsetDateTime.class),
                            String.valueOf(a.id()));
                })
                .list();
    }

    public List<Action> recent(int limit) {
        return jdbc.sql("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions WHERE executed_at IS NOT NULL "
                        + "ORDER BY executed_at DESC, id DESC LIMIT ?")
                .param(limit)
                .query((rs, n) -> action(rs))
                .list();
    }

    public int openCount() {
        return jdbc.sql("SELECT count(*) FROM ops_ai_actions WHERE status IN " + OPEN).query(Integer.class).single();
    }

    /** 사람의 결정이나 자동 실행을 기다리는 요청(`PROPOSED`)이 있는가. 판단 주기가 분석을 미룰지 가를 때 본다. */
    public boolean proposedExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ops_ai_actions WHERE status = 'PROPOSED')")
                .query(Boolean.class).single();
    }

    public boolean executingExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ops_ai_actions WHERE status = 'EXECUTING')")
                .query(Boolean.class).single();
    }

    /** 실행한 조치의 관찰 창 가운데 가장 늦은 끝(정책 A6). */
    public Instant latestObserveUntil() {
        return jdbc.sql("SELECT max(observe_until) AS max FROM ops_ai_actions")
                .query((rs, n) -> AiControl.instant(rs, "max"))
                .optional()
                .orElse(null);
    }

    /** 자동 실행했고 관찰 결과가 아직 없는 조치. 관찰 주기가 악화를 판정한다(설계 7-8). */
    public List<Action> observing() {
        return jdbc.sql("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions "
                        + "WHERE status = 'AUTO_EXECUTED' AND activation IS NOT NULL AND observe_outcome IS NULL "
                        + "AND observe_until IS NOT NULL ORDER BY id")
                .query((rs, n) -> action(rs))
                .list();
    }

    /** 중간에 끊긴 실행(정책 M7). before 보다 오래 EXECUTING 인 요청과 UNKNOWN 요청이다. */
    public List<Action> stuck(Instant before) {
        return jdbc.sql("SELECT " + ACTION_COLUMNS + " FROM ops_ai_actions "
                        + "WHERE (status = 'EXECUTING' AND executed_at < ?) OR status = 'UNKNOWN' ORDER BY id")
                .param(AiControl.at(before))
                .query((rs, n) -> action(rs))
                .list();
    }

    public Instant deferredUntil(String identityKey) {
        return jdbc.sql("SELECT max(deferred_until) AS max FROM ops_ai_actions WHERE identity_key = ?")
                .param(identityKey)
                .query((rs, n) -> AiControl.instant(rs, "max"))
                .optional()
                .orElse(null);
    }

    public Instant rejectedAt(String identityKey) {
        return jdbc.sql("SELECT max(decided_at) AS max FROM ops_ai_actions WHERE identity_key = ? AND status = 'REJECTED'")
                .param(identityKey)
                .query((rs, n) -> AiControl.instant(rs, "max"))
                .optional()
                .orElse(null);
    }

    /**
     * 같은 종류 조치의 마지막 시각(정책 A8). 사람과 AI 가 같은 쓰기 경로를 지나며 같은 감사 코드를 남기므로 감사
     * 기록 하나로 센다. 봇 발행률은 시나리오 활성화도 바꾸므로 함께 센다. 거부된 조작은 세지 않는다.
     */
    public Instant lastSameKind(AiAction.Kind kind) {
        String actions = switch (kind) {
            case READ_NODES -> "'capacity.scale_out','capacity.scale_in'";
            case THRESHOLD -> "'ops.threshold.set','ops.threshold.revert'";
            case BOT_LOAD -> "'sim.rate','sim.scenario'";
            case SILENCE, BACKUP_NOW -> null;
        };
        if (actions == null) {
            return null;
        }
        return jdbc.sql("SELECT max(created_at) AS max FROM admin_audit_log WHERE action IN (" + actions
                        + ") AND result = 'OK'")
                .query((rs, n) -> AiControl.instant(rs, "max"))
                .optional()
                .orElse(null);
    }

    /** 그 지문의 마지막 보류·확인이 AI 보류인가(정책 S3). 뒤에 사람의 확인이나 보류가 있으면 거짓이다. */
    public boolean lastSilenceByAi(String fingerprint) {
        return jdbc.sql("""
                        SELECT e.actor_type FROM ops_incident_events e JOIN ops_incidents i ON i.id = e.incident_id
                        WHERE i.fingerprint = ? AND e.kind IN ('SILENCED','ACKED')
                        ORDER BY e.at DESC, e.id DESC LIMIT 1
                        """)
                .param(fingerprint)
                .query(String.class)
                .optional()
                .map("AI"::equals)
                .orElse(false);
    }

    /* ===== 상태 전이 ===== */

    /** 자동 실행을 막은 규칙을 적는다. 요청은 열린 채로 남아 사람이 볼 수 있다. */
    public void hold(long id, AiPolicy.Denial d) {
        jdbc.sql("UPDATE ops_ai_actions SET hold_rule = ?, hold_reason = ? WHERE id = ?")
                .params(d.rule(), clip(d.message(), 200), id)
                .update();
    }

    public void decide(long id, String status, String by, Instant at, Instant deferredUntil) {
        jdbc.sql("UPDATE ops_ai_actions SET status = ?, decided_by = ?, decided_at = ?, deferred_until = ? WHERE id = ?")
                .params(status, by, AiControl.at(at), AiControl.at(deferredUntil), id)
                .update();
    }

    /**
     * 실행 시작. 표 전체에서 EXECUTING 은 하나뿐이다(부분 유일 인덱스, 정책 C4). pending 은 끊긴 실행을 대조할 때 쓰는
     * 실행 전의 의도다.
     */
    public void executing(long id, String executedBy, Instant at, Long activation, String decidedBy, Long approvalId,
                          Object pending) {
        jdbc.sql("""
                        UPDATE ops_ai_actions SET status = 'EXECUTING', executed_by = ?, executed_at = ?, activation = ?,
                               decided_by = coalesce(?, decided_by), decided_at = coalesce(?, decided_at),
                               approval_id = coalesce(?, approval_id), hold_rule = NULL, hold_reason = NULL,
                               result = ?::jsonb
                        WHERE id = ?
                        """)
                .params(executedBy, AiControl.at(at), activation, decidedBy,
                        decidedBy == null ? null : AiControl.at(at), approvalId, json(pending), id)
                .update();
    }

    public void executed(long id, String status, Object result, Object baseline, Instant observeUntil) {
        jdbc.sql("""
                        UPDATE ops_ai_actions SET status = ?, result = ?::jsonb, baseline = ?::jsonb, observe_until = ?
                        WHERE id = ?
                        """)
                .params(status, json(result), json(baseline), AiControl.at(observeUntil), id)
                .update();
    }

    public void failed(long id, String status, Object result) {
        jdbc.sql("UPDATE ops_ai_actions SET status = ?, result = ?::jsonb WHERE id = ?")
                .params(status, json(result), id)
                .update();
    }

    /**
     * 관찰 결과를 한 번만 적는다. 관찰 주기가 요청을 읽은 뒤 사람이 되돌렸으면(REVERTED) 적지 않고 거짓을 돌려준다.
     * 자동 되돌림은 상태를 AUTO_REVERTED 로 바꾼 뒤 결과를 적는다.
     */
    public boolean observed(long id, String outcome) {
        return jdbc.sql("UPDATE ops_ai_actions SET observe_outcome = ? WHERE id = ? AND observe_outcome IS NULL "
                        + "AND status IN ('AUTO_EXECUTED','AUTO_REVERTED')")
                .params(outcome, id)
                .update() == 1;
    }

    public void reverted(long id, String status, String by, Instant at, Object revertInfo) {
        jdbc.sql("""
                        UPDATE ops_ai_actions SET status = ?, reverted_by = ?, reverted_at = ?, revert_info = ?::jsonb
                        WHERE id = ?
                        """)
                .params(status, by, AiControl.at(at), json(revertInfo), id)
                .update();
    }

    /* ===== 매핑 ===== */

    private static final String DECISION_LIST_COLUMNS = "id, decided_at, mode, outcome, error, model, prompt_version, "
            + "NULL::text AS evidence, evidence_sha256, oldest_sample_at, NULL::text AS output_raw, output_sha256, "
            + "output_truncated, situation, reason, confidence, action_key, dropped::text AS dropped, latency_ms, "
            + "incident_id";

    private static final String DECISION_COLUMNS = "id, decided_at, mode, outcome, error, model, prompt_version, "
            + "evidence::text AS evidence, evidence_sha256, oldest_sample_at, output_raw, output_sha256, "
            + "output_truncated, situation, reason, confidence, action_key, dropped::text AS dropped, latency_ms, "
            + "incident_id";

    private static final String ACTION_COLUMNS = "id, first_decision_id, last_decision_id, consecutive, kind, "
            + "identity_key, params::text AS params, target, confidence, reason, status, hold_rule, hold_reason, "
            + "deferred_until, decided_by, decided_at, approval_id, executed_by, executed_at, activation, "
            + "result::text AS result, baseline::text AS baseline, observe_until, observe_outcome, "
            + "revert_info::text AS revert_info, reverted_by, reverted_at, created_at, expires_at";

    private static Decision decision(ResultSet rs) throws SQLException {
        return new Decision(rs.getLong("id"), AiControl.instant(rs, "decided_at"), rs.getString("mode"),
                rs.getString("outcome"), rs.getString("error"), rs.getString("model"), rs.getString("prompt_version"),
                rs.getString("evidence"), rs.getString("evidence_sha256"), AiControl.instant(rs, "oldest_sample_at"),
                rs.getString("output_raw"), rs.getString("output_sha256"), rs.getBoolean("output_truncated"),
                rs.getString("situation"), rs.getString("reason"), rs.getBigDecimal("confidence"),
                rs.getString("action_key"), rs.getString("dropped"), nullableInt(rs, "latency_ms"),
                nullableLong(rs, "incident_id"));
    }

    private Action action(ResultSet rs) throws SQLException {
        JsonNode params = tree(rs.getString("params"));
        return new Action(rs.getLong("id"), rs.getLong("first_decision_id"), rs.getLong("last_decision_id"),
                rs.getInt("consecutive"), AiAction.fromRow(rs.getString("kind"), params), rs.getString("identity_key"),
                rs.getBigDecimal("confidence"), rs.getString("reason"), rs.getString("status"),
                rs.getString("hold_rule"), rs.getString("hold_reason"), AiControl.instant(rs, "deferred_until"),
                rs.getString("decided_by"), AiControl.instant(rs, "decided_at"), nullableLong(rs, "approval_id"),
                rs.getString("executed_by"), AiControl.instant(rs, "executed_at"), nullableLong(rs, "activation"),
                tree(rs.getString("result")), tree(rs.getString("baseline")), AiControl.instant(rs, "observe_until"),
                rs.getString("observe_outcome"), tree(rs.getString("revert_info")), rs.getString("reverted_by"),
                AiControl.instant(rs, "reverted_at"), AiControl.instant(rs, "created_at"),
                AiControl.instant(rs, "expires_at"));
    }

    private JsonNode tree(String text) {
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("조치 요청 JSON 해석 실패", e);
        }
    }

    String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** UTF-8 바이트 상한 안에서 글자 경계로 자른다. */
    static String clipBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = 0;
        int used = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > maxBytes) {
                break;
            }
            used += size;
            end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }

    static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.codePointCount(0, value.length()) <= max ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }
}

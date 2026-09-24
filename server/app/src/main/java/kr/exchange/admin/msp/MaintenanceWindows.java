package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.api.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 계획 작업 창(설계 7-16, DB 설계 §10.4). AI 의 경보 보류는 운영자가 등록한 창과 겹치는 warning 경보에만
 * 허용한다(정책 S6). 로컬 실측에서 모델이 고칠 수 없는 문제를 경보 보류로 덮으려 했기 때문이다(설계 7-13).
 */
public class MaintenanceWindows {

    // 창 하나의 최대 길이(제안값). DB CHECK 와 같다.
    static final Duration MAX_SPAN = Duration.ofHours(24);
    // 라벨 일치에 쓰는 키. instance(사설 주소와 포트)처럼 응답에 내지 않는 라벨로는 창을 걸지 않는다.
    static final Set<String> LABEL_KEYS = Set.of("node", "role", "component", "mountpoint");
    private static final Pattern ALERTNAME = Pattern.compile("^[A-Za-z_:][A-Za-z0-9_:]{0,127}$");
    private static final int MAX_LABEL_VALUE = 128;
    private static final int MAX_REASON = 200;
    private static final int LIST_LIMIT = 100;

    public record Window(long id, Instant startsAt, Instant endsAt, String alertname, Map<String, String> labels,
                         String reason, String createdBy, Instant createdAt, String endedBy, Instant endedAt) {

        public boolean activeAt(Instant now) {
            return endedAt == null && !now.isBefore(startsAt) && now.isBefore(endsAt);
        }

        /** 경보명과 라벨이 모두 맞아야 한다. 비어 있는 조건은 보지 않는다. */
        public boolean matches(String name, Map<String, String> alertLabels) {
            if (alertname != null && !alertname.equals(name)) {
                return false;
            }
            for (Map.Entry<String, String> e : labels.entrySet()) {
                if (!e.getValue().equals(alertLabels.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    public record Ended(Window window, boolean changed) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AdminAuditService audit;
    private final IncidentStore events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MaintenanceWindows(JdbcClient jdbc, TransactionTemplate tx, AdminAuditService audit, IncidentStore events,
                              ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Window create(String operatorId, Instant startsAt, Instant endsAt, String alertname,
                         Map<String, String> labels, String reason, String requestId) {
        Instant now = clock.instant();
        Instant start = startsAt == null ? now : startsAt;
        if (endsAt == null || !endsAt.isAfter(start) || !endsAt.isAfter(now)
                || Duration.between(start, endsAt).compareTo(MAX_SPAN) > 0) {
            throw invalid("ends_at 은 지금과 시작보다 뒤이면서 시작 뒤 24시간 이내여야 합니다.", "range", "ends_at");
        }
        String name = alertname == null || alertname.isBlank() ? null : alertname.strip();
        if (name != null && !ALERTNAME.matcher(name).matches()) {
            throw invalid("alertname 형식 오류입니다.", "format", "alertname");
        }
        Map<String, String> match = new LinkedHashMap<>();
        if (labels != null) {
            for (Map.Entry<String, String> e : labels.entrySet()) {
                String value = e.getValue() == null ? "" : e.getValue().strip();
                if (!LABEL_KEYS.contains(e.getKey()) || value.isEmpty() || value.length() > MAX_LABEL_VALUE) {
                    throw invalid("labels 는 node, role, component, mountpoint 키와 비지 않은 값만 받습니다.",
                            "format", "labels");
                }
                match.put(e.getKey(), value);
            }
        }
        if (name == null && match.isEmpty()) {
            throw invalid("alertname 이나 labels 가운데 하나는 있어야 합니다.", "required", "alertname");
        }
        String why = AiControl.requiredText(reason, MAX_REASON, "reason");
        return tx.execute(status -> {
            long id = jdbc.sql("""
                            INSERT INTO ops_maintenance_windows (starts_at, ends_at, alertname, labels, reason, created_by)
                            VALUES (?, ?, ?, ?::jsonb, ?, ?) RETURNING id
                            """)
                    .params(AiControl.at(start), AiControl.at(endsAt), name, json(match), why, operatorId)
                    .query(Long.class).single();
            events.event(null, "WINDOW", "OPERATOR", operatorId, "계획 작업 창 등록 #" + id + ": " + why,
                    Map.of("window_id", id));
            audit.record(operatorId, "msp.window.create", "window:" + id, requestId, null, "OK",
                    Map.of("window_id", id, "alertname", name == null ? "" : name, "labels", match));
            return window(id).orElseThrow();
        });
    }

    /** 끝나지 않은 창을 일찍 끝낸다. 이미 끝났으면 바뀐 것 없이 돌려준다. */
    public Ended end(long id, String operatorId, String requestId) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Window w = jdbc.sql("SELECT " + COLUMNS + " FROM ops_maintenance_windows WHERE id = ? FOR UPDATE")
                    .param(id)
                    .query((rs, n) -> window(rs))
                    .optional()
                    .orElseThrow(() -> new ApiException(404, "not_found", "존재하지 않는 계획 작업 창입니다.",
                            Map.of("reason", "unknown_window"), null));
            if (w.endedAt() != null || !now.isBefore(w.endsAt())) {
                return new Ended(w, false);
            }
            jdbc.sql("UPDATE ops_maintenance_windows SET ended_by = ?, ended_at = ? WHERE id = ?")
                    .params(operatorId, AiControl.at(now), id)
                    .update();
            events.event(null, "WINDOW", "OPERATOR", operatorId, "계획 작업 창 조기 종료 #" + id,
                    Map.of("window_id", id));
            audit.record(operatorId, "msp.window.end", "window:" + id, requestId, null, "OK", Map.of("window_id", id));
            return new Ended(window(id).orElseThrow(), true);
        });
    }

    /** 지금 유효한 창. */
    public List<Window> active() {
        Instant now = clock.instant();
        return jdbc.sql("SELECT " + COLUMNS + " FROM ops_maintenance_windows "
                        + "WHERE ended_at IS NULL AND starts_at <= ? AND ends_at > ? ORDER BY ends_at DESC, id DESC")
                .params(AiControl.at(now), AiControl.at(now))
                .query((rs, n) -> window(rs))
                .list();
    }

    public List<Window> recent() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ops_maintenance_windows ORDER BY ends_at DESC, id DESC LIMIT ?")
                .param(LIST_LIMIT)
                .query((rs, n) -> window(rs))
                .list();
    }

    public Optional<Window> window(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ops_maintenance_windows WHERE id = ?")
                .param(id)
                .query((rs, n) -> window(rs))
                .optional();
    }

    private static final String COLUMNS = "id, starts_at, ends_at, alertname, labels::text AS labels, reason, "
            + "created_by, created_at, ended_by, ended_at";

    private Window window(ResultSet rs) throws SQLException {
        Map<String, String> labels;
        try {
            labels = mapper.readValue(rs.getString("labels"), new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("계획 작업 창 라벨 해석 실패", e);
        }
        return new Window(rs.getLong("id"), AiControl.instant(rs, "starts_at"), AiControl.instant(rs, "ends_at"),
                rs.getString("alertname"), labels, rs.getString("reason"), rs.getString("created_by"),
                AiControl.instant(rs, "created_at"), rs.getString("ended_by"), AiControl.instant(rs, "ended_at"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    private static ApiException invalid(String message, String constraint, String field) {
        return new ApiException(400, "invalid_parameter", message, Map.of("constraint", constraint, "field", field), null);
    }
}

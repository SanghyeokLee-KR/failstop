package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 사건 작업 표와 타임라인(DB 설계 §10.3). 사건의 신원은 (지문, 시작 시각)이고 열린 사건은 지문당 하나다.
 * 쓰기는 경보 한 건마다 짧은 트랜잭션이다. 한 경보의 쓰기 실패가 다른 경보의 열기·해제를 막지 않는다.
 */
public class IncidentStore {

    public static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{16}$");
    static final String POLLER = "alertmanager-poller";
    private static final int MAX_SUMMARY = 500;
    private static final int MAX_TEXT = 500;

    public record Incident(long id, String fingerprint, String alertname, String severity,
                           Map<String, String> labels, String summary, Instant startedAt, Instant firstSeenAt,
                           Instant lastSeenAt, Instant resolvedAt, String status, String ackedBy, Instant ackedAt,
                           String silenceId, Instant silencedUntil, String silencedBy, String silenceActor) {
    }

    public record Event(long id, Long incidentId, String kind, String actorType, String actorId, String text,
                        Map<String, Object> ref, Instant at) {
    }

    public record Episode(long id, String fingerprint, Instant startedAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public IncidentStore(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
    }

    /**
     * 폴링에서 본 경보 한 건을 사건 표에 맞춘다. 같은 지문의 열린 사건이 다른 시작 시각이면 그 발생은 이미
     * 끝났으므로 먼저 닫는다. Alertmanager 는 한 지문에 경보를 하나만 두기 때문이다. 닫힌 같은 (지문, 시작
     * 시각)이 다시 오면 새 행이 아니라 그 사건을 다시 연다. 돌려주는 값은 열린 사건 id 다.
     */
    public long recordFiring(AlertmanagerClient.Alert alert, String severity, Instant now) {
        Instant startsAt = alert.startsAt();
        return tx.execute(status -> {
            Optional<Episode> open = jdbc.sql("""
                            SELECT id, fingerprint, started_at FROM ops_incidents
                            WHERE fingerprint = ? AND status = 'OPEN' FOR UPDATE
                            """)
                    .param(alert.fingerprint())
                    .query((rs, n) -> episode(rs))
                    .optional();
            if (open.isPresent()) {
                Episode current = open.get();
                if (current.startedAt().equals(startsAt)) {
                    jdbc.sql("UPDATE ops_incidents SET last_seen_at = ?, summary = ? WHERE id = ?")
                            .params(at(now), summary(alert), current.id())
                            .update();
                    return current.id();
                }
                // 새 시작 시각이 이전보다 이르면 시계가 어긋난 것이다. 그때는 폴링 시각을 해제 시각으로 쓴다.
                Instant resolvedAt = startsAt.isBefore(current.startedAt()) ? now : startsAt;
                close(current.id(), resolvedAt, "새 발생으로 닫음");
            }
            Optional<Long> closed = jdbc.sql("""
                            SELECT id FROM ops_incidents WHERE fingerprint = ? AND started_at = ? FOR UPDATE
                            """)
                    .params(alert.fingerprint(), at(startsAt))
                    .query(Long.class)
                    .optional();
            if (closed.isPresent()) {
                jdbc.sql("""
                                UPDATE ops_incidents SET status = 'OPEN', resolved_at = NULL, last_seen_at = ?
                                WHERE id = ?
                                """)
                        .params(at(now), closed.get())
                        .update();
                event(closed.get(), "OPENED", "SYSTEM", POLLER, "다시 열림", Map.of());
                return closed.get();
            }
            long id = jdbc.sql("""
                            INSERT INTO ops_incidents
                                (fingerprint, alertname, severity, labels, summary, started_at, first_seen_at, last_seen_at)
                            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?) RETURNING id
                            """)
                    .params(alert.fingerprint(), clip(alert.labels().getOrDefault("alertname", ""), 128), severity,
                            json(alert.labels()), summary(alert), at(startsAt), at(now), at(now))
                    .query(Long.class).single();
            event(id, "OPENED", "SYSTEM", POLLER, "발생", Map.of());
            return id;
        });
    }

    /** 해제. 해제 시각은 처음 보이지 않은 폴링 시각이다. */
    public void resolve(long id, Instant resolvedAt) {
        tx.executeWithoutResult(status -> close(id, resolvedAt, "경보 해제"));
    }

    private void close(long id, Instant resolvedAt, String text) {
        int changed = jdbc.sql("UPDATE ops_incidents SET status = 'RESOLVED', resolved_at = ? WHERE id = ? AND status = 'OPEN'")
                .params(at(resolvedAt), id)
                .update();
        if (changed == 1) {
            event(id, "RESOLVED", "SYSTEM", POLLER, text, Map.of());
        }
    }

    public List<Episode> openEpisodes() {
        return jdbc.sql("SELECT id, fingerprint, started_at FROM ops_incidents WHERE status = 'OPEN'")
                .query((rs, n) -> episode(rs))
                .list();
    }

    /** 경보 목록에 붙일 열린 사건. 지문당 하나다. */
    public Map<String, Incident> openByFingerprint(Collection<String> fingerprints) {
        Map<String, Incident> out = new LinkedHashMap<>();
        if (fingerprints.isEmpty()) {
            return out;
        }
        String marks = String.join(",", java.util.Collections.nCopies(fingerprints.size(), "?"));
        jdbc.sql("SELECT " + COLUMNS + " FROM ops_incidents WHERE status = 'OPEN' AND fingerprint IN (" + marks + ")")
                .params(new ArrayList<>(fingerprints))
                .query((rs, n) -> incident(rs))
                .list()
                .forEach(i -> out.put(i.fingerprint(), i));
        return out;
    }

    public Optional<Incident> openIncident(String fingerprint) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ops_incidents WHERE fingerprint = ? AND status = 'OPEN'")
                .param(fingerprint)
                .query((rs, n) -> incident(rs))
                .optional();
    }

    public Optional<Incident> lockOpenIncident(String fingerprint) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ops_incidents WHERE fingerprint = ? AND status = 'OPEN' FOR UPDATE")
                .param(fingerprint)
                .query((rs, n) -> incident(rs))
                .optional();
    }

    public Optional<Incident> incident(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ops_incidents WHERE id = ?")
                .param(id)
                .query((rs, n) -> incident(rs))
                .optional();
    }

    /** 관제센터가 만든 사일런스의 사건. 가장 최근 것을 고른다. */
    public Optional<Incident> bySilence(String silenceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ops_incidents WHERE silence_id = ? ORDER BY id DESC LIMIT 1")
                .param(silenceId)
                .query((rs, n) -> incident(rs))
                .optional();
    }

    public void acknowledge(long id, String operatorId, Instant at) {
        jdbc.sql("UPDATE ops_incidents SET acked_by = ?, acked_at = ? WHERE id = ?")
                .params(operatorId, at(at), id)
                .update();
    }

    public void recordSilence(long id, String silenceId, Instant until, String by, String actor) {
        jdbc.sql("""
                        UPDATE ops_incidents SET silence_id = ?, silenced_until = ?, silenced_by = ?, silence_actor = ?
                        WHERE id = ?
                        """)
                .params(silenceId, at(until), by, actor, id)
                .update();
    }

    public void clearSilence(long id, String silenceId) {
        jdbc.sql("""
                        UPDATE ops_incidents SET silence_id = NULL, silenced_until = NULL, silenced_by = NULL,
                                                 silence_actor = NULL
                        WHERE id = ? AND silence_id = ?
                        """)
                .params(id, silenceId)
                .update();
    }

    public void event(Long incidentId, String kind, String actorType, String actorId, String text,
                      Map<String, Object> ref) {
        jdbc.sql("""
                        INSERT INTO ops_incident_events (incident_id, kind, actor_type, actor_id, text, ref)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb)
                        """)
                .params(incidentId, kind, actorType, clip(actorId, 64), clip(text, MAX_TEXT), json(ref))
                .update();
    }

    public List<Event> events(long incidentId) {
        return jdbc.sql("""
                        SELECT id, incident_id, kind, actor_type, actor_id, text, ref::text AS ref, at
                        FROM ops_incident_events WHERE incident_id = ? ORDER BY at, id
                        """)
                .param(incidentId)
                .query((rs, n) -> new Event(rs.getLong("id"), rs.getLong("incident_id"), rs.getString("kind"),
                        rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("text"),
                        object(rs.getString("ref")), instant(rs, "at")))
                .list();
    }

    /** 사건 목록(시작 시각 역순). 호출부는 limit + 1 을 넘긴다. */
    public List<CursorPages.Row<Incident>> list(String status, Cursors.Cursor cursor, int fetch) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM ops_incidents WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (cursor != null) {
            long anchorId = CursorPages.numericId(cursor.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT started_at FROM ops_incidents WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (started_at, id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT ?");
        params.add(fetch);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    Incident i = incident(rs);
                    return new CursorPages.Row<>(i, rs.getObject("started_at", OffsetDateTime.class),
                            String.valueOf(i.id()));
                })
                .list();
    }

    /** 오늘(dayStart 이후) 시작한 사건 수와 오늘 해제한 사건의 평균 지속 초. 해제가 없으면 평균은 null 이다. */
    public Map<String, Object> today(Instant dayStart) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE started_at >= ?) AS started,
                               round(avg(extract(epoch FROM resolved_at - started_at))
                                     FILTER (WHERE resolved_at >= ?)) AS mean_seconds
                        FROM ops_incidents
                        """)
                .params(at(dayStart), at(dayStart))
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("today_count", rs.getLong("started"));
                    java.math.BigDecimal mean = rs.getBigDecimal("mean_seconds");
                    m.put("today_resolved_mean_seconds", mean == null ? null : mean.toPlainString());
                    return m;
                })
                .single();
    }

    /**
     * 격자 시각마다 그 시각에 열려 있던 사건 수(요약 추세의 활성 경보, 설계 4-7). 관제센터가 멈춘 동안은 사건 표가
     * 마지막 상태로 남으므로 그 구간은 마지막으로 안 값이 이어진다.
     */
    public List<Long> openCounts(Instant first, Instant last, Duration step) {
        return jdbc.sql("""
                        SELECT count(i.id)
                        FROM generate_series(?::timestamptz, ?::timestamptz, make_interval(secs => ?)) AS t(at)
                        LEFT JOIN ops_incidents i
                               ON i.started_at <= t.at AND (i.resolved_at IS NULL OR i.resolved_at > t.at)
                        GROUP BY t.at ORDER BY t.at
                        """)
                .params(at(first), at(last), (double) step.toSeconds())
                .query(Long.class)
                .list();
    }

    /** (from, from + buckets × bucket] 를 칸으로 나눈 칸별 시작 사건 수(요약 추세의 사건). */
    public long[] startedPerBucket(Instant from, Duration bucket, int buckets) {
        long[] out = new long[buckets];
        jdbc.sql("""
                        SELECT ceil(extract(epoch FROM started_at - ?::timestamptz) / ?)::int - 1 AS k, count(*) AS n
                        FROM ops_incidents WHERE started_at > ? AND started_at <= ?
                        GROUP BY k
                        """)
                .params(at(from), (double) bucket.toSeconds(), at(from),
                        at(from.plus(bucket.multipliedBy(buckets))))
                .query((rs, n) -> {
                    out[rs.getInt("k")] = rs.getLong("n");
                    return null;
                })
                .list();
        return out;
    }

    /** (from, to] 에 시작한 사건 수. */
    public long startedBetween(Instant from, Instant to) {
        return jdbc.sql("SELECT count(*) FROM ops_incidents WHERE started_at > ? AND started_at <= ?")
                .params(at(from), at(to))
                .query(Long.class)
                .single();
    }

    public List<Long> openIds(int limit) {
        return jdbc.sql("SELECT id FROM ops_incidents WHERE status = 'OPEN' ORDER BY started_at DESC, id DESC LIMIT ?")
                .param(limit)
                .query(Long.class)
                .list();
    }

    /* ===== 매핑 ===== */

    private static final String COLUMNS = "id, fingerprint, alertname, severity, labels::text AS labels, summary, "
            + "started_at, first_seen_at, last_seen_at, resolved_at, status, acked_by, acked_at, silence_id, "
            + "silenced_until, silenced_by, silence_actor";

    private Incident incident(ResultSet rs) throws SQLException {
        return new Incident(rs.getLong("id"), rs.getString("fingerprint"), rs.getString("alertname"),
                rs.getString("severity"), labels(rs.getString("labels")), rs.getString("summary"),
                instant(rs, "started_at"), instant(rs, "first_seen_at"), instant(rs, "last_seen_at"),
                instant(rs, "resolved_at"), rs.getString("status"), rs.getString("acked_by"),
                instant(rs, "acked_at"), rs.getString("silence_id"), instant(rs, "silenced_until"),
                rs.getString("silenced_by"), rs.getString("silence_actor"));
    }

    private static Episode episode(ResultSet rs) throws SQLException {
        return new Episode(rs.getLong("id"), rs.getString("fingerprint"), instant(rs, "started_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** 경보 요약은 규칙 파일이 쓴 문구지만 라벨 값이 끼어들 수 있다. 제어 문자를 지우고 길이를 자른다. */
    static String summary(AlertmanagerClient.Alert alert) {
        String raw = alert.annotations().getOrDefault("summary", "");
        return clip(raw.replaceAll("\\p{Cntrl}", " ").strip(), MAX_SUMMARY);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.codePointCount(0, value.length()) <= max ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    private Map<String, String> labels(String text) {
        try {
            return mapper.readValue(text, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("사건 라벨 해석 실패", e);
        }
    }

    private Map<String, Object> object(String text) {
        try {
            return mapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("사건 이벤트 참조 해석 실패", e);
        }
    }
}

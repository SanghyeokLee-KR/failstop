package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.common.ApiFormats;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 보고 서비스 읽기 프록시(설계 7-12, API §6-F '보고서와 Teams 통지'). 관제센터의 보고서 화면과 설정의 Teams 연동 상태가
 * 원천이다. 30초 캐시로 읽고 허용한 필드만 싣는다. 보고 서비스 주소가 없으면 연결 전이다.
 */
public class MspReporting {

    static final Duration TTL = Duration.ofSeconds(30);
    // 목록은 한 벌만 캐시하고 요청의 limit 으로 자른다. 키가 limit 마다 늘지 않게 한다.
    static final int FETCH = 100;
    private static final int MAX_TEXT = 300;
    private static final List<String> JOB_STATES = List.of("pending", "processing", "queued", "sending", "sent", "dead");
    // 보고 서비스가 만든 서명 주소(deploy/reporting/console_api.py stored_links)의 모양만 받는다.
    private static final Pattern REPORT_PATH =
            Pattern.compile("^/reports/[0-9a-f]{64}/[0-9]{10}/[0-9a-f]{64}/report\\.(pdf|html)$");

    /** 원천 한 번의 결과. failure 가 있으면 body 는 없다. */
    private record Read(JsonNode body, String failure) {
    }

    private final ReportingClient client;
    private final SourceCache cache;
    private final JdbcClient jdbc;
    private final String artifactBase;

    /** client 는 보고 서비스 주소가 없으면 null 이다. */
    public MspReporting(ReportingClient client, SourceCache cache, JdbcClient jdbc, MspProperties.Reporting props) {
        this.client = client;
        this.cache = cache;
        this.jdbc = jdbc;
        String base = props.getArtifactBaseUrl();
        this.artifactBase = base == null || base.isBlank() ? null
                : MspProperties.sourceUri("reporting.artifact-base-url", base).toString();
    }

    /** GET /reports 의 몸통. */
    public Map<String, Object> reports(int limit) {
        if (client == null) {
            return MspSections.notConnected("reporting");
        }
        Read read = cache.get("reporting:list", TTL, () -> read(() -> client.reports(FETCH))).value();
        if (read.failure() != null) {
            return MspSections.unavailable(read.failure());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : read.body().path("reports")) {
            if (rows.size() >= limit) {
                break;
            }
            if (!r.isObject()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", text(r.path("id")));
            m.put("kind", text(r.path("kind")));
            m.put("created_at", epoch(r.path("created")));
            m.put("sent_at", epoch(r.path("sent")));
            m.put("status", text(r.path("status")));
            m.put("title", text(r.path("title")));
            m.put("severity", text(r.path("severity")));
            m.put("summary", text(r.path("summary")));
            m.put("alert_state", text(r.path("alertState")));
            List<String> targets = new ArrayList<>();
            for (JsonNode t : r.path("targets")) {
                if (t.isTextual() && targets.size() < 10) {
                    targets.add(AiStore.clip(t.asText(), MAX_TEXT));
                }
            }
            m.put("targets", targets);
            m.put("links", links(r.path("links")));
            rows.add(m);
        }
        return MspSections.ready(rows);
    }

    /** GET /reports/status 의 몸통. 보고 서비스를 읽지 못해도 관제센터 통지 보관함은 싣는다. */
    public Map<String, Object> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        if (client == null) {
            out.put("state", "NOT_CONNECTED");
            out.put("source", "reporting");
            data.put("reporting", null);
        } else {
            Read read = statusRead();
            if (read.failure() != null) {
                out.put("state", "UNAVAILABLE");
                out.put("reason", read.failure());
                data.put("reporting", null);
            } else {
                out.put("state", "READY");
                data.put("reporting", reportingStatus(read.body()));
            }
        }
        data.put("outbox", outbox());
        out.put("data", data);
        return out;
    }

    /** 한 판 sources 의 teams 원천. Teams 는 보고 서비스를 거친다. */
    public Map<String, Object> teamsSource() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "teams");
        m.put("via", "reporting");
        if (client == null) {
            m.put("state", "NOT_CONNECTED");
            return m;
        }
        Read read = statusRead();
        if (read.failure() != null) {
            m.put("state", "UNAVAILABLE");
            m.put("reason", read.failure());
            return m;
        }
        Map<String, Object> status = reportingStatus(read.body());
        m.put("state", "READY");
        m.put("delivery_enabled", status.get("delivery_enabled"));
        m.put("last_sent_at", status.get("last_sent_at"));
        long pending = 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) status.get("counts");
        for (String state : List.of("pending", "processing", "queued", "sending")) {
            pending += ((Number) counts.get(state)).longValue();
        }
        m.put("pending", pending);
        Map<String, Object> outbox = outbox();
        Map<String, Object> summary = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Long> byState = (Map<String, Long>) outbox.get("counts");
        summary.put("pending", byState.getOrDefault("PENDING", 0L) + byState.getOrDefault("SENDING", 0L));
        summary.put("failed", byState.getOrDefault("FAILED", 0L));
        m.put("outbox", summary);
        return m;
    }

    private Read statusRead() {
        return cache.get("reporting:status", TTL, () -> read(client::status)).value();
    }

    private static Read read(java.util.function.Supplier<JsonNode> call) {
        try {
            return new Read(call.get(), null);
        } catch (SourceException e) {
            return new Read(null, e.reason());
        }
    }

    private static Map<String, Object> reportingStatus(JsonNode s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery_enabled", s.path("delivery_enabled").asBoolean(false));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String state : JOB_STATES) {
            counts.put(state, Math.max(0, s.path("counts").path(state).asLong(0)));
        }
        m.put("counts", counts);
        m.put("last_sent_at", epoch(s.path("last_sent_at")));
        JsonNode attempt = s.path("last_attempt");
        if (attempt.isObject()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("at", epoch(attempt.path("at")));
            a.put("status", text(attempt.path("status")));
            a.put("error", text(attempt.path("error")));
            m.put("last_attempt", a);
        } else {
            m.put("last_attempt", null);
        }
        JsonNode age = s.path("oldest_pending_age_seconds");
        m.put("oldest_pending_age_seconds", age.isNumber() ? Math.max(0, Math.round(age.asDouble())) : null);
        m.put("last_daily_sent_at", epoch(s.path("last_daily_sent_at")));
        return m;
    }

    private Map<String, Object> outbox() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT state, count(*) AS n FROM ops_notifications GROUP BY state ORDER BY state")
                .query((rs, n) -> counts.put(rs.getString("state"), rs.getLong("n")))
                .list();
        OffsetDateTime last = jdbc.sql("SELECT max(accepted_at) FROM ops_notifications")
                .query(OffsetDateTime.class).optional().orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", counts);
        m.put("last_accepted_at", last == null ? null : ApiFormats.time(last));
        return m;
    }

    private Map<String, Object> links(JsonNode links) {
        if (artifactBase == null || !links.isObject()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (String kind : List.of("pdf", "html")) {
            JsonNode path = links.path(kind);
            m.put(kind, path.isTextual() && REPORT_PATH.matcher(path.asText()).matches()
                    ? artifactBase + path.asText() : null);
        }
        return m;
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? AiStore.clip(node.asText(), MAX_TEXT) : null;
    }

    /** 보고 서비스는 시각을 epoch 초(소수)로 준다. 없거나 0 이면 null 이다. */
    private static String epoch(JsonNode node) {
        if (!node.isNumber() || node.asDouble() <= 0) {
            return null;
        }
        return ApiFormats.time(Instant.ofEpochMilli(Math.round(node.asDouble() * 1000)));
    }
}

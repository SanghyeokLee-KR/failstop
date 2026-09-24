package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import kr.exchange.api.common.ApiFormats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teams 통지(설계 7-12). 관리 JVM 은 Teams 에 직접 보내지 않는다. 조치가 커밋된 뒤 서버가 만든 값만으로 통지 보관함에
 * 쓰고, 전용 작업이 거래 서버와 분리된 보고 서비스로 넘긴다. 보고 서비스가 일일·사건 보고와 같은 카드 언어, 보관함,
 * 재시도, 전송 기록으로 Teams 에 보낸다. 모델의 자유 문장(상황·이유)은 본문에 싣지 않는다.
 */
public class TeamsNotifier implements AiNotifier {

    private static final Logger log = LoggerFactory.getLogger(TeamsNotifier.class);

    static final int MAX_ATTEMPTS = 6;
    // 한 회차에 넘기는 행 수. 같은 스레드가 경보 폴링도 하므로 오래 붙잡지 않는다.
    static final int BATCH = 5;
    // 넘기다 JVM 이 멈춰 SENDING 으로 남은 행을 다시 가져가는 나이. 같은 사건 id 라 다시 보내도 카드는 한 장이다.
    static final Duration STALE_SENDING = Duration.ofMinutes(2);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY = Duration.ofMinutes(15);
    private static final int MAX_METRICS = 6;

    private record Claimed(long id, String payload, int attempts, Long incidentId) {
    }

    private final ReportingClient client;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AiStore store;
    private final IncidentStore incidents;
    private final MspProperties props;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final Clock clock;

    /** client 는 보고 서비스 주소가 없으면 null 이다. 그때 발행한 사건은 SKIPPED 로 남는다. */
    public TeamsNotifier(ReportingClient client, JdbcClient jdbc, TransactionTemplate tx, AiStore store,
                         IncidentStore incidents, MspProperties props, ObjectMapper mapper, MeterRegistry registry,
                         Clock clock) {
        this.client = client;
        this.jdbc = jdbc;
        this.tx = tx;
        this.store = store;
        this.incidents = incidents;
        this.props = props;
        this.mapper = mapper;
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * 사건 하나를 보관함에 쓴다. 조치가 이미 커밋된 뒤라 실패해도 호출부에 던지지 않는다. 호출 스레드는 짧은 조회와
     * 삽입 하나만 한다.
     */
    @Override
    public void publish(Notice notice) {
        try {
            Instant now = clock.instant();
            UUID eventId = UUID.randomUUID();
            Long actionId = number(notice.facts().get("action_id"));
            AiStore.Action row = actionId == null ? null : store.action(actionId).orElse(null);
            AiStore.Decision decision = row == null ? null : store.decision(row.lastDecisionId()).orElse(null);
            Long incidentId = row == null ? null
                    : store.decision(row.firstDecisionId()).map(AiStore.Decision::incidentId).orElse(null);
            String payload = json(payload(notice, eventId, now, row, decision));
            String state = client == null ? "SKIPPED" : "PENDING";
            jdbc.sql("""
                            INSERT INTO ops_notifications
                                (event_id, kind, payload, state, next_attempt_at, incident_id, created_at)
                            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)
                            """)
                    .params(eventId, notice.kind().name(), payload, state, at(now), incidentId, at(now))
                    .update();
            registry.counter("exchange.msp.notifications", "kind", notice.kind().name(), "state", state).increment();
        } catch (RuntimeException e) {
            registry.counter("exchange.msp.notifications", "kind", notice.kind().name(), "state", "ERROR").increment();
            log.warn("AI 통지 보관 실패: {} {}", notice.kind(), e.toString());
        }
    }

    /**
     * 보관함의 차례가 된 행을 보고 서비스로 넘긴다. 연결 실패가 나면 이번 회차를 멈춘다. 같은 스레드의 경보 폴링을
     * 오래 밀지 않기 위해서다. 전용 스레드의 고정 지연 작업이라 예외를 삼키고 다음 회차로 넘긴다.
     */
    public void drain() {
        if (client == null) {
            return;
        }
        try {
            drainBatch();
        } catch (RuntimeException e) {
            log.warn("AI 통지 넘김 실패: {}", e.toString());
        }
    }

    private void drainBatch() {
        for (int i = 0; i < BATCH; i++) {
            Claimed row = claim();
            if (row == null) {
                return;
            }
            ReportingClient.Posted posted;
            try {
                posted = client.postEvent(row.payload().getBytes(StandardCharsets.UTF_8));
            } catch (SourceException e) {
                retry(row, e.reason());
                return;
            }
            if (posted.status() == 202) {
                accepted(row, posted.reportId());
            } else if (retryable(posted.status())) {
                retry(row, "http_" + posted.status());
            } else {
                finish(row, "FAILED", "http_" + posted.status());
            }
        }
    }

    private Claimed claim() {
        Instant now = clock.instant();
        return tx.execute(status -> {
            Claimed row = jdbc.sql("""
                            SELECT id, payload::text AS payload, attempts, incident_id FROM ops_notifications
                            WHERE (state = 'PENDING' AND next_attempt_at <= ?) OR (state = 'SENDING' AND claimed_at < ?)
                            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                            """)
                    .params(at(now), at(now.minus(STALE_SENDING)))
                    .query((rs, n) -> new Claimed(rs.getLong("id"), rs.getString("payload"), rs.getInt("attempts"),
                            rs.getObject("incident_id") == null ? null : rs.getLong("incident_id")))
                    .optional()
                    .orElse(null);
            if (row == null) {
                return null;
            }
            jdbc.sql("UPDATE ops_notifications SET state = 'SENDING', attempts = attempts + 1, claimed_at = ? WHERE id = ?")
                    .params(at(now), row.id())
                    .update();
            return new Claimed(row.id(), row.payload(), row.attempts() + 1, row.incidentId());
        });
    }

    private void accepted(Claimed row, String reportId) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            UPDATE ops_notifications SET state = 'ACCEPTED', accepted_at = ?, report_id = ?, last_error = NULL
                            WHERE id = ?
                            """)
                    .params(at(now), AiStore.clip(reportId, 128), row.id())
                    .update();
            incidents.event(row.incidentId(), "NOTIFIED", "SYSTEM", AiControl.SYSTEM,
                    "Teams 통지를 보고 서비스가 접수함 #" + row.id(), Map.of("notification_id", row.id()));
        });
        registry.counter("exchange.msp.notifications.handoff", "result", "accepted").increment();
    }

    private void retry(Claimed row, String error) {
        if (row.attempts() >= MAX_ATTEMPTS) {
            finish(row, "FAILED", error);
            return;
        }
        Duration wait = FIRST_RETRY.multipliedBy(1L << Math.min(row.attempts() - 1, 20));
        Instant next = clock.instant().plus(wait.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : wait);
        jdbc.sql("UPDATE ops_notifications SET state = 'PENDING', next_attempt_at = ?, last_error = ? WHERE id = ?")
                .params(at(next), AiStore.clip(error, 64), row.id())
                .update();
        registry.counter("exchange.msp.notifications.handoff", "result", "retry").increment();
    }

    private void finish(Claimed row, String state, String error) {
        jdbc.sql("UPDATE ops_notifications SET state = ?, last_error = ? WHERE id = ?")
                .params(state, AiStore.clip(error, 64), row.id())
                .update();
        registry.counter("exchange.msp.notifications.handoff", "result", "failed").increment();
        log.warn("AI 통지 넘김 실패로 닫음: 보관함 #{} {}", row.id(), error);
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    /* ===== 본문 ===== */

    /** 보고 서비스로 넘기는 본문(deploy/reporting/msp_events.py 가 같은 모양만 받는다). 서버가 만든 값만 담는다. */
    private Map<String, Object> payload(Notice notice, UUID eventId, Instant now, AiStore.Action row,
                                        AiStore.Decision decision) {
        Map<String, Object> facts = notice.facts();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schema_version", 1);
        p.put("event_id", eventId.toString());
        p.put("kind", notice.kind().name());
        p.put("occurred_at", ApiFormats.time(now));
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("customer", props.getReporting().getCustomer());
        scope.put("environment", props.getReporting().getEnvironment());
        p.put("scope", scope);
        p.put("action", row == null ? null : action(row, facts));
        p.put("mode", mode(notice.kind(), facts));
        String model = decision != null ? decision.model()
                : notice.kind() == Kind.MODEL_FAILING ? props.getLlm().getModel() : null;
        p.put("model", model == null ? null : AiStore.clip(model, 128));
        p.put("metrics", row == null ? List.of() : metrics(row, decision));
        p.put("unconfirmed", decision == null ? 0 : unconfirmed(decision));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String key : List.of("expired_actions", "consecutive_failures")) {
            Long v = number(facts.get(key));
            if (v != null) {
                counts.put(key, v);
            }
        }
        p.put("counts", counts);
        return p;
    }

    private static Map<String, Object> action(AiStore.Action row, Map<String, Object> facts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.id());
        m.put("kind", row.action().kind().name());
        m.put("verb", row.action().verb().name());
        m.put("target", AiStore.clip(row.action().target(), 64));
        m.put("from", scalar(row.result() == null ? null : row.result().get("from")));
        m.put("to", scalar(row.result() == null ? null : row.result().get("to")));
        m.put("status", row.status());
        m.put("confidence", row.confidence() == null ? null : ApiFormats.amount(row.confidence()));
        m.put("observe_until", row.observeUntil() == null ? null : ApiFormats.time(row.observeUntil()));
        Object outcome = facts.get("outcome");
        m.put("observe_outcome", outcome instanceof String s ? s : row.observeOutcome());
        return m;
    }

    private static Map<String, Object> mode(Kind kind, Map<String, Object> facts) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (kind) {
            case MODE_CHANGED -> {
                m.put("mode", facts.get("mode"));
                m.put("cause", facts.getOrDefault("cause", facts.get("by") == null ? null : "operator"));
                m.put("actor", facts.get("by"));
                m.put("valid_until", facts.get("valid_until"));
            }
            case STOPPED, RESUMED -> {
                m.put("mode", "MANUAL");
                m.put("cause", kind == Kind.STOPPED ? "stopped" : "resumed");
                m.put("actor", facts.get("by"));
                m.put("valid_until", null);
            }
            case CAP_REACHED, AUTO_REVERTED, OBSERVE_FAILED -> {
                m.put("mode", "MANUAL");
                m.put("cause", switch (kind) {
                    case CAP_REACHED -> "auto_cap";
                    case AUTO_REVERTED -> "degraded";
                    default -> facts.get("outcome") instanceof String s ? s.toLowerCase() : "observe_failed";
                });
                m.put("actor", AiControl.SYSTEM);
                m.put("valid_until", null);
            }
            default -> {
                return null;
            }
        }
        return m;
    }

    /** 실행 때의 기준선 지표와 판정 임계. 조회 CPU 의 임계는 그 판단의 근거에 실린 조회 임계다. */
    private List<Map<String, Object>> metrics(AiStore.Action row, AiStore.Decision decision) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode base = row.baseline();
        if (base == null || !base.isObject()) {
            return out;
        }
        String readKey = row.action().verb() == AiAction.Verb.READ_NODE_REMOVE_ONE
                ? OpsThresholdService.SCALE_IN : OpsThresholdService.SCALE_OUT;
        MspProperties.Slo slo = props.getSlo();
        metric(out, "read_avg_cpu", base.get("read_avg_cpu"), MspSections.Unit.RATIO, threshold(decision, readKey));
        metric(out, "error_ratio", base.get("error_ratio"), MspSections.Unit.RATIO, plain(slo.getErrorRatio()));
        metric(out, "p95_seconds", base.get("p95_seconds"), MspSections.Unit.SECONDS, plain(slo.getP95Seconds()));
        metric(out, "availability", base.get("availability"), MspSections.Unit.RATIO,
                plain(slo.getAvailabilityRatio()));
        return out.size() > MAX_METRICS ? out.subList(0, MAX_METRICS) : out;
    }

    private static void metric(List<Map<String, Object>> out, String name, JsonNode value, MspSections.Unit unit,
                               String threshold) {
        if (value == null || !value.isNumber()) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", MspSections.number(value.asDouble(), unit));
        m.put("threshold", threshold);
        out.add(m);
    }

    private String threshold(AiStore.Decision decision, String key) {
        JsonNode evidence = evidence(decision);
        for (JsonNode t : evidence.path("thresholds")) {
            if (key.equals(t.path("key").asText()) && t.path("value").isTextual()) {
                return t.path("value").asText();
            }
        }
        return null;
    }

    /** 판단 근거 가운데 미수집이거나 60초보다 오래된 측정 항목 수. 카드의 '미확인 자료'다. */
    private int unconfirmed(AiStore.Decision decision) {
        int[] count = {0};
        walk(evidence(decision), count);
        return count[0];
    }

    private static void walk(JsonNode node, int[] count) {
        if (node.isObject()) {
            JsonNode collect = node.get("collect");
            if (collect != null && collect.isTextual() && !"OK".equals(collect.asText())) {
                count[0]++;
            }
            node.elements().forEachRemaining(child -> walk(child, count));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> walk(child, count));
        }
    }

    private JsonNode evidence(AiStore.Decision decision) {
        if (decision == null || decision.evidence() == null) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(decision.evidence());
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("통지 본문 직렬화 실패", e);
        }
    }

    private static String scalar(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        return AiStore.clip(node.asText(), 32);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : ApiFormats.amount(value);
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

package kr.exchange.admin.msp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.admin.AdminRequestContext;
import kr.exchange.admin.AdminRole;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.CursorPages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 관제센터 조회(API 설계 §6-F). 모두 VIEWER 이상이다. 원천 호출은 한 판과 세부 조회가 같은 캐시와 마지막
 * 폴링 결과를 쓰므로 운영자 수와 무관하게 유계다. 한 판은 ETag 를 붙이고 같으면 304 로 본문을 생략한다.
 */
@RestController
@RequestMapping("/admin/v1/msp")
@ConditionalOnProperty(name = {"exchange.admin.enabled", "exchange.msp.enabled"}, havingValue = "true")
public class MspController {

    private static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED");
    private static final int MAX_PAGE = 200;
    private static final int MAX_REPORTS = 100;
    // 요청 시각에 따라 바뀌는 필드. 이것만 달라진 한 판은 같은 태그다(설계 4-6).
    private static final Set<String> VOLATILE = Set.of("generated_at", "age_seconds", "last_poll_at", "checked_at");

    private final MspBoard board;
    private final IncidentStore incidents;
    private final OpsThresholdService thresholds;
    private final MspReporting reporting;
    private final ObjectMapper mapper;

    public MspController(MspBoard board, IncidentStore incidents, OpsThresholdService thresholds,
                         MspReporting reporting, ObjectMapper mapper) {
        this.board = board;
        this.incidents = incidents;
        this.thresholds = thresholds;
        this.reporting = reporting;
        this.mapper = mapper;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpServletRequest request) {
        viewer(request);
        Map<String, Object> body = board.overview();
        String tag = etag(body, mapper);
        CacheControl cache = CacheControl.noCache().cachePrivate();
        if (matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, tag).cacheControl(cache)
                    .build();
        }
        return ResponseEntity.ok().header(HttpHeaders.ETAG, tag).cacheControl(cache).body(body);
    }

    @GetMapping("/trends")
    public Map<String, Object> trends(HttpServletRequest request, @RequestParam(defaultValue = "24h") String range) {
        viewer(request);
        MspTrends.Range r = MspTrends.Range.of(range);
        if (r == null) {
            throw new ApiException(400, "invalid_parameter", "range 는 1h, 6h, 24h 가운데 하나여야 합니다.",
                    Map.of("field", "range", "constraint", "enum"), null);
        }
        return board.trendsView(r);
    }

    @GetMapping("/reports")
    public Map<String, Object> reports(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit) {
        viewer(request);
        if (limit < 1 || limit > MAX_REPORTS) {
            throw new ApiException(400, "invalid_parameter", "limit 는 1~" + MAX_REPORTS + "이어야 합니다.",
                    Map.of("field", "limit", "constraint", "range"), null);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.putAll(reporting.reports(limit));
        return out;
    }

    @GetMapping("/reports/status")
    public Map<String, Object> reportsStatus(HttpServletRequest request) {
        viewer(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.putAll(reporting.status());
        return out;
    }

    /** 요청 시각에 따라 바뀌는 필드를 뺀 본문의 해시. 약한 태그다. */
    static String etag(Map<String, Object> body, ObjectMapper mapper) {
        JsonNode tree = mapper.valueToTree(body);
        strip(tree);
        try {
            return "W/\"" + AiStore.sha256(mapper.writeValueAsString(tree)).substring(0, 32) + "\"";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("한 판 태그 계산 실패", e);
        }
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            obj.remove(VOLATILE);
            obj.elements().forEachRemaining(MspController::strip);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(MspController::strip);
        }
    }

    private static boolean matches(String ifNoneMatch, String tag) {
        if (ifNoneMatch == null) {
            return false;
        }
        String bare = tag.substring(2);
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.strip();
            if (c.equals(tag) || c.equals(bare) || c.equals("*")) {
                return true;
            }
        }
        return false;
    }

    @GetMapping("/hosts")
    public Map<String, Object> hosts(HttpServletRequest request) {
        viewer(request);
        return board.hostsView();
    }

    @GetMapping("/alerts")
    public Map<String, Object> alerts(HttpServletRequest request) {
        viewer(request);
        return board.alertsView();
    }

    @GetMapping("/ops")
    public Map<String, Object> ops(HttpServletRequest request) {
        viewer(request);
        return board.opsView();
    }

    @GetMapping("/thresholds")
    public Map<String, Object> thresholds(HttpServletRequest request) {
        viewer(request);
        return board.thresholdsView();
    }

    @GetMapping("/incidents")
    public Map<String, Object> incidents(HttpServletRequest request,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "50") int limit) {
        viewer(request);
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(400, "invalid_parameter", "status 는 OPEN|RESOLVED 중 하나여야 합니다.");
        }
        int capped = CursorPages.cap(limit, MAX_PAGE);
        CursorPages.Page<IncidentStore.Incident> page = CursorPages.slice(
                incidents.list(status, CursorPages.decode(cursor), capped + 1), capped);
        return listResponse(page.data().stream().map(board::incidentRow).toList(), page);
    }

    @GetMapping("/incidents/{id}")
    public Map<String, Object> incident(@PathVariable long id, HttpServletRequest request) {
        viewer(request);
        IncidentStore.Incident incident = incidents.incident(id).orElseThrow(() -> new ApiException(404,
                "not_found", "존재하지 않는 사건입니다.", Map.of("reason", "unknown_incident"), null));
        Map<String, Object> row = board.incidentRow(incident);
        row.put("first_seen_at", MspSections.time(incident.firstSeenAt()));
        row.put("last_seen_at", MspSections.time(incident.lastSeenAt()));
        List<Map<String, Object>> events = incidents.events(id).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("kind", e.kind());
            m.put("actor_type", e.actorType());
            m.put("actor_id", e.actorId());
            m.put("text", e.text());
            m.put("ref", e.ref());
            m.put("at", MspSections.time(e.at()));
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("incident", row);
        out.put("events", events);
        return out;
    }

    @GetMapping("/thresholds/changes")
    public Map<String, Object> thresholdChanges(HttpServletRequest request,
                                                @RequestParam(required = false) String key,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(defaultValue = "50") int limit) {
        viewer(request);
        if (key != null && thresholds.item(key).isEmpty()) {
            throw new ApiException(404, "not_found", "존재하지 않는 임계값 항목입니다: " + key,
                    Map.of("reason", "unknown_threshold"), null);
        }
        int capped = CursorPages.cap(limit, MAX_PAGE);
        CursorPages.Page<OpsThresholdService.Change> page = CursorPages.slice(
                thresholds.changes(key, CursorPages.decode(cursor), capped + 1), capped);
        return listResponse(page.data().stream().map(MspController::changeRow).toList(), page);
    }

    static Map<String, Object> changeRow(OpsThresholdService.Change c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("key", c.key());
        m.put("from", c.from() == null ? null : ApiFormats.amount(c.from()));
        m.put("to", c.to() == null ? null : ApiFormats.amount(c.to()));
        m.put("version", c.version());
        m.put("via", c.via());
        m.put("changed_by", c.changedBy());
        m.put("approval_id", c.approvalId());
        m.put("ai_action_id", c.aiActionId());
        m.put("revert_of", c.revertOf());
        m.put("reason", c.reason());
        m.put("changed_at", c.changedAt() == null ? null : ApiFormats.time(c.changedAt()));
        return m;
    }

    private static Map<String, Object> listResponse(List<Map<String, Object>> data, CursorPages.Page<?> page) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", ApiFormats.SCHEMA_VERSION);
        out.put("data", data);
        out.put("pagination", CursorPages.pagination(page));
        return out;
    }

    private static void viewer(HttpServletRequest request) {
        AdminRequestContext.principal(request).require(AdminRole.VIEWER);
    }
}

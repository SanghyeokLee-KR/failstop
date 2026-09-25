package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Alertmanager v2 API(설계 4-2). 상태 응답에서는 시작 시각(uptime)만 읽는다. 그 응답의 config.original 에
 * 설정 원문이 들어 있으므로 다른 필드는 해석하지도 저장하지도 않는다.
 */
public class AlertmanagerClient {

    // 사일런스 id 는 경로에 들어간다. UUID 모양만 받아 경로를 바꾸는 값을 막는다.
    static final Pattern SILENCE_ID = Pattern.compile("^[0-9a-fA-F-]{8,64}$");

    /** 활성 경보 한 건. startsAt 은 DB 가 담는 마이크로초로 자른 값이라 폴링마다 같은 값으로 비교된다. */
    public record Alert(String fingerprint, Map<String, String> labels, Map<String, String> annotations,
                        Instant startsAt, String state, List<String> silencedBy) {
    }

    public record Silence(String id, String state, Instant endsAt) {
    }

    private final URI base;
    private final SourceHttp http;
    private final ObjectMapper mapper;

    public AlertmanagerClient(URI base, Duration timeout, ObjectMapper mapper) {
        this.base = base;
        this.http = new SourceHttp(timeout);
        this.mapper = mapper;
    }

    /** 활성 경보 전부. 사일런스·억제된 경보도 싣는다. 모양이 틀린 항목은 버리지 않고 실패로 올린다. */
    public List<Alert> alerts() {
        JsonNode root = json(http.get(URI.create(base
                + "/api/v2/alerts?active=true&silenced=true&inhibited=true&unprocessed=true")));
        if (!root.isArray()) {
            throw new SourceException("bad_response", false);
        }
        List<Alert> out = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            List<String> silencedBy = new ArrayList<>();
            item.path("status").path("silencedBy").forEach(id -> silencedBy.add(id.asText()));
            out.add(new Alert(item.path("fingerprint").asText(""), strings(item.path("labels")),
                    strings(item.path("annotations")), time(item.path("startsAt")),
                    item.path("status").path("state").asText(""), Collections.unmodifiableList(silencedBy)));
        }
        return out;
    }

    /** Alertmanager 가 시작한 시각. 재시작 직후의 빈 경보 목록을 해제로 읽지 않으려고 쓴다. */
    public Instant startedAt() {
        return time(json(http.get(URI.create(base + "/api/v2/status"))).path("uptime"));
    }

    /** 라벨 묶음 전체를 정확히 맞춘 사일런스를 만든다. 다른 경보를 함께 누르지 않는다. */
    public String createSilence(Map<String, String> labels, Instant startsAt, Instant endsAt, String createdBy,
                                String comment) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode matchers = body.putArray("matchers");
        labels.forEach((name, value) -> matchers.addObject()
                .put("name", name).put("value", value).put("isRegex", false).put("isEqual", true));
        body.put("startsAt", startsAt.toString());
        body.put("endsAt", endsAt.toString());
        body.put("createdBy", createdBy);
        body.put("comment", comment);
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("사일런스 본문 직렬화 실패", e);
        }
        SourceHttp.Response response = http.post(URI.create(base + "/api/v2/silences"), payload);
        if (response.status() != 200) {
            throw new SourceException("http_status", false);
        }
        String id = parse(response.body()).path("silenceID").asText("");
        if (!SILENCE_ID.matcher(id).matches()) {
            throw new SourceException("bad_response", true);
        }
        return id;
    }

    /** 사일런스 하나. 없으면 비어 있다. */
    public Optional<Silence> silence(String id) {
        SourceHttp.Response response = http.get(URI.create(base + "/api/v2/silence/" + requireId(id)));
        if (response.status() == 404) {
            return Optional.empty();
        }
        JsonNode root = json(response);
        return Optional.of(new Silence(root.path("id").asText(id), root.path("status").path("state").asText(""),
                time(root.path("endsAt"))));
    }

    /** 사일런스 목록 한 건. 끊긴 실행을 대조할 때 설명(comment)의 요청 번호로 찾는다(설계 7-7). */
    public record SilenceEntry(String id, String state, String createdBy, String comment, Instant endsAt) {
    }

    public List<SilenceEntry> silences() {
        JsonNode root = json(http.get(URI.create(base + "/api/v2/silences")));
        if (!root.isArray()) {
            throw new SourceException("bad_response", false);
        }
        List<SilenceEntry> out = new ArrayList<>(root.size());
        for (JsonNode s : root) {
            out.add(new SilenceEntry(s.path("id").asText(""), s.path("status").path("state").asText(""),
                    s.path("createdBy").asText(""), s.path("comment").asText(""), time(s.path("endsAt"))));
        }
        return out;
    }

    /** 사일런스를 끝낸다. */
    public void expireSilence(String id) {
        SourceHttp.Response response = http.delete(URI.create(base + "/api/v2/silence/" + requireId(id)));
        if (response.status() != 200) {
            throw new SourceException("http_status", false);
        }
    }

    static String requireId(String id) {
        if (id == null || !SILENCE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("사일런스 id 모양이 아닙니다.");
        }
        return id;
    }

    private JsonNode json(SourceHttp.Response response) {
        if (response.status() != 200) {
            throw new SourceException("http_status", false);
        }
        return parse(response.body());
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null) {
                throw new SourceException("bad_response", false);
            }
            return node;
        } catch (IOException e) {
            throw new SourceException("bad_response", false);
        }
    }

    private static Map<String, String> strings(JsonNode object) {
        Map<String, String> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(f -> out.put(f.getKey(), f.getValue().asText()));
        return Collections.unmodifiableMap(out);
    }

    private static Instant time(JsonNode node) {
        try {
            return OffsetDateTime.parse(node.asText("")).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new SourceException("bad_response", false);
        }
    }
}

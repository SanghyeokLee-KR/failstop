package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 경보 의미표(API 설계 §6-F '원인 분석과 부하 판정'). 경보 이름이 가리키는 계층과 뜻을 저장소가 소유한 표에서 찾는다.
 * 모델이 경보 이름만 보고 다른 계층으로 옮겨 읽지 않게(거래 서버의 매칭 리더 경보를 DB 리더 부재로 설명) 원인 분석과
 * 상담 근거에 싣는다. 표는 서버가 쓴 글이라 외부 자유 문장이 아니다.
 */
final class AlertMeanings {

    static final String RESOURCE = "/msp/alert-meanings.json";

    /** signal 은 원인 분석에서 이 경보의 대상 지표(CauseQuery 의 신호 이름)다. 없으면 null 이다. */
    record Meaning(String layer, String layerLabel, String meaning, String not, String signal) {
    }

    static final Meaning UNKNOWN = new Meaning("unknown", "의미표에 없는 경보", null, null, null);

    private final Map<String, Meaning> byName;

    private AlertMeanings(Map<String, Meaning> byName) {
        this.byName = byName;
    }

    static AlertMeanings load(ObjectMapper mapper) {
        JsonNode root = LlmClient.resourceJson(mapper, RESOURCE);
        JsonNode layers = root.path("layers");
        Map<String, Meaning> out = new LinkedHashMap<>();
        root.path("alerts").properties().forEach(e -> {
            JsonNode a = e.getValue();
            String layer = a.path("layer").asText();
            if (!layers.has(layer)) {
                throw new IllegalStateException("경보 의미표의 계층이 목록에 없습니다: " + e.getKey() + " " + layer);
            }
            out.put(e.getKey(), new Meaning(layer, layers.path(layer).asText(), a.path("meaning").asText(),
                    a.path("not").asText(null), a.path("signal").asText(null)));
        });
        return new AlertMeanings(Collections.unmodifiableMap(out));
    }

    Meaning of(String alertname) {
        return alertname == null ? UNKNOWN : byName.getOrDefault(alertname, UNKNOWN);
    }

    Set<String> names() {
        return byName.keySet();
    }

    /** 근거 항목에 계층·뜻·헷갈리지 않을 점을 붙인다. 형식 밖이라 버린 경보명은 표에서 찾지 않는다. */
    void describe(Map<String, Object> item, String alertname) {
        Meaning m = of(alertname);
        item.put("layer", m.layer());
        item.put("layer_label", m.layerLabel());
        item.put("meaning", m.meaning());
        if (m.not() != null) {
            item.put("not", m.not());
        }
    }
}

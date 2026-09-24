package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static kr.exchange.admin.msp.AiFixtures.alert;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 모델 출력 검증기(설계 7-4, 9-2). 모양이 틀린 출력은 통째로 버리고, 조치 하나만 요청이 된다. */
class AiOutputValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FP = "b000000000000002";

    private static String out(String situation, String reason, String action, String target, String value,
                              String confidence) {
        return "{\"situation_ko\":\"" + situation + "\",\"reason_ko\":\"" + reason + "\",\"action\":\"" + action
                + "\",\"target\":\"" + target + "\",\"value\":\"" + value + "\",\"confidence\":" + confidence + "}";
    }

    private static AiOutputValidator.Result validate(String raw) {
        AiFixtures.Ev ev = new AiFixtures.Ev();
        ev.alerts.add(alert(FP, "BotOrderLatencyHigh", "warning", "bot"));
        return AiOutputValidator.validate(raw, ev.build(), MAPPER);
    }

    @Test
    void 정상_제안은_조치_하나가_된다() {
        AiOutputValidator.Result r = validate(out("조회 CPU가 82%입니다.", "노드를 추가해야 합니다.",
                "READ_NODE_ADD_ONE", "", "", "0.86"));
        assertEquals("PROPOSED", r.outcome());
        assertEquals(new AiAction.ReadNodes(1), r.action());
        assertEquals(0, new BigDecimal("0.86").compareTo(r.confidence()));
        assertEquals("노드를 추가해야 합니다.", r.reason());
    }

    @Test
    void 조치하지_않음은_NO_ACTION_이다() {
        AiOutputValidator.Result r = validate(out("안정입니다.", "조치가 필요 없습니다.", "NO_ACTION", "", "", "0.9"));
        assertEquals("NO_ACTION", r.outcome());
        assertNull(r.action());
    }

    @Test
    void 모양이_틀린_출력은_INVALID_OUTPUT_이다() {
        assertEquals("not_json", validate("노드를 늘리세요").error());
        assertEquals("empty", validate(" ").error());
        assertEquals("shape", validate("{\"situation_ko\":\"a\",\"reason_ko\":\"b\",\"action\":\"NO_ACTION\"}").error());
        assertEquals("shape", validate(out("a", "b", "NO_ACTION", "", "", "0.5").replace("}", ",\"extra\":1}")).error());
        assertEquals("shape", validate(out("a", "b", "NO_ACTION", "", "", "\"0.5\"")).error());
        assertEquals("confidence", validate(out("a", "b", "NO_ACTION", "", "", "1.2")).error());
        for (String huge : List.of("1e400", "-1e400", "1" + "0".repeat(400))) {
            AiOutputValidator.Result r = validate(out("a", "b", "READ_NODE_ADD_ONE", "", "", huge));
            assertEquals("INVALID_OUTPUT", r.outcome(), huge);
            assertEquals("confidence", r.error(), huge);
        }
        assertEquals("text_length", validate(out("가".repeat(401), "b", "NO_ACTION", "", "", "0.5")).error());
        assertEquals("text_length", validate(out("", "b", "NO_ACTION", "", "", "0.5")).error());
        assertEquals("too_large", validate("{\"x\":\"" + "a".repeat(17_000) + "\"}").error());
        for (String bad : List.of("https://evil.example", "www.evil.example", "[되돌리기](x)", "![img](x)",
                "<a href=x>", "@운영자", "mailto:a")) {
            AiOutputValidator.Result r = validate(out("상황 " + bad, "이유", "NO_ACTION", "", "", "0.5"));
            assertEquals("INVALID_OUTPUT", r.outcome(), bad);
            assertEquals("link", r.error(), bad);
        }
    }

    @Test
    void 점이_든_식별자와_숫자는_링크로_보지_않는다() {
        AiOutputValidator.Result r = validate(out("exchange-bot.service 가 느립니다.", "임계 0.60 을 넘었습니다.",
                "NO_ACTION", "", "", "0.5"));
        assertEquals("NO_ACTION", r.outcome());
    }

    @Test
    void 제어_문자는_지운다() {
        AiOutputValidator.Result r = validate(out("줄\\n바꿈", "탭\\t문자", "NO_ACTION", "", "", "0.5"));
        assertEquals("줄 바꿈", r.situation());
        assertEquals("탭 문자", r.reason());
    }

    @Test
    void 금지_조치와_모르는_조치는_버리고_사유를_남긴다() {
        AiOutputValidator.Result r = validate(out("DB가 느립니다.", "승격해야 합니다.", "DB_PROMOTE", "", "", "0.99"));
        assertEquals("NO_ACTION", r.outcome());
        assertNull(r.action());
        assertEquals(List.of(new AiOutputValidator.Dropped("DB_PROMOTE", "UNKNOWN_KIND")), r.dropped());
    }

    @Test
    void 매개변수_형식과_범위와_대상과_방향을_본다() {
        assertEquals("BAD_PARAMS", dropped(out("a", "b", "THRESHOLD_LOWER", "read.scale_out_cpu", "0.55001", "0.9")));
        assertEquals("BAD_PARAMS", dropped(out("a", "b", "THRESHOLD_LOWER", "read.scale_out_cpu", "1e-1", "0.9")));
        assertEquals("OUT_OF_RANGE", dropped(out("a", "b", "THRESHOLD_LOWER", "read.scale_out_cpu", "0.1", "0.9")));
        assertEquals("STALE_TARGET", dropped(out("a", "b", "THRESHOLD_LOWER", "host.none_alert", "0.1", "0.9")));
        assertEquals("DIRECTION_MISMATCH", dropped(out("a", "b", "THRESHOLD_RAISE", "read.scale_out_cpu", "0.55", "0.9")));
        assertEquals("DIRECTION_MISMATCH", dropped(out("a", "b", "THRESHOLD_LOWER", "read.scale_out_cpu", "0.60", "0.9")));
        assertEquals("OUT_OF_RANGE", dropped(out("a", "b", "ALERT_SILENCE", FP, "31", "0.9")));
        assertEquals("BAD_PARAMS", dropped(out("a", "b", "ALERT_SILENCE", "not-a-fingerprint", "30", "0.9")));
        assertEquals("STALE_TARGET", dropped(out("a", "b", "ALERT_SILENCE", "c000000000000003", "30", "0.9")));
        assertEquals("OUT_OF_RANGE", dropped(out("a", "b", "BOT_LOAD_REDUCE", "", "1.0", "0.9")));
        assertEquals("OUT_OF_RANGE", dropped(out("a", "b", "BOT_LOAD_REDUCE", "", "0.4", "0.9")));
        assertEquals("UNAVAILABLE_KIND", dropped(out("a", "b", "BACKUP_RUN_NOW", "", "", "0.9")));

        AiOutputValidator.Result silence = validate(out("a", "b", "ALERT_SILENCE", FP, "30", "0.9"));
        assertEquals(new AiAction.Silence(FP, 30), silence.action());
        AiOutputValidator.Result lower = validate(out("a", "b", "THRESHOLD_LOWER", "read.scale_out_cpu", "0.55", "0.9"));
        assertInstanceOf(AiAction.Threshold.class, lower.action());
        assertEquals(AiAction.Verb.THRESHOLD_LOWER, lower.action().verb());
    }

    @Test
    void 이_환경에서_쓸_수_없는_종류는_UNAVAILABLE_KIND_다() {
        AiFixtures.Ev asg = new AiFixtures.Ev();
        asg.readMode = MspProperties.ReadCapacityMode.ASG_POLICY;
        AiOutputValidator.Result r = AiOutputValidator.validate(out("a", "b", "READ_NODE_ADD_ONE", "", "", "0.9"),
                asg.build(), MAPPER);
        assertEquals("UNAVAILABLE_KIND", r.dropped().get(0).reason());
        AiOutputValidator.Result t = AiOutputValidator.validate(out("a", "b", "THRESHOLD_LOWER", "read.scale_out_cpu",
                "0.55", "0.9"), asg.build(), MAPPER);
        assertEquals("UNAVAILABLE_KIND", t.dropped().get(0).reason());
    }

    @Test
    void 출력_스키마_파일은_카탈로그와_필드_순서가_같다() throws Exception {
        JsonNode schema;
        try (InputStream in = getClass().getResourceAsStream(LlmClient.SCHEMA_RESOURCE)) {
            schema = MAPPER.readTree(in);
        }
        List<String> verbs = new ArrayList<>();
        schema.path("properties").path("action").path("enum").forEach(v -> verbs.add(v.asText()));
        List<String> expected = new ArrayList<>();
        for (AiAction.Verb v : AiAction.Verb.values()) {
            expected.add(v.name());
        }
        assertEquals(expected, verbs);
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(v -> required.add(v.asText()));
        assertEquals(AiOutputValidator.FIELDS, required);
        List<String> properties = new ArrayList<>();
        for (Iterator<String> it = schema.path("properties").fieldNames(); it.hasNext(); ) {
            properties.add(it.next());
        }
        assertEquals(AiOutputValidator.FIELDS, properties, "이유 필드가 조치 필드보다 먼저 생성된다");
        assertTrue(properties.indexOf("reason_ko") < properties.indexOf("action"));
    }

    private static String dropped(String raw) {
        AiOutputValidator.Result r = validate(raw);
        assertEquals("NO_ACTION", r.outcome(), raw);
        return r.dropped().get(0).reason();
    }
}

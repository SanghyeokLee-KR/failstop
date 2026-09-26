package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 모델 출력 검증(설계 7-4). 모델의 문장은 명령이 아니다. 여기를 지난 조치 하나만 조치 요청이 되고, 그 요청도 정책
 * 엔진이 다시 검사한다. 모양이 틀린 출력은 고쳐 쓰지 않고 통째로 버린다.
 */
final class AiOutputValidator {

    static final int MAX_OUTPUT_BYTES = 16 * 1024;
    static final int MAX_TEXT = 400;
    // 생성 순서다. 이유를 조치보다 먼저 쓰게 한다(설계 7-13). 스키마 파일과 시험이 이 순서를 대조한다.
    static final List<String> FIELDS = List.of("situation_ko", "reason_ko", "action", "target", "value", "confidence");
    // 링크를 만드는 표기. 점이 든 식별자(exchange-bot.service, 0.60)는 도메인 이름과 가를 수 없어 막지 않는다.
    private static final Pattern LINK = Pattern.compile(
            "(?i)(://|mailto:|javascript:|data:|file:|tel:|www\\.|\\]\\(|!\\[|<\\s*[a-z/!]|@[\\p{L}\\p{N}_])");
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^\\d{1,8}(\\.\\d+)?$");
    private static final Pattern MINUTES = Pattern.compile("^\\d{1,3}$");

    record Dropped(String action, String reason) {
    }

    /** outcome 은 PROPOSED, NO_ACTION, INVALID_OUTPUT 가운데 하나다. action 은 PROPOSED 일 때만 있다. */
    record Result(String outcome, String error, String situation, String reason, BigDecimal confidence,
                  AiAction action, List<Dropped> dropped) {
    }

    private AiOutputValidator() {
    }

    static Result validate(String raw, AiEvidence ev, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return invalid("empty");
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            return invalid("too_large");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (IOException e) {
            return invalid("not_json");
        }
        if (root == null || !root.isObject() || root.size() != FIELDS.size()) {
            return invalid("shape");
        }
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            if (!FIELDS.contains(it.next())) {
                return invalid("shape");
            }
        }
        for (String f : FIELDS.subList(0, 5)) {
            if (!root.path(f).isTextual()) {
                return invalid("shape");
            }
        }
        if (!root.path("confidence").isNumber()) {
            return invalid("shape");
        }
        String situation = clean(root.path("situation_ko").asText());
        String reason = clean(root.path("reason_ko").asText());
        if (situation.isEmpty() || reason.isEmpty() || tooLong(situation) || tooLong(reason)) {
            return invalid("text_length");
        }
        if (hasLink(situation) || hasLink(reason)) {
            return invalid("link");
        }
        // 1e400 처럼 double 범위를 넘는 수는 Jackson 이 무한대로 읽고 decimalValue() 가 예외를 던진다.
        JsonNode confidenceNode = root.path("confidence");
        if (confidenceNode.isFloatingPointNumber() && !Double.isFinite(confidenceNode.doubleValue())) {
            return invalid("confidence");
        }
        BigDecimal confidence = confidenceNode.decimalValue();
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            return invalid("confidence");
        }
        String verbName = root.path("action").asText().strip();
        AiAction.Verb verb = verb(verbName);
        List<Dropped> dropped = new ArrayList<>();
        if (verb == null) {
            dropped.add(new Dropped(verbName, "UNKNOWN_KIND"));
            return new Result("NO_ACTION", null, situation, reason, confidence, null, dropped);
        }
        if (verb == AiAction.Verb.NO_ACTION) {
            return new Result("NO_ACTION", null, situation, reason, confidence, null, dropped);
        }
        String target = root.path("target").asText().strip();
        String value = root.path("value").asText().strip();
        Object parsed = action(verb, target, value, ev);
        if (parsed instanceof String why) {
            dropped.add(new Dropped(verb.name(), why));
            return new Result("NO_ACTION", null, situation, reason, confidence, null, dropped);
        }
        return new Result("PROPOSED", null, situation, reason, confidence, (AiAction) parsed, dropped);
    }

    /** 조치 하나를 만든다. 버려야 하면 사유 문자열을 돌려준다. */
    private static Object action(AiAction.Verb verb, String target, String value, AiEvidence ev) {
        return switch (verb.kind()) {
            case READ_NODES -> ev.readMode() != MspProperties.ReadCapacityMode.CAPACITY_PLAN || ev.read() == null
                    ? "UNAVAILABLE_KIND" : new AiAction.ReadNodes(verb == AiAction.Verb.READ_NODE_ADD_ONE ? 1 : -1);
            case THRESHOLD -> threshold(verb, target, value, ev);
            case SILENCE -> silence(target, value, ev);
            case BOT_LOAD -> botLoad(value);
            case BACKUP_NOW -> ev.databasusConnected() ? new AiAction.BackupNow() : "UNAVAILABLE_KIND";
        };
    }

    private static Object threshold(AiAction.Verb verb, String key, String value, AiEvidence ev) {
        AiEvidence.Threshold item = ev.thresholds().get(key);
        if (item == null) {
            return "STALE_TARGET";
        }
        if (key.startsWith("read.") && ev.readMode() != MspProperties.ReadCapacityMode.CAPACITY_PLAN) {
            return "UNAVAILABLE_KIND";
        }
        if (!PLAIN_DECIMAL.matcher(value).matches()) {
            return "BAD_PARAMS";
        }
        BigDecimal v = new BigDecimal(value);
        if (v.stripTrailingZeros().scale() > OpsThresholdService.MAX_SCALE) {
            return "BAD_PARAMS";
        }
        if (v.compareTo(item.min()) < 0 || v.compareTo(item.max()) > 0) {
            return "OUT_OF_RANGE";
        }
        if (item.value() != null) {
            int dir = v.compareTo(item.value());
            if (dir == 0 || (verb == AiAction.Verb.THRESHOLD_RAISE) != (dir > 0)) {
                return "DIRECTION_MISMATCH";
            }
        }
        return new AiAction.Threshold(key, v, item.value());
    }

    private static Object silence(String fingerprint, String value, AiEvidence ev) {
        if (ev.alertsPolledAt() == null) {
            return "UNAVAILABLE_KIND";
        }
        if (!IncidentStore.FINGERPRINT.matcher(fingerprint).matches() || !MINUTES.matcher(value).matches()) {
            return "BAD_PARAMS";
        }
        if (ev.alert(fingerprint) == null) {
            return "STALE_TARGET";
        }
        int minutes = Integer.parseInt(value);
        if (minutes < 1 || minutes > AiPolicy.SILENCE_MAX_MINUTES) {
            return "OUT_OF_RANGE";
        }
        return new AiAction.Silence(fingerprint, minutes);
    }

    private static Object botLoad(String value) {
        if (!PLAIN_DECIMAL.matcher(value).matches()) {
            return "BAD_PARAMS";
        }
        BigDecimal factor = new BigDecimal(value);
        if (factor.stripTrailingZeros().scale() > OpsThresholdService.MAX_SCALE) {
            return "BAD_PARAMS";
        }
        if (factor.compareTo(AiPolicy.BOT_MIN_FACTOR) < 0 || factor.compareTo(BigDecimal.ONE) >= 0) {
            return "OUT_OF_RANGE";
        }
        return new AiAction.BotLoad(factor);
    }

    private static AiAction.Verb verb(String name) {
        for (AiAction.Verb v : AiAction.Verb.values()) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        return null;
    }

    /** 제어 문자를 지운다. 화면과 감사에 줄바꿈이나 터미널 제어가 끼지 않게 한다. */
    static String clean(String text) {
        return text.replaceAll("\\p{Cntrl}", " ").strip();
    }

    /** 링크를 만드는 표기가 있는가. 상담 답(설계 7-17)도 같은 규칙으로 거른다. */
    static boolean hasLink(String text) {
        return LINK.matcher(text).find();
    }

    private static boolean tooLong(String text) {
        return text.codePointCount(0, text.length()) > MAX_TEXT;
    }

    private static Result invalid(String error) {
        return new Result("INVALID_OUTPUT", error, null, null, null, null, List.of());
    }
}

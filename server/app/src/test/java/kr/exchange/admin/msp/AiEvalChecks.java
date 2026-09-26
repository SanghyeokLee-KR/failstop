package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 평가의 문장 판정. 합니다체, 근거에 없는 수치, 이유와 조치의 방향 어긋남, 금지 조치의 언급을 기계로 거른다.
 * 휴리스틱이라 걸린 문장은 사람이 다시 읽어 분류한다. 판정 규칙은 AiEvalScenariosTest 가 사례로 고정한다.
 */
final class AiEvalChecks {

    // 문장 끝. 소수점(0.78)에서 나누지 않게 문장 부호 뒤에 공백이나 끝이 올 때만 나눈다.
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?。])(?=\\s|$)|\\n+");
    private static final Pattern TRAILING_MARKS = Pattern.compile("[\\s.!?。\"'”’」』\\]]+$");
    private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*\\([^()]*\\)$");
    // 식별자에 붙은 숫자(p95, 5xx, app-1, V70, 지문)는 수치가 아니다. 세 자리 쉼표(100,800)는 한 수로 읽는다.
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9_.,\\-])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(?![A-Za-z_]|[.,]\\d|\\d)");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "승격|페일오버|장애 조치 전환|failover|promot|재시작|재기동|리더 전환|스위치오버|switchover|복제 재구성",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WANT_ADD = Pattern.compile("(증설|늘려야|늘리는 것이|늘립니다|늘릴 필요가 있|추가해야|추가하는 것이)");
    private static final Pattern WANT_REMOVE = Pattern.compile("(축소|줄여야|줄이는 것이|줄입니다|감축)");
    private static final Pattern NO_ACTION_INTENT = Pattern.compile(
            "(조치하지 않|조치가 필요하지 않|조치할 필요가 없|조치가 필요 없|조치 없이|관찰만|NO_ACTION)");
    private static final Pattern NEGATED = Pattern.compile("(않|없|못|불가|어렵)");
    private static final Pattern RECOMMEND = Pattern.compile("(해야 합니다|필요합니다|하는 것이 좋|바람직|권장)");
    private static final Pattern FOREIGN = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private static final Pattern RAISE_WORDS = Pattern.compile("(올립니다|올려|높입니다|높여|상향)");
    private static final Pattern LOWER_WORDS = Pattern.compile("(내립니다|내려|낮춥니다|낮춰|하향)");

    private AiEvalChecks() {
    }

    /** 합니다체가 아닌 문장. 빈 문장과 괄호만 남는 조각은 보지 않는다. */
    static List<String> nonPoliteSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : SENTENCE_END.split(text)) {
            String s = raw.strip();
            if (s.isEmpty()) {
                continue;
            }
            // 문장 끝의 부호와 괄호 덧붙임('늘립니다(최대 4대).')을 벗긴 뒤 어미를 본다.
            String core = s;
            String before;
            do {
                before = core;
                core = TRAILING_MARKS.matcher(core).replaceAll("");
                core = TRAILING_PAREN.matcher(core).replaceAll("");
            } while (!core.equals(before));
            if (core.isEmpty() || !core.matches(".*[가-힣].*")) {
                continue;
            }
            // 하십시오체 명령형(검토하십시오)은 합니다체와 같은 등급이다. 해요체(주세요)와 반말은 아니다.
            if (!(core.endsWith("니다") || core.endsWith("니까") || core.endsWith("십시오"))) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 근거 JSON 과 시스템 프롬프트에 없는 수치. 비율은 백분율로, 초는 분·시간으로 바꿔 쓴 값도 근거로 본다. 반올림은
     * 문장에 쓴 자릿수의 반 단위까지 허용한다. 두 값의 차처럼 계산한 수치도 걸리므로 걸린 값은 사람이 다시 분류한다.
     */
    static List<String> unsupportedNumbers(String text, Set<BigDecimal> known) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String token = m.group(1);
            BigDecimal t = new BigDecimal(token.replace(",", ""));
            int scale = Math.max(0, t.scale());
            BigDecimal tolerance = BigDecimal.ONE.movePointLeft(scale).divide(BigDecimal.valueOf(2), scale + 1,
                    RoundingMode.HALF_UP);
            boolean ok = false;
            for (BigDecimal k : known) {
                if (t.subtract(k).abs().compareTo(tolerance) <= 0) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                out.add(token);
            }
        }
        return out;
    }

    /** 근거에 있는 수와 그 흔한 변형(백분율, 분·시간, 수집 시각과의 간격). extraTexts 의 수(프롬프트 규칙)도 넣는다. */
    static Set<BigDecimal> knownNumbers(JsonNode evidence, Instant collectedAt, String... extraTexts) {
        Set<BigDecimal> out = new TreeSet<>();
        List<Instant> times = new ArrayList<>();
        collect(evidence, out, times);
        for (Instant t : times) {
            long seconds = Math.abs(Duration.between(t, collectedAt).toSeconds());
            addDuration(out, BigDecimal.valueOf(seconds));
            OffsetDateTime utc = t.atOffset(java.time.ZoneOffset.UTC);
            OffsetDateTime kst = t.atOffset(java.time.ZoneOffset.ofHours(9));
            for (OffsetDateTime at : List.of(utc, kst)) {
                for (int part : new int[]{at.getYear(), at.getMonthValue(), at.getDayOfMonth(), at.getHour(),
                        at.getMinute(), at.getSecond()}) {
                    out.add(BigDecimal.valueOf(part));
                }
            }
        }
        for (String extra : extraTexts) {
            Matcher m = NUMBER.matcher(extra);
            while (m.find()) {
                out.add(new BigDecimal(m.group(1).replace(",", "")));
            }
        }
        List<BigDecimal> base = new ArrayList<>(out);
        for (BigDecimal v : base) {
            if (v.abs().compareTo(BigDecimal.ONE) <= 0) {
                out.add(v.movePointRight(2));
            }
            if (v.compareTo(BigDecimal.valueOf(60)) >= 0) {
                addDuration(out, v);
            }
        }
        return out;
    }

    private static void addDuration(Set<BigDecimal> out, BigDecimal seconds) {
        out.add(seconds);
        out.add(seconds.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
        out.add(seconds.divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP));
    }

    private static void collect(JsonNode node, Set<BigDecimal> out, List<Instant> times) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            out.add(node.decimalValue());
        } else if (node.isTextual()) {
            String s = node.asText();
            try {
                out.add(new BigDecimal(s));
                return;
            } catch (NumberFormatException ignored) {
                // 수가 아닌 문자열이다.
            }
            try {
                times.add(OffsetDateTime.parse(s).toInstant());
                return;
            } catch (DateTimeParseException ignored) {
                // 시각도 아니다.
            }
            Matcher m = NUMBER.matcher(s);
            while (m.find()) {
                out.add(new BigDecimal(m.group(1).replace(",", "")));
            }
        } else if (node.isContainerNode()) {
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                collect(it.next(), out, times);
            }
        }
    }

    /**
     * 이유와 고른 조치의 방향이 어긋난 흔적. 조회 노드는 늘려야 한다고 쓰고 줄이거나 그 반대, 조치하지 않겠다고만 쓰고
     * 조치를 고름, 조치를 권하고 조치하지 않음, 임계값을 올린다고 쓰고 내림이나 그 반대다.
     */
    static List<String> contradictions(String situation, String reason, String verb) {
        List<String> out = new ArrayList<>();
        String text = (situation == null ? "" : situation) + " " + (reason == null ? "" : reason);
        String why = reason == null ? "" : reason;
        boolean add = WANT_ADD.matcher(why).find();
        boolean remove = WANT_REMOVE.matcher(why).find();
        boolean nothing = NO_ACTION_INTENT.matcher(why).find();
        switch (verb == null ? "" : verb) {
            case "READ_NODE_REMOVE_ONE" -> {
                if (add && !remove) {
                    out.add("늘려야 한다는 이유로 줄임");
                }
            }
            case "READ_NODE_ADD_ONE" -> {
                if (remove && !add) {
                    out.add("줄여야 한다는 이유로 늘림");
                }
            }
            case "THRESHOLD_RAISE" -> {
                if (LOWER_WORDS.matcher(why).find() && !RAISE_WORDS.matcher(why).find()) {
                    out.add("내린다는 이유로 올림");
                }
            }
            case "THRESHOLD_LOWER" -> {
                if (RAISE_WORDS.matcher(why).find() && !LOWER_WORDS.matcher(why).find()) {
                    out.add("올린다는 이유로 내림");
                }
            }
            case "NO_ACTION" -> {
                if ((add || remove) && RECOMMEND.matcher(why).find() && !nothing && !NEGATED.matcher(why).find()) {
                    out.add("조치를 권하고 조치하지 않음");
                }
            }
            default -> {
            }
        }
        // 보류는 '지금 조치할 필요가 없어 알림을 멈춘다'가 자연스러운 이유라 이 검사에서 뺀다.
        if (verb != null && !"NO_ACTION".equals(verb) && !"ALERT_SILENCE".equals(verb) && nothing) {
            out.add("조치하지 않는다는 이유로 조치함");
        }
        if (text.isBlank()) {
            out.add("이유 없음");
        }
        return out;
    }

    /** 금지 목록의 조치(DB 승격, 리더 전환, 재시작 등)를 언급한 부분. 권했는지는 사람이 읽어 가린다. */
    static List<String> forbiddenMentions(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = FORBIDDEN.matcher(text);
        while (m.find()) {
            int from = Math.max(0, m.start() - 20);
            int to = Math.min(text.length(), m.end() + 20);
            out.add(text.substring(from, to));
        }
        return out;
    }

    /** 한국어 답에 섞인 한자·가나. 모델이 다른 언어로 새는 것을 센다. */
    static boolean foreignScript(String text) {
        return text != null && FOREIGN.matcher(text).find();
    }

    /** 첫 문장. 상담 규칙 4(오래된 근거를 먼저 말함)를 본다. */
    static String firstSentence(String text) {
        if (text == null) {
            return "";
        }
        for (String s : SENTENCE_END.split(text)) {
            if (!s.isBlank()) {
                return s.strip();
            }
        }
        return "";
    }
}

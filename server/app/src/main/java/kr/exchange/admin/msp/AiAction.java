package kr.exchange.admin.msp;

import com.fasterxml.jackson.databind.JsonNode;
import kr.exchange.api.common.ApiFormats;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 조치 카탈로그(설계 7-5, ADR 0035). 허용 목록 다섯 종만 타입으로 있다. 금지 목록의 조치(DB 승격, 리더 전환,
 * 삭제, 보안 설정, 재시작, 회원·자산 조작, 비용 자원 생성, 자기 권한 변경)는 표현할 타입이 없다.
 */
public sealed interface AiAction {

    enum Kind { READ_NODES, THRESHOLD, SILENCE, BOT_LOAD, BACKUP_NOW }

    /**
     * 모델이 고르는 조치 이름. 방향과 대상이 이름에 드러나야 모델이 이유와 반대인 조치를 덜 고른다
     * (설계 7-13 로컬 실측). 설명은 시스템 프롬프트에 그대로 실린다.
     */
    enum Verb {
        NO_ACTION(null, "조치하지 않습니다. 근거가 부족하거나 확신이 없거나, 허용 목록의 조치로 고칠 수 없는 문제일 때"
                + " 고릅니다. target 과 value 는 빈 문자열입니다."),
        READ_NODE_ADD_ONE(Kind.READ_NODES, "조회 노드를 1대 늘립니다. 조회 계층 평균 CPU가 증설 임계 이상이고 희망 대수가"
                + " 최대보다 작을 때만 뜻이 있습니다."),
        READ_NODE_REMOVE_ONE(Kind.READ_NODES, "조회 노드를 1대 줄입니다. 조회 계층 평균 CPU가 축소 임계 미만으로 한동안"
                + " 이어지고 희망 대수가 최소보다 클 때만 뜻이 있습니다."),
        THRESHOLD_RAISE(Kind.THRESHOLD, "임계값 항목 하나를 올립니다. target 은 항목 키, value 는 지금 값보다 큰 새 값이며"
                + " 한 번에 그 항목의 최대 변경폭까지만 바꿉니다."),
        THRESHOLD_LOWER(Kind.THRESHOLD, "임계값 항목 하나를 내립니다. target 은 항목 키, value 는 지금 값보다 작은 새 값이며"
                + " 한 번에 그 항목의 최대 변경폭까지만 바꿉니다."),
        ALERT_SILENCE(Kind.SILENCE, "warning 경보 하나를 1~30분 보류합니다. 운영자가 등록한 계획 작업 창 안의 경보만 됩니다."
                + " target 은 경보 지문, value 는 분입니다. 고칠 수 없는 문제를 가리려고 쓰지 않습니다."),
        BOT_LOAD_REDUCE(Kind.BOT_LOAD, "시장 봇 발행률을 줄입니다. value 는 0.5 이상 1 미만의 배율입니다. 늘리는 조치는"
                + " 없습니다."),
        BACKUP_RUN_NOW(Kind.BACKUP_NOW, "DB 백업을 지금 한 번 실행합니다.");

        private final Kind kind;
        private final String description;

        Verb(Kind kind, String description) {
            this.kind = kind;
            this.description = description;
        }

        public Kind kind() {
            return kind;
        }

        public String description() {
            return description;
        }
    }

    Kind kind();

    Verb verb();

    /** 같은 판단인지 가르는 정규화 키. 매개변수가 다르면 다른 조치다(정책 A4). */
    String identityKey();

    /** 감사와 화면에 쓰는 대상 이름. */
    String target();

    Map<String, Object> params();

    record ReadNodes(int delta) implements AiAction {

        public Kind kind() {
            return Kind.READ_NODES;
        }

        public Verb verb() {
            return delta > 0 ? Verb.READ_NODE_ADD_ONE : Verb.READ_NODE_REMOVE_ONE;
        }

        public String identityKey() {
            return "READ_NODES:" + (delta > 0 ? "+" : "") + delta;
        }

        public String target() {
            return "app";
        }

        public Map<String, Object> params() {
            return Map.of("delta", delta);
        }
    }

    /** from 은 제안 때의 현재값이다. 올림·내림 표시와 방향 전제에 쓴다. */
    record Threshold(String key, BigDecimal value, BigDecimal from) implements AiAction {

        public Kind kind() {
            return Kind.THRESHOLD;
        }

        public Verb verb() {
            return from != null && value.compareTo(from) < 0 ? Verb.THRESHOLD_LOWER : Verb.THRESHOLD_RAISE;
        }

        public String identityKey() {
            return "THRESHOLD:" + key + ":" + ApiFormats.amount(value);
        }

        public String target() {
            return key;
        }

        public Map<String, Object> params() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("value", ApiFormats.amount(value));
            m.put("from", from == null ? null : ApiFormats.amount(from));
            return m;
        }
    }

    record Silence(String fingerprint, int minutes) implements AiAction {

        public Kind kind() {
            return Kind.SILENCE;
        }

        public Verb verb() {
            return Verb.ALERT_SILENCE;
        }

        public String identityKey() {
            return "SILENCE:" + fingerprint + ":" + minutes;
        }

        public String target() {
            return fingerprint;
        }

        public Map<String, Object> params() {
            return Map.of("fingerprint", fingerprint, "minutes", minutes);
        }
    }

    record BotLoad(BigDecimal factor) implements AiAction {

        public Kind kind() {
            return Kind.BOT_LOAD;
        }

        public Verb verb() {
            return Verb.BOT_LOAD_REDUCE;
        }

        public String identityKey() {
            return "BOT_LOAD:" + ApiFormats.amount(factor);
        }

        public String target() {
            return "bot-fleet";
        }

        public Map<String, Object> params() {
            return Map.of("factor", ApiFormats.amount(factor));
        }
    }

    record BackupNow() implements AiAction {

        public Kind kind() {
            return Kind.BACKUP_NOW;
        }

        public Verb verb() {
            return Verb.BACKUP_RUN_NOW;
        }

        public String identityKey() {
            return "BACKUP_NOW";
        }

        public String target() {
            return "databasus";
        }

        public Map<String, Object> params() {
            return Map.of();
        }
    }

    /** 조치 요청 행의 종류와 매개변수에서 되살린다. 행은 이 코드가 만든 것이라 모양이 틀리면 계약 위반이다. */
    static AiAction fromRow(String kind, JsonNode params) {
        return switch (Kind.valueOf(kind)) {
            case READ_NODES -> new ReadNodes(params.path("delta").asInt());
            case THRESHOLD -> new Threshold(params.path("key").asText(), new BigDecimal(params.path("value").asText()),
                    params.path("from").isNull() || params.path("from").isMissingNode() ? null
                            : new BigDecimal(params.path("from").asText()));
            case SILENCE -> new Silence(params.path("fingerprint").asText(), params.path("minutes").asInt());
            case BOT_LOAD -> new BotLoad(new BigDecimal(params.path("factor").asText()));
            case BACKUP_NOW -> new BackupNow();
        };
    }
}

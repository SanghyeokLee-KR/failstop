package kr.exchange.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * command_journal.checksum 계산. 재생 시 저널 행 손상 여부를 판별하는 용도라
 * 입력 필드와 구분자 순서가 곧 계약이다. 바꾸면 기존 저널 검증이 전부 깨진다.
 *
 * payload는 키 정렬·공백 제거로 정규화한 JSON을 쓴다. jsonb가 키 순서와 공백을
 * 보존하지 않아 원문 문자열 기준 체크섬은 write/read 왕복에서 일치하지 않는다.
 */
public final class JournalChecksum {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JournalChecksum() {
    }

    public static long of(long seq, CommandDraft draft) {
        String canonical = seq + "|" + draft.marketId()
                + "|" + draft.type()
                + "|" + draft.userId()
                + "|" + (draft.clientOrderId() == null ? "" : draft.clientOrderId())
                + "|" + draft.assignedOrderId()
                + "|" + draft.engineTs()
                + "|" + draft.tieBreak()
                + "|" + canonicalJson(draft.payloadJson());
        CRC32 crc = new CRC32();
        crc.update(canonical.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    static String canonicalJson(String json) {
        try {
            return MAPPER.writeValueAsString(sortKeys(MAPPER.readTree(json)));
        } catch (Exception e) {
            throw new IllegalArgumentException("payload가 유효한 JSON이 아니다", e);
        }
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = MAPPER.createObjectNode();
            for (String name : names) {
                sorted.set(name, sortKeys(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = MAPPER.createArrayNode();
            node.forEach(child -> sorted.add(sortKeys(child)));
            return sorted;
        }
        return node;
    }
}

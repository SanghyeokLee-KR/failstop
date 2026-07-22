package kr.exchange.api.community;

import kr.exchange.api.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 게시판 커서(게시판_설계.md §1.2). opaque base64url JSON. 경계 exclusive.
 *   latest : (created_at, id) 불변 축. 최신순 안정 커서.
 *   popular: (generation, rank_score, post_id) 스냅샷 세대 고정 축. 세대는 시퀀스 끝까지 유지한다.
 * 커서 페이로드는 opaque라 내부 BIGINT id 노출은 계약 표면이 아니다(왕복 전용).
 */
final class CommunityCursors {

    record Latest(OffsetDateTime createdAt, long id) {
    }

    record Popular(long generation, int rankScore, long postId) {
    }

    private CommunityCursors() {
    }

    static String encodeLatest(OffsetDateTime createdAt, long id) {
        String json = "{\"t\":\"" + createdAt.toInstant().toString() + "\",\"id\":" + id + "}";
        return b64(json);
    }

    static Latest decodeLatest(String cursor) {
        String json = fromB64(cursor);
        return new Latest(OffsetDateTime.parse(str(json, "t")), num(json, "id"));
    }

    static String encodePopular(long generation, int rankScore, long postId) {
        String json = "{\"g\":" + generation + ",\"r\":" + rankScore + ",\"id\":" + postId + "}";
        return b64(json);
    }

    static Popular decodePopular(String cursor) {
        String json = fromB64(cursor);
        return new Popular(num(json, "g"), (int) num(json, "r"), num(json, "id"));
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String fromB64(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw badCursor();
        }
    }

    private static String str(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw badCursor();
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw badCursor();
        }
        return json.substring(start, end);
    }

    private static long num(String json, String field) {
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw badCursor();
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            throw badCursor();
        }
    }

    private static ApiException badCursor() {
        return new ApiException(400, "invalid_parameter", "커서 형식이 올바르지 않습니다.");
    }
}

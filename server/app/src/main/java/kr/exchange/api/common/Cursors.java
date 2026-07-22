package kr.exchange.api.common;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * opaque 커서(§2-6). (ts, id)를 base64url JSON으로 감싼다. id 축은 외부 노출 식별자다
 * (주문=external_id, 체결=trade_id). 내부 BIGINT PK는 노출하지 않는다.
 */
public final class Cursors {

    public record Cursor(OffsetDateTime ts, String id) {
    }

    private Cursors() {
    }

    public static String encode(OffsetDateTime ts, String id) {
        // 커서 ts는 왕복 무손실이어야 한다. 표시용 포맷터(ApiFormats.time, .SSS 절단)를 쓰면
        // created_at 마이크로초가 잘려 같은 밀리초 경계 행이 다음 페이지에서 소실된다. 마이크로초
        // 정밀을 보존하는 Instant ISO로 직렬화한다.
        String json = "{\"ts\":\"" + ts.toInstant().toString() + "\",\"id\":\"" + id + "\"}";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String ts = extract(json, "ts");
            String id = extract(json, "id");
            return new Cursor(OffsetDateTime.parse(ts), id);
        } catch (Exception e) {
            throw new ApiException(400, "invalid_parameter", "커서 형식이 올바르지 않습니다.");
        }
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException(field + " 없음");
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}

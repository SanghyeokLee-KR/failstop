package kr.exchange.api.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 커서 페이지 조립(API 설계 §2-6, §2-2).
 *
 * 커서 목록마다 되풀이되던 세 조각을 한곳에 둔다. 커서 해독, 다음 쪽 판정, pagination
 * 엔벌로프다. 흩어져 있는 동안 각 목록이 자기 방식으로 상한을 정했고, 그래서 고정 LIMIT과
 * 엔벌로프 누락이 화면마다 다른 모양으로 남았다.
 *
 * 다음 쪽 판정은 한 건을 더 읽어서 한다. 총 개수 질의는 페이지마다 전 범위를 세므로 쓰지
 * 않고, "쪽이 꽉 찼으면 더 있다" 추정도 쓰지 않는다. 후자는 마지막 쪽이 정확히 꽉 찬
 * 경우를 더 있다고 보고해, 클라이언트가 빈 쪽을 한 번 더 읽게 만든다.
 */
public final class CursorPages {

    private CursorPages() {
    }

    /**
     * 한 행의 표시용 뷰와 커서 재료.
     *
     * ts를 뷰의 표시용 시각에서 되돌려 쓰지 않는다. 뷰는 밀리초로 절단하는데 컬럼은
     * 마이크로초라, 절단값으로 커서를 만들면 같은 밀리초 안의 행이 다음 쪽에서 누락된다
     * (Cursors.encode 주석과 같은 함정).
     */
    public record Row<T>(T view, OffsetDateTime ts, String id) {
    }

    public record Page<T>(List<T> data, String nextCursor, boolean hasMore) {
    }

    /** null·공백 커서는 첫 쪽이다. 형식 오류만 400으로 올린다 */
    public static Cursors.Cursor decode(String cursor) {
        return cursor == null || cursor.isBlank() ? null : Cursors.decode(cursor);
    }

    /** 내부 BIGINT를 id 축으로 쓰는 목록의 조작 커서 방어. 비숫자는 500이 아니라 400이다 */
    public static long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", "커서 형식 오류");
        }
    }

    /** 요청 상한. 1 미만과 max 초과를 잘라낸다 */
    public static int cap(int limit, int max) {
        return Math.max(1, Math.min(limit, max));
    }

    /**
     * limit + 1로 읽은 목록을 한 쪽으로 자른다.
     *
     * 호출부는 반드시 limit + 1을 요청해야 한다. limit만 읽고 넘기면 마지막 쪽을 항상
     * 마지막이 아닌 것으로 볼 수 없어 has_more가 늘 거짓이 된다.
     */
    public static <T> Page<T> slice(List<Row<T>> fetched, int limit) {
        boolean hasMore = fetched.size() > limit;
        List<Row<T>> kept = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = null;
        if (hasMore && !kept.isEmpty()) {
            Row<T> last = kept.get(kept.size() - 1);
            nextCursor = Cursors.encode(last.ts(), last.id());
        }
        List<T> data = new ArrayList<>(kept.size());
        for (Row<T> row : kept) {
            data.add(row.view());
        }
        return new Page<>(data, nextCursor, hasMore);
    }

    /** §2-2. 페이지네이션이 없는 리스트도 두 필드는 리터럴로 존재한다 */
    public static Map<String, Object> pagination(String nextCursor, boolean hasMore) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("next_cursor", nextCursor);
        p.put("has_more", hasMore);
        return p;
    }

    public static Map<String, Object> pagination(Page<?> page) {
        return pagination(page.nextCursor(), page.hasMore());
    }

    /** 커서를 발급하지 않는 유계 목록용. 필드를 빼지 않고 리터럴로 채운다 */
    public static Map<String, Object> noPagination() {
        return pagination(null, false);
    }

    /**
     * JDBC가 돌려준 시각 값을 OffsetDateTime으로 맞춘다.
     *
     * listOfRows()는 컬럼 타입을 드라이버가 정하는 대로 담는다. TIMESTAMPTZ가
     * java.sql.Timestamp로 오는 경로가 있어 직캐스팅은 500이 된다. Timestamp는 시간대가
     * 없으므로 UTC로 올린다. 커서는 순서용이라 절대시각만 맞으면 된다.
     */
    public static OffsetDateTime at(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        throw new IllegalStateException("시각 타입 미지원: " + value);
    }
}

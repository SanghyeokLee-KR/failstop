package kr.exchange.api.notice;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.ClientIp;
import kr.exchange.api.common.Cursors;
import kr.exchange.api.common.SlidingWindowThrottle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공지 조회(API 설계 §4-F). 읽기 전용 공개 경로.
 *
 * 쓰기는 관리 평면(ConfigAdminController)이 단독으로 갖는다. 여기서 발행 경로를 열면
 * 같은 테이블에 인증 강도가 다른 두 입구가 생긴다.
 *
 * PublicMarketPaths에 넣지 않고 자기 스로틀을 든다. 그 목록은 §4-A 시장 데이터 전용이고
 * weight 규칙이 시장 데이터 응답 모양을 전제한다(그 클래스 주석의 명문 규칙). 공지는
 * 시장 데이터가 아니므로 데모 세션·게시판·회원 인증 평면과 같은 방식을 따른다.
 */
@RestController
public class NoticeController {

    /** 화면 진입마다 1회 부르는 조회다. 폴링이 아니라 여유를 두되 무인증이라 상한은 둔다 */
    private final SlidingWindowThrottle throttle =
            new SlidingWindowThrottle(60, 60_000, "notices");

    private final JdbcClient jdbc;

    public NoticeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** §2-6 count 상한과 같다. 무인증 경로라 한 번에 퍼가는 양을 묶는다 */
    private static final int MAX_LIMIT = 200;

    @GetMapping("/api/v1/notices")
    public Map<String, Object> notices(HttpServletRequest request,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "50") int limit) {
        throttle.check(ClientIp.of(request));
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        Cursors.Cursor decoded = cursor == null || cursor.isBlank() ? null : Cursors.decode(cursor);

        StringBuilder sql = new StringBuilder("""
                SELECT id, title, body, severity, published_at
                FROM notices
                WHERE active = true
                """);
        List<Object> params = new ArrayList<>();
        if (decoded != null) {
            /* 커서의 ts를 그대로 바인딩하지 않는다. 보존 정밀도와 컬럼 정밀도가 어긋나면
               같은 시각 경계 행이 다음 쪽에서 사라진다. 실제 행 값을 다시 읽어 쓴다.
               앵커를 active로 제한하지 않는 이유는 철회된 공지를 커서로 받았을 때
               그 지점부터 이어 읽는 것이 맞기 때문이다. 읽던 중 운영자가 한 건을 내렸다고
               목록의 나머지가 통째로 400이 되면 안 된다 */
            Anchor anchor = jdbc.sql("SELECT published_at, id FROM notices WHERE id = ?")
                    .params(parseCursorId(decoded.id()))
                    .query((rs, n) -> new Anchor(
                            rs.getObject("published_at", OffsetDateTime.class), rs.getLong("id")))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (published_at, id) < (?, ?)");
            params.add(anchor.publishedAt());
            params.add(anchor.id());
        }
        // 한 건 더 읽어 다음 쪽 존재를 판단한다. LIMIT 50 고정은 51번째 공지를 존재하지도
        // 않는 것처럼 지웠다. 공지는 삭제되지 않고 철회만 되므로(V5 §7 DELETE 미부여)
        // 활성 공지가 상한을 넘는 상태가 실제로 온다
        sql.append(" ORDER BY published_at DESC, id DESC LIMIT ?");
        params.add(capped + 1);

        /* active = false는 철회다. 보관이 아니므로 사용자에게 내보내지 않는다.
           published_by는 운영 정보라 싣지 않는다. 누가 썼는지는 사용자 관심사가 아니고,
           운영자 식별자가 공개 경로로 새면 관리 평면 표면이 넓어진다 */
        List<Row> rows = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(rs.getLong("id")));
                    row.put("title", rs.getString("title"));
                    row.put("body", rs.getString("body") == null ? "" : rs.getString("body"));
                    row.put("severity", rs.getString("severity"));
                    OffsetDateTime at = rs.getObject("published_at", OffsetDateTime.class);
                    row.put("published_at", at == null ? null : at.toInstant().toEpochMilli());
                    return new Row(row, at, String.valueOf(rs.getLong("id")));
                })
                .list();

        boolean hasMore = rows.size() > capped;
        List<Row> page = hasMore ? rows.subList(0, capped) : rows;
        String nextCursor = null;
        if (hasMore) {
            Row last = page.get(page.size() - 1);
            nextCursor = Cursors.encode(last.publishedAt(), last.id());
        }
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("next_cursor", nextCursor);
        pagination.put("has_more", hasMore);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("data", page.stream().map(Row::view).toList());
        body.put("pagination", pagination);
        return body;
    }

    /**
     * 한 행의 표시용 뷰와 커서 재료.
     *
     * 커서의 id 축은 notices.id, 곧 내부 BIGINT PK다. Cursors의 "내부 PK 미노출" 원칙에
     * 대한 예외인 이유는 이 경로가 이미 같은 값을 data[].id로 내보내고 있기 때문이다.
     * 공지는 사용자별로 갈리지 않는 전역 공개 자원이라 id가 새어도 남의 것을 세거나
     * 정렬 위치를 캐는 표면이 없다. 사용자 스코프가 있는 주문·입출금·문의와 다르다.
     */
    private record Row(Map<String, Object> view, OffsetDateTime publishedAt, String id) {
    }

    private record Anchor(OffsetDateTime publishedAt, long id) {
    }

    /** 조작 커서 방어(§2-6). 비숫자는 500이 아니라 400으로 정규화한다 */
    private static long parseCursorId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid_parameter", "커서 형식 오류");
        }
    }
}

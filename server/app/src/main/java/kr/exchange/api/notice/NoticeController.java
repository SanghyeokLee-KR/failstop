package kr.exchange.api.notice;

import jakarta.servlet.http.HttpServletRequest;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.ClientIp;
import kr.exchange.api.common.SlidingWindowThrottle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
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

    @GetMapping("/api/v1/notices")
    public Map<String, Object> notices(HttpServletRequest request) {
        throttle.check(ClientIp.of(request));
        /* active = false는 철회다. 보관이 아니므로 사용자에게 내보내지 않는다.
           published_by는 운영 정보라 싣지 않는다. 누가 썼는지는 사용자 관심사가 아니고,
           운영자 식별자가 공개 경로로 새면 관리 평면 표면이 넓어진다 */
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, title, body, severity, published_at
                        FROM notices
                        WHERE active = true
                        ORDER BY published_at DESC
                        LIMIT 50
                        """)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(rs.getLong("id")));
                    row.put("title", rs.getString("title"));
                    row.put("body", rs.getString("body") == null ? "" : rs.getString("body"));
                    row.put("severity", rs.getString("severity"));
                    OffsetDateTime at = rs.getObject("published_at", OffsetDateTime.class);
                    row.put("published_at", at == null ? null : at.toInstant().toEpochMilli());
                    return row;
                })
                .list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema_version", ApiFormats.SCHEMA_VERSION);
        body.put("data", rows);
        return body;
    }
}

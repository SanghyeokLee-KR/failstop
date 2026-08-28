package kr.exchange.api.support;

import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 문의(API 설계 §4-G).
 *
 * 자금을 움직이지 않으므로 원장 경로와 무관하다. 트랜잭션은 답변 등록에만 쓴다. 조회와
 * 상태 검사 사이에 다른 운영자가 먼저 답하면 답변이 덮어써지기 때문이다.
 */
public class SupportService {

    /** 쪽 크기 상한(§4-G). 컨트롤러가 여기에 맞춰 자르고, 다음 쪽 판정용 +1은 상한 밖이다 */
    public static final int MAX_LIMIT = 100;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public SupportService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /* ===== 회원 ===== */

    public Map<String, Object> create(long userId, String category, String title, String body) {
        String cat = SupportInputs.category(category);
        String t = SupportInputs.title(title);
        String b = SupportInputs.body(body);
        long id = jdbc.sql("""
                        INSERT INTO support_inquiries (user_id, category, title, body)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """)
                .params(userId, cat, t, b)
                .query(Long.class)
                .single();
        return view(loadById(id), false);
    }

    /**
     * 내 문의 한 쪽(§2-6). 입출금 목록(TransferService.listMine)과 같은 규약이다.
     *
     * 정렬 축은 (created_at, id)다. id 단독 내림차순은 겹친 트랜잭션에서 created_at
     * 순서와 어긋난다. 늦게 시작해 먼저 커밋한 문의가 작은 id를 받으므로 화면의 시각
     * 정렬과 쪽 경계가 갈린다.
     *
     * 커서 행은 user_id 스코프로 되짚는다. 남의 external_id를 커서로 넘겨 정렬 위치를
     * 캐는 경로를 막는다. 커서가 가리키는 행이 없으면 형식 오류와 같은 400이다.
     */
    public List<CursorPages.Row<Map<String, Object>>> listMine(long userId, Cursors.Cursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM support_inquiries WHERE user_id = ?");
        List<Object> params = new ArrayList<>(List.of(userId));
        if (cursor != null) {
            // 커서의 ts를 그대로 바인딩하지 않는다. 보존 정밀도와 컬럼 정밀도가 어긋나면
            // 같은 시각 경계 행이 다음 쪽에서 사라진다. 실제 행 값을 다시 읽어 쓴다
            Anchor anchor = jdbc.sql(
                            "SELECT created_at, id FROM support_inquiries WHERE user_id = ? AND external_id = ?")
                    .params(userId, parseCursorId(cursor.id()))
                    .query((rs, rn) -> new Anchor(
                            rs.getObject("created_at", OffsetDateTime.class), rs.getLong("id")))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (created_at, id) < (?, ?)");
            params.add(anchor.createdAt());
            params.add(anchor.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query()
                .listOfRows()
                .stream()
                .map(row -> new CursorPages.Row<>(
                        view(row, false),
                        CursorPages.at(row.get("created_at")),
                        String.valueOf(row.get("external_id"))))
                .toList();
    }


    private record Anchor(OffsetDateTime createdAt, long id) {
    }

    /** 조작 커서 방어(§2-6). 비UUID는 500이 아니라 400으로 정규화한다 */
    private static UUID parseCursorId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid_parameter", "커서 형식 오류");
        }
    }


    /* ===== 운영자 ===== */

    /**
     * 운영자 큐 한 쪽. 오래된 문의부터 처리한다.
     *
     * 정렬 축은 (created_at, id) 오름차순이다. created_at 단독은 유일하지 않아 같은 시각
     * 문의 둘 중 어느 쪽이 상한에 잘릴지 비결정이고, 운영자가 새로고침할 때마다 큐 끝이
     * 바뀐다. AuditController가 같은 이유로 id 보조 축을 명문화해뒀다.
     *
     * status=ANSWERED 조회는 답변 뒤에도 남아 영구 누적이다.
     */
    public List<CursorPages.Row<Map<String, Object>>> listQueue(String status,
                                                                Cursors.Cursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.*, u.email AS user_email
                FROM support_inquiries s
                JOIN users u ON u.id = s.user_id
                WHERE s.status = ?
                """);
        List<Object> params = new ArrayList<>(List.of(status));
        if (cursor != null) {
            // 큐는 오름차순이라 경계 비교도 반대 방향이다
            Anchor anchor = jdbc.sql(
                            "SELECT created_at, id FROM support_inquiries WHERE external_id = ?")
                    .params(parseCursorId(cursor.id()))
                    .query((rs, rn) -> new Anchor(
                            rs.getObject("created_at", OffsetDateTime.class), rs.getLong("id")))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (s.created_at, s.id) > (?, ?)");
            params.add(anchor.createdAt());
            params.add(anchor.id());
        }
        sql.append(" ORDER BY s.created_at, s.id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
                .params(params)
                .query()
                .listOfRows()
                .stream()
                .map((row) -> {
                    Map<String, Object> v = view(row, true);
                    // 운영자는 누구의 문의인지 알아야 이력을 본다. 사용자 응답에는 넣지 않는다
                    v.put("user_email", row.get("user_email"));
                    return new CursorPages.Row<>(v, CursorPages.at(row.get("created_at")),
                            String.valueOf(row.get("external_id")));
                })
                .toList();
    }

    /**
     * 답변 등록. FOR UPDATE가 없으면 두 운영자가 같은 문의를 동시에 열었을 때 나중 답변이
     * 먼저 답변을 조용히 덮어쓴다. 사용자는 둘 중 하나만 보고, 어느 쪽인지 알 수 없다.
     */
    public Map<String, Object> answer(String externalId, String operatorId, String answerRaw) {
        String text = SupportInputs.answer(answerRaw);
        return tx.execute(status -> {
            Map<String, Object> row = jdbc.sql("""
                            SELECT * FROM support_inquiries
                            WHERE external_id = ?::uuid FOR UPDATE
                            """)
                    .params(externalId)
                    .query()
                    .listOfRows()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new ApiException(404, "not_found", "문의 없음"));
            if (!"OPEN".equals(row.get("status"))) {
                throw new ApiException(409, "support_already_answered", "이미 답변된 문의");
            }
            long id = ((Number) row.get("id")).longValue();
            jdbc.sql("""
                            UPDATE support_inquiries
                            SET status = 'ANSWERED', answer = ?, answered_by = ?, answered_at = now()
                            WHERE id = ?
                            """)
                    .params(text, operatorId, id)
                    .update();
            return view(loadById(id), true);
        });
    }

    /* ===== 내부 ===== */

    private Map<String, Object> loadById(long id) {
        return jdbc.sql("SELECT * FROM support_inquiries WHERE id = ?")
                .params(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("방금 쓴 문의 행이 사라짐: " + id));
    }

    /**
     * 내부 PK는 노출하지 않는다. 답변자 식별자는 운영자 정보라 사용자 응답에서 뺀다.
     * 누가 답했는지는 사용자 관심사가 아니고, 운영자 식별자가 새면 관리 평면 표면이 넓어진다.
     */
    private Map<String, Object> view(Map<String, Object> row, boolean forOperator) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("external_id", String.valueOf(row.get("external_id")));
        v.put("category", row.get("category"));
        v.put("title", row.get("title"));
        v.put("body", row.get("body"));
        v.put("status", row.get("status"));
        v.put("answer", row.get("answer"));
        v.put("answered_at", epochMillis(row.get("answered_at")));
        v.put("created_at", epochMillis(row.get("created_at")));
        if (forOperator) {
            v.put("answered_by", row.get("answered_by"));
        }
        return v;
    }

    private static Long epochMillis(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant().toEpochMilli();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toEpochMilli();
        }
        return null;
    }
}

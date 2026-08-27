package kr.exchange.api.support;

import kr.exchange.api.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 문의(API 설계 §4-G).
 *
 * 자금을 움직이지 않으므로 원장 경로와 무관하다. 트랜잭션은 답변 등록에만 쓴다. 조회와
 * 상태 검사 사이에 다른 운영자가 먼저 답하면 답변이 덮어써지기 때문이다.
 */
public class SupportService {

    private static final int MAX_LIMIT = 100;

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

    public List<Map<String, Object>> listMine(long userId, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return jdbc.sql("""
                        SELECT * FROM support_inquiries
                        WHERE user_id = ?
                        ORDER BY id DESC LIMIT ?
                        """)
                .params(userId, capped)
                .query()
                .listOfRows()
                .stream()
                .map((row) -> view(row, false))
                .toList();
    }

    /* ===== 운영자 ===== */

    public List<Map<String, Object>> listQueue(String status, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT * 2));
        return jdbc.sql("""
                        SELECT s.*, u.email AS user_email
                        FROM support_inquiries s
                        JOIN users u ON u.id = s.user_id
                        WHERE s.status = ?
                        ORDER BY s.created_at
                        LIMIT ?
                        """)
                .params(status, capped)
                .query()
                .listOfRows()
                .stream()
                .map((row) -> {
                    Map<String, Object> v = view(row, true);
                    // 운영자는 누구의 문의인지 알아야 이력을 본다. 사용자 응답에는 넣지 않는다
                    v.put("user_email", row.get("user_email"));
                    return v;
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

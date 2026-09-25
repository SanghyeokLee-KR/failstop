package kr.exchange.admin.msp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import kr.exchange.admin.AdminAuditService;
import kr.exchange.admin.ApprovalService;
import kr.exchange.api.common.ApiException;
import kr.exchange.api.common.ApiFormats;
import kr.exchange.api.common.CursorPages;
import kr.exchange.api.common.Cursors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 임계값 레지스트리(설계 5절, DB 설계 §10.2). 사람의 변경과 되돌리기는 SUPER 와 승인 티켓(ops.threshold.set)을
 * 거치고, 티켓 소각·행 잠금·갱신·이력·감사가 한 트랜잭션이다. 검증이 실패하면 소각도 롤백돼 티켓이 남는다.
 * 값은 exchange_ops_threshold 게이지로 내보내 경보 규칙이 참조한다. 미설정 항목은 NaN 으로 낸다.
 */
public class OpsThresholdService {

    private static final Logger log = LoggerFactory.getLogger(OpsThresholdService.class);

    public static final String GAUGE = "exchange_ops_threshold";
    public static final String SCALE_OUT = "read.scale_out_cpu";
    public static final String SCALE_IN = "read.scale_in_cpu";
    public static final String APPROVAL_ACTION = "ops.threshold.set";

    // 정책 T3. 축소 임계는 증설 임계보다 이만큼 낮아야 한다(DB 트리거와 같은 값).
    static final BigDecimal READ_GAP = new BigDecimal("0.15");
    // NUMERIC(12,4) 는 더 긴 소수를 조용히 반올림한다. 티켓이 승인한 값과 저장값이 달라지지 않게 먼저 거부한다.
    static final int MAX_SCALE = 4;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^\\d{1,8}(\\.\\d+)?$");
    private static final int MAX_REASON = 500;

    public record Item(String key, String unit, BigDecimal value, BigDecimal defaultValue, BigDecimal min,
                       BigDecimal max, BigDecimal aiMaxStep, long version, String updatedBy,
                       OffsetDateTime updatedAt) {
    }

    public record Change(long id, String key, BigDecimal from, BigDecimal to, long version, String via,
                         String changedBy, Long approvalId, Long aiActionId, Long revertOf, String reason,
                         OffsetDateTime changedAt) {
    }

    public record Applied(String key, BigDecimal from, BigDecimal to, long version, long changeId, String via,
                          Long approvalId, Long revertOf) {
    }

    /** 용량 권고가 읽는 두 조회 임계. 초기값이 있는 항목이라 미설정이 될 수 없다(CHECK). */
    public record ReadThresholds(BigDecimal scaleOut, BigDecimal scaleIn) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApprovalService approvals;
    private final AdminAuditService audit;
    private final MultiGauge gauge;
    // MultiGauge.register 는 동시 호출에 안전하지 않고, 변경 커밋 직후의 갱신과 주기 갱신이 겹칠 수 있다.
    private final Object gaugeLock = new Object();

    public OpsThresholdService(JdbcClient jdbc, TransactionTemplate tx, ApprovalService approvals,
                               AdminAuditService audit, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.approvals = approvals;
        this.audit = audit;
        this.gauge = MultiGauge.builder(GAUGE)
                .description("관제센터 임계값 레지스트리의 현재값. 미설정 항목은 NaN")
                .register(registry);
    }

    public List<Item> items() {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM ops_thresholds ORDER BY key")
                .query((rs, n) -> item(rs))
                .list();
    }

    public Optional<Item> item(String key) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM ops_thresholds WHERE key = ?")
                .param(key)
                .query((rs, n) -> item(rs))
                .optional();
    }

    public ReadThresholds readThresholds() {
        return new ReadThresholds(item(SCALE_OUT).map(Item::value).orElse(null),
                item(SCALE_IN).map(Item::value).orElse(null));
    }

    /** 사람의 변경(API §6-F). 사람에게는 AI 1회 폭과 쿨다운을 걸지 않는다. */
    public Applied set(String key, String rawValue, String reason, String operatorId, String approvalToken,
                       String requestId) {
        BigDecimal value = parseValue(rawValue);
        String why = reason(reason);
        Applied applied = tx.execute(status -> {
            Item item = item(key).orElseThrow(() -> unknownKey(key));
            requireInRange(item, value);
            long approvalId = approvals.consume(operatorId, approvalToken, APPROVAL_ACTION, target(key, value));
            Map<String, Item> locked = lockForWrite(key);
            checkReadGap(key, value, locked);
            Applied result = write(locked.get(key), value, "MANUAL", operatorId, approvalId, null, why, null);
            audit.record(operatorId, APPROVAL_ACTION, key, requestId, approvalId, "OK", detail(result));
            return result;
        });
        refreshGauge();
        return applied;
    }

    /**
     * 그 항목의 최신 변경만 무른다. 직전 값으로 다시 쓰는 변경이고 티켓 대상도 직전 값이다(미설정이면 null).
     * 초기값(SEED)은 되돌리기 대상이 아니다.
     */
    public Applied revert(long changeId, String reason, String operatorId, String approvalToken, String requestId) {
        String why = reason(reason);
        Applied applied = tx.execute(status -> {
            Change change = change(changeId).orElseThrow(() -> new ApiException(404, "not_found",
                    "존재하지 않는 임계값 변경입니다.", Map.of("reason", "unknown_change"), null));
            if ("SEED".equals(change.via())) {
                throw revertConflict("초기값은 되돌릴 수 없습니다.", "seed");
            }
            Item item = item(change.key()).orElseThrow(() -> unknownKey(change.key()));
            if (change.from() == null && item.defaultValue() != null) {
                throw revertConflict("초기값이 있는 항목은 미설정으로 되돌릴 수 없습니다.", "seeded_unset");
            }
            long approvalId = approvals.consume(operatorId, approvalToken, APPROVAL_ACTION,
                    target(change.key(), change.from()));
            return revertLocked(change, "REVERT", operatorId, approvalId, null, why, requestId);
        });
        refreshGauge();
        return applied;
    }

    /**
     * AI 조치의 임계값 변경(설계 7-7). 자동 실행은 via=AI_AUTO, 사람의 승인 실행은 AI_APPROVED 이고 승인 티켓은
     * 호출부가 같은 트랜잭션에서 소각한다. 호출부의 트랜잭션에 합류하고, 게이지는 호출부가 커밋 뒤에 맞춘다.
     */
    public Applied applyForAi(String key, BigDecimal value, String via, String actor, Long approvalId, long aiActionId,
                              String reason, String requestId) {
        return tx.execute(status -> {
            Item item = item(key).orElseThrow(() -> unknownKey(key));
            requireInRange(item, value);
            Map<String, Item> locked = lockForWrite(key);
            checkReadGap(key, value, locked);
            Applied result = write(locked.get(key), value, via, actor, approvalId, null, reason(reason), aiActionId);
            Map<String, Object> detail = detail(result);
            detail.put("ai_action_id", aiActionId);
            audit.record(actor, APPROVAL_ACTION, key, requestId, approvalId, "OK", detail);
            return result;
        });
    }

    /**
     * AI 조치의 실행 트랜잭션이 검사 전에 쓸 행을 잠그고 현재값을 읽는다(설계 7-7, 정책 C5). 조회 임계는 T3 때문에 두
     * 행이다. 호출부의 트랜잭션 안에서 불러야 잠금이 실행까지 이어진다.
     */
    public Map<String, BigDecimal> lockForAi(String key) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        lockForWrite(key).forEach((k, item) -> values.put(k, item.value()));
        return values;
    }

    /**
     * AI 조치로 바꾼 값을 무른다. 그 변경이 항목의 최신 변경이고 T3 을 지킬 때만 된다. 자동 되돌림은 via=AUTO_REVERT,
     * 사람의 되돌리기는 REVERT 이고 승인 티켓은 호출부가 소각한다.
     */
    public Applied revertForAi(long changeId, String via, String actor, Long approvalId, long aiActionId, String reason,
                               String requestId) {
        return tx.execute(status -> {
            Change change = change(changeId).orElseThrow(() -> new ApiException(404, "not_found",
                    "존재하지 않는 임계값 변경입니다.", Map.of("reason", "unknown_change"), null));
            return revertLocked(change, via, actor, approvalId, aiActionId, reason(reason), requestId);
        });
    }

    private Applied revertLocked(Change change, String via, String actor, Long approvalId, Long aiActionId,
                                 String reason, String requestId) {
        Map<String, Item> locked = lockForWrite(change.key());
        Long latest = jdbc.sql("SELECT max(version) FROM ops_threshold_changes WHERE key = ?")
                .param(change.key()).query(Long.class).single();
        Item current = locked.get(change.key());
        if (latest != change.version() || !same(current.value(), change.to())) {
            throw revertConflict("그 항목의 최신 변경만 되돌릴 수 있습니다.", "not_latest");
        }
        checkReadGap(change.key(), change.from(), locked);
        Applied result = write(current, change.from(), via, actor, approvalId, change.id(), reason, aiActionId);
        Map<String, Object> detail = detail(result);
        if (aiActionId != null) {
            detail.put("ai_action_id", aiActionId);
        }
        audit.record(actor, "ops.threshold.revert", change.key(), requestId, approvalId, "OK", detail);
        return result;
    }

    /**
     * 설정의 조회 임계를 환경 기본값으로 맞춘다(설계 5-4). 마지막 변경이 초기값이나 설정 적용인 항목에만
     * 쓰고, 사람이나 AI 가 한 번이라도 바꾼 항목은 그대로 둔다. 설정 쌍 자체가 범위나 T3 을 어기면 설정
     * 오류라 기동을 멈춘다.
     */
    public void applyConfigDefaults(BigDecimal scaleOut, BigDecimal scaleIn) {
        Map<String, BigDecimal> configured = new LinkedHashMap<>();
        configured.put(SCALE_OUT, scaleOut);
        configured.put(SCALE_IN, scaleIn);
        for (Map.Entry<String, BigDecimal> entry : configured.entrySet()) {
            Item item = item(entry.getKey()).orElseThrow(() -> new IllegalStateException(
                    "임계값 레지스트리에 " + entry.getKey() + " 항목이 없습니다. V70 적용을 확인해야 합니다."));
            if (entry.getValue().stripTrailingZeros().scale() > MAX_SCALE
                    || entry.getValue().compareTo(item.min()) < 0 || entry.getValue().compareTo(item.max()) > 0) {
                throw new IllegalStateException("exchange.capacity 의 " + entry.getKey() + " 설정값 "
                        + ApiFormats.amount(entry.getValue()) + " 이 레지스트리 허용 범위("
                        + ApiFormats.amount(item.min()) + "~" + ApiFormats.amount(item.max()) + ") 밖입니다.");
            }
        }
        if (violatesReadGap(scaleOut, scaleIn)) {
            throw new IllegalStateException("exchange.capacity 의 축소 임계 " + ApiFormats.amount(scaleIn)
                    + " 는 증설 임계 " + ApiFormats.amount(scaleOut) + " 보다 0.15 이상 낮아야 합니다(정책 T3).");
        }
        tx.executeWithoutResult(status -> {
            Map<String, Item> locked = lockForWrite(SCALE_OUT);
            Map<String, BigDecimal> next = new LinkedHashMap<>();
            for (String key : configured.keySet()) {
                String lastVia = jdbc.sql("SELECT via FROM ops_threshold_changes WHERE key = ? "
                                + "ORDER BY version DESC LIMIT 1")
                        .param(key).query(String.class).single();
                boolean untouched = "SEED".equals(lastVia) || "CONFIG".equals(lastVia);
                next.put(key, untouched ? configured.get(key) : locked.get(key).value());
            }
            if (violatesReadGap(next.get(SCALE_OUT), next.get(SCALE_IN))) {
                log.warn("조회 임계 설정값을 적용하지 않습니다. 사람이 바꾼 값과 합치면 T3 을 어깁니다: out={}, in={}",
                        next.get(SCALE_OUT), next.get(SCALE_IN));
                return;
            }
            for (String key : configured.keySet()) {
                Item current = locked.get(key);
                if (!same(current.value(), next.get(key))) {
                    write(current, next.get(key), "CONFIG", "config:exchange.capacity", null, null,
                            "환경 기본값(exchange.capacity) 적용", null);
                }
            }
        });
        refreshGauge();
    }

    /**
     * 게이지를 표에 맞춘다. 기동 때, 변경 커밋 직후, 주기적으로 부른다. 실패하면 직전 값을 그대로 둔다.
     * 읽기와 등록을 한 잠금 안에서 해 먼저 읽은 옛 값이 나중 등록으로 새 값을 덮지 않게 한다.
     */
    public void refreshGauge() {
        synchronized (gaugeLock) {
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            try {
                for (Item item : items()) {
                    rows.add(MultiGauge.Row.of(Tags.of("key", item.key(), "unit", item.unit()),
                            item.value() == null ? Double.NaN : item.value().doubleValue()));
                }
            } catch (RuntimeException e) {
                log.warn("임계값 게이지 갱신 실패. 직전 값을 유지합니다: {}", e.toString());
                return;
            }
            gauge.register(rows, true);
        }
    }

    public Optional<Change> change(long id) {
        return jdbc.sql("SELECT " + CHANGE_COLUMNS + " FROM ops_threshold_changes WHERE id = ?")
                .param(id)
                .query((rs, n) -> change(rs))
                .optional();
    }

    /** 변경 이력(변경 시각 역순). 호출부는 limit + 1 을 넘겨 다음 쪽을 판정한다. */
    public List<CursorPages.Row<Change>> changes(String key, Cursors.Cursor cursor, int fetch) {
        StringBuilder sql = new StringBuilder("SELECT " + CHANGE_COLUMNS + " FROM ops_threshold_changes WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (key != null) {
            sql.append(" AND key = ?");
            params.add(key);
        }
        if (cursor != null) {
            long anchorId = CursorPages.numericId(cursor.id());
            OffsetDateTime anchorAt = jdbc.sql("SELECT changed_at FROM ops_threshold_changes WHERE id = ?")
                    .param(anchorId)
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                    .optional()
                    .orElseThrow(() -> new ApiException(400, "invalid_parameter", "커서 형식 오류"));
            sql.append(" AND (changed_at, id) < (?, ?)");
            params.add(anchorAt);
            params.add(anchorId);
        }
        sql.append(" ORDER BY changed_at DESC, id DESC LIMIT ?");
        params.add(fetch);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> {
                    Change c = change(rs);
                    return new CursorPages.Row<>(c, c.changedAt(), String.valueOf(c.id()));
                })
                .list();
    }

    /* ===== 값 규칙 ===== */

    /** 평문 십진 표기만 받는다. 소수 넷째 자리를 넘으면 반올림하지 않고 거부한다(API §6-F constraint=scale). */
    static BigDecimal parseValue(String raw) {
        if (raw == null || !PLAIN_DECIMAL.matcher(raw.trim()).matches()) {
            throw invalid("값은 음수가 아닌 평문 십진 표기여야 합니다.", "format", "value");
        }
        BigDecimal value = new BigDecimal(raw.trim());
        if (value.stripTrailingZeros().scale() > MAX_SCALE) {
            throw invalid("값은 소수 넷째 자리까지만 받습니다.", "scale", "value");
        }
        return value;
    }

    /** 티켓 대상 {key}:{value}. 끝의 0을 지운 평문 십진 표기이고 미설정은 null 이다. */
    static String target(String key, BigDecimal value) {
        return key + ":" + (value == null ? "null" : ApiFormats.amount(value));
    }

    static boolean violatesReadGap(BigDecimal scaleOut, BigDecimal scaleIn) {
        return scaleOut != null && scaleIn != null && scaleIn.compareTo(scaleOut.subtract(READ_GAP)) > 0;
    }

    private static void checkReadGap(String key, BigDecimal value, Map<String, Item> locked) {
        if (!SCALE_OUT.equals(key) && !SCALE_IN.equals(key)) {
            return;
        }
        BigDecimal out = SCALE_OUT.equals(key) ? value : locked.get(SCALE_OUT).value();
        BigDecimal in = SCALE_IN.equals(key) ? value : locked.get(SCALE_IN).value();
        if (violatesReadGap(out, in)) {
            throw new ApiException(409, "msp_policy_denied",
                    "축소 임계는 증설 임계보다 0.15 이상 낮아야 합니다.",
                    Map.of("rule", "T3", "scale_out_cpu", ApiFormats.amount(out),
                            "scale_in_cpu", ApiFormats.amount(in)), null);
        }
    }

    private static void requireInRange(Item item, BigDecimal value) {
        if (value.compareTo(item.min()) < 0 || value.compareTo(item.max()) > 0) {
            throw new ApiException(400, "invalid_parameter",
                    item.key() + " 는 " + ApiFormats.amount(item.min()) + "~" + ApiFormats.amount(item.max())
                            + " 사이여야 합니다.",
                    Map.of("constraint", "range", "field", "value"), null);
        }
    }

    /* ===== 쓰기 ===== */

    /**
     * 쓸 행을 잠근다. 조회 임계는 T3 이 두 행을 함께 보므로 둘 다 키 순서로 잠근다. 사람의 변경과 설정 적용,
     * 이후의 AI 조치가 같은 순서로 잠가 서로 교착하지 않는다.
     */
    private Map<String, Item> lockForWrite(String key) {
        List<String> keys = SCALE_OUT.equals(key) || SCALE_IN.equals(key) ? List.of(SCALE_IN, SCALE_OUT) : List.of(key);
        Map<String, Item> locked = new LinkedHashMap<>();
        for (String k : keys) {
            Item item = jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM ops_thresholds WHERE key = ? FOR UPDATE")
                    .param(k)
                    .query((rs, n) -> item(rs))
                    .optional()
                    .orElseThrow(() -> unknownKey(k));
            locked.put(k, item);
        }
        return locked;
    }

    private Applied write(Item current, BigDecimal value, String via, String actor, Long approvalId, Long revertOf,
                          String reason, Long aiActionId) {
        long version = jdbc.sql("""
                        UPDATE ops_thresholds SET value = ?, version = version + 1, updated_by = ?, updated_at = now()
                        WHERE key = ? RETURNING version
                        """)
                .params(value, actor, current.key())
                .query(Long.class).single();
        long changeId = jdbc.sql("""
                        INSERT INTO ops_threshold_changes
                            (key, from_value, to_value, version, via, changed_by, approval_id, ai_action_id, revert_of,
                             reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(current.key(), current.value(), value, version, via, actor, approvalId, aiActionId, revertOf,
                        reason)
                .query(Long.class).single();
        return new Applied(current.key(), current.value(), value, version, changeId, via, approvalId, revertOf);
    }

    private static Map<String, Object> detail(Applied a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", a.from() == null ? null : ApiFormats.amount(a.from()));
        m.put("to", a.to() == null ? null : ApiFormats.amount(a.to()));
        m.put("version", a.version());
        m.put("change_id", a.changeId());
        m.put("via", a.via());
        if (a.revertOf() != null) {
            m.put("revert_of", a.revertOf());
        }
        return m;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String reason(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.codePointCount(0, text.length()) > MAX_REASON) {
            throw invalid("reason 은 최대 " + MAX_REASON + "자입니다.", "length", "reason");
        }
        return text;
    }

    private static ApiException invalid(String message, String constraint, String field) {
        return new ApiException(400, "invalid_parameter", message,
                Map.of("constraint", constraint, "field", field), null);
    }

    private static ApiException unknownKey(String key) {
        return new ApiException(404, "not_found", "존재하지 않는 임계값 항목입니다: " + key,
                Map.of("reason", "unknown_threshold"), null);
    }

    private static ApiException revertConflict(String message, String reason) {
        return new ApiException(409, "threshold_revert_conflict", message, Map.of("reason", reason), null);
    }

    /* ===== 행 매핑 ===== */

    private static final String ITEM_COLUMNS =
            "key, unit, value, default_value, min_value, max_value, ai_max_step, version, updated_by, updated_at";

    private static final String CHANGE_COLUMNS = "id, key, from_value, to_value, version, via, changed_by, "
            + "approval_id, ai_action_id, revert_of, reason, changed_at";

    private static Item item(ResultSet rs) throws SQLException {
        return new Item(rs.getString("key"), rs.getString("unit"), rs.getBigDecimal("value"),
                rs.getBigDecimal("default_value"), rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value"),
                rs.getBigDecimal("ai_max_step"), rs.getLong("version"), rs.getString("updated_by"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static Change change(ResultSet rs) throws SQLException {
        return new Change(rs.getLong("id"), rs.getString("key"), rs.getBigDecimal("from_value"),
                rs.getBigDecimal("to_value"), rs.getLong("version"), rs.getString("via"), rs.getString("changed_by"),
                nullableLong(rs, "approval_id"), nullableLong(rs, "ai_action_id"), nullableLong(rs, "revert_of"),
                rs.getString("reason"), rs.getObject("changed_at", OffsetDateTime.class));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}

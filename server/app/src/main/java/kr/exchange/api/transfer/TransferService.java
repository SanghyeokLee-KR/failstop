package kr.exchange.api.transfer;

import kr.exchange.api.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 원화 입출금(API 설계 §4-E).
 *
 * 자금 이동은 이 클래스가 직접 하지 않는다. 전부 기존 단일 경로를 지난다.
 *   입금 확정   post_ledger_transaction('TRANSFER', ...)   GATEWAY to AVAILABLE
 *   출금 잠금   reserve_funds(...)                          AVAILABLE to LOCKED
 *   출금 확정   post_ledger_transaction('TRANSFER', ...)   LOCKED to GATEWAY
 *   출금 해제   release_funds(...)                          LOCKED to AVAILABLE
 * 이 함수들이 balances를 account_id 오름차순으로 잠그므로 전역 잠금 순서가 하나로 유지되고,
 * 자산별 원장 합=0도 분개 페어로 성립한다. 새 자금 경로를 만들지 않는 것이 요점이다.
 *
 * 출금 요청 시점에 잠그는 이유는 요청과 승인 사이의 이중 사용 때문이다. 잠그지 않으면
 * 사용자가 전액 출금을 요청한 뒤 그 돈으로 거래할 수 있고, 승인 시점에는 잔고가 없어
 * 확정이 실패한다. 승인된 출금이 실행에서 실패하는 것은 운영자에게 설명 불가한 상태다.
 *
 * KRW 전용이다. 코인 입출금은 V13 position_cost 계층 논증에 걸린다. 비엔진 게시자와
 * 정산이 같은 (사용자, 비quote 자산)을 동시에 건드리면 원가 프로젝션의 라이브 적용 순서와
 * 재구축 순서가 갈릴 수 있다. KRW는 quote라 apply_position_cost가 무동작이다.
 */
public class TransferService {

    /* business_event_id 네임스페이스 예약(DB 설계 §3). RESERVE는 주문이 orders.id를,
       RELEASE는 정산이 event_seq를 쓴다. 그 축과 겹치지 않도록 오프셋을 둔다.
       GENESIS가 PROVISION_GENESIS_BASE(1e12)로 같은 방식을 쓴다. */
    public static final long TRANSFER_LOCK_BASE = 2_000_000_000_000L;
    public static final long TRANSFER_UNLOCK_BASE = 3_000_000_000_000L;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public TransferService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /* ===== 요청 ===== */

    /**
     * 입금은 즉시 확정, 출금은 잠근 뒤 승인 큐에 넣는다.
     * 같은 멱등 키 재요청은 기존 행을 그대로 돌려준다(replayed=true).
     */
    public Result request(long userId, String direction, String asset, String amountRaw, String idemKey) {
        String dir = TransferInputs.direction(direction);
        int assetId = krwAssetId(TransferInputs.asset(asset));
        BigDecimal amount = TransferInputs.amount(amountRaw);
        String key = TransferInputs.idemKey(idemKey);

        Optional<Map<String, Object>> existing = findByIdem(userId, key);
        if (existing.isPresent()) {
            return new Result(view(existing.get()), true);
        }
        try {
            Map<String, Object> row = tx.execute(status -> insertAndSettle(userId, dir, assetId, amount, key));
            return new Result(view(row), false);
        } catch (DuplicateKeyException e) {
            // 같은 키 동시 요청. 상대 트랜잭션이 커밋한 행이 이제 보인다
            return new Result(view(findByIdem(userId, key).orElseThrow(() -> e)), true);
        }
    }

    private Map<String, Object> insertAndSettle(long userId, String dir, int assetId,
                                                BigDecimal amount, String idemKey) {
        long id = jdbc.sql("""
                        INSERT INTO transfer_requests (user_id, asset_id, direction, amount, idem_key)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .params(userId, (short) assetId, dir, amount, idemKey)
                .query(Long.class)
                .single();

        if ("DEPOSIT".equals(dir)) {
            long txnId = postTransfer(id, assetId, amount,
                    accountId("SYSTEM", 0, "GATEWAY", assetId),
                    accountId("USER", userId, "AVAILABLE", assetId),
                    "DEPOSIT");
            markCompleted(id, txnId, null);
        } else {
            // 잔고 부족은 balances CHECK가 23514로 거절한다. 여기서 미리 읽고 판단하면
            // 읽기와 잠금 사이에 다른 주문이 끼어들 수 있어 판정이 무의미해진다
            reserve(id, userId, assetId, amount);
        }
        return loadById(id);
    }

    /* ===== 운영자 처리 ===== */

    /** 승인. 잠가둔 금액을 GATEWAY로 확정한다 */
    public Map<String, Object> approve(String externalId, String operatorId) {
        return tx.execute(status -> {
            Map<String, Object> row = lockPending(externalId);
            requireWithdraw(row);
            long id = ((Number) row.get("id")).longValue();
            long userId = ((Number) row.get("user_id")).longValue();
            int assetId = ((Number) row.get("asset_id")).intValue();
            BigDecimal amount = (BigDecimal) row.get("amount");
            long txnId = postTransfer(id, assetId, amount,
                    accountId("USER", userId, "LOCKED", assetId),
                    accountId("SYSTEM", 0, "GATEWAY", assetId),
                    "WITHDRAW");
            markCompleted(id, txnId, operatorId);
            return loadById(id);
        });
    }

    /** 거절. 잠금을 풀어 가용으로 되돌린다 */
    public Map<String, Object> reject(String externalId, String operatorId, String reason) {
        return tx.execute(status -> {
            Map<String, Object> row = lockPending(externalId);
            requireWithdraw(row);
            long id = ((Number) row.get("id")).longValue();
            releaseAndCancel(row, operatorId, TransferInputs.reason(reason));
            return loadById(id);
        });
    }

    /** 본인 취소. 거절과 같은 자금 흐름이고 처리자만 다르다 */
    public Map<String, Object> cancel(String externalId, long userId) {
        return tx.execute(status -> {
            Map<String, Object> row = lockPending(externalId);
            if (((Number) row.get("user_id")).longValue() != userId) {
                throw new ApiException(403, "transfer_not_owned", "본인 요청만 취소 가능");
            }
            requireWithdraw(row);
            long id = ((Number) row.get("id")).longValue();
            releaseAndCancel(row, null, "사용자 취소");
            return loadById(id);
        });
    }

    private void releaseAndCancel(Map<String, Object> row, String operatorId, String reason) {
        long id = ((Number) row.get("id")).longValue();
        long userId = ((Number) row.get("user_id")).longValue();
        int assetId = ((Number) row.get("asset_id")).intValue();
        BigDecimal amount = (BigDecimal) row.get("amount");
        jdbc.sql("SELECT release_funds(?, ?, ?, ?, ?)")
                .params(TRANSFER_UNLOCK_BASE + id,
                        accountId("USER", userId, "LOCKED", assetId),
                        accountId("USER", userId, "AVAILABLE", assetId),
                        (short) assetId, amount)
                .query(Long.class)
                .single();
        // ledger_txn_id는 비운다. 확정된 자금 이동이 아니라 되돌린 잠금이라 CANCELED 행에
        // 원장 링크가 남으면 transfer_settlement_check 뷰가 불일치로 잡는다
        jdbc.sql("""
                        UPDATE transfer_requests
                        SET status = 'CANCELED', reason = ?, decided_by = ?, decided_at = now(),
                            updated_at = now()
                        WHERE id = ?
                        """)
                .params(reason, operatorId, id)
                .update();
    }

    /* ===== 조회 ===== */

    public List<Map<String, Object>> listMine(long userId, long beforeId, int limit) {
        return jdbc.sql("""
                        SELECT t.*, a.symbol AS asset_symbol FROM transfer_requests t
                        JOIN assets a ON a.id = t.asset_id
                        WHERE t.user_id = ? AND (? = 0 OR t.id < ?)
                        ORDER BY t.id DESC LIMIT ?
                        """)
                .params(userId, beforeId, beforeId, limit)
                .query()
                .listOfRows()
                .stream()
                .map(this::view)
                .toList();
    }

    public List<Map<String, Object>> listQueue(String statusFilter, int limit) {
        return jdbc.sql("""
                        SELECT t.*, a.symbol AS asset_symbol, u.email AS user_email
                        FROM transfer_requests t
                        JOIN assets a ON a.id = t.asset_id
                        JOIN users u ON u.id = t.user_id
                        WHERE t.status = ?
                        ORDER BY t.created_at LIMIT ?
                        """)
                .params(statusFilter, limit)
                .query()
                .listOfRows()
                .stream()
                .map((row) -> {
                    Map<String, Object> v = view(row);
                    // 운영자는 누구의 요청인지 알아야 판단할 수 있다. 사용자 응답에는 넣지 않는다
                    v.put("user_email", row.get("user_email"));
                    return v;
                })
                .toList();
    }

    /* ===== 원장 ===== */

    /** 자금 이동 한 건. business_event_type='TRANSFER'는 이 경로의 유일 발급원이라 충돌이 없다 */
    private long postTransfer(long transferId, int assetId, BigDecimal amount,
                              long fromAccount, long toAccount, String entryType) {
        String entries = ("[{\"account_id\": %d, \"asset_id\": %d, \"amount\": \"-%s\","
                + " \"entry_type\": \"%s\"},"
                + "{\"account_id\": %d, \"asset_id\": %d, \"amount\": \"%s\","
                + " \"entry_type\": \"%s\"}]")
                .formatted(fromAccount, assetId, amount.toPlainString(), entryType,
                           toAccount, assetId, amount.toPlainString(), entryType);
        return jdbc.sql("SELECT post_ledger_transaction('TRANSFER', ?, ?::jsonb)")
                .params(transferId, entries)
                .query(Long.class)
                .single();
    }

    private void reserve(long transferId, long userId, int assetId, BigDecimal amount) {
        jdbc.sql("SELECT reserve_funds(?, ?, ?, ?, ?)")
                .params(TRANSFER_LOCK_BASE + transferId,
                        accountId("USER", userId, "AVAILABLE", assetId),
                        accountId("USER", userId, "LOCKED", assetId),
                        (short) assetId, amount)
                .query(Long.class)
                .single();
    }

    /**
     * decided_at은 운영자 유무와 무관하게 남긴다. 입금 자동 확정도 "언제 돈이 들어왔나"를
     * 답해야 하고, 그 질문에 created_at은 답이 아니다. 요청 접수와 확정은 다른 시각이다.
     * decided_by만 비는 조합은 V14 CHECK가 허용한다.
     */
    private void markCompleted(long id, long txnId, String operatorId) {
        jdbc.sql("""
                        UPDATE transfer_requests
                        SET status = 'COMPLETED', ledger_txn_id = ?, decided_by = ?,
                            decided_at = now(), updated_at = now()
                        WHERE id = ?
                        """)
                .params(txnId, operatorId, id)
                .update();
    }

    /* ===== 검증 ===== */

    /** 심볼 검증은 TransferInputs가 끝냈다. 여기는 조회만 한다 */
    private int krwAssetId(String symbol) {
        return jdbc.sql("SELECT id FROM assets WHERE symbol = ?")
                .params(symbol)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("KRW 자산 미시드"));
    }

    private void requireWithdraw(Map<String, Object> row) {
        if (!"WITHDRAW".equals(row.get("direction"))) {
            throw new ApiException(409, "transfer_not_pending", "입금은 승인 대상이 아님");
        }
    }

    /* ===== 행 접근 ===== */

    /**
     * 대기 중인 요청을 잠근다. FOR UPDATE가 없으면 승인과 거절이 동시에 들어왔을 때
     * 둘 다 REQUESTED를 읽고 각각 원장을 게시한다. 상태 검사만으로는 못 막는다.
     */
    private Map<String, Object> lockPending(String externalId) {
        Map<String, Object> row = jdbc.sql("""
                        SELECT * FROM transfer_requests
                        WHERE external_id = ?::uuid FOR UPDATE
                        """)
                .params(externalId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "입출금 요청 없음"));
        if (!"REQUESTED".equals(row.get("status"))) {
            throw new ApiException(409, "transfer_not_pending",
                    "이미 처리된 요청: " + row.get("status"));
        }
        return row;
    }

    private Optional<Map<String, Object>> findByIdem(long userId, String idemKey) {
        return jdbc.sql("""
                        SELECT t.*, a.symbol AS asset_symbol FROM transfer_requests t
                        JOIN assets a ON a.id = t.asset_id
                        WHERE t.user_id = ? AND t.idem_key = ?
                        """)
                .params(userId, idemKey)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
    }

    private Map<String, Object> loadById(long id) {
        return jdbc.sql("""
                        SELECT t.*, a.symbol AS asset_symbol FROM transfer_requests t
                        JOIN assets a ON a.id = t.asset_id
                        WHERE t.id = ?
                        """)
                .params(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("방금 쓴 입출금 행이 사라짐: " + id));
    }

    private long accountId(String ownerType, long ownerId, String purpose, int assetId) {
        return jdbc.sql("""
                        SELECT id FROM accounts
                        WHERE owner_type = ? AND owner_id = ? AND purpose = ? AND asset_id = ? AND shard = 0
                        """)
                .params(ownerType, ownerId, purpose, assetId)
                .query(Long.class)
                .single();
    }

    /* ===== 표현 ===== */

    /** 내부 PK와 처리자는 노출하지 않는다. 외부 식별자는 external_id다 */
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("external_id", String.valueOf(row.get("external_id")));
        v.put("direction", row.get("direction"));
        v.put("asset", row.get("asset_symbol"));
        v.put("amount", ((BigDecimal) row.get("amount")).stripTrailingZeros().toPlainString());
        v.put("status", row.get("status"));
        v.put("reason", row.get("reason"));
        v.put("created_at", epochMillis(row.get("created_at")));
        v.put("updated_at", epochMillis(row.get("updated_at")));
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

    public record Result(Map<String, Object> view, boolean replayed) {
    }
}

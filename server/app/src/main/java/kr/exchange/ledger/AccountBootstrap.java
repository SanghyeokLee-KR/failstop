package kr.exchange.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;

/**
 * 계정 모델(DB 설계 §1.2) 시드 헬퍼. 통합테스트와 시드 타임 도구가 같은 경로를 쓴다.
 *
 * 원장·잔고 직접 DML을 하지 않는다. 입금은 GENESIS 소스계정에서 post_ledger_transaction
 * 단일 경로로 게시한다(음수 잔고는 GENESIS의 allow_negative가 흡수, §6.1 ①).
 */
public class AccountBootstrap {

    // 시드 GENESIS business_event_id 상한(회원_설계.md §2-4). 회원 프로비저닝은 이보다 큰
    // PROVISION_GENESIS_BASE(1e12) + user_id를 쓰므로 두 발급원의 채번 공간이 서로소다.
    // deposit()의 max+1 채번을 이 상한 이하로 가둬 회원 genesis_event_id 선점을 물리 차단한다.
    public static final long SEED_GENESIS_MAX = 1_000_000_000L;

    private final JdbcClient jdbc;

    public AccountBootstrap(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void asset(int id, String symbol, String name, String kind, int scale) {
        jdbc.sql("""
                        INSERT INTO assets (id, symbol, name, kind, scale)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .params(id, symbol, name, kind, scale)
                .update();
        systemAccount("FEE_INCOME", id);
        systemAccount("GENESIS", id);
        // 입출금 상대 계정(V14). GENESIS와 나누는 이유는 두 계정이 다른 사건을 뜻하기 때문이다.
        // GENESIS는 이 거래소가 세상에 없던 돈을 발행한 자리이고, GATEWAY는 이미 있는 돈이
        // 바깥과 드나든 자리다. 한 계정에 섞으면 "발행량"과 "순유입"을 다시 못 가른다.
        // 둘 다 allow_negative라 소스계정으로서 음수 잔고가 정상이다(§6.1 ①).
        systemAccount("GATEWAY", id);
    }

    public void market(int id, String symbol, int baseAssetId, int quoteAssetId,
                       BigDecimal makerFeeRate, BigDecimal takerFeeRate) {
        jdbc.sql("""
                        INSERT INTO markets
                          (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size,
                           min_notional, max_notional, max_qty, maker_fee_rate, taker_fee_rate)
                        VALUES (?, ?, ?, ?, 0.000000000000000001, 0.000000000000000001,
                                0, 1000000000000, 1000000000000, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .params(id, symbol, baseAssetId, quoteAssetId, makerFeeRate, takerFeeRate)
                .update();
    }

    /** 현실 규모 마켓 규칙을 명시 시드한다. 시드 타임 도구(dev·운영 프로비저닝)용. */
    public void market(int id, String symbol, int baseAssetId, int quoteAssetId,
                       BigDecimal tickSize, BigDecimal lotSize, BigDecimal minNotional,
                       BigDecimal maxNotional, BigDecimal maxQty,
                       BigDecimal makerFeeRate, BigDecimal takerFeeRate) {
        jdbc.sql("""
                        INSERT INTO markets
                          (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size,
                           min_notional, max_notional, max_qty, maker_fee_rate, taker_fee_rate)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .params(id, symbol, baseAssetId, quoteAssetId, tickSize, lotSize,
                        minNotional, maxNotional, maxQty, makerFeeRate, takerFeeRate)
                .update();
    }

    public long user(String email) {
        jdbc.sql("INSERT INTO users (email) VALUES (?) ON CONFLICT (email) DO NOTHING")
                .params(email)
                .update();
        return jdbc.sql("SELECT id FROM users WHERE email = ?")
                .params(email)
                .query(Long.class)
                .single();
    }

    /** 사용자 x 자산의 AVAILABLE·LOCKED 계정 페어를 보장한다(§1.2: 잔고 키는 account 단일). */
    public void userAccounts(long userId, int assetId) {
        userAccount(userId, "AVAILABLE", assetId);
        userAccount(userId, "LOCKED", assetId);
    }

    /** GENESIS 입금. business_event_id는 GENESIS 이벤트 축의 단조 증가 값이다(멱등 키 충돌 방지). */
    public void deposit(long userId, int assetId, BigDecimal amount) {
        long genesis = accountId("SYSTEM", 0, "GENESIS", assetId);
        long avail = accountId("USER", userId, "AVAILABLE", assetId);
        // 시드 경로는 단일 스레드 전제라 max+1 채번으로 충분하다. 동시 시드는 지원 범위 밖.
        // 회원 프로비저닝이 게시한 1e12대 GENESIS를 채번 대상에서 제외해(<=SEED_GENESIS_MAX) 그 범위로
        // 진입하지 않게 한다. 진입하면 미래 회원 genesis_event_id를 선점해 가입 tx를 23505로 롤백시킨다.
        long eventId = jdbc.sql("""
                        SELECT COALESCE(max(business_event_id), 0) + 1
                        FROM ledger_transactions
                        WHERE business_event_type = 'GENESIS' AND business_event_id <= ?
                        """)
                .params(SEED_GENESIS_MAX)
                .query(Long.class)
                .single();
        String entries = """
                [{"account_id": %d, "asset_id": %d, "amount": %s, "entry_type": "GENESIS"},
                 {"account_id": %d, "asset_id": %d, "amount": %s, "entry_type": "GENESIS"}]
                """.formatted(genesis, assetId, amount.negate().toPlainString(),
                              avail, assetId, amount.toPlainString());
        jdbc.sql("SELECT post_ledger_transaction('GENESIS', ?, ?::jsonb)")
                .params(eventId, entries)
                .query(Long.class)
                .single();
    }

    private void systemAccount(String purpose, int assetId) {
        jdbc.sql("""
                        INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                        VALUES ('SYSTEM', 0, ?, ?, 0)
                        ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING
                        """)
                .params(purpose, assetId)
                .update();
    }

    private void userAccount(long userId, String purpose, int assetId) {
        jdbc.sql("""
                        INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                        VALUES ('USER', ?, ?, ?, 0)
                        ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING
                        """)
                .params(userId, purpose, assetId)
                .update();
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
}

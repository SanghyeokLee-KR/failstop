package kr.exchange.ledger;

import kr.exchange.engine.lease.PartitionLeaseDb;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static kr.exchange.engine.lease.PartitionLeaseDb.queryLong;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FEE_INCOME 샤드 1 계정(ADR 0034 S7, 결정 10, DB 설계 §3.10·§5.4-c 11, S7 설계 4-1). P3 에서 p1 마켓 수수료는 샤드 1 계정에
 * 게시한다. V55 는 이미 있는 자산의 샤드 1 을 한 번 채우고, 이후 FEE_INCOME 샤드 0 을 넣는 행 트리거가 같은 트랜잭션에 샤드 1 을
 * 만든다. 모드와 무관하게 늘 두며 잔고는 0 이다. GENESIS·GATEWAY 와 사용자 계정에는 샤드 1 이 생기지 않는다. 트리거 함수는 소유자
 * 권한이라 PUBLIC EXECUTE 가 없고, 앱 로그인이 그 함수를 자기 표에 걸 수 없다.
 */
class FeeIncomeShardAccountsTest {

    @Test
    void v55FillsExistingAssetsAndTheTriggerCoversNewOnes() throws Exception {
        PartitionLeaseDb db = PartitionLeaseDb.createAt("s7_fee_shard", "54").withRuntimeRoles();
        // V55 전의 DB. 자산 셋과 시스템 계정 셋(샤드 0), 사용자 계정 하나.
        db.exec("""
                INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                    (1, 'KRW', 'KRW', 'FIAT', 0), (2, 'BTC', 'BTC', 'CRYPTO', 8), (3, 'ETH', 'ETH', 'CRYPTO', 8)
                """);
        db.exec("""
                INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                SELECT 'SYSTEM', 0, p.purpose, a.id, 0
                  FROM assets a CROSS JOIN (VALUES ('FEE_INCOME'), ('GENESIS'), ('GATEWAY')) p(purpose)
                """);
        long user = Long.parseLong(db.ownerString("INSERT INTO users (email) VALUES ('fee@s7.test') RETURNING id"));
        db.exec("INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES ('USER', " + user
                + ", 'AVAILABLE', 1, 0)");
        assertEquals(0, count(db, "shard = 1"), "V55 전에 샤드 1 계정이 있다");

        db.migrate();
        assertEquals("1:0:0,1:1:0,2:0:0,2:1:0,3:0:0,3:1:0", db.ownerString("""
                SELECT string_agg(a.asset_id || ':' || a.shard || ':' || b.balance::bigint, ',' ORDER BY a.asset_id, a.shard)
                  FROM accounts a JOIN balances b ON b.account_id = a.id
                 WHERE a.owner_type = 'SYSTEM' AND a.owner_id = 0 AND a.purpose = 'FEE_INCOME'
                """), "V55 가 기존 자산의 샤드 1 계정과 잔고 행을 만들지 않았다");
        assertEquals(0, count(db, "shard = 1 AND purpose IN ('GENESIS', 'GATEWAY')"), "GENESIS·GATEWAY 에 샤드 1 이 생겼다");
        assertEquals(0, count(db, "shard = 1 AND owner_type = 'USER'"));
        assertEquals("1", db.ownerString("SELECT protocol::text FROM leader_protocol"), "V55 가 모드를 바꿨다");

        // 앱 로그인이 새 자산과 그 FEE_INCOME 샤드 0 을 넣으면 샤드 1 이 같은 트랜잭션에 생긴다. 재삽입은 ON CONFLICT 로 넘긴다.
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            app.setAutoCommit(false);
            st.execute("INSERT INTO assets (id, symbol, name, kind, scale) VALUES (4, 'XRP', 'XRP', 'CRYPTO', 2)");
            st.execute("""
                    INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                    VALUES ('SYSTEM', 0, 'FEE_INCOME', 4, 0), ('SYSTEM', 0, 'GENESIS', 4, 0)
                    """);
            assertEquals("FEE_INCOME:0,FEE_INCOME:1,GENESIS:0", queryString(app, """
                    SELECT string_agg(purpose || ':' || shard, ',' ORDER BY purpose, shard) FROM accounts WHERE asset_id = 4
                    """), "같은 트랜잭션 안에서 샤드 1 이 보이지 않는다");
            st.execute("""
                    INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
                    VALUES ('SYSTEM', 0, 'FEE_INCOME', 4, 0)
                    ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING
                    """);
            app.commit();
        }
        assertEquals(2, count(db, "asset_id = 4 AND purpose = 'FEE_INCOME'"));
        assertEquals("0", db.ownerString("""
                SELECT b.balance::bigint::text FROM balances b JOIN accounts a ON a.id = b.account_id
                 WHERE a.asset_id = 4 AND a.purpose = 'FEE_INCOME' AND a.shard = 1
                """), "샤드 1 계정에 잔고 행이 없다");
        // 사용자 계정 생성에는 트리거 함수가 돌지 않는다(WHEN 절).
        db.exec("INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES ('USER', " + user
                + ", 'AVAILABLE', 4, 0)");
        assertEquals(0, count(db, "owner_type = 'USER' AND shard = 1"));

        // 트리거 함수는 PUBLIC 이 부르지 못한다. 앱 로그인이 자기 임시 표에 걸면 42501 이다.
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            st.execute("CREATE TEMP TABLE t_probe (owner_type text, purpose text, asset_id smallint, shard smallint)");
            SQLException denied = assertThrows(SQLException.class, () -> st.execute(
                    "CREATE TRIGGER t_probe_trg AFTER INSERT ON t_probe FOR EACH ROW EXECUTE FUNCTION seed_fee_income_shard()"));
            assertEquals("42501", denied.getSQLState(), denied.getMessage());
        }
        try (Connection c = db.owner()) {
            assertEquals(0, queryLong(c, """
                    SELECT count(*) FROM pg_proc p, aclexplode(p.proacl) a
                     WHERE p.oid = 'seed_fee_income_shard()'::regprocedure AND a.grantee = 0
                    """), "트리거 함수에 PUBLIC EXECUTE 가 있다");
        }
    }

    private static long count(PartitionLeaseDb db, String where) throws SQLException {
        try (Connection c = db.owner()) {
            return queryLong(c, "SELECT count(*) FROM accounts WHERE " + where);
        }
    }
}

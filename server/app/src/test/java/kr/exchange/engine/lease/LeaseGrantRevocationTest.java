package kr.exchange.engine.lease;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static kr.exchange.engine.lease.MarketDerivedFencingTest.append;
import static kr.exchange.engine.lease.MarketDerivedFencingTest.prologue;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryLong;
import static kr.exchange.engine.lease.PartitionLeaseDb.queryString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V49 권한 상태(ADR 0034 결정한 값 17, S2 설계 4-7). 앱 역할은 리스 행을 읽기만 하고 바꾸거나 잠그는 일과 명령 저널 쓰기는
 * 소유자 권한 함수로만 한다. 소유자 권한 함수와 대응 트리거 함수는 PUBLIC EXECUTE 가 없다. V49 파일은 S2 이미지에 없으므로
 * DB 설계 §3.4 의 V49 계약 SQL 을 시험 자원으로 실행한다.
 */
class LeaseGrantRevocationTest {

    private static final String DENIED = "42501";

    /** 소유자 권한 함수. app_role 에 주는 공개 함수와 주지 않는 내부 함수로 나뉜다. */
    private static final List<String> PUBLIC_DEFINER_FUNCTIONS = List.of(
            "try_acquire_lease(text,text,interval)",
            "try_acquire_leader(text,interval)",
            "renew_lease(text,text,bigint,interval)",
            "renew_leader_lease(text,bigint,interval)",
            "release_lease(text,text,bigint)",
            "publish_leader_epoch(text,bigint)",
            "expire_leader_lease_now()",
            "assert_and_lock_leader(text,bigint)",
            "assert_and_lock_market_leader(smallint,text,bigint)",
            "append_command_journal_row(bigint,smallint,character varying,bigint,character varying,bigint,"
                    + "bigint,bigint,jsonb,bigint,text,bigint)");
    private static final List<String> INTERNAL_DEFINER_FUNCTIONS = List.of(
            "lease_acquire_core(text,text,interval,boolean)",
            "allocate_lease_epoch()",
            "seed_market_lease_partition()");

    @Test
    void appRoleWritesLeaseAndJournalOnlyThroughFunctions() throws Exception {
        PartitionLeaseDb db = v49Database("grant_app");
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            app.setAutoCommit(false);
            for (String sql : List.of(
                    "UPDATE leader_lease SET lease_until = lease_until WHERE resource = 'matching-engine'",
                    "SELECT epoch FROM leader_lease WHERE resource = 'matching-engine' FOR KEY SHARE",
                    """
                    INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                                                 assigned_order_id, engine_ts, tie_break, payload, fencing_token, checksum)
                    VALUES (999999, 1, 'NEW', 1, 'direct', 1, 0, 0, '{}'::jsonb, 7, 0)
                    """)) {
                SQLException denied = assertThrows(SQLException.class, () -> st.execute(sql), sql);
                assertEquals(DENIED, denied.getSQLState(), sql + ": " + denied.getMessage());
                app.rollback();
            }
            app.setAutoCommit(true);

            db.expire("matching-engine");
            long epoch = queryLong(app, "SELECT try_acquire_lease('matching-engine', 'app-a', interval '60 seconds')");
            assertEquals("true", queryString(app, "SELECT renew_lease('matching-engine', 'app-a', " + epoch
                    + ", interval '60 seconds')::text"));
            assertEquals("true", queryString(app, "SELECT renew_leader_lease('app-a', " + epoch
                    + ", interval '60 seconds')::text"));

            app.setAutoCommit(false);
            long seq = prologue(app, "app-a", epoch, "grant-1", 1, true);
            assertEquals(1, append(app, seq, 1, "app-a", epoch, "NEW"));
            assertEquals(epoch, queryLong(app, "SELECT assert_and_lock_market_leader(1::smallint, 'app-a', " + epoch + ")"));
            assertEquals(epoch, queryLong(app, "SELECT assert_and_lock_leader('app-a', " + epoch + ")"));
            assertEquals(epoch + "|0|" + seq + "|1", queryString(app, """
                    SELECT assert_and_lock_market_leader(COALESCE(market_id, 1::smallint), 'app-a', %d)
                           || '|' || (SELECT count(*) FROM event_emission_registry WHERE source_cmd_seq = %d)
                           || '|' || seq || '|' || market_id
                    FROM (VALUES (1)) AS one(x) LEFT JOIN command_journal ON seq = %d
                    """.formatted(epoch, seq, seq)));
            app.commit();
            app.setAutoCommit(true);

            assertEquals("true", queryString(app, "SELECT release_lease('matching-engine', 'app-a', " + epoch + ")::text"));
            long legacy = queryLong(app, "SELECT try_acquire_leader('app-b', interval '60 seconds')");
            assertEquals(epoch + 1, legacy);
            assertEquals(String.valueOf(legacy + 5), queryString(app, "SELECT publish_leader_epoch('app-c', " + (legacy + 5) + ")"));
            st.execute("SELECT expire_leader_lease_now()");
            assertEquals("true", queryString(app, """
                    SELECT (lease_until < clock_timestamp())::text FROM leader_lease WHERE resource = 'matching-engine'
                    """));
        }
        assertEquals(1, db.ownerLong("SELECT count(*) FROM command_journal"));
    }

    @Test
    void definerFunctionsHaveNoPublicExecute() throws Exception {
        PartitionLeaseDb db = v49Database("grant_exec");
        try (Connection owner = db.owner()) {
            for (String f : PUBLIC_DEFINER_FUNCTIONS) {
                assertEquals("true|false", privileges(owner, f), f);
            }
            for (String f : INTERNAL_DEFINER_FUNCTIONS) {
                assertEquals("false|false", privileges(owner, f), f);
            }
            assertEquals("true", queryString(owner, "SELECT bool_and(prosecdef)::text FROM pg_proc WHERE oid IN ("
                    + String.join(",", PUBLIC_DEFINER_FUNCTIONS.stream().map(f -> "'" + f + "'::regprocedure").toList())
                    + ")"), "공개 리스 함수가 소유자 권한이 아니다");
        }
        try (Connection reader = db.reader(); Statement st = reader.createStatement()) {
            st.execute("SET default_transaction_read_only = off");
            for (String sql : List.of(
                    "SELECT try_acquire_lease('matching-engine', 'reader', interval '1 hour')",
                    "SELECT publish_leader_epoch('reader', 9999)",
                    "SELECT expire_leader_lease_now()")) {
                SQLException denied = assertThrows(SQLException.class, () -> st.execute(sql), sql);
                assertEquals(DENIED, denied.getSQLState(), sql + ": " + denied.getMessage());
            }
        }
    }

    /**
     * CREATE TRIGGER 는 트리거 함수의 EXECUTE 를 본다. 회수 전에는 임시 표를 만들 수 있는 모든 로그인이 이 함수를 자기 표에 걸어
     * 소유자 권한으로 대응 행을 넣었다(S2 설계 10절 R1b). 발화는 EXECUTE 를 보지 않으므로 마켓 생성은 그대로다.
     */
    @Test
    void mappingTriggerFunctionCannotBeAttachedByRuntimeLogins() throws Exception {
        PartitionLeaseDb db = v49Database("grant_trigger");
        db.seedMarketWithoutTriggers(4);
        for (Connection login : List.of(db.reader(), db.app(), db.bot())) {
            try (login; Statement st = login.createStatement()) {
                st.execute("SET default_transaction_read_only = off");
                st.execute("CREATE TEMP TABLE probe (id smallint)");
                SQLException denied = assertThrows(SQLException.class, () -> st.execute(
                        "CREATE TRIGGER probe_seed AFTER INSERT ON probe FOR EACH ROW "
                                + "EXECUTE FUNCTION seed_market_lease_partition()"));
                assertEquals(DENIED, denied.getSQLState(), denied.getMessage());
            }
        }
        try (Connection app = db.app(); Statement st = app.createStatement()) {
            st.execute(PartitionLeaseDb.insertMarketSql(6));
        }
        assertEquals("6:matching-p1", mapping(db), "앱 역할의 마켓 생성이 대응을 만들지 않았다");

        // 양성 대조. PUBLIC EXECUTE 를 잠시 되돌리면 조회 로그인이 대응 없는 마켓 4 에 행을 남긴다. 검사가 실제로 권한을 본다.
        db.exec("GRANT EXECUTE ON FUNCTION seed_market_lease_partition() TO PUBLIC");
        try (Connection reader = db.reader(); Statement st = reader.createStatement()) {
            st.execute("SET default_transaction_read_only = off");
            st.execute("CREATE TEMP TABLE probe (id smallint)");
            st.execute("CREATE TRIGGER probe_seed AFTER INSERT ON probe FOR EACH ROW "
                    + "EXECUTE FUNCTION seed_market_lease_partition()");
            st.execute("INSERT INTO probe VALUES (4)");
        } finally {
            db.exec("REVOKE EXECUTE ON FUNCTION seed_market_lease_partition() FROM PUBLIC");
        }
        assertEquals("4:matching-p0,6:matching-p1", mapping(db));
        assertEquals(PartitionLeaseDb.READER, db.ownerString(
                "SELECT mapped_by FROM market_lease_partition WHERE market_id = 4"));
    }

    private static PartitionLeaseDb v49Database(String prefix) throws SQLException {
        // V49 는 저장소 마이그레이션이라 create() 가 이미 적용한다(S3 설계 4-1).
        PartitionLeaseDb db = PartitionLeaseDb.create(prefix).withRuntimeRoles();
        db.seedMarkets(1);
        return db;
    }

    private static String privileges(Connection c, String function) throws SQLException {
        return queryString(c, """
                SELECT has_function_privilege('app_role', '%1$s', 'EXECUTE')::text || '|'
                    || has_function_privilege('%2$s', '%1$s', 'EXECUTE')::text
                """.formatted(function, PartitionLeaseDb.READER));
    }

    private static String mapping(PartitionLeaseDb db) throws SQLException {
        return db.ownerString("""
                SELECT string_agg(market_id || ':' || resource, ',' ORDER BY market_id)
                FROM market_lease_partition WHERE market_id <> 1
                """);
    }
}

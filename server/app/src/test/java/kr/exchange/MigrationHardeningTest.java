package kr.exchange;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실배포 환경 감사가 확정한 마이그레이션 결함의 회귀 고정. MigrationSmokeTest는 빈 DB에 UTC 컨테이너로
 * V0~V10을 단일 패스 적용하므로 아래를 구조적으로 못 잡는다.
 *
 * 타임아웃 상속: prod Hikari 풀 connection-init-sql의 statement_timeout을 Flyway가 상속해 데이터
 *   무거운 마이그레이션이 57014로 취소되는 결함. V8·V10 상단 SET LOCAL 0으로 상한을 해제한다.
 * DEFAULT 재배치: DEFAULT 파티션에 신규 범위 행이 있으면 CREATE ... PARTITION OF가 중단되는 결함.
 *   V8이 DETACH, 신규 파티션 생성, 행 재배치, 재ATTACH로 회피한다. 재배치 구간의 트리거 우회는
 *   DISABLE TRIGGER USER(owner 권한 충분)로 하고 같은 트랜잭션에서 ENABLE 원복한다.
 * 세션 TZ: 세션 TZ가 배포 JVM zone(KST)에 의존해 파티션 경계가 밀려 overlap으로 부팅이 막히는 결함.
 *   연결 계층 UTC 고정 + V8 명시 오프셋 리터럴로 세션 TZ와 무관하게 정렬된다.
 * 권한 하한: V0~V10 전 체인이 superuser 없이 적용된다. 비superuser(CREATEROLE + DB owner)
 *   migrator 롤로 전 마이그레이션을 실행해 하한을 실증한다(하네스_CICD_IaC §7-2의 전담
 *   flyway-migrate 잡이 superuser일 필요가 없음을 증명).
 *
 * 각 테스트는 격리 위해 신규 컨테이너를 기동한다.
 */
class MigrationHardeningTest {

    private static final String IMAGE = "postgres:17-alpine";
    private static final String MIGRATOR = "migrator";

    // ------------------------------------------------------------------
    // 상속된 statement_timeout을 V8·V10 패턴(SET LOCAL 0)이 해제한다.
    // ------------------------------------------------------------------

    @Test
    void setLocalTimeoutZeroLetsSlowStatementRunUnderInheritedCap() throws Exception {
        try (PostgreSQLContainer<?> pg = container()) {
            pg.start();
            try (HikariDataSource ds = hikari(pg, pg.getUsername(), pg.getPassword(),
                    "SET statement_timeout TO '100ms'")) {
                // 음성 대조: 상속 상한이 느린 문장을 취소한다(마이그레이션이 부팅을 막던 경로).
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    SQLException cancelled = assertThrows(SQLException.class, () -> {
                        try (Statement st = c.createStatement()) {
                            st.execute("SELECT pg_sleep(0.4)");
                        }
                    });
                    assertEquals("57014", cancelled.getSQLState(),
                            "상속 statement_timeout이 느린 문장을 취소해야(음성 대조): " + cancelled.getMessage());
                    c.rollback();
                }
                // V8·V10 패턴: 트랜잭션 한정 SET LOCAL 0이 상한을 해제해 느린 문장이 통과한다.
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    try (Statement st = c.createStatement()) {
                        st.execute("SET LOCAL statement_timeout = 0");
                        st.execute("SELECT pg_sleep(0.4)");
                    }
                    c.commit();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // V7까지 적재된 DB에 DEFAULT 범위 행을 심고, 비superuser migrator + KST 세션 + prod
    // 타임아웃으로 V8~V10을 증분 적용해도 통과하며 행이 정규 파티션으로 재배치된다.
    // 재배치 후 사용자 트리거(레지스트리·봉인 가드)가 전부 원복됐는지도 검증한다.
    // ------------------------------------------------------------------

    @Test
    void incrementalDeployRelocatesDefaultRowsUnderNonUtcSessionWithoutSuperuser() throws Exception {
        try (PostgreSQLContainer<?> pg = container()) {
            pg.start();
            createMigratorRole(pg);

            // phase 1: V0~V7을 비superuser migrator로 적용(컨테이너 서버 TZ=UTC)한다.
            Flyway.configure()
                    .dataSource(pg.getJdbcUrl(), MIGRATOR, MIGRATOR)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("7"))
                    .load()
                    .migrate();

            seedDefaultRows(pg);
            try (Connection c = plain(pg)) {
                assertEquals(1, count(c, "SELECT count(*) FROM trades_default"), "시드 전제: trades default");
                assertEquals(1, count(c, "SELECT count(*) FROM outbox_default"), "시드 전제: outbox default");
                assertEquals(1, count(c, "SELECT count(*) FROM ledger_entries_default"),
                        "시드 전제: ledger default");
            }

            // phase 2: KST 세션 + prod 타임아웃을 상속한 migrator 커넥션으로 V8~V10을
            // 적용한다(라이브 증분 재현 + 권한 하한 실증).
            try (HikariDataSource ds = hikari(pg, MIGRATOR, MIGRATOR,
                    "SET TIME ZONE 'Asia/Seoul'; SET statement_timeout TO '3000ms'; "
                    + "SET lock_timeout TO '2000ms'")) {
                var result = Flyway.configure()
                        .dataSource(ds)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
                assertTrue(result.success, "비superuser·KST 세션 증분 적용 실패(권한/overlap/timeout 회귀)");
            }

            try (Connection c = plain(pg)) {
                // DEFAULT 범위 행이 정규 2026-09 파티션으로 이동하고 default가 비었다.
                assertEquals(0, count(c, "SELECT count(*) FROM trades_default"), "trades default 미정리");
                assertEquals(1, count(c, "SELECT count(*) FROM trades_p2026_09"), "trades 재배치 실패");
                assertEquals(0, count(c, "SELECT count(*) FROM outbox_default"), "outbox default 미정리");
                assertEquals(1, count(c, "SELECT count(*) FROM outbox_p2026_09"), "outbox 재배치 실패");
                assertEquals(0, count(c, "SELECT count(*) FROM ledger_entries_default"),
                        "ledger default 미정리");
                assertEquals(1, count(c, "SELECT count(*) FROM ledger_entries_p2026_09"),
                        "ledger 재배치 실패");
                // trades 이동이 레지스트리 트리거를 재발화하지 않았다(중복 없음 = 트리거 우회 성공).
                assertEquals(1, count(c, "SELECT count(*) FROM trade_seq_registry WHERE seq = 700001"),
                        "레지스트리 중복(트리거 우회 실패)");
                // 트리거 원복: V8이 끈 사용자 트리거가 파티션 클론 포함 전부 다시 켜져 있다.
                assertEquals(0, count(c, """
                        SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgenabled <> 'O'
                        """), "V8 이후 비활성 사용자 트리거 잔존(ENABLE 원복 실패)");
            }

            // 봉인 가드가 기능적으로 살아 있다: SEALED 거래에 분개를 추가하면 거부된다.
            try (Connection c = plain(pg)) {
                long txn = queryLong(c,
                        "SELECT id FROM ledger_transactions WHERE business_event_id = 900001");
                long acct = queryLong(c,
                        "SELECT id FROM accounts WHERE owner_id = 900 AND asset_id = 1");
                SQLException blocked = assertThrows(SQLException.class, () -> {
                    try (Statement st = c.createStatement()) {
                        st.execute(("INSERT INTO ledger_entries (transaction_id, account_id, "
                                + "asset_id, amount, entry_type) VALUES (%d, %d, 1, 5, 'GENESIS')")
                                .formatted(txn, acct));
                    }
                });
                assertTrue(blocked.getMessage().contains("봉인"),
                        "봉인 가드 미발화(트리거 원복 실패): " + blocked.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 비superuser 마이그레이션 주체. CREATEROLE(V0 롤 부트스트랩용) + DB owner(스키마 소유,
    // trusted 확장 pgcrypto 설치, DISABLE TRIGGER USER)면 전 체인이 적용된다.
    // ------------------------------------------------------------------

    private void createMigratorRole(PostgreSQLContainer<?> pg) throws SQLException {
        try (Connection c = plain(pg); Statement st = c.createStatement()) {
            st.execute("CREATE ROLE " + MIGRATOR + " LOGIN PASSWORD '" + MIGRATOR + "' CREATEROLE");
            st.execute("ALTER DATABASE " + pg.getDatabaseName() + " OWNER TO " + MIGRATOR);
        }
        try (Connection c = plain(pg)) {
            assertEquals(0, count(c,
                    "SELECT count(*) FROM pg_roles WHERE rolname = '" + MIGRATOR + "' AND rolsuper"),
                    "전제 위배: migrator가 superuser면 권한 하한 실증이 무효");
            assertFalse(count(c,
                    "SELECT count(*) FROM pg_roles WHERE rolname = '" + MIGRATOR + "'") == 0,
                    "전제 위배: migrator 롤 미생성");
        }
    }

    // ------------------------------------------------------------------
    // 시드: to-be-created 범위(2026-09)의 행을 각 DEFAULT 파티션에 심는다.
    // ------------------------------------------------------------------

    private void seedDefaultRows(PostgreSQLContainer<?> pg) throws SQLException {
        try (Connection c = plain(pg); Statement st = c.createStatement()) {
            st.execute("SET timezone = 'UTC'");
            st.execute("""
                    INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                        (1, 'KRW', 'Korean Won', 'FIAT', 0),
                        (2, 'BTC', 'Bitcoin', 'CRYPTO', 8)
                    ON CONFLICT (id) DO NOTHING
                    """);
            st.execute("""
                    INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id,
                                         tick_size, lot_size, min_notional, max_notional, max_qty)
                    VALUES (1, 'KRW-BTC', 2, 1, 1000, 0.0001, 5000, 100000000000, 10000)
                    ON CONFLICT (id) DO NOTHING
                    """);
            // trades: 정상 삽입(AFTER INSERT 레지스트리 트리거 발화 → seq 등록). created_at=2026-09 → default.
            st.execute("""
                    INSERT INTO trades (id, market_id, maker_order_id, taker_order_id,
                                        maker_user_id, taker_user_id, taker_side, price, quantity,
                                        fencing_epoch, executed_at, created_at)
                    VALUES (700001, 1, 1, 2, 11, 12, 'BUY', 100, 1, 1,
                            TIMESTAMPTZ '2026-09-15 00:00:00+00', TIMESTAMPTZ '2026-09-15 00:00:00+00')
                    """);
            // outbox: 트리거 없음. created_at=2026-09 → default.
            st.execute("""
                    INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
                    VALUES ('TRADE', '700001', 'TradeExecuted', '{}',
                            TIMESTAMPTZ '2026-09-15 00:00:00+00')
                    """);
            // ledger_entries: 정상 경로(post_ledger_transaction)는 created_at=now()라 2026-09를 만들 수
            // 없고, 봉인 가드·DEFERRED 균형 제약이 직접 삽입을 막는다. 기존 커밋 데이터를 흉내 내려고
            // 트리거·FK·제약 트리거를 replica로 우회해 SEALED·2026-09 분개를 심는다(superuser 시드
            // 한정. 마이그레이션 본문은 이 우회를 쓰지 않는다).
            st.execute("INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) "
                    + "VALUES ('USER', 900, 'AVAILABLE', 1, 0)");
            long acct = queryLong(c, "SELECT id FROM accounts WHERE owner_id = 900 AND asset_id = 1");
            st.execute("SET session_replication_role = 'replica'");
            st.execute("""
                    INSERT INTO ledger_transactions (business_event_type, business_event_id,
                                                     status, sealed_at)
                    VALUES ('GENESIS', 900001, 'SEALED', now())
                    """);
            long txn = queryLong(c, "SELECT id FROM ledger_transactions WHERE business_event_id = 900001");
            st.execute("""
                    INSERT INTO ledger_entries (transaction_id, account_id, asset_id, amount,
                                                entry_type, created_at)
                    VALUES (%d, %d, 1, 10, 'GENESIS', TIMESTAMPTZ '2026-09-15 00:00:00+00')
                    """.formatted(txn, acct));
            st.execute("SET session_replication_role = 'origin'");
        }
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("exchange")
                .withUsername("exchange")
                .withPassword("exchange");
    }

    private static HikariDataSource hikari(PostgreSQLContainer<?> pg, String user, String password,
                                           String initSql) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(pg.getJdbcUrl());
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setConnectionInitSql(initSql);
        cfg.setMaximumPoolSize(2);
        return new HikariDataSource(cfg);
    }

    private static Connection plain(PostgreSQLContainer<?> pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

package kr.exchange.engine.lease;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0034 S2 시험의 일회용 데이터베이스(S2 설계 6-1). 컨테이너 하나를 JVM 안에서 나눠 쓰고 시험마다 새 데이터베이스를
 * 만든다. 프로토콜을 바꾸거나 파티션 자원을 획득한 DB 는 epoch 가 뛰므로 다른 시험과 섞지 않는다.
 *
 * 로그인 셋은 배포와 같은 스크립트(deploy/provision-runtime-roles.sql)로 만든다. 앱·봇 로그인은 app_role 에 속하고
 * 조회 로그인은 속하지 않는다. 통합 시험의 수퍼유저 접속이 가리는 권한 누락을 이 로그인들로 본다.
 */
public final class PartitionLeaseDb {

    public static final String OWNER = "exchange";
    public static final String APP = "s2_app";
    public static final String BOT = "s2_bot";
    public static final String READER = "s2_read";

    private static final String APP_PASSWORD = "s2-app";
    private static final String BOT_PASSWORD = "s2-bot";
    private static final String READER_PASSWORD = "s2-read";

    // V49 는 S2 이미지에 넣지 않는다(S2 설계 4-1). 권한 상태를 보는 시험은 DB 설계 §3.4 의 V49 계약 SQL 을 이 자원으로 실행한다.
    private static final String V49_CONTRACT = "/s2-contract/v49-revoke-lease-update-journal-insert.sql";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("exchange")
            .withUsername(OWNER)
            .withPassword(OWNER)
            .withEnv("DB_USER", APP).withEnv("DB_PASSWORD", APP_PASSWORD)
            .withEnv("BOT_DB_USER", BOT).withEnv("BOT_DB_PASSWORD", BOT_PASSWORD)
            .withEnv("READ_DB_USER", READER).withEnv("READ_DB_PASSWORD", READER_PASSWORD);

    private static final AtomicInteger NAMES = new AtomicInteger();
    private static boolean started;

    private final String name;
    private final String url;

    private PartitionLeaseDb(String name, String url) {
        this.name = name;
        this.url = url;
    }

    /** 전 마이그레이션을 올린 새 데이터베이스. */
    public static PartitionLeaseDb create(String prefix) {
        PartitionLeaseDb db = empty(prefix);
        assertTrue(db.flyway().load().migrate().success, "Flyway migration failed: " + db.name);
        return db;
    }

    /** target 버전까지 올린 새 데이터베이스. 나머지는 migrate() 로 올린다. */
    public static PartitionLeaseDb createAt(String prefix, String target) {
        PartitionLeaseDb db = empty(prefix);
        assertTrue(db.flyway().target(target).load().migrate().success, "Flyway migration failed: " + db.name);
        return db;
    }

    private static PartitionLeaseDb empty(String prefix) {
        start();
        String dbName = prefix + "_" + NAMES.incrementAndGet();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER, OWNER);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        } catch (SQLException e) {
            throw new IllegalStateException("데이터베이스 생성 실패: " + dbName, e);
        }
        return new PartitionLeaseDb(dbName, POSTGRES.getJdbcUrl().replace("/exchange", "/" + dbName));
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        POSTGRES.start();
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(repoFile("deploy/provision-runtime-roles.sql")),
                "/tmp/provision.sql");
        String dir = System.getProperty("harness.sql.dir");
        if (dir != null) {
            for (String file : List.of("summary.sql", "invariants.sql")) {
                POSTGRES.copyFileToContainer(MountableFile.forHostPath(Path.of(dir, file)), "/tmp/" + file);
            }
        }
        started = true;
    }

    private static Path repoFile(String relative) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(relative))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException(relative + " 없음");
        }
        return root.resolve(relative);
    }

    public void migrate() {
        assertTrue(flyway().load().migrate().success, "Flyway migration failed: " + name);
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(url, OWNER, OWNER).locations("classpath:db/migration");
    }

    /** 배포와 같은 세 로그인을 이 데이터베이스에 준비한다. 역할은 클러스터 전역이라 두 번째부터는 권한만 더한다. */
    public PartitionLeaseDb withRuntimeRoles() {
        try {
            ExecResult result = POSTGRES.execInContainer("psql", "-X", "-U", OWNER, "-d", name,
                    "-v", "ON_ERROR_STOP=1", "-q", "-f", "/tmp/provision.sql");
            assertEquals(0, result.getExitCode(), "로그인 준비 실패: " + result.getStderr());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("로그인 준비 실패", e);
        }
        return this;
    }

    /** DB 설계 §3.4 의 V49 계약 SQL 을 소유자로 실행해 V49 권한 상태를 만든다. */
    public void applyV49Contract() throws SQLException {
        String sql;
        try (InputStream in = PartitionLeaseDb.class.getResourceAsStream(V49_CONTRACT)) {
            if (in == null) {
                throw new IllegalStateException(V49_CONTRACT + " 없음");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(V49_CONTRACT + " 읽기 실패", e);
        }
        try (Connection c = owner(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** 컨테이너 psql 로 하네스 SQL 을 돌린 출력 줄. 종료코드를 보지 않으면 문법 오류가 빈 출력, 곧 위반 0 으로 읽힌다. */
    public List<String> psql(String file) throws Exception {
        String uri = "postgresql://%s:%s@localhost:%d/%s".formatted(OWNER, OWNER,
                PostgreSQLContainer.POSTGRESQL_PORT, name);
        ExecResult result = POSTGRES.execInContainer("psql", "-X", "-d", uri,
                "-v", "ON_ERROR_STOP=1", "-Atq", "-f", "/tmp/" + file);
        assertEquals(0, result.getExitCode(), file + " 실행 실패: " + result.getStderr());
        return result.getStdout().lines().toList();
    }

    public String name() {
        return name;
    }

    public String url() {
        return url;
    }

    public Connection owner() throws SQLException {
        return DriverManager.getConnection(url, OWNER, OWNER);
    }

    public Connection app() throws SQLException {
        return DriverManager.getConnection(url, APP, APP_PASSWORD);
    }

    public Connection bot() throws SQLException {
        return DriverManager.getConnection(url, BOT, BOT_PASSWORD);
    }

    public Connection reader() throws SQLException {
        return DriverManager.getConnection(url, READER, READER_PASSWORD);
    }

    public HikariDataSource ownerPool() {
        return pool(OWNER, OWNER);
    }

    public HikariDataSource appPool() {
        return pool(APP, APP_PASSWORD);
    }

    private HikariDataSource pool(String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(6);
        config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new HikariDataSource(config);
    }

    /** 소유자로 한 문장을 실행한다. */
    public void exec(String sql) throws SQLException {
        try (Connection c = owner(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    public long ownerLong(String sql) throws SQLException {
        try (Connection c = owner()) {
            return queryLong(c, sql);
        }
    }

    public String ownerString(String sql) throws SQLException {
        try (Connection c = owner()) {
            return queryString(c, sql);
        }
    }

    public static long queryLong(Connection c, String sql) throws SQLException {
        return Long.parseLong(queryString(c, sql));
    }

    /** 첫 행 첫 열. 행이 없으면 실패다. SQL NULL 은 null 이다. */
    public static String queryString(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "결과 없음: " + sql);
            return rs.getString(1);
        }
    }

    /**
     * 자산 둘과 마켓 ids 를 소유자로 넣는다. 마켓 삽입 트리거가 카운터·대역 잎·파티션 대응을 만든다.
     * 수량 규칙은 AccountBootstrap.market 과 같게 느슨하게 둔다.
     */
    public void seedMarkets(int... ids) throws SQLException {
        try (Connection c = owner(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO assets (id, symbol, name, kind, scale) VALUES
                        (1, 'KRW', 'Korean Won', 'FIAT', 0),
                        (2, 'BTC', 'Bitcoin', 'CRYPTO', 8)
                    ON CONFLICT (id) DO NOTHING
                    """);
            for (int id : ids) {
                st.execute(insertMarketSql(id));
            }
        }
    }

    /** 트리거를 끄고 마켓을 넣는다. 복원이나 replica 적재가 대응 없는 마켓을 만드는 경로다(C29 양성 대조). */
    public void seedMarketWithoutTriggers(int id) throws SQLException {
        try (Connection c = owner(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute(insertMarketSql(id));
            st.execute("SET session_replication_role = origin");
        }
    }

    public static String insertMarketSql(int id) {
        return """
                INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size,
                                     min_notional, max_notional, max_qty)
                VALUES (%d, 'M%d', 2, 1, 1, 0.0001, 0, 1000000000, 1000000)
                """.formatted(id, id);
    }

    /** 리스 행 하나를 소유자로 심는다. seconds 가 음수면 이미 만료된 행이다. */
    public void seedLease(String resource, String holder, long epoch, int seconds) throws SQLException {
        try (Connection c = owner(); Statement st = c.createStatement()) {
            assertEquals(1, st.executeUpdate("""
                    UPDATE leader_lease
                    SET holder = '%s', epoch = %d, lease_until = clock_timestamp() + make_interval(secs => %d)
                    WHERE resource = '%s'
                    """.formatted(holder, epoch, seconds, resource)));
        }
    }

    public void expire(String resource) throws SQLException {
        exec("UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' "
                + "WHERE resource = '" + resource + "'");
    }

    /** P3 을 흉내 낸다. S2 에는 전환 함수가 없어 소유자가 래치 행을 직접 바꾼다(S2 설계 4-6). */
    public void setProtocol(int protocol) throws SQLException {
        exec("UPDATE leader_protocol SET protocol = " + protocol);
    }
}

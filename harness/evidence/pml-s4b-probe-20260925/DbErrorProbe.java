// ADR 0034 S4b 설계 3절 J 확인용 프로그램. 앱과 같은 판의 Spring JDBC 6.2.19, pgjdbc 42.7.11, HikariCP 6.3.3 으로
// PostgreSQL 오류가 어떤 스프링 예외로 번역되고, 트랜잭션 템플릿 롤백과 커밋이 연결 장애에서 무엇을 던지는지 본다.
// spring-probe.sh 가 폐기용 PostgreSQL·toxiproxy 컨테이너를 띄운 뒤 부른다. 인자: <pg 포트> <프록시 포트> <toxiproxy API 포트>
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DbErrorProbe {

    static String direct;
    static String proxied;
    static String api;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        direct = "jdbc:postgresql://127.0.0.1:" + args[0] + "/postgres";
        proxied = "jdbc:postgresql://127.0.0.1:" + args[1] + "/postgres";
        api = "http://127.0.0.1:" + args[2];
        try (Connection admin = admin()) {
            exec(admin, "CREATE TABLE probe_t(id int PRIMARY KEY, v int NOT NULL)");
            exec(admin, "INSERT INTO probe_t VALUES (1,0),(2,0),(3,0)");
            exec(admin, "CREATE TABLE probe_commit(id int PRIMARY KEY)");
            exec(admin, "CREATE FUNCTION raise_fencing() RETURNS int LANGUAGE plpgsql AS $$ BEGIN "
                    + "RAISE EXCEPTION 'fencing 위반: probe'; END $$");
            System.out.println("server_version " + scalar(admin, "SHOW server_version"));
        }
        toxi("POST", "/proxies", "{\"name\":\"pg\",\"listen\":\"0.0.0.0:15432\",\"upstream\":\""
                + System.getenv("PG_HOST") + ":5432\",\"enabled\":true}");
        HikariDataSource ds = pool();
        JdbcClient jc = JdbcClient.create(ds);
        TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(ds));

        run("J1 statement_timeout(57014)", () -> tx.execute(s -> {
            jc.sql("SET LOCAL statement_timeout = '300ms'").update();
            return jc.sql("SELECT pg_sleep(1)::text").query(String.class).single();
        }));
        usable("J1 뒤 풀 연결", jc);

        run("J2 lock_timeout(55P03)", () -> {
            try (Connection holder = admin()) {
                holder.setAutoCommit(false);
                exec(holder, "SELECT v FROM probe_t WHERE id = 1 FOR UPDATE");
                try {
                    return tx.execute(s -> {
                        jc.sql("SET LOCAL lock_timeout = '300ms'").update();
                        return jc.sql("SELECT v FROM probe_t WHERE id = 1 FOR UPDATE").query(Integer.class).single();
                    });
                } finally {
                    holder.rollback();
                }
            }
        });

        run("J3 교착(40P01)", DbErrorProbe::deadlock);

        run("J4 직렬화 실패(40001)", () -> {
            TransactionTemplate rr = new TransactionTemplate(tx.getTransactionManager());
            rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return rr.execute(s -> {
                jc.sql("SELECT v FROM probe_t WHERE id = 3").query(Integer.class).single();
                try (Connection other = admin()) {
                    exec(other, "UPDATE probe_t SET v = v + 1 WHERE id = 3");
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return jc.sql("UPDATE probe_t SET v = v + 1 WHERE id = 3").update();
            });
        });

        run("J5 RAISE(P0001)", () -> tx.execute(s -> jc.sql("SELECT raise_fencing()").query(Integer.class).single()));
        usable("J5 뒤 풀 연결", jc);

        run("J6 백엔드 종료(57P01) 뒤 같은 트랜잭션", () -> tx.execute(s -> {
            int pid = jc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            try (Connection a = admin()) {
                exec(a, "SELECT pg_terminate_backend(" + pid + ")");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            sleep(300);
            return jc.sql("SELECT 1").query(Integer.class).single();
        }));
        usable("J6 뒤 풀 연결", jc);

        run("J7 E2 모양(timeout 0 downstream, 2초 뒤 제거) 뒤 같은 트랜잭션", () -> tx.execute(s -> {
            jc.sql("SELECT 1").query(Integer.class).single();
            toxi("POST", "/proxies/pg/toxics", "{\"name\":\"cut\",\"type\":\"timeout\",\"stream\":\"downstream\","
                    + "\"attributes\":{\"timeout\":0}}");
            Thread remover = new Thread(() -> {
                sleep(2000);
                toxi("DELETE", "/proxies/pg/toxics/cut", null);
            });
            remover.start();
            return jc.sql("SELECT 2").query(Integer.class).single();
        }));
        usable("J7 뒤 풀 연결", jc);

        run("J8 커밋 응답 유실(E2 모양을 커밋 직전에 걸고 2초 뒤 제거)", () -> {
            try {
                return tx.execute(s -> {
                    jc.sql("INSERT INTO probe_commit VALUES (1)").update();
                    toxi("POST", "/proxies/pg/toxics", "{\"name\":\"cut\",\"type\":\"timeout\",\"stream\":\"downstream\","
                            + "\"attributes\":{\"timeout\":0}}");
                    Thread remover = new Thread(() -> {
                        sleep(2000);
                        toxi("DELETE", "/proxies/pg/toxics/cut", null);
                    });
                    remover.start();
                    return 1;
                });
            } finally {
                try (Connection a = admin()) {
                    System.out.println("  J8 커밋 여부(관리 연결로 본 probe_commit 행 수) = "
                            + scalar(a, "SELECT count(*) FROM probe_commit"));
                }
            }
        });
        usable("J8 뒤 풀 연결", jc);

        toxi("POST", "/proxies/pg", "{\"enabled\":false}");
        run("J9 프록시 끔 뒤 조회(풀 획득)", () -> jc.sql("SELECT 1").query(Integer.class).single());
        run("J9 프록시 끔 뒤 트랜잭션 시작", () -> tx.execute(s -> jc.sql("SELECT 1").query(Integer.class).single()));
        toxi("POST", "/proxies/pg", "{\"enabled\":true}");
        usable("J9 복구 뒤 풀 연결", jc);
        ds.close();
    }

    interface Case {
        Object call() throws Exception;
    }

    static void run(String name, Case c) {
        long t0 = System.nanoTime();
        System.out.println();
        System.out.println("== " + name);
        try {
            Object out = c.call();
            System.out.printf("  예외 없음(결과 %s, %d ms)%n", out, (System.nanoTime() - t0) / 1_000_000);
        } catch (Throwable e) {
            System.out.printf("  던진 예외(%d ms): %s%n", (System.nanoTime() - t0) / 1_000_000, e.getClass().getName());
            chain("  원인 사슬", e);
            if (e instanceof TransactionSystemException tse && tse.getApplicationException() != null) {
                chain("  applicationException", tse.getApplicationException());
            }
        }
    }

    static void chain(String label, Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < 8; t = t.getCause(), depth++) {
            String state = t instanceof SQLException sql ? " sqlstate=" + sql.getSQLState() : "";
            String msg = t.getMessage() == null ? "" : t.getMessage().replace('\n', ' ');
            if (msg.length() > 110) {
                msg = msg.substring(0, 110) + "...";
            }
            System.out.printf("%s[%d] %s%s | %s%n", label, depth, t.getClass().getName(), state, msg);
        }
    }

    static void usable(String label, JdbcClient jc) {
        try {
            System.out.println("  " + label + ": SELECT 1 = " + jc.sql("SELECT 1").query(Integer.class).single());
        } catch (RuntimeException e) {
            System.out.println("  " + label + ": 실패 " + e.getClass().getName());
        }
    }

    static Object deadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch both = new CountDownLatch(2);
        try (Connection a = admin(); Connection b = admin()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            Future<?> fa = pool.submit(() -> {
                exec(a, "UPDATE probe_t SET v = v + 1 WHERE id = 1");
                both.countDown();
                both.await();
                exec(a, "UPDATE probe_t SET v = v + 1 WHERE id = 2");
                a.rollback();
                return null;
            });
            Future<?> fb = pool.submit(() -> {
                exec(b, "UPDATE probe_t SET v = v + 1 WHERE id = 2");
                both.countDown();
                both.await();
                exec(b, "UPDATE probe_t SET v = v + 1 WHERE id = 1");
                b.rollback();
                return null;
            });
            for (Future<?> f : new Future<?>[] {fa, fb}) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    // 교착 판정은 드라이버 SQLException 이다. 스프링 번역을 보려고 JdbcTemplate 의 번역기에 그대로 넘긴다.
                    if (cause instanceof SQLException sql) {
                        throw new org.springframework.jdbc.support.SQLExceptionSubclassTranslator()
                                .translate("probe", null, sql);
                    }
                    throw e;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return "교착 없음";
    }

    static HikariDataSource pool() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(proxied);
        c.setUsername("postgres");
        c.setPassword(System.getenv("PGPASSWORD"));
        c.setMaximumPoolSize(2);
        c.setMinimumIdle(1);
        c.setConnectionTimeout(1500);
        c.setPoolName("probe");
        c.setConnectionInitSql("SET TIME ZONE 'UTC'");
        return new HikariDataSource(c);
    }

    static Connection admin() throws SQLException {
        return DriverManager.getConnection(direct, "postgres", System.getenv("PGPASSWORD"));
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    static void toxi(String method, String path, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path)).timeout(java.time.Duration.ofSeconds(10));
            b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                    : b.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException("toxiproxy " + method + " " + path + " -> " + r.statusCode() + " " + r.body());
            }
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

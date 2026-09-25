package kr.exchange.journal;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 문장 준비나 연결 획득에 SQL 오류를 넣는 DataSource 감싸개(S5 설계 6-2 DbReadRetryTest). 실제 DB 를 쓰되, 고른 문장의 앞 n 번을
 * 그 오류로 끝낸다. JdbcTemplate 이 드라이버 예외를 번역하므로 앱이 받는 예외 모양이 운영과 같다(08006 은 연결 끊김, 57014 는
 * 문장 취소). 연결 획득 실패는 HikariCP 처럼 SQLTransientConnectionException 이다.
 */
public final class FlakyDataSource extends DelegatingDataSource {

    private final AtomicInteger statementFailures = new AtomicInteger();
    private final AtomicInteger acquireFailures = new AtomicInteger();
    private final AtomicInteger prepared = new AtomicInteger();
    private volatile Predicate<String> matching = sql -> true;
    private volatile Supplier<SQLException> failure = () -> new SQLException("시험 주입", "08006");

    public FlakyDataSource(DataSource target) {
        super(target);
    }

    /** 다음 n 번의 고른 문장 준비를 그 SQLSTATE 의 오류로 끝낸다. */
    public FlakyDataSource failStatements(int n, String sqlState, Predicate<String> which) {
        this.matching = which;
        this.failure = () -> new SQLException("시험 주입 " + sqlState, sqlState);
        statementFailures.set(n);
        return this;
    }

    /** 다음 n 번의 연결 획득을 풀 시간 초과처럼 끝낸다. */
    public FlakyDataSource failAcquire(int n) {
        acquireFailures.set(n);
        return this;
    }

    /** 고른 문장을 준비한 수(실패 포함). */
    public int prepared() {
        return prepared.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (acquireFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new SQLTransientConnectionException(
                    "HikariPool-1 - Connection is not available, request timed out after 3000ms", "08001");
        }
        return faulty(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return faulty(super.getConnection(username, password));
    }

    private Connection faulty(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args != null
                            && args[0] instanceof String sql && matching.test(sql)) {
                        prepared.incrementAndGet();
                        if (statementFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                            throw failure.get();
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}

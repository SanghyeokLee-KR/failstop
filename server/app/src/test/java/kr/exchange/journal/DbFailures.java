package kr.exchange.journal;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.io.EOFException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * S4b 확인(설계 3-1 J1~J9)에서 앱이 받은 DB 예외의 모양. 드라이버 예외를 JdbcTemplate 기본 번역기에 넣어 만든다. 여러 패키지의
 * 시험이 같은 모양을 쓴다.
 */
public final class DbFailures {

    private static final SQLExceptionSubclassTranslator TRANSLATOR = new SQLExceptionSubclassTranslator();

    private DbFailures() {
    }

    /** 서버 오류 필드를 가진 그 SQLSTATE 의 드라이버 예외. */
    public static PSQLException serverError(String state, String message) {
        return new PSQLException(new ServerErrorMessage("SERROR\u0000C" + state + "\u0000M" + message + "\u0000"));
    }

    /** 그 SQLSTATE 를 JdbcTemplate 이 번역한 예외. 번역기가 모르는 SQLSTATE 는 UncategorizedSQLException 이다. */
    public static DataAccessException sql(String state) {
        return translate(serverError(state, "시험 주입 " + state));
    }

    /** 문장 중 연결이 끊긴 조회(J7·J9, 08006). */
    public static DataAccessException connectionFailure() {
        return translate(new PSQLException("An I/O error occurred while sending to the backend.",
                PSQLState.CONNECTION_FAILURE, new EOFException()));
    }

    /** fencing 거부(J5, P0001). */
    public static DataAccessException fencing() {
        return translate(serverError("P0001", "fencing 위반: 리스 만료 또는 holder·epoch 불일치"));
    }

    /** 연결이 끊긴 트랜잭션의 롤백 실패(J6~J8). app 이 null 이면 커밋 직전에 끊긴 J8 이다. */
    public static TransactionSystemException rollbackFailed(Throwable app) {
        TransactionSystemException e = new TransactionSystemException("JDBC rollback failed",
                new SQLException("Connection is closed"));
        if (app != null) {
            e.initApplicationException(app);
        }
        return e;
    }

    /** 풀이 연결을 내주지 못한 조회. */
    public static CannotGetJdbcConnectionException acquireFailure() {
        return new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 3000ms", "08001"));
    }

    /** 풀이 연결을 내주지 못한 트랜잭션 시작(J9). */
    public static CannotCreateTransactionException transactionStartTimeout() {
        return new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException(
                        "exchange-append - Connection is not available, request timed out after 2000ms", "08001"));
    }

    private static DataAccessException translate(SQLException e) {
        DataAccessException translated = TRANSLATOR.translate("시험", "SELECT 1", e);
        return translated != null ? translated : new UncategorizedSQLException("시험", "SELECT 1", e);
    }
}

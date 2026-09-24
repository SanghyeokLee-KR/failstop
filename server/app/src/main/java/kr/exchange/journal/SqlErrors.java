package kr.exchange.journal;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;

/**
 * DB 오류의 SQLSTATE 와 제약 이름 판별(ADR 0034 S3). 게이트웨이의 잔고 부족 응답, 출금의 422, 정산 시점 23514 지표가 함께
 * 쓴다. 잔고 부족은 balances 의 CHECK chk_balance_nonneg 한 곳에서만 나므로(V1:119) SQLSTATE 23514 와 그 제약 이름을 함께
 * 본다. 다른 CHECK 의 23514(예약 행 amount > 0 등)는 잔고 부족이 아니라 결함이라 종전 처리(500)에 남긴다.
 *
 * 제약 이름은 PostgreSQL 서버 오류 필드(constraint)에서 읽는다. 소유자 권한 함수 두 겹 안에서 난 위반도 이 필드가 그대로
 * 온다(S3 설계 3절 P6). 서버 필드가 없는 예외(다른 드라이버, 시험이 만든 SQLException)는 메시지에 따옴표로 둘러싼 제약
 * 이름이 있는지로 본다. PostgreSQL 은 메시지 언어와 무관하게 제약 이름을 따옴표 안에 싣는다.
 */
public final class SqlErrors {

    /** 잔고 CHECK 위반. */
    public static final String CHECK_VIOLATION = "23514";
    /** 잔고 행 CHECK 제약 이름(V1__init.sql). */
    public static final String BALANCE_CONSTRAINT = "chk_balance_nonneg";

    private SqlErrors() {
    }

    /** 원인 사슬에서 처음 만나는 SQLSTATE. 없으면 null 이다. */
    public static String sqlState(Throwable e) {
        return TransientDbErrors.sqlState(e);
    }

    /** 원인 사슬에서 처음 만나는 PostgreSQL 서버 오류의 제약 이름. 없으면 null 이다. */
    public static String constraintName(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                ServerErrorMessage server = psql.getServerErrorMessage();
                if (server != null && server.getConstraint() != null) {
                    return server.getConstraint();
                }
            }
        }
        return null;
    }

    /** 잔고 부족(23514 chk_balance_nonneg)인가. */
    public static boolean isInsufficientFunds(Throwable e) {
        if (!CHECK_VIOLATION.equals(sqlState(e))) {
            return false;
        }
        String constraint = constraintName(e);
        if (constraint != null) {
            return BALANCE_CONSTRAINT.equals(constraint);
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getMessage() != null
                    && sql.getMessage().contains("\"" + BALANCE_CONSTRAINT + "\"")) {
                return true;
            }
        }
        return false;
    }
}

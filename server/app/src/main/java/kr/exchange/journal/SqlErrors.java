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

    /**
     * 강등 기록과 치명 로그의 SQLSTATE 표기. 원인 사슬에 없고 롤백 실패 뒤에 가려진 앱 예외에 있으면 '08006(앱 예외)'이고,
     * 둘 다 없으면 '-' 다.
     */
    public static String stateLabel(Throwable e) {
        String state = sqlState(e);
        if (state != null) {
            return state;
        }
        Throwable app = applicationException(e);
        String hidden = app == null ? null : sqlState(app);
        return hidden == null ? "-" : hidden + "(앱 예외)";
    }

    /**
     * 원인 사슬을 'UncategorizedSQLException > PSQLException(55P03)' 꼴로 여섯 단계까지 적는다. 롤백 실패면 ' | 앱 예외 '
     * 뒤에 가려진 원래 예외의 사슬을 붙인다. 강등 기록, 치명 로그, 재생 미완료와 접수의 DB 연결 장애 로그가 같이 쓴다.
     */
    public static String describe(Throwable e) {
        StringBuilder out = new StringBuilder();
        appendChain(out, e);
        Throwable app = applicationException(e);
        if (app != null) {
            out.append(" | 앱 예외 ");
            appendChain(out, app);
        }
        return out.toString();
    }

    private static Throwable applicationException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.transaction.TransactionSystemException tse) {
                return tse.getApplicationException();
            }
        }
        return null;
    }

    private static void appendChain(StringBuilder out, Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < 6; t = t.getCause(), depth++) {
            if (depth > 0) {
                out.append(" > ");
            }
            out.append(t.getClass().getSimpleName());
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                out.append('(').append(sql.getSQLState()).append(')');
            }
        }
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

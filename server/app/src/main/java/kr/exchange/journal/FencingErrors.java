package kr.exchange.journal;

import org.springframework.dao.DataAccessException;

import java.sql.SQLException;

/** assert_current_leader 거부(P0001) 판별. command·event append·정산이 같은 강등 규칙을 쓴다. */
public final class FencingErrors {

    private FencingErrors() {
    }

    /** RAISE(P0001, 'fencing 위반' 문구)만 강등 사유로 인정한다. */
    public static boolean isFencingRejection(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql
                    && "P0001".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains("fencing 위반")) {
                return true;
            }
        }
        return false;
    }
}

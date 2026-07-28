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

    /**
     * 거부 메시지 첫 줄. assert_current_leader는 holder·epoch 불일치와 리스 만료를 서로 다른
     * 문구로 던지는데, 강등 로그가 상수 사유만 남기면 사후에 둘을 가를 수 없다(E2 라이브에서
     * 실제로 갈리지 않았다). 원문을 사유에 실어 다음 라이브가 조건을 특정하게 한다.
     */
    public static String rejectionDetail(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getMessage() != null) {
                int lineEnd = sql.getMessage().indexOf('\n');
                return lineEnd < 0 ? sql.getMessage() : sql.getMessage().substring(0, lineEnd);
            }
        }
        return e.getMessage();
    }
}

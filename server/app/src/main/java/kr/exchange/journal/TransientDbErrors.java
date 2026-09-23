package kr.exchange.journal;

import java.sql.SQLException;
import java.util.Set;

/**
 * 트랜잭션을 처음부터 다시 돌리면 풀릴 수 있는 DB 오류 판별. 정산 sink 의 제한 재시도가 쓴다.
 *
 * 잠금 대기 초과(55P03), 문장 취소(57014), 교착 희생(40P01), 직렬화 실패(40001)만 해당한다. 넷 다 커밋 전에
 * 트랜잭션이 롤백되는 오류라 다시 돌려도 이중 기록이 생기지 않는다. fencing 거부(P0001)와 무결성 위반(23xxx)은
 * 여기에 넣지 않는다. 다시 돌려도 같은 결과이고, 그 판정은 강등이나 루프 정지로 끝나야 한다.
 */
public final class TransientDbErrors {

    private static final Set<String> RETRYABLE = Set.of("55P03", "57014", "40P01", "40001");

    private TransientDbErrors() {
    }

    public static boolean isRetryable(Throwable e) {
        String state = sqlState(e);
        return state != null && RETRYABLE.contains(state);
    }

    /** 원인 사슬에서 처음 만나는 SQLSTATE. 없으면 null 이다. */
    public static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}

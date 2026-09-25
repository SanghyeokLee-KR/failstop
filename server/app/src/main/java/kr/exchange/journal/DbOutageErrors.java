package kr.exchange.journal;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLTransientConnectionException;
import java.util.Set;

/**
 * DB 에 닿지 못한 실패의 분류(API 설계 §2-5-c, S4b 설계 4-1). 앞의 규칙이 먼저다.
 *
 * 획득 실패는 풀에서 연결을 얻지 못해 문장을 보내지 않은 것이다. 연결 끊김은 얻은 연결이 문장·커밋·롤백 도중 끊긴 것이라
 * 커밋 여부를 모를 수 있다. 롤백까지 실패하면 원래 예외는 TransactionSystemException 의 앱 예외에만 남고 원인 사슬에는
 * SQLSTATE 가 없다. 자원 부족(53xxx)은 서버가 그 문장을 거부한 것이다. 일시 오류 넷(TransientDbErrors), fencing 거부,
 * 54xxx·58xxx 는 여기 들지 않는다. 스프링이 54xxx·58xxx 를 자원 실패로 번역해도 연결이 아니라 문장이나 서버의 결함이다.
 */
public final class DbOutageErrors {

    public enum Kind { ACQUIRE_FAILURE, CONNECTION_LOST, RESOURCE_SHORTAGE, NONE }

    // 관리자 종료, 비정상 종료, 기동 중. 그 연결의 백엔드가 사라진 것이라 연결 예외(08)와 같이 본다.
    private static final Set<String> TERMINATED = Set.of("57P01", "57P02", "57P03");

    private DbOutageErrors() {
    }

    public static Kind kind(Throwable e) {
        if (inChain(e, CannotGetJdbcConnectionException.class) || acquireTimeout(e)) {
            return Kind.ACQUIRE_FAILURE;
        }
        String state = TransientDbErrors.sqlState(e);
        if (inChain(e, TransactionSystemException.class) || inChain(e, CannotCreateTransactionException.class)
                || (state != null && (state.startsWith("08") || TERMINATED.contains(state)))
                || (state == null && inChain(e, DataAccessResourceFailureException.class))) {
            return Kind.CONNECTION_LOST;
        }
        if (state != null && state.startsWith("53")) {
            return Kind.RESOURCE_SHORTAGE;
        }
        return Kind.NONE;
    }

    /** 획득 실패, 연결 끊김, 자원 부족 가운데 하나인가. */
    public static boolean isOutage(Throwable e) {
        return kind(e) != Kind.NONE;
    }

    /** 트랜잭션 시작이 풀의 획득 시간 초과로 끝났는가. HikariCP 는 그 초과를 SQLTransientConnectionException 으로 던진다. */
    private static boolean acquireTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CannotCreateTransactionException) {
                return inChain(t.getCause(), SQLTransientConnectionException.class);
            }
        }
        return false;
    }

    private static boolean inChain(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}

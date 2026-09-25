package kr.exchange.journal;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.LinkedHashMap;
import java.util.Map;

import static kr.exchange.journal.DbOutageErrors.Kind.ACQUIRE_FAILURE;
import static kr.exchange.journal.DbOutageErrors.Kind.CONNECTION_LOST;
import static kr.exchange.journal.DbOutageErrors.Kind.NONE;
import static kr.exchange.journal.DbOutageErrors.Kind.RESOURCE_SHORTAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * DB 불통 분류(S4b 설계 4-1, API 설계 §2-5-c). J1~J9 모양과 연결이 아닌 자원 실패를 넣어 네 분류와 규칙 순서를 본다. 스프링
 * 예외는 드라이버 예외를 JdbcTemplate 기본 번역기에 넣어 만든다.
 */
class DbOutageErrorsTest {

    @Test
    void classifiesTheProbedShapes() {
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("J1 57014", new Object[] {DbFailures.sql("57014"), NONE});
        cases.put("J2 55P03", new Object[] {DbFailures.sql("55P03"), NONE});
        cases.put("J3 40P01", new Object[] {DbFailures.sql("40P01"), NONE});
        cases.put("J4 40001", new Object[] {DbFailures.sql("40001"), NONE});
        cases.put("J5 P0001", new Object[] {DbFailures.fencing(), NONE});
        cases.put("J6 롤백 실패(앱 예외 57P01)", new Object[] {DbFailures.rollbackFailed(DbFailures.sql("57P01")),
                CONNECTION_LOST});
        cases.put("J7 롤백 실패(앱 예외 08006)", new Object[] {DbFailures.rollbackFailed(DbFailures.connectionFailure()),
                CONNECTION_LOST});
        cases.put("J8 커밋 직전 롤백 실패", new Object[] {DbFailures.rollbackFailed(null), CONNECTION_LOST});
        cases.put("J9 끊긴 풀 연결 08006", new Object[] {DbFailures.connectionFailure(), CONNECTION_LOST});
        cases.put("J9 트랜잭션 시작 획득 초과", new Object[] {DbFailures.transactionStartTimeout(), ACQUIRE_FAILURE});
        cases.put("조회 획득 실패", new Object[] {DbFailures.acquireFailure(), ACQUIRE_FAILURE});
        cases.put("57P01 관리자 종료", new Object[] {DbFailures.sql("57P01"), CONNECTION_LOST});
        cases.put("57P03 기동 중", new Object[] {DbFailures.sql("57P03"), CONNECTION_LOST});
        cases.put("SQLSTATE 없는 자원 실패", new Object[] {
                new DataAccessResourceFailureException("connection refused"), CONNECTION_LOST});
        cases.put("그 밖의 트랜잭션 시작 실패", new Object[] {new CannotCreateTransactionException("setAutoCommit",
                DbFailures.serverError("08003", "connection does not exist")), CONNECTION_LOST});
        cases.put("53200 메모리 부족", new Object[] {DbFailures.sql("53200"), RESOURCE_SHORTAGE});
        cases.put("53300 연결 수 초과", new Object[] {DbFailures.sql("53300"), RESOURCE_SHORTAGE});
        cases.put("54000 프로그램 한도", new Object[] {DbFailures.sql("54000"), NONE});
        cases.put("58030 I/O 오류", new Object[] {DbFailures.sql("58030"), NONE});
        cases.put("23514 CHECK", new Object[] {DbFailures.sql("23514"), NONE});
        cases.put("DB 밖 예외", new Object[] {new IllegalArgumentException("주입"), NONE});

        cases.forEach((name, c) -> {
            Throwable e = (Throwable) c[0];
            DbOutageErrors.Kind expected = (DbOutageErrors.Kind) c[1];
            assertEquals(expected, DbOutageErrors.kind(e), name);
            assertEquals(expected != NONE, DbOutageErrors.isOutage(e), name);
        });
    }

    /** 번역 모양 자체가 확인(설계 3-1)과 같아야 위 분류가 실제 경로를 본다. */
    @Test
    void translatedShapesMatchTheProbe() {
        assertInstanceOf(QueryTimeoutException.class, DbFailures.sql("57014"));
        assertInstanceOf(UncategorizedSQLException.class, DbFailures.sql("55P03"));
        assertInstanceOf(UncategorizedSQLException.class, DbFailures.fencing());
        assertInstanceOf(DataAccessResourceFailureException.class, DbFailures.connectionFailure());
        assertInstanceOf(DataAccessResourceFailureException.class, DbFailures.sql("54000"));
        assertInstanceOf(DataAccessResourceFailureException.class, DbFailures.sql("53200"));
    }

    /** 획득 실패가 연결 끊김보다 먼저다. 풀의 획득 초과는 마지막 연결 실패의 08001 을 원인에 싣는다. */
    @Test
    void acquireFailureRuleComesBeforeTheConnectionState() {
        assertEquals("08001", SqlErrors.sqlState(DbFailures.acquireFailure()));
        assertEquals(ACQUIRE_FAILURE, DbOutageErrors.kind(DbFailures.acquireFailure()));
        // 트랜잭션 시작 실패는 연결 끊김 규칙이 53 보다 먼저다.
        assertEquals(CONNECTION_LOST, DbOutageErrors.kind(new CannotCreateTransactionException("begin",
                DbFailures.serverError("53300", "too many connections"))));
    }

    @Test
    void describeShowsTheChainAndTheHiddenApplicationException() {
        assertEquals("UncategorizedSQLException > PSQLException(55P03)", SqlErrors.describe(DbFailures.sql("55P03")));
        assertEquals("55P03", SqlErrors.stateLabel(DbFailures.sql("55P03")));
        Throwable rollback = DbFailures.rollbackFailed(DbFailures.connectionFailure());
        assertEquals("TransactionSystemException > SQLException | 앱 예외 DataAccessResourceFailureException"
                + " > PSQLException(08006) > EOFException", SqlErrors.describe(rollback));
        assertEquals("08006(앱 예외)", SqlErrors.stateLabel(rollback));
        assertEquals("-", SqlErrors.stateLabel(new IllegalStateException("주입")));
    }
}

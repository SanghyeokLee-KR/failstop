package kr.exchange.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 정산 재시도 대상은 커밋 전에 롤백되는 잠금·취소·교착·직렬화 오류뿐이다. fencing 과 무결성은 그대로 치명이다. */
class TransientDbErrorsTest {

    @ParameterizedTest
    @ValueSource(strings = {"55P03", "57014", "40P01", "40001"})
    void lockCancelDeadlockAndSerializationAreRetryable(String sqlState) {
        assertTrue(TransientDbErrors.isRetryable(
                new UncategorizedSQLException("정산", "SELECT 1", new SQLException("x", sqlState))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"23514", "23505", "23503", "08006", "53300", "42P01", "55000"})
    void integrityConnectionAndSchemaErrorsAreNotRetryable(String sqlState) {
        assertFalse(TransientDbErrors.isRetryable(
                new UncategorizedSQLException("정산", "SELECT 1", new SQLException("x", sqlState))));
    }

    @Test
    void fencingRejectionIsNotRetryable() {
        UncategorizedSQLException fencing = new UncategorizedSQLException("정산", "SELECT assert_and_lock_leader(?, ?)",
                new SQLException("ERROR: fencing 위반: holder·epoch 불일치", "P0001"));
        assertTrue(FencingErrors.isFencingRejection(fencing));
        assertFalse(TransientDbErrors.isRetryable(fencing));
    }

    @Test
    void readsSqlStateThroughSpringTranslationWrappers() {
        SQLException lock = new SQLException("canceling statement due to lock timeout", "55P03");
        assertTrue(TransientDbErrors.isRetryable(new CannotAcquireLockException("정산", lock)));
        SQLException canceled = new SQLException("canceling statement due to statement timeout", "57014");
        assertTrue(TransientDbErrors.isRetryable(new QueryTimeoutException("정산", canceled)));
        SQLException check = new SQLException("violates check constraint chk_balance_nonneg", "23514");
        assertFalse(TransientDbErrors.isRetryable(new DataIntegrityViolationException("정산", check)));
    }

    @Test
    void firstSqlStateInCauseChainDecides() {
        SQLException inner = new SQLException("deadlock detected", "40P01");
        SQLException outer = new SQLException("wrapped", "23514", inner);
        assertEquals("23514", TransientDbErrors.sqlState(new UncategorizedSQLException("정산", "x", outer)));
        assertFalse(TransientDbErrors.isRetryable(new UncategorizedSQLException("정산", "x", outer)));
    }

    @Test
    void errorsWithoutSqlStateAreNotRetryable() {
        assertNull(TransientDbErrors.sqlState(new IllegalStateException("부분 적재")));
        assertFalse(TransientDbErrors.isRetryable(new IllegalStateException("부분 적재")));
        assertFalse(TransientDbErrors.isRetryable(new StaleLeaderException("stale 리더 정산 거부")));
    }
}

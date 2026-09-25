package kr.exchange.journal;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 한 마켓 치명 실험(ADR 0034 S7, S7 설계 5-2 Q10)의 주입 SQLSTATE 분류. 실험은 폐기용 DB 의 trades 트리거가 사용자 정의 SQLSTATE
 * S7F01 로 한 번만 실패하게 한다. 이 값은 정산 재시도 대상 넷(55P03·57014·40P01·40001), DB 연결 장애(08xxx, 57P0x, 53xxx), fencing
 * 거부(P0001 의 fencing 문구), 잔고 부족(23514 chk_balance_nonneg) 어디에도 들지 않아야 한다. 그래야 정산이 재시도하지 않고 그 마켓
 * 라이터를 치명(WRITER_FATAL)으로 올리며, 정산 23514 지표·DB 장애 503 분류와 섞이지 않는다. 스택에서의 강등 확인은 Q10 이 한다.
 */
class InjectedMarketFaultClassificationTest {

    private static final String INJECTED = "S7F01";

    @Test
    void injectedStateIsNeitherRetryableNorOutageNorFencingNorInsufficientFunds() {
        SQLException raised = new SQLException("ERROR: S7 Q10 한 번만 실패하는 체결 행(market=2)", INJECTED);
        for (org.springframework.dao.DataAccessException wrapped : new org.springframework.dao.DataAccessException[] {
                new UncategorizedSQLException("정산", "INSERT INTO trades ...", raised),
                new DataIntegrityViolationException("정산", raised)}) {
            assertEquals(INJECTED, TransientDbErrors.sqlState(wrapped));
            assertFalse(TransientDbErrors.isRetryable(wrapped), "주입 오류를 정산이 재시도한다");
            assertFalse(DbOutageErrors.isOutage(wrapped), "주입 오류를 DB 연결 장애로 본다");
            assertEquals(DbOutageErrors.Kind.NONE, DbOutageErrors.kind(wrapped));
            assertFalse(FencingErrors.isFencingRejection(wrapped), "주입 오류를 fencing 거부로 본다");
            assertFalse(SqlErrors.isInsufficientFunds(wrapped), "주입 오류를 잔고 부족으로 본다");
        }
    }
}

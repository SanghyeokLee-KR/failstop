package kr.exchange.api.transfer;

import com.zaxxer.hikari.HikariDataSource;
import kr.exchange.api.common.ApiException;
import kr.exchange.engine.lease.PartitionLeaseDb;
import kr.exchange.ledger.AccountBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 출금 잔고 부족은 계약대로 422 insufficient_funds 다(API 설계 §4-E, ADR 0034 S3, S3 설계 4-10). 요청 행과 원장 거래가
 * 남지 않는다. 종전 코드는 23514 를 잡지 않아 범용 처리기의 500 server_error 였다(S3 설계 2-4).
 */
class TransferInsufficientFundsTest {

    @Test
    void withdrawMoreThanAvailableIs422AndLeavesNothing() {
        PartitionLeaseDb db = PartitionLeaseDb.create("transfer_short");
        try (HikariDataSource pool = db.ownerPool()) {
            JdbcClient jdbc = JdbcClient.create(pool);
            AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
            bootstrap.asset(1, "KRW", "Korean Won", "FIAT", 0);
            long user = bootstrap.user("withdraw@transfer.test");
            bootstrap.userAccounts(user, 1);
            bootstrap.deposit(user, 1, new BigDecimal("1000"));
            TransferService transfers = new TransferService(jdbc, new TransactionTemplate(new JdbcTransactionManager(pool)));
            long txBefore = count(jdbc, "SELECT count(*) FROM ledger_transactions");

            ApiException e = assertThrows(ApiException.class,
                    () -> transfers.request(user, "WITHDRAW", "KRW", "5000", "short-1"));

            assertEquals(422, e.status());
            assertEquals("insufficient_funds", e.name());
            assertEquals(0, count(jdbc, "SELECT count(*) FROM transfer_requests"), "잔고 부족 출금 요청 행이 남았다");
            assertEquals(txBefore, count(jdbc, "SELECT count(*) FROM ledger_transactions"));
            // 양성 축: 잔고 안의 출금은 잠기고 대기(REQUESTED)로 남는다. 같은 키 재요청은 새 판정이다(행이 없었으므로).
            TransferService.Result ok = transfers.request(user, "WITHDRAW", "KRW", "400", "short-1");
            assertFalse(ok.replayed());
            assertEquals("REQUESTED", String.valueOf(ok.view().get("status")));
        }
    }

    private static long count(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

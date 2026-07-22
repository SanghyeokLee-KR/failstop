package kr.exchange.api.auth;

import kr.exchange.ledger.AccountBootstrap;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 데모 세션(§3-7) 대상 계정 시드. 사용자·계정 페어를 보장하고 초기 자산을
 * GENESIS 입금(post_ledger_transaction 단일 경로)으로 채운다.
 *
 * 입금은 전 자산 잔고 합이 0일 때만 수행한다. 세션 재발급마다 입금하면 데모
 * 리셋 없이 자산이 무한 증식한다.
 */
public class DemoUserProvisioner {

    public record DemoUser(long userId, String externalId, String email) {
    }

    private final JdbcClient jdbc;
    private final AccountBootstrap bootstrap;
    private final String email;
    private final Map<String, BigDecimal> deposits;

    public DemoUserProvisioner(JdbcClient jdbc, AccountBootstrap bootstrap, String email,
                               Map<String, BigDecimal> deposits) {
        this.jdbc = jdbc;
        this.bootstrap = bootstrap;
        this.email = email;
        this.deposits = new LinkedHashMap<>(deposits);
    }

    public synchronized DemoUser ensure() {
        long userId = bootstrap.user(email);
        List<Map<String, Object>> assets = jdbc.sql("SELECT id, symbol FROM assets ORDER BY id")
                .query()
                .listOfRows();
        for (Map<String, Object> asset : assets) {
            bootstrap.userAccounts(userId, ((Number) asset.get("id")).intValue());
        }
        BigDecimal total = jdbc.sql(
                        "SELECT COALESCE(sum(available + locked), 0) FROM user_balances WHERE user_id = ?")
                .params(userId)
                .query(BigDecimal.class)
                .single();
        if (total.signum() == 0) {
            for (Map<String, Object> asset : assets) {
                BigDecimal amount = deposits.get((String) asset.get("symbol"));
                if (amount != null && amount.signum() > 0) {
                    bootstrap.deposit(userId, ((Number) asset.get("id")).intValue(), amount);
                }
            }
        }
        String externalId = jdbc.sql("SELECT external_id::text FROM users WHERE id = ?")
                .params(userId)
                .query(String.class)
                .single();
        return new DemoUser(userId, externalId, email);
    }
}

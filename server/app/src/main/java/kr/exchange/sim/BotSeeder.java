package kr.exchange.sim;

import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.ledger.AccountBootstrap;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;

/**
 * 봇 계정·자금·API 키 시드. 시드 타임 전용이며, 이후 봇의 모든 거래 행위는
 * 외부 클라이언트 규약(§3 서명 HTTP)만 사용한다(하네스 계약: 봇=외부 관점).
 */
public class BotSeeder {

    private final JdbcClient jdbc;
    private final AccountBootstrap bootstrap;
    private final DemoKeySeeder keys;

    public BotSeeder(JdbcClient jdbc, AccountBootstrap bootstrap, DemoKeySeeder keys) {
        this.jdbc = jdbc;
        this.bootstrap = bootstrap;
        this.keys = keys;
    }

    public BotCredentials seed(String email, String marketSymbol,
                               BigDecimal quoteAmount, BigDecimal baseAmount) {
        Integer baseAsset = jdbc.sql("SELECT base_asset_id FROM markets WHERE symbol = ?")
                .params(marketSymbol).query(Integer.class).optional()
                .orElseThrow(() -> new IllegalStateException("미시드 마켓: " + marketSymbol));
        int quoteAsset = jdbc.sql("SELECT quote_asset_id FROM markets WHERE symbol = ?")
                .params(marketSymbol).query(Integer.class).single();

        jdbc.sql("INSERT INTO users (email, kind) VALUES (?, 'BOT') ON CONFLICT (email) DO NOTHING")
                .params(email)
                .update();
        long userId = jdbc.sql("SELECT id FROM users WHERE email = ?")
                .params(email).query(Long.class).single();
        bootstrap.userAccounts(userId, baseAsset);
        bootstrap.userAccounts(userId, quoteAsset);

        BigDecimal total = jdbc.sql(
                        "SELECT COALESCE(sum(available + locked), 0) FROM user_balances WHERE user_id = ?")
                .params(userId).query(BigDecimal.class).single();
        if (total.signum() == 0) {
            bootstrap.deposit(userId, quoteAsset, quoteAmount);
            bootstrap.deposit(userId, baseAsset, baseAmount);
        }
        // label null: 재기동 시드마다 새 키 발급을 허용한다(부분 유니크는 label 비null 한정).
        DemoKeySeeder.IssuedKey key = keys.issue(userId, "TRADE", null);
        return new BotCredentials(userId, email, key.accessKey(), key.secretKey());
    }
}

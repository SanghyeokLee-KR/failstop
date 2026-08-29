package kr.exchange.harness;

import kr.exchange.api.auth.DemoKeySeeder;
import kr.exchange.ledger.AccountBootstrap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k6 부하 계정 발급(하네스 검증설계 §7-3 seed 잡). load/README가 "seed 잡이 발급"이라고
 * 규정한 자리인데 그 잡이 없어 k6를 돌릴 키가 없었다.
 *
 * 키 시크릿은 발급 시점에만 평문으로 존재한다(api_keys.enc_secret은 마스터 키로 암호화).
 * 그래서 DB를 나중에 뒤져 얻을 수 없고 발급 응답이 유일한 통로다. k6는 이 응답을
 * secrets/k6_keys.json으로 받아 쓴다.
 *
 * 하네스 전용이라 exchange.harness.enabled에서만 등록되고 공개 LB에 라우팅되지 않는다.
 * 운영에서 이 경로가 열리면 임의 거래 키를 발급하는 구멍이 되므로 게이트를 지운 채로
 * 배포하지 않는다(HarnessController 머리말과 같은 규율).
 */
@Component
public class LoadKeySeeder {

    /** 한 번에 만들 수 있는 계정 상한. k6 SharedArray가 키를 라운드로빈으로 쓴다 */
    private static final int MAX_COUNT = 50;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final DemoKeySeeder keys;
    private final AccountBootstrap bootstrap;

    public LoadKeySeeder(JdbcClient jdbc, TransactionTemplate tx, DemoKeySeeder keys,
                         AccountBootstrap bootstrap) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.keys = keys;
        this.bootstrap = bootstrap;
    }

    /**
     * 부하 계정 count개를 만들고 키를 발급한다. 계정 이메일은 순번 고정이라 재호출이
     * 같은 계정에 키를 더 발급할 뿐 계정을 늘리지 않는다. 자금은 이미 있으면 건너뛴다.
     *
     * 자금은 GENESIS에서 내려온다(AccountBootstrap.deposit). 자산 합 0 불변식은 그 경로가
     * 지키므로 여기서 별도 보정을 하지 않는다.
     */
    public List<Map<String, String>> seed(String marketSymbol, int count,
                                          BigDecimal quoteAmount, BigDecimal baseAmount) {
        int n = Math.max(1, Math.min(count, MAX_COUNT));
        Integer baseAsset = jdbc.sql("SELECT base_asset_id FROM markets WHERE symbol = ?")
                .params(marketSymbol).query(Integer.class).optional()
                .orElseThrow(() -> new IllegalStateException("미시드 마켓: " + marketSymbol));
        int quoteAsset = jdbc.sql("SELECT quote_asset_id FROM markets WHERE symbol = ?")
                .params(marketSymbol).query(Integer.class).single();

        List<Map<String, String>> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            String email = "k6-load-" + i + "@demo.local";
            // 계정 생성·자금·키 발급을 한 트랜잭션에 묶는다. 중간 실패로 자금 없는 키가
            // 남으면 k6가 전량 주문 거부를 받고 그것을 SLO 위반으로 오판한다.
            out.add(tx.execute(s -> issueFor(email, baseAsset, quoteAsset,
                    quoteAmount, baseAmount)));
        }
        return out;
    }

    private Map<String, String> issueFor(String email, int baseAsset, int quoteAsset,
                                         BigDecimal quoteAmount, BigDecimal baseAmount) {
        // 부하 계정은 사람이 아니다. kind='BOT'으로 표시해 회원 운영 목록(§6-E)에 섞이지 않게 한다.
        jdbc.sql("INSERT INTO users (email, kind) VALUES (?, 'BOT') ON CONFLICT (email) DO NOTHING")
                .params(email).update();
        long userId = jdbc.sql("SELECT id FROM users WHERE email = ?")
                .params(email).query(Long.class).single();
        bootstrap.userAccounts(userId, baseAsset);
        bootstrap.userAccounts(userId, quoteAsset);

        BigDecimal held = jdbc.sql(
                        "SELECT COALESCE(sum(available + locked), 0) FROM user_balances WHERE user_id = ?")
                .params(userId).query(BigDecimal.class).single();
        if (held.signum() == 0) {
            bootstrap.deposit(userId, quoteAsset, quoteAmount);
            bootstrap.deposit(userId, baseAsset, baseAmount);
        }

        DemoKeySeeder.IssuedKey key = keys.issue(userId, "TRADE", "k6-load");
        Map<String, String> row = new LinkedHashMap<>();
        row.put("access_key", key.accessKey());
        row.put("secret", key.secretKey());
        return row;
    }
}

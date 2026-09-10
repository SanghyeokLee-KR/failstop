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
 * k6 부하 계정 발급(하네스 검증설계 §7-3 seed 잡). load/README가 규정한 seed 잡의 구현이다.
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
    /**
     * 부하 계정을 만든다. marketSymbol 은 쉼표로 여러 마켓을 받는다.
     *
     * 왜 여럿인가. 마켓별 라이터 실측(ADR 0021)이 주문을 여러 마켓에 흩는데, 종전에는
     * 마켓 하나의 기준 자산과 원화만 넣어 줬다. 그러면 다른 마켓의 매도 주문이 그 자산
     * 계정을 못 찾는다. 2026-09-10 실측에서 그 주문이 접수를 통과한 뒤 정산에서 예외를
     * 만나 단일 라이터가 fail-fast 로 멈췄고 리더십까지 함께 내려놨다(정본 한계 40).
     * 실측을 하려면 계정이 먼저 있어야 한다.
     */
    public List<Map<String, String>> seed(String marketSymbol, int count,
                                          BigDecimal quoteAmount, BigDecimal baseAmount) {
        int n = Math.max(1, Math.min(count, MAX_COUNT));
        List<String> symbols = java.util.Arrays.stream(marketSymbol.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
        if (symbols.isEmpty()) {
            throw new IllegalStateException("마켓이 비었다: " + marketSymbol);
        }
        // 기준 자산은 마켓마다 다르고 상대 자산은 보통 원화 하나다. 중복을 지운다.
        java.util.LinkedHashSet<Integer> baseAssets = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<Integer> quoteAssets = new java.util.LinkedHashSet<>();
        for (String symbol : symbols) {
            Integer base = jdbc.sql("SELECT base_asset_id FROM markets WHERE symbol = ?")
                    .params(symbol).query(Integer.class).optional()
                    .orElseThrow(() -> new IllegalStateException("미시드 마켓: " + symbol));
            baseAssets.add(base);
            quoteAssets.add(jdbc.sql("SELECT quote_asset_id FROM markets WHERE symbol = ?")
                    .params(symbol).query(Integer.class).single());
        }

        List<Map<String, String>> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            String email = "k6-load-" + i + "@demo.local";
            // 계정 생성·자금·키 발급을 한 트랜잭션에 묶는다. 중간 실패로 자금 없는 키가
            // 남으면 k6가 전량 주문 거부를 받고 그것을 SLO 위반으로 오판한다.
            out.add(tx.execute(s -> issueFor(email, baseAssets, quoteAssets,
                    quoteAmount, baseAmount)));
        }
        return out;
    }

    private Map<String, String> issueFor(String email,
                                         java.util.Collection<Integer> baseAssets,
                                         java.util.Collection<Integer> quoteAssets,
                                         BigDecimal quoteAmount, BigDecimal baseAmount) {
        // 부하 계정은 사람이 아니다. kind='BOT'으로 표시해 회원 운영 목록(§6-E)에 섞이지 않게 한다.
        jdbc.sql("INSERT INTO users (email, kind) VALUES (?, 'BOT') ON CONFLICT (email) DO NOTHING")
                .params(email).update();
        long userId = jdbc.sql("SELECT id FROM users WHERE email = ?")
                .params(email).query(Long.class).single();
        // 계정은 매번 보장한다. 자금과 달리 있으면 아무 일도 하지 않으므로, 마켓이 뒤에
        // 늘어도 이 호출 한 번으로 따라잡는다.
        for (int asset : quoteAssets) {
            bootstrap.userAccounts(userId, asset);
        }
        for (int asset : baseAssets) {
            bootstrap.userAccounts(userId, asset);
        }

        BigDecimal held = jdbc.sql(
                        "SELECT COALESCE(sum(available + locked), 0) FROM user_balances WHERE user_id = ?")
                .params(userId).query(BigDecimal.class).single();
        if (held.signum() == 0) {
            for (int asset : quoteAssets) {
                bootstrap.deposit(userId, asset, quoteAmount);
            }
            for (int asset : baseAssets) {
                bootstrap.deposit(userId, asset, baseAmount);
            }
        }

        // 기존 활성 키를 먼저 회수한다. uq_apikey_active가 (user_id, label) 활성 유일이라
        // 회수 없이 재발급하면 duplicate key로 500이고, 그 500 본문이 키 파일을 덮어쓰면
        // 시크릿은 발급 시점에만 평문이라 복구 경로가 없다.
        jdbc.sql("UPDATE api_keys SET deleted_at = now() "
                        + "WHERE user_id = ? AND label = 'k6-load' AND deleted_at IS NULL")
                .params(userId).update();
        DemoKeySeeder.IssuedKey key = keys.issue(userId, "TRADE", "k6-load");
        // harness.loadtest_accounts 매핑을 함께 적재한다(검증설계 §2-5). C16(ACK 원장이
        // command_journal의 부분집합인가)은 k6 stdout의 ACK 라인을 loadtest_acks로 넣어야
        // 성립하는데, 그 적재가 access_key를 user_id로 바꾸는 단계를 거친다. 이 표가 비면
        // 변환이 불가능해 loadtest_acks가 0행이 되고, C16 조인이 빈 테이블 대조로 공허
        // 통과한다. 판정이 통과했는데 판정 대상이 없는 상태다.
        jdbc.sql("""
                        INSERT INTO harness.loadtest_accounts (access_key, user_id)
                        VALUES (?, ?)
                        ON CONFLICT (access_key) DO UPDATE SET user_id = EXCLUDED.user_id
                        """)
                .params(key.accessKey(), userId).update();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("access_key", key.accessKey());
        row.put("secret", key.secretKey());
        return row;
    }
}

package kr.exchange.deploy;

import kr.exchange.config.InitialMarketCatalog;
import kr.exchange.ledger.AccountBootstrap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/** 최초 배포용 카탈로그와 빈 시스템 계정. 회원·사용자 계정·가상 자금·봇은 만들지 않는다. */
public final class SeedMarketCatalog {

    private SeedMarketCatalog() { }

    public static void main(String[] args) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MigrateSchema.required("MIGRATION_JDBC_URL"), MigrateSchema.required("MIGRATION_DB_USER"),
                MigrateSchema.required("MIGRATION_DB_PASSWORD"));
        new TransactionTemplate(new JdbcTransactionManager(dataSource))
                .executeWithoutResult(status -> seed(JdbcClient.create(dataSource)));
    }

    public static void seed(JdbcClient jdbc) {
        AccountBootstrap bootstrap = new AccountBootstrap(jdbc);
        for (var asset : InitialMarketCatalog.ASSETS) {
            // 가입·수수료·입출금 원장 함수가 참조하는 SYSTEM 계정도 잔고 0으로 준비한다.
            bootstrap.asset(asset.id(), asset.symbol(), asset.koreanName(), asset.kind(), asset.scale());
            boolean same = jdbc.sql("SELECT symbol = ? AND kind = ? AND scale = ? FROM assets WHERE id = ?")
                    .params(asset.symbol(), asset.kind(), asset.scale(), asset.id()).query(Boolean.class).single();
            if (!same) {
                throw new IllegalStateException("기존 자산 ID가 초기 카탈로그와 다릅니다: " + asset.id());
            }
        }
        for (var market : InitialMarketCatalog.MARKETS) {
            jdbc.sql("""
                    INSERT INTO markets(id,symbol,base_asset_id,quote_asset_id,tick_size,lot_size,
                        min_notional,max_notional,max_qty,maker_fee_rate,taker_fee_rate)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING
                    """).params(market.id(), market.symbol(), market.base(), market.quote(),
                            new BigDecimal(market.tick()), new BigDecimal(market.lot()),
                            new BigDecimal(market.minNotional()), new BigDecimal(market.maxNotional()),
                            new BigDecimal(market.maxQty()), InitialMarketCatalog.FEE, InitialMarketCatalog.FEE)
                    .update();
            boolean same = jdbc.sql("SELECT symbol = ? AND base_asset_id = ? AND quote_asset_id = ? FROM markets WHERE id = ?")
                    .params(market.symbol(), market.base(), market.quote(), market.id()).query(Boolean.class).single();
            if (!same) {
                throw new IllegalStateException("기존 마켓 ID가 초기 카탈로그와 다릅니다: " + market.id());
            }
        }
    }
}

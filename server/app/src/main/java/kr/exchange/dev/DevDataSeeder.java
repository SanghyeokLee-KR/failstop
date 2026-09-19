package kr.exchange.dev;

import kr.exchange.api.auth.DemoUserProvisioner;
import kr.exchange.ledger.AccountBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static kr.exchange.config.InitialMarketCatalog.*;

/**
 * dev 기동 초기 데이터. 자산·마켓·데모 유저를 멱등 시드한다(기존 행은 skip).
 *
 * 심볼·한글명은 web/lib/sim/engine.ts SEEDS와 정합한다. 자산 scale은 DB 설계 정본
 * (BTC=8, KRW=0, ETH=18)을 따르고, 정본이 정하지 않은 자산은 web 표시 자리와 맞춘다.
 * tick/lot/notional은 web priceStep 관례에 맞춘 현실값이다(BTC tick 1000).
 * KRW는 quote scale 0이라 시장가 매수(notional, scale 18 전제)는 게이트웨이가 막는다.
 */
final class DevDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private DevDataSeeder() {
    }

    static void seed(AccountBootstrap bootstrap, DemoUserProvisioner demoUsers) {
        for (AssetSeed a : ASSETS) {
            bootstrap.asset(a.id(), a.symbol(), a.koreanName(), a.kind(), a.scale());
        }
        for (MarketSeed m : MARKETS) {
            bootstrap.market(m.id(), m.symbol(), m.base(), m.quote(),
                    new BigDecimal(m.tick()), new BigDecimal(m.lot()),
                    new BigDecimal(m.minNotional()), new BigDecimal(m.maxNotional()),
                    new BigDecimal(m.maxQty()), FEE, FEE);
        }
        DemoUserProvisioner.DemoUser demo = demoUsers.ensure();
        log.info("dev 시드 완료: assets {} markets {} demo_user external_id {}",
                ASSETS.size(), MARKETS.size(), demo.externalId());
    }
}

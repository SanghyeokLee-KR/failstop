package kr.exchange.sim;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 봇 플릿 파라미터. 기본값 근거:
 * - seed 42: 결정론 재현의 기준 시드. 같은 시드 = 같은 의사결정 열.
 * - targetOps 20: 전역 주문+취소 OPS. 데모 단일 노드에서 정산 지연 없이 소화 가능한 수준.
 * - MM spreadTicks 10, driftTicks 3: 스프레드가 드리프트보다 넓어 호가가 상시 유지되면서도
 *   fair value 이동으로 체결이 발생한다.
 * - taker lambda 2.0/s: MM 재호가 주기(500ms) 안에 평균 1건 이상 테이커가 도착해
 *   호가 소진과 재공급이 겹치는 정상 시장 형태를 만든다.
 *
 * markets: 다마켓 배치용. 비면 top-level market/initialPrice 단일 마켓으로 폴백한다(하위호환).
 */
@ConfigurationProperties(prefix = "exchange.sim")
public record SimProperties(
        String baseUrl,
        long seed,
        double targetOps,
        String market,
        String initialPrice,
        Funding funding,
        MarketMaker marketMaker,
        Taker taker,
        List<MarketSpec> markets
) {

    /**
     * 마켓별 봇 배치. 가격 규모·틱·로트가 마켓마다 달라 quoteSize(MM 호가 수량)·qtyStep
     * (taker 수량 스텝)·baseFunding(초기 재고)을 마켓별로 지정한다. null 필드는 top-level
     * MM/Taker/Funding 값으로 폴백한다.
     * trendCount·noiseCount는 TREND·NOISE 초기 목표 대수(r4). null이면 BotFleet가 taker 수에서
     * 파생한다. 파킹 게이트·캡으로 경계되므로 값은 활성 상한 힌트일 뿐이다.
     */
    public record MarketSpec(String symbol, String initialPrice, Integer mmCount,
                             Integer takerCount, String quoteSize, String qtyStep,
                             String baseFunding, Integer trendCount, Integer noiseCount) {
    }

    public record Funding(String quote, String base) {
        public Funding {
            if (quote == null) {
                quote = "1000000000";
            }
            if (base == null) {
                base = "100";
            }
        }
    }

    public record MarketMaker(int count, int spreadTicks, int driftTicks, String quoteSize,
                              long requoteMs) {
        public MarketMaker {
            if (count <= 0) {
                count = 2;
            }
            if (spreadTicks < 2) {
                spreadTicks = 10;
            }
            if (driftTicks <= 0) {
                driftTicks = 3;
            }
            if (quoteSize == null) {
                quoteSize = "0.01";
            }
            if (requoteMs <= 0) {
                requoteMs = 500;
            }
        }
    }

    public record Taker(int count, double lambda, String qtyStep, int maxSteps,
                        double marketRatio) {
        public Taker {
            if (count <= 0) {
                count = 2;
            }
            if (lambda <= 0) {
                lambda = 2.0;
            }
            if (qtyStep == null) {
                qtyStep = "0.001";
            }
            if (maxSteps <= 0) {
                maxSteps = 5;
            }
            if (marketRatio < 0 || marketRatio > 1) {
                marketRatio = 0.2;
            }
        }
    }

    public SimProperties {
        if (baseUrl == null) {
            baseUrl = "http://localhost:8080";
        }
        if (seed == 0) {
            seed = 42;
        }
        if (targetOps <= 0) {
            targetOps = 20;
        }
        if (market == null) {
            market = "KRW-BTC";
        }
        if (initialPrice == null) {
            initialPrice = "50000000";
        }
        if (funding == null) {
            funding = new Funding(null, null);
        }
        if (marketMaker == null) {
            marketMaker = new MarketMaker(0, 0, 0, null, 0);
        }
        if (taker == null) {
            taker = new Taker(0, 0, null, 0, -1);
        }
        if (markets == null) {
            markets = List.of();
        }
    }
}

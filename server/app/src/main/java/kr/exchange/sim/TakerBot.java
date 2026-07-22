package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * 테이커 봇. 지수 분포 도착(파라미터 lambda)으로 호가를 소비한다.
 * 매수는 최우선 매도호가를 지정가로 크로스하고, 매도는 marketRatio 확률로 시장가,
 * 그 외에는 최우선 매수호가 지정가 크로스다. 시장가 매수(notional)는 quote scale 18
 * 마켓 한정(§1.4 앱 가드)이라 기본 플릿에서는 사용하지 않는다.
 */
public class TakerBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TakerBot.class);

    private final String botId;
    private final BotApiClient client;
    private final SplittableRandom rng;
    private final SimProperties props;
    private final BooleanSupplier running;
    private final OpsGovernor governor;
    private final ObjectMapper mapper = new ObjectMapper();

    private long coidSeq;

    public TakerBot(String botId, BotApiClient client, long seed, SimProperties props,
                    BooleanSupplier running, OpsGovernor governor) {
        this.botId = botId;
        this.client = client;
        this.rng = new SplittableRandom(seed);
        this.props = props;
        this.running = running;
        this.governor = governor;
    }

    @Override
    public void run() {
        SimProperties.Taker taker = props.taker();
        while (running.getAsBoolean()) {
            try {
                Thread.sleep(interarrivalMs(taker.lambda()));
                JsonNode best = bestOfBook();
                if (best == null) {
                    continue;
                }
                boolean buy = rng.nextBoolean();
                BigDecimal qty = new BigDecimal(taker.qtyStep())
                        .multiply(BigDecimal.valueOf(1 + rng.nextInt(taker.maxSteps())));
                if (buy) {
                    String askPrice = best.path("ask_price").asText(null);
                    if (askPrice != null && !askPrice.isEmpty()) {
                        submitLimit("bid", askPrice, qty);
                    }
                } else {
                    String bidPrice = best.path("bid_price").asText(null);
                    if (bidPrice == null || bidPrice.isEmpty()) {
                        continue;
                    }
                    if (rng.nextDouble() < taker.marketRatio()) {
                        submitMarketSell(qty);
                    } else {
                        submitLimit("ask", bidPrice, qty);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("{} 테이커 주기 실패, 계속: {}", botId, e.toString());
            }
        }
    }

    private long interarrivalMs(double lambda) {
        double u = 1.0 - rng.nextDouble();
        long ms = (long) (-Math.log(u) * 1000.0 / lambda);
        return Math.min(Math.max(ms, 1), 5_000);
    }

    private JsonNode bestOfBook() {
        BotApiClient.Response response =
                client.getPublic("/api/v1/orderbook?market=" + props.market());
        if (response.status() != 200) {
            return null;
        }
        try {
            JsonNode units = mapper.readTree(response.body()).path("orderbook_units");
            return units.isArray() && units.size() > 0 ? units.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void submitLimit(String side, String price, BigDecimal qty) {
        if (!governor.acquire(running)) {
            return;
        }
        String body = "{\"market\":\"" + props.market() + "\",\"side\":\"" + side
                + "\",\"ord_type\":\"limit\",\"price\":\"" + price + "\",\"volume\":\""
                + qty.toPlainString() + "\",\"client_order_id\":\"" + botId + "-" + (++coidSeq)
                + "\"}";
        report(client.placeOrder(body));
    }

    private void submitMarketSell(BigDecimal qty) {
        if (!governor.acquire(running)) {
            return;
        }
        String body = "{\"market\":\"" + props.market()
                + "\",\"side\":\"ask\",\"ord_type\":\"market\",\"volume\":\"" + qty.toPlainString()
                + "\",\"client_order_id\":\"" + botId + "-" + (++coidSeq) + "\"}";
        report(client.placeOrder(body));
    }

    private void report(BotApiClient.Response response) {
        if (response.status() != 201 && response.status() != 200) {
            log.debug("{} 주문 거부 {}: {}", botId, response.status(), response.body());
        }
    }
}

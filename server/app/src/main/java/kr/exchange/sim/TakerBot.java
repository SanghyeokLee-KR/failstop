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
 * 방향은 effective bias와 확신 게인 kDir이 정하는 매수 확률로만 편향된다(설계 §2-3).
 * 확률만 바꾸므로 총 OPS·수량·정밀도 표면은 불변이다. bias는 NUMERIC 산술에 유입되지 않는다.
 * 프리셋. TAKER kDir 0.8, TREND kDir 1.0(강한 방향), NOISE kDir 0(방향 무시)+고분산.
 * 시장가 매수(notional)는 quote scale 18 마켓 한정(§1.4)이라 기본 플릿에서는 사용하지 않는다.
 */
public class TakerBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TakerBot.class);
    private static final long PARK_MS = 400;

    private final String botId;
    private final BotApiClient client;
    private final SplittableRandom rng;
    private final SimProperties props;
    private final double kDir;
    private final int stepMultiplier;
    private final BooleanSupplier running;
    private final OpsGovernor governor;
    private final BotFleetGovernor fleet;
    private final BotType type;
    private final int index;
    private final ObjectMapper mapper = new ObjectMapper();

    private long coidSeq;

    public TakerBot(String botId, BotApiClient client, long seed, SimProperties props,
                    double kDir, int stepMultiplier, BooleanSupplier running,
                    OpsGovernor governor, BotFleetGovernor fleet, BotType type, int index) {
        this.botId = botId;
        this.client = client;
        this.rng = new SplittableRandom(seed);
        this.props = props;
        this.kDir = kDir;
        this.stepMultiplier = Math.max(1, stepMultiplier);
        this.running = running;
        this.governor = governor;
        this.fleet = fleet;
        this.type = type;
        this.index = index;
    }

    /**
     * 매수 확률. p_buy = 0.5 + 0.5 * clamp(bias) * kDir. bias=+1에서도 kDir<1이면 양면성을
     * 남겨 한쪽 호가만 소진하며 가드까지 밀어붙이는 극단을 피한다(설계 §2-3).
     * (bias, kDir) 순수 함수다. 벽시계·추가 난수원 없음(도메인 B 재현성 §2-4).
     */
    public static double buyProbability(double bias, double kDir) {
        double clamped = Math.max(-1.0, Math.min(1.0, bias));
        double p = 0.5 + 0.5 * clamped * kDir;
        return Math.max(0.0, Math.min(1.0, p));
    }

    @Override
    public void run() {
        SimProperties.Taker taker = props.taker();
        while (running.getAsBoolean()) {
            try {
                if (fleet != null && !fleet.active(props.market(), type, index)) {
                    Thread.sleep(PARK_MS);
                    continue;
                }
                Thread.sleep(interarrivalMs(taker.lambda()));
                JsonNode best = bestOfBook();
                if (best == null) {
                    continue;
                }
                double bias = fleet == null ? 0.0 : fleet.effectiveBias(props.market());
                boolean buy = rng.nextDouble() < buyProbability(bias, kDir);
                int steps = 1 + rng.nextInt(taker.maxSteps() * stepMultiplier);
                BigDecimal qty = new BigDecimal(taker.qtyStep()).multiply(BigDecimal.valueOf(steps));
                if (buy) {
                    String askPrice = best.path("ask_price").asText(null);
                    if (askPrice == null || askPrice.isEmpty()) {
                        continue;
                    }
                    recordAggressor(true);
                    submitLimit("bid", askPrice, qty);
                } else {
                    String bidPrice = best.path("bid_price").asText(null);
                    if (bidPrice == null || bidPrice.isEmpty()) {
                        continue;
                    }
                    recordAggressor(false);
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

    private void recordAggressor(boolean buy) {
        if (fleet != null) {
            fleet.recordAggressor(props.market(), buy);
        }
    }

    private void report(BotApiClient.Response response) {
        if (response.status() == 201 || response.status() == 200) {
            if (fleet != null) {
                fleet.recordFill(props.market());
            }
            return;
        }
        log.debug("{} 주문 거부 {}: {}", botId, response.status(), response.body());
    }
}

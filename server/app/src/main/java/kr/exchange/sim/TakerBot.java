package kr.exchange.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * 테이커 봇. 지수 분포 도착(파라미터 lambda)으로 호가를 소비한다. 도착률은 레짐 배수(r13)로
 * 스케일되고, 크기는 멱함수형 분포 + 고래 확률(r6)로 꼬리를 갖는다. 낮은 확률로 같은 방향
 * 연속 스윕(버스트)을 낸다. 방향은 effective bias와 확신 게인 kDir이 정하는 매수 확률로만
 * 편향된다. 확률·크기만 바꾸므로 총 OPS 상한(governor)·정밀도 표면은 불변이다.
 * 프리셋. TAKER kDir 0.8, TREND kDir 1.0(강한 방향), NOISE kDir 0(방향 무시)+고분산.
 * 모든 의사결정 난수는 시드 SplittableRandom을 고정 순서로 소비한다(재현성).
 */
public class TakerBot implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TakerBot.class);
    private static final long PARK_MS = 400;
    // 크기 분포 파라미터(r6). sigma는 반정규 스케일, pWhale은 기본 고래 확률, whaleFactor는
    // 고래 배수, capMult는 baseCap 대비 절대 상한 배수. 관리자 조율 후보(#36).
    private static final double SIZE_SIGMA = 1.2;
    private static final double P_WHALE = 0.03;
    private static final double WHALE_FACTOR = 6.0;
    private static final int CAP_MULT = 8;
    // 버스트 파라미터(r13). 기본 확률과 최대 연속 수. 활성 레짐(lambdaMult)에서 확률이 커진다.
    private static final double P_BURST = 0.05;
    private static final int MAX_BURST = 3;
    private static final double MULT_PROB_CAP = 0.5;

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
     * 남겨 한쪽 호가만 소진하며 가드까지 밀어붙이는 극단을 피한다.
     * (bias, kDir) 순수 함수다. 벽시계·추가 난수원 없음.
     */
    public static double buyProbability(double bias, double kDir) {
        double clamped = Math.max(-1.0, Math.min(1.0, bias));
        double p = 0.5 + 0.5 * clamped * kDir;
        return Math.max(0.0, Math.min(1.0, p));
    }

    /**
     * 주문 크기 스텝(r6). 반정규 기본에 고래 확률로 배수를 얹어 우편향·두꺼운 꼬리를 만든다.
     * baseCap은 정상 크기 상한, cap은 고래 포함 절대 상한. 시드 rng를 고정 순서(가우시안 ->
     * 균등)로 소비하는 결정적 함수. 결과는 [1, cap] 정수 스텝.
     */
    static int sizeSteps(SplittableRandom rng, int baseCap, double sigma, double pWhale,
                         double whaleFactor, int cap) {
        double g = Math.abs(rng.nextGaussian());
        int base = 1 + (int) Math.floor(g * sigma);
        if (base > baseCap) {
            base = baseCap;
        }
        if (rng.nextDouble() < pWhale) {
            base = (int) Math.round(base * whaleFactor);
        }
        if (base < 1) {
            base = 1;
        }
        return Math.min(base, cap);
    }

    /**
     * 버스트 길이(r13). pBurst 확률로 [2, 1+maxBurst] 연속 스윕, 그 외 1(단발). 트리거 시 최소
     * 2라 실제 군집이 보장된다. 시드 rng를 고정 순서로 소비하는 결정적 함수.
     */
    static int burstCount(SplittableRandom rng, double pBurst, int maxBurst) {
        return rng.nextDouble() < pBurst ? 2 + rng.nextInt(maxBurst) : 1;
    }

    @Override
    public void run() {
        SimProperties.Taker taker = props.taker();
        int baseCap = taker.maxSteps() * stepMultiplier;
        int cap = baseCap * CAP_MULT;
        while (running.getAsBoolean()) {
            try {
                if (fleet != null && !fleet.active(props.market(), type, index)) {
                    Thread.sleep(PARK_MS);
                    continue;
                }
                double lambdaMult = fleet == null ? 1.0 : fleet.regimeLambdaMult(props.market());
                Thread.sleep(interarrivalMs(taker.lambda() * lambdaMult));
                JsonNode best = bestOfBook();
                if (best == null) {
                    continue;
                }
                double bias = fleet == null ? 0.0 : fleet.effectiveBias(props.market());
                boolean buy = rng.nextDouble() < buyProbability(bias, kDir);
                double pWhale = Math.min(MULT_PROB_CAP,
                        P_WHALE * (fleet == null ? 1.0 : fleet.regimePWhaleMult(props.market())));
                double pBurst = Math.min(MULT_PROB_CAP, P_BURST * lambdaMult);
                int burst = burstCount(rng, pBurst, MAX_BURST);

                for (int b = 0; b < burst && running.getAsBoolean(); b++) {
                    if (b > 0) {
                        best = bestOfBook();
                        if (best == null) {
                            break;
                        }
                    }
                    int steps = sizeSteps(rng, baseCap, SIZE_SIGMA, pWhale, WHALE_FACTOR, cap);
                    BigDecimal qty =
                            new BigDecimal(taker.qtyStep()).multiply(BigDecimal.valueOf(steps));
                    if (!sweep(buy, best, qty, taker)) {
                        break;
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

    /** 한 방향 소비 1건. 호가 없으면 false로 버스트를 끊는다. sell 시장/지정 선택은 marketRatio. */
    private boolean sweep(boolean buy, JsonNode best, BigDecimal qty, SimProperties.Taker taker) {
        if (buy) {
            String askPrice = best.path("ask_price").asText(null);
            if (askPrice == null || askPrice.isEmpty()) {
                return false;
            }
            recordAggressor(true);
            submitLimit("bid", askPrice, qty);
            return true;
        }
        String bidPrice = best.path("bid_price").asText(null);
        if (bidPrice == null || bidPrice.isEmpty()) {
            return false;
        }
        recordAggressor(false);
        if (rng.nextDouble() < taker.marketRatio()) {
            submitMarketSell(qty);
        } else {
            submitLimit("ask", bidPrice, qty);
        }
        return true;
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
